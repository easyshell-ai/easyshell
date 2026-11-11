import os
from pathlib import Path
import sqlite3
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class ServiceEntryTest(unittest.TestCase):
    def launch(self, arguments):
        entry = Path(__file__).resolve().parents[1] / 'deploy/service-entry.py'
        saved_path = list(sys.path)
        try:
            with patch.object(sys, 'argv', ['service-entry.py', *arguments]), \
                    patch.object(sys, 'flags', SimpleNamespace(isolated=1)), \
                    patch('runpy.run_module') as run:
                exec(compile(entry.read_text(), str(entry), 'exec'), {})
                return list(sys.argv), run.call_args
        finally:
            sys.path[:] = saved_path

    def test_reaper_retains_existing_journal_but_new_path_stays_new(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'journal.sqlite'
            args, _ = self.launch(['reaper', '--journal', str(path)])
            self.assertNotIn('--resume-existing', args)
            path.touch(mode=0o600)
            args, called = self.launch(['reaper', '--journal', str(path)])
            self.assertIn('--resume-existing', args)
            self.assertEqual('supervisor.reaper', called.args[0])

    def test_orchestrator_reads_only_live_session_and_rejects_static_override(self):
        from .reaper import starttime
        import time
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'journal.sqlite'
            path.touch(mode=0o600)
            db = sqlite3.connect(path)
            db.execute('CREATE TABLE reaper(singleton INTEGER,session TEXT,pid INTEGER,start TEXT,heartbeat REAL)')
            # Use a known live independent process; no daemon or child is created.
            db.execute('INSERT INTO reaper VALUES(1,?,?,?,?)', ('synthetic-session', 1, starttime(1), time.monotonic()))
            db.commit()
            try:
                args, _ = self.launch(['orchestrator_rpc', '--journal', str(path)])
                self.assertEqual(['--reaper-session', 'synthetic-session'], args[-2:])
                with self.assertRaises(SystemExit):
                    self.launch(['orchestrator_rpc', '--journal', str(path), '--reaper-session', 'stale'])
                db.execute('UPDATE reaper SET heartbeat=0')
                db.commit()
                from .control import Rejected
                with self.assertRaises(Rejected):
                    self.launch(['orchestrator_rpc', '--journal', str(path)])
            finally:
                db.close()

    def test_symlink_journal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'journal.sqlite'
            target = Path(directory) / 'target'
            target.touch(mode=0o600)
            path.symlink_to(target)
            with self.assertRaises(SystemExit):
                self.launch(['reaper', '--journal', str(path)])
