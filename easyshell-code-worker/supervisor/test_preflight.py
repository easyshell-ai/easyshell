import hashlib
import json
import os
from pathlib import Path
import runpy
import tempfile
import time
import unittest


@unittest.skipUnless(os.geteuid() == 0, 'root-owned release proof')
class PreflightTest(unittest.TestCase):
    def test_missing_changed_expired_and_symlink_release_fail(self):
        module = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'deploy/preflight.py'))
        verify = module['verify']
        with tempfile.TemporaryDirectory(dir='/root') as directory:
            root = Path(directory)
            names = ['supervisor/control.py', 'supervisor/reaper.py', 'supervisor/host_guard.py',
                     'supervisor/docker_adapter.py', 'supervisor/cancel.py',
                     'supervisor/orchestrator_rpc.py', 'supervisor/control_server.py',
                     'deploy/service-entry.py', 'deploy/preflight.py', 'deploy/preflight.sh',
                     'deploy/runtime-entry.py']
            files = {}
            for name in names:
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.parent.chmod(0o755)
                file.write_text('synthetic reviewed release')
                file.chmod(0o644)
                files[name] = hashlib.sha256(file.read_bytes()).hexdigest()
            manifest = root / 'release.json'
            config = dict(schema=1, release_root=str(root), files=files,
                          task_image='sha256:'+'a'*64, daemon_id='synthetic-daemon',
                          acceptance=['runtime', 'mtls', 'cancel', 'reaper', 'restart', 'oom'],
                          expires_at=int(time.time())+3600)
            with self.assertRaises(FileNotFoundError):
                verify(manifest)
            manifest.write_text(json.dumps(config)); manifest.chmod(0o644)
            verify(manifest)
            manifest.chmod(0o666)
            with self.assertRaises(ValueError): verify(manifest)
            manifest.chmod(0o644)
            (root/names[0]).write_text('changed')
            with self.assertRaises(ValueError): verify(manifest)
            (root/names[0]).write_text('synthetic reviewed release')
            config['expires_at'] = 0; manifest.write_text(json.dumps(config))
            with self.assertRaises(ValueError): verify(manifest)
            link=root/'alias';link.symlink_to(manifest)
            with self.assertRaises(ValueError): verify(link)
