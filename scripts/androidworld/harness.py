"""Plan by default; execute only after explicit real-task authorization."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import sys
import time
import hashlib
import re

from protocol import Results, condition, digest, identity, shard_tasks, ExecutionLedger, execution_order, evidenced_environment_failure, ResumeCompatibility, stable_protocol


def metadata_from(path):
    values = json.loads(Path(path).read_text())
    mapping = {v["task_name"]: v for v in values}
    if len(mapping) != len(values):
        raise ValueError("Duplicate metadata entries")
    return mapping


def registry_and_plan(aw, names, count, index):
    sys.path.insert(0, str(Path(aw).resolve()))
    from android_world import registry
    classes = registry.TaskRegistry().get_registry("android_world")
    metadata = metadata_from(Path(aw)/"android_world/task_metadata.json")
    if set(classes) != set(metadata):
        raise ValueError("Registry and metadata do not exactly align")
    if names == ["all"]:
        names = sorted(classes)
    if not names or len(set(names)) != len(names) or set(names) - set(classes):
        raise ValueError("Unknown/duplicate task in fixed manifest")
    selected = shard_tasks(names, count, index)
    return classes, metadata, sorted(names), selected


def run_episode_record(task, agent, spec, store, bridge, settings, reserved=False):
    from android_world import constants, episode_runner, suite_utils
    stage = "initialize_task"
    raw = None
    env_failure = None
    artifacts_done = False
    answer_cache_before_score = None
    ocr_state = None
    start = time.monotonic()
    if not reserved:
        store.reserve(spec)
    def episode(current):
        nonlocal stage, artifacts_done, answer_cache_before_score, ocr_state
        stage = "episode"
        result = episode_runner.run_episode(current.goal, agent,
            max_n_steps=suite_utils._allocate_step_budget(current.complexity),
            start_on_home_screen=current.start_on_home_screen)
        if settings.phase == "learn" and agent.result and agent.result.get("status") == "completed":
            bridge.wait_manual_update()
        bridge.capture(store.path(spec))
        artifacts_done = True
        ocr_state = bridge.ocr_evidence(settings)
        answer_cache_before_score = agent.env.interaction_cache
        stage = "official_score"
        return result
    try:
        raw = suite_utils._run_task(task, episode, agent.env, demo_mode=False)
        exception = raw.get(constants.EpisodeConstants.EXCEPTION_INFO)
        if exception is not None:
            env_failure = stage + ": " + exception
        if agent.env_failure:
            env_failure = agent.env_failure
        log_path = store.path(spec).with_suffix(".log")
        transport_failure = evidenced_environment_failure(agent.result,log_path.read_text() if log_path.exists() else "",spec["run_id"])
        if transport_failure:
            env_failure = transport_failure
        if settings.manual == "READ_ONLY" and digest(bridge.manual_files()) != spec["protocol"]["experiment"]["manual_files_sha256"]:
            env_failure = "READ_ONLY_manuals_changed"
    except BaseException as error:
        env_failure = stage + ": " + type(error).__name__ + ": " + str(error)
    finally:
        if not artifacts_done:
            try:
                bridge.capture(store.path(spec))
            except Exception as error:
                env_failure = (env_failure or "") + "; artifact_capture:" + type(error).__name__
        # Official _run_task tears down on success, but skips it on exceptions.
        if task.initialized:
            try:
                task.tear_down(agent.env)
            except Exception as error:
                env_failure = (env_failure or "") + "; teardown:" + type(error).__name__
    score = raw.get(constants.EpisodeConstants.IS_SUCCESSFUL) if raw and not env_failure else None
    if score is not None and (not isinstance(score, (int, float)) or not 0 <= score <= 1):
        env_failure, score = "invalid_official_score", None
    record = bridge.json_safe({"success": score, "coati": agent.result,
        "wall_s": time.monotonic()-start, "coati_host_wall_s": agent.wall_s,
        "ocr": ocr_state,
        "manual_inventory": spec["protocol"]["experiment"].get("manual_inventory"),
        "host_monotonic_window": [start, time.monotonic()],
        "agent_failure": agent.agent_failure, "env_failure": env_failure,
        "official_episode": raw, "raw_official_score":raw.get(constants.EpisodeConstants.IS_SUCCESSFUL) if raw else None, "answer_cache_before_official_score":answer_cache_before_score,
        "official_teardown_finished":not task.initialized, "complete": env_failure is None and artifacts_done,
        "transport_session": bridge.relay.metadata() if type(getattr(bridge, "relay", None)).__name__ == "RelaySession" else None})
    # Official results contain a datetime; serialize it without changing scores.
    store.save(spec, record)
    if env_failure:
        raise RuntimeError("Episode environment failure saved; stop without replay")
    return record


def reserve_before_start(store, ledger, spec):
    """No official initialization or agent dispatch precedes a complete reserve."""
    try:
        store.reserve(spec)
        ledger.start(spec)
    except Exception as error:
        store.stage_failure(spec, "reserve_or_ledger_before_initialize", error)
        raise


def provider_models(provider, relay_config):
    """Models and transport for a provider. The openrouter dictionaries are unchanged, so existing
    experiment identities stay valid; google adds the app provider and a separate Jev endpoint."""
    from relay_connection import UPSTREAMS, relay_endpoints
    if provider == "openrouter":
        models = {"endpoint": "https://openrouter.ai/api", "reasoning": "google/gemini-3.8-flash",
                  "fast": "google/gemini-3.8-flash", "jev": "jev-1.13"}
    elif provider == "google":
        models = {"endpoint": "https://generativelanguage.googleapis.com", "jev_endpoint": "https://api.typesafe.ai",
                  "app_provider": "gemini", "reasoning": "gemini-3.8-flash", "fast": "gemini-3.8-flash",
                  "jev": "jev-1.13.0"}
    else:
        raise ValueError("Unknown provider")
    if relay_config:
        if type(relay_config.get("device_port")) is not int or not 1024 <= relay_config["device_port"] <= 65535:
            raise ValueError("Invalid relay device port")
        endpoint, jev_endpoint = relay_endpoints(provider, relay_config["device_port"])
        models["endpoint"] = endpoint
        if "jev_endpoint" in models:
            models["jev_endpoint"] = jev_endpoint
    transport = {"route": "loopback_adb_reverse_host_https" if relay_config else "direct_https",
                 "upstream": UPSTREAMS[provider], "extra_retries": 0, "wall_s_includes_transport": True}
    return models, transport


def existing_learning_records(directory, settings, connection, relay_config, models=None, transport=None):
    """Ignore only validated relay metadata; require review for unsafe history."""
    records = []
    for path in sorted(directory.glob("*.json")):
        try:
            row = json.loads(path.read_text())
        except (ValueError, OSError) as error:
            raise ValueError("Unreadable learning history requires review") from error
        session = re.fullmatch(r"transport-session-([0-9a-f]{32})\.json", path.name)
        if session:
            required = {"instance", "route", "upstream", "endpoint", "host_port",
                        "serial", "adb_server_port", "metrics_path", "extra_retries", "TLS"}
            if (not isinstance(row, dict) or set(row) != required or not relay_config
                    or not isinstance(row["instance"], str)
                    or not re.fullmatch(r"[0-9a-f]{32}", row["instance"])
                    or row["route"] != "loopback_adb_reverse_host_https"
                    or row["upstream"] != (transport or {}).get("upstream", "https://openrouter.ai")
                    or row["endpoint"] != (models or {}).get("endpoint", f'http://127.0.0.1:{relay_config["device_port"]}/api')
                    or row["serial"] != connection["serial"]
                    or type(row["adb_server_port"]) is not int
                    or row["adb_server_port"] != connection["adb_server_port"]
                    or type(row["host_port"]) is not int or not 1024 <= row["host_port"] <= 65535
                    or not isinstance(row["metrics_path"], str)
                    or Path(row["metrics_path"]).name != row["instance"] + ".jsonl"
                    or Path(row["metrics_path"]).parent != Path(relay_config["metrics_directory"])
                    or type(row["extra_retries"]) is not int or row["extra_retries"] != 0
                    or row["TLS"] != "host_standard_CA_and_hostname"):
                raise ValueError("Invalid relay metadata requires review")
            continue
        if (not isinstance(row, dict) or row.get("complete") is not True
                or row.get("env_failure") is not None
                or row.get("config") != settings.config or row.get("phase") != "learn"
                or row.get("seed") != 31 or not isinstance(row.get("identity"), dict)
                or row["identity"].get("label") != path.stem):
            raise ValueError("Incomplete or unknown learning history requires review")
        records.append(path)
    for artifact in list(directory.glob("*.log")) + list(directory.glob("*.jpg")):
        if artifact.with_suffix(".json") not in records:
            raise ValueError("Orphan learning artifacts require review")
    return records


def inventory_summary(files):
    """Count canonical and revision manuals; preserve all metadata hashes."""
    if not isinstance(files, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in files.items()):
        raise ValueError("Invalid manual inventory")
    count = sum(Path(n).name == "manual.md" and "/revisions/" not in n for n in files)
    return {"empty": count == 0, "manual_count": count, "file_count": len(files)}


def validate_frozen_inventory(value):
    summary = inventory_summary(value.get("files"))
    if value.get("schema") == 2:
        if any(type(value.get(k)) is not type(v) or value[k] != v for k, v in summary.items()):
            raise ValueError("Explicit frozen inventory summary differs")
        if value.get("inventory") != summary:
            raise ValueError("Frozen inventory evidence differs")
    elif summary["empty"] or value.get("schema") not in (None, 1):
        raise ValueError("An empty freeze requires explicit schema2 evidence")
    return summary


def frozen_manuals(path, settings, experiment, expected_apk=None):
    if settings.manual != "READ_ONLY":
        if path:
            raise ValueError("A manual freeze cannot be attached to OFF/LEARNING")
        return None
    if not path:
        raise ValueError("C3/C4 evaluation requires a seed31 trained manual freeze")
    value = json.loads(Path(path).read_text())
    if value.get("config") != settings.config or value.get("training_seed") != 31 or value.get("training_complete") is not True:
        raise ValueError("Manual freeze is not valid training for this condition")
    validate_frozen_inventory(value)
    if value.get("schema") == 2:
        summary = value.get("learning_summary", {})
        if summary.get("source_versions") != value["experiment"].get("sources"):
            raise ValueError("Learning source-version summary differs")
        if expected_apk is not None and summary.get("apk_sha256") != expected_apk:
            raise ValueError("Training/evaluation APK differs")
    for key in ("sources", "models", "tasks", "variants", "perturbation", "execution_order", "transport"):
        if value["experiment"].get(key) != experiment.get(key):
            raise ValueError("Training/evaluation manual protocol differs")
    if digest(value["files"]) != value.get("files_sha256"):
        raise ValueError("Invalid frozen manual inventory")
    labels = value.get("learning_record_labels", [])
    if not labels or len(labels) != len(set(labels)):
        raise ValueError("Manual freeze lacks unique learning record references")
    rows = []
    for label in labels:
        if Path(label).name != label or not label.endswith(".json"):
            raise ValueError("Invalid learning record reference")
        raw = (Path(path).parent/label).read_bytes()
        if hashlib.sha256(raw).hexdigest() != value.get("learning_episode_files_sha256", {}).get(label):
            raise ValueError("Learning evidence hash mismatch")
        row = json.loads(raw)
        if row.get("config") != settings.config or row.get("phase") != "learn" or row.get("seed") != 31 or row.get("complete") is not True or row.get("env_failure") is not None:
            raise ValueError("Learning record is not completed seed31 training")
        rows.append(row)
    expected = {(n,v) for n in experiment["tasks"] for v in range(experiment["variants"])}
    if {(r["task"],r["variant"]) for r in rows} != expected or len(rows) != len(expected) or digest(rows) != value.get("learning_records_sha256"):
        raise ValueError("Learning coverage or result digest mismatch")
    if value.get("schema") == 2:
        summary = value["learning_summary"]
        if summary.get("episodes") != len(rows) or summary.get("official_score_sum") != sum(r["success"] for r in rows):
            raise ValueError("Learning result summary differs from referenced evidence")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aw-root", required=True)
    parser.add_argument("--device-bench", default=str(Path(__file__).resolve().parents[1]/"device_bench"))
    parser.add_argument("--host-diff-sha256", required=True)
    parser.add_argument("--connection", required=True)
    parser.add_argument("--tasks", default=str(Path(__file__).with_name("smoke_tasks.json")))
    parser.add_argument("--config", choices=["C1", "C2", "C3", "C4"], required=True)
    parser.add_argument("--phase", choices=["learn", "eval"], default="eval")
    parser.add_argument("--trial", type=int, choices=[1, 2, 3], default=1)
    parser.add_argument("--variants", type=int, default=1)
    parser.add_argument("--shards", type=int, default=1)
    parser.add_argument("--shard-index", type=int, default=0)
    parser.add_argument("--perturbation", default="")
    parser.add_argument("--output", required=True)
    parser.add_argument("--credentials")
    parser.add_argument("--provider", choices=["openrouter", "google"], default="openrouter",
                        help="openrouter: one OpenRouter key for models and Jev; google: official Gemini API and Jev")
    parser.add_argument("--relay-config", help="Explicit local transport configuration; absent preserves direct HTTPS")
    parser.add_argument("--manual-freeze")
    parser.add_argument("--resume-compatibility", help="Explicit reviewed old two-record compatibility manifest")
    parser.add_argument("--clear-learning", action="store_true")
    parser.add_argument("--allow-cold-restart", action="store_true")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--max-new-episodes", type=int, help="Dispatch cap only; does not change the full experiment identity")
    parser.add_argument("--android-id", required=True)
    parser.add_argument("--apk-sha256", required=True)
    args = parser.parse_args()
    # AW must be importable before the agent/runtime module is loaded.
    sys.path.insert(0, str(Path(args.aw_root).resolve()))
    from runtime import Bridge, connect, load_credentials, load_host, source_manifest, validate_connection, restart_connection
    from agent import CoatiAgent
    c = json.loads(Path(args.connection).read_text())
    settings = condition(args.config, args.phase, args.perturbation)
    task_manifest = json.loads(Path(args.tasks).read_text())
    classes, metadata, full, selected = registry_and_plan(args.aw_root, task_manifest["tasks"], args.shards, args.shard_index)
    order = execution_order(full, task_manifest.get("execution_order", sorted(full)))
    if args.max_new_episodes is not None and args.max_new_episodes <= 0:
        raise ValueError("Positive new-episode dispatch cap required")
    if args.phase == "learn" and args.trial != 1:
        raise ValueError("Learning uses one fixed seed31 curriculum, not three scoring trials")
    if args.variants < 1:
        raise ValueError("Positive variants required")
    sources = source_manifest(args.aw_root, args.device_bench, args.host_diff_sha256)
    relay_config = json.loads(Path(args.relay_config).read_text()) if args.relay_config else None
    models, transport = provider_models(args.provider, relay_config)
    experiment = {"sources": sources, "models": models, "tasks": full, "variants": args.variants,
                  "condition": asdict(settings), "perturbation": args.perturbation, "manual_files_sha256": None, "manual_inventory": None, "execution_order":order}
    experiment["transport"] = transport
    from android_world import suite_utils
    budgets = {n:{"complexity":classes[n].complexity,
                  "official_max_steps":suite_utils._allocate_step_budget(classes[n].complexity),
                  "wall_limit_s":min(suite_utils._allocate_step_budget(classes[n].complexity)*60,1800)}
               for n in full if isinstance(classes[n].complexity,(int,float))}
    plan = {"condition": asdict(settings), "tasks": selected, "full_tasks": full,
            "registry_count": len(classes), "episodes": len(selected)*args.variants,
            "fixed_execution_order": [n for n in order if n in selected],
            "max_new_episodes":args.max_new_episodes,
            "cap_changes_experiment_protocol":False,
            "official_budgets_from_class_source":budgets,
            "experiment_sha256_before_manual_freeze":digest(experiment),
            "actual_attempt_ledger":"per private serial, persistent across config/phase/invocations; skips excluded",
            "wall_per_episode_max_s": 1800, "additional_learning_settlement_max_s": 240 if args.phase == "learn" else 0,
            "cold_restart_every": 10, "execute": args.execute,
            "multi_instance_restart_dependency": "Blocked until read-only-aware host helper is reviewed"}
    print(json.dumps(plan, indent=2))
    if not args.execute:
        return
    connection = validate_connection(c, args.shards)
    if not args.credentials:
        raise ValueError("Authorized credential path required for execution")
    if min(plan["episodes"],args.max_new_episodes or plan["episodes"]) > 10 and not args.allow_cold_restart:
        raise ValueError("More than10 episodes requires explicit cold-restart permission")
    frozen = frozen_manuals(args.manual_freeze, settings, experiment, args.apk_sha256)
    if frozen:
        experiment["manual_files_sha256"] = frozen["files_sha256"]
        experiment["manual_inventory"] = validate_frozen_inventory(frozen)
    protocol = {"experiment": experiment, "experiment_sha256": digest(experiment), "connection": connection,
                "apk_sha256": args.apk_sha256, "android_id": args.android_id, "automatic_replays": False}
    protocol = stable_protocol(protocol)
    compatibility = ResumeCompatibility(args.resume_compatibility, protocol) if args.resume_compatibility else None
    store = Results(args.output, settings)
    import fcntl
    lock_path = Path(c["workspace"])/"tmp"/("androidworld-"+c["serial"]+".lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    lock = lock_path.open("a")
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        lock.close()
        raise ValueError("Another harness owns this device; do not configure it") from None
    if settings.phase == "learn" and (store.directory/"manual-freeze.json").exists():
        lock.close()
        raise ValueError("Learning is already frozen; do not reopen it as LEARNING")
    ledger = ExecutionLedger(Path(c["workspace"])/"tmp"/("androidworld-"+c["serial"]+"-ledger.json"),connection,args.android_id)
    if ledger.needs_restart and not args.allow_cold_restart:
        raise ValueError("Persistent actual-attempt ledger requires a reviewed cold restart before another episode")
    if compatibility is not None:
        for entry in compatibility.entries.values():
            path = store.directory/(entry["label"]+".json")
            compatibility.validate(json.loads(path.read_text()), path)
    credentials = load_credentials(args.credentials)
    if credentials["provider"] != args.provider:
        raise ValueError("Credential file does not match --provider")
    env = None
    relay = None
    try:
        if relay_config:
            from relay_connection import RelaySession
            relay = RelaySession(c, credentials, relay_config, args.android_id).start()
            session = relay.metadata()
            from uuid import uuid4
            with (store.directory/("transport-session-"+uuid4().hex+".json")).open("x") as file:
                json.dump(session, file, indent=2)
        bench = load_host(args.device_bench, c, credentials, models, args.perturbation)
        bridge = Bridge(bench, c, credentials)
        bridge.relay = relay
        bridge.verify_identity(args.android_id, args.apk_sha256)
        if settings.phase == "learn":
            existing = existing_learning_records(store.directory, settings, c, relay_config, models, transport)
            if args.clear_learning and existing:
                raise ValueError("Cannot clear manuals while resuming a learning run")
            if not existing and not args.clear_learning:
                raise ValueError("Fresh seed31 learning requires explicit --clear-learning")
            if args.clear_learning:
                bridge.safe_clear()
                bridge.manual_command("CLEAR_MANUALS")
                if bridge.manual_files():
                    raise ValueError("Manual clear did not take effect")
        bridge.configure(settings, frozen)
        env = connect(c)
        bridge.check_bindings()
        from android_world import suite_utils
        # Restrict registry first: official create_suite otherwise generates all tasks.
        suite = suite_utils.create_suite({n:classes[n] for n in selected}, args.variants,
                                        seed=settings.seed, env=env)
        dispatched = 0
        records = []
        schedule = [(name,variant,task) for name in order if name in suite
                    for variant,task in enumerate(suite[name])]
        for name, variant, task in schedule:
            try:
                spec = identity(task, metadata, settings, args.trial, variant, protocol)
            except Exception as error:
                store.stage_failure({"task": name}, "identity_parameter_encoding", error)
                raise
            if store.exists_complete(spec, compatibility):
                old_record = json.loads(store.path(spec).with_suffix(".json").read_text())
                records.append(old_record)
                evidence = compatibility.validate(old_record, store.path(spec).with_suffix(".json"), spec) if old_record["identity"] != spec else None
                ledger.skip(spec, evidence)
                continue
            if args.max_new_episodes is not None and dispatched >= args.max_new_episodes:
                ledger.event("dispatch_cap_reached",config=settings.config,phase=settings.phase,new_episodes=dispatched,full_tasks=full)
                break
            if ledger.needs_restart:
                if not args.allow_cold_restart:
                    raise ValueError("Persistent actual-attempt counter requires cold restart before next episode")
                previous = env
                env = None
                env = restart_connection(previous, bridge, c, settings, frozen, args.android_id, args.apk_sha256)
                ledger.restart_verified()
            bridge.check_bindings()
            agent = CoatiAgent(env, bridge, spec, bench.PERTURB)
            reserve_before_start(store, ledger, spec)
            try:
                record = run_episode_record(task, agent, spec, store, bridge, settings, reserved=True)
            except BaseException:
                p = store.path(spec).with_suffix(".json")
                if p.exists():
                    ledger.finish(spec,False)
                raise
            records.append(record)
            ledger.finish(spec,True)
            dispatched += 1
        if settings.phase == "learn" and len(records) != len(schedule):
            ledger.event("partial_learning_not_frozen",covered=len(records),expected=len(schedule))
            return
        if settings.phase == "learn":
            # Switch off learning before freezing; verify inventory remains stable.
            bridge.manual_command("SET_MANUAL_MODE", "--es", "mode", "READ_ONLY")
            first = bridge.manual_files()
            time.sleep(20)
            if first != bridge.manual_files() or any(v.get("env_failure") for v in records):
                raise RuntimeError("Manual inventory not settled; no freeze produced")
            inventory = inventory_summary(first)
            value = {"schema": 2, **inventory, "inventory": inventory,
                     "learning_summary": {"episodes": len(records),
                         "official_score_sum": sum(v["success"] for v in records),
                         "source_versions": experiment["sources"],
                         "apk_sha256": args.apk_sha256},
                     "config": settings.config, "training_seed":31, "training_complete":True,
                     "experiment":experiment, "files":first, "files_sha256":digest(first),
                     "learning_records_sha256":digest(records),
                     "learning_record_labels":[v["identity"]["label"]+".json" for v in records],
                     "learning_episode_files_sha256":{v["identity"]["label"]+".json":hashlib.sha256((store.directory/(v["identity"]["label"]+".json")).read_bytes()).hexdigest() for v in records},
                     "settlement":"per-task update log +20s stable inventory; checked again before/after evaluation"}
            with (store.directory/"manual-freeze.json").open("x") as f:
                json.dump(value, f, indent=2)
    finally:
        try:
            if env is not None:
                env.close()
        finally:
            if relay is not None:
                relay.close()
        os_keys = ("RELAY_KEY", "JEV_KEY")
        import os
        for name in os_keys:
            os.environ.pop(name, None)
        lock.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never emit raw command output/tracebacks containing credential arguments.
        print("STOP: " + type(error).__name__ + "; inspect saved episode evidence or nonsecret preflight constraints", file=sys.stderr)
        raise SystemExit(1)
