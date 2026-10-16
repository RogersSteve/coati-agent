"""Protocol identity, configuration and collision-safe result storage."""
from dataclasses import asdict, dataclass
import hashlib
import json
import os
import datetime
from pathlib import Path
import re

SCHEMA = 3

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def safe_name(value):
    if not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("Unsafe output identifier")
    return value


@dataclass(frozen=True)
class Condition:
    config: str
    phase: str
    seed: int
    mode: str
    verify: bool
    jev: bool
    ocr: bool
    manual: str
    withhold_tree: bool = False


def condition(config, phase="eval", perturbation=""):
    if config not in ("C1", "C2", "C3", "C4") or phase not in ("learn", "eval"):
        raise ValueError("Unknown condition")
    if phase == "learn" and config not in ("C3", "C4"):
        raise ValueError("Only C3/C4 have a learning phase")
    return Condition(config, phase, 31 if phase == "learn" else 30,
                     "baseline" if config == "C1" else "jev" if config == "C4" else "classic",
                     config != "C1", config == "C4", True,
                     "LEARNING" if phase == "learn" else "READ_ONLY" if config in ("C3", "C4") else "OFF",
                     perturbation == "notree")


def derived_seed(seed, task, variant):
    return int(hashlib.sha256(f"{seed}_{task}_{variant}".encode()).hexdigest(), 16) % 2**32


def shard_tasks(tasks, count, index):
    if count < 1 or not 0 <= index < count or len(set(tasks)) != len(tasks):
        raise ValueError("Invalid shard or duplicate tasks")
    # Use the same sorted, fixed full list in every process, not a local subset.
    return sorted(tasks)[index::count]


def identity(task, metadata, settings, trial, variant, protocol):
    if trial not in (1, 2, 3) or variant < 0:
        raise ValueError("Invalid trial or variant")
    if task.name not in metadata or metadata[task.name]["difficulty"] not in ("easy", "medium", "hard"):
        raise ValueError("Missing or invalid official difficulty")
    from android_world import suite_utils
    budget = suite_utils._allocate_step_budget(task.complexity)
    if budget <= 0:
        raise ValueError("Official budget must be positive")
    if task.params.get("seed") != derived_seed(settings.seed, task.name, variant):
        raise ValueError("Official parameter seed does not match variant")
    label = f"{safe_name(task.name)}_t{trial}" + (f"_v{variant}" if variant else "")
    protocol = stable_protocol(protocol)
    run_id = f"{task.name}_{settings.config}_{settings.phase}_t{trial}_v{variant}_{digest(protocol)[:12]}"
    return {"schema": SCHEMA, "task": task.name, "difficulty": metadata[task.name]["difficulty"],
            "task_metadata": metadata[task.name], "seed": settings.seed, "derived_seed": task.params["seed"],
            "trial": trial, "variant": variant, "config": settings.config, "phase": settings.phase,
            "condition": asdict(settings), "goal": task.goal, "params": parameter_evidence(task.params),
            "complexity": task.complexity, "official_max_steps": budget,
            "wall_limit_s": min(budget * 60, 1800), "protocol": protocol,
            "protocol_sha256": digest(protocol), "run_id": run_id, "label": label}


