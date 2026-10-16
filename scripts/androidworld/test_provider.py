"""Provider configuration contracts; synthetic credentials only, no device or network."""
import json
import os
from pathlib import Path
import tempfile
import unittest

from harness import provider_models, validate_routing
from relay_connection import as_credentials
from runtime import load_credentials


class ProviderTests(unittest.TestCase):
    def credentials(self, value):
        directory = tempfile.mkdtemp()
        os.chmod(directory, 0o700)
        path = Path(directory)/"credentials.json"
        path.write_text(json.dumps(value))
        os.chmod(path, 0o600)
        return load_credentials(path)

    def test_credential_formats(self):
        self.assertEqual(self.credentials({"openrouter_api_key": " k "}),
                         {"provider": "openrouter", "llm_key": "k", "jev_key": "k"})
        self.assertEqual(self.credentials({"google_api_key": "g", "jev_api_key": "j"}),
                         {"provider": "google", "llm_key": "g", "jev_key": "j"})
        for bad in ({}, {"google_api_key": "g"}, {"google_api_key": "", "jev_api_key": "j"},
                    {"openrouter_api_key": "k", "jev_api_key": "j"}):
            with self.assertRaises(ValueError):
                self.credentials(bad)
        self.assertEqual(as_credentials("k"), {"provider": "openrouter", "llm_key": "k", "jev_key": "k"})

    def test_openrouter_identity_is_unchanged(self):
        # Exactly the dictionaries earlier experiments hashed.
        models, transport = provider_models("openrouter", {"device_port": 35556})
        self.assertEqual(models, {"endpoint": "http://127.0.0.1:35556/api", "reasoning": "google/gemini-3.8-flash",
                                  "fast": "google/gemini-3.8-flash", "jev": "jev-1.13"})
        self.assertEqual(transport, {"route": "loopback_adb_reverse_host_https", "upstream": "https://openrouter.ai",
                                     "extra_retries": 0, "wall_s_includes_transport": True})
        models, transport = provider_models("openrouter", None)
        self.assertEqual(models["endpoint"], "https://openrouter.ai/api")
        self.assertEqual(transport["route"], "direct_https")

    def test_google_uses_official_gemini_and_jev_behind_the_relay(self):
        models, transport = provider_models("google", {"device_port": 35556})
        self.assertEqual(models, {"endpoint": "http://127.0.0.1:35556/google", "jev_endpoint": "http://127.0.0.1:35556/jev",
                                  "app_provider": "gemini", "reasoning": "gemini-3.8-flash", "fast": "gemini-3.8-flash",
                                  "jev": "jev-1.13.0"})
        self.assertEqual(transport["upstream"], "https://generativelanguage.googleapis.com|https://api.typesafe.ai")
        with self.assertRaises(ValueError):
            provider_models("other", None)

    def test_routing_values(self):
        for value in (None, "FAST", "BALANCED", "ACCURATE", "0.7,1.01", "0,0.6"):
            self.assertEqual(validate_routing(value), value)
        for bad in ("fast", "0.7", "0.7,1.5", "a,b", "0.7,0.6,0.5"):
            with self.assertRaises(ValueError):
                validate_routing(bad)

    def test_official_row_parameters(self):
        from android_world.task_evals.utils import sqlite_schema_utils as rows
        from protocol import parameter_evidence
        event = rows.CalendarEvent(start_ts=1, end_ts=2, title="Visit")
        evidence = parameter_evidence({"events": [event]})
        self.assertEqual(evidence["events"][0]["__aw_row_type__"],
                         "android_world.task_evals.utils.sqlite_schema_utils.CalendarEvent")
        self.assertEqual(evidence["events"][0]["fields"]["title"], "Visit")
        with self.assertRaises(TypeError):
            parameter_evidence(object())

    def test_goal_filled_by_official_initialization_is_dispatched(self):
        from unittest.mock import Mock
        from agent import CoatiAgent

        class Dispatched(Exception):
            pass

        bridge = Mock()
        bridge.safe_clear.side_effect = Dispatched
        spec = {"goal": "What is on my calendar on {date}?", "official_max_steps": 10, "run_id": "r", "wall_limit_s": 600}
        agent = CoatiAgent(Mock(), bridge, spec, Mock())
        agent.set_max_steps(10)
        with self.assertRaises(ValueError):
            agent.step("Something else entirely")
        self.assertFalse(agent.started)
        agent.official_goal = "What is on my calendar on October 17?"
        try:
            agent.step("What is on my calendar on October 17?")
        except Exception:
            pass  # step() records bridge errors itself; only dispatch matters here
        self.assertTrue(agent.started)
        bridge.safe_clear.assert_called_once()


if __name__ == "__main__":
    unittest.main()
