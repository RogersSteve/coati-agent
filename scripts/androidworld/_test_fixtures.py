"""Synthetic immutable resume evidence; never reads historical server results."""
import copy
import hashlib
import json
from protocol import derived_seed, digest


def resume_fixture(root):
    names = ["SystemBrightnessMin", "NotesTodoItemCount", "BrowserMultiply", "ContactsAddContact",
             "ExpenseAddMultipleFromGallery", "FilesMoveFile", "MarkorCreateNote", "MarkorCreateNoteAndSms",
             "NotesMeetingAttendeeCount", "RecipeAddSingleRecipe"]
    new_files = {n: "new" for n in ("protocol.py", "harness.py", "summarize.py", "test_resume_identity.py", "test_parameter_recording.py")}
    sources = {"host_HEAD": "fixture-host", "harness_files_sha256": new_files}
    experiment = {"sources": sources, "models": {"reasoning": "fixture-model", "endpoint": "http://127.0.0.1:35556/api"},
                  "tasks": sorted(names), "execution_order": names, "variants": 1,
                  "transport": {"route": "loopback_adb_reverse_host_https", "upstream": "https://openrouter.ai"}}
    new = {"schema": 3, "experiment": experiment, "experiment_sha256": digest(experiment),
           "connection": {"serial": "emulator-5556", "adb_server_port": 5039}, "apk_sha256": "a" * 64}
    olds = []
    for schema in (1, 2):
        old = copy.deepcopy(new); old["schema"] = schema
        old["experiment"]["sources"]["harness_files_sha256"] = {n: "old" + str(schema) for n in ("protocol.py", "harness.py", "summarize.py")}
        old["experiment_sha256"] = digest(old["experiment"]); olds.append(old)
    entries = []
    for i, name in enumerate(names[:4]):
        old = olds[i % 2]; seed = derived_seed(30, name, 0); label = name + "_t1"
        spec = {"schema": old["schema"], "label": label, "task": name, "trial": 1, "variant": 0,
                "config": "C2", "phase": "eval", "seed": 30, "derived_seed": seed, "params": {"seed": seed},
                "difficulty": "easy", "goal": "fixture goal", "official_max_steps": 10,
                "protocol": old, "protocol_sha256": digest(old), "run_id": name + "_C2_eval_t1_v0_" + digest(old)[:12]}
        base = root / label
        for suffix in (".log", ".jpg"): base.with_suffix(suffix).write_bytes(b"fixture bytes")
        hashes = {suffix: hashlib.sha256(base.with_suffix(suffix).read_bytes()).hexdigest() for suffix in (".log", ".jpg")}
        row = {k: spec[k] for k in ("task", "trial", "variant", "config", "phase", "seed", "difficulty")}
        row.update(identity=spec, complete=True, env_failure=None, success=0.0 if name == "BrowserMultiply" else 1.0,
                   coati={"run_id": spec["run_id"], "declared_done": name != "BrowserMultiply"}, artifact_sha256=hashes,
                   agent_failure="fixture step limit" if name == "BrowserMultiply" else None)
        base.with_suffix(".json").write_text(json.dumps(row))
        entries.append({"label": label, "task": name, "trial": 1, "variant": 0, "old_protocol_sha256": digest(old),
                        "files_sha256": {suffix: hashlib.sha256(base.with_suffix(suffix).read_bytes()).hexdigest() for suffix in (".json", ".log", ".jpg")}})
    manifest = {"schema": 2, "purpose": "C2-four-record-parameter-recording", "old_protocols": olds, "new_protocol": new, "records": entries}
    path = root / "compatibility.json"; path.write_text(json.dumps(manifest))
    return path
