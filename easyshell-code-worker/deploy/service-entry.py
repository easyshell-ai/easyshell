"""Fixed module dispatch from a protected release; Python isolated mode required."""
import runpy
import sys

if not sys.flags.isolated or len(sys.argv) < 2 or sys.argv[1] not in (
        "reaper", "orchestrator_rpc", "control_server"):
    raise SystemExit(64)
module = "supervisor." + sys.argv.pop(1)
# Never import from cwd, PYTHONPATH, user site, or a request-selected path.
sys.path.insert(0, "/opt/easyshell-code")
runpy.run_module(module, run_name="__main__")
