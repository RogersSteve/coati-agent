"""Offline history guard checks; no device or model access."""
import json
from pathlib import Path
import tempfile
import sys
import unittest
from uuid import uuid4
from relay_connection import RelaySession
from harness import existing_learning_records
from protocol import condition


class LearningPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.settings = condition("C4", "learn")
        self.connection = {"serial": "emulator-5556", "adb_server_port": 5039}
        self.relay_config = {"python": sys.executable, "device_port": 35556, "metrics_directory": str(self.root / "metrics")}


    def write(self, name, row):
        path = self.root / name
        path.write_text(json.dumps(row))
        return path

    def test_fresh_production_metadata_with_independent_UUIDs_allows_clear(self):
        # Use the production constructor/metadata and the exact harness file-writing flow.
        relay = RelaySession(self.connection, "offline-test-only", self.relay_config, "offline-id")
        relay.port = 12345  # No process/device/network is started.
        session = relay.metadata()
        path = self.root / ("transport-session-" + uuid4().hex + ".json")
        with path.open("x") as file:
            json.dump(session, file, indent=2)
        self.assertNotEqual(path.stem.removeprefix("transport-session-"), session["instance"])
        self.assertEqual(existing_learning_records(
            self.root, self.settings, self.connection, self.relay_config), [])
        # A genuine session for another device must never grant a clear bypass.
        for field, value in [("serial", "emulator-5554"), ("adb_server_port", 5037)]:
            changed = dict(session, **{field: value})
            path.write_text(json.dumps(changed))
            with self.assertRaises(ValueError):
                existing_learning_records(self.root, self.settings, self.connection, self.relay_config)

    def test_completed_learning_episode_remains_existing(self):
        path = self.write("SystemBrightnessMin_t1.json", {
            "config": "C4", "phase": "learn", "seed": 31,
            "complete": True, "env_failure": None,
            "identity": {"label": "SystemBrightnessMin_t1"}})
        self.assertEqual(existing_learning_records(self.root, self.settings, self.connection, self.relay_config), [path])

    def test_partial_stage_unknown_and_wrong_configuration_require_review(self):
        for name, row in [
            ("SystemBrightnessMin_t1.inprogress.json", {"task": "SystemBrightnessMin"}),
            ("stage-event-" + "1" * 32 + ".json", {"kind": "pre_initialize_adapter_failure"}),
            ("manifest.json", {"complete": True}),
            ("SystemBrightnessMin_t1.json", {"complete": True, "config": "C3",
             "phase": "learn", "seed": 31, "identity": {"label": "SystemBrightnessMin_t1"}}),
        ]:
            with self.subTest(name=name):
                path = self.write(name, row)
                with self.assertRaises(ValueError):
                    existing_learning_records(self.root, self.settings, self.connection, self.relay_config)
                path.unlink()

    def test_corrupt_or_forged_session_requires_review(self):
        name = "transport-session-" + "2" * 32 + ".json"
        path = self.root / name
        for value in ["", "{", json.dumps({"instance": "3" * 32})]:
            path.write_text(value)
            with self.assertRaises(ValueError):
                existing_learning_records(self.root, self.settings, self.connection, self.relay_config)

    def test_orphan_episode_artifact_requires_review(self):
        (self.root / "SystemBrightnessMin_t1.log").write_text("original partial evidence")
        with self.assertRaises(ValueError):
            existing_learning_records(self.root, self.settings, self.connection, self.relay_config)


if __name__ == "__main__":
    unittest.main()
