"""Bounded local RPC. Runtime imports occur only in the orchestrator CLI factory.

The dedicated control UID is a trusted deputy for the configured service DNS
identities. Peer credentials, not an untrusted owner string, grant that authority.
No runtime objects, credentials or runtime socket paths enter UnixRPCBackend.
"""

import argparse
import concurrent.futures
import json
import math
import os
import re
import socket
import sqlite3
import stat
import struct
import threading
import time
import uuid
import fcntl

from .control import MAX_REQUEST, decode_json, fingerprint, request

FRAME_LIMIT = 128 * 1024
CONTENT_LIMIT = 60 * 1024
REPLAY_SECONDS = 86400


class DeadlineRuntime:
    """Cap existing adapter IO and reaper lease without changing the core."""
    def __init__(self, adapter, deadline, service=None):
        self.adapter, self.deadline = adapter, deadline
        self.service = service

    def __getattr__(self, name):
        return getattr(self.adapter, name)

    def set_deadline(self, deadline):
        self.adapter.set_deadline(min(deadline, self.deadline))

    def authorize(self, journal, record, deadline):
        result = self.adapter.authorize(journal, record, min(deadline, self.deadline))
        if self.service is not None:
            with self.service.lock:
                row = self.service.db.execute("SELECT cancel_requested FROM rpc_tasks WHERE id=?", (record.request_id,)).fetchone()
            if row and row[0] and self.service.cancellation is not None:
                # Close claim-to-lease handoff gap: intent may have arrived
                # before core request_cancel could see its matching lease.
                self.service.cancellation(record.request_id, record.fingerprint,
                                          min(self.deadline, self.service.clock() + 3))
        return result

    def attach(self, target, deadline):
        return self.adapter.attach(target, min(deadline, self.deadline))

    def start(self, target, deadline):
        return self.adapter.start(target, min(deadline, self.deadline))

    def verify_limits(self, target, deadline):
        return self.adapter.verify_limits(target, min(deadline, self.deadline))


class RPCError(Exception):
    pass


class ClaimJournal:
    """Serialize cancellation intent with core claim, without sharing its DB."""
    def __init__(self, journal, service):
        self.journal, self.service = journal, service

    def __getattr__(self, name):
        return getattr(self.journal, name)

    def claim(self, value, profile="refusal"):
        from .control import Rejected
        with self.service.lock:
            row = self.service.db.execute("SELECT cancel_requested FROM rpc_tasks WHERE id=?",
                                          (value["requestId"],)).fetchone()
            if row and row[0]:
                raise Rejected("CANCEL_PENDING")
            record = self.journal.claim(value, profile)
            with self.service.db:
                self.service.db.execute("UPDATE rpc_tasks SET handoff_state='accepted' WHERE id=? AND handoff_state='claiming'",
                                        (value["requestId"],))
            return record


class CoreCancellation:
    """Open a separate Journal per cancel. Receipt verifier is fail-closed.

    receipt_verifier(journal, id, fingerprint, session) must validate the future
    core's persistent nonce/container/guard cleanup receipt; absent integration
    never confirms cleanup. No state/name inferred from RPC metadata suffices.
    """
    def __init__(self, journal_factory, session, receipt_verifier=None):
        self.journal_factory, self.session = journal_factory, session
        self.receipt_verifier = receipt_verifier

    def receipt(self, request_id, digest):
        journal = self.journal_factory()
        try:
            receipt = journal.cancel_receipt(request_id, digest, self.session)
            return (isinstance(receipt, dict) and receipt.get("cleanupVerified") is True
                    and receipt.get("terminalReason") == "cancelled")
        finally:
            journal.close()

    def __call__(self, request_id, digest, deadline):
        journal = self.journal_factory()
        try:
            journal.request_cancel(request_id, digest, self.session)
            if self.receipt_verifier is None:
                waiter = getattr(journal, "wait_cancel", None)
                if waiter is None:
                    return False
                receipt = waiter(request_id, digest, self.session,
                                 max(0, min(3, deadline - time.monotonic())))
                return (isinstance(receipt, dict) and receipt.get("cleanupVerified") is True
                        and receipt.get("terminalReason") == "cancelled")
            while self.receipt_verifier is not None and time.monotonic() < deadline:
                if self.receipt_verifier(journal, request_id, digest, self.session) is True:
                    return True
                threading.Event().wait(min(0.025, max(0, deadline - time.monotonic())))
            return False
        finally:
            journal.close()


