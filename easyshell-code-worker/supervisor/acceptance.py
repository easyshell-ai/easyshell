"""Explicit, one-shot dedicated-daemon acceptance. Never invoked by the default CLI."""

import argparse
import json
import os
import time
from dataclasses import replace

from .control import Journal, Rejected, Supervisor, policy, verify, worker_response
from .docker_adapter import Config, DockerAdapter
from .diagnostics import policy_diff


def run(adapter, journal, clock=time.monotonic, pause=time.sleep):
    report = {"status": "failed", "error": "ACCEPTANCE_UNVERIFIED", "cleaned": False}
    record, channel, attempted = None, None, False
    deadline = clock() + 15
    differences = []

    def checked_inspect(cid):
        raw = adapter.inspect(cid).raw
        differences[:] = policy_diff(raw, record, adapter.config.image)
        return verify(raw, record, adapter.config.image)
    try:
        # No journal-owned exceptions: acceptance must begin with zero containers, including stopped.
        adapter.preflight([])
        record = journal.claim({"requestId": "refusal-lifecycle-acceptance-v1",
                                "language": "python", "source": "", "input": {}, "timeoutMs": 5000})
        adapter.preflight([record])
        attempted = True
        cid = adapter.create(record.name, policy(adapter.config.image, record))
        record = replace(record, container_id=cid)
        journal.bind(record, cid)
        state = checked_inspect(cid)
        if state["Status"] != "created":
            raise Rejected("STATE_UNVERIFIED")
        channel = adapter.attach(cid, deadline)
        adapter.start(cid, deadline)
        closed_pipe = False
        try:
            # No source/input/user payload is delivered to the refusal-only Worker.
            result = worker_response(channel.exchange(b""))
            if result != {"status": "failed", "error": "ISOLATION_UNVERIFIED"}:
                raise Rejected("UNEXPECTED_WORKER_RESPONSE")
        except Rejected as error:
            if error.code != "ATTACH_CONNECTION_FAILED":
                raise
            closed_pipe = True
        completed, channel = channel, None
        completed.close()
        while True:
            if clock() >= deadline:
                raise Rejected("TIMEOUT")
            state = checked_inspect(cid)
            if state["Status"] == "exited":
                if (type(state.get("ExitCode")) is not int or state["ExitCode"] != 0
                        or state.get("OOMKilled") is not False):
                    raise Rejected("EXIT_UNVERIFIED")
                break
            if state["Status"] != "running":
                raise Rejected("STATE_UNVERIFIED")
            pause(0.01)
        report = {"status": "passed", "observation": "closed_pipe" if closed_pipe else "worker_refused",
                  "cleaned": False}
    except Rejected as error:
        report = {"status": "failed", "error": error.code, "cleaned": False}
    except Exception:
        report = {"status": "failed", "error": "RUNTIME_UNCERTAIN", "cleaned": False}
    finally:
        close_failed = False
        if channel is not None:
            try:
                channel.close()
            except Exception:
                close_failed = True
        if attempted:
            try:
                Supervisor(adapter, journal, adapter.config.image, clock, pause).cleanup(record)
                # Final empty-daemon check also rejects an unexpected concurrent container.
                adapter.preflight([])
                journal.finish(record, "terminal")
                report["cleaned"] = True
            except Exception:
                journal.finish(record, "uncertain")
                report = {"status": "failed", "error": "CLEANUP_UNVERIFIED", "cleaned": False}
        if close_failed:
            report = {"status": "failed", "error": "ATTACH_CLOSE_FAILED", "cleaned": report["cleaned"]}
    if differences:
        report["policy_diff"] = differences
    return report


def main():
    parser = argparse.ArgumentParser(description="Refusal-only lifecycle acceptance on a fresh dedicated daemon")
    parser.add_argument("--socket", required=True)
    parser.add_argument("--expected-daemon-id", required=True)
    parser.add_argument("--image", required=True, help="preloaded sha256 config ID or repository@sha256 digest")
    parser.add_argument("--journal", required=True, help="new file in a trusted private persistent directory")
    args = parser.parse_args()
    journal = None
    try:
        config = Config(args.socket, args.expected_daemon_id, args.image)
        # No overwriting or reusing prior evidence. Retain journal on failure for reconciliation.
        fd = os.open(args.journal, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        journal = Journal(args.journal)
        report = run(DockerAdapter(config), journal)
    except Exception:
        report = {"status": "failed", "error": "CONFIG_OR_JOURNAL_UNVERIFIED", "cleaned": False}
    finally:
        if journal is not None:
            journal.close()
    print(json.dumps(report, separators=(",", ":")))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
