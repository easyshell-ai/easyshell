import os
from pathlib import Path
import runpy
import sys
import unittest
from unittest.mock import patch


class RuntimeEntryTest(unittest.TestCase):
    def test_caller_cannot_choose_pid_or_command(self):
        module = runpy.run_path(str(Path(__file__).resolve().parents[1]/'deploy/runtime-entry.py'))
        with patch.object(sys, 'argv', ['runtime-entry', '--pid', '1']), \
                patch('subprocess.check_output') as query, patch('os.execv') as execute:
            with self.assertRaises(ValueError): module['start']()
            query.assert_not_called(); execute.assert_not_called()

    def test_missing_mainpid_never_enters_namespace(self):
        module = runpy.run_path(str(Path(__file__).resolve().parents[1]/'deploy/runtime-entry.py'))
        with patch.object(sys, 'argv', ['runtime-entry']), patch('os.geteuid', return_value=0), \
                patch('subprocess.check_output', return_value='0\n'), patch('os.execv') as execute:
            with self.assertRaises(ValueError): module['start']()
            execute.assert_not_called()