def encode(value):
    data = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
    if not 0 < len(data) <= FRAME_LIMIT:
        raise RPCError("FRAME_TOO_LARGE")
    return data


def remaining(deadline):
    value = deadline - time.monotonic()
    if not 0 < value <= 25:
        raise RPCError("DEADLINE_INVALID")
    return value


def receive(sock, deadline):
    def exact(size):
        parts = bytearray()
        while len(parts) < size:
            sock.settimeout(remaining(deadline))
            part = sock.recv(size - len(parts))
            if not part:
                raise RPCError("IO_UNCERTAIN")
            parts.extend(part)
        return bytes(parts)
    size = struct.unpack("!I", exact(4))[0]
    if not 0 < size <= FRAME_LIMIT:
        raise RPCError("FRAME_TOO_LARGE")
    value = decode_json(exact(size), FRAME_LIMIT)
    encode(value)  # Reject non-finite numbers, including exponent overflow.
    return value


def send(sock, value, deadline):
    data = encode(value)
    sock.settimeout(remaining(deadline))
    sock.sendall(struct.pack("!I", len(data)) + data)


def peer_uid(sock):
    return struct.unpack("3i", sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED,
                                              struct.calcsize("3i")))[1]


def private_path(path, uid, *, require_socket=True):
    if not os.path.isabs(path) or os.path.realpath(path) != path:
        raise RPCError("PATH_UNVERIFIED")
    parent = os.stat(os.path.dirname(path), follow_symlinks=False)
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid != uid
            or stat.S_IMODE(parent.st_mode) != 0o700):
        raise RPCError("PARENT_UNVERIFIED")
    if require_socket:
        info = os.stat(path, follow_symlinks=False)
        if (not stat.S_ISSOCK(info.st_mode) or info.st_uid != uid
                or stat.S_IMODE(info.st_mode) != 0o600):
            raise RPCError("SOCKET_UNVERIFIED")


class UnixRPCBackend:
    """Control-side client: exactly one attempt, no runtime adapter import."""

    def __init__(self, path, *, control_uid, orchestrator_uid, owners):
        if any(type(uid) is not int or uid < 0 for uid in (control_uid, orchestrator_uid)) or not owners:
            raise ValueError("explicit UIDs and owners required")
        self.path, self.control_uid = path, control_uid
        self.orchestrator_uid, self.owners = orchestrator_uid, frozenset(owners)

    def _call(self, owner, operation, deadline, **fields):
        if owner not in self.owners or os.geteuid() != self.control_uid:
            raise RPCError("PEER_DENIED")
        remaining(deadline)
        private_path(self.path, self.control_uid)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(remaining(deadline))
            sock.connect(self.path)
            if peer_uid(sock) != self.orchestrator_uid:
                raise RPCError("SERVER_PEER_DENIED")
            send(sock, {"op": operation, "owner": owner, "deadline": deadline, **fields}, deadline)
            response = receive(sock, deadline)
        if not isinstance(response, dict) or response.get("ok") is not True or set(response) != {"ok", "value"}:
            raise RPCError("RPC_REJECTED")
        return response["value"]

    def execute(self, owner, value, deadline):
        response = self._call(owner, "execute", deadline, request=value)
        if (isinstance(response, dict) and response.get("status") == "cancelled"
                and response.get("cleanupVerified") is True):
            return {"status": "failed", "error": "CANCELLED"}
        if not isinstance(response, dict) or "result" not in response:
            raise RPCError("RESULT_UNAVAILABLE")
        return response["result"]

    def status(self, owner, request_id, deadline):
        return self._call(owner, "status", deadline, requestId=request_id)

    def cancel(self, owner, request_id, deadline):
        response = self._call(owner, "cancel", deadline, requestId=request_id)
        return isinstance(response, dict) and response.get("cleanupVerified") is True

    def health(self, owner, deadline):
        return self._call(owner, "health", deadline)


