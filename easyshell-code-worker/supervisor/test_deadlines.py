import unittest
from unittest.mock import patch

from .control import Journal, Rejected, Supervisor
from .execution_acceptance import run
from .test_control import FakeRuntime, FakeChannel, Clock, IMAGE, sample
from .docker_adapter import Config, DockerAdapter


class DeadlineTest(unittest.TestCase):
    def test_reaper_lease_matches_exact_hard_deadline_and_rejects_over_bound(self):
        journal, clock = Journal(':memory:'), Clock()
        self.addCleanup(journal.close)
        record = journal.claim(sample(), 'execution')
        adapter = DockerAdapter(Config('/run/easyshell-code/test/docker.sock', 'dedicated-id', IMAGE,
                                       'execution'), clock)
        adapter.reaper_session = 'fixed-session'
        with patch('supervisor.reaper.require_live'):
            adapter.authorize(journal, record, 20)
            self.assertEqual(20, journal.db.execute('SELECT deadline FROM leases').fetchone()[0])
            with self.assertRaisesRegex(Rejected, 'LEASE_UNVERIFIED'):
                adapter.authorize(journal, record, 20.01)

    def test_fixed_startup_allowance_and_worker_timeout_origin(self):
        journal, clock = Journal(':memory:'), Clock()
        self.addCleanup(journal.close)

        class Runtime(FakeRuntime):
            def attach(self, target, deadline):
                self.deadline = deadline
                class Channel(FakeChannel):
                    delivered = False
                    def exchange(self, payload):
                        clock.pause(10)  # Trusted initialization/transport uses startup allowance.
                        self.delivered = True
                        return b'{"status":"failed","error":"TIMEOUT"}'
                return Channel(self)

        runtime = Runtime()
        supervisor = Supervisor(runtime, journal, IMAGE, clock, clock.pause)
        result = supervisor.execute(sample() | {'timeoutMs': 5000})
        self.assertEqual(20, runtime.deadline)
        self.assertEqual('TIMEOUT', result['error'])
        self.assertEqual('worker', supervisor.last_timeout_origin)
        self.assertTrue(supervisor.last_delivery)
        self.assertIsNone(runtime.raw)

    def test_external_timeout_is_distinct_even_after_delivery(self):
        journal, clock = Journal(':memory:'), Clock()
        self.addCleanup(journal.close)

        class Runtime(FakeRuntime):
            def attach(self, target, deadline):
                class Channel(FakeChannel):
                    delivered = True
                    def exchange(self, payload):
                        clock.now = deadline
                        raise Rejected('TIMEOUT')
                return Channel(self)

        supervisor = Supervisor(Runtime(), journal, IMAGE, clock, clock.pause)
        self.assertEqual('TIMEOUT', supervisor.execute(sample())['error'])
        self.assertEqual('external', supervisor.last_timeout_origin)
        self.assertTrue(supervisor.last_delivery)

    def test_loop_external_timeout_not_accepted(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        runtime = type('Runtime', (), {'config': type('Config', (), {'profile': 'execution', 'image': IMAGE})(),
                                       'preflight': lambda self, records: None})()
        with patch('supervisor.execution_acceptance.Supervisor') as model:
            model.return_value.execute.side_effect = [
                {'status': 'succeeded', 'output': {'n': 4}},
                {'status': 'succeeded', 'output': {'n': 4}},
                {'status': 'failed', 'error': 'TIMEOUT'}]
            model.return_value.last_delivery = True
            model.return_value.last_timeout_origin = 'external'
            model.return_value.last_stage = 'source_delivered'
            result = run(runtime, journal)
            self.assertEqual('CASE_UNVERIFIED', result['error'])
            self.assertEqual('external', result['timeout_origin'])
