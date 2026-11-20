"""Independent lease reaper. Deployment must start this before execution acceptance."""
import argparse
import json
import os
import time
import uuid
import fcntl
import stat
import socket
from pathlib import Path

from .control import Journal, MAX_HARD_SECONDS, Rejected, Supervisor
from .docker_adapter import Config, DockerAdapter
from .host_guard import HostGuard


def starttime(pid):
    text = Path('/proc', str(pid), 'stat').read_text()
    return text[text.rfind(')') + 2:].split()[19]


def notify_ready():
    """Optional systemd readiness, only after reconciliation and fresh heartbeat."""
    address = os.environ.get('NOTIFY_SOCKET')
    if address is None:
        return
    if not address or address[0] not in ('/', '@') or len(address.encode()) > 107:
        raise Rejected('REAPER_UNVERIFIED')
    if address[0] == '@':
        address = '\0' + address[1:]
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as channel:
        channel.settimeout(0.5)
        channel.connect(address)
        channel.sendall(b'READY=1')


def require_live(journal, session):
    row = journal.db.execute('SELECT session,pid,start,heartbeat FROM reaper WHERE singleton=1').fetchone()
    if (row is None or row[0] != session or row[1] == os.getpid()
            or not 0 <= time.monotonic() - row[3] <= 1
            or starttime(row[1]) != row[2]):
        raise Rejected('REAPER_UNVERIFIED')


def tick(adapter, journal, session, pid, started):
    # Refresh only after all due records have been reconciled successfully.
    records = journal.pending()
    profiles = {record.profile for record in records}
    if profiles and getattr(adapter, 'resource_probes', False) is True:
        if len(profiles) != 1 or not profiles <= {'execution', 'resourceprobe'}:
            raise Rejected('PROFILE_UNVERIFIED')
        from dataclasses import replace
        adapter.config = replace(adapter.config, profile=next(iter(profiles)))
    adapter.set_deadline(time.monotonic() + 2)
    adapter.preflight(records)
    adapter.journal = journal
    for record in records:
        lease = journal.db.execute('SELECT session,deadline FROM leases WHERE request_id=?',
                                   (record.request_id,)).fetchone()
        if lease is not None and lease[1] - time.monotonic() > MAX_HARD_SECONDS:
            raise Rejected('LEASE_UNVERIFIED')
        cancelled = journal.cancellation_requested(record)
        # A live supervisor must finish the start/guard-save critical section. Without a saved
        # identity we may terminate at hard expiry, but never invent cleanup evidence.
        saved_guard = journal.db.execute("SELECT 1 FROM guards WHERE container_id=?",
                                        (record.container_id,)).fetchone()
        lifecycle = journal.db.execute("SELECT start_attempted FROM lifecycle WHERE request_id=?",
                                       (record.request_id,)).fetchone()
        if ((cancelled and (saved_guard or record.container_id and lifecycle and lifecycle[0] == 0))
                or lease is None or lease[0] not in ("", session)
                or time.monotonic() >= lease[1]):
            adapter.set_deadline(time.monotonic() + 2)
            try:
                Supervisor(adapter, journal, adapter.config.image, profile=record.profile).cleanup(record)
            except Rejected as error:
                if error.code != 'CLEANUP_BUSY':
                    journal.finish(record, 'uncertain')
                    raise
    with journal.db:
        journal.db.execute('INSERT OR REPLACE INTO reaper VALUES (1,?,?,?,?)',
                           (session, pid, started, time.monotonic()))


def main():
    parser = argparse.ArgumentParser()
    for name in ('socket', 'expected-daemon-id', 'image', 'journal'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--resource-probes', action='store_true', help='Trusted fixed resource acceptance only')
    parser.add_argument('--resume-existing', action='store_true', help='Trusted restart: reconcile the retained journal, never replay')
    args = parser.parse_args()
    lock_fd = open_owned_journal(args.journal, args.resume_existing)
    journal = Journal(args.journal)
    adapter = DockerAdapter(Config(args.socket, args.expected_daemon_id, args.image, 'execution'), guard=HostGuard())
    adapter.resource_probes = args.resource_probes
    session, pid = uuid.uuid4().hex, os.getpid()
    started = starttime(pid)
    try:
        tick(adapter, journal, session, pid, started)
        notify_ready()
        print(json.dumps({'status': 'ready', 'session': session}), flush=True)
        while True:
            time.sleep(0.2)
            tick(adapter, journal, session, pid, started)
    finally:
        journal.close()
        os.close(lock_fd)


def open_owned_journal(path, resume=False):
    """Reject replacement/symlinks and concurrent reapers; retain all prior task identities."""
    target = Path(path)
    if not target.is_absolute() or str(target.resolve()) != str(target):
        raise Rejected('JOURNAL_UNVERIFIED')
    parent = target.parent.stat()
    if parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
        raise Rejected('JOURNAL_UNVERIFIED')
    flags = os.O_RDWR | os.O_NOFOLLOW
    if not resume:
        flags |= os.O_CREAT | os.O_EXCL
    fd = os.open(path, flags, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600:
            raise Rejected('JOURNAL_UNVERIFIED')
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fd
    except BaseException:
        os.close(fd)
        raise


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # No refreshed heartbeat on failure; health expires, never log paths/exception text.
        print(json.dumps({'status': 'failed', 'error': 'REAPER_UNVERIFIED'}), flush=True)
        raise SystemExit(1)