class Orchestrator:
    """One runtime worker, no execution queue; metadata journal has no content.

    factory constructs runtime/core journal on the worker that owns their SQLite
    connection. It returns (adapter, journal, supervisor, require_live).
    """

    def __init__(self, metadata, *, allowed_uid, owners, factory, allow_root=False,
                 capacity=1024, clock=time.monotonic, wall_clock=time.time, cancellation=None):
        if (type(allowed_uid) is not int or allowed_uid < 0 or (allowed_uid == 0 and not allow_root)
                or not owners or capacity < 1
                or any(not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", x) for x in owners)):
            raise ValueError("dedicated control UID and fixed service identities required")
        self.allowed_uid, self.owners, self.capacity = allowed_uid, frozenset(owners), capacity
        self.factory, self.clock = factory, clock
        self.wall_clock = wall_clock
        self.cancellation = cancellation
        self.health_lock = threading.Lock()
        self.health_future = None
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="orchestrator")
        self.slot, self.lock = threading.Lock(), threading.RLock()
        self.runtime = None
        self.last_verified = None
        self.results = {}
        self.db = sqlite3.connect(metadata, check_same_thread=False)
        self.db.execute("PRAGMA max_page_count=1024")
        self.db.execute("CREATE TABLE IF NOT EXISTS rpc_tasks "
                        "(id TEXT PRIMARY KEY, owner TEXT, hash TEXT, status TEXT)")
        if "cleaned_at" not in {row[1] for row in self.db.execute("PRAGMA table_info(rpc_tasks)")}:
            self.db.execute("ALTER TABLE rpc_tasks ADD COLUMN cleaned_at REAL")
        if "cancel_requested" not in {row[1] for row in self.db.execute("PRAGMA table_info(rpc_tasks)")}:
            self.db.execute("ALTER TABLE rpc_tasks ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0")
        columns = {row[1] for row in self.db.execute("PRAGMA table_info(rpc_tasks)")}
        if "handoff_state" not in columns:
            self.db.execute("ALTER TABLE rpc_tasks ADD COLUMN handoff_state TEXT NOT NULL DEFAULT 'legacy_unknown'")
        if "cancel_reason" not in columns:
            self.db.execute("ALTER TABLE rpc_tasks ADD COLUMN cancel_reason TEXT")
        with self.db:
            self.db.execute("UPDATE rpc_tasks SET status='unknown' WHERE cleaned_at IS NULL")
            self.db.execute("UPDATE rpc_tasks SET handoff_state='legacy_unknown' WHERE cleaned_at IS NULL")

    def close(self):
        def close_runtime():
            if self.runtime is not None:
                self.runtime[1].close()
        self.pool.submit(close_runtime).result()
        self.pool.shutdown(wait=True)
        self.db.close()

    def _state(self, request_id, state):
        with self.db:
            self.db.execute("UPDATE rpc_tasks SET status=? WHERE id=?", (state, request_id))

    def _view(self, owner, request_id):
        row = self.db.execute("SELECT owner,hash,status FROM rpc_tasks WHERE id=?", (request_id,)).fetchone()
        if not row:
            raise RPCError("NOT_FOUND")
        if row[0] != owner:
            raise RPCError("OWNER_DENIED")
        value = {"requestId": request_id, "status": row[2]}
        if row[2] == "cancelled":
            value["cleanupVerified"] = True
        cached = self.results.get(request_id)
        if cached and 0 <= self.clock() - cached[0] < 60:
            value["result"] = cached[1]
        return row, value

    def handle(self, uid, message):
        if uid != self.allowed_uid:
            raise RPCError("PEER_DENIED")
        if not isinstance(message, dict):
            raise RPCError("INVALID_REQUEST")
        op, owner, deadline = (message.get(k) for k in ("op", "owner", "deadline"))
        if (type(deadline) not in (int, float) or not math.isfinite(deadline)
                or not 0 < deadline - self.clock() <= 25 or owner not in self.owners):
            raise RPCError("AUTH_OR_DEADLINE_DENIED")
        fields = {"op", "owner", "deadline"}
        if op == "execute":
            fields.add("request")
        elif op in ("status", "cancel"):
            fields.add("requestId")
        elif op != "health":
            raise RPCError("INVALID_OPERATION")
        if set(message) != fields:
            raise RPCError("INVALID_FIELDS")
        if op in ("status", "cancel"):
            request_id = message["requestId"]
            if not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", request_id):
                raise RPCError("INVALID_ID")
            with self.lock:
                row, value = self._view(owner, request_id)
                if op == "cancel":
                    if row[2] == "cancelled":
                        return {**value, "cleanupVerified": True, "cancelPending": False}
                    if row[2] in ("succeeded", "failed"):
                        return {**value, "cleanupVerified": False, "cancelPending": False}
                    with self.db:
                        self.db.execute("UPDATE rpc_tasks SET cancel_requested=1 WHERE id=?", (request_id,))
                        changed = self.db.execute(
                            "UPDATE rpc_tasks SET handoff_state='cancelled_unstarted',status='cancelled',"
                            "cancel_reason='no_submission',cleaned_at=? WHERE id=? AND handoff_state='not_started'",
                            (self.wall_clock(), request_id)).rowcount
                    if changed:
                        return {"requestId": request_id, "status": "cancelled", "cleanupVerified": True,
                                "cancelPending": False}
                else:
                    pending = self.db.execute("SELECT cancel_requested FROM rpc_tasks WHERE id=?", (request_id,)).fetchone()[0]
                    if not pending or row[2] in ("succeeded", "failed", "cancelled"):
                        return {**value, "cleanupVerified": row[2] == "cancelled"}
            if op == "status":
                verified = False
                reader = getattr(self.cancellation, "receipt", None)
                if reader is not None:
                    try:
                        verified = reader(request_id, row[1]) is True
                    except Exception:
                        pass
                if verified:
                    with self.lock:
                        self._state(request_id, "cancelled")
                        with self.db:
                            self.db.execute("UPDATE rpc_tasks SET cleaned_at=? WHERE id=?", (self.wall_clock(), request_id))
                    return {"requestId": request_id, "status": "cancelled", "cleanupVerified": True}
                return {"requestId": request_id, "status": "cancel_pending", "cleanupVerified": False}
            verified = False
            if self.cancellation is not None:
                try:
                    verified = self.cancellation(request_id, row[1], min(deadline, self.clock() + 3)) is True
                except Exception:
                    pass
            if verified:
                with self.lock:
                    self._state(request_id, "cancelled")
                    with self.db:
                        self.db.execute("UPDATE rpc_tasks SET cleaned_at=? WHERE id=?", (self.wall_clock(), request_id))
            return {**value, "cleanupVerified": verified, "cancelPending": not verified}
        if op == "health":
            deadline = min(deadline, self.clock() + 10)
            with self.health_lock:
                future = self.health_future
                if future is None or future.done():
                    if not self.slot.acquire(blocking=False):
                        raise RPCError("BUSY")
                    def health_work():
                        try:
                            return self._health(deadline)
                        finally:
                            self.slot.release()
                    future = self.pool.submit(health_work)
                    self.health_future = future
                    joined = False
                else:
                    joined = True
            value = future.result(timeout=max(0, deadline - self.clock()))
            if joined:
                return self._work(self._health, deadline)
            return value
        raw = encode(message["request"])
        if len(raw) > CONTENT_LIMIT:
            raise RPCError("INPUT_TOO_LARGE")
        value = request(raw)
        # Keep the trusted self-test namespace out of user-controlled execution.
        if value["requestId"].startswith("rpc-health-"):
            raise RPCError("RESERVED_ID")
        digest, request_id = fingerprint(value), value["requestId"]
        with self.lock:
            expired = self.db.execute("SELECT 1 FROM rpc_tasks WHERE cleaned_at IS NOT NULL AND cleaned_at<=? LIMIT 1",
                                      (self.wall_clock() - REPLAY_SECONDS,)).fetchone()
        if expired:
            self._work(lambda end: self._configured(end) is not None, deadline)
        with self.lock:
            for key, (stamp, _) in list(self.results.items()):
                if self.clock() - stamp >= 60:
                    del self.results[key]
            row = self.db.execute("SELECT owner,hash FROM rpc_tasks WHERE id=?", (request_id,)).fetchone()
            if row:
                self._view(owner, request_id)
                if row[1] != digest:
                    raise RPCError("REQUEST_ID_CONFLICT")
                return self._view(owner, request_id)[1]
            if self.db.execute("SELECT count(*) FROM rpc_tasks").fetchone()[0] >= self.capacity:
                raise RPCError("JOURNAL_FULL")
            if not self.slot.acquire(blocking=False):
                raise RPCError("BUSY")
            try:
                with self.db:
                    self.db.execute("INSERT INTO rpc_tasks (id,owner,hash,status,handoff_state) VALUES (?,?,?,'running','not_started')", (request_id, owner, digest))
                future = self.pool.submit(self._execute, owner, value, deadline)
            except Exception:
                self.slot.release()
                raise
        try:
            return future.result(timeout=max(0, deadline - self.clock()))
        except concurrent.futures.TimeoutError:
            raise RPCError("IO_UNCERTAIN") from None

    def _work(self, operation, deadline):
        if not self.slot.acquire(blocking=False):
            raise RPCError("BUSY")
        def run():
            try:
                return operation(deadline)
            finally:
                self.slot.release()
        future = self.pool.submit(run)
        try:
            return future.result(timeout=max(0, deadline - self.clock()))
        except concurrent.futures.TimeoutError:
            raise RPCError("IO_UNCERTAIN") from None

    def _configured(self, deadline):
        if self.runtime is None:
            self.runtime = self.factory()
        adapter, journal, supervisor, require_live = self.runtime
        if (adapter.config.profile != "execution" or supervisor.profile != "execution"
                or supervisor.image != adapter.config.image or adapter.guard is None
                or not adapter.reaper_session):
            raise RPCError("CONFIG_UNVERIFIED")
        adapter.set_deadline(deadline)
        require_live(journal, adapter.reaper_session)
        adapter.preflight(journal.pending())
        # Retire only exact, receipt-confirmed RPC records after the fixed replay
        # window. Never sweep core journal rows by prefix or touch pending rows.
        if hasattr(journal, "db"):
            with self.lock:
                expired = self.db.execute("SELECT id,hash FROM rpc_tasks WHERE cleaned_at IS NOT NULL AND cleaned_at<=?",
                                          (self.wall_clock() - REPLAY_SECONDS,)).fetchall()
                for request_id, digest in expired:
                    self._retire_core(journal, request_id, digest)
                    with self.db:
                        self.db.execute("DELETE FROM rpc_tasks WHERE id=? AND cleaned_at IS NOT NULL", (request_id,))
        return adapter, journal, supervisor, require_live

    @staticmethod
    def _retire_core(journal, request_id, digest):
        with journal.db:
            row = journal.db.execute("SELECT container_id FROM tasks WHERE request_id=? AND fingerprint=? AND state='terminal'",
                                     (request_id, digest)).fetchone()
            if row is None:
                return
            tables = {item[0] for item in journal.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            for table in ("leases", "task_controls"):
                if table in tables:
                    journal.db.execute("DELETE FROM " + table + " WHERE request_id=?", (request_id,))
            if row[0] and "guards" in tables:
                journal.db.execute("DELETE FROM guards WHERE container_id=?", (row[0],))
            journal.db.execute("DELETE FROM tasks WHERE request_id=? AND fingerprint=? AND state='terminal'", (request_id, digest))

    def _run(self, value, deadline, *, probe=False):
        adapter, journal, supervisor, require_live = self._configured(deadline)
        if not probe:
            with self.lock:
                row = self.db.execute("SELECT cancel_requested FROM rpc_tasks WHERE id=?", (value["requestId"],)).fetchone()
                if row and row[0]:
                    raise RPCError("CANCEL_PENDING")
        # Core creates its own execution deadline. Require enough remaining RPC
        # budget for the whole core budget plus bounded cleanup; never shorten
        # guest timeout or dispatch work whose RPC lease already expired.
        if not probe and deadline - self.clock() < 15 + value["timeoutMs"] / 1000 + 1:
            raise RPCError("INSUFFICIENT_DEADLINE")
        original = getattr(supervisor, "runtime", adapter)
        original_journal = getattr(supervisor, "journal", journal)
        supervisor.runtime = DeadlineRuntime(adapter, deadline - 0.5, self)
        supervisor.journal = ClaimJournal(original_journal, self)
        try:
            result = supervisor.execute(value)
        finally:
            supervisor.runtime = original
            supervisor.journal = original_journal
        # These terminal results imply validated response/limits and cleanup.
        verified = (isinstance(result, dict) and
                    (result.get("status") == "succeeded" or result == {"status": "failed", "error": "TIMEOUT"})
                    and supervisor.last_stage == "response_validated")
        adapter.set_deadline(deadline)
        require_live(journal, adapter.reaper_session)
        adapter.preflight(journal.pending())
        if journal.pending() or self.clock() >= deadline:
            raise RPCError("CLEANUP_UNVERIFIED")
        receipt_reader = getattr(journal, "cancel_receipt", None)
        if receipt_reader is not None:
            receipt = receipt_reader(value["requestId"], fingerprint(value), adapter.reaper_session)
            if not isinstance(receipt, dict) or receipt.get("cleanupVerified") is not True:
                raise RPCError("CLEANUP_UNVERIFIED")
            if receipt.get("terminalReason") == "cancelled":
                result = {"status": "failed", "error": "CANCELLED"}
                verified = False
        # Oversize worker output is a fixed failure code; never return its body.
        if result == {"status": "failed", "error": "RESPONSE_TOO_LARGE"}:
            result = {"status": "failed", "error": "OUTPUT_TOO_LARGE"}
        deliverable = verified or result.get("error") in {
            "TIMEOUT", "RESOURCE_EXHAUSTED", "EXECUTION_FAILED", "OUTPUT_TOO_LARGE", "CANCELLED"}
        if len(encode(result)) > CONTENT_LIMIT:
            result = {"status": "failed", "error": "OUTPUT_TOO_LARGE"}
        if verified:
            self.last_verified = (self.clock(), adapter.config.image)
        return result, deliverable

    def _execute(self, owner, value, deadline):
        try:
            with self.lock:
                row, view = self._view(owner, value["requestId"])
                if row[2] == "cancelled":
                    return view
                with self.db:
                    changed = self.db.execute("UPDATE rpc_tasks SET handoff_state='claiming' WHERE id=? AND handoff_state='not_started' AND cancel_requested=0",
                                              (value["requestId"],)).rowcount
                if changed != 1:
                    raise RPCError("HANDOFF_UNVERIFIED")
            result, verified = self._run(value, deadline)
            with self.lock:
                if self._view(owner, value["requestId"])[0][2] == "cancelled":
                    return self._view(owner, value["requestId"])[1]
                state = result["status"] if verified else "unknown"
                self._state(value["requestId"], state)
                if verified:
                    self.results[value["requestId"]] = (self.clock(), result)
                    with self.db:
                        self.db.execute("UPDATE rpc_tasks SET cleaned_at=? WHERE id=?", (self.wall_clock(), value["requestId"]))
                return self._view(owner, value["requestId"])[1]
        except Exception:
            self.last_verified = None
            with self.lock:
                if self._view(owner, value["requestId"])[0][2] != "cancelled":
                    self._state(value["requestId"], "unknown")
                return self._view(owner, value["requestId"])[1]
        finally:
            self.slot.release()

    def _health(self, deadline):
        try:
            adapter, journal, _, _ = self._configured(deadline)
            if journal.pending():
                raise RPCError("PENDING_UNVERIFIED")
            if (self.last_verified is None or not 0 <= self.clock() - self.last_verified[0] <= 60
                    or self.last_verified[1] != adapter.config.image):
                value = {"requestId": "rpc-health-" + uuid.uuid4().hex, "language": "javascript",
                         "source": "function main(i) { return 1+1; }", "input": {}, "timeoutMs": 1000}
                result, verified = self._run(value, deadline, probe=True)
                if not verified or result != {"status": "succeeded", "output": 2}:
                    raise RPCError("PROBE_UNVERIFIED")
                if hasattr(journal, "db"):
                    self._retire_core(journal, value["requestId"], fingerprint(value))
            stamp, image = self.last_verified
            if not 0 <= self.clock() - stamp <= 60 or image != adapter.config.image:
                raise RPCError("POLICY_STALE")
            return {"status": "ready", "policyVerified": True}
        except Exception:
            self.last_verified = None
            return {"status": "unavailable", "policyVerified": False}


class RPCServer:
    """Linux AF_UNIX listener; exclusive owner lock and verified stale socket recovery."""

    def __init__(self, path, orchestrator, *, connections=8):
        if connections < 1:
            raise ValueError("invalid connections")
        self.path, self.orchestrator = path, orchestrator
        self.slots = threading.BoundedSemaphore(connections)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=connections)
        self.sock = None

    def serve(self):
        uid = self.orchestrator.allowed_uid
        private_path(self.path, uid, require_socket=False)
        self.endpoint_lock = acquire_endpoint(self.path, uid)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            self.sock = listener
            # Restrictive creation, even before chown/chmod completes.
            previous = os.umask(0o177)
            try:
                listener.bind(self.path)
            finally:
                os.umask(previous)
            os.chown(self.path, uid, -1)
            os.chmod(self.path, 0o600)
            private_path(self.path, uid)
            listener.listen(8)
            from .reaper import notify_ready
            notify_ready()
            while True:
                connection, _ = listener.accept()
                if not self.slots.acquire(blocking=False):
                    connection.close()
                    continue
                self.pool.submit(self.connection, connection)

    def connection(self, connection):
        deadline = time.monotonic() + 25
        try:
            with connection:
                uid = peer_uid(connection)
                if uid != self.orchestrator.allowed_uid:
                    return
                message = receive(connection, deadline)
                if isinstance(message, dict) and type(message.get("deadline")) in (float, int):
                    deadline = min(deadline, message["deadline"])
                try:
                    value = self.orchestrator.handle(uid, message)
                    response = {"ok": True, "value": value}
                except Exception:
                    response = {"ok": False, "error": "RPC_REJECTED"}
                send(connection, response, deadline)
        except Exception:
            pass  # No request, response, peer or exception content logging.
        finally:
            self.slots.release()


def acquire_endpoint(path, uid):
    """Hold until process exit. Never delete a live, replaced or foreign endpoint."""
    fd = os.open(path + '.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600:
            raise RPCError('ENDPOINT_EXISTS')
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if os.path.lexists(path):
            before = os.lstat(path)
            if not stat.S_ISSOCK(before.st_mode) or before.st_uid != uid or stat.S_IMODE(before.st_mode) != 0o600:
                raise RPCError('ENDPOINT_EXISTS')
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                probe.settimeout(0.2)
                try:
                    probe.connect(path)
                except ConnectionRefusedError:
                    after = os.lstat(path)
                    if (after.st_dev, after.st_ino) != (before.st_dev, before.st_ino):
                        raise RPCError('ENDPOINT_EXISTS')
                    os.unlink(path)
                else:
                    raise RPCError('ENDPOINT_EXISTS')
        return fd
    except BaseException:
        os.close(fd)
        raise

def main():
    parser = argparse.ArgumentParser(description="Trusted execution orchestrator; never run inside worker")
    parser.add_argument("--rpc-socket", required=True)
    parser.add_argument("--control-uid", type=int, required=True)
    parser.add_argument("--allow-root-control", action="store_true")
    parser.add_argument("--owner", action="append", required=True)
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--socket", required=True, help="dedicated runtime socket, orchestrator only")
    parser.add_argument("--expected-daemon-id", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--journal", required=True)
    parser.add_argument("--reaper-session", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    # Metadata is orchestrator-owned, distinct from the control-owned RPC
    # directory and from the core journal shared with the existing reaper.
    private_path(args.metadata, os.geteuid(), require_socket=False)
    if os.path.lexists(args.metadata):
        info = os.stat(args.metadata, follow_symlinks=False)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o600):
            return 1

    def factory():
        # Intentionally lazy: importing the control-side backend cannot import
        # DockerAdapter, HostGuard or reaper into the control process.
        from .control import Journal, Supervisor
        from .docker_adapter import Config, DockerAdapter
        from .host_guard import HostGuard
        from .reaper import require_live
        if not os.path.isfile(args.journal) or os.path.islink(args.journal):
            raise RPCError("JOURNAL_UNVERIFIED")
        config = Config(args.socket, args.expected_daemon_id, args.image, profile="execution")
        journal = Journal(args.journal)
        adapter = DockerAdapter(config, guard=HostGuard())
        adapter.reaper_session = args.reaper_session
        return adapter, journal, Supervisor(adapter, journal, config.image, profile="execution"), require_live

    def cancellation_journal():
        from .cancel import open_journal
        return open_journal(args.journal)

    orchestrator = None
    try:
        orchestrator = Orchestrator(args.metadata, allowed_uid=args.control_uid, owners=args.owner,
                                    factory=factory, allow_root=args.allow_root_control,
                                    cancellation=CoreCancellation(cancellation_journal, args.reaper_session))
        RPCServer(args.rpc_socket, orchestrator).serve()
    except Exception:
        return 1
    finally:
        if orchestrator is not None:
            orchestrator.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
