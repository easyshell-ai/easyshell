"""Native launcher proof using a synthetic JVM inside a disposable chroot.

No real JVM, runtime socket or guest code is used. Root/compiler are needed only
for this Linux deployment proof; ordinary policy tests do not require either.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.geteuid() == 0 and shutil.which('gcc') and shutil.which('chroot'),
                     'Linux root and static C toolchain required for launcher proof')
class LauncherEnvironmentTest(unittest.TestCase):
    def test_launcher_drops_inherited_environment_and_rejects_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java = root / 'opt/java/openjdk/bin/java'
            java.parent.mkdir(parents=True)
            stub = root / 'synthetic-jvm.c'
            stub.write_text('''
#include <string.h>
extern char **environ;
int main(int argc, char **argv) {
    if (environ[0] != 0) return 81;
    if (argc != 13) return 82;
    if (strcmp(argv[0], "/opt/java/openjdk/bin/java")) return 83;
    if (strcmp(argv[12], "com.easyshell.codeworker.IsolationWorker")) return 84;
    return 0;
}
''')
            launcher_source = Path(__file__).resolve().parents[1] / 'deploy/launcher.c'
            for source, output in ((stub, java), (launcher_source, root / 'launcher')):
                subprocess.run(['gcc', '-static', '-std=c11', '-Wall', '-Wextra', '-Werror',
                                '-o', str(output), str(source)], check=True, capture_output=True)
            environment = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin',
                           'EASYSHELL_SYNTHETIC_SECRET': 'must-not-reach-jvm',
                           'JAVA_TOOL_OPTIONS': '-javaagent:/synthetic-must-not-load.jar',
                           'GRAAL_PYTHONHOME': '/synthetic-must-not-use'}
            result = subprocess.run(['chroot', str(root), '/launcher'], env=environment,
                                    capture_output=True, timeout=5)
            self.assertEqual(0, result.returncode)
            rejected = subprocess.run(['chroot', str(root), '/launcher', '--anything'],
                                      env=environment, capture_output=True, timeout=5)
            self.assertEqual(64, rejected.returncode)
