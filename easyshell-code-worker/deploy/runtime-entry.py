"""Join the dedicated containerd namespaces, never a request-selected process."""
import os
from pathlib import Path
import subprocess
import sys


def start():
    if len(sys.argv) != 1 or os.geteuid() != 0:
        raise ValueError('arguments')
    pid = subprocess.check_output(['/usr/bin/systemctl', 'show',
        'easyshell-code-containerd.service', '--property=MainPID', '--value'],
        timeout=2, text=True).strip()
    if not pid.isdigit() or int(pid) <= 1:
        raise ValueError('pid')
    proc = Path('/proc') / pid
    before = (proc/'stat').read_text()
    if (proc/'exe').resolve() != Path('/usr/bin/containerd').resolve() or proc.stat().st_uid != 0:
        raise ValueError('runtime')
    args = (proc/'cmdline').read_bytes().split(b'\0')
    if b'/etc/easyshell-code/containerd.toml' not in args:
        raise ValueError('configuration')
    descriptors = []
    try:
        for namespace in ('mnt', 'net'):
            fd = os.open(proc/'ns'/namespace, os.O_RDONLY)
            os.set_inheritable(fd, True)
            descriptors.append(fd)
        if (proc/'stat').read_text() != before:
            # CPU counters may change: compare stable process starttime instead.
            old = before[before.rfind(')')+2:].split()[19]
            new = (proc/'stat').read_text().rsplit(')', 1)[1].split()[19]
            if old != new:
                raise ValueError('pid reused')
        os.execv('/usr/bin/nsenter', ['/usr/bin/nsenter',
            '--mount=/proc/self/fd/'+str(descriptors[0]),
            '--net=/proc/self/fd/'+str(descriptors[1]), '--',
            '/usr/bin/dockerd', '--config-file=/etc/easyshell-code/daemon.json'])
    finally:
        for fd in descriptors:
            os.close(fd)


if __name__ == '__main__':
    try:
        start()
    except Exception:
        print('RUNTIME_NAMESPACE_UNVERIFIED', file=sys.stderr)
        raise SystemExit(78)
