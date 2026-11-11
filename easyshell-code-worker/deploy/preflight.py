"""Protected release admission. Missing provisioning rejects; never provisions itself."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys


def protected(path, uid=0):
    path = Path(path)
    if not path.is_absolute() or str(path.resolve(strict=True)) != str(path):
        raise ValueError('path')
    for parent in (path, *path.parents):
        info = parent.stat()
        if info.st_uid != uid or info.st_mode & 0o022:
            raise ValueError('ownership')
    return path


def verify(config_path):
    path = protected(config_path)
    # Manifest contains identities/hashes only, never secrets; control UID needs read access.
    if stat.S_IMODE(path.stat().st_mode) not in (0o600, 0o644) or path.stat().st_size > 65536:
        raise ValueError('configuration')
    config = json.loads(path.read_text())
    if set(config) != {'schema', 'release_root', 'files', 'task_image', 'daemon_id',
                       'acceptance', 'expires_at'} or config['schema'] != 1:
        raise ValueError('schema')
    import time
    if not isinstance(config['expires_at'], int) or not time.time() < config['expires_at'] <= time.time() + 31 * 86400:
        raise ValueError('expiry')
    if config['acceptance'] != ['runtime', 'mtls', 'cancel', 'reaper', 'restart', 'oom']:
        raise ValueError('acceptance')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', config['task_image']):
        raise ValueError('image')
    if not re.fullmatch(r'[A-Za-z0-9:._-]{8,128}', config['daemon_id']):
        raise ValueError('daemon')
    root = protected(config['release_root'])
    files = config['files']
    if not isinstance(files, dict) or not files or len(files) > 1024:
        raise ValueError('manifest')
    required = {'supervisor/control.py', 'supervisor/reaper.py', 'supervisor/host_guard.py',
                'supervisor/docker_adapter.py', 'supervisor/cancel.py',
                'supervisor/orchestrator_rpc.py', 'supervisor/control_server.py',
                'deploy/service-entry.py', 'deploy/preflight.py', 'deploy/preflight.sh',
                'deploy/runtime-entry.py'}
    if not required.issubset(files):
        raise ValueError('incomplete release')
    for name, digest in files.items():
        if not isinstance(name, str) or name.startswith('/') or any(p in ('', '.', '..') for p in name.split('/')):
            raise ValueError('manifest path')
        item = protected(root / name)
        if not item.is_file() or not re.fullmatch('[0-9a-f]{64}', digest):
            raise ValueError('manifest entry')
        if hashlib.sha256(item.read_bytes()).hexdigest() != digest:
            raise ValueError('release changed')
    if not Path('/sys/fs/cgroup/cgroup.controllers').is_file():
        raise ValueError('cgroup v2')
    # A protected attestation is operator authority, not automatic discovery of trust.
    # Runtime identity/limits/reaper and TLS are checked again by actual execution.


if __name__ == '__main__':
    try:
        if len(sys.argv) != 1:
            raise ValueError('arguments')
        verify('/etc/easyshell-code/release.json')
    except Exception:
        print('DEPLOYMENT_UNVERIFIED', file=sys.stderr)
        raise SystemExit(78)
