import json
import copy
import unittest

from .control import Journal, Rejected, policy, verify
from .diagnostics import policy_diff
from .test_control import FakeRuntime, IMAGE, sample
from .test_acceptance import AcceptanceRuntime
from .acceptance import run
from .test_control import Clock


class DiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.journal = Journal(":memory:")
        self.addCleanup(self.journal.close)
        self.record = self.journal.claim(sample())
        runtime = FakeRuntime()
        runtime.create(self.record.name, policy(IMAGE, self.record))
        self.raw = runtime.raw

    def test_fixed_categories_do_not_disclose_values_or_keys(self):
        secret = "synthetic-sensitive-marker"
        self.raw["Config"]["Env"] = [secret + "=" + secret]
        self.raw["Config"]["Labels"] = {secret: secret}
        self.raw["HostConfig"]["CapDrop"] = [secret]
        self.raw["HostConfig"][secret] = secret
        del self.raw["HostConfig"]["Memory"]
        result = policy_diff(self.raw, self.record, IMAGE)
        self.assertNotIn(secret, json.dumps(result))
        self.assertEqual({"Config.Env", "HostConfig.CapDrop", "HostConfig.Memory"},
                         {entry["field"] for entry in result})
        self.assertIn({"field": "HostConfig.Memory", "expected_category": "integer",
                       "actual_category": "missing"}, result)

    def test_null_empty_normalization_and_matching_spec(self):
        self.assertEqual([], policy_diff(self.raw, self.record, IMAGE))

    def test_omitted_host_mounts_requires_explicit_live_empty_and_empty_legacy(self):
        del self.raw["HostConfig"]["Mounts"]
        verify(self.raw, self.record, IMAGE)
        self.assertEqual([], policy_diff(self.raw, self.record, IMAGE))
        for mutation in ("missing_live", "nonempty_live", "bind", "volumes_from",
                         "missing_binds", "missing_volumes_from", "image_volume", "host_mount"):
            with self.subTest(mutation=mutation):
                raw = copy.deepcopy(self.raw)
                if mutation == "missing_live":
                    del raw["Mounts"]
                elif mutation == "nonempty_live":
                    raw["Mounts"] = [{"Type": "bind", "Source": "synthetic-private-path"}]
                elif mutation == "bind":
                    raw["HostConfig"]["Binds"] = ["synthetic-private-path:/data"]
                elif mutation == "volumes_from":
                    raw["HostConfig"]["VolumesFrom"] = ["synthetic-container"]
                elif mutation == "missing_binds":
                    del raw["HostConfig"]["Binds"]
                elif mutation == "missing_volumes_from":
                    del raw["HostConfig"]["VolumesFrom"]
                elif mutation == "image_volume":
                    raw["Config"]["Volumes"] = {"/data": {}}
                else:
                    raw["HostConfig"]["Mounts"] = [{"Type": "bind"}]
                with self.assertRaises(Rejected):
                    verify(raw, self.record, IMAGE)
                self.assertTrue(policy_diff(raw, self.record, IMAGE))
        self.raw["Config"]["Env"] = None
        self.raw["HostConfig"]["Binds"] = None
        self.assertEqual([], policy_diff(self.raw, self.record, IMAGE))

    def test_acceptance_reports_diff_after_cleanup_without_start(self):
        journal, clock = Journal(":memory:"), Clock()
        self.addCleanup(journal.close)

        class Mismatch(AcceptanceRuntime):
            def create(self, name, spec):
                cid = super().create(name, spec)
                self.raw["HostConfig"]["SecurityOpt"] = ["synthetic-private-value"]
                return cid

        runtime = Mismatch()
        report = run(runtime, journal, clock, clock.pause)
        self.assertEqual("POLICY_UNVERIFIED", report["error"])
        self.assertTrue(report["cleaned"])
        self.assertEqual("HostConfig.SecurityOpt", report["policy_diff"][0]["field"])
        self.assertNotIn("start", runtime.calls)
        self.assertNotIn("synthetic-private-value", json.dumps(report))
