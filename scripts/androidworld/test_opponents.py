"""Opponent worker contracts; synthetic workers and keys only, no device or network."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

from agent import EnvironmentFailure
from opponent_agent import Opponent, answer_required, compact_result, successful_model_calls, validate_result

CONNECTION = {"workspace": None, "adb_path": "/sdk/adb", "adb_server_port": 5043, "serial": "emulator-5564"}

FAKE_WORKER = """
import json, os, sys
request = json.loads(sys.stdin.read())
lease = json.load(open(request["lease"]["metadata_path"]))
assert lease["token"] == request["lease"]["token"] and not lease["closed"] and lease["serial"] == "emulator-5564"
assert os.environ["GEMINI_API_KEY"] == "fake-key" and "RELAY_KEY" not in os.environ
open(request["trace_path"], "w").write(json.dumps({"modelEvents": [{"status": 200}]}))
json.dump({"schema": 1, "agent": request["agent"], "run_id": request["run_id"], "status": "completed",
           "charged_steps": 2, "answer": "3" if request["question_answer_required"] else None,
           "native_result": {"history": ["tap", "done"]}}, open(request["result_path"], "w"))
"""


class Base:
    def is_successful(self, env):
        return env.interaction_cache == "x"


class Derived(Base):
    pass


class ActionTask:
    def is_successful(self, env):
        return 1.0


class OpponentTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "tmp").mkdir()
        self.connection = dict(CONNECTION, workspace=str(self.root))

    def test_answer_is_required_only_where_the_official_check_reads_it(self):
        self.assertTrue(answer_required(Base))
        self.assertTrue(answer_required(Derived))
        self.assertFalse(answer_required(ActionTask))

    def test_results_are_validated_against_the_request(self):
        request = {"schema": 1, "agent": "midscene", "run_id": "r", "max_steps": 10}
        good = {"schema": 1, "agent": "midscene", "run_id": "r", "status": "failed", "charged_steps": 10}
        self.assertEqual(validate_result(good, request), good)
        for bad in ({**good, "run_id": "other"}, {**good, "charged_steps": 11}, {**good, "status": "odd"},
                    {**good, "answer": 3}, {**good, "infrastructure_failure": True}):
            with self.assertRaises(EnvironmentFailure):
                validate_result(bad, request)

    def test_successful_model_calls_are_counted_in_both_trace_formats(self):
        midscene, finalrun = self.root / "m.trace.json", self.root / "f.trace.json"
        midscene.write_text(json.dumps({"modelEvents": [{"status": 400}, {"status": 200}], "nativeEvents": []}))
        finalrun.write_text("\n".join(json.dumps(e) for e in (
            {"event": "model_http", "status": 400}, {"event": "planner_action"}, {"event": "model_http", "status": 200})))
        self.assertEqual(successful_model_calls(midscene), 1)
        self.assertEqual(successful_model_calls(finalrun), 1)
        midscene.write_text(json.dumps({"modelEvents": [{"status": 400}]}))
        self.assertEqual(successful_model_calls(midscene), 0)

    def test_the_handoff_rebinds_only_the_observer(self):
        from opponent_agent import refresh_official_after_driver

        class Observer:
            closed = False

            def close(self):
                self.closed = True

        class Controller:
            def __init__(self):
                self.env = Observer()

            def refresh_env(self):
                self.env = Observer()

        class Env:
            def __init__(self):
                self.controller, self.interaction_cache = Controller(), "3"

        env = Env()
        old = env.controller.env
        refresh_official_after_driver(env)
        self.assertTrue(old.closed)
        self.assertIsNot(env.controller.env, old)
        env.controller.refresh_env = lambda: setattr(env, "interaction_cache", "")
        with self.assertRaises(EnvironmentFailure):
            refresh_official_after_driver(env)

    def test_native_history_moves_next_to_the_result(self):
        path = self.root / "x.worker.json"
        path.write_text(json.dumps({"status": "completed", "native_result": {"steps": [1, 2]}}))
        value = compact_result(path)
        self.assertNotIn("native_result", value)
        self.assertEqual(json.loads((self.root / "x.worker.native.json").read_text()), {"steps": [1, 2]})

    def test_request_uses_the_private_emulator_and_google_model(self):
        opponent = Opponent("finalrun", self.root, sys.executable, self.connection,
                            {"/a.apk": "1", "/b.apk": "2"})
        request = opponent.request("r", "goal", 12, 720, False, "/r.json", "/t.json", "tok")
        self.assertEqual(request["device"], {"serial": "emulator-5564", "adb_server_port": 5043, "adb_path": "/sdk/adb"})
        self.assertEqual((request["provider"], request["main_model"], request["base_url"]),
                         ("google", "gemini-3.8-flash", "https://generativelanguage.googleapis.com"))
        with self.assertRaises(ValueError):
            Opponent("finalrun", self.root, sys.executable, self.connection)

    def test_dispatch_runs_one_worker_with_a_live_lease_and_closes_it(self):
        worker = self.root / "bench/exp9/worker.mjs"
        worker.parent.mkdir(parents=True)
        worker.write_text(FAKE_WORKER)
        opponent = Opponent("midscene", self.root, sys.executable, self.connection)
        base = self.root / "Task_t1"
        result = opponent.dispatch("run-1", "How many?", 10, 60, True, base, "fake-key")
        self.assertEqual((result["status"], result["answer"]), ("completed", "3"))
        self.assertTrue(json.loads(opponent.lease_path.read_text())["closed"])
        self.assertTrue((self.root / "Task_t1.worker.native.json").exists())
        with self.assertRaises(EnvironmentFailure):
            opponent.dispatch("run-1", "How many?", 10, 60, True, base, "fake-key")


if __name__ == "__main__":
    unittest.main()
