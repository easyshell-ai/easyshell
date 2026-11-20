import json
import os
import tempfile
import time
import unittest
import threading
from pathlib import Path
from unittest.mock import patch

from .control import Journal, Rejected, Supervisor
from .execution_acceptance import safe_result
from .execution_acceptance import run as acceptance_run
from .reaper import require_live, tick
from .test_control import CID, IMAGE, Clock, FakeRuntime, FakeChannel, sample
from .test_profiles import HostGuardTest
from .docker_adapter import Config, DockerAdapter


class ReviewTest(unittest.TestCase):
    def test_all_static_supervisor_errors_have_safe_categories(self):
        import ast
        directory = Path(__file__).parent
        codes = set()
        for path in directory.glob('*.py'):
            if path.name.startswith('test_'):
                continue
            for node in ast.walk(ast.parse(path.read_text())):
                if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                        and node.func.id in ('Rejected', 'failed') and node.args):
                    for part in ast.walk(node.args[0]):
                        if (isinstance(part, ast.Constant) and isinstance(part.value, str)
                                and part.value.isupper()):
                            codes.add(part.value)
        state = type('State', (), {'last_stage': 'claimed'})()
        for code in codes:
            self.assertNotEqual('unknown', safe_result({'status': 'failed', 'error': code}, state)['result_error'], code)
        self.assertEqual('none', safe_result({'status': 'succeeded'}, state)['result_error'])

    def test_pre_delivery_timeout_cannot_pass_loop_acceptance(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        runtime = type('Runtime', (), {'config': type('Config', (), {'profile': 'execution', 'image': IMAGE})(),
                                       'preflight': lambda self, records: None})()
        with patch('supervisor.execution_acceptance.Supervisor') as model:
            model.return_value.execute.side_effect = [
                {'status': 'succeeded', 'output': {'n': 4}},
                {'status': 'succeeded', 'output': {'n': 4}},
                {'status': 'failed', 'error': 'TIMEOUT'}]
            model.return_value.last_delivery = False
            model.return_value.last_stage = 'started'
            result = acceptance_run(runtime, journal)
            self.assertEqual('CASE_UNVERIFIED', result['error'])
            self.assertEqual(2, result['case'])
            self.assertEqual('started', result['stage'])

    def test_http_slow_read_interrupted_by_absolute_deadline(self):
        adapter = DockerAdapter(Config('/run/easyshell-code/test/docker.sock', 'dedicated-id', IMAGE))
        adapter.set_deadline(time.monotonic() + 0.05)
        stopped = threading.Event()

        class Connection:
            sock = None

            def __init__(self, path):
                self.sock = self

            def request(self, *args):
                pass

            def getresponse(self):
                return self

            def read(self, limit):
                if not stopped.wait(1):
                    raise AssertionError('deadline did not interrupt blocked read')
                return b'{}'

            def shutdown(self, how):
                stopped.set()

            def close(self):
                pass

        with patch.object(adapter, '_socket_check'), patch('supervisor.docker_adapter.UnixConnection', Connection):
            with self.assertRaisesRegex(Rejected, 'TIMEOUT'):
                adapter._http('GET', '/info')

    def test_empty_stdout_oom_is_not_hidden_by_parse_error(self):
        journal, clock = Journal(':memory:'), Clock()
        self.addCleanup(journal.close)

        class OOM(FakeRuntime):
            def attach(self, target, deadline):
                class Empty(FakeChannel):
                    def exchange(self, payload):
                        return b''
                return Empty(self)

        runtime = OOM('oom')
        result = Supervisor(runtime, journal, IMAGE, clock, clock.pause).execute(sample())
        self.assertEqual('RESOURCE_EXHAUSTED', result['error'])
        self.assertIsNone(runtime.raw)

    def test_safe_diagnostic_does_not_echo_output_or_error(self):
        supervisor = type('State', (), {'last_stage': 'limits_verified'})()
        result = safe_result({'status': 'failed', 'error': 'synthetic-secret',
                              'output': 'synthetic-secret'}, supervisor)
        self.assertEqual('unknown', result['result_error'])
        self.assertEqual('limits_verified', result['stage'])
        self.assertNotIn('synthetic-secret', json.dumps(result))

    def test_reaper_live_requires_other_stable_process_and_fresh_session(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        journal.db.execute('INSERT INTO reaper VALUES (1,?,?,?,?)',
                           ('session', os.getpid() + 1, '123', time.monotonic()))
        journal.db.commit()
        with patch('supervisor.reaper.starttime', return_value='123'):
            require_live(journal, 'session')
            with self.assertRaises(Rejected):
                require_live(journal, 'wrong')
            journal.db.execute('UPDATE reaper SET heartbeat=0')
            with self.assertRaises(Rejected):
                require_live(journal, 'session')

    def test_reaper_due_task_cleanup_and_heartbeat(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        record = journal.claim(sample(), 'execution')
        from .control import policy
        runtime = FakeRuntime()
        runtime.create(record.name, policy(IMAGE, record))
        runtime.config = type('Config', (), {'image': IMAGE})()
        runtime.set_deadline = lambda deadline: None
        runtime.confirm_empty = lambda record: None
        journal.db.execute('UPDATE leases SET deadline=0')
        journal.db.execute('UPDATE leases SET session=?', ('session',))
        journal.bind(record, CID)
        journal.db.execute('INSERT INTO guards VALUES (?,?,?,?)', (CID, '/synthetic/cgroup', 1, 2))
        journal.db.commit()
        tick(runtime, journal, 'session', 123, '456')
        self.assertIsNone(runtime.raw)
        self.assertEqual([], journal.pending())
        self.assertEqual('session', journal.db.execute('SELECT session FROM reaper').fetchone()[0])


class CgroupCleanupTest(HostGuardTest):
    def test_saved_identity_populated_missing_and_unknown(self):
        journal = Journal(':memory:')
        self.addCleanup(journal.close)
        with self.assertRaises(Rejected):
            self.guard.confirm_empty(CID, journal)
        self.guard.save(self.raw, journal)
        (self.leaf / 'cgroup.events').write_text('populated 1\nfrozen 0')
        with self.assertRaises(Rejected):
            self.guard.confirm_empty(CID, journal)
        (self.leaf / 'cgroup.events').write_text('populated 0\nfrozen 0')
        (self.leaf / 'cgroup.procs').write_text('')
        self.guard.confirm_empty(CID, journal)
        with patch.object(self.guard, 'read', side_effect=PermissionError()):
            with self.assertRaises(PermissionError):
                self.guard.confirm_empty(CID, journal)
        for path in self.leaf.iterdir():
            path.unlink()
        self.leaf.rmdir()
        self.guard.confirm_empty(CID, journal)
