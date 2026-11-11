import io
import unittest
from dataclasses import replace
from unittest.mock import Mock, patch

from .attach import Attach
from .control import Journal, Rejected
from .crash_acceptance import EVENTS, REQUEST_ID, evidence, wait_events
from .test_attach import Wire
from .test_control import CID, Clock, sample


class CrashTest(unittest.TestCase):
    def test_delivery_observer_after_half_close_before_read(self):
        wire = Wire()
        channel = Attach(wire, 1, Clock())

        def gate():
            self.assertTrue(wire.half_closed)
            self.assertTrue(channel.delivered)
            raise Rejected('synthetic-gate')

        channel.on_delivered = gate
        with self.assertRaisesRegex(Rejected, 'synthetic-gate'):
            channel.exchange(b'{}')

    def test_event_pipe_accepts_only_ordered_fixed_enums(self):
        process = Mock(stdout=Mock())
        selector = Mock()
        selector.__enter__ = Mock(return_value=selector)
        selector.__exit__ = Mock(return_value=False)
        selector.select.return_value = [True]
        for chunks, valid in ((list(EVENTS), True), ([EVENTS[1]], False),
                              ([b'user-source\n'], False), ([b''], False)):
            with self.subTest(chunks=chunks), patch('supervisor.crash_acceptance.selectors.DefaultSelector',
                                                   return_value=selector), patch(
                    'supervisor.crash_acceptance.os.read', side_effect=chunks):
                if valid:
                    wait_events(process, 1, Clock())
                else:
                    with self.assertRaises(Rejected):
                        wait_events(process, 1, Clock())

    def test_only_terminal_after_lease_with_saved_cgroup_and_empty_daemon_passes(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        record = journal.claim(sample() | {'requestId': REQUEST_ID}, 'execution')
        journal.bind(record, CID)
        journal.db.execute('UPDATE leases SET session=?,deadline=?', ('session', 20))
        journal.db.commit()
        adapter, guard = Mock(), Mock()
        with patch('supervisor.crash_acceptance.require_live'):
            self.assertFalse(evidence(adapter, journal, 'session', guard, 21))
            guard.confirm_empty.assert_not_called()
            journal.finish(record, 'terminal')
            self.assertFalse(evidence(adapter, journal, 'session', guard, 19))
            self.assertTrue(evidence(adapter, journal, 'session', guard, 21))
            guard.confirm_empty.assert_called_once_with(CID, journal)
            adapter.preflight.assert_called_once_with([])
            guard.confirm_empty.side_effect = Rejected('CGROUP_CLEANUP_UNVERIFIED')
            with self.assertRaises(Rejected):
                evidence(adapter, journal, 'session', guard, 21)

    def test_parent_has_no_container_cleanup_path(self):
        import inspect
        from .crash_acceptance import run
        source = inspect.getsource(run)
        self.assertNotIn('.cleanup(', source)
        self.assertNotIn('.reconcile(', source)
        self.assertNotIn('os.kill(', source)
        self.assertIn('process.kill()', source)

    def test_parent_kills_only_spawned_child_and_waits_for_reaper_evidence(self):
        from .crash_acceptance import run
        from .docker_adapter import Config
        from .test_control import IMAGE
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        process = Mock()
        process.poll.side_effect = [None, -9]
        process.wait.return_value = -9
        with patch('supervisor.crash_acceptance.Journal', return_value=journal), patch(
                'supervisor.crash_acceptance.require_live'), patch(
                'supervisor.crash_acceptance.DockerAdapter') as adapter, patch(
                'supervisor.crash_acceptance.subprocess.Popen', return_value=process) as spawn, patch(
                'supervisor.crash_acceptance.wait_events') as stages, patch(
                'supervisor.crash_acceptance.evidence', return_value=True) as proof:
            result = run(Config('/run/easyshell-code/test/docker.sock', 'dedicated-id', IMAGE, 'execution'),
                         '/trusted/new.sqlite', 'session')
            self.assertEqual('passed', result['status'])
            self.assertTrue(result['cleaned'])
            process.kill.assert_called_once_with()
            self.assertEqual(1, spawn.call_count)
            stages.assert_called_once()
            proof.assert_called_once()
            adapter.return_value.cleanup.assert_not_called()
