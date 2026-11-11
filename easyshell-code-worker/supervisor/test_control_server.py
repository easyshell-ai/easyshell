"""Fake-only control ingress tests: no listener, certificates or daemon access."""

import io
import json
import ssl
import stat
import tempfile
import threading
import unittest
from email.message import Message
from unittest.mock import Mock, patch

from .control_server import (ApiError, ControlHandler, ControlHTTPServer, ControlService,
                             TLSConfig, client_identity, main, trusted_file)


ALLOWED = frozenset({"server.internal", "other.internal"})


def cert(name="server.internal"):
    return {"subjectAltName": (("DNS", name),)}


def payload(**changes):
    value = {"requestId": "job-1", "language": "python", "source": "secret-source",
             "input": {"secret": "private-input"}, "timeoutMs": 1000}
    value.update(changes)
    return json.dumps(value).encode()


class FakeBackend:
    def __init__(self):
        self.started, self.release = threading.Event(), threading.Event()
        self.calls, self.cancels = [], []
        self.verified = False

    def execute(self, owner, value, deadline):
        self.calls.append((owner, value["requestId"], deadline))
        self.started.set()
        self.release.wait(2)
        return {"status": "succeeded", "output": "private-result"}

    def cancel(self, owner, request_id, deadline):
        self.cancels.append((owner, request_id))
        return self.verified


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.backend = FakeBackend()
        self.service = ControlService(":memory:", ALLOWED, self.backend)
        self.addCleanup(self.service.close)
        self.addCleanup(self.backend.release.set)

    def call(self, method="POST", path="/internal/v1/code-executions", body=None, identity="server.internal"):
        return self.service.dispatch(cert(identity), method, path,
                                     payload() if body is None and method == "POST" else body or b"")

    def finish(self):
        self.backend.release.set()
        self.service.pool.shutdown(wait=True)

    def test_default_backend_is_unconfigured(self):
        service = ControlService(":memory:", ALLOWED)
        self.addCleanup(service.close)
        self.assertEqual((503, {"status": "failed", "errorCode": "RUNTIME_UNAVAILABLE"}),
                         service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload()))

    def test_identity_requires_exact_single_dns_san_not_cn(self):
        for certificate in (None, {}, {"subject": ((('commonName', 'server.internal'),),)},
                            cert("*.internal"), cert("unknown.internal"),
                            {"subjectAltName": (("DNS", "server.internal"), ("DNS", "other.internal"))}):
            with self.subTest(certificate=certificate):
                with self.assertRaises(ApiError):
                    client_identity(certificate, ALLOWED)
                self.assertEqual(403, self.service.dispatch(certificate, "POST", "/internal/v1/code-executions", payload())[0])
        self.assertEqual("server.internal", client_identity(cert(), ALLOWED))
        self.assertFalse(self.backend.calls)

    def test_same_id_same_content_status_and_conflicting_content(self):
        self.backend.release.set()
        self.assertEqual((200, {"requestId": "job-1", "status": "succeeded", "output": "private-result"}), self.call())
        self.assertTrue(self.backend.started.wait(1))
        self.assertEqual(200, self.call()[0])
        self.assertEqual(409, self.call(body=payload(source="changed"))[0])
        self.finish()
        status, value = self.call()
        self.assertEqual(200, status)
        self.assertEqual("succeeded", value["status"])
        self.assertEqual(1, len(self.backend.calls))

    def test_cross_client_forbidden_for_all_resource_operations(self):
        self.backend.release.set()
        self.call()
        for method, path in (("GET", "/internal/v1/code-executions/job-1"),
                             ("DELETE", "/internal/v1/code-executions/job-1"), ("POST", "/internal/v1/code-executions")):
            with self.subTest(method=method):
                self.assertEqual(403, self.call(method, path, identity="other.internal")[0])
        self.assertFalse(self.backend.cancels)

    def test_strict_requests_never_reach_backend(self):
        invalid = [b"x" * 65537, payload(timeoutMs=0), payload(timeoutMs=5001),
                   payload(timeoutMs=True), payload(source="x" * 32769), payload(language="sh"),
                   payload(extra="bad"), b'{"requestId":"a","requestId":"a"}',
                   payload(input=float("nan")), payload().replace(b'1000', b'1e309'),
                   payload(input=[[[[]]]]).replace(b'[[[[]]]]', b'[' * 70 + b'0' + b']' * 70),
                   payload(input=list(range(10001)))]
        for body in invalid:
            with self.subTest(size=len(body)):
                self.assertIn(self.call(body=body)[0], (400, 413))
        self.assertFalse(self.backend.calls)

    def test_unverified_cancel_retains_future_result_and_never_replayed(self):
        task = threading.Thread(target=self.call)
        task.start()
        self.assertTrue(self.backend.started.wait(1))
        self.assertEqual((202, {"requestId": "job-1", "status": "cancel_pending"}),
                         self.call("DELETE", "/internal/v1/code-executions/job-1"))
        self.finish()
        task.join(1)
        value = self.call("GET", "/internal/v1/code-executions/job-1")[1]
        self.assertEqual("succeeded", value["status"])
        self.assertEqual("private-result", value["output"])
        self.assertEqual(200, self.call()[0])
        self.assertEqual(1, len(self.backend.calls))

    def test_verified_cancel(self):
        self.backend.verified = True
        task = threading.Thread(target=self.call)
        task.start()
        self.assertTrue(self.backend.started.wait(1))
        self.assertEqual((200, {"requestId": "job-1", "status": "cancelled"}),
                         self.call("DELETE", "/internal/v1/code-executions/job-1"))
        self.finish()
        task.join(1)
        self.assertEqual("cancelled", self.call("GET", "/internal/v1/code-executions/job-1")[1]["status"])

    def test_backend_uncertainty_and_invalid_result_are_not_retried(self):
        for result in (OSError("private exception"), {"status": "succeeded", "output": "x" * 65537},
                       {"status": "failed", "error": "private-error"}):
            with self.subTest(kind=type(result).__name__):
                backend = Mock()
                if isinstance(result, Exception):
                    backend.execute.side_effect = result
                else:
                    backend.execute.return_value = result
                service = ControlService(":memory:", ALLOWED, backend)
                self.addCleanup(service.close)
                service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload())
                service.pool.shutdown(wait=True)
                status, value = service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload())
                if isinstance(result, dict) and result.get("status") == "succeeded":
                    self.assertEqual(200, status)
                    self.assertEqual("OUTPUT_TOO_LARGE", value["errorCode"])
                else:
                    self.assertEqual(503, status)
                    self.assertEqual({"requestId": "job-1", "status": "failed", "errorCode": "CODE_EXECUTION_UNCERTAIN"}, value)
                backend.execute.assert_called_once()

    def test_journal_capacity_fails_closed_without_forgetting_ids(self):
        service = ControlService(":memory:", ALLOWED, self.backend, capacity=1)
        self.addCleanup(service.close)
        self.backend.release.set()
        service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload())
        service.pool.shutdown(wait=True)
        self.assertEqual(503, service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload(requestId="new"))[0])
        self.assertEqual(200, service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload())[0])

    def test_queue_and_rate_bounds(self):
        service = ControlService(":memory:", ALLOWED, self.backend, concurrency=1, queue_size=0)
        self.addCleanup(service.close)
        task = threading.Thread(target=lambda: service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload()))
        task.start()
        self.assertTrue(self.backend.started.wait(1))
        self.assertEqual(429, service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload(requestId="job-2"))[0])
        self.assertEqual(429, service.dispatch(cert(), "DELETE", "/internal/v1/code-executions/job-1")[0])
        service.rate = 3
        self.assertEqual(429, service.dispatch(cert(), "GET", "/internal/v1/code-executions/job-1")[0])
        self.backend.release.set()
        task.join(1)

    def test_ttl_and_restart_never_persist_content_or_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            path, now = directory + "/state.sqlite", [0]
            service = ControlService(path, ALLOWED, self.backend, clock=lambda: now[0], result_ttl=5)
            self.backend.release.set()
            self.assertEqual(200, service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload())[0])
            service.pool.shutdown(wait=True)
            self.assertEqual({"requestId": "job-1", "status": "succeeded", "output": "private-result"},
                             service.dispatch(cert(), "GET", "/internal/v1/code-executions/job-1")[1])
            now[0] = 6
            self.assertEqual(503, service.dispatch(cert(), "POST", "/internal/v1/code-executions", payload())[0])
            service.close()
            with open(path, "rb") as state:
                data = state.read()
            for secret in (b"secret-source", b"private-input", b"private-result"):
                self.assertNotIn(secret, data)
            restarted = ControlService(path, ALLOWED, self.backend)
            self.addCleanup(restarted.close)
            response = restarted.dispatch(cert(), "POST", "/internal/v1/code-executions", payload())
            self.assertEqual(503, response[0])
            self.assertEqual("CODE_EXECUTION_UNCERTAIN", response[1]["errorCode"])
            self.assertEqual(1, len(self.backend.calls))


