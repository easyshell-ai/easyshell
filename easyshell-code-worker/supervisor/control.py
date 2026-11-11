"""Bounded supervisor model. Only fixed, trusted fake workloads are tested."""

import hashlib
import json
import re
import sqlite3
import time
import uuid
from dataclasses import dataclass
from .cancel import CancellationJournal


MAX_REQUEST = 65536
MAX_OUTPUT = 65536
STARTUP_ALLOWANCE_SECONDS = 15
MAX_HARD_SECONDS = 20
MEMORY = 512 * 1024 * 1024
PREFIX = "easyshell-code-v1-"
PROTOCOL = "easyshell.code.lifecycle.v1"
JVM_OPTIONS = ["-Xmx256m", "-Duser.home=/tmp", "-Djava.io.tmpdir=/tmp",
               "-Dtruffle.UseFallbackRuntime=true", "-Dpolyglot.engine.WarnInterpreterOnly=false",
               "-Dpolyglot.engine.resourcePath=/opt/code-worker/resources"]
COMMAND = ["/opt/java/openjdk/bin/java", *JVM_OPTIONS, "-cp",
           "/opt/code-worker/lib/*", "com.easyshell.codeworker.Worker"]
EXECUTION_COMMAND = ["/opt/code-worker/launcher"]
DIAGNOSTIC_COMMAND = COMMAND[:-1] + ["com.easyshell.codeworker.PythonDiagnosticWorker"]
RESOURCE_COMMAND = ["/opt/java/openjdk/bin/java", "-Xmx256m", "-XX:MaxDirectMemorySize=1g",
                    "-cp", "/opt/code-worker/lib/*", "com.easyshell.codeworker.MemoryLimitProbe"]
TMPFS = "rw,noexec,nosuid,nodev,size=67108864,mode=1777"


class Rejected(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


class NotFound(Exception):
    pass


def failed(code):
    return {"status": "failed", "error": code}


def decode_json(data, limit):
    if len(data) > limit:
        raise Rejected("JSON_TOO_LARGE")

    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Rejected("INVALID_JSON")
            result[key] = value
        return result

    def constant(_):
        raise Rejected("INVALID_JSON")

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs, parse_constant=constant)
        pending = [(value, 0)]
        count = 0
        while pending:
            item, depth = pending.pop()
            count += 1
            if depth > 64 or count > 10000:
                raise Rejected("JSON_TOO_COMPLEX")
            if isinstance(item, dict):
                pending.extend((child, depth + 1) for child in item.values())
            elif isinstance(item, list):
                pending.extend((child, depth + 1) for child in item)
        return value
    except (ValueError, UnicodeError, RecursionError):
        raise Rejected("INVALID_JSON") from None


def request(data):
    value = decode_json(data, MAX_REQUEST)
    fields = {"requestId", "language", "source", "input", "timeoutMs"}
    if not isinstance(value, dict) or set(value) != fields:
        raise Rejected("INVALID_REQUEST")
    if not isinstance(value["requestId"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value["requestId"]):
        raise Rejected("INVALID_REQUEST")
    if value["language"] not in ("javascript", "python"):
        raise Rejected("INVALID_REQUEST")
    if not isinstance(value["source"], str) or len(value["source"].encode("utf-8")) > 32768:
        raise Rejected("INVALID_REQUEST")
    if type(value["timeoutMs"]) is not int or not 1 <= value["timeoutMs"] <= 5000:
        raise Rejected("INVALID_REQUEST")
    return value


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=True,
                                     separators=(",", ":")).encode()).hexdigest()


@dataclass(frozen=True)
class Record:
    request_id: str
    fingerprint: str
    name: str
    nonce: str
    container_id: str | None = None
    state: str = "pending"
    profile: str = "refusal"

    def labels(self):
        protocols = {"refusal": PROTOCOL, "execution": "easyshell.code.execution.v2",
                     "resourceprobe": "easyshell.code.resourceprobe.v1"}
        return {"easyshell.protocol": protocols[self.profile], "easyshell.request": self.fingerprint,
                "easyshell.nonce": self.nonce}


