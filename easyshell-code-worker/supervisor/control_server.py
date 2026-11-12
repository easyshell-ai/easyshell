"""Internal mTLS control ingress. No runtime/socket adapter is imported here.

Only trusted deployment code may inject an ExecutionBackend. Bind UnixRPCBackend
from orchestrator_rpc to reach the separate trusted orchestrator process.
The explicit CLI accepts trusted deployment configuration, never runtime config.
"""

import argparse
import copy
import json
import os
import re
import socket
import sqlite3
import ssl
import stat
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from typing import Protocol

from .control import MAX_OUTPUT, MAX_REQUEST, Rejected, decode_json, fingerprint, request

USER_LIMIT = 60 * 1024
REPLAY_SECONDS = 24 * 3600


def wire_json(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


class ExecutionBackend(Protocol):
    """Trusted RPC boundary, never a Docker adapter in the ingress process.

    execute must enforce the absolute monotonic deadline including RPC IO, return
    only after verified cleanup, and never retry uncertain delivery. cancel must
    independently verify owner/id and cleanup; True means cleanup was confirmed.
    Both calls must be thread-safe. No caller-supplied command/path/config exists.
    """

    def execute(self, owner: str, value: dict, deadline: float) -> dict: ...

    def cancel(self, owner: str, request_id: str, deadline: float) -> bool: ...

    def health(self, owner: str, deadline: float) -> dict: ...


class ApiError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


def client_identity(cert, allowed):
    # CN, HTTP headers and wildcard SANs never establish service identity.
    names = [value for kind, value in (cert or {}).get("subjectAltName", ()) if kind == "DNS"]
    if len(names) != 1 or names[0] not in allowed:
        raise ApiError(403, "CLIENT_IDENTITY_DENIED")
    return names[0]


@dataclass(frozen=True)
class TLSConfig:
    certfile: str
    keyfile: str
    cafile: str
    client_dns_names: frozenset[str]

    def context(self):
        if (not all((self.certfile, self.keyfile, self.cafile, self.client_dns_names))
                or any(not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", name)
                       for name in self.client_dns_names)):
            raise ValueError("explicit TLS files and exact client DNS identities required")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.verify_mode = ssl.CERT_REQUIRED
        context.load_cert_chain(self.certfile, self.keyfile)
        context.load_verify_locations(cafile=self.cafile)
        context.set_alpn_protocols(["http/1.1"])
        return context


class ControlService:
    """Single-process bounded admission and durable non-replayable identities.

    Use one instance/process per private journal; multiple writers are unsupported.
    Journal exhaustion fails closed rather than forgetting idempotency records.
    """

    @classmethod
    def with_unix_rpc(cls, journal, allowed, *, rpc_socket, control_uid, orchestrator_uid, **limits):
        from .orchestrator_rpc import UnixRPCBackend
        backend = UnixRPCBackend(rpc_socket, control_uid=control_uid,
                                 orchestrator_uid=orchestrator_uid, owners=allowed)
        return cls(journal, allowed, backend, **limits)

    def __init__(self, journal, allowed, backend: ExecutionBackend | None = None, *,
                 concurrency=4, queue_size=8, capacity=1024, result_ttl=60,
                  rate_per_minute=120, clock=time.monotonic, wall_clock=time.time):
        if (not allowed or any(type(n) is not int or n < 1 for n in
                              (concurrency, capacity, rate_per_minute))
                or type(queue_size) is not int or queue_size < 0 or result_ttl <= 0):
            raise ValueError("invalid control limits")
        self.allowed = frozenset(allowed)
        self.backend, self.capacity, self.result_ttl = backend, capacity, result_ttl
        self.clock, self.rate = clock, rate_per_minute
        self.wall_clock = wall_clock
        self.cancel_pending = set()
        self.lock = threading.RLock()
        self.changed = threading.Condition(self.lock)
        self.results, self.rates = {}, {}
        self.slots = threading.BoundedSemaphore(concurrency + queue_size)
        self.pool = ThreadPoolExecutor(max_workers=concurrency, thread_name_prefix="control")
        self.db = sqlite3.connect(journal, check_same_thread=False)
        self.db.execute("PRAGMA max_page_count=1024")
        self.db.execute("CREATE TABLE IF NOT EXISTS control_tasks "
                        "(request_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, "
                        "owner TEXT NOT NULL, status TEXT NOT NULL)")
        if "cleaned_at" not in {row[1] for row in self.db.execute("PRAGMA table_info(control_tasks)")}:
            self.db.execute("ALTER TABLE control_tasks ADD COLUMN cleaned_at REAL")
        # No result survives restart; never automatically redispatch a claimed id.
        with self.db:
            self.db.execute("UPDATE control_tasks SET status='unknown' WHERE cleaned_at IS NULL")

    def close(self):
        self.pool.shutdown(wait=True)
        self.db.close()

    def _admit(self, cert):
        owner = client_identity(cert, self.allowed)
        now = self.clock()
        with self.lock:
            start, count = self.rates.get(owner, (now, 0))
            if now - start >= 60:
                start, count = now, 0
            if count >= self.rate:
                raise ApiError(429, "RATE_LIMITED")
            self.rates[owner] = (start, count + 1)
            with self.db:
                self.db.execute("DELETE FROM control_tasks WHERE cleaned_at IS NOT NULL AND cleaned_at<=?",
                                (self.wall_clock() - REPLAY_SECONDS,))
            for key, (expiry, _) in list(self.results.items()):
                if expiry <= now:
                    del self.results[key]
        return owner

    def _owned(self, owner, request_id):
        row = self.db.execute("SELECT fingerprint, owner, status FROM control_tasks "
                              "WHERE request_id=?", (request_id,)).fetchone()
        if row is None:
            raise ApiError(404, "NOT_FOUND")
        if row[1] != owner:
            raise ApiError(403, "RESOURCE_FORBIDDEN")
        return row

    def _view(self, owner, request_id):
        row = self._owned(owner, request_id)
        value = {"requestId": request_id, "status": row[2], "resultAvailable": False}
        cached = self.results.get(request_id)
        if cached and cached[0] > self.clock():
            value.update(resultAvailable=True, result=copy.deepcopy(cached[1]))
        return value

    def _response(self, owner, request_id):
        row = self._owned(owner, request_id)
        cached = self.results.get(request_id)
        if cached and cached[0] > self.clock():
            result = cached[1]
            value = {"requestId": request_id, "status": result["status"]}
            if result["status"] == "succeeded":
                value["output"] = result["output"]
            else:
                value["errorCode"] = self._error(result["error"])
            if len(wire_json(value)) > MAX_OUTPUT:
                return 200, {"requestId": request_id, "status": "failed", "errorCode": "OUTPUT_TOO_LARGE"}
            return 200, value
        if row[2] == "cancelled":
            return 200, {"requestId": request_id, "status": "cancelled"}
        if request_id in self.cancel_pending:
            return 202, {"requestId": request_id, "status": "cancel_pending"}
        if row[2] in ("queued", "running"):
            if request_id in self.cancel_pending:
                return 202, {"requestId": request_id, "status": "cancel_pending"}
            return 200, {"requestId": request_id, "status": row[2]}
        return self._uncertain(request_id)

    @staticmethod
    def _uncertain(request_id):
        return 503, {"requestId": request_id, "status": "failed", "errorCode": "CODE_EXECUTION_UNCERTAIN"}

    def dispatch(self, cert, method, path, body=b""):
        """Called only after TLS chain verification by ControlHandler."""
        try:
            owner = self._admit(cert)
            if method == "GET" and path == "/internal/v1/code-health":
                if body:
                    raise ApiError(400, "INVALID_REQUEST")
                try:
                    health = self.backend.health(owner, self.clock() + 10) if self.backend else None
                except Exception:
                    health = None
                if (isinstance(health, dict) and set(health) == {"status", "policyVerified"}
                        and health["status"] == "ready" and health["policyVerified"] is True):
                    return 200, health
                return 503, {"status": "unavailable", "policyVerified": False}
            if method == "POST" and path == "/internal/v1/code-executions":
                try:
                    if len(body) > USER_LIMIT:
                        raise Rejected("REQUEST_TOO_LARGE")
                    value = request(body)
                    json.dumps(value, allow_nan=False)
                except (Rejected, TypeError, ValueError, UnicodeError, OverflowError):
                    raise ApiError(413 if len(body) > USER_LIMIT else 400,
                                   "REQUEST_TOO_LARGE" if len(body) > USER_LIMIT else "INVALID_REQUEST") from None
                request_id = value["requestId"]
                end = self.clock() + 24
                self._submit(owner, value)
                with self.changed:
                    while self._owned(owner, request_id)[2] in ("queued", "running", "cancelling"):
                        left = end - self.clock()
                        if left <= 0:
                            break
                        self.changed.wait(timeout=left)
                    if self._owned(owner, request_id)[2] in ("queued", "running"):
                        return self._uncertain(request_id)
                    return self._response(owner, request_id)
            match = re.fullmatch(r"/internal/v1/code-executions/([A-Za-z0-9_-]{1,64})", path)
            if not match or method not in ("GET", "DELETE"):
                raise ApiError(404, "NOT_FOUND")
            if body:
                raise ApiError(400, "UNEXPECTED_BODY")
            request_id = match[1]
            if method == "GET":
                self._refresh_cancel(owner, request_id)
            with self.lock:
                row = self._owned(owner, request_id)
                if method == "GET":
                    return self._response(owner, request_id)
                if row[2] == "cancelled":
                    return 200, {"requestId": request_id, "status": "cancelled"}
                if row[2] == "unknown":
                    return self._response(owner, request_id)
                if self.backend is None:
                    raise ApiError(503, "BACKEND_UNCONFIGURED")
                if request_id in self.cancel_pending:
                    return 202, {"requestId": request_id, "status": "cancel_pending"}
                if not self.slots.acquire(blocking=False):
                    raise ApiError(429, "QUEUE_FULL")
                self.cancel_pending.add(request_id)
            # Cancel must not create an unbounded parallel backend call population.
            future = self.pool.submit(self._cancel, owner, request_id, self.clock() + 3)
            try:
                future.result(timeout=3)
            except Exception:
                return 202, {"requestId": request_id, "status": "cancel_pending"}
            with self.lock:
                if self._owned(owner, request_id)[2] == "cancelled":
                    return 200, {"requestId": request_id, "status": "cancelled"}
                return self._response(owner, request_id)
        except ApiError as exc:
            return exc.status, {"status": "failed", "errorCode": self._error(exc.code)}

    def _refresh_cancel(self, owner, request_id):
        with self.lock:
            self._owned(owner, request_id)
            if request_id not in self.cancel_pending or self.backend is None:
                return
            if not self.slots.acquire(blocking=False):
                return
        try:
            response = self.backend.status(owner, request_id, self.clock() + 1)
            if (isinstance(response, dict) and response.get("requestId") == request_id
                    and response.get("status") == "cancelled" and response.get("cleanupVerified") is True):
                with self.lock:
                    # Preserve a normal result that won the execution race.
                    if self._owned(owner, request_id)[2] in ("succeeded", "failed"):
                        return
                    self.results.pop(request_id, None)
                    self.cancel_pending.discard(request_id)
                    self._state(request_id, "cancelled")
                    with self.db:
                        self.db.execute("UPDATE control_tasks SET cleaned_at=? WHERE request_id=?", (self.wall_clock(), request_id))
        except Exception:
            pass
        finally:
            self.slots.release()

    @staticmethod
    def _error(code):
        if code in {"TIMEOUT", "RESOURCE_EXHAUSTED", "EXECUTION_FAILED", "INVALID_REQUEST",
                    "OUTPUT_TOO_LARGE", "CANCELLED"}:
            return code
        return {"RESPONSE_TOO_LARGE": "OUTPUT_TOO_LARGE", "QUEUE_FULL": "RATE_LIMITED",
                "RATE_LIMITED": "RATE_LIMITED", "CLIENT_IDENTITY_DENIED": "INVALID_REQUEST",
                "REQUEST_TOO_LARGE": "INPUT_TOO_LARGE", "ISOLATION_UNVERIFIED": "POLICY_UNVERIFIED",
                "REQUEST_ID_CONFLICT": "INVALID_REQUEST"}.get(code, "RUNTIME_UNAVAILABLE")

    def _state(self, request_id, state):
        with self.db:
            self.db.execute("UPDATE control_tasks SET status=? WHERE request_id=?", (state, request_id))
        self.changed.notify_all()

    def _submit(self, owner, value):
        request_id, digest = value["requestId"], fingerprint(value)
        with self.lock:
            row = self.db.execute("SELECT fingerprint, owner, status FROM control_tasks "
                                  "WHERE request_id=?", (request_id,)).fetchone()
            if row:
                self._owned(owner, request_id)
                if row[0] != digest:
                    raise ApiError(409, "REQUEST_ID_CONFLICT")
                return 200, self._view(owner, request_id)
            if self.backend is None:
                raise ApiError(503, "BACKEND_UNCONFIGURED")
            if self.db.execute("SELECT count(*) FROM control_tasks").fetchone()[0] >= self.capacity:
                raise ApiError(503, "JOURNAL_FULL")
            if not self.slots.acquire(blocking=False):
                raise ApiError(429, "QUEUE_FULL")
            try:
                with self.db:
                    self.db.execute("INSERT INTO control_tasks (request_id,fingerprint,owner,status) VALUES (?, ?, ?, 'queued')",
                                    (request_id, digest, owner))
                # Queue time is included in the existing 15s startup + guest budget.
                deadline = self.clock() + 23
                self.pool.submit(self._execute, owner, value, deadline)
            except Exception:
                self.slots.release()
                raise ApiError(503, "ADMISSION_FAILED") from None
            return 202, self._view(owner, request_id)

    def _execute(self, owner, value, deadline):
        request_id = value["requestId"]
        try:
            with self.lock:
                if self._owned(owner, request_id)[2] != "queued":
                    return
                if self.clock() >= deadline:
                    self._state(request_id, "unknown")
                    return
                self._state(request_id, "running")
            result = self.backend.execute(owner, value, deadline)
            # Validate structure/size only. SensitiveDataFilter in server is the
            # sole content masking implementation; never persist output here.
            raw = wire_json(result)
            if len(raw) > USER_LIMIT:
                result = {"status": "failed", "error": "OUTPUT_TOO_LARGE"}
                raw = wire_json(result)
            result = decode_json(raw, USER_LIMIT)
            valid = (isinstance(result, dict) and
                     ((set(result) == {"status", "output"} and result["status"] == "succeeded")
                      or (set(result) == {"status", "error"} and result["status"] == "failed"
                          and result["error"] in {"TIMEOUT", "EXECUTION_FAILED", "RESOURCE_EXHAUSTED",
                                                  "ISOLATION_UNVERIFIED", "CLEANUP_UNVERIFIED",
                                                  "RESPONSE_TOO_LARGE", "OUTPUT_TOO_LARGE", "RUNTIME_UNAVAILABLE", "CANCELLED"})))
            if not valid or self.clock() >= deadline:
                raise ValueError("unverified result")
            if result.get("error") in {"CLEANUP_UNVERIFIED", "RUNTIME_UNAVAILABLE", "ISOLATION_UNVERIFIED"}:
                raise ValueError("uncertain cleanup")
            with self.lock:
                if self._owned(owner, request_id)[2] == "running":
                    self._state(request_id, "cancelled" if result.get("error") == "CANCELLED" else result["status"])
                    self.results[request_id] = (self.clock() + self.result_ttl, result)
                    self.cancel_pending.discard(request_id)
                    with self.db:
                        self.db.execute("UPDATE control_tasks SET cleaned_at=? WHERE request_id=?", (self.wall_clock(), request_id))
        except Exception:
            with self.lock:
                if self._owned(owner, request_id)[2] == "running":
                    self._state(request_id, "unknown")
        finally:
            self.slots.release()

    def _cancel(self, owner, request_id, deadline):
        verified = False
        try:
            if self.clock() < deadline:
                verified = self.backend.cancel(owner, request_id, deadline) is True
                verified = verified and self.clock() < deadline
        except Exception:
            verified = False
        finally:
            with self.lock:
                if verified:
                    self.results.pop(request_id, None)
                    self.cancel_pending.discard(request_id)
                    self._state(request_id, "cancelled")
                    with self.db:
                        self.db.execute("UPDATE control_tasks SET cleaned_at=? WHERE request_id=?", (self.wall_clock(), request_id))
            self.slots.release()


class ControlHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Control"

    def log_message(self, format, *args):
        pass  # No paths, source, input, output, exception text or TLS identities.

    def _handle(self):
        self.close_connection = True
        try:
            if (not isinstance(self.connection, ssl.SSLSocket)
                    or self.connection.context.verify_mode != ssl.CERT_REQUIRED):
                raise ApiError(403, "MTLS_REQUIRED")
            cert = self.connection.getpeercert()
            client_identity(cert, self.server.service.allowed)
            if (self.headers.get_all("Transfer-Encoding") or self.headers.get_all("Expect")
                    or len(self.headers.get_all("Content-Length", [])) > 1):
                raise ApiError(400, "INVALID_FRAMING")
            length = self.headers.get("Content-Length", "0")
            if not re.fullmatch(r"[0-9]{1,8}", length):
                raise ApiError(400, "INVALID_FRAMING")
            length = int(length)
            if length > USER_LIMIT:
                raise ApiError(413, "REQUEST_TOO_LARGE")
            if self.command == "POST" and self.headers.get("Content-Type") != "application/json":
                raise ApiError(415, "JSON_REQUIRED")
            body = self.rfile.read(length)
            if len(body) != length:
                raise ApiError(400, "INCOMPLETE_BODY")
            status, value = self.server.service.dispatch(cert, self.command, self.path, body)
        except ApiError as exc:
            status, value = exc.status, {"status": "failed", "errorCode": ControlService._error(exc.code)}
        except (OSError, ValueError):
            status, value = 400, {"status": "failed", "errorCode": "INVALID_REQUEST"}
        except Exception:
            status, value = 503, {"status": "failed", "errorCode": "RUNTIME_UNAVAILABLE"}
        data = wire_json(value)
        if len(data) > MAX_OUTPUT:
            request_id = value.get("requestId")
            value = {"status": "failed", "errorCode": "OUTPUT_TOO_LARGE"}
            if request_id:
                value["requestId"] = request_id
            data = wire_json(value)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    do_POST = do_GET = do_DELETE = _handle

    def handle_expect_100(self):
        self.send_error(417)
        return False


class ControlHTTPServer(ThreadingMixIn, HTTPServer):
    """Explicit trusted construction only; no plaintext listener or guest switch.

    Absolute connection lifetime includes TLS handshake, headers and body. A
    bounded number of handler threads prevents slow clients growing threads.
    """

    daemon_threads = True
    block_on_close = False
    request_queue_size = 16

    def __init__(self, address, service, tls: TLSConfig, *, connections=16, request_seconds=25):
        if connections < 1 or not 0 < request_seconds <= 25:
            raise ValueError("invalid HTTP limits")
        if frozenset(tls.client_dns_names) != service.allowed:
            raise ValueError("TLS/service identity configuration mismatch")
        self.tls_context = tls.context()
        self.service, self.request_seconds = service, request_seconds
        self.connections = threading.BoundedSemaphore(connections)
        super().__init__(address, ControlHandler)

    def process_request(self, request_socket, client_address):
        if not self.connections.acquire(blocking=False):
            self.shutdown_request(request_socket)
            return
        try:
            super().process_request(request_socket, client_address)
        except Exception:
            self.connections.release()
            raise

    def process_request_thread(self, request_socket, client_address):
        holder = [request_socket]

        def expire():
            try:
                holder[0].shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        timer = threading.Timer(self.request_seconds, expire)
        timer.daemon = True
        timer.start()
        try:
            request_socket.settimeout(self.request_seconds)
            secured = self.tls_context.wrap_socket(request_socket, server_side=True,
                                                   do_handshake_on_connect=False)
            holder[0] = secured
            secured.do_handshake()
            self.finish_request(secured, client_address)
        except Exception:
            pass
        finally:
            timer.cancel()
            holder[0].close()
            self.connections.release()


def trusted_file(path, uid):
    if not os.path.isabs(path) or os.path.realpath(path) != path:
        raise ValueError("trusted file path required")
    info = os.stat(path, follow_symlinks=False)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != uid
            or stat.S_IMODE(info.st_mode) != 0o600):
        raise ValueError("private UID-owned file required")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Dedicated unprivileged mTLS code control ingress")
    parser.add_argument("--listen-host", required=True)
    parser.add_argument("--listen-port", type=int, required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--ca", required=True)
    parser.add_argument("--client-dns", action="append", required=True)
    parser.add_argument("--rpc-socket", required=True)
    parser.add_argument("--control-uid", type=int, required=True)
    parser.add_argument("--orchestrator-uid", type=int, required=True)
    parser.add_argument("--journal", required=True)
    args = parser.parse_args(argv)
    service = server = None
    try:
        if (args.control_uid <= 0 or os.getuid() != args.control_uid
                or os.geteuid() != args.control_uid or args.orchestrator_uid == args.control_uid
                or args.orchestrator_uid < 0 or not 1024 <= args.listen_port <= 65535):
            raise ValueError("separate unprivileged control identity required")
        from .orchestrator_rpc import private_path
        for path in (args.cert, args.key, args.ca):
            trusted_file(path, args.control_uid)
        private_path(args.journal, args.control_uid, require_socket=False)
        if os.path.lexists(args.journal):
            trusted_file(args.journal, args.control_uid)
        private_path(args.rpc_socket, args.control_uid)
        os.umask(0o077)
        allowed = frozenset(args.client_dns)
        tls = TLSConfig(args.cert, args.key, args.ca, allowed)
        service = ControlService.with_unix_rpc(
            args.journal, allowed, rpc_socket=args.rpc_socket,
            control_uid=args.control_uid, orchestrator_uid=args.orchestrator_uid)
        server = ControlHTTPServer((args.listen_host, args.listen_port), service, tls)
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except Exception:
        return 1  # Never print TLS/config/exception content.
    finally:
        if server is not None:
            server.server_close()
        if service is not None:
            service.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
