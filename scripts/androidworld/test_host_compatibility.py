"""Host port-release and private connection contracts; no device or emulator IO."""
import hashlib
import importlib
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import runtime
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "device_bench"))
import adbdev


class HostContracts(unittest.TestCase):
    def test_private_adb_endpoint_conflict_fails_before_command(self):
        with patch.dict(os.environ, {"ADB_SERVER_SOCKET": "tcp:127.0.0.1:5039", "ANDROID_ADB_SERVER_PORT": "5039", "ANDROID_SERIAL": "emulator-5556"}):
            argv = adbdev.adb_command("get-state")
            self.assertIn("5039", argv); self.assertIn("emulator-5556", argv)
        with patch.dict(os.environ, {"ADB_SERVER_SOCKET": "tcp:public.invalid:5039"}):
            with self.assertRaises(RuntimeError): adbdev.adb_command("get-state")
        with patch.dict(os.environ, {"ADB_SERVER_SOCKET": "tcp:127.0.0.1:5039", "ANDROID_ADB_SERVER_PORT": "5037"}):
            with self.assertRaises(RuntimeError): adbdev.adb_command("get-state")

    def test_untracked_helper_source_rejected_not_aw_generated_files(self):
        def git(path, *args):
            if args == ("rev-parse", "HEAD"):
                return "6e4faf91094e72b9f51276b9aa394cbdc057c9a7" if str(path) == "/offline/aw" else "same"
            if args[0] == "ls-files":
                self.assertEqual(args[-2:], ("scripts/androidworld", "scripts/device_bench"))
                return "scripts/androidworld/unreviewed.py"
            return ""
        with patch.object(runtime, "git", side_effect=git), patch.object(runtime.subprocess, "check_output", return_value=b""):
            with self.assertRaisesRegex(ValueError, "Untracked"):
                runtime.source_manifest("/offline/aw", "/offline/coati/scripts/device_bench", hashlib.sha256(b"").hexdigest())

    def test_occupied_port_timeout_prevents_any_emulator_spawn(self):
        env = {"ANDROID_SERIAL": "emulator-5556", "ADB_SERVER_SOCKET": "tcp:127.0.0.1:5039", "ANDROID_ADB_SERVER_PORT": "5039", "EMULATOR_GRPC_PORT": "8556", "EMULATOR": "/offline/emulator", "EMULATOR_EXTRA_ARGS": "[]"}
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {**env, "ANDROID_AVD_HOME": str(Path(tmp) / "avd")}), patch.object(adbdev.os.path, "isfile", return_value=True), patch.object(adbdev, "sh", return_value="fixture\nOK"), patch.object(adbdev.subprocess, "run", return_value=Mock(returncode=0, stdout=b"OK")), patch.object(adbdev, "_wait_for_emulator_ports", side_effect=RuntimeError("occupied")), patch.object(adbdev.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(RuntimeError, "occupied"): adbdev.cold_restart_emulator("fixture")
            spawn.assert_not_called()


if __name__ == "__main__": unittest.main()
