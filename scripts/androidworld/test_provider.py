"""Provider configuration contracts; synthetic credentials only, no device or network."""
import json
import os
from pathlib import Path
import tempfile
import unittest

from harness import provider_models
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


if __name__ == "__main__":
    unittest.main()