class Journal(CancellationJournal):
    """Dedicated SQLite file, no source/input/output; bounded entries, no blind retries."""

    def __init__(self, path, capacity=1024):
        self.db = sqlite3.connect(path, timeout=0.05)
        self.capacity = capacity
        self.db.execute("PRAGMA max_page_count=1024")
        self.db.execute("CREATE TABLE IF NOT EXISTS tasks (request_id TEXT PRIMARY KEY, "
                        "fingerprint TEXT, name TEXT UNIQUE, nonce TEXT, container_id TEXT, state TEXT, profile TEXT)")
        self.db.commit()
        self.init_cancellation()
        self.db.execute("CREATE TABLE IF NOT EXISTS guards (container_id TEXT PRIMARY KEY, path TEXT, device INTEGER, inode INTEGER)")
        self.db.execute("CREATE TABLE IF NOT EXISTS leases (request_id TEXT PRIMARY KEY, session TEXT, deadline REAL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS reaper (singleton INTEGER PRIMARY KEY, session TEXT, pid INTEGER, start TEXT, heartbeat REAL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS lifecycle (request_id TEXT PRIMARY KEY, nonce TEXT, "
                        "container_id TEXT, start_attempted INTEGER DEFAULT 0, no_start_verified INTEGER DEFAULT 0)")
        self.db.commit()

    def close(self):
        self.db.close()

    def claim(self, value, profile="refusal"):
        if profile not in ("refusal", "execution", "resourceprobe"):
            raise Rejected("PROFILE_UNVERIFIED")
        digest = fingerprint(value)
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            previous = self.db.execute("SELECT * FROM tasks WHERE request_id=?",
                                       (value["requestId"],)).fetchone()
            if previous:
                raise Rejected("DUPLICATE_REQUEST" if previous[1] == digest and previous[6] == profile else "REQUEST_ID_CONFLICT")
            if self.db.execute("SELECT count(*) FROM tasks").fetchone()[0] >= self.capacity:
                raise Rejected("JOURNAL_FULL")
            prefix = {"refusal": PREFIX, "execution": "easyshell-code-v2-",
                      "resourceprobe": "easyshell-resource-v1-"}[profile]
            record = Record(value["requestId"], digest, prefix + hashlib.sha256(
                value["requestId"].encode()).hexdigest()[:32], uuid.uuid4().hex, profile=profile)
            self.db.execute("INSERT INTO tasks VALUES (?, ?, ?, ?, ?, ?, ?)", tuple(record.__dict__.values()))
            if profile != "refusal":
                self.db.execute("INSERT INTO leases VALUES (?, ?, ?)",
                                (record.request_id, "", time.monotonic() + 2))
        return record

    def bind(self, record, container_id):
        with self.db:
            self.db.execute("UPDATE tasks SET container_id=? WHERE request_id=?",
                            (container_id, record.request_id))

    def finish(self, record, state):
        with self.db:
            self.db.execute("UPDATE tasks SET state=? WHERE request_id=? AND NOT EXISTS "
                            "(SELECT 1 FROM task_controls WHERE request_id=? AND verified=1)",
                            (state, record.request_id, record.request_id))

    def pending(self):
        rows = self.db.execute("SELECT * FROM tasks WHERE state IN ('pending','uncertain')").fetchall()
        return [Record(*row) for row in rows]


def policy(image, record):
    """No field originates from source/input; image comes from trusted configuration."""
    if record.profile not in ("refusal", "execution", "resourceprobe"):
        raise Rejected("PROFILE_UNVERIFIED")
    return {
        "Image": image, "Cmd": list({"refusal": COMMAND, "execution": EXECUTION_COMMAND,
                                     "resourceprobe": RESOURCE_COMMAND}[record.profile]), "Entrypoint": [], "User": "65532:65532",
        # Docker scratch builds inject PATH. Execution may carry only this public constant;
        # the static launcher replaces envp with an empty array before starting the JVM.
        "Env": ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"]
               if record.profile == "execution" else [],
        "WorkingDir": "/tmp", "Labels": record.labels(), "NetworkDisabled": True,
        "OpenStdin": True, "StdinOnce": True, "Tty": False,
        "AttachStdin": True, "AttachStdout": True, "AttachStderr": True,
        "HostConfig": {
            "NetworkMode": "none", "ReadonlyRootfs": True, "Privileged": False,
            "CapDrop": ["ALL"], "CapAdd": [], "SecurityOpt": ["no-new-privileges:true"],
            "Memory": MEMORY, "MemorySwap": MEMORY, "CpuPeriod": 100000, "CpuQuota": 100000,
            "PidsLimit": 128, "Tmpfs": {"/tmp": TMPFS},
            "Ulimits": [{"Name": "nofile", "Soft": 256, "Hard": 256}],
            "LogConfig": {"Type": "none", "Config": {}}, "Binds": [], "Mounts": [],
            "VolumesFrom": [], "Devices": [], "DeviceRequests": [], "PortBindings": {},
            "PublishAllPorts": False, "PidMode": "", "IpcMode": "private", "CgroupnsMode": "private",
            "RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}, "AutoRemove": False,
        },
    }


