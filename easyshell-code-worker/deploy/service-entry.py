"""Fixed module dispatch from a protected release; Python isolated mode required."""
import runpy
import sys
import os
import sqlite3
import stat
from pathlib import Path

if not sys.flags.isolated or len(sys.argv) < 2 or sys.argv[1] not in (
        "reaper", "orchestrator_rpc", "control_server"):
    raise SystemExit(64)
module = "supervisor." + sys.argv.pop(1)
# Never import from cwd, PYTHONPATH, user site, or a request-selected path.
sys.path.insert(0, "/opt/easyshell-code")


def journal_argument(arguments):
    if arguments.count('--journal') != 1:
        raise SystemExit(64)
    position = arguments.index('--journal')
    if position + 1 >= len(arguments):
        raise SystemExit(64)
    path = Path(arguments[position + 1])
    if not path.is_absolute() or str(path.resolve()) != str(path):
        raise SystemExit(78)
    parent = path.parent.stat()
    if parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
        raise SystemExit(78)
    return path


if module == 'supervisor.reaper':
    path = journal_argument(sys.argv)
    if path.exists() and '--resume-existing' not in sys.argv:
        sys.argv.append('--resume-existing')
elif module == 'supervisor.orchestrator_rpc':
    path = journal_argument(sys.argv)
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(78)
    # This is a trusted launch-time lookup, not authority to replay any task.
    connection = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=0.2)
    try:
        row = connection.execute('SELECT session FROM reaper WHERE singleton=1').fetchone()
        if row is None:
            raise SystemExit(78)
        from supervisor.reaper import require_live
        from types import SimpleNamespace
        require_live(SimpleNamespace(db=connection), row[0])
        if '--reaper-session' in sys.argv:
            raise SystemExit(64)
        sys.argv.extend(['--reaper-session', row[0]])
    finally:
        connection.close()
runpy.run_module(module, run_name="__main__")