class Results:
    def __init__(self, root, settings):
        name = settings.config + ("_learn" if settings.phase == "learn" else "")
        self.directory = Path(root) / name
        self.directory.mkdir(parents=True, exist_ok=True)

    def path(self, spec):
        return self.directory / safe_name(spec["label"])

    def exists_complete(self, spec, compatibility=None):
        base = self.path(spec)
        p = base.with_suffix(".json")
        if not p.exists():
            if any(base.with_suffix(s).exists() for s in (".log", ".jpg", ".inprogress.json")):
                raise RuntimeError("Interrupted artifacts exist; do not overwrite or replay")
            return False
        record = json.loads(p.read_text())
        if record.get("identity") != spec:
            if compatibility is None:
                raise RuntimeError("Existing episode uses a different protocol/seed/budget/variant")
            compatibility.validate(record, p, spec)
        if record.get("complete") is not True or record.get("env_failure") is not None:
            raise RuntimeError("Incomplete/environment-failed episode requires explicit review; no automatic replay")
        for suffix in (".log", ".jpg"):
            artifact = base.with_suffix(suffix)
            if not artifact.is_file() or hashlib.sha256(artifact.read_bytes()).hexdigest() != record.get("artifact_sha256", {}).get(suffix):
                raise RuntimeError("Missing or changed episode artifact")
        return True

    def reserve(self, spec):
        base = self.path(spec)
        if any(base.with_suffix(ext).exists() for ext in (".json", ".log", ".jpg", ".inprogress.json", ".writing.json")):
            raise FileExistsError("Existing episode evidence must not be overwritten")
        atomic_new_json(base.with_suffix(".inprogress.json"), spec)

    def stage_failure(self, spec, stage, error):
        from uuid import uuid4
        event = {"schema": 1, "kind": "pre_initialize_adapter_failure", "task": spec.get("task"),
                 "run_id": spec.get("run_id"), "stage": stage, "error_type": type(error).__name__,
                 "official_initialized": False, "RUN_TASK": 0, "UTC": datetime.datetime.now(datetime.timezone.utc).isoformat()}
        atomic_new_json(self.directory/("stage-event-"+uuid4().hex+".json"), event)

    def save(self, spec, record):
        base = self.path(spec)
        record["identity"] = spec
        record.update({k: spec[k] for k in ("task", "difficulty", "seed", "trial", "variant", "config", "phase")})
        record["artifact_sha256"] = {s: hashlib.sha256(base.with_suffix(s).read_bytes()).hexdigest()
                                     for s in (".log", ".jpg") if base.with_suffix(s).exists()}
        atomic_new_json(base.with_suffix(".json"), record)
        base.with_suffix(".inprogress.json").unlink()


class ExecutionLedger:
    """Persistent actual AW attempts per private serial; skips never increment."""
    def __init__(self, path, connection, android_id):
        self.path = Path(path)
        binding = {"connection": connection, "android_id": android_id}
        if self.path.exists():
            self.state = json.loads(self.path.read_text())
            if self.state.get("binding") != binding or self.state.get("schema") != 1:
                raise ValueError("Execution ledger binding/schema mismatch")
            if self.state.get("pending") is not None:
                raise RuntimeError("Interrupted AW attempt remains pending; review before any new task")
        else:
            self.state = {"schema":1,"binding":binding,"attempts_since_restart":0,"generation":0,"pending":None,"events":[]}
        if type(self.state.get("attempts_since_restart")) is not int or not 0 <= self.state["attempts_since_restart"] <= 10:
            raise ValueError("Invalid actual-attempt counter")

    def event(self, kind, **values):
        self.state["events"].append({"kind":kind,"UTC":datetime.datetime.now(datetime.timezone.utc).isoformat(),**values})
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".writing.json")
        with temporary.open("w") as f:
            json.dump(self.state,f,indent=2)
            f.flush();os.fsync(f.fileno())
        os.chmod(temporary,0o600)
        temporary.replace(self.path)

    @property
    def needs_restart(self):
        return self.state["attempts_since_restart"] >= 10

    def start(self, spec):
        if self.needs_restart or self.state["pending"] is not None:
            raise RuntimeError("Restart or pending-attempt review required before dispatch")
        self.state["attempts_since_restart"] += 1
        self.state["pending"] = spec["run_id"]
        self.event("actual_episode_attempt",run_id=spec["run_id"],config=spec["config"],phase=spec["phase"],trial=spec["trial"])

    def finish(self, spec, complete):
        if self.state["pending"] != spec["run_id"]:
            raise RuntimeError("Ledger/result attempt mismatch")
        self.state["pending"] = None
        self.event("attempt_finished",run_id=spec["run_id"],complete=complete)

    def skip(self, spec, compatibility=None):
        if compatibility is None:
            self.event("same_protocol_result_skipped",run_id=spec["run_id"])
        else:
            self.event("explicit_compatible_result_skipped",run_id=compatibility["old_run_id"],
                       target_run_id=spec["run_id"], compatibility_sha256=compatibility["manifest_sha256"])

    def restart_verified(self):
        if self.state["pending"] is not None:
            raise RuntimeError("Cannot reset a pending attempt counter")
        self.state["attempts_since_restart"] = 0
        self.state["generation"] += 1
        self.event("cold_restart_identity_connection_config_manuals_verified",generation=self.state["generation"])


def execution_order(full_tasks, requested):
    if len(requested) != len(set(requested)) or set(requested) != set(full_tasks):
        raise ValueError("Execution order must cover the unchanged full manifest exactly")
    return list(requested)


