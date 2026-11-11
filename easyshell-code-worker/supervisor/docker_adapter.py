"""Fixed Docker HTTP API subset; no CLI, Exec, logs, or arbitrary command/API."""

import http.client
import os
import re
import socket
import stat
import time
import threading
import urllib.parse
from dataclasses import dataclass, replace

from .control import (MAX_REQUEST, MAX_HARD_SECONDS, NotFound, Rejected, Snapshot, decode_json, owned, policy, verify)
from .attach import Attach


MAX_DOCKER_RESPONSE = 1024 * 1024
API = "/v1.45"


@dataclass(frozen=True)
class Config:
    socket_path: str
    daemon_id: str
    image: str
    profile: str = "refusal"

    def __post_init__(self):
        if self.profile not in ("refusal", "execution", "resourceprobe"):
            raise Rejected("PROFILE_UNVERIFIED")
        # Dedicated host-managed directory, never the system Docker socket or its symlink.
        if not re.fullmatch(r"/run/easyshell-code/[A-Za-z0-9_-]+/docker\.sock", self.socket_path):
            raise Rejected("DEDICATED_SOCKET_REQUIRED")
        if not re.fullmatch(r"[A-Za-z0-9:._-]{8,128}", self.daemon_id):
            raise Rejected("DAEMON_ID_REQUIRED")
        if not (re.fullmatch(r"sha256:[0-9a-f]{64}", self.image)
                or re.fullmatch(r"[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}", self.image)):
            raise Rejected("IMAGE_DIGEST_REQUIRED")

    @classmethod
    def from_environment(cls):
        try:
            return cls(os.environ["EASYSHELL_CODE_SOCKET"], os.environ["EASYSHELL_CODE_DAEMON_ID"],
                       os.environ["EASYSHELL_CODE_IMAGE"])
        except KeyError:
            raise Rejected("CONFIG_UNVERIFIED") from None


