#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
for script in ./*.sh; do bash -n "$script"; done
python3 -I -B - <<'PY'
import ast, json, pathlib

root = pathlib.Path.cwd()
ast.parse((root / 'service-entry.py').read_text())
ast.parse((root / 'preflight.py').read_text())
ast.parse((root / 'runtime-entry.py').read_text())
json.loads((root / 'daemon.json.example').read_text())
dockerfile = (root / 'Dockerfile').read_text()
for name in ('CODE_JRE_IMAGE', 'CODE_JDK_IMAGE', 'CODE_CC_IMAGE'):
    assert f'ARG {name}\n' in dockerfile
    assert f'ARG {name}=' not in dockerfile
assert 'FROM scratch AS task' in dockerfile
assert 'COPY --from=approved_jre / /' in dockerfile
assert 'exportResources' in dockerfile
assert '--dependency-verification strict' in dockerfile
assert not any(line.startswith(('ENV ', 'VOLUME ')) for line in dockerfile.splitlines())
launcher = (root / 'launcher.c').read_text()
assert 'execve(args[0], args, empty_env)' in launcher
assert 'char *const empty_env[] = { NULL };' in launcher
assert '"/opt/java/openjdk/bin/java"' in launcher
units = list((root / 'systemd').glob('*.service'))
assert len(units) == 5
for unit in units:
    content = unit.read_text()
    runtime = unit.name in ('easyshell-code-containerd.service', 'easyshell-code-dockerd.service')
    for required in ('NoNewPrivileges=no' if runtime else 'NoNewPrivileges=yes', 'Restart=no',
                      'ExecStartPre=/bin/bash /usr/lib/easyshell-code/deploy/preflight.sh'):
        assert required in content, (unit.name, required)
    assert 'Environment=' not in content
    assert 'ExecStopPost=' not in content
    if runtime:
        assert 'UMask=0022' in content and 'PrivateTmp=no' in content
    else:
        assert 'ProtectSystem=strict' in content
control = (root / 'systemd/easyshell-code-control.service').read_text()
assert 'User=17001' in control and 'Group=17001' in control
assert 'InaccessiblePaths=/run/easyshell-code/runtime' in control
assert 'exit 78' in (root / 'preflight.sh').read_text()
print('STATIC_TEMPLATES_VERIFIED; protected release provisioning required')
PY
# Optional compiler parses only; no executable, daemon, download or service action.
if command -v cc >/dev/null 2>&1; then
    cc -std=c11 -Wall -Wextra -Werror -fsyntax-only launcher.c
fi
# Do not inspect or assume the state of a real host's /etc release manifest here.
# Missing/expired/tampered provisioning is verified by supervisor.test_preflight.
