"""Explicit fixed HTTP acceptance. No Docker access or arbitrary guest source."""

import argparse
import concurrent.futures
import http.client
import ipaddress
import json
import re
import socket
import ssl
import threading
import time
import uuid
from urllib.parse import urlsplit

from .control import decode_json


BASE = "/internal/v1/code-executions"
MAX_REQUEST = 60 * 1024
MAX_RESPONSE = 65536


class AcceptanceError(Exception):
    pass


SAFE_STATUSES = frozenset({"queued", "running", "cancel_pending", "cancelling", "cancelled",
                           "succeeded", "failed", "unknown", "unavailable"})
SAFE_ERRORS = frozenset({"CODE_EXECUTION_UNCERTAIN", "RUNTIME_UNAVAILABLE", "POLICY_UNVERIFIED",
                         "TIMEOUT", "RESOURCE_EXHAUSTED", "EXECUTION_FAILED", "INVALID_REQUEST",
                         "INPUT_TOO_LARGE", "OUTPUT_TOO_LARGE", "RATE_LIMITED", "CANCELLED"})


def safe_observation(stage, response=None, transport=False):
    status, body = response if isinstance(response, tuple) and len(response) == 2 else (None, {})
    body = body if isinstance(body, dict) else {}
    state, error = body.get("status"), body.get("errorCode")
    return {"stage": stage,
            "httpStatus": status if type(status) is int and 100 <= status <= 599 else None,
            "status": state if isinstance(state, str) and state in SAFE_STATUSES else "unrecognized",
            "errorCode": ("none" if "errorCode" not in body else
                          error if isinstance(error, str) and error in SAFE_ERRORS else "unrecognized"),
            "transport": "unverified" if transport else "response"}


class CancelError(AcceptanceError):
    def __init__(self, stage, observations, code="ACCEPTANCE_UNVERIFIED"):
        super().__init__(code)
        self.stage, self.observations = stage, observations


def origin(value):
    try:
        parsed = urlsplit(value)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.path or parsed.query
                or parsed.fragment or parsed.username is not None or parsed.password is not None
                or any(c.isspace() for c in value) or "?" in value or "#" in value):
            raise ValueError()
        host, port = parsed.hostname, parsed.port or 443
        try:
            ipaddress.ip_address(host)
        except ValueError:
            if not re.fullmatch(r"[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*", host):
                raise ValueError() from None
        if not 1 <= port <= 65535:
            raise ValueError()
        return host, port
    except ValueError:
        raise AcceptanceError("HTTPS_ORIGIN_REQUIRED") from None