class UnixConnection(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__("localhost", timeout=0.5)
        self.path = path
        self.absolute_deadline = None

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        remaining = self.timeout if self.absolute_deadline is None else self.absolute_deadline - time.monotonic()
        if remaining <= 0:
            raise Rejected("TIMEOUT")
        self.sock.settimeout(min(self.timeout, remaining))
        self.sock.connect(self.path)


class DockerAdapter:
    def __init__(self, config, clock=time.monotonic, guard=None):
        self.config = config
        self.clock = clock
        self.guard = guard
        self.records = {}
        self.verified = False
        self.deadline = None
        self.journal = None
        self.reaper_session = None
        self.cleanup_claim = None

    def set_cleanup_claim(self, record, token):
        self.cleanup_claim = None if record is None else (record, token)

    def _cleanup_fence(self):
        if self.config.profile != "refusal":
            if self.journal is None or self.cleanup_claim is None:
                raise Rejected("CLEANUP_LEASE_LOST")
            self.journal.cleanup_fence(*self.cleanup_claim)

    def set_deadline(self, deadline):
        self.deadline = deadline

    def authorize(self, journal, record, deadline):
        from .reaper import require_live
        if not 0 < deadline - self.clock() <= MAX_HARD_SECONDS:
            raise Rejected("LEASE_UNVERIFIED")
        if not self.reaper_session:
            raise Rejected("REAPER_UNVERIFIED")
        require_live(journal, self.reaper_session)
        self.journal = journal
        with journal.db:
            journal.db.execute("INSERT OR REPLACE INTO leases VALUES (?, ?, ?)",
                               (record.request_id, self.reaper_session, deadline))

    def confirm_empty(self, record):
        if self.config.profile != "refusal":
            if self.journal is None:
                raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
            row = self.journal.db.execute("SELECT nonce,container_id,start_attempted,no_start_verified "
                                          "FROM lifecycle WHERE request_id=?", (record.request_id,)).fetchone()
            if row == (record.nonce, record.container_id, 0, 1):
                return
            if self.guard is None:
                raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
            self.guard.confirm_empty(record.container_id, self.journal)

    def prepare_cleanup_evidence(self, record, raw):
        """Called under cleanup CAS ownership. No guest input or guest-reported evidence."""
        from .control import verify
        state = verify(raw, record, self.config.image)
        self._cleanup_fence()
        if state["Status"] == "created" and state["Pid"] == 0:
            with self.journal.db:
                self.journal.db.execute("BEGIN IMMEDIATE")
                self.journal.cleanup_fence(*self.cleanup_claim)
                row = self.journal.db.execute("SELECT start_attempted FROM lifecycle WHERE request_id=?",
                                              (record.request_id,)).fetchone()
                if row is None:
                    self.journal.db.execute("INSERT INTO lifecycle VALUES (?,?,?,0,1)",
                                            (record.request_id, record.nonce, record.container_id))
                elif row[0] == 0:
                    self.journal.db.execute("UPDATE lifecycle SET no_start_verified=1 WHERE request_id=? "
                                            "AND nonce=? AND container_id=?",
                                            (record.request_id, record.nonce, record.container_id))
                else:
                    raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
            return
        guard = self.journal.db.execute("SELECT 1 FROM guards WHERE container_id=?",
                                        (record.container_id,)).fetchone()
        if guard is None:
            if self.guard is None or state["Running"] is not True:
                raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
            deadline = self.deadline or self.clock() + 2
            self.guard.verify(raw, deadline)
            self.guard.save(raw, self.journal)
            self.guard.verify(raw, deadline)

    def _socket_check(self):
        if os.path.realpath(self.config.socket_path) != self.config.socket_path:
            raise Rejected("DEDICATED_SOCKET_REQUIRED")
        info = os.stat(self.config.socket_path, follow_symlinks=False)
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != 0 or info.st_mode & stat.S_IWOTH:
            raise Rejected("SOCKET_UNVERIFIED")

    def _http(self, method, route, body=None, missing=False):
        # All routes are constructed by the fixed methods below, never request data.
        import json
        self._socket_check()
        payload = None if body is None else json.dumps(body).encode()
        if payload is not None and len(payload) > MAX_REQUEST:
            raise Rejected("DOCKER_REQUEST_TOO_LARGE")
        connection = UnixConnection(self.config.socket_path)
        end = min(self.deadline, self.clock() + 2) if self.deadline is not None else self.clock() + 2
        remaining = end - self.clock()
        if remaining <= 0:
            raise Rejected("TIMEOUT")
        connection.absolute_deadline = time.monotonic() + remaining

        def expire():
            if connection.sock is not None:
                try:
                    connection.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

        timer = threading.Timer(remaining, expire)
        timer.daemon = True
        timer.start()
        try:
            connection.request(method, API + route, payload, {"Content-Type": "application/json"})
            response = connection.getresponse()
            data = response.read(MAX_DOCKER_RESPONSE + 1)
            if self.clock() >= end:
                raise Rejected("TIMEOUT")
            if len(data) > MAX_DOCKER_RESPONSE:
                raise Rejected("DOCKER_RESPONSE_TOO_LARGE")
            if response.status == 404 and missing:
                raise NotFound()
            if response.status not in (200, 201, 204):
                raise Rejected("DOCKER_OPERATION_FAILED")
            return decode_json(data, MAX_DOCKER_RESPONSE) if data else None
        finally:
            timer.cancel()
            connection.close()

    def _identity(self):
        info = self._http("GET", "/info")
        security = info.get("SecurityOptions", [])
        if (info.get("ID") != self.config.daemon_id or info.get("CgroupVersion") != "2"
                or info.get("CgroupDriver") != "systemd"
                or "name=seccomp,profile=builtin" not in security
                or "name=apparmor" not in security):
            self.verified = False
            raise Rejected("DAEMON_UNVERIFIED")

    def preflight(self, records):
        self.verified = False
        self._identity()
        if any(record.profile != self.config.profile for record in records):
            raise Rejected("PROFILE_UNVERIFIED")
        self.records = {record.name: record for record in records}
        containers = self._http("GET", "/containers/json?all=1&limit=1025")
        if not isinstance(containers, list) or len(containers) > 1024:
            raise Rejected("DAEMON_NOT_DEDICATED")
        for item in containers:
            names = item.get("Names", [])
            if len(names) != 1 or names[0].removeprefix("/") not in self.records:
                raise Rejected("DAEMON_NOT_DEDICATED")
            record = self.records[names[0].removeprefix("/")]
            raw = self._http("GET", "/containers/" + record.name + "/json", missing=True)
            cid = owned(raw, record)
            if item.get("Id") != cid:
                raise Rejected("OWNERSHIP_UNVERIFIED")
        self.verified = True

    def _guard(self):
        if not self.verified:
            raise Rejected("ISOLATION_UNVERIFIED")
        self._identity()

    def create(self, name, spec):
        self._guard()
        record = self.records.get(name)
        if record is None or spec != policy(self.config.image, record):
            raise Rejected("POLICY_UNVERIFIED")
        if self.journal is not None and self.journal.cancellation_requested(record):
            raise Rejected("CANCELLED")
        result = self._http("POST", "/containers/create?name=" + urllib.parse.quote(name, safe=""), spec)
        if not isinstance(result, dict) or result.get("Warnings") not in (None, []):
            raise Rejected("CREATE_UNCERTAIN")
        cid = result.get("Id")
        if not isinstance(cid, str) or not re.fullmatch(r"[0-9a-f]{64}", cid):
            raise Rejected("CREATE_UNCERTAIN")
        self.records[name] = replace(record, container_id=cid)
        if self.journal is not None:
            with self.journal.db:
                self.journal.db.execute("INSERT INTO lifecycle VALUES (?,?,?,0,0)",
                                        (record.request_id, record.nonce, cid))
        return cid

    def _record(self, target):
        for record in self.records.values():
            if target in (record.name, record.container_id):
                return record
        # Container IDs created after preflight can only be matched by inspecting known names.
        for record in self.records.values():
            try:
                raw = self._http("GET", "/containers/" + record.name + "/json", missing=True)
            except NotFound:
                continue
            if owned(raw, record) == target:
                self.records[record.name] = replace(record, container_id=target)
                return record
        raise Rejected("OWNERSHIP_UNVERIFIED")

    def inspect(self, target):
        self._guard()
        record = self._record(target)
        identifier = record.container_id or record.name
        raw = self._http("GET", "/containers/" + identifier + "/json", missing=True)
        owned(raw, record)
        return Snapshot(raw)

    def _created(self, target, deadline):
        if self.clock() >= deadline:
            raise Rejected("TIMEOUT")
        self._guard()
        record = self._record(target)
        raw = self.inspect(target).raw
        if owned(raw, record) != target or verify(raw, record, self.config.image)["Status"] != "created":
            raise Rejected("STATE_UNVERIFIED")
        if self.clock() >= deadline:
            raise Rejected("TIMEOUT")

    def start(self, target, deadline):
        self._created(target, deadline)
        if self.journal is not None and self.journal.cancellation_requested(self._record(target)):
            raise Rejected("CANCELLED")
        if self.config.profile != "refusal":
            record = self._record(target)
            with self.journal.db:
                self.journal.db.execute("BEGIN IMMEDIATE")
                control = self.journal.db.execute("SELECT cleanup_until,verified,cancel_requested FROM "
                                                  "task_controls WHERE request_id=?", (record.request_id,)).fetchone()
                if control and (control[0] > time.monotonic() or control[1] or control[2]):
                    raise Rejected("CANCELLED")
                updated = self.journal.db.execute("UPDATE lifecycle SET start_attempted=1 "
                                                  "WHERE request_id=? AND nonce=? AND container_id=? "
                                                  "AND no_start_verified=0 AND start_attempted=0",
                                                  (record.request_id, record.nonce, target))
                if updated.rowcount != 1:
                    raise Rejected("STATE_UNVERIFIED")
        # Fixed entrypoint is already validated. No command or configuration accepted here.
        self._http("POST", "/containers/" + target + "/start")
        if self.clock() >= deadline:
            raise Rejected("TIMEOUT")

    def verify_limits(self, target, deadline):
        if self.config.profile == "refusal" or self.guard is None:
            raise Rejected("HOST_LIMITS_UNVERIFIED")
        from .reaper import require_live
        require_live(self.journal, self.reaper_session)
        record = self._record(target)
        raw = self.inspect(target).raw
        verify(raw, record, self.config.image)
        self.guard.verify(raw, deadline)
        self.guard.save(raw, self.journal)
        self.guard.verify(raw, deadline)

    def _open_attach(self, route, deadline):
        self._socket_check()
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        channel = Attach(sock, deadline, self.clock)
        try:
            channel.remaining()
            sock.connect(self.config.socket_path)
            channel.handshake(API + route)
            return channel
        except Exception:
            channel.close()
            raise

    def attach(self, target, deadline):
        self._created(target, deadline)
        return self._open_attach("/containers/" + target
                                 + "/attach?stream=1&stdin=1&stdout=1&stderr=1&logs=0", deadline)

    def kill(self, target):
        self._guard()
        record = self._record(target)
        cid = owned(self.inspect(record.name).raw, record)
        if target != cid:
            raise Rejected("OWNERSHIP_UNVERIFIED")
        self._cleanup_fence()
        self._http("POST", "/containers/" + cid + "/kill?signal=SIGKILL")

    def remove(self, target):
        self._guard()
        record = self._record(target)
        raw = self.inspect(record.name).raw
        cid = owned(raw, record)
        state = raw.get("State", {})
        if target != cid or state.get("Running") is not False or state.get("Pid") != 0:
            raise Rejected("CLEANUP_UNVERIFIED")
        self._cleanup_fence()
        self._http("DELETE", "/containers/" + cid + "?force=false&v=false")
