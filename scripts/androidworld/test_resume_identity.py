"""Exact historical migration checks on synthetic files, without model/device IO."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from _test_fixtures import resume_fixture
from protocol import ResumeCompatibility, Results, condition, digest, stable_protocol
from summarize import summarize


class ResumeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.compat = ResumeCompatibility(resume_fixture(self.root))
        self.path = self.root / "SystemBrightnessMin_t1.json"; self.old = json.loads(self.path.read_text())
        self.spec = copy.deepcopy(self.old["identity"]); self.spec.update(schema=3, protocol=self.compat.value["new_protocol"],
            protocol_sha256=digest(self.compat.value["new_protocol"]), run_id="SystemBrightnessMin_C2_eval_t1_v0_" + digest(self.compat.value["new_protocol"])[:12])

    def test_exact_four_skip_keeps_original_files_and_failed_result(self):
        before = {p: p.read_bytes() for p in self.root.glob("*_t1.*")}
        store = object.__new__(Results); store.directory = self.root
        for entry in self.compat.entries.values():
            path = self.root / (entry["label"] + ".json"); row = json.loads(path.read_text())
            target = copy.deepcopy(row["identity"]); target.update(schema=3, protocol=self.compat.value["new_protocol"],
                protocol_sha256=digest(self.compat.value["new_protocol"]), run_id=entry["task"] + "_C2_eval_t1_v0_" + digest(self.compat.value["new_protocol"])[:12])
            self.assertTrue(store.exists_complete(target, self.compat))
            with self.assertRaises(RuntimeError): store.exists_complete(target)
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_changed_budget_seed_goal_model_apk_and_source_rejected(self):
        for key, value in (("official_max_steps", 11), ("seed", 31), ("goal", "different")):
            target = copy.deepcopy(self.spec); target[key] = value
            with self.assertRaises(RuntimeError): self.compat.validate(self.old, self.path, target)
        target = copy.deepcopy(self.spec); target["protocol"]["apk_sha256"] = "b" * 64
        with self.assertRaises(RuntimeError): self.compat.validate(self.old, self.path, target)
        for key in ("models", "sources"):
            target = copy.deepcopy(self.spec); target["protocol"]["experiment"][key]["changed"] = True
            with self.assertRaises(RuntimeError): self.compat.validate(self.old, self.path, target)

    def test_tampered_artifact_and_invalid_manifest_rejected(self):
        self.path.with_suffix(".jpg").write_bytes(b"changed")
        with self.assertRaises(RuntimeError): self.compat.validate(self.old, self.path, self.spec)
        path = self.root / "invalid.json"; value = copy.deepcopy(self.compat.value); value["unexpected"] = True
        path.write_text(json.dumps(value))
        with self.assertRaises(ValueError): ResumeCompatibility(path)

    def test_summary_explicit_view_no_three_trial_metric_or_duplicate(self):
        paths = [self.root / (x["label"] + ".json") for x in self.compat.entries.values()]
        rows = [json.loads(p.read_text()) for p in paths]
        report = summarize(rows, self.compat, paths)[0]
        self.assertEqual(report["episodes"], 4); self.assertEqual(report["trial_SR"][1]["expected"], 10)
        self.assertIsNone(report["SR_mean"]); self.assertIsNone(report["pass3"])
        with self.assertRaises(ValueError): summarize(rows + rows[:1], self.compat, paths + paths[:1])


if __name__ == "__main__": unittest.main()
