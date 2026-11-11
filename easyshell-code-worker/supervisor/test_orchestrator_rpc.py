"""Fake transport/runtime only. No listeners, real credentials or containers."""

import io
import json
import os
import socket
import stat
import struct
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from .control_server import ControlService
from .orchestrator_rpc import (Orchestrator, RPCError, RPCServer, UnixRPCBackend,
                               ClaimJournal, CoreCancellation, encode, peer_uid, private_path, receive, send)


OWNER = "server.internal"
UID = 17001


def value(**changes):
    return {"requestId": "rpc-1", "language": "javascript", "source": "secret-source",
            "input": {"secret": "secret-input"}, "timeoutMs": 1000, **changes}


def message(op="execute", **changes):
    extra = {"request": value()} if op == "execute" else {} if op == "health" else {"requestId": "rpc-1"}
    return {"op": op, "owner": OWNER, "deadline": time.monotonic() + 24, **extra, **changes}


class FakeJournal:
    def __init__(self):
        self.pending_rows = []
    def pending(self):
        return self.pending_rows
    def close(self):
        pass


class FakeCore:
    profile = "execution"
    image = "sha256:" + "a" * 64
    last_stage = "response_validated"
    def __init__(self):
        self.calls = []
        self.started = threading.Event()
        self.result = {"status": "succeeded", "output": "secret-result"}
        self.block = None
    def execute(self, request):
        self.calls.append(request)
        self.started.set()
        if self.block:
            self.block.wait(2)
        if request["requestId"].startswith("rpc-health-"):
            return {"status": "succeeded", "output": 2}
        return self.result