class TransportTest(unittest.TestCase):
    def test_legacy_paths_are_not_public_aliases(self):
        service = ControlService(":memory:", ALLOWED)
        self.addCleanup(service.close)
        for method, path in (("POST", "/v1/execute"), ("GET", "/v1/executions/job-1"),
                             ("DELETE", "/v1/executions/job-1")):
            self.assertEqual(404, service.dispatch(cert(), method, path)[0])

    def test_cli_requires_unprivileged_identity_and_binds_trusted_configuration(self):
        args = ["--listen-host", "127.0.0.1", "--listen-port", "9443", "--cert", "/tls/cert",
                "--key", "/tls/key", "--ca", "/tls/ca", "--client-dns", "server.internal",
                "--rpc-socket", "/rpc/orchestrator.sock", "--control-uid", "17001",
                "--orchestrator-uid", "0", "--journal", "/control/state.sqlite"]
        with patch("os.getuid", return_value=0), patch("os.geteuid", return_value=0), \
                patch("supervisor.control_server.ControlHTTPServer") as server:
            self.assertEqual(1, main(args))
            server.assert_not_called()
        with patch("os.getuid", return_value=17001), patch("os.geteuid", return_value=17001), \
                patch("supervisor.control_server.trusted_file") as files, \
                patch("supervisor.orchestrator_rpc.private_path") as paths, \
                patch("os.path.lexists", return_value=False), patch("os.umask"), \
                patch.object(ControlService, "with_unix_rpc") as binding, \
                patch("supervisor.control_server.ControlHTTPServer") as server:
            self.assertEqual(0, main(args))
            binding.assert_called_once_with("/control/state.sqlite", frozenset({"server.internal"}),
                                            rpc_socket="/rpc/orchestrator.sock", control_uid=17001,
                                            orchestrator_uid=0)
            self.assertEqual(3, files.call_count)
            paths.assert_any_call("/rpc/orchestrator.sock", 17001)
            server.return_value.serve_forever.assert_called_once()
            server.return_value.server_close.assert_called_once()

    def test_tls_files_require_control_ownership_and_0600(self):
        info = Mock(st_uid=17001, st_mode=stat.S_IFREG | 0o600)
        with patch("os.path.realpath", return_value="/tls/cert"), patch("os.stat", return_value=info):
            trusted_file("/tls/cert", 17001)
            info.st_uid = 0
            with self.assertRaises(ValueError):
                trusted_file("/tls/cert", 17001)
            info.st_uid, info.st_mode = 17001, stat.S_IFREG | 0o644
            with self.assertRaises(ValueError):
                trusted_file("/tls/cert", 17001)

    def handler(self, headers=None, body=b"", tls=True):
        handler = ControlHandler.__new__(ControlHandler)
        handler.connection = Mock(spec=ssl.SSLSocket) if tls else Mock()
        handler.connection.context.verify_mode = ssl.CERT_REQUIRED
        handler.connection.getpeercert.return_value = cert()
        handler.server = Mock()
        handler.server.service.allowed = ALLOWED
        handler.server.service.dispatch.return_value = (200, {"requestId": "job-1", "status": "succeeded", "output": {}})
        handler.headers = Message()
        for key, value in headers or []:
            handler.headers[key] = value
        handler.command, handler.path = "POST", "/internal/v1/code-executions"
        handler.rfile, handler.wfile = io.BytesIO(body), io.BytesIO()
        handler.send_response = Mock()
        handler.send_header = Mock()
        handler.end_headers = Mock()
        return handler

    def test_http_rejects_plaintext_framing_and_oversize_before_dispatch(self):
        cases = [(False, [], 403), (True, [("Transfer-Encoding", "chunked")], 400),
                 (True, [("Content-Length", "1"), ("Content-Length", "1")], 400),
                 (True, [("Content-Length", "65537")], 413),
                 (True, [("Content-Length", "-1")], 400),
                 (True, [("Expect", "100-continue")], 400)]
        for tls, headers, expected in cases:
            with self.subTest(headers=headers):
                handler = self.handler(headers, tls=tls)
                handler._handle()
                handler.send_response.assert_called_once_with(expected)
                handler.server.service.dispatch.assert_not_called()

    def test_http_json_and_no_store(self):
        body = payload()
        handler = self.handler([("Content-Length", str(len(body))), ("Content-Type", "application/json")], body)
        handler._handle()
        handler.send_response.assert_called_once_with(200)
        handler.send_header.assert_any_call("Cache-Control", "no-store")
        handler.server.service.dispatch.assert_called_once_with(cert(), "POST", "/internal/v1/code-executions", body)

    def test_tls_config_required_without_reading_real_credentials(self):
        with self.assertRaises(ValueError):
            TLSConfig("", "", "", ALLOWED).context()
        with patch("ssl.SSLContext") as factory:
            context = TLSConfig("fake-cert", "fake-key", "fake-ca", ALLOWED).context()
            self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
            context.load_verify_locations.assert_called_once_with(cafile="fake-ca")
            context.load_cert_chain.assert_called_once_with("fake-cert", "fake-key")

    def test_absolute_connection_timer_covers_handshake(self):
        server = ControlHTTPServer.__new__(ControlHTTPServer)
        server.request_seconds = 5
        server.connections = Mock()
        server.tls_context = Mock()
        secured = server.tls_context.wrap_socket.return_value
        def handshake():
            timer.call_args.args[1]()
            raise TimeoutError()
        secured.do_handshake.side_effect = handshake
        with patch("threading.Timer") as timer:
            server.process_request_thread(Mock(), ("fake", 0))
        timer.assert_called_once()
        secured.shutdown.assert_called_once()
        secured.close.assert_called_once()
        server.connections.release.assert_called_once()


if __name__ == "__main__":
    unittest.main()