class Client:
    def __init__(self, endpoint, ca, cert, key):
        self.host, self.port = origin(endpoint)
        self.context = ssl.create_default_context(cafile=ca)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.context.load_cert_chain(cert, key)

    def call(self, method, path, value=None, *, deadline=None):
        end = min(deadline, time.monotonic() + 25) if deadline else time.monotonic() + 25
        left = end - time.monotonic()
        if left <= 0:
            raise AcceptanceError("HTTP_DEADLINE")
        body = None if value is None else json.dumps(
            value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
        if body is not None and len(body) > MAX_REQUEST:
            raise AcceptanceError("REQUEST_ENVELOPE_TOO_LARGE")
        connection = http.client.HTTPSConnection(self.host, self.port, context=self.context, timeout=left)

        def expire():
            if connection.sock is not None:
                try:
                    connection.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

        timer = threading.Timer(left, expire)
        timer.daemon = True
        timer.start()
        try:
            connection.request(method, path, body, {"Content-Type": "application/json"} if body else {})
            response = connection.getresponse()
            data = response.read(MAX_RESPONSE + 1)
            if time.monotonic() >= end or len(data) > MAX_RESPONSE:
                raise AcceptanceError("RESPONSE_ENVELOPE_UNVERIFIED")
            if response.getheader("Content-Type", "").split(";", 1)[0] != "application/json":
                raise AcceptanceError("RESPONSE_PROTOCOL_UNVERIFIED")
            result = decode_json(data, MAX_RESPONSE)
            if not isinstance(result, dict):
                raise AcceptanceError("RESPONSE_PROTOCOL_UNVERIFIED")
            return response.status, result
        except Exception:
            raise AcceptanceError("HTTP_OR_PROTOCOL_UNVERIFIED") from None
        finally:
            timer.cancel()
            connection.close()


def fixed_request(request_id, language="javascript", *, loop=False):
    if not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", request_id):
        raise AcceptanceError("REQUEST_ID_INVALID")
    source = ("function main(i) { while(true) {} }" if loop else
              "function main(i) { return {n:i.n+1}; }")
    if language == "python":
        source = "def main(i):\n while True: pass" if loop else "def main(i):\n return {'n': i['n']+1}"
    return {"requestId": request_id, "language": language, "source": source,
            "input": {"n": 3}, "timeoutMs": 5000 if not loop else 1000}


def require(condition):
    if not condition:
        raise AcceptanceError("CASE_UNVERIFIED")


def cancel_case(client, request_id, *, clock=time.monotonic, pause=time.sleep):
    value = fixed_request(request_id, loop=True)
    value["timeoutMs"] = 5000
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    future = pool.submit(client.call, "POST", BASE, value)
    observations = []
    stage = "await_running"

    def record(item):
        # Keep bounded recent protocol observations, never body/source/id/output.
        observations.append(item)
        if len(observations) > 12:
            del observations[0]

    def call(method, deadline):
        try:
            response = client.call(method, BASE + "/" + request_id, deadline=deadline)
        except Exception:
            record(safe_observation(stage, transport=True))
            raise CancelError(stage, observations) from None
        record(safe_observation(stage, response))
        return response

    try:
        end = clock() + 5
        while clock() < end:
            status, current = call("GET", end)
            if status == 200 and current == {"requestId": request_id, "status": "running"}:
                break
            require(status == 404 or (status == 200 and current.get("status") == "queued"))
            pause(min(0.05, max(0, end - clock())))
        else:
            raise AcceptanceError("RUNNING_UNVERIFIED")
        end = clock() + 5
        stage = "delete"
        status, current = call("DELETE", end)
        require((status, current) in (
            (202, {"requestId": request_id, "status": "cancel_pending"}),
            (200, {"requestId": request_id, "status": "cancelled"})))
        while clock() < end:
            stage = "poll_terminal"
            status, current = call("GET", end)
            if status == 200 and current == {"requestId": request_id, "status": "cancelled"}:
                # A broken POST transport still fails acceptance, even if GET
                # confirms cleanup. Never treat TLS/network exceptions as pass.
                stage = "post_result"
                try:
                    post_status, post_result = future.result(timeout=25)
                except Exception:
                    record(safe_observation(stage, transport=True))
                    raise CancelError(stage, observations) from None
                record(safe_observation(stage, (post_status, post_result)))
                require(post_status == 200 and (
                    post_result == {"requestId": request_id, "status": "cancelled"}
                    or post_result == {"requestId": request_id, "status": "failed", "errorCode": "CANCELLED"}))
                return
            require(status == 202 and current == {"requestId": request_id, "status": "cancel_pending"}
                    or status == 200 and current == {"requestId": request_id, "status": "running"})
            pause(min(0.05, max(0, end - clock())))
        raise AcceptanceError("CANCEL_PENDING_UNVERIFIED")
    except CancelError:
        raise
    except Exception as exc:
        code = "CANCEL_PENDING_UNVERIFIED" if isinstance(exc, AcceptanceError) and exc.args == ("CANCEL_PENDING_UNVERIFIED",) else "ACCEPTANCE_UNVERIFIED"
        raise CancelError(stage, observations, code) from None
    finally:
        pool.shutdown(wait=True)  # Each real HTTP call has a 25s absolute limit.
        # Include the POST outcome on DELETE/poll assertion failures as well.
        # Updating this list in finally also updates the raised CancelError.
        if stage != "post_result":
            try:
                record(safe_observation("post_result", future.result()))
            except Exception:
                record(safe_observation("post_result", transport=True))


def run(client, other_client=None, *, token=None, clock=time.monotonic, pause=time.sleep):
    prefix = "accept-" + (token or uuid.uuid4().hex)
    observations = []
    cases = []
    def health():
        status, result = client.call("GET", "/internal/v1/code-health", deadline=clock() + 10)
        require(status == 200 and result == {"status": "ready", "policyVerified": True})
        if other_client is not None:
            status, result = other_client.call("GET", "/internal/v1/code-health", deadline=clock() + 10)
            require(status == 200 and result == {"status": "ready", "policyVerified": True})
    cases.append(("health", health))
    for language in ("javascript", "python"):
        def normal(language=language):
            value = fixed_request(prefix + "-" + language, language)
            expected = (200, {"requestId": value["requestId"], "status": "succeeded", "output": {"n": 4}})
            require(client.call("POST", BASE, value) == expected)
            require(client.call("GET", BASE + "/" + value["requestId"]) == expected)
            require(client.call("POST", BASE, value) == expected)
            changed = {**value, "input": {"n": 4}}
            status, result = client.call("POST", BASE, changed)
            require(status == 409 and result.get("status") == "failed")
            if other_client is not None:
                for method, path, body in (("GET", BASE + "/" + value["requestId"], None),
                                           ("DELETE", BASE + "/" + value["requestId"], None),
                                           ("POST", BASE, value)):
                    status, _ = other_client.call(method, path, body)
                    require(status == 403)
        cases.append((language + "_normal_get_idempotency_conflict", normal))
        def timeout(language=language):
            value = fixed_request(prefix + "-timeout-" + language, language, loop=True)
            require(client.call("POST", BASE, value) == (
                200, {"requestId": value["requestId"], "status": "failed", "errorCode": "TIMEOUT"}))
        cases.append((language + "_timeout", timeout))
    cases.append(("cancel", lambda: cancel_case(client, prefix + "-cancel", clock=clock, pause=pause)))
    for name, operation in cases:
        try:
            operation()
            observations.append({"case": name, "status": "passed"})
        except Exception as exc:
            code = "CANCEL_PENDING_UNVERIFIED" if isinstance(exc, AcceptanceError) and exc.args == ("CANCEL_PENDING_UNVERIFIED",) else "ACCEPTANCE_UNVERIFIED"
            report = {"status": "failed", "case": name, "error": code, "cases": observations}
            if isinstance(exc, CancelError):
                report.update(stage=exc.stage, diagnostics=exc.observations)
            return report
    return {"status": "passed", "cases": observations,
            "crossOwner": "checked" if other_client else "not_checked",
            "cleanupEvidence": "backend_contract", "containerCount": "not_observed"}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Explicit fixed mTLS control acceptance; no Docker access")
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--cacert", required=True)
    parser.add_argument("--clientcert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--other-clientcert")
    parser.add_argument("--other-key")
    args = parser.parse_args(argv)
    try:
        if bool(args.other_clientcert) != bool(args.other_key):
            raise AcceptanceError("SECOND_IDENTITY_PAIR_REQUIRED")
        client = Client(args.endpoint, args.cacert, args.clientcert, args.key)
        other = Client(args.endpoint, args.cacert, args.other_clientcert, args.other_key) if args.other_key else None
        report = run(client, other)
    except Exception:
        report = {"status": "failed", "error": "CONFIG_OR_TLS_UNVERIFIED"}
    print(json.dumps(report, separators=(",", ":")))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