def evidenced_environment_failure(coati, episode_log, run_id):
    """Classify only explicit transport evidence within the matching episode."""
    if not coati or coati.get("status") != "failed" or not isinstance(coati.get("reason"), str):
        return None
    if not re.search(r"\brunId="+re.escape(run_id)+r"(?=[ ,)])", episode_log):
        return None
    # HTTP evidence is constrained to the actual gateway message and the last
    # product loop start, not arbitrary numbers in task text or stale requests.
    http_reason = re.fullmatch(r"(?:Model analysis failed: )?API error (\d{3})", coati["reason"])
    loop_start = re.compile(r"\bCoatiLoop\s*:\s*Starting agent loop for task: .* "
                            r"\(confirmationMode=[A-Z_]+, runId=([^,\s)]+), maxSteps=\d+\)\s*$")
    starts = [(index, match[1]) for index, line in enumerate(episode_log.splitlines())
              if (match := loop_start.search(line))]
    if (http_reason and coati.get("run_id") == run_id and starts
            and starts[-1][1] == run_id):
        code = int(http_reason[1])
        category = {401: "HTTP_authentication_failure", 402: "HTTP_balance_failure",
                    403: "HTTP_access_denied", 429: "HTTP_rate_limit_or_quota_failure"}.get(code)
        if 500 <= code <= 599:
            category = "HTTP_provider_service_failure"
        gateway_error = re.compile(r"\bCoatiModel\s*:\s*API error: " + str(code)
                                   + r", responseLength=\d+\s*$")
        if category and any(gateway_error.search(line)
                            for line in episode_log.splitlines()[starts[-1][0] + 1:]):
            return category
    patterns = {
        "TLS_certificate_validation_failure":r"SSLHandshakeException|SSLPeerUnverifiedException|CertificateNotYetValidException|CertificateExpiredException|CertPathValidatorException|Unacceptable certificate|Trust anchor for certification path not found",
        "network_connection_failure":r"UnknownHostException|ConnectException|SocketTimeoutException|Unable to resolve host|Failed to connect to|No route to host|Connection reset|Broken pipe",
    }
    for category, pattern in patterns.items():
        typed = (r"SSLHandshakeException|SSLPeerUnverifiedException|CertificateNotYetValidException|CertificateExpiredException|CertPathValidatorException" if category.startswith("TLS") else r"UnknownHostException|ConnectException|SocketTimeoutException|SocketException")
        model_exception = re.search(r"CoatiModel\s*:[^\n]*(?:"+typed+r")",episode_log,re.I)
        if re.search(pattern,coati["reason"],re.I) and model_exception:
            return category
    return None


SESSION_KEYS = {"route", "upstream", "endpoint", "host_port", "serial", "adb_server_port", "instance", "metrics_path", "extra_retries", "TLS"}


def stable_protocol(protocol):
    """Only ephemeral relay evidence is excluded; stable transport is checked."""
    import copy
    value = copy.deepcopy(protocol)
    session = value.pop("transport_session", None)
    if session is not None:
        if set(session) != SESSION_KEYS:
            raise ValueError("Unknown relay session metadata")
        connection, experiment = value["connection"], value["experiment"]
        expected = {"route": experiment["transport"]["route"], "upstream": experiment["transport"]["upstream"],
                    "endpoint": experiment["models"]["endpoint"], "serial": connection["serial"],
                    "adb_server_port": connection["adb_server_port"], "extra_retries": 0,
                    "TLS": "host_standard_CA_and_hostname"}
        if any(session[k] != v for k, v in expected.items()):
            raise ValueError("Relay stable metadata differs from protocol")
    if value.get("schema", 3) != 3:
        raise ValueError("Unknown stable protocol schema")
    value["schema"] = 3
    return value