def owned(raw, record):
    """Labels alone are insufficient: match a pre-existing durable journal record too."""
    cid = raw.get("Id")
    if (not isinstance(cid, str) or not re.fullmatch(r"[0-9a-f]{64}", cid)
            or raw.get("Name") != "/" + record.name
            or raw.get("Config", {}).get("Labels") != record.labels()
            or (record.container_id is not None and record.container_id != cid)):
        raise Rejected("OWNERSHIP_UNVERIFIED")
    return cid


def verify(raw, record, image):
    owned(raw, record)
    # Docker Inspect's top-level Image is the config ID, not the repository manifest digest.
    image_id = raw.get("Image")
    if (not isinstance(image_id, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id)
            or (image.startswith("sha256:") and image_id != image)):
        raise Rejected("IMAGE_UNVERIFIED")
    expected = policy(image, record)
    config = raw.get("Config", {})
    for key in ("Image", "Cmd", "Entrypoint", "User", "Env", "WorkingDir", "NetworkDisabled",
                "OpenStdin", "StdinOnce", "Tty", "AttachStdin", "AttachStdout", "AttachStderr"):
        value = config.get(key)
        # Docker serializes explicitly empty slices as null. Missing keys still fail.
        if key in ("Entrypoint", "Env") and value is None:
            value = []
        if key not in config or value != expected[key]:
            raise Rejected("POLICY_UNVERIFIED")
    if config.get("Volumes") or "Mounts" not in raw or raw["Mounts"] != []:
        # Deliberately conservative: target daemon representation requires live acceptance tests.
        raise Rejected("POLICY_UNVERIFIED")
    actual = raw.get("HostConfig", {})
    for key, value in expected["HostConfig"].items():
        # Docker omits HostConfig.Mounts when unused. This exception is safe only
        # with an explicit empty live mount list and independently empty legacy mounts.
        if key == "Mounts" and "Mounts" not in actual and omitted_mounts_safe(raw):
            continue
        observed = actual.get(key)
        if value in ([], {}) and observed is None:
            observed = value
        if key not in actual or observed != value:
            raise Rejected("POLICY_UNVERIFIED")
    if actual.get("Runtime") not in (None, "", "runc"):
        raise Rejected("POLICY_UNVERIFIED")
    state = raw.get("State", {})
    if (type(state.get("Running")) is not bool or type(state.get("Pid")) is not int
            or state.get("Paused") is not False or state.get("Restarting") is not False
            or state.get("Status") not in ("created", "running", "exited", "dead")
            or state["Running"] != (state["Status"] == "running")
            or (state["Running"] and state["Pid"] <= 0)
            or (not state["Running"] and state["Pid"] != 0)):
        raise Rejected("STATE_UNVERIFIED")
    return state


def omitted_mounts_safe(raw):
    actual = raw.get("HostConfig", {})
    config = raw.get("Config", {})
    return ("Mounts" in raw and raw["Mounts"] == []
            and isinstance(config, dict) and not config.get("Volumes")
            and isinstance(actual, dict)
            and all(key in actual and actual[key] in (None, []) for key in ("Binds", "VolumesFrom")))


@dataclass(frozen=True)
class Snapshot:
    raw: dict


def worker_response(data):
    value = decode_json(data, MAX_OUTPUT)
    if isinstance(value, dict):
        if set(value) == {"status", "output"} and value["status"] == "succeeded":
            return value
        if (set(value) == {"status", "error"} and value["status"] == "failed"
                and value["error"] in ("ISOLATION_UNVERIFIED", "INVALID_REQUEST", "REQUEST_TOO_LARGE",
                                       "EXECUTION_FAILED", "TIMEOUT", "RESPONSE_TOO_LARGE")):
            return failed(value["error"])
    raise Rejected("RESULT_UNVERIFIED")


