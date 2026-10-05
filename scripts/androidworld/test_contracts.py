"""Offline contracts against pinned AW interfaces; forbid subprocess/network IO."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

p=argparse.ArgumentParser();p.add_argument("--aw-root",required=True);args=p.parse_args()
sys.path.insert(0,args.aw_root)

def forbidden(*a,**k):
    raise AssertionError("External subprocess/network/device IO forbidden in offline contracts")

# Install guards before importing the real registry/runner; no fabricated AW modules.
subprocess.Popen=forbidden
socket.socket.connect=forbidden
socket.create_connection=forbidden
from android_world import registry, suite_utils, episode_runner
from android_world.env import interface, json_action
from android_world.task_evals import task_eval
from agent import CoatiAgent, EnvironmentFailure
from protocol import Results, condition, derived_seed, digest, identity, shard_tasks, ExecutionLedger, execution_order, evidenced_environment_failure
from runtime import Bridge, validate_connection
import harness as runner
from summarize import summarize


class Question(task_eval.TaskEval):
    template="Read the value"
    complexity=1.7
    app_names=()
    schema={}
    @classmethod
    def generate_random_params(cls): return {"value":1}
    def initialize_task(self,env):
        self.initialized=True;env.interaction_cache="";env.events.append("initialize")
    def is_successful(self,env):
        assert self.initialized
        env.events.append("score")
        return 1.0 if env.interaction_cache == " 42\n" else 0.0
    def tear_down(self,env):
        self.initialized=False;env.events.append("teardown")


class FakeEnv:
    def __init__(self):self.interaction_cache="stale";self.events=[]
    def reset(self,go_home=False):
        self.events.append("reset");self.interaction_cache=""
    # Use the real official ANSWER branch; suppress only its display IO.
    execute_action=interface.AsyncAndroidEnv.execute_action
    def display_message(self,*a,**k):self.events.append("answer")


class Clock:
    def __init__(self):self.t=0
    def now(self):return self.t
    def sleep(self,s):self.t+=s


class Contracts(unittest.TestCase):
    def setUp(self):
        self.settings=condition("C2")
        self.task=Question({"seed":derived_seed(30,"Question",0)})
        self.metadata={"Question":{"task_name":"Question","difficulty":"medium"}}
        self.experiment={"tasks":["Question"],"variants":1,"condition":{"config":"C2"}}
        self.protocol={"experiment":self.experiment,"experiment_sha256":digest(self.experiment)}
        self.spec=identity(self.task,self.metadata,self.settings,1,0,self.protocol)
        self.env=FakeEnv();self.clock=Clock();self.bridge=Mock();self.perturb=Mock()
        self.result={"run_id":self.spec["run_id"],"goal":self.task.goal,"max_steps":17,"status":"completed","answer":" 42\n","declared_done":True,"steps":3,"verify":True,"tree_withheld":False}
        self.bridge.result.return_value=self.result
        self.agent=CoatiAgent(self.env,self.bridge,self.spec,self.perturb,self.clock.now,self.clock.sleep)

    def execute(self):
        return episode_runner.run_episode(self.task.goal,self.agent,max_n_steps=suite_utils._allocate_step_budget(self.task.complexity),print_fn=lambda _:None)

    def test_official_budget_single_dispatch_answer(self):
        result=self.execute()
        self.assertTrue(result.done);self.bridge.run.assert_called_once_with(self.task.goal,self.spec["run_id"],17)
        self.assertEqual(self.env.interaction_cache," 42\n")
        self.assertEqual(self.env.events,["reset","answer"])
        with self.assertRaises(RuntimeError):self.agent.step(self.task.goal)
        self.bridge.stop.assert_called_once()

    def test_official_task_lifecycle_score_not_declared_done(self):
        self.result["declared_done"]=False
        raw=suite_utils._run_task(self.task,lambda _:self.execute(),self.env,False)
        self.assertEqual(raw["is_successful"],1)
        self.assertEqual(self.env.events,["initialize","reset","answer","score","teardown"])
        self.assertFalse(self.result["declared_done"])

    def test_wrong_budget_no_dispatch(self):
        self.agent.set_max_steps(200)
        with self.assertRaises(ValueError):self.agent.step(self.task.goal)
        self.bridge.run.assert_not_called()

    def test_stale_run_id_timeout_stops_without_answer(self):
        bench=Mock();bench.dev.sh.return_value=json.dumps({**self.result,"run_id":"old"})
        actual=Bridge(bench,{},"synthetic-not-a-real-credential")
        self.assertIsNone(actual.result(self.spec["run_id"],1))
        self.bridge.result.return_value=None
        self.spec["wall_limit_s"]=7
        self.execute()
        self.assertEqual(self.agent.agent_failure,"wall_timeout_or_no_matching_result")
        self.bridge.run.assert_called_once();self.bridge.stop.assert_called_once()
        self.assertEqual(self.env.interaction_cache,"")
        self.assertEqual(self.clock.t,7)

    def test_actual_bridge_run_passes_budget_and_run_id(self):
        bench=Mock();bench.dev.sh.return_value="1000.001 CoatiLoop: previous"
        bench.log_events_after.return_value=[]
        bridge=Bridge(bench,{},"synthetic-not-a-real-credential")
        bridge.run("raw goal", "unique_run", 17)
        bench.broadcast.assert_called_once_with("bot.coati.debug.RUN_TASK", "--es", "task", "raw goal", "--es", "run_id", "unique_run", "--ei", "max_steps", "17")
        bridge.safe_clear()
        self.assertEqual(bench.method_calls[-2:], [unittest.mock.call.stop_task_before_configuration(), unittest.mock.call.clear_task_state()])

    def test_configuration_mismatch_fails_closed(self):
        self.result["verify"]=False
        self.execute()
        self.assertIn("configuration differs",self.agent.env_failure)
        self.assertEqual(self.env.interaction_cache,"")

    def test_cold_restart_reconnects_same_native_identity_before_config(self):
        from runtime import restart_connection
        import runtime
        c={"adb_path":"/offline/adb","adb_server_port":5039,"console_port":5556,"device_adb_port":5557,"grpc_port":8556,"serial":"emulator-5556","avd_name":"fixture"}
        owner=Mock();old=owner.old;bridge=owner.bridge;new=owner.new
        def attach(connection):
            self.assertEqual(connection,c)
            owner.attach()
            return new
        with patch.object(runtime,"connect",side_effect=attach):
            self.assertIs(restart_connection(old,bridge,c,self.settings,None,"android-id","apk-hash"),new)
        self.assertEqual(owner.mock_calls,[unittest.mock.call.bridge.stop(),unittest.mock.call.old.close(),unittest.mock.call.bridge.dev.cold_restart_emulator("fixture"),unittest.mock.call.bridge.verify_identity("android-id","apk-hash",require_bindings=False),unittest.mock.call.attach(),unittest.mock.call.bridge.verify_identity("android-id","apk-hash"),unittest.mock.call.bridge.configure(self.settings,None),unittest.mock.call.bridge.check_bindings()])

    def test_readonly_restart_refused_before_shutdown(self):
        from runtime import restart_connection
        env,bridge=Mock(),Mock()
        c={"adb_path":"/offline/adb","adb_server_port":5039,"console_port":5556,"device_adb_port":5557,"grpc_port":8556,"serial":"emulator-5556","avd_name":"fixture","read_only":True}
        with self.assertRaises(ValueError):restart_connection(env,bridge,c,self.settings,None,"id","apk")
        bridge.stop.assert_not_called();env.close.assert_not_called()

    def test_native_config_reaches_loader_with_all_private_ports(self):
        from runtime import connect
        from android_env import loader
        c={"workspace":"/offline/workspace","adb_path":"/offline/adb","adb_server_port":5039,"console_port":5556,"device_adb_port":5557,"grpc_port":8556,"serial":"emulator-5556","avd_name":"fixture"}
        seen=[]
        def inspect(cfg):
            seen.append(cfg)
            raise EnvironmentFailure("stop before any connection")
        with patch.object(loader,"load",side_effect=inspect):
            with self.assertRaises(EnvironmentFailure):connect(c)
        cfg=seen[0].simulator
        self.assertEqual((cfg.adb_controller.adb_server_port,cfg.adb_controller.device_name),(5039,"emulator-5556"))
        self.assertEqual((cfg.emulator_launcher.emulator_console_port,cfg.emulator_launcher.adb_port,cfg.emulator_launcher.grpc_port),(5556,5557,8556))

    def test_matching_result_wrong_budget_environment_failure(self):
        self.result["max_steps"]=200;self.execute()
        self.assertIn("wrong goal/budget",self.agent.env_failure)
        self.assertEqual(self.env.interaction_cache,"")

    def test_safe_clear_failure_never_dispatches(self):
        self.bridge.safe_clear.side_effect=EnvironmentFailure("not inactive")
        self.execute();self.bridge.run.assert_not_called();self.bridge.stop.assert_called_once()
        self.assertIsNotNone(self.agent.env_failure)

    def test_failed_task_still_uses_official_score(self):
        self.result.update(status="failed",reason="budget",answer=None,declared_done=False)
        raw=suite_utils._run_task(self.task,lambda _:self.execute(),self.env,False)
        self.assertEqual(raw["is_successful"],0);self.assertEqual(self.agent.agent_failure,"budget")
        self.assertIsNone(self.agent.env_failure)

    def test_read_failure_is_not_stale_data(self):
        bench=Mock();actual=Bridge(bench,{},"synthetic-not-a-real-credential")
        for output in ("", "{broken", "[]"):
            bench.dev.sh.return_value=output
            with self.assertRaises(EnvironmentFailure):actual.result("id",1)

    def test_official_seed_variant_and_identity_collision(self):
        suite=suite_utils.create_suite({"Question":Question},2,seed=30)
        self.assertEqual(suite["Question"][1].params["seed"],derived_seed(30,"Question",1))
        v=identity(suite["Question"][1],self.metadata,self.settings,1,1,self.protocol)
        self.assertNotEqual(v["run_id"],self.spec["run_id"]);self.assertTrue(v["label"].endswith("_v1"))
        with self.assertRaises(ValueError):identity(self.task,{},self.settings,1,0,self.protocol)
        with self.assertRaises(ValueError):identity(self.task,self.metadata,condition("C3","learn"),1,0,self.protocol)

    def test_actual_registry_metadata_and_fixed_candidates(self):
        classes,meta,full,subset=runner.registry_and_plan(args.aw_root,["all"],3,0)
        self.assertEqual(len(classes),116);self.assertEqual(set(classes),set(meta))
        candidates=json.loads(Path(__file__).with_name("smoke_tasks.json").read_text())["tasks"]
        self.assertEqual(len(candidates),10)
        self.assertEqual([sum(meta[n]["difficulty"]==d for n in candidates) for d in ("easy","medium","hard")],[4,3,3])
        for name in candidates:self.assertTrue(issubclass(classes[name],task_eval.TaskEval))
        shards=[shard_tasks(full,3,i) for i in range(3)]
        self.assertEqual([len(s) for s in shards],[39,39,38]);self.assertEqual(len(set(sum(shards,[]))),116)

    def test_resume_requires_matching_complete_artifacts(self):
        with tempfile.TemporaryDirectory() as d:
            store=Results(d,self.settings);store.reserve(self.spec)
            for suffix in (".log",".jpg"):store.path(self.spec).with_suffix(suffix).write_bytes(b"offline fixture")
            store.save(self.spec,{"complete":True,"env_failure":None})
            self.assertTrue(store.exists_complete(self.spec))
            changed=copy.deepcopy(self.spec);changed["official_max_steps"]=18
            with self.assertRaises(RuntimeError):store.exists_complete(changed)
            store.path(self.spec).with_suffix(".log").write_bytes(b"changed")
            with self.assertRaises(RuntimeError):store.exists_complete(self.spec)

    def test_inprogress_is_not_replayed(self):
        with tempfile.TemporaryDirectory() as d:
            store=Results(d,self.settings);store.reserve(self.spec)
            with self.assertRaises(RuntimeError):store.exists_complete(self.spec)

    def test_official_score_exception_preserved_not_zero(self):
        self.task.is_successful=Mock(side_effect=RuntimeError("scoring fixture failure"))
        self.bridge.json_safe=Bridge(Mock(),{},"synthetic-not-a-real-credential").json_safe
        def capture(base):
            for ext in (".log",".jpg"):base.with_suffix(ext).write_bytes(b"fixture")
        self.bridge.capture.side_effect=capture
        with tempfile.TemporaryDirectory() as d:
            store=Results(d,self.settings)
            with self.assertRaises(RuntimeError):runner.run_episode_record(self.task,self.agent,self.spec,store,self.bridge,self.settings)
            record=json.loads(store.path(self.spec).with_suffix(".json").read_text())
            self.assertIsNone(record["success"]);self.assertIn("official_score",record["env_failure"])
            self.assertFalse(record["complete"]);self.assertFalse(self.task.initialized)

    def test_configuration_and_manual_freeze_isolation(self):
        self.assertEqual((condition("C1").mode,condition("C1").manual,condition("C1").verify),("baseline","OFF",False))
        self.assertEqual((condition("C2").manual,condition("C2").verify),("OFF",True))
        self.assertEqual((condition("C3","learn").seed,condition("C3","learn").manual),(31,"LEARNING"))
        self.assertEqual((condition("C4").seed,condition("C4").manual,condition("C4").ocr),(30,"READ_ONLY",True))
        with self.assertRaises(ValueError):runner.frozen_manuals(None,condition("C3"),{})
        with self.assertRaises(ValueError):condition("C1","learn")
        experiment={"sources":{},"models":{},"tasks":["Question"],"variants":1,"perturbation":""}
        row={"config":"C3","phase":"learn","seed":31,"complete":True,"env_failure":None,"task":"Question","variant":0}
        evidence=json.dumps(row).encode()
        value={"learning_record_labels":["Question_t1.json"],"learning_episode_files_sha256":{"Question_t1.json":hashlib.sha256(evidence).hexdigest()},"learning_records_sha256":digest([row]),"config":"C3","training_seed":31,"training_complete":True,"files":{"manual.md":"hash"},"files_sha256":digest({"manual.md":"hash"}),"experiment":experiment}
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/"freeze.json";f.write_text(json.dumps(value))
            (Path(d)/"Question_t1.json").write_bytes(evidence)
            self.assertEqual(runner.frozen_manuals(f,condition("C3"),experiment),value)
            with self.assertRaises(ValueError):runner.frozen_manuals(f,condition("C4"),experiment)
            (Path(d)/"Question_t1.json").write_text("changed evidence")
            with self.assertRaises(ValueError):runner.frozen_manuals(f,condition("C3"),experiment)

    def test_native_connection_fails_closed_for_shared_or_parallel(self):
        c={"adb_path":"/fixture/adb","adb_server_port":5039,"console_port":5556,"device_adb_port":5557,"grpc_port":8556,"serial":"emulator-5556","avd_name":"fixture"}
        self.assertEqual(validate_connection(c,1)["adb_server_port"],5039)
        with self.assertRaises(ValueError):validate_connection(c,3)
        with self.assertRaises(ValueError):validate_connection({**c,"adb_server_port":5037},1)
        with self.assertRaises(ValueError):validate_connection({**c,"grpc_port":5557},1)

    def rows(self):
        rows=[]
        for trial,score in ((1,1),(2,0),(3,1)):
            spec=copy.deepcopy(self.spec);spec["trial"]=trial
            rows.append({"config":"C2","phase":"eval","trial":trial,"task":"Question","variant":0,"difficulty":"medium","identity":spec,"complete":True,"env_failure":None,"success":score,"wall_s":10,"coati":{"declared_done":True,"steps":2,"usage":{"critical":{"calls":4,"total_tokens":100},"background":{"total_tokens":25}}}})
        return rows

    def test_persistent_actual_counter_excludes_skips_and_spans_configs(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"ledger.json"
            ledger=ExecutionLedger(p,{"serial":"emulator-5556"},"id")
            for i in range(8):
                spec={**self.spec,"run_id":f"attempt{i}"}
                ledger.start(spec);ledger.finish(spec,True)
            ledger.skip(self.spec)
            resumed=ExecutionLedger(p,{"serial":"emulator-5556"},"id")
            self.assertEqual(resumed.state["attempts_since_restart"],8)
            for config in ("C1","C3"):
                spec={**self.spec,"config":config,"run_id":config}
                resumed.start(spec);resumed.finish(spec,True)
            self.assertTrue(resumed.needs_restart)
            with self.assertRaises(RuntimeError):resumed.start(self.spec)
            resumed.restart_verified()
            self.assertEqual(ExecutionLedger(p,{"serial":"emulator-5556"},"id").state["attempts_since_restart"],0)

    def test_interrupted_counter_and_wrong_serial_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"ledger.json";ledger=ExecutionLedger(p,{"serial":"emulator-5556"},"id")
            ledger.start(self.spec)
            with self.assertRaises(RuntimeError):ExecutionLedger(p,{"serial":"emulator-5556"},"id")
            with self.assertRaises(ValueError):ExecutionLedger(p,{"serial":"emulator-5558"},"id")

    def test_fixed_two_task_schedule_does_not_change_full_protocol(self):
        manifest=json.loads(Path(__file__).with_name("smoke_tasks.json").read_text())
        order=execution_order(sorted(manifest["tasks"]),manifest["execution_order"])
        self.assertEqual(order[:2],["SystemBrightnessMin","NotesTodoItemCount"])
        self.assertEqual(len(order),10)
        with self.assertRaises(ValueError):execution_order(manifest["tasks"],order[:2])
        protocol={"tasks":sorted(manifest["tasks"]),"execution_order":order}
        unchanged=digest(protocol)
        for dispatch_cap in (2,None):
            schedule=order[:dispatch_cap] if dispatch_cap else order
            self.assertEqual(digest(protocol),unchanged)
        self.assertEqual(order[2:],sorted(set(manifest["tasks"])-set(order[:2])))

    def test_usage_summary_does_not_add_outside_task(self):
        rows=self.rows()[:1]
        rows[0]["coati"]["usage"]={"critical":{"total_tokens":100},"background":{"total_tokens":25},"outside_task":{"total_tokens":999}}
        self.assertEqual(summarize(rows)[0]["total_tokens"]["mean"],125)

    def test_typed_TLS_failure_has_matching_episode_evidence(self):
        result={"status":"failed","reason":"Unacceptable certificate: CertificateNotYetValidException"}
        log="CoatiLoop: Starting runId=offline_run, maxSteps=10\nCoatiModel: SSLHandshakeException: Unacceptable certificate"
        self.assertEqual(evidenced_environment_failure(result,log,"offline_run"),"TLS_certificate_validation_failure")
        self.assertIsNone(evidenced_environment_failure(result,log,"another_run"))
        for reason in ("Budget exceeded","unknown model error"):
            self.assertIsNone(evidenced_environment_failure({"status":"failed","reason":reason},log,"offline_run"))

    def test_TLS_canonical_env_failure_preserves_raw_score_and_stops_next(self):
        self.result.update(status="failed",reason="Unacceptable certificate: CN=GTS Root R4",answer=None)
        self.bridge.json_safe=Bridge(Mock(),{},"synthetic-not-a-real-credential").json_safe
        def capture(base):
            base.with_suffix(".log").write_text("CoatiLoop: Starting agent loop runId="+self.spec["run_id"]+", maxSteps=17\nE CoatiModel: SSLHandshakeException: Unacceptable certificate: CN=GTS Root R4\n")
            base.with_suffix(".jpg").write_bytes(b"offline")
        self.bridge.capture.side_effect=capture
        next_initialized=Mock()
        with tempfile.TemporaryDirectory() as d:
            store=Results(d,self.settings)
            with self.assertRaises(RuntimeError):
                runner.run_episode_record(self.task,self.agent,self.spec,store,self.bridge,self.settings)
                next_initialized()
            record=json.loads(store.path(self.spec).with_suffix(".json").read_text())
            self.assertIsNone(record["success"]);self.assertEqual(record["raw_official_score"],0)
            self.assertEqual(record["env_failure"],"TLS_certificate_validation_failure")
            self.assertFalse(record["complete"]);next_initialized.assert_not_called()

    def http_log(self, run_id, code):
        return ("1.0 7 7 I CoatiLoop: Starting agent loop for task: Read 401 "
                "(confirmationMode=TRUST, runId="+run_id+", maxSteps=17)\n"
                "0.5 7 7 E CoatiModel: API error: "+str(code)+", responseLength=100\n")

    def test_HTTP_environment_categories_require_current_run_and_gateway_evidence(self):
        categories={401:"HTTP_authentication_failure",402:"HTTP_balance_failure",
                    403:"HTTP_access_denied",429:"HTTP_rate_limit_or_quota_failure",
                    500:"HTTP_provider_service_failure",502:"HTTP_provider_service_failure",
                    503:"HTTP_provider_service_failure",504:"HTTP_provider_service_failure",
                    599:"HTTP_provider_service_failure"}
        for code, category in categories.items():
            for prefix in ("", "Model analysis failed: "):
                with self.subTest(code=code,prefix=prefix):
                    result={"run_id":"current", "status":"failed", "reason":prefix+"API error "+str(code)}
                    self.assertEqual(evidenced_environment_failure(result,self.http_log("current",code),"current"),category)

    def test_HTTP_classification_rejects_false_positives_and_preserves_agent_failures(self):
        result={"run_id":"current","status":"failed","reason":"Model analysis failed: API error 401"}
        log=self.http_log("current",401)
        bad_logs=[self.http_log("other",401),self.http_log("current",402),
                  log.replace("CoatiModel:","CoatiLoop:"),
                  log.replace("API error: 401, responseLength=100","Task says API error: 401, responseLength=100"),
                  self.http_log("old",401)+self.http_log("current",200),
                  log+self.http_log("other",200),
                  "CoatiDebugAgent: task=runId=current, API error: 401, responseLength=100"]
        for bad in bad_logs:
            with self.subTest(log=bad): self.assertIsNone(evidenced_environment_failure(result,bad,"current"))
        self.assertIsNone(evidenced_environment_failure({**result,"run_id":"other"},log,"current"))
        self.assertIsNone(evidenced_environment_failure({**result,"status":"completed"},log,"current"))
        for reason in ("Step limit reached", "Budget exceeded", "Model analysis failed: Malformed JSON 401",
                       "Task completion audit rejected API error 401", "Action ineffective: 401"):
            self.assertIsNone(evidenced_environment_failure({**result,"reason":reason},log,"current"))
        for code in (200,400,404,408,409):
            self.assertIsNone(evidenced_environment_failure({**result,"reason":"API error "+str(code)},self.http_log("current",code),"current"))

    def test_HTTP_environment_failure_preserves_raw_score_and_stops_next(self):
        for code in (401,402,403,429,502,503,504):
            with self.subTest(code=code):
                self.setUp()
                self.result.update(status="failed",reason="Model analysis failed: API error "+str(code),answer=None)
                self.bridge.json_safe=Bridge(Mock(),{},"synthetic-not-a-real-credential").json_safe
                def capture(base):
                    base.with_suffix(".log").write_text(self.http_log(self.spec["run_id"],code))
                    base.with_suffix(".jpg").write_bytes(b"offline")
                self.bridge.capture.side_effect=capture
                next_initialized=Mock()
                with tempfile.TemporaryDirectory() as d:
                    store=Results(d,self.settings)
                    with self.assertRaises(RuntimeError):
                        runner.run_episode_record(self.task,self.agent,self.spec,store,self.bridge,self.settings)
                        next_initialized()
                    record=json.loads(store.path(self.spec).with_suffix(".json").read_text())
                    self.assertIsNone(record["success"])
                    self.assertEqual(record["raw_official_score"],0)
                    self.assertTrue(record["env_failure"].startswith("HTTP_"))
                    self.assertFalse(record["complete"])
                    next_initialized.assert_not_called()
                    self.bridge.run.assert_called_once()
                    with self.assertRaises(RuntimeError):store.exists_complete(self.spec)

    def test_HTTP_log_does_not_turn_ordinary_agent_failures_into_environment_failures(self):
        for reason in ("Step limit reached", "Budget exceeded", "Model analysis failed: Malformed JSON 401"):
            with self.subTest(reason=reason):
                self.setUp()
                self.result.update(status="failed",reason=reason,answer=None)
                self.bridge.json_safe=Bridge(Mock(),{},"synthetic-not-a-real-credential").json_safe
                def capture(base):
                    base.with_suffix(".log").write_text(self.http_log(self.spec["run_id"],401))
                    base.with_suffix(".jpg").write_bytes(b"offline")
                self.bridge.capture.side_effect=capture
                with tempfile.TemporaryDirectory() as d:
                    store=Results(d,self.settings)
                    record=runner.run_episode_record(self.task,self.agent,self.spec,store,self.bridge,self.settings)
                    self.assertTrue(record["complete"])
                    self.assertIsNone(record["env_failure"])
                    self.assertEqual(record["agent_failure"],reason)
                    self.assertEqual(record["success"],0)

    def test_host_marker_accepts_order_despite_saved_clock_regression(self):
        import os,importlib
        root=Path(args.aw_root).parents[1]
        sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"device_bench"))
        with patch.dict(os.environ,{"RELAY_KEY":"offline-only","RELAY_ENDPOINT":"https://offline.invalid/api"}):
            host=importlib.import_module("run_app_bench")
        boot="12345678-1234-1234-1234-123456789abc"
        cursor={"boot_id":boot,"marker":"bench_unique"}
        log="1697384054.413 I CoatiDebugAgent: Debug task stopped=true\n1697384048.530 I CoatiBenchCursor: bench_unique\n1697384048.535 I CoatiDebugAgent: Debug task stopped=true"
        with patch.object(host.dev,"sh",return_value=boot):
            self.assertEqual(host.log_events_after(log,cursor),["1697384048.535 I CoatiDebugAgent: Debug task stopped=true"])
            for invalid in ("",log.replace("bench_unique","old_marker"),log+"\nI CoatiBenchCursor: bench_unique"):
                with self.assertRaises(RuntimeError):host.log_events_after(invalid,cursor)
        with patch.object(host.dev,"sh",return_value="changed-boot"):
            with self.assertRaises(RuntimeError):host.log_events_after(log,cursor)
        with patch.object(host.dev,"sh",return_value=boot):
            with self.assertRaises(RuntimeError):host.log_events_after(log,1697384054.413)

    def test_marker_stop_timeout_and_fresh_ready_contract(self):
        import os,importlib
        root=Path(args.aw_root).parents[1]
        sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"device_bench"))
        with patch.dict(os.environ,{"RELAY_KEY":"offline-only","RELAY_ENDPOINT":"https://offline.invalid/api"}):host=importlib.import_module("run_app_bench")
        boot="12345678-1234-1234-1234-123456789abc"
        buffer=["1697384054.413 I CoatiDebugAgent: Debug task stopped=true"]
        current={"status":"failed"}
        def device(*argv,**kwargs):
            if argv[:3]==("shell","cat","/proc/sys/kernel/random/boot_id"):return boot
            if argv[:2]==("shell","log"):
                buffer.append("1697384048.500 I CoatiBenchCursor: "+argv[-1]);return ""
            if argv[0]=="logcat":
                self.assertIn("main",argv);self.assertIn("CoatiBenchCursor:I",argv)
                return "\n".join(buffer)
            raise AssertionError("Unexpected IO")
        def stop(*args):buffer.append("1697384048.535 I CoatiDebugAgent: Debug task stopped=true")
        clock=Clock()
        with patch.object(host.dev,"sh",side_effect=device),patch.object(host,"broadcast",side_effect=stop),patch.object(host,"configuration_task_state",return_value=current),patch.object(host.time,"monotonic",side_effect=clock.now),patch.object(host.time,"sleep",side_effect=clock.sleep):
            host.stop_task_before_configuration()
            self.assertLess(clock.t,1)
            cursor=host.configuration_cursor()
            buffer.append("1697384040.0 I CoatiDebugAgent: Debug agent configured: jev=false, verify=true,")
            expected=f"Agent initialized: reasoning={host.PROVIDER}/{host.MODEL}, fast={host.PROVIDER}/{host.FAST_MODEL}, jev=off,"
            buffer.append("1697384040.1 I CoatiService: "+expected)
            with patch.object(host,"prepare_device") as ready:
                host.wait_for_configuration("classic",cursor);ready.assert_called_once()
            with patch.object(host,"broadcast",return_value=None),patch.object(host,"TASK_STOP_TIMEOUT_S",1):
                with self.assertRaises(RuntimeError):host.stop_task_before_configuration()

    def test_summary_exact_three_trials_false_done_and_usage(self):
        result=summarize(self.rows())[0]
        self.assertAlmostEqual(result["SR_mean"],2/3);self.assertEqual(result["pass3"],0)
        self.assertAlmostEqual(result["false_done"],1/3);self.assertEqual(result["false_done_declared_done_denominator"],3)
        self.assertEqual(result["total_tokens"]["mean"],125);self.assertEqual(result["critical_calls"]["mean"],4)
        self.assertEqual(result["difficulty"]["medium"],{"successful":2,"eligible":3})

    def test_summary_incomplete_env_failure_missing_usage_learning_excluded(self):
        rows=self.rows();rows[0]["coati"]["declared_done"]=False
        rows[1]["env_failure"]="offline environment fixture";rows[1]["success"]=None
        rows[2]["coati"]["usage"]={}
        result=summarize(rows)[0]
        self.assertIsNone(result["SR_mean"]);self.assertIsNone(result["pass3"])
        self.assertEqual(result["false_done_declared_done_denominator"],1)
        self.assertEqual(result["total_tokens"]["missing"],1)
        rows[0]["phase"]="learn";self.assertEqual(summarize(rows)[0]["episodes"],2)
        self.assertIsNone(summarize(self.rows()[:1])[0]["SR_std"])
        with self.assertRaises(ValueError):summarize(self.rows()+self.rows()[:1])


if __name__=="__main__":
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Contracts))
    print(json.dumps({"status":"PASS" if result.wasSuccessful() else "FAIL","tests":result.testsRun,"failures":len(result.failures),"errors":len(result.errors),"real_pinned_AW_interfaces":True,"subprocess_network_device_IO_forbidden":True}))
    raise SystemExit(not result.wasSuccessful())
