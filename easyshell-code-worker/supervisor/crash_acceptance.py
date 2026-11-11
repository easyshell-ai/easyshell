"""Fixed SIGKILL-supervisor test. Only the independent reaper cleans containers."""

import argparse
import json
import os
import selectors
import signal
import subprocess
import sys
import time

from .control import Journal, Rejected, Supervisor
from .docker_adapter import Config, DockerAdapter
from .host_guard import HostGuard
from .reaper import require_live


REQUEST_ID = "crash-acceptance-v2-fixed"
EVENTS = (b"LIMITS_VERIFIED\n", b"PAYLOAD_DELIVERED\n")


def child(config, journal_path, session):
    journal = Journal(journal_path)

    class ObservedAdapter(DockerAdapter):
        def verify_limits(self, target, deadline):
            super().verify_limits(target, deadline)
            os.write(1, EVENTS[0])

        def attach(self, target, deadline):
            channel = super().attach(target, deadline)

            def delivered():
                os.write(1, EVENTS[1])
                # Parent never releases this gate: kill occurs before child finally/cleanup.
                os.read(0, 1)
                raise Rejected("CRASH_GATE_RELEASED")

            channel.on_delivered = delivered
            return channel

    adapter = ObservedAdapter(config, guard=HostGuard())
    adapter.reaper_session = session
    try:
        Supervisor(adapter, journal, config.image, profile="execution").execute({
            "requestId": REQUEST_ID, "language": "javascript",
            "source": "function main(i) { while(true) {} }", "input": {}, "timeoutMs": 5000})
        return 1
    finally:
        journal.close()


def wait_events(process, deadline, clock=time.monotonic):
    pending = bytearray()
    index = 0
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        while index < len(EVENTS):
            remaining = deadline - clock()
            if remaining <= 0 or not selector.select(remaining):
                raise Rejected("CRASH_STAGE_TIMEOUT")
            chunk = os.read(process.stdout.fileno(), 64)
            if not chunk:
                raise Rejected("CRASH_STAGE_UNVERIFIED")
            pending.extend(chunk)
            if len(pending) > 64:
                raise Rejected("CRASH_STAGE_UNVERIFIED")
            while b"\n" in pending:
                line, _, rest = pending.partition(b"\n")
                pending = bytearray(rest)
                if index >= len(EVENTS) or line + b"\n" != EVENTS[index]:
                    raise Rejected("CRASH_STAGE_UNVERIFIED")
                index += 1
        if pending:
            raise Rejected("CRASH_STAGE_UNVERIFIED")


def evidence(adapter, journal, session, guard, now):
    require_live(journal, session)
    row = journal.db.execute("SELECT * FROM tasks WHERE request_id=?", (REQUEST_ID,)).fetchone()
    if row is None:
        return False
    from .control import Record
    record = Record(*row)
    lease = journal.db.execute("SELECT session,deadline FROM leases WHERE request_id=?", (REQUEST_ID,)).fetchone()
    if (record.profile != "execution" or not record.container_id or lease is None or lease[0] != session):
        raise Rejected("CRASH_EVIDENCE_UNVERIFIED")
    if record.state != "terminal" or now < lease[1]:
        return False
    guard.confirm_empty(record.container_id, journal)
    adapter.set_deadline(time.monotonic() + 1)
    adapter.preflight([])
    return True


def run(config, journal_path, session):
    end = time.monotonic() + 30
    journal = Journal(journal_path)
    process = None
    try:
        require_live(journal, session)
        if journal.db.execute("SELECT count(*) FROM tasks").fetchone()[0] != 0:
            raise Rejected("CRASH_FRESH_JOURNAL_REQUIRED")
        adapter = DockerAdapter(config, guard=HostGuard())
        adapter.set_deadline(min(end, time.monotonic() + 2))
        adapter.preflight([])
        command = [sys.executable, "-m", "supervisor.crash_acceptance", "--child",
                   "--socket", config.socket_path, "--expected-daemon-id", config.daemon_id,
                   "--image", config.image, "--journal", journal_path, "--reaper-session", session]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, env={"PYTHONDONTWRITEBYTECODE": "1"},
                                   cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        wait_events(process, end)
        if process.poll() is not None:
            raise Rejected("CRASH_CHILD_ALREADY_EXITED")
        process.kill()  # Exact Popen-owned child, no PID from request/journal/daemon.
        if process.wait(timeout=max(0.001, end - time.monotonic())) != -signal.SIGKILL:
            raise Rejected("CRASH_KILL_UNVERIFIED")
        while time.monotonic() < end:
            if evidence(adapter, journal, session, adapter.guard, time.monotonic()):
                return {"status": "passed", "observation": "reaper_after_sigkill", "cleaned": True}
            time.sleep(min(0.1, max(0, end - time.monotonic())))
        raise Rejected("CRASH_REAPER_TIMEOUT")
    except Exception:
        return {"status": "failed", "error": "CRASH_ACCEPTANCE_UNVERIFIED", "cleaned": False}
    finally:
        if process is not None:
            if process.poll() is None:
                process.kill()
            # No container cleanup here. Reaper retains sole responsibility even on stage failure.
            try:
                process.wait(timeout=max(0.001, end - time.monotonic()))
            except subprocess.TimeoutExpired:
                pass
            process.stdin.close()
            process.stdout.close()
        journal.close()


def main():
    parser = argparse.ArgumentParser(description="Fixed supervisor SIGKILL acceptance; independent reaper required")
    for name in ("socket", "expected-daemon-id", "image", "journal", "reaper-session"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--child", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    config = Config(args.socket, args.expected_daemon_id, args.image, "execution")
    if not os.path.isfile(args.journal) or os.path.islink(args.journal):
        raise Rejected("CRASH_JOURNAL_UNVERIFIED")
    if args.child:
        return child(config, args.journal, args.reaper_session)
    report = run(config, args.journal, args.reaper_session)
    print(json.dumps(report, separators=(",", ":")))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
