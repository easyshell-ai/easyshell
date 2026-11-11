"""Explicit four-task execution acceptance; default supervisor CLI stays disabled."""

import argparse
import json
import os

from .control import Journal, Supervisor
from .docker_adapter import Config, DockerAdapter
from .host_guard import HostGuard


def safe_result(result, supervisor):
    statuses = ("failed", "succeeded")
    errors = ("TIMEOUT", "EXECUTION_FAILED", "RESOURCE_EXHAUSTED", "HOST_LIMITS_UNVERIFIED",
              "REAPER_UNVERIFIED", "STATE_UNVERIFIED", "CLEANUP_UNVERIFIED", "RUNTIME_UNCERTAIN",
              "ATTACH_CONNECTION_FAILED", "ATTACH_MALFORMED", "RESULT_UNVERIFIED",
              "INVALID_JSON", "ISOLATION_UNVERIFIED", "RESPONSE_TOO_LARGE",
              "JSON_TOO_LARGE", "JSON_TOO_COMPLEX", "INVALID_REQUEST", "REQUEST_TOO_LARGE",
              "PROFILE_UNVERIFIED", "DUPLICATE_REQUEST", "REQUEST_ID_CONFLICT", "JOURNAL_FULL",
              "OWNERSHIP_UNVERIFIED", "IMAGE_UNVERIFIED", "POLICY_UNVERIFIED", "CLEANUP_UNCERTAIN",
              "CLEANUP_TIMEOUT", "EXECUTION_UNVERIFIED", "CREATE_UNCERTAIN", "OUTPUT_TOO_LARGE",
              "ATTACH_CLOSE_FAILED", "RECONCILE_UNVERIFIED", "DEDICATED_SOCKET_REQUIRED",
              "DAEMON_ID_REQUIRED", "IMAGE_DIGEST_REQUIRED", "CONFIG_UNVERIFIED",
              "CGROUP_CLEANUP_UNVERIFIED", "SOCKET_UNVERIFIED", "DOCKER_REQUEST_TOO_LARGE",
              "DOCKER_RESPONSE_TOO_LARGE", "DOCKER_OPERATION_FAILED", "DAEMON_UNVERIFIED",
              "DAEMON_NOT_DEDICATED", "ATTACH_UPGRADE_FAILED", "ATTACH_ALREADY_USED",
              "EXIT_UNVERIFIED", "UNEXPECTED_WORKER_RESPONSE", "LEASE_UNVERIFIED", "JOURNAL_UNVERIFIED",
              "CRASH_GATE_RELEASED", "CRASH_STAGE_TIMEOUT", "CRASH_STAGE_UNVERIFIED",
              "CRASH_EVIDENCE_UNVERIFIED", "CRASH_FRESH_JOURNAL_REQUIRED",
              "CRASH_CHILD_ALREADY_EXITED", "CRASH_KILL_UNVERIFIED", "CRASH_REAPER_TIMEOUT",
              "CRASH_JOURNAL_UNVERIFIED")
    errors += ("CANCEL_NOT_FOUND", "CANCEL_CLAIM_MISMATCH", "CLEANUP_LEASE_LOST",
               "CLEANUP_REASON_UNVERIFIED", "CLEANUP_BUSY", "CANCELLED", "CANCEL_PENDING")
    stages = ("claimed", "preflight", "created", "started", "limits_verified", "source_delivered",
              "response_received", "response_validated")
    return {"result_status": result.get("status") if result.get("status") in statuses else "unknown",
            "result_error": ("none" if "error" not in result else
                             result.get("error") if result.get("error") in errors else "unknown"),
            "stage": supervisor.last_stage if supervisor.last_stage in stages else "unknown",
            "timeout_origin": (getattr(supervisor, "last_timeout_origin", "unknown")
                               if getattr(supervisor, "last_timeout_origin", "unknown") in ("none", "worker", "external") else "unknown")}


def run(adapter, journal):
    observations = []
    cases = (
        ("javascript", "function main(i) { return {n:i.n+1}; }", "normal"),
        ("python", "def main(i):\n return {'n': i['n']+1}", "normal"),
        ("javascript", "function main(i) { while(true) {} }", "timeout"),
        ("python", "def main(i):\n while True: pass", "timeout"),
    )
    try:
        if adapter.config.profile != "execution":
            raise ValueError()
        adapter.preflight([])
        supervisor = Supervisor(adapter, journal, adapter.config.image, profile="execution")
        for index, (language, source, kind) in enumerate(cases):
            adapter.preflight([])
            result = supervisor.execute({"requestId": "execution-acceptance-v2-" + str(index),
                                         "language": language, "source": source, "input": {"n": 3},
                                         "timeoutMs": 5000 if kind == "normal" else 1000})
            # execute only returns success/timeout after cleanup; additionally demand empty daemon.
            adapter.preflight([])
            expected = ({"status": "succeeded", "output": {"n": 4}} if kind == "normal"
                        else {"status": "failed", "error": "TIMEOUT"})
            if result != expected or (kind == "timeout" and (
                    supervisor.last_delivery is not True or supervisor.last_timeout_origin != "worker")):
                return {"status": "failed", "error": "CASE_UNVERIFIED", "case": index,
                        **safe_result(result, supervisor)}
            observations.append({"language": language, "case": kind, "cleaned": True,
                                 "timeout_origin": supervisor.last_timeout_origin})
        return {"status": "passed", "cases": observations}
    except Exception:
        return {"status": "failed", "error": "EXECUTION_ACCEPTANCE_UNVERIFIED"}


def main():
    parser = argparse.ArgumentParser(description="Explicit execution profile acceptance on empty dedicated daemon")
    parser.add_argument("--socket", required=True)
    parser.add_argument("--expected-daemon-id", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--journal", required=True)
    parser.add_argument("--reaper-session", required=True)
    args = parser.parse_args()
    journal = None
    try:
        config = Config(args.socket, args.expected_daemon_id, args.image, profile="execution")
        if not os.path.isfile(args.journal) or os.path.islink(args.journal):
            raise ValueError()
        journal = Journal(args.journal)
        adapter = DockerAdapter(config, guard=HostGuard())
        adapter.reaper_session = args.reaper_session
        report = run(adapter, journal)
    except Exception:
        report = {"status": "failed", "error": "CONFIG_OR_JOURNAL_UNVERIFIED"}
    finally:
        if journal is not None:
            journal.close()
    print(json.dumps(report, separators=(",", ":")))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
