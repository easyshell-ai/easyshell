import tempfile
import time
import unittest
from pathlib import Path

from .cancel import open_journal
from .control import Journal, Rejected, Supervisor, policy
from .test_control import CID, IMAGE, FakeRuntime, FakeChannel, Clock, sample


class CancelTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = str(Path(temp.name) / 'core.sqlite')
        self.journal = Journal(self.path)
        self.client = open_journal(self.path)
        self.addCleanup(self.client.close)
        self.addCleanup(self.journal.close)
        self.record = self.journal.claim(sample(), 'execution')
        self.journal.db.execute('UPDATE leases SET session=?,deadline=?', ('session', time.monotonic() + 20))
        self.journal.db.commit()

    def cancel(self):
        return self.client.request_cancel(self.record.request_id, self.record.fingerprint, 'session')

    def bind_guard(self):
        self.journal.bind(self.record, CID)
        self.journal.db.execute('INSERT INTO guards VALUES (?,?,?,?)', (CID, '/synthetic/guard', 1, 2))
        self.journal.db.commit()

    def test_cancel_intent_is_not_cleanup_proof_and_is_durable(self):
        receipt = self.cancel()
        self.assertTrue(receipt['cancelRequested'])
        self.assertFalse(receipt['cleanupVerified'])
        self.assertTrue(self.journal.cancellation_requested(self.record))
        self.assertFalse(self.client.wait_cancel(self.record.request_id, self.record.fingerprint, 'session', 0)['cleanupVerified'])
        self.journal.finish(self.record, 'terminal')
        self.assertFalse(self.cancel()['cleanupVerified'])

    def test_unknown_wrong_fingerprint_session_and_nonce_rejected(self):
        for request_id, digest, session in [('unknown', self.record.fingerprint, 'session'),
                                            (self.record.request_id, 'bad', 'session'),
                                            (self.record.request_id, self.record.fingerprint, 'other')]:
            with self.assertRaises(Rejected):
                self.client.request_cancel(request_id, digest, session)
        self.cancel()
        self.journal.db.execute('UPDATE task_controls SET nonce=?', ('foreign',))
        self.journal.db.commit()
        with self.assertRaises(Rejected):
            self.cancel()

    def test_cleanup_cas_and_cancel_receipt_preserves_exact_guard(self):
        self.bind_guard()
        self.cancel()
        token = self.journal.cleanup_acquire(self.record)
        self.assertIsNone(self.client.cleanup_acquire(self.record))
        self.journal.cleanup_commit(self.record, token, 'succeeded')
        receipt = self.cancel()
        self.assertTrue(receipt['cleanupVerified'])
        self.assertEqual('cancelled', receipt['terminalReason'])
        self.journal.finish(self.record, 'uncertain')
        self.assertTrue(self.cancel()['cleanupVerified'])
        self.journal.db.execute('UPDATE guards SET inode=3')
        self.journal.db.commit()
        with self.assertRaises(Rejected):
            self.cancel()

    def test_success_commit_before_cancel_keeps_original_terminal_reason(self):
        self.bind_guard()
        token = self.journal.cleanup_acquire(self.record)
        self.journal.cleanup_commit(self.record, token, 'succeeded')
        receipt = self.cancel()
        self.assertTrue(receipt['cleanupVerified'])
        self.assertFalse(receipt['cancelRequested'])
        self.assertEqual('succeeded', receipt['terminalReason'])

    def test_expired_cleanup_owner_cannot_commit_after_takeover(self):
        self.bind_guard()
        token = self.journal.cleanup_acquire(self.record)
        self.journal.db.execute('UPDATE task_controls SET cleanup_until=0')
        self.journal.db.commit()
        second = self.client.cleanup_acquire(self.record)
        self.assertNotEqual(token, second)
        with self.assertRaises(Rejected):
            self.journal.cleanup_commit(self.record, token, 'failed')

    def test_no_guard_never_confirms_cleanup(self):
        self.journal.bind(self.record, CID)
        token = self.journal.cleanup_acquire(self.record)
        with self.assertRaises(Rejected):
            self.journal.cleanup_commit(self.record, token, 'failed')
        self.assertFalse(self.cancel()['cleanupVerified'])

    def test_blocked_exchange_reaper_cancellation_not_overridden_by_404(self):
        # Separate connections and adapters; no runtime object crosses the cancel client boundary.
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        clock = Clock()
        runtime = FakeRuntime()
        runtime.authorize = lambda j, r, d: None
        runtime.verify_limits = lambda target, deadline: None
        runtime.confirm_empty = lambda record: None
        original_create = runtime.create

        def create(name, spec):
            cid = original_create(name, spec)
            journal.db.execute('UPDATE leases SET session=?', ('session',))
            journal.db.execute('INSERT INTO guards VALUES (?,?,?,?)', (CID, '/synthetic/guard', 1, 2))
            journal.db.commit()
            return cid

        runtime.create = create

        class Channel(FakeChannel):
            def exchange(self, payload):
                record = journal.pending()[0]
                journal.request_cancel(record.request_id, record.fingerprint, 'session')
                reaper = Supervisor(runtime, journal, IMAGE, clock, clock.pause, profile='execution')
                reaper.cleanup(record)
                raise Rejected('ATTACH_CONNECTION_FAILED')

        runtime.attach = lambda target, deadline: Channel(runtime)
        result = Supervisor(runtime, journal, IMAGE, clock, clock.pause, profile='execution').execute(sample())
        self.assertEqual({'status': 'failed', 'error': 'CANCELLED'}, result)
        self.assertIsNone(runtime.raw)

    def test_reaper_cancels_before_execution_lease_deadline(self):
        from .reaper import tick
        self.bind_guard()
        self.cancel()
        runtime = FakeRuntime('timeout')
        runtime.create(self.record.name, policy(IMAGE, self.record))
        runtime.config = type('Config', (), {'image': IMAGE})()
        runtime.set_deadline = lambda deadline: None
        runtime.confirm_empty = lambda record: None
        tick(runtime, self.journal, 'session', 123, '456')
        self.assertIsNone(runtime.raw)
        self.assertEqual('cancelled', self.cancel()['terminalReason'])