class ResumeCompatibility:
    """A reviewed, exact four-record migration allowlist; never rewrite results."""
    KEYS = {"schema", "purpose", "old_protocols", "new_protocol", "records"}
    RECORD_KEYS = {"label", "task", "trial", "variant", "old_protocol_sha256", "files_sha256"}
    SOURCE_CHANGES = {"protocol.py", "harness.py", "summarize.py", "test_resume_identity.py", "test_parameter_recording.py"}

    def __init__(self, path, new_protocol=None):
        self.path = Path(path).resolve()
        raw = self.path.read_bytes()
        self.sha256 = hashlib.sha256(raw).hexdigest()
        self.value = json.loads(raw)
        v = self.value
        if set(v) != self.KEYS or v["schema"] != 2 or v["purpose"] != "C2-four-record-parameter-recording":
            raise ValueError("Unknown compatibility manifest schema/keys/purpose")
        new = v["new_protocol"]
        if new != stable_protocol(new) or (new_protocol is not None and new != stable_protocol(new_protocol)):
            raise ValueError("Compatibility target protocol mismatch")
        if new["experiment_sha256"] != digest(new["experiment"]):
            raise ValueError("Invalid target experiment digest")
        olds = v["old_protocols"]
        if not isinstance(olds, list) or len(olds) != 2 or len({digest(o) for o in olds}) != 2:
            raise ValueError("Exactly the two reviewed source protocols are required")
        self.old_protocols = {digest(o): o for o in olds}
        for old in olds:
            if old.get("schema", 1) not in (1, 2) or old["experiment_sha256"] != digest(old["experiment"]):
                raise ValueError("Invalid original schema/experiment digest")
            import copy
            normalized = copy.deepcopy(old)
            normalized.pop("schema", None)
            normalized = stable_protocol(normalized)
            old_sources = normalized["experiment"]["sources"]
            new_sources = new["experiment"]["sources"]
            if set(old_sources) != set(new_sources) or any(old_sources[k] != new_sources[k] for k in old_sources if k != "harness_files_sha256"):
                raise ValueError("Unapproved AW/host/baseline source change")
            oh, nh = old_sources["harness_files_sha256"], new_sources["harness_files_sha256"]
            if not set(oh) <= set(nh) or not set(nh)-set(oh) <= {"test_resume_identity.py", "test_parameter_recording.py"}:
                raise ValueError("Unexpected source file additions/removals")
            changed = {k for k in nh if oh.get(k) != nh[k]}
            if not changed <= self.SOURCE_CHANGES or not {"protocol.py", "harness.py", "summarize.py"} <= changed:
                raise ValueError("Unapproved protocol source mapping")
            normalized["experiment"]["sources"] = new_sources
            normalized["experiment_sha256"] = digest(normalized["experiment"])
            if normalized != new:
                raise ValueError("Stable experiment/connection/APK/transport changed")
        entries = v["records"]
        if not isinstance(entries, list) or len(entries) != 4:
            raise ValueError("Exactly the original four records must be authorized")
        if {x.get("task") for x in entries} != {"SystemBrightnessMin", "NotesTodoItemCount", "BrowserMultiply", "ContactsAddContact"}:
            raise ValueError("Unknown compatible episode")
        self.entries = {}
        for entry in entries:
            if set(entry) != self.RECORD_KEYS or entry["trial"] != 1 or entry["variant"] != 0 or entry["label"] != entry["task"]+"_t1":
                raise ValueError("Invalid compatible label/trial/variant")
            if entry["old_protocol_sha256"] not in self.old_protocols:
                raise ValueError("Unknown original protocol mapping")
            hashes = entry["files_sha256"]
            if set(hashes) != {".json", ".log", ".jpg"} or not all(isinstance(h, str) and re.fullmatch(r"[0-9a-f]{64}", h) for h in hashes.values()):
                raise ValueError("Invalid compatibility artifact inventory")
            self.entries[entry["label"]] = entry

    def validate(self, record, path, target_spec=None):
        old_spec = record["identity"]
        entry = self.entries.get(old_spec.get("label"))
        if entry is None or Path(path).name != entry["label"]+".json":
            raise RuntimeError("Record is outside explicit compatibility allowlist")
        base = Path(path).with_suffix("")
        for suffix, expected in entry["files_sha256"].items():
            file = base.with_suffix(suffix)
            if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
                raise RuntimeError("Compatibility record/artifact hash mismatch")
        if record != json.loads(Path(path).read_text()):
            raise RuntimeError("In-memory record differs from original evidence")
        old = self.old_protocols[entry["old_protocol_sha256"]]
        if old_spec.get("schema") != old.get("schema", 1) or old_spec.get("protocol") != old or old_spec.get("protocol_sha256") != digest(old):
            raise RuntimeError("Original protocol/schema/digest mismatch")
        old_run = f'{entry["task"]}_C2_eval_t1_v0_{digest(old)[:12]}'
        if old_spec.get("run_id") != old_run or (record.get("coati") or {}).get("run_id") != old_run:
            raise RuntimeError("Original run identity mismatch")
        if record.get("complete") is not True or record.get("env_failure") is not None or record.get("success") is None:
            raise RuntimeError("Incomplete/environment-failed record is not compatible")
        for key in ("task", "trial", "variant", "config", "phase", "seed", "difficulty"):
            if record.get(key) != old_spec.get(key):
                raise RuntimeError("Record identity metadata mismatch")
        if old_spec["task"] != entry["task"] or old_spec["config"] != "C2" or old_spec["phase"] != "eval" or old_spec["seed"] != 30:
            raise RuntimeError("Unexpected original condition")
        if old_spec.get("derived_seed") != derived_seed(30, entry["task"], 0) or old_spec.get("params", {}).get("seed") != old_spec["derived_seed"]:
            raise RuntimeError("Original parameter seed mismatch")
        if any(record.get("artifact_sha256", {}).get(s) != entry["files_sha256"][s] for s in (".log", ".jpg")):
            raise RuntimeError("Original artifact inventory mismatch")
        import copy
        expected_spec = copy.deepcopy(old_spec)
        expected_spec.update(schema=3, protocol=self.value["new_protocol"], protocol_sha256=digest(self.value["new_protocol"]),
                             run_id=f'{entry["task"]}_C2_eval_t1_v0_{digest(self.value["new_protocol"])[:12]}')
        if target_spec is not None and target_spec != expected_spec:
            raise RuntimeError("Target goal/params/budget/seed/protocol differs")
        return {"old_run_id": old_run, "manifest_sha256": self.sha256,
                "old_experiment_sha256": old["experiment_sha256"],
                "new_experiment_sha256": self.value["new_protocol"]["experiment_sha256"],
                "old_sources": old["experiment"]["sources"],
                "new_sources": self.value["new_protocol"]["experiment"]["sources"]}

    def view(self, record, path):
        """An explicitly attributed in-memory grouping view, not a new episode."""
        evidence = self.validate(record, path)
        import copy
        view = copy.deepcopy(record)
        view["compatibility_evidence"] = evidence
        view["identity"]["protocol"] = self.value["new_protocol"]
        # Original run_id/schema/hash remain evidence; this view is never saved.
        return view



