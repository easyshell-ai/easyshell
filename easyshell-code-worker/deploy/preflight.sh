#!/usr/bin/env bash
set -euo pipefail
# Missing, stale or changed protected release state fails closed (exit 78).
test -f /usr/lib/easyshell-code/deploy/preflight.py || exit 78
exec /usr/bin/python3 -I -B /usr/lib/easyshell-code/deploy/preflight.py
