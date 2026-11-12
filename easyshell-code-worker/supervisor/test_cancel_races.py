import time
import unittest

from .control import Journal, Rejected, Supervisor, policy
from .docker_adapter import Config, DockerAdapter
from .test_control import CID, IMAGE, FakeRuntime, sample


class RaceTest(unittest.TestCase):
    def setUp(self):
        self.journal = Journal(':memory:')
        self.addCleanup(self.journal.close)
        self.record = self.journal.claim(sample(), 'execution')
        self.journal.bind(self.record, CID)
        from dataclasses import replace
        self.record = replace(self.record, container_id=CID)
        self.journal.db.execute('UPDATE leases SET session=?', ('session',))
        self.journal.db.commit()
        self.runtime = FakeRuntime()
        self.runtime.create(self.record.name, policy(IMAGE, self.record))
        self.adapter = DockerAdapter(Config('/run/easyshell-code/test/docker.sock', 'dedicated-id', IMAGE, 'execution'))
        self.adapter.journal = self.journal

    def acquire(self):
        token = self.journal.cleanup_acquire(self.record)
        self.adapter.set_cleanup_claim(self.record, token)
        return token

    def test_created_owned_receipt_without_cgroup_is_verified(self):
        self.journal.request_cancel(self.record.request_id, self.record.fingerprint, 'session')
        token = self.acquire()
        self.adapter.prepare_cleanup_evidence(self.record, self.runtime.raw)
        self.adapter.confirm_empty(self.record)
        self.journal.cleanup_commit(self.record, token, 'failed')
        receipt = self.journal.cancel_receipt(self.record.request_id, self.record.fingerprint, 'session')
        self.assertTrue(receipt['cleanupVerified'])
        self.assertEqual('cancelled', receipt['terminalReason'])

    def test_start_attempt_even_created_cannot_use_no_start_receipt(self):
        self.journal.db.execute('INSERT INTO lifecycle VALUES (?,?,?,1,0)',
                                (self.record.request_id, self.record.nonce, CID))
        self.journal.db.commit()
        self.acquire()
        with self.assertRaises(Rejected):
            self.adapter.prepare_cleanup_evidence(self.record, self.runtime.raw)

    def test_started_missing_guard_uses_trusted_host_recovery(self):
        from unittest.mock import Mock
        self.runtime.raw['State'].update(Running=True, Pid=99, Status='running')
        self.acquire()
        self.adapter.guard = Mock()
        self.adapter.prepare_cleanup_evidence(self.record, self.runtime.raw)
        self.assertEqual(2, self.adapter.guard.verify.call_count)
        self.adapter.guard.save.assert_called_once_with(self.runtime.raw, self.journal)

    def test_started_exited_missing_guard_remains_unverified(self):
        self.runtime.raw['State'].update(Running=False, Pid=0, Status='exited')
        self.acquire()
        with self.assertRaisesRegex(Rejected, 'CGROUP_CLEANUP_UNVERIFIED'):
            self.adapter.prepare_cleanup_evidence(self.record, self.runtime.raw)

    def test_reaper_defers_bootstrap_cancel_without_guard_until_deadline(self):
        from .reaper import tick
        from unittest.mock import Mock
        self.journal.db.execute('INSERT INTO lifecycle VALUES (?,?,?,1,0)',
                                (self.record.request_id, self.record.nonce, CID))
        self.journal.db.execute('UPDATE leases SET deadline=?', (time.monotonic() + 10,))
        self.journal.db.commit()
        self.journal.request_cancel(self.record.request_id, self.record.fingerprint, 'session')
        adapter = Mock()
        adapter.config.image = IMAGE
        tick(adapter, self.journal, 'session', 123, '456')
        adapter.kill.assert_not_called()
        adapter.remove.assert_not_called()
        self.assertEqual('pending', self.journal.pending()[0].state)

    def test_cleanup_claim_prevents_start_before_docker_mutation(self):
        from unittest.mock import patch
        self.journal.db.execute('INSERT INTO lifecycle VALUES (?,?,?,0,0)',
                                (self.record.request_id, self.record.nonce, CID))
        self.journal.db.commit()
        self.adapter.records = {self.record.name: self.record}
        self.acquire()
        with patch.object(self.adapter, '_created'), patch.object(self.adapter, '_http') as mutation:
            with self.assertRaisesRegex(Rejected, 'CANCELLED'):
                self.adapter.start(CID, time.monotonic() + 1)
            mutation.assert_not_called()

    def test_missing_container_without_either_evidence_is_never_verified(self):
        from unittest.mock import patch
        from .control import NotFound
        self.adapter.records = {self.record.name: self.record}
        with patch.object(self.adapter, 'inspect', side_effect=NotFound()):
            with self.assertRaisesRegex(Rejected, 'CGROUP_CLEANUP_UNVERIFIED'):
                Supervisor(self.adapter, self.journal, IMAGE, profile='execution').cleanup(self.record)
        self.assertFalse(self.journal.request_cancel(self.record.request_id, self.record.fingerprint,
                                                     'session')['cleanupVerified'])
