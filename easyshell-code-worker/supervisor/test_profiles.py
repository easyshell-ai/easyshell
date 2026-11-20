import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from .control import Journal, MEMORY, Rejected, Supervisor, policy, verify, JVM_OPTIONS, DIAGNOSTIC_COMMAND
from .docker_adapter import Config, DockerAdapter
from .host_guard import HostGuard
from .execution_acceptance import run as acceptance_run
from .test_control import CID, IMAGE, Clock, FakeRuntime, sample


class ProfileTest(unittest.TestCase):
    def test_fixed_runtime_resource_and_fallback_options(self):
        self.assertIn('-Dtruffle.UseFallbackRuntime=true', JVM_OPTIONS)
        self.assertIn('-Dpolyglot.engine.resourcePath=/opt/code-worker/resources', JVM_OPTIONS)
        self.assertIn('-Duser.home=/tmp', JVM_OPTIONS)
        self.assertNotIn('--enable-native-access=ALL-UNNAMED', JVM_OPTIONS)
        self.assertEqual('com.easyshell.codeworker.PythonDiagnosticWorker', DIAGNOSTIC_COMMAND[-1])

    def test_execution_acceptance_four_fixed_cases_and_strict_results(self):
        journal = Journal(":memory:")
        self.addCleanup(journal.close)
        adapter = DockerAdapter(Config("/run/easyshell-code/test/docker.sock", "dedicated-id", IMAGE,
                                       profile="execution"))
        results = [{"status": "succeeded", "output": {"n": 4}}] * 2 + [
            {"status": "failed", "error": "TIMEOUT"}] * 2
        with patch.object(adapter, "preflight"), patch(
                "supervisor.execution_acceptance.Supervisor") as model:
            model.return_value.execute.side_effect = results
            model.return_value.last_delivery = True
            model.return_value.last_timeout_origin = "worker"
            model.return_value.last_stage = "response_validated"
            self.assertEqual("passed", acceptance_run(adapter, journal)["status"])
            self.assertEqual(4, model.return_value.execute.call_count)
            model.return_value.execute.side_effect = None
            model.return_value.execute.return_value = {"status": "succeeded", "output": {"n": 4}, "extra": 1}
            self.assertEqual("failed", acceptance_run(adapter, journal)["status"])

    def test_fixed_commands_and_distinct_ownership(self):
        journals = [Journal(":memory:"), Journal(":memory:")]
        for journal in journals:
            self.addCleanup(journal.close)
        first = journals[0].claim(sample())
        second = journals[1].claim(sample(), "execution")
        self.assertNotEqual(first.name, second.name)
        self.assertNotEqual(first.labels()["easyshell.protocol"], second.labels()["easyshell.protocol"])
        self.assertEqual("com.easyshell.codeworker.Worker", policy(IMAGE, first)["Cmd"][-1])
        self.assertEqual(["/opt/code-worker/launcher"], policy(IMAGE, second)["Cmd"])
        runtime = FakeRuntime()
        runtime.create(second.name, policy(IMAGE, second))
        with self.assertRaises(Rejected):
            verify(runtime.raw, first, IMAGE)
        adapter = DockerAdapter(Config("/run/easyshell-code/test/docker.sock", "dedicated-id", IMAGE))
        with patch.object(adapter, "_identity"):
            with self.assertRaisesRegex(Rejected, "PROFILE_UNVERIFIED"):
                adapter.preflight([second])

    def test_missing_or_failed_guard_never_sends_source(self):
        for guard in (None, "reject"):
            journal, clock = Journal(":memory:"), Clock()
            self.addCleanup(journal.close)
            runtime = FakeRuntime()
            if guard:
                def reject(*args):
                    raise Rejected("HOST_LIMITS_UNVERIFIED")
                runtime.verify_limits = reject
            result = Supervisor(runtime, journal, IMAGE, clock, clock.pause,
                                profile="execution").execute(sample())
            self.assertEqual("failed", result["status"])
            self.assertNotIn("exchange", runtime.calls)
            self.assertIsNone(runtime.raw)


class HostGuardTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.proc, self.cg = self.root / "proc", self.root / "cgroup"
        pid = self.proc / "99"
        pid.mkdir(parents=True)
        self.leaf = self.cg / "system.slice" / ("docker-" + CID + ".scope")
        self.leaf.mkdir(parents=True)
        # field 3..21 followed by field 22 (starttime).
        (pid / "stat").write_text("99 (java test) " + " ".join(["S"] + ["0"] * 18 + ["12345"]))
        (pid / "cgroup").write_text("0::/system.slice/docker-" + CID + ".scope\n")
        for name, value in {"memory.max": str(MEMORY), "memory.swap.max": "0", "pids.max": "128",
                            "cpu.max": "100000 100000", "cgroup.procs": "99\n"}.items():
            (self.leaf / name).write_text(value)
        self.raw = {"Id": CID, "State": {"Pid": 99, "Running": True}}
        self.guard = HostGuard(self.proc, self.cg, Clock())

    def test_actual_limits_and_pid_membership(self):
        self.guard.verify(self.raw, 1)

    def test_limits_missing_unlimited_or_pid_not_member_rejected(self):
        for name in ("memory.max", "memory.swap.max", "pids.max", "cpu.max", "cgroup.procs"):
            path = self.leaf / name
            saved = path.read_text()
            path.write_text("max")
            with self.subTest(name=name), self.assertRaises(Rejected):
                self.guard.verify(self.raw, 1)
            path.unlink()
            with self.assertRaises(Rejected):
                self.guard.verify(self.raw, 1)
            path.write_text(saved)

    def test_permission_denied_fail_closed(self):
        with patch.object(self.guard, "read", side_effect=PermissionError()):
            with self.assertRaisesRegex(Rejected, "HOST_LIMITS_UNVERIFIED"):
                self.guard.verify(self.raw, 1)

    def test_pid_reuse_and_foreign_container_path_rejected(self):
        original = self.guard.identity
        calls = []

        def changing(pid, cid):
            value = original(pid, cid)
            calls.append(1)
            return value if len(calls) == 1 else ("99999", value[1], value[2])

        with patch.object(self.guard, "identity", side_effect=changing):
            with self.assertRaises(Rejected):
                self.guard.verify(self.raw, 1)
        (self.proc / "99" / "cgroup").write_text("0::/system.slice/foreign.scope")
        with self.assertRaises(Rejected):
            self.guard.verify(self.raw, 1)
