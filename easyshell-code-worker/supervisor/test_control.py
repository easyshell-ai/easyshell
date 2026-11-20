import copy
import io
import json
import unittest

from .__main__ import main
from .control import (COMMAND, MAX_OUTPUT, Journal, NotFound, Rejected, Snapshot, Supervisor,
                      policy, request, verify)
from .docker_adapter import Config, DockerAdapter


IMAGE = "example.invalid/code-worker@sha256:" + "a" * 64
CID = "b" * 64


def sample():
    return {"requestId": "test-1", "language": "python", "source": "def main(i): return i",
            "input": {}, "timeoutMs": 100}


class Clock:
    def __init__(self):
        self.now = 0

    def __call__(self):
        return self.now

    def pause(self, amount):
        self.now += amount


class FakeRuntime:
    def __init__(self, mode="success"):
        self.mode, self.raw, self.calls, self.reads = mode, None, [], 0

    def preflight(self, records):
        self.calls.append("preflight")
        if self.mode == "preflight_fail":
            raise Rejected("DAEMON_NOT_DEDICATED")

    def create(self, name, spec):
        self.calls.append("create")
        self.raw = {"Id": CID, "Name": "/" + name, "Image": "sha256:" + "c" * 64,
                    "Config": {k: copy.deepcopy(v) for k, v in spec.items() if k != "HostConfig"},
                    "HostConfig": copy.deepcopy(spec["HostConfig"]), "Mounts": [],
                    "State": {"Running": False, "Pid": 0, "Paused": False, "Restarting": False,
                              "Status": "created", "ExitCode": 0, "OOMKilled": False}}
        if self.mode == "unknown_create":
            raise OSError("synthetic lost response")
        if self.mode == "bad_id":
            return "invalid"
        if self.mode == "missing_memory":
            del self.raw["HostConfig"]["Memory"]
        if self.mode == "foreign":
            self.raw["Config"]["Labels"] = {}
        return CID

    def inspect(self, target):
        self.calls.append("inspect")
        if self.raw is None:
            raise NotFound()
        self.reads += 1
        if self.mode == "bad_inspect":
            raise OSError("synthetic inspect failure")
        if (self.raw["State"]["Status"] == "running"
                and self.mode not in ("timeout", "missing_memory", "foreign", "stuck")):
            self.raw["State"].update(Running=False, Pid=0, Status="exited")
            if self.mode == "exit_failure":
                self.raw["State"]["ExitCode"] = 1
            if self.mode == "oom":
                self.raw["State"]["OOMKilled"] = True
        return Snapshot(copy.deepcopy(self.raw))

    def attach(self, target, deadline):
        self.calls.append("attach")
        if self.mode == "attach_failure":
            raise OSError("synthetic attach failure")
        return FakeChannel(self)

    def start(self, target, deadline):
        self.calls.append("start")
        if self.mode == "start_failure":
            raise OSError("synthetic start response lost")
        self.raw["State"].update(Running=True, Pid=99, Status="running")

    def kill(self, target):
        self.calls.append("kill")
        if self.mode != "stuck":
            self.raw["State"].update(Running=False, Pid=0, Status="exited")

    def remove(self, target):
        self.calls.append("remove")
        if self.mode == "cleanup_fail":
            raise OSError("synthetic cleanup failure")
        if self.mode != "remove_lies":
            self.raw = None


