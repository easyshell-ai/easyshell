import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch
import sys

from .reaper import notify_ready
from .control import Rejected


class ReaperNotifyTest(unittest.TestCase):
    def test_failed_reconciliation_never_announces_ready(self):
        from .reaper import main
        with tempfile.TemporaryDirectory() as directory:
            arguments = ['reaper', '--socket', '/run/easyshell-code/test/docker.sock',
                         '--expected-daemon-id', 'synthetic-daemon',
                         '--image', 'sha256:' + 'a' * 64,
                         '--journal', str(Path(directory) / 'journal.sqlite')]
            with patch.object(sys, 'argv', arguments), \
                    patch('supervisor.reaper.DockerAdapter'), \
                    patch('supervisor.reaper.tick', side_effect=Rejected('REAPER_UNVERIFIED')), \
                    patch('supervisor.reaper.notify_ready') as notification:
                with self.assertRaises(Rejected):
                    main()
                notification.assert_not_called()

    def test_readiness_uses_bounded_unix_datagram(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'notify.sock')
            with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as listener:
                listener.bind(path)
                listener.settimeout(1)
                with patch.dict(os.environ, {'NOTIFY_SOCKET': path}):
                    notify_ready()
                self.assertEqual(b'READY=1', listener.recv(64))

    def test_absent_notification_is_optional_and_invalid_address_fails(self):
        with patch.dict(os.environ, {}, clear=True):
            notify_ready()
        for address in ('', 'relative.sock', '/' + 'x' * 108):
            with self.subTest(address=address), patch.dict(os.environ, {'NOTIFY_SOCKET': address}):
                with self.assertRaises(Rejected):
                    notify_ready()
