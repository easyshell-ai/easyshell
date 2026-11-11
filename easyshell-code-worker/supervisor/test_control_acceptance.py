"""Acceptance client fakes: no real TLS, listeners, certificates or containers."""

import contextlib
import io
import json
import ssl
import threading
import unittest
from unittest.mock import Mock, patch

from .control_acceptance import (BASE, AcceptanceError, Client, cancel_case,
                                 fixed_request, main, origin, run, safe_observation)


class FakeClient:
    def __init__(self):
        self.lock = threading.Lock()
        self.records, self.calls, self.executions = {}, [], 0
        self.cancel_requested = threading.Event()
        self.cancel_finished = threading.Event()
        self.pending = False
        self.broken_post = False
        self.bad_timeout = False

    def call(self, method, path, value=None, *, deadline=None):
        self.calls.append((method, path))
        if path == "/internal/v1/code-health":
            return 200, {"status": "ready", "policyVerified": True}
        if method == "POST":
            request_id = value["requestId"]
            with self.lock:
                previous = self.records.get(request_id)
                if previous:
                    return (200, previous[1]) if previous[0] == value else (409, {"status": "failed"})
                self.executions += 1
                if request_id.endswith("-cancel"):
                    self.records[request_id] = (value, {"requestId": request_id, "status": "running"})
                else:
                    result = ({"requestId": request_id, "status": "failed", "errorCode": "TIMEOUT"}
                              if "while" in value["source"] else
                              {"requestId": request_id, "status": "succeeded", "output": {"n": 4}})
                    if self.bad_timeout and "while" in value["source"]:
                        result["errorCode"] = "CODE_EXECUTION_UNCERTAIN"
                    self.records[request_id] = (value, result)
                    return 200, result
            self.cancel_requested.wait(1)
            if self.broken_post:
                raise ssl.SSLError("private certificate info")
            return 200, {"requestId": request_id, "status": "cancelled"}
        request_id = path.rsplit("/", 1)[1]
        with self.lock:
            if request_id not in self.records:
                return 404, {"status": "failed"}
            if method == "DELETE":
                self.cancel_requested.set()
                status = "cancel_pending" if self.pending else "cancelled"
                self.records[request_id] = (self.records[request_id][0], {"requestId": request_id, "status": status})
                return (202 if self.pending else 200), self.records[request_id][1]
            result = self.records[request_id][1]
            return (202 if result["status"] == "cancel_pending" else 200), result