class FakeChannel:
    def __init__(self, runtime):
        self.runtime = runtime

    def exchange(self, payload):
        self.runtime.calls.append("exchange")
        assert "requestId" not in json.loads(payload)
        if self.runtime.mode == "large_output":
            raise Rejected("OUTPUT_TOO_LARGE")
        if self.runtime.mode == "exchange_failure":
            raise OSError("synthetic connection failure")
        if self.runtime.mode == "worker_refusal":
            return b'{"status":"failed","error":"ISOLATION_UNVERIFIED"}\n'
        return b'{"status":"succeeded","output":{}}'

    def close(self):
        self.runtime.calls.append("close")
        if self.runtime.mode == "close_failure":
            raise OSError("synthetic close failure")


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.journal = Journal(":memory:")
        self.addCleanup(self.journal.close)

    def run_mode(self, mode):
        runtime, clock = FakeRuntime(mode), Clock()
        supervisor = Supervisor(runtime, self.journal, IMAGE, clock, clock.pause)
        return supervisor.execute(sample()), runtime, supervisor

    def test_result_only_after_removal_confirmation(self):
        result, runtime, _ = self.run_mode("success")
        self.assertEqual({"status": "succeeded", "output": {}}, result)
        self.assertIsNone(runtime.raw)
        self.assertEqual("inspect", runtime.calls[-1])

    def test_timeout_kills_and_removes(self):
        result, runtime, _ = self.run_mode("timeout")
        self.assertEqual("TIMEOUT", result["error"])
        self.assertIn("kill", runtime.calls)
        self.assertIsNone(runtime.raw)

    def test_cleanup_failure_never_succeeds(self):
        for mode in ("cleanup_fail", "remove_lies", "stuck", "bad_inspect"):
            with self.subTest(mode=mode):
                journal = Journal(":memory:")
                self.addCleanup(journal.close)
                clock = Clock()
                result = Supervisor(FakeRuntime(mode), journal, IMAGE, clock, clock.pause).execute(sample())
                self.assertEqual("CLEANUP_UNVERIFIED", result["error"])
                self.assertEqual("uncertain", journal.pending()[0].state)

    def test_failures_do_not_retry_start_or_attach(self):
        for mode in ("start_failure", "attach_failure", "exchange_failure", "close_failure"):
            with self.subTest(mode=mode):
                journal, clock = Journal(":memory:"), Clock()
                self.addCleanup(journal.close)
                runtime = FakeRuntime(mode)
                result = Supervisor(runtime, journal, IMAGE, clock, clock.pause).execute(sample())
                self.assertEqual("failed", result["status"])
                self.assertLessEqual(runtime.calls.count("start"), 1)
                self.assertEqual(1, runtime.calls.count("attach"))
                self.assertIsNone(runtime.raw)

    def test_exit_status_and_worker_refusal(self):
        for mode, error in (("exit_failure", "EXECUTION_FAILED"), ("oom", "RESOURCE_EXHAUSTED"),
                            ("worker_refusal", "ISOLATION_UNVERIFIED")):
            with self.subTest(mode=mode):
                journal, clock = Journal(":memory:"), Clock()
                self.addCleanup(journal.close)
                runtime = FakeRuntime(mode)
                result = Supervisor(runtime, journal, IMAGE, clock, clock.pause).execute(sample())
                self.assertEqual(error, result["error"])
                self.assertIsNone(runtime.raw)

    def test_order_is_verify_attach_start_exchange_exit_cleanup(self):
        _, runtime, _ = self.run_mode("success")
        self.assertEqual(["preflight", "create", "inspect", "attach", "start", "exchange", "close",
                          "inspect", "inspect", "inspect", "remove", "inspect"], runtime.calls)

    def test_unknown_create_response_recovers_by_owned_name(self):
        result, runtime, _ = self.run_mode("unknown_create")
        self.assertEqual("RUNTIME_UNCERTAIN", result["error"])
        self.assertIsNone(runtime.raw)
        self.assertEqual(1, runtime.calls.count("create"))

    def test_bad_create_response_is_not_success(self):
        result, runtime, _ = self.run_mode("bad_id")
        self.assertEqual("CREATE_UNCERTAIN", result["error"])
        self.assertIsNone(runtime.raw)

    def test_missing_limit_is_rejected_and_cleaned(self):
        result, runtime, _ = self.run_mode("missing_memory")
        self.assertEqual("POLICY_UNVERIFIED", result["error"])
        self.assertIsNone(runtime.raw)

    def test_output_bounded(self):
        result, runtime, _ = self.run_mode("large_output")
        self.assertEqual("OUTPUT_TOO_LARGE", result["error"])
        self.assertIsNone(runtime.raw)

    def test_foreign_container_never_killed_or_removed(self):
        result, runtime, _ = self.run_mode("foreign")
        self.assertEqual("CLEANUP_UNVERIFIED", result["error"])
        self.assertNotIn("kill", runtime.calls)
        self.assertNotIn("remove", runtime.calls)

    def test_failed_preflight_never_touches_container(self):
        result, runtime, _ = self.run_mode("preflight_fail")
        self.assertEqual("DAEMON_NOT_DEDICATED", result["error"])
        self.assertEqual(["preflight"], runtime.calls)

    def test_duplicate_and_conflicting_request_ids_never_create_twice(self):
        _, runtime, supervisor = self.run_mode("success")
        self.assertEqual("DUPLICATE_REQUEST", supervisor.execute(sample())["error"])
        changed = sample() | {"input": {"changed": True}}
        self.assertEqual("REQUEST_ID_CONFLICT", supervisor.execute(changed)["error"])
        self.assertEqual(1, runtime.calls.count("create"))

    def test_reconcile_only_journal_owned(self):
        value = sample()
        record = self.journal.claim(value)
        runtime, clock = FakeRuntime("timeout"), Clock()
        runtime.create(record.name, policy(IMAGE, record))
        supervisor = Supervisor(runtime, self.journal, IMAGE, clock, clock.pause)
        supervisor.reconcile()
        self.assertIsNone(runtime.raw)
        self.assertEqual([], self.journal.pending())

    def test_reconcile_unknown_absent_name_stays_uncertain(self):
        self.journal.claim(sample())
        with self.assertRaises(Rejected):
            Supervisor(FakeRuntime(), self.journal, IMAGE).reconcile()
        self.assertEqual("uncertain", self.journal.pending()[0].state)

    def test_journal_capacity(self):
        journal = Journal(":memory:", capacity=1)
        self.addCleanup(journal.close)
        journal.claim(sample())
        with self.assertRaisesRegex(Rejected, "JOURNAL_FULL"):
            journal.claim(sample() | {"requestId": "next"})