class Supervisor:
    def __init__(self, runtime, journal, image, clock=time.monotonic, pause=time.sleep,
                 cleanup_grace=0.5, profile="refusal"):
        if profile not in ("refusal", "execution", "resourceprobe"):
            raise Rejected("PROFILE_UNVERIFIED")
        self.profile = profile
        self.runtime, self.journal, self.image = runtime, journal, image
        self.clock, self.pause, self.cleanup_grace = clock, pause, cleanup_grace

    def cleanup(self, record):
        if self.profile == "refusal":
            return self._cleanup(record)
        token = self.journal.cleanup_acquire(record)
        if token == "verified":
            return
        if token is None:
            raise Rejected("CLEANUP_BUSY")
        try:
            if hasattr(self.runtime, "set_deadline"):
                self.runtime.set_deadline(self.clock() + 2)
            if hasattr(self.runtime, "set_cleanup_claim"):
                self.runtime.set_cleanup_claim(record, token)
            self._cleanup(record)
            self.journal.cleanup_commit(record, token, getattr(self, "cleanup_reason", "expired"))
        finally:
            if hasattr(self.runtime, "set_cleanup_claim"):
                self.runtime.set_cleanup_claim(None, None)
            self.journal.cleanup_release(record, token)

    def _cleanup(self, record):
        target = record.container_id or record.name
        try:
            snap = self.runtime.inspect(target)
        except NotFound:
            # Not found is sufficient only when we previously bound an identified container.
            if record.container_id is None:
                raise Rejected("CLEANUP_UNCERTAIN")
            if self.profile != "refusal":
                self.runtime.confirm_empty(record)
            return
        cid = owned(snap.raw, record)
        # Bind before destructive operations; no operation uses a caller-supplied container id.
        bound = Record(record.request_id, record.fingerprint, record.name, record.nonce, cid, profile=record.profile)
        self.journal.bind(record, cid)
        if self.profile != "refusal" and hasattr(self.runtime, "prepare_cleanup_evidence"):
            self.runtime.prepare_cleanup_evidence(bound, snap.raw)
        end = self.clock() + self.cleanup_grace
        while True:
            state = snap.raw.get("State", {})
            if (type(state.get("Running")) is not bool or type(state.get("Pid")) is not int
                    or state.get("Paused") is not False or state.get("Restarting") is not False):
                raise Rejected("CLEANUP_UNVERIFIED")
            if not state["Running"] and state["Pid"] == 0:
                break
            self.runtime.kill(cid)
            if self.clock() >= end:
                raise Rejected("CLEANUP_TIMEOUT")
            self.pause(0.01)
            snap = self.runtime.inspect(cid)
            owned(snap.raw, bound)
        self.runtime.remove(cid)
        try:
            self.runtime.inspect(cid)
        except NotFound:
            if self.profile != "refusal":
                self.runtime.confirm_empty(bound)
            return
        raise Rejected("CLEANUP_UNVERIFIED")

    def execute(self, value):
        # Internal deployment API only. CLI stays disabled and never calls this method.
        try:
            value = request(json.dumps(value, ensure_ascii=True, allow_nan=False).encode())
            record = self.journal.claim(value, self.profile)
        except Rejected as error:
            return failed(error.code)
        except (ValueError, TypeError, RecursionError):
            return failed("INVALID_REQUEST")
        result = failed("EXECUTION_UNVERIFIED")
        self.last_delivery = False
        self.last_timeout_origin = "none"
        self.last_stage = "claimed"
        create_attempted = False
        channel = None
        deadline = self.clock() + STARTUP_ALLOWANCE_SECONDS + value["timeoutMs"] / 1000
        try:
            if self.profile != "refusal":
                self.runtime.authorize(self.journal, record, deadline)
            if hasattr(self.runtime, "set_deadline"):
                self.runtime.set_deadline(deadline)
            self.runtime.preflight(self.journal.pending())
            self.last_stage = "preflight"
            if self.journal.cancellation_requested(record):
                raise Rejected("CANCELLED")
            create_attempted = True
            cid = self.runtime.create(record.name, policy(self.image, record))
            if not isinstance(cid, str) or not re.fullmatch(r"[0-9a-f]{64}", cid):
                raise Rejected("CREATE_UNCERTAIN")
            record = Record(record.request_id, record.fingerprint, record.name, record.nonce, cid, profile=record.profile)
            self.journal.bind(record, cid)
            self.last_stage = "created"
            state = verify(self.runtime.inspect(cid).raw, record, self.image)
            if state["Status"] != "created":
                raise Rejected("STATE_UNVERIFIED")
            if self.clock() >= deadline:
                raise Rejected("TIMEOUT")
            # Attach before Start prevents losing a short-lived worker's stdout (log driver none).
            channel = self.runtime.attach(cid, deadline)
            if self.journal.cancellation_requested(record):
                raise Rejected("CANCELLED")
            self.runtime.start(cid, deadline)
            self.last_stage = "started"
            if self.profile != "refusal":
                # A missing verifier is a failure before any source/input reaches the process.
                self.runtime.verify_limits(cid, deadline)
                self.last_stage = "limits_verified"
            if self.clock() >= deadline:
                raise Rejected("TIMEOUT")
            if self.journal.cancellation_requested(record):
                raise Rejected("CANCELLED")
            payload = json.dumps({key: value[key] for key in ("language", "source", "input", "timeoutMs")},
                                 ensure_ascii=True, allow_nan=False, separators=(",", ":")).encode()
            if len(payload) > MAX_REQUEST:
                raise Rejected("REQUEST_TOO_LARGE")
            output = channel.exchange(payload)
            self.last_stage = "response_received"
            completed_channel = channel
            channel = None
            completed_channel.close()
            result = worker_response(output)
            if result == failed("TIMEOUT"):
                self.last_timeout_origin = "worker"
            self.last_stage = "response_validated"
            if len(json.dumps(result, ensure_ascii=True).encode()) > MAX_OUTPUT:
                raise Rejected("OUTPUT_TOO_LARGE")
            while True:
                if self.clock() >= deadline:
                    raise Rejected("TIMEOUT")
                snapshot = self.runtime.inspect(cid)
                state = verify(snapshot.raw, record, self.image)
                if self.clock() >= deadline:
                    raise Rejected("TIMEOUT")
                if state["Status"] in ("exited", "dead"):
                    if type(state.get("OOMKilled")) is not bool or type(state.get("ExitCode")) is not int:
                        raise Rejected("STATE_UNVERIFIED")
                    if state["OOMKilled"]:
                        raise Rejected("RESOURCE_EXHAUSTED")
                    if state["ExitCode"] != 0 or state["Status"] != "exited":
                        raise Rejected("EXECUTION_FAILED")
                    break
                self.pause(0.01)
        except Rejected as error:
            result = failed(error.code)
            if error.code == "TIMEOUT":
                self.last_timeout_origin = "external"
        except Exception:
            result = failed("RUNTIME_UNCERTAIN")
        finally:
            if channel is not None:
                self.last_delivery = getattr(channel, "delivered", False)
            elif 'completed_channel' in locals():
                self.last_delivery = getattr(completed_channel, "delivered", False)
            if self.last_delivery and self.last_stage == "limits_verified":
                self.last_stage = "source_delivered"
            # Inspect before parsing/transport errors can hide an OOM. Never infer OOM from EOF.
            if record.container_id is not None:
                try:
                    if hasattr(self.runtime, "set_deadline"):
                        self.runtime.set_deadline(self.clock() + self.cleanup_grace)
                    raw = self.runtime.inspect(record.container_id).raw
                    owned(raw, record)
                    if raw.get("State", {}).get("OOMKilled") is True:
                        result = failed("RESOURCE_EXHAUSTED")
                except Exception:
                    result = failed("STATE_UNVERIFIED")
            if channel is not None:
                try:
                    channel.close()
                except Exception:
                    result = failed("ATTACH_CLOSE_FAILED")
            try:
                if create_attempted:
                    if hasattr(self.runtime, "set_deadline"):
                        self.runtime.set_deadline(self.clock() + self.cleanup_grace)
                    self.cleanup_reason = "succeeded" if result.get("status") == "succeeded" else "failed"
                    try:
                        self.cleanup(record)
                    except Rejected as error:
                        if error.code != "CLEANUP_BUSY":
                            raise
                        # Independent reaper owns cleanup; wait only for a matching durable receipt.
                        lease = self.journal.db.execute("SELECT session FROM leases WHERE request_id=?",
                                                       (record.request_id,)).fetchone()
                        receipt = self.journal.wait_cancel(record.request_id, record.fingerprint, lease[0], 3)
                        if not receipt["cleanupVerified"]:
                            raise Rejected("CLEANUP_UNVERIFIED")
            except Exception:
                result = failed("CLEANUP_UNVERIFIED")
                self.journal.finish(record, "uncertain")
            else:
                self.journal.finish(record, "terminal")
            if self.profile != "refusal":
                row = self.journal.db.execute("SELECT terminal_reason,verified FROM task_controls "
                                              "WHERE request_id=? AND nonce=?", (record.request_id, record.nonce)).fetchone()
                if row is not None and row == ("cancelled", 1):
                    # Reaper removal and its expected 404 never override verified cancellation.
                    result = failed("CANCELLED")
        return result

    def reconcile(self):
        """Recover only journal-owned names/IDs. Never scan/delete by a label alone."""
        records = self.journal.pending()
        self.runtime.preflight(records)
        failures = 0
        for record in records:
            try:
                self.cleanup(record)
                self.journal.finish(record, "terminal")
            except Exception:
                self.journal.finish(record, "uncertain")
                failures += 1
        if failures:
            raise Rejected("RECONCILE_UNVERIFIED")
