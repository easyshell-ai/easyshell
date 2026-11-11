import unittest
from unittest.mock import Mock, patch

from .control import Journal, RESOURCE_COMMAND, policy, verify, Rejected
from .docker_adapter import Config
from .resource_acceptance import run
from .test_control import IMAGE, FakeRuntime, sample


class ResourceTest(unittest.TestCase):
    def test_resource_command_is_fixed_and_distinct(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        record = journal.claim(sample(), 'resourceprobe')
        self.assertTrue(record.name.startswith('easyshell-resource-v1-'))
        self.assertEqual('easyshell.code.resourceprobe.v1', record.labels()['easyshell.protocol'])
        self.assertEqual('com.easyshell.codeworker.MemoryLimitProbe', RESOURCE_COMMAND[-1])
        self.assertIn('-XX:MaxDirectMemorySize=1g', RESOURCE_COMMAND)
        runtime = FakeRuntime()
        runtime.create(record.name, policy(IMAGE, record))
        verify(runtime.raw, record, IMAGE)
        runtime.raw['Config']['Cmd'] = ['arbitrary']
        with self.assertRaises(Rejected):
            verify(runtime.raw, record, IMAGE)

    def test_only_kernel_oom_result_and_verified_cleanup_allow_recovery(self):
        for result, verified, expected in (
                ({'status': 'failed', 'error': 'RESOURCE_EXHAUSTED'}, True, 'passed'),
                ({'status': 'failed', 'error': 'EXECUTION_FAILED'}, True, 'failed'),
                ({'status': 'failed', 'error': 'TIMEOUT'}, True, 'failed'),
                ({'status': 'failed', 'error': 'RESOURCE_EXHAUSTED'}, False, 'failed')):
            with self.subTest(result=result, verified=verified):
                journal = Mock()
                journal.cancel_receipt.return_value = {'cleanupVerified': verified}
                with patch('supervisor.resource_acceptance.DockerAdapter') as adapter, patch(
                        'supervisor.resource_acceptance.Supervisor') as supervisor:
                    adapter.return_value.clock.return_value = 0
                    supervisor.return_value.execute.side_effect = [result,
                        {'status': 'succeeded', 'output': {'n': 4}}]
                    report = run(Config('/run/easyshell-code/test/docker.sock', 'dedicated-id', IMAGE,
                                        'resourceprobe'), journal, 'session')
                    self.assertEqual(expected, report['status'])
                    self.assertEqual(2 if expected == 'passed' else 1,
                                     supervisor.return_value.execute.call_count)
