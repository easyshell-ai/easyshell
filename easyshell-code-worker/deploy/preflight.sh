#!/usr/bin/env bash
set -euo pipefail
# Deliberately unconditional until reviewed core integration + real acceptance replace it.
echo 'DEPLOYMENT_BLOCKED: pinned build inputs, protected provisioning and systemd restart acceptance are still required' >&2
exit 78