class ContractTest(unittest.TestCase):
    def test_cli_disabled_and_rejects_request_configuration(self):
        for extra, error in (({}, "ISOLATION_UNVERIFIED"), ({"image": IMAGE}, "INVALID_REQUEST"),
                             ({"command": COMMAND}, "INVALID_REQUEST"), ({"env": {}}, "INVALID_REQUEST")):
            out = io.StringIO()
            main(io.BytesIO(json.dumps(sample() | extra).encode()), out)
            self.assertEqual(error, json.loads(out.getvalue())["error"])

    def test_json_bounds_and_duplicates(self):
        for data in (b"x" * 65537, b'{"requestId":1,"requestId":2}', b"[" * 100 + b"]" * 100,
                     b'{"x":NaN}', b"{} {}"):
            with self.assertRaises(Rejected):
                request(data)

    def test_config_rejects_system_socket_and_unpinned_image(self):
        with self.assertRaises(Rejected):
            Config("/var/run/docker.sock", "dedicated-id", IMAGE)
        with self.assertRaises(Rejected):
            Config("/run/easyshell-code/test/docker.sock", "dedicated-id", "worker:latest")

    def test_policy_contains_no_payload_or_secret(self):
        journal = Journal(":memory:")
        self.addCleanup(journal.close)
        record = journal.claim(sample())
        spec = policy(IMAGE, record)
        self.assertEqual([], spec["Env"])
        self.assertEqual("65532:65532", spec["User"])
        self.assertEqual("none", spec["HostConfig"]["NetworkMode"])
        self.assertEqual("none", spec["HostConfig"]["LogConfig"]["Type"])
        self.assertEqual(536870912, spec["HostConfig"]["MemorySwap"])
        self.assertNotIn(sample()["source"], json.dumps(spec))

    def test_every_fixed_inspect_field_is_required(self):
        journal = Journal(":memory:")
        self.addCleanup(journal.close)
        record = journal.claim(sample())
        runtime = FakeRuntime()
        runtime.create(record.name, policy(IMAGE, record))
        verify(runtime.raw, record, IMAGE)
        for section in ("Config", "HostConfig"):
            for key in runtime.raw[section]:
                with self.subTest(section=section, key=key):
                    changed = copy.deepcopy(runtime.raw)
                    del changed[section][key]
                    if section == "HostConfig" and key == "Mounts":
                        verify(changed, record, IMAGE)
                        continue
                    with self.assertRaises(Rejected):
                        verify(changed, record, IMAGE)


class FakeDocker(DockerAdapter):
    """Overrides transport entirely; tests never touch sockets."""
    def __init__(self, info, containers):
        super().__init__(Config("/run/easyshell-code/test/docker.sock", "dedicated-id", IMAGE))
        self.info, self.containers, self.calls = info, containers, []

    def _http(self, method, route, body=None, missing=False):
        self.calls.append((method, route))
        if route == "/info":
            return self.info
        if route.startswith("/containers/json?"):
            return self.containers
        raise AssertionError("Unexpected transport operation")


class AdapterTest(unittest.TestCase):
    def info(self):
        return {"ID": "dedicated-id", "CgroupVersion": "2", "CgroupDriver": "systemd",
                "SecurityOptions": ["name=seccomp,profile=builtin", "name=apparmor"]}

    def test_wrong_daemon_id_rejected_before_container_operations(self):
        adapter = FakeDocker(self.info() | {"ID": "shared-daemon"}, [])
        with self.assertRaisesRegex(Rejected, "DAEMON_UNVERIFIED"):
            adapter.preflight([])
        self.assertEqual([("GET", "/info")], adapter.calls)

    def test_non_workload_container_rejected(self):
        adapter = FakeDocker(self.info(), [{"Id": CID, "Names": ["/existing-service"]}])
        with self.assertRaisesRegex(Rejected, "DAEMON_NOT_DEDICATED"):
            adapter.preflight([])
        self.assertTrue(all(method == "GET" for method, _ in adapter.calls))

    def test_empty_dedicated_daemon_accepted_only_as_metadata_preflight(self):
        adapter = FakeDocker(self.info(), [])
        adapter.preflight([])
        self.assertTrue(adapter.verified)

    def test_adapter_has_no_execution_or_arbitrary_command_method(self):
        for name in ("exec", "logs", "run"):
            self.assertFalse(hasattr(DockerAdapter, name))


if __name__ == "__main__":
    unittest.main()