class OrchestratorTest(unittest.TestCase):
    def setUp(self):
        self.journal, self.core = FakeJournal(), FakeCore()
        self.adapter = Mock()
        self.adapter.config = SimpleNamespace(profile="execution", image=self.core.image)
        self.adapter.guard = object()
        self.adapter.reaper_session = "fixed-session"
        self.live = Mock()
        self.factory = lambda: (self.adapter, self.journal, self.core, self.live)
        self.service = Orchestrator(":memory:", allowed_uid=UID, owners={OWNER, "other.internal"}, factory=self.factory)
        self.addCleanup(self.service.close)

    def call(self, op="execute", **changes):
        return self.service.handle(UID, message(op, **changes))

    def test_peer_and_owner_and_fixed_fields(self):
        for uid, request in ((UID + 1, message()), (UID, message(owner="evil.internal")),
                             (UID, message(command="sh")), (UID, message(op="exec")),
                             (UID, message(deadline=time.monotonic() + 26))):
            with self.subTest(uid=uid, op=request["op"]), self.assertRaises(RPCError):
                self.service.handle(uid, request)
        self.assertFalse(self.core.calls)
        with self.assertRaises(ValueError):
            Orchestrator(":memory:", allowed_uid=0, owners={OWNER}, factory=self.factory)
        root = Orchestrator(":memory:", allowed_uid=0, owners={OWNER}, factory=self.factory, allow_root=True)
        root.close()

    def test_execute_duplicate_owner_and_conflict(self):
        first = self.call()
        self.assertEqual("succeeded", first["status"])
        self.assertEqual(first, self.call())
        self.assertEqual(1, len(self.core.calls))
        with self.assertRaises(RPCError):
            self.call(request=value(source="changed"))
        for op in ("status", "cancel", "execute"):
            with self.subTest(op=op), self.assertRaises(RPCError):
                self.call(op, owner="other.internal")

    def test_busy_status_cancel_never_touches_runtime(self):
        self.core.block = threading.Event()
        task = threading.Thread(target=self.call)
        task.start()
        try:
            self.assertTrue(self.core.started.wait(1))
            with self.assertRaises(RPCError):
                self.call(request=value(requestId="second"))
            self.assertFalse(self.call("cancel")["cleanupVerified"])
            self.assertEqual("cancel_pending", self.call("status")["status"])
            self.adapter.kill.assert_not_called()
            self.adapter.remove.assert_not_called()
        finally:
            self.core.block.set()
            task.join(3)

    def test_cleanup_uncertainty_no_result_or_replay(self):
        self.core.result = {"status": "failed", "error": "CLEANUP_UNVERIFIED"}
        result = self.call()
        self.assertEqual("unknown", result["status"])
        self.assertNotIn("result", result)
        self.assertFalse(self.call("cancel")["cleanupVerified"])
        self.call()
        self.assertEqual(1, len(self.core.calls))

    def test_timeout_verified_is_delivered(self):
        self.core.result = {"status": "failed", "error": "TIMEOUT"}
        self.assertEqual(self.core.result, self.call()["result"])

    def test_normal_terminal_metadata_never_confirms_cancel(self):
        self.call()
        self.assertFalse(self.call("cancel")["cleanupVerified"])

    def test_admission_cancel_cas_before_worker_handoff_calls_no_runtime(self):
        blocker = threading.Event()
        occupied = self.service.pool.submit(lambda: blocker.wait(2))
        output = []
        thread = threading.Thread(target=lambda: output.append(self.call()))
        thread.start()
        try:
            # Synchronize on durable admission, not absence of a core claim.
            for _ in range(1000):
                with self.service.lock:
                    admitted = self.service.db.execute("SELECT handoff_state FROM rpc_tasks WHERE id='rpc-1'").fetchone()
                if admitted:
                    break
                threading.Event().wait(0.001)
            self.assertEqual(("not_started",), admitted)
            result = self.call("cancel")
            self.assertTrue(result["cleanupVerified"])
            self.assertEqual("cancelled", result["status"])
            with self.service.lock:
                self.assertEqual(("cancelled_unstarted", "no_submission"), self.service.db.execute(
                    "SELECT handoff_state,cancel_reason FROM rpc_tasks WHERE id='rpc-1'").fetchone())
        finally:
            blocker.set()
            occupied.result(timeout=1)
            thread.join(2)
        self.assertEqual("cancelled", output[0]["status"])
        self.assertIsNone(self.service.runtime)
        self.assertFalse(self.core.calls)
        self.adapter.preflight.assert_not_called()

    def test_claiming_state_cannot_issue_no_submission_proof(self):
        with self.service.lock, self.service.db:
            self.service.db.execute("INSERT INTO rpc_tasks (id,owner,hash,status,handoff_state) VALUES (?,?,?,'running','claiming')",
                                    ("rpc-1", OWNER, "digest"))
        self.assertFalse(self.call("cancel")["cleanupVerified"])
        with self.service.lock:
            self.assertEqual(("claiming", None), self.service.db.execute(
                "SELECT handoff_state,cancel_reason FROM rpc_tasks WHERE id='rpc-1'").fetchone())

    def test_unknown_cancel_refresh_requires_late_verified_receipt(self):
        self.core.result = {"status": "failed", "error": "CLEANUP_UNVERIFIED"}
        self.call()
        self.service.cancellation = Mock(return_value=False)
        self.service.cancellation.receipt.return_value = False
        self.call("cancel")
        pending = self.call("status")
        self.assertEqual("cancel_pending", pending["status"])
        self.assertFalse(pending["cleanupVerified"])
        self.service.cancellation.receipt.return_value = True
        verified = self.call("status")
        self.assertEqual("cancelled", verified["status"])
        self.assertTrue(verified["cleanupVerified"])

    def test_cancel_before_claim_remains_pending_without_receipt(self):
        with self.service.lock, self.service.db:
            self.service.db.execute("INSERT INTO rpc_tasks (id,owner,hash,status,cancel_requested) VALUES (?,?,?,'unknown',1)",
                                    ("rpc-1", OWNER, "digest"))
        self.service.cancellation = Mock()
        self.service.cancellation.receipt.side_effect = OSError("no receipt")
        self.assertEqual("cancel_pending", self.call("status")["status"])

    def test_cancel_intent_blocks_preclaim_and_only_receipt_confirms(self):
        with self.service.lock:
            with self.service.db:
                self.service.db.execute("INSERT INTO rpc_tasks (id,owner,hash,status) VALUES (?,?,?,'running')",
                                        ("rpc-1", OWNER, "digest"))
        self.service.cancellation = Mock(return_value=False)
        self.assertFalse(self.call("cancel")["cleanupVerified"])
        journal = Mock()
        from .control import Rejected
        with self.assertRaises(Rejected):
            ClaimJournal(journal, self.service).claim(value(), "execution")
        journal.claim.assert_not_called()
        self.service.cancellation = Mock(return_value=True)
        self.assertTrue(self.call("cancel")["cleanupVerified"])
        self.assertEqual("cancelled", self.call("status")["status"])

    def test_oversize_worker_response_maps_without_marking_policy_verified(self):
        self.core.last_stage = "limits_verified"
        self.core.result = {"status": "failed", "error": "RESPONSE_TOO_LARGE"}
        self.assertEqual({"status": "failed", "error": "OUTPUT_TOO_LARGE"}, self.call()["result"])
        self.assertIsNone(self.service.last_verified)

    def test_health_fixed_probe_reaper_runtime_guard_and_freshness(self):
        self.assertEqual({"status": "ready", "policyVerified": True}, self.call("health"))
        self.assertEqual("function main(i) { return 1+1; }", self.core.calls[0]["source"])
        self.call("health")
        self.assertEqual(1, len(self.core.calls))
        self.live.assert_called_with(self.journal, "fixed-session")
        self.adapter.preflight.assert_called_with([])
        self.service.last_verified = (time.monotonic() - 61, self.core.image)
        self.assertTrue(self.call("health")["policyVerified"])
        self.assertEqual(2, len(self.core.calls))
        self.adapter.guard = None
        self.assertFalse(self.call("health")["policyVerified"])

    def test_health_failure_on_reaper_pending_wrong_probe_or_image(self):
        self.live.side_effect = RuntimeError("private detail")
        self.assertFalse(self.call("health")["policyVerified"])
        self.assertFalse(self.core.calls)
        self.live.side_effect = None
        self.journal.pending_rows = [object()]
        self.assertFalse(self.call("health")["policyVerified"])
        self.journal.pending_rows = []
        self.adapter.config.image = "sha256:" + "b" * 64
        self.assertFalse(self.call("health")["policyVerified"])

    def test_restart_metadata_only_and_no_replay(self):
        with tempfile.TemporaryDirectory() as folder:
            path = folder + "/metadata"
            service = Orchestrator(path, allowed_uid=UID, owners={OWNER}, factory=self.factory)
            service.handle(UID, message())
            service.close()
            with open(path, "rb") as stored:
                data = stored.read()
            for secret in (b"secret-source", b"secret-input", b"secret-result"):
                self.assertNotIn(secret, data)
            restarted = Orchestrator(path, allowed_uid=UID, owners={OWNER}, factory=self.factory)
            try:
                response = restarted.handle(UID, message())
                self.assertNotIn("result", response)
            finally:
                restarted.close()
            self.assertEqual(1, len(self.core.calls))


