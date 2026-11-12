"""Trusted local cancellation journal API. No runtime imports or owner authorization here."""

import time
import uuid


class CancellationJournal:
    def init_cancellation(self):
        self.db.execute("CREATE TABLE IF NOT EXISTS task_controls (request_id TEXT PRIMARY KEY, "
                        "nonce TEXT, profile TEXT, session TEXT, cancel_requested INTEGER DEFAULT 0, "
                        "cleanup_token TEXT, cleanup_until REAL DEFAULT 0, verified INTEGER DEFAULT 0, "
                        "terminal_reason TEXT, container_id TEXT, guard_path TEXT, guard_device INTEGER, "
                        "guard_inode INTEGER, cgroup_empty INTEGER DEFAULT 0)")
        self.db.commit()

    def _control(self, request_id, fingerprint=None, session=None):
        from .control import Record, Rejected
        row = self.db.execute("SELECT * FROM tasks WHERE request_id=?", (request_id,)).fetchone()
        if row is None:
            raise Rejected("CANCEL_NOT_FOUND")
        record = Record(*row)
        lease = self.db.execute("SELECT session FROM leases WHERE request_id=?", (request_id,)).fetchone()
        if (record.profile not in ("execution", "resourceprobe") or lease is None or not lease[0]
                or (fingerprint is not None and record.fingerprint != fingerprint)
                or (session is not None and lease[0] != session)):
            raise Rejected("CANCEL_CLAIM_MISMATCH")
        control = self.db.execute("SELECT * FROM task_controls WHERE request_id=?", (request_id,)).fetchone()
        if control is None:
            self.db.execute("INSERT INTO task_controls (request_id,nonce,profile,session) VALUES (?,?,?,?)",
                            (request_id, record.nonce, record.profile, lease[0]))
        elif control[1:4] != (record.nonce, record.profile, lease[0]):
            raise Rejected("CANCEL_CLAIM_MISMATCH")
        return record

    def request_cancel(self, request_id, fingerprint, session):
        """Caller first authorizes owner in RPC metadata. No claim => no verified cancellation."""
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            record = self._control(request_id, fingerprint, session)
            self.db.execute("UPDATE task_controls SET cancel_requested=1 WHERE request_id=? AND verified=0",
                            (record.request_id,))
        return self.cancel_receipt(request_id, fingerprint, session)

    def cancel_receipt(self, request_id, fingerprint, session):
        from .control import Rejected
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            record = self._control(request_id, fingerprint, session)
            row = self.db.execute("SELECT cancel_requested,verified,terminal_reason,container_id,"
                                  "guard_path,guard_device,guard_inode,cgroup_empty FROM task_controls "
                                  "WHERE request_id=?", (request_id,)).fetchone()
            no_start = self.db.execute("SELECT nonce,container_id,start_attempted,no_start_verified "
                                       "FROM lifecycle WHERE request_id=?", (request_id,)).fetchone()
            no_start_valid = no_start == (record.nonce, record.container_id, 0, 1)
            verified = (record.state == "terminal" and row[1] == 1 and row[7] == 1
                        and record.container_id is not None and row[3] == record.container_id
                        and (no_start_valid or row[4] is not None and row[5] is not None and row[6] is not None))
            if verified and not no_start_valid:
                guard = self.db.execute("SELECT path,device,inode FROM guards WHERE container_id=?",
                                        (record.container_id,)).fetchone()
                if guard != row[4:7]:
                    raise Rejected("CANCEL_CLAIM_MISMATCH")
        return {"requestId": request_id, "cancelRequested": bool(row[0]),
                "cleanupVerified": verified, "terminalReason": row[2] if verified else None,
                "state": "terminal" if verified else "cancelling" if row[0] else "unverified"}

    def wait_cancel(self, request_id, fingerprint, session, timeout=3.0):
        """Bounded receipt wait on this connection; never calls Docker or retries execution."""
        if not 0 <= timeout <= 3:
            raise ValueError("wait must be between zero and three seconds")
        end = time.monotonic() + timeout
        while True:
            receipt = self.cancel_receipt(request_id, fingerprint, session)
            if receipt["cleanupVerified"] or time.monotonic() >= end:
                return receipt
            time.sleep(min(0.05, max(0, end - time.monotonic())))

    def cancellation_requested(self, record):
        row = self.db.execute("SELECT nonce,profile,cancel_requested FROM task_controls WHERE request_id=?",
                              (record.request_id,)).fetchone()
        return row is not None and row[:2] == (record.nonce, record.profile) and row[2] == 1

    def cleanup_acquire(self, record):
        """Three-second CAS lease; all Docker I/O must finish inside the shorter two-second budget."""
        from .control import Rejected
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            current = self._control(record.request_id)
            if current.nonce != record.nonce or (record.container_id and current.container_id != record.container_id):
                raise Rejected("CANCEL_CLAIM_MISMATCH")
            row = self.db.execute("SELECT verified,cleanup_until FROM task_controls WHERE request_id=?",
                                  (record.request_id,)).fetchone()
            if row[0]:
                return "verified"
            if row[1] > time.monotonic():
                return None
            token = uuid.uuid4().hex
            self.db.execute("UPDATE task_controls SET cleanup_token=?,cleanup_until=? WHERE request_id=?",
                            (token, time.monotonic() + 3, record.request_id))
            return token

    def cleanup_fence(self, record, token):
        from .control import Rejected
        row = self.db.execute("SELECT nonce,cleanup_token,cleanup_until,verified FROM task_controls WHERE request_id=?",
                              (record.request_id,)).fetchone()
        if row is None or row[0] != record.nonce or row[1] != token or row[2] <= time.monotonic() or row[3]:
            raise Rejected("CLEANUP_LEASE_LOST")

    def cleanup_release(self, record, token):
        with self.db:
            self.db.execute("UPDATE task_controls SET cleanup_token=NULL,cleanup_until=0 "
                            "WHERE request_id=? AND nonce=? AND cleanup_token=? AND verified=0",
                            (record.request_id, record.nonce, token))

    def cleanup_commit(self, record, token, reason):
        from .control import Rejected
        if reason not in ("succeeded", "failed", "expired"):
            raise Rejected("CLEANUP_REASON_UNVERIFIED")
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            self.cleanup_fence(record, token)
            current = self._control(record.request_id)
            guard = self.db.execute("SELECT path,device,inode FROM guards WHERE container_id=?",
                                    (current.container_id,)).fetchone()
            no_start = self.db.execute("SELECT nonce,container_id,start_attempted,no_start_verified "
                                       "FROM lifecycle WHERE request_id=?", (record.request_id,)).fetchone()
            if current.container_id is None or (guard is None and no_start != (current.nonce, current.container_id, 0, 1)):
                raise Rejected("CGROUP_CLEANUP_UNVERIFIED")
            guard = guard or (None, None, None)
            cancelled = self.cancellation_requested(current)
            self.db.execute("UPDATE task_controls SET verified=1,terminal_reason=?,container_id=?,"
                            "guard_path=?,guard_device=?,guard_inode=?,cgroup_empty=1 WHERE request_id=?",
                            ("cancelled" if cancelled else reason, current.container_id, *guard, record.request_id))
            self.db.execute("UPDATE tasks SET state='terminal' WHERE request_id=? AND nonce=?",
                            (record.request_id, record.nonce))


def open_journal(path):
    """Independent connection for an already-authorized local client; never creates a new file."""
    import os
    from .control import Journal
    if not os.path.isabs(path) or os.path.realpath(path) != path or not os.path.isfile(path):
        raise ValueError("existing trusted journal required")
    return Journal(path)