def parameter_evidence(value):
    """Known AW row dataclasses only; never mutate official task parameters."""
    import math
    import dataclasses
    if value is None or type(value) in (str, bool, int):
        return value
    if type(value) is float:
        if not math.isfinite(value):
            raise TypeError("Nonfinite task parameter")
        return value
    if type(value) is dict:
        if not all(type(k) is str for k in value):
            raise TypeError("Task parameter keys must be strings")
        return {k: parameter_evidence(v) for k, v in value.items()}
    if type(value) is list:
        return [parameter_evidence(v) for v in value]
    if type(value) is tuple:
        # Standard JSON already represents tuples as arrays.
        return [parameter_evidence(v) for v in value]
    typename = type(value).__module__+"."+type(value).__qualname__
    # Every official frozen row type: Expense, Recipe, CalendarEvent (SimpleCalendar), Task,
    # SportsActivity, PlaylistInfo, OsmAndMapMarker, JoplinNormalizedNote.
    if type(value).__module__ == "android_world.task_evals.utils.sqlite_schema_utils":
        from android_world.task_evals.utils import sqlite_schema_utils
        cls = getattr(sqlite_schema_utils, type(value).__name__, None)
        if (type(value) is not cls or not dataclasses.is_dataclass(value) or not cls.__dataclass_params__.frozen
                or not issubclass(cls, sqlite_schema_utils.SQLiteRow)):
            raise TypeError("Unexpected official row type")
        return {"__aw_row_type__": typename,
                "fields": {field.name: parameter_evidence(getattr(value, field.name)) for field in dataclasses.fields(value)}}
    raise TypeError("Unsupported task parameter type: "+typename)


def atomic_new_json(path, value):
    """Pre-encode fully, then publish by exclusive hard link on the same disk."""
    import tempfile
    path = Path(path)
    encoded = json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False).encode("utf-8")
    fd, temporary = tempfile.mkstemp(prefix="."+path.stem+"-", suffix=".writing.json", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)  # Fails if the final evidence path already exists.
        directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        os.unlink(temporary)