class WireTest(unittest.TestCase):
    def test_cancel_uses_independent_connection_and_only_verified_receipt(self):
        journal = Mock()
        journal.wait_cancel.return_value = {"cleanupVerified": False}
        factory = Mock(return_value=journal)
        cancel = CoreCancellation(factory, "session")
        self.assertFalse(cancel("id", "hash", time.monotonic() + 3))
        factory.assert_called_once_with()
        journal.request_cancel.assert_called_once_with("id", "hash", "session")
        journal.close.assert_called_once()
        journal.wait_cancel.return_value = {"cleanupVerified": True, "terminalReason": "normal"}
        self.assertFalse(cancel("id", "hash", time.monotonic() + 3))
        journal.wait_cancel.return_value = {"cleanupVerified": True, "terminalReason": "cancelled"}
        self.assertTrue(cancel("id", "hash", time.monotonic() + 3))

    def test_cancel_not_found_remains_pending_and_connection_is_closed(self):
        from .control import Rejected
        journal = Mock()
        journal.request_cancel.side_effect = Rejected("CANCEL_NOT_FOUND")
        cancellation = CoreCancellation(lambda: journal, "session")
        with self.assertRaises(Rejected):
            cancellation("id", "hash", time.monotonic() + 3)
        journal.wait_cancel.assert_not_called()
        journal.close.assert_called_once()

    def test_root_orchestrator_sets_control_owned_private_socket(self):
        server = RPCServer("/rpc/orchestrator.sock", SimpleNamespace(allowed_uid=UID))
        with patch("supervisor.orchestrator_rpc.private_path") as paths, \
                patch("os.path.lexists", return_value=False), patch("socket.socket") as sockets, \
                patch("os.umask"), patch("os.chown") as chown, patch("os.chmod") as chmod:
            sockets.return_value.__enter__.return_value.accept.side_effect = RuntimeError("stop fake listener")
            with self.assertRaises(RuntimeError):
                server.serve()
            paths.assert_any_call("/rpc/orchestrator.sock", UID, require_socket=False)
            paths.assert_any_call("/rpc/orchestrator.sock", UID)
            chown.assert_called_once_with("/rpc/orchestrator.sock", UID, -1)
            chmod.assert_called_once_with("/rpc/orchestrator.sock", 0o600)
        server.pool.shutdown(wait=True)

    def test_framing_limit_eof_depth_and_nonfinite(self):
        for data in (struct.pack("!I", 131073), struct.pack("!I", 0),
                     struct.pack("!I", 8) + b"{}", struct.pack("!I", 5) + b"1e309"):
            source = io.BytesIO(data)
            sock = Mock()
            sock.recv.side_effect = source.read
            with self.assertRaises(Exception):
                receive(sock, time.monotonic() + 1)
        sock = Mock()
        send(sock, {"op": "health"}, time.monotonic() + 1)
        wire = sock.sendall.call_args.args[0]
        self.assertEqual(len(wire) - 4, struct.unpack("!I", wire[:4])[0])
        with self.assertRaises(RPCError):
            encode({"data": "x" * 131072})

    def test_peer_credentials(self):
        sock = Mock()
        sock.getsockopt.return_value = struct.pack("3i", 123, UID, 0)
        self.assertEqual(UID, peer_uid(sock))
        sock.getsockopt.assert_called_once_with(socket.SOL_SOCKET, socket.SO_PEERCRED, 12)

    def test_private_path_modes_and_ownership(self):
        parent = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=UID)
        endpoint = SimpleNamespace(st_mode=stat.S_IFSOCK | 0o600, st_uid=UID)
        with patch("os.path.realpath", return_value="/private/rpc"), patch("os.stat", side_effect=[parent, endpoint]):
            private_path("/private/rpc", UID)
        parent.st_mode = stat.S_IFDIR | 0o755
        with patch("os.path.realpath", return_value="/private/rpc"), patch("os.stat", return_value=parent):
            with self.assertRaises(RPCError):
                private_path("/private/rpc", UID)

    def test_client_no_retry_and_checks_server_peer(self):
        backend = UnixRPCBackend("/private/rpc", control_uid=UID, orchestrator_uid=0, owners={OWNER})
        with patch("os.geteuid", return_value=UID), patch("supervisor.orchestrator_rpc.private_path"), \
                patch("socket.socket") as factory, patch("supervisor.orchestrator_rpc.peer_uid", return_value=99):
            sock = factory.return_value.__enter__.return_value
            with self.assertRaises(RPCError):
                backend.health(OWNER, time.monotonic() + 24)
            sock.connect.assert_called_once()
            sock.sendall.assert_not_called()