class AcceptanceTest(unittest.TestCase):
    def test_full_fixed_suite_and_no_duplicate_fake_execution(self):
        client = FakeClient()
        report = run(client, token="fake")
        self.assertEqual("passed", report["status"])
        self.assertEqual(5, client.executions)
        self.assertEqual("not_checked", report["crossOwner"])
        self.assertEqual("not_observed", report["containerCount"])
        self.assertEqual(6, len(report["cases"]))

    def test_cross_owner_all_resource_methods(self):
        other = Mock()
        other.call.side_effect = lambda method, path, *args, **kwargs: (
            (200, {"status": "ready", "policyVerified": True}) if path.endswith("code-health")
            else (403, {"status": "failed"}))
        self.assertEqual("checked", run(FakeClient(), other, token="fake")["crossOwner"])
        self.assertEqual(7, other.call.call_count)
        other.call.side_effect = None
        other.call.return_value = (200, {"status": "failed"})
        self.assertEqual("failed", run(FakeClient(), other, token="fake")["status"])

    def test_uncertain_loop_never_passes_as_timeout(self):
        client = FakeClient()
        client.bad_timeout = True
        report = run(client, token="fake")
        self.assertEqual("failed", report["status"])
        self.assertEqual("javascript_timeout", report["case"])

    def test_cancel_pending_fails_after_bounded_poll(self):
        client = FakeClient()
        client.pending = True
        now = [0.0]
        def pause(seconds):
            now[0] += max(seconds, 0.01)
        with self.assertRaises(AcceptanceError):
            cancel_case(client, "fake-cancel", clock=lambda: now[0], pause=pause)
        self.assertLessEqual(now[0], 5.1)

    def test_tls_exception_even_with_cancelled_get_never_passes(self):
        client = FakeClient()
        client.broken_post = True
        report = run(client, token="fake")
        self.assertEqual("failed", report["status"])
        self.assertEqual("cancel", report["case"])
        self.assertEqual("post_result", report["stage"])
        self.assertEqual("unverified", report["diagnostics"][-1]["transport"])
        self.assertNotIn("private", json.dumps(report))
        client = Mock()
        client.call.side_effect = ssl.SSLError("secret")
        self.assertEqual("failed", run(client)["status"])

    def test_cancel_delete_uncertainty_reports_stage_and_post_without_raw(self):
        delegate = FakeClient()
        class EarlyCancel:
            def call(self, method, path, value=None, **kwargs):
                if method == "DELETE":
                    delegate.cancel_requested.set()
                    return 503, {"status": "failed", "errorCode": "CODE_EXECUTION_UNCERTAIN",
                                 "output": "secret", "stack": "secret"}
                return delegate.call(method, path, value, **kwargs)
        report = run(EarlyCancel(), token="fake")
        self.assertEqual("delete", report["stage"])
        self.assertEqual(503, report["diagnostics"][-2]["httpStatus"])
        self.assertEqual("CODE_EXECUTION_UNCERTAIN", report["diagnostics"][-2]["errorCode"])
        self.assertEqual("post_result", report["diagnostics"][-1]["stage"])
        self.assertNotIn("secret", json.dumps(report))

    def test_cancel_poll_unknown_reports_exact_failed_assertion(self):
        delegate = FakeClient()
        class PollUnknown:
            def call(self, method, path, value=None, **kwargs):
                if method == "DELETE":
                    delegate.cancel_requested.set()
                    return 202, {"requestId": path.rsplit("/", 1)[1], "status": "cancel_pending"}
                if method == "GET" and delegate.cancel_requested.is_set():
                    return 503, {"status": "failed", "errorCode": "CODE_EXECUTION_UNCERTAIN"}
                return delegate.call(method, path, value, **kwargs)
        report = run(PollUnknown(), token="fake")
        self.assertEqual("poll_terminal", report["stage"])
        self.assertEqual("CODE_EXECUTION_UNCERTAIN", report["diagnostics"][-2]["errorCode"])

    def test_diagnostics_only_allow_fixed_scalars(self):
        observation = safe_observation("delete", (503, {"status": ["secret"], "errorCode": "secret", "output": "secret"}))
        self.assertEqual("unrecognized", observation["status"])
        self.assertEqual("unrecognized", observation["errorCode"])
        self.assertNotIn("secret", json.dumps(observation))

    def test_all_default_request_ids_within_core_limit(self):
        generated = []
        original = fixed_request
        def capture(request_id, *args, **kwargs):
            generated.append(request_id)
            return original(request_id, *args, **kwargs)
        with patch("supervisor.control_acceptance.fixed_request", side_effect=capture):
            self.assertEqual("passed", run(FakeClient())["status"])
        self.assertTrue(all(len(request_id) <= 64 for request_id in generated))
        with self.assertRaises(AcceptanceError):
            fixed_request("x" * 65)

    def test_origin_exactly_https_origin(self):
        self.assertEqual(("localhost", 19443), origin("https://localhost:19443"))
        self.assertEqual(("::1", 443), origin("https://[::1]"))
        for value in ("http://localhost", "https://localhost/", "https://localhost/path",
                      "https://user:pass@localhost", "https://localhost?", "https://localhost#",
                      "https://localhost:99999", "https://localhost bad", "https://"):
            with self.subTest(value=value), self.assertRaises(AcceptanceError):
                origin(value)

    def test_cli_hides_credentials_and_errors(self):
        args = ["--endpoint", "https://localhost:19443", "--cacert", "private-ca",
                "--clientcert", "private-cert", "--key", "private-key"]
        with patch("supervisor.control_acceptance.Client", return_value=FakeClient()), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, main(args))
        self.assertNotIn("private", output.getvalue())
        with patch("supervisor.control_acceptance.Client", side_effect=OSError("private-secret")), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, main(args))
        self.assertNotIn("private", output.getvalue())

    def test_http_transport_bounds_and_default_tls_verification(self):
        with patch("ssl.create_default_context") as context:
            client = Client("https://localhost:19443", "fake-ca", "fake-cert", "fake-key")
            context.assert_called_once_with(cafile="fake-ca")
            context.return_value.load_cert_chain.assert_called_once_with("fake-cert", "fake-key")
        response = Mock(status=200)
        response.getheader.return_value = "application/json"
        response.read.return_value = b'{"status":"ready","policyVerified":true}'
        with patch("http.client.HTTPSConnection") as factory:
            connection = factory.return_value
            connection.getresponse.return_value = response
            self.assertEqual(200, client.call("GET", "/internal/v1/code-health")[0])
            response.read.assert_called_once_with(65537)
            connection.close.assert_called_once()
            response.read.return_value = b"x" * 65537
            with self.assertRaises(AcceptanceError):
                client.call("GET", "/internal/v1/code-health")
        with self.assertRaises(AcceptanceError):
            client.call("POST", BASE, fixed_request("fake") | {"input": "x" * 61440})


if __name__ == "__main__":
    unittest.main()
