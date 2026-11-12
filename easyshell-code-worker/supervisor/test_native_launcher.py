"""Execution policy must not bypass the audited empty-env launcher."""
import unittest

from .control import EXECUTION_COMMAND, Journal, policy, verify, Rejected
from .test_control import FakeRuntime, IMAGE, sample


class NativeLauncherPolicyTest(unittest.TestCase):
    def test_execution_uses_only_fixed_launcher(self):
        journal = Journal(':memory:')
        try:
            record = journal.claim(sample(), 'execution')
            spec = policy(IMAGE, record)
            self.assertEqual(['/opt/code-worker/launcher'], EXECUTION_COMMAND)
            self.assertEqual(EXECUTION_COMMAND, spec['Cmd'])
            self.assertEqual(['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'], spec['Env'])
            runtime = FakeRuntime()
            runtime.create(record.name, spec)
            verify(runtime.raw, record, IMAGE)
            for injection in ('JAVA_TOOL_OPTIONS=-javaagent:/untrusted.jar',
                              'LD_PRELOAD=/untrusted.so', 'HOME=/untrusted',
                              'GRAAL_PYTHONHOME=/untrusted'):
                runtime.raw['Config']['Env'] = [spec['Env'][0], injection]
                with self.subTest(variable=injection.split('=')[0]), self.assertRaises(Rejected):
                    verify(runtime.raw, record, IMAGE)
            runtime.raw['Config']['Env'] = ['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin']
            runtime.raw['Config']['Cmd'] = ['/opt/java/openjdk/bin/java']
            with self.assertRaises(Rejected):
                verify(runtime.raw, record, IMAGE)
        finally:
            journal.close()