class HTTPContractTest(unittest.TestCase):
    def setUp(self):
        self.backend = Mock()
        self.service = ControlService(":memory:", {OWNER}, self.backend)
        self.addCleanup(self.service.close)
        self.cert = {"subjectAltName": (("DNS", OWNER),)}

    def post(self):
        return self.service.dispatch(self.cert, "POST", "/internal/v1/code-executions", json.dumps(value()).encode())

    def test_synchronous_success_and_duplicate(self):
        self.backend.execute.return_value = {"status": "succeeded", "output": {"n": 2}}
        expected = (200, {"requestId": "rpc-1", "status": "succeeded", "output": {"n": 2}})
        self.assertEqual(expected, self.post())
        self.assertEqual(expected, self.post())
        self.backend.execute.assert_called_once()

    def test_failed_timeout_and_cleanup_mapping(self):
        self.backend.execute.return_value = {"status": "failed", "error": "TIMEOUT"}
        self.assertEqual("TIMEOUT", self.post()[1]["errorCode"])
        self.assertEqual("OUTPUT_TOO_LARGE", ControlService._error("RESPONSE_TOO_LARGE"))
        self.assertEqual("RUNTIME_UNAVAILABLE", ControlService._error("CLEANUP_UNVERIFIED"))

    def test_unknown_no_exception_content(self):
        self.backend.execute.side_effect = OSError("secret-error")
        code, response = self.post()
        self.assertEqual(503, code)
        self.assertEqual("CODE_EXECUTION_UNCERTAIN", response["errorCode"])
        self.assertNotIn("secret", json.dumps(response))

    def test_get_returns_cached_post_result_and_expiry_is_uncertain(self):
        self.backend.execute.return_value = {"status": "succeeded", "output": "中文"}
        expected = self.post()
        self.assertEqual(expected, self.service.dispatch(self.cert, "GET", "/internal/v1/code-executions/rpc-1"))
        self.service.results.clear()
        self.assertEqual("CODE_EXECUTION_UNCERTAIN", self.service.dispatch(
            self.cert, "GET", "/internal/v1/code-executions/rpc-1")[1]["errorCode"])

    def test_normal_receipt_does_not_erase_normal_result_on_cancel(self):
        self.backend.execute.return_value = {"status": "succeeded", "output": 2}
        self.backend.cancel.return_value = False
        expected = self.post()
        self.assertEqual(expected, self.service.dispatch(self.cert, "DELETE", "/internal/v1/code-executions/rpc-1"))
        self.assertEqual(expected, self.service.dispatch(self.cert, "GET", "/internal/v1/code-executions/rpc-1"))

    def test_get_refreshes_late_cancel_receipt_after_execute_unknown(self):
        self.backend.execute.side_effect = OSError("uncertain")
        self.post()
        self.service.cancel_pending.add("rpc-1")
        self.backend.status.return_value = {"requestId": "rpc-1", "status": "cancelled", "cleanupVerified": False}
        self.assertEqual((202, {"requestId": "rpc-1", "status": "cancel_pending"}),
                         self.service.dispatch(self.cert, "GET", "/internal/v1/code-executions/rpc-1"))
        self.backend.status.return_value["cleanupVerified"] = True
        self.assertEqual((200, {"requestId": "rpc-1", "status": "cancelled"}),
                         self.service.dispatch(self.cert, "GET", "/internal/v1/code-executions/rpc-1"))

    def test_cleanup_confirmed_records_reclaimed_after_fixed_window_only(self):
        now = [100000]
        backend = Mock()
        backend.execute.return_value = {"status": "succeeded", "output": 2}
        service = ControlService(":memory:", {OWNER}, backend, capacity=1, wall_clock=lambda: now[0])
        self.addCleanup(service.close)
        self.assertEqual(200, service.dispatch(self.cert, "POST", "/internal/v1/code-executions", json.dumps(value()).encode())[0])
        now[0] += 86399
        self.assertEqual(503, service.dispatch(self.cert, "POST", "/internal/v1/code-executions", json.dumps(value(requestId="next")).encode())[0])
        now[0] += 1
        self.assertEqual(200, service.dispatch(self.cert, "POST", "/internal/v1/code-executions", json.dumps(value(requestId="next")).encode())[0])

    def test_utf8_output_bound_and_request_limit(self):
        self.backend.execute.return_value = {"status": "succeeded", "output": "中" * 19000}
        response = self.post()
        self.assertEqual("succeeded", response[1]["status"])
        self.assertLessEqual(len(json.dumps(response[1], ensure_ascii=False).encode()), 65536)
        large = value(input="x" * 61440)
        self.assertEqual(413, self.service.dispatch(self.cert, "POST", "/internal/v1/code-executions", json.dumps(large).encode())[0])

    def test_health_exact_true_and_auth(self):
        for response, expected in (({"status": "ready", "policyVerified": True}, 200),
                                   ({"status": "ready", "policyVerified": False}, 503),
                                   ({"status": "ready"}, 503)):
            self.backend.health.return_value = response
            self.assertEqual(expected, self.service.dispatch(self.cert, "GET", "/internal/v1/code-health")[0])
        self.assertEqual(403, self.service.dispatch({}, "GET", "/internal/v1/code-health")[0])


if __name__ == "__main__":
    unittest.main()
