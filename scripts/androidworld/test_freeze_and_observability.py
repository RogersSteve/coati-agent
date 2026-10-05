"""Offline empty/nonempty freeze and APK observation regression tests."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from harness import frozen_manuals, inventory_summary, validate_frozen_inventory
from observability import ocr_evidence
from protocol import condition, digest
from summarize import summarize


class FreezeTests(unittest.TestCase):
    def fixture(self, root, files):
        experiment = {"sources": {"Coati": "commit"}, "models": {}, "tasks": ["Question"], "variants": 1, "perturbation": ""}
        row = {"config": "C4", "phase": "learn", "seed": 31, "task": "Question", "variant": 0, "complete": True, "env_failure": None, "success": 1}
        raw = json.dumps(row).encode()
        (root / "Question_t1.json").write_bytes(raw)
        inventory = inventory_summary(files)
        freeze = {"schema": 2, **inventory, "inventory": inventory, "config": "C4", "training_seed": 31, "training_complete": True,
                  "files": files, "files_sha256": digest(files), "experiment": experiment,
                  "learning_summary": {"source_versions": experiment["sources"], "apk_sha256": "apk", "episodes": 1, "official_score_sum": 1},
                  "learning_record_labels": ["Question_t1.json"], "learning_episode_files_sha256": {"Question_t1.json": hashlib.sha256(raw).hexdigest()},
                  "learning_records_sha256": digest([row])}
        path = root / "manual-freeze.json"
        path.write_text(json.dumps(freeze))
        return path, freeze, experiment

    def test_empty_and_nonempty_are_explicit_and_keep_training_checks(self):
        for files in ({}, {"no_backup/app_manuals_v2/app/manual.md": "hash"}):
            with self.subTest(files=files), tempfile.TemporaryDirectory() as d:
                path, value, experiment = self.fixture(Path(d), files)
                self.assertEqual(frozen_manuals(path, condition("C4"), experiment, "apk"), value)
                self.assertEqual(value["empty"], not files)
                with self.assertRaises(ValueError): frozen_manuals(path, condition("C4"), experiment, "different-apk")
                with self.assertRaises(ValueError): frozen_manuals(None, condition("C4"), experiment)
                changed = copy.deepcopy(value); changed["empty"] = not value["empty"]
                with self.assertRaises(ValueError): validate_frozen_inventory(changed)
                (Path(d) / "Question_t1.json").write_text("changed")
                with self.assertRaises(ValueError): frozen_manuals(path, condition("C4"), experiment)

    def test_legacy_empty_cannot_bypass_new_manifest(self):
        with self.assertRaises(ValueError): validate_frozen_inventory({"files": {}})
        with self.assertRaises(ValueError): validate_frozen_inventory({"schema": 2, "files": {}, "empty": True, "manual_count": 1})
        self.assertEqual(inventory_summary({"metadata.json": "hash"}), {"empty": True, "manual_count": 0, "file_count": 1})


class ObservationTests(unittest.TestCase):
    def test_current_pid_and_buffer_order_not_regressing_device_time(self):
        lines = "1.0 7 7 I CoatiService: OCR ready: detection pass 97ms\n2.0 8 8 W CoatiService: OCR disabled: one detection pass takes 4000ms\n0.5 7 7 W CoatiJev: OCR disabled for this session: 2 calls took over 3000ms"
        result = ocr_evidence(lines, ["7"], "boot", True, True)
        self.assertFalse(result["effective"])
        self.assertIn("this session", result["log_line"])
        self.assertIsNone(ocr_evidence(lines, ["9"], "boot", True, True)["effective"])
        self.assertFalse(ocr_evidence(lines, ["7"], "boot", True, False)["effective"])

    def test_old_missing_metric_stays_missing(self):
        row = {"phase": "eval", "config": "C2", "trial": 1, "task": "Question", "variant": 0,
               "difficulty": "easy", "success": 1, "complete": True, "env_failure": None,
               "identity": {"protocol": {"experiment_sha256": "hash", "experiment": {"tasks": ["Question"], "variants": 1}}},
               "coati": {"steps": 2}}
        report = summarize([row])[0]
        self.assertEqual(report["refunded_steps"], {"mean": None, "observed": 0, "missing": 1})
        self.assertEqual(report["ocr_effective"]["missing"], 1)
        self.assertEqual(report["manual_empty"]["missing"], 1)
        row["manual_inventory"] = {"empty": True, "manual_count": 0, "file_count": 0}
        self.assertEqual(summarize([row])[0]["manual_empty"]["mean"], True)
        self.assertEqual(summarize([row])[0]["manual_count"]["mean"], 0)
        row["coati"]["refunded_steps"] = 0
        self.assertEqual(summarize([row])[0]["refunded_steps"]["mean"], 0)


if __name__ == "__main__": unittest.main()
