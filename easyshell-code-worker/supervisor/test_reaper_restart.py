import os
from pathlib import Path
import tempfile
import unittest

from .reaper import open_owned_journal
from .control import Journal, Rejected


class ReaperRestartTest(unittest.TestCase):
    def test_restart_retains_records_and_excludes_another_reaper(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'retained.sqlite')
            fd = open_owned_journal(path)
            journal = Journal(path)
            record = journal.claim({'requestId': 'retained', 'language': 'javascript',
                                    'source': '', 'input': {}, 'timeoutMs': 1000})
            journal.close()
            with self.assertRaises(BlockingIOError):
                open_owned_journal(path, True)
            os.close(fd)
            resumed = open_owned_journal(path, True)
            try:
                journal = Journal(path)
                self.assertEqual([row.request_id for row in journal.pending()], [record.request_id])
                journal.close()
            finally:
                os.close(resumed)

    def test_restart_does_not_create_missing_file_or_follow_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'missing.sqlite'
            with self.assertRaises(FileNotFoundError):
                open_owned_journal(str(path), True)
            target = Path(directory) / 'target'
            target.touch(mode=0o600)
            path.symlink_to(target)
            with self.assertRaises(Rejected):
                open_owned_journal(str(path), True)

    def test_restart_rejects_world_readable_journal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'unsafe.sqlite'
            path.touch(mode=0o644)
            with self.assertRaises(Rejected):
                open_owned_journal(str(path), True)
