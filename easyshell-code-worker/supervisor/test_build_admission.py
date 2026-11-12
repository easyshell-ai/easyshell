"""Build admission checks without a daemon, network or image download."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class BuildAdmissionTest(unittest.TestCase):
    def test_invalid_inputs_never_call_docker_and_valid_pins_are_preserved(self):
        source = Path(__file__).resolve().parents[1] / 'deploy/build-image.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'deploy').mkdir()
            (root / 'gradle').mkdir()
            (root / 'bin').mkdir()
            shutil.copy2(source, root / 'deploy/build-image.sh')
            (root / 'gradle.lockfile').write_text('synthetic-lock')
            (root / 'gradle/verification-metadata.xml').write_text('<synthetic/>')
            marker = root / 'docker-arguments'
            docker = root / 'bin/docker'
            docker.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$PROOF_ARGUMENTS"\n')
            docker.chmod(0o700)
            env = {'PATH': str(root / 'bin') + ':/usr/bin:/bin',
                   'PROOF_ARGUMENTS': str(marker), 'GRADLE_DISTRIBUTION_SHA256': 'a' * 64,
                   'CODE_OUTPUT_TAG': 'synthetic-worker:proof'}
            for name in ('JRE', 'JDK', 'CC'):
                repository = 'approved.example/' + name.lower()
                env['CODE_' + name + '_REPOSITORY'] = repository
                env['CODE_' + name + '_IMAGE'] = repository + '@sha256:' + 'b' * 64
            for changed in ({'CODE_JRE_IMAGE': 'approved.example/jre:latest'},
                            {'CODE_JDK_IMAGE': 'other.example/jdk@sha256:' + 'b' * 64},
                            {'GRADLE_DISTRIBUTION_SHA256': ''}):
                result = subprocess.run(['bash', str(root / 'deploy/build-image.sh')],
                                        env={**env, **changed}, capture_output=True, timeout=5)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(marker.exists())
            metadata = root / 'gradle/verification-metadata.xml'
            metadata.unlink()
            missing = subprocess.run(['bash', str(root / 'deploy/build-image.sh')],
                                     env=env, capture_output=True, timeout=5)
            self.assertNotEqual(0, missing.returncode)
            self.assertFalse(marker.exists())
            metadata.write_text('<synthetic/>')
            accepted = subprocess.run(['bash', str(root / 'deploy/build-image.sh')],
                                      env=env, capture_output=True, timeout=5)
            self.assertEqual(0, accepted.returncode)
            arguments = marker.read_text().splitlines()
            self.assertEqual('build', arguments[0])
            self.assertIn('CODE_JRE_IMAGE=' + env['CODE_JRE_IMAGE'], arguments)
            self.assertNotIn('run', arguments)
