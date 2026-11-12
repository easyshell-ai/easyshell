import os
from pathlib import Path
import socket
import tempfile
import unittest
from .orchestrator_rpc import acquire_endpoint, RPCError


class EndpointRestartTest(unittest.TestCase):
    def test_stale_socket_removed_only_under_exclusive_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory)/'rpc.sock')
            with socket.socket(socket.AF_UNIX) as listener: listener.bind(path)
            os.chmod(path,0o600)
            fd=acquire_endpoint(path,os.geteuid())
            try:
                self.assertFalse(os.path.exists(path))
                with self.assertRaises(BlockingIOError): acquire_endpoint(path,os.geteuid())
            finally:os.close(fd)

    def test_live_socket_and_regular_file_are_never_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            path=str(Path(directory)/'rpc.sock')
            with socket.socket(socket.AF_UNIX) as listener:
                listener.bind(path);listener.listen(1);os.chmod(path,0o600)
                with self.assertRaises(RPCError):acquire_endpoint(path,os.geteuid())
                self.assertTrue(os.path.exists(path))
            os.unlink(path);Path(path).write_text('preserve')
            with self.assertRaises(RPCError):acquire_endpoint(path,os.geteuid())
            self.assertEqual('preserve',Path(path).read_text())
