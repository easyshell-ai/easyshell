import copy
import unittest
from types import SimpleNamespace

from .acceptance import run
from .control import Journal, Rejected, policy, verify
from .docker_adapter import Config
from .test_control import Clock, FakeChannel, FakeRuntime, IMAGE, sample


class AcceptanceRuntime(FakeRuntime):
    def __init__(self, mode="worker_refusal"):
        super().__init__(mode)
        self.config = SimpleNamespace(image=IMAGE)
        self.empty_checks = 0

    def preflight(self, records):
        self.calls.append("preflight")
        if not records:
            self.empty_checks += 1
            if self.raw is not None or self.mode == "nonempty":
                raise Rejected("DAEMON_NOT_DEDICATED")

    def attach(self, target, deadline):
        self.calls.append("attach")
        runtime = self

        class EmptyInputChannel(FakeChannel):
            def exchange(self, payload):
                if payload != b"":
                    raise AssertionError("acceptance sent payload")
                runtime.calls.append("empty_stdin")
                if runtime.mode == "closed_pipe":
                    raise Rejected("ATTACH_CONNECTION_FAILED")
                if runtime.mode == "success":
                    return b'{"status":"succeeded","output":{}}'
                return b'{"status":"failed","error":"ISOLATION_UNVERIFIED"}'

        return EmptyInputChannel(self)


class AcceptanceTest(unittest.TestCase):
    def test_config_accepts_only_immutable_image_forms(self):
        for image in ("sha256:" + "c" * 64, IMAGE):
            self.assertEqual(image, Config("/run/easyshell-code/acceptance/docker.sock",
                                          "dedicated-id", image).image)
        for image in ("worker:latest", "worker", "sha256:" + "c" * 63,
                      "sha256:" + "C" * 64, "sha256:" + "z" * 64, "worker@sha256:short"):
            with self.subTest(image=image), self.assertRaises(Rejected):
                Config("/run/easyshell-code/acceptance/docker.sock", "dedicated-id", image)

    def test_inspect_image_config_id_and_repository_digest_are_distinct(self):
        journal = Journal(":memory:")
        self.addCleanup(journal.close)
        record = journal.claim(sample())
        for image in ("sha256:" + "c" * 64, IMAGE):
            with self.subTest(image=image):
                runtime = FakeRuntime()
                runtime.create(record.name, policy(image, record))
                verify(runtime.raw, record, image)
                changed = copy.deepcopy(runtime.raw)
                changed["Config"]["Image"] = "worker:latest"
                with self.assertRaises(Rejected):
                    verify(changed, record, image)
                changed = copy.deepcopy(runtime.raw)
                del changed["Image"]
                with self.assertRaises(Rejected):
                    verify(changed, record, image)
                changed["Image"] = "sha256:" + "d" * 64
                if image.startswith("sha256:"):
                    with self.assertRaisesRegex(Rejected, "IMAGE_UNVERIFIED"):
                        verify(changed, record, image)
                else:
                    # A manifest digest is not equal to its image config digest.
                    verify(changed, record, image)

    def test_refusal_or_closed_pipe_requires_cleanup_and_empty_daemon(self):
        for mode in ("worker_refusal", "closed_pipe"):
            with self.subTest(mode=mode):
                journal, clock = Journal(":memory:"), Clock()
                self.addCleanup(journal.close)
                runtime = AcceptanceRuntime(mode)
                report = run(runtime, journal, clock, clock.pause)
                self.assertEqual("passed", report["status"])
                self.assertTrue(report["cleaned"])
                self.assertEqual(2, runtime.empty_checks)
                self.assertIsNone(runtime.raw)
                self.assertIn("empty_stdin", runtime.calls)

    def test_nonempty_daemon_never_creates(self):
        journal = Journal(":memory:")
        self.addCleanup(journal.close)
        runtime = AcceptanceRuntime("nonempty")
        self.assertEqual("failed", run(runtime, journal)["status"])
        self.assertNotIn("create", runtime.calls)

    def test_unexpected_success_is_failure_even_after_cleanup(self):
        journal, clock = Journal(":memory:"), Clock()
        self.addCleanup(journal.close)
        runtime = AcceptanceRuntime("success")
        report = run(runtime, journal, clock, clock.pause)
        self.assertEqual("UNEXPECTED_WORKER_RESPONSE", report["error"])
        self.assertTrue(report["cleaned"])

    def test_cleanup_failure_is_not_acceptance(self):
        journal, clock = Journal(":memory:"), Clock()
        self.addCleanup(journal.close)
        report = run(AcceptanceRuntime("cleanup_fail"), journal, clock, clock.pause)
        self.assertEqual("CLEANUP_UNVERIFIED", report["error"])
        self.assertFalse(report["cleaned"])

    def test_docker_null_empty_fields_accepted_but_missing_rejected(self):
        journal = Journal(":memory:")
        self.addCleanup(journal.close)
        record = journal.claim(sample())
        runtime = FakeRuntime()
        runtime.create(record.name, policy(IMAGE, record))
        raw = copy.deepcopy(runtime.raw)
        for section in ("Config", "HostConfig"):
            for key, value in raw[section].items():
                if value in ([], {}):
                    raw[section][key] = None
        verify(raw, record, IMAGE)
        del raw["HostConfig"]["Binds"]
        with self.assertRaises(Rejected):
            verify(raw, record, IMAGE)
