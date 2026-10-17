"""AndroidWorld opponents (FinalRun, Midscene) as official AW agents in the Coati harness.

An episode dispatches one native worker process with the official goal and step budget, on the same
emulator setup, task instances, cold-restart policy and official scorer as Coati. The workers live in
separate opponent checkouts (bench/androidworld-google) and call the same Gemini model through
Google's API. Native stdout/stderr are discarded so a key can never reach a log; workers write
sanitized result and trace files that are kept with the episode.
"""
import hashlib
import inspect
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import time

from android_world.agents import base_agent
from android_world.env import json_action

from agent import EnvironmentFailure

GOOGLE_MODEL = "gemini-3.8-flash"
WORKERS = {
    # Midscene speaks OpenAI chat; Google serves the same model on its OpenAI-compatible endpoint.
    "midscene": {"worker": "bench/exp9/worker.mjs",
                 "base_url": "https://generativelanguage.googleapis.com/v1beta/openai"},
    # FinalRun uses its native Google client.
    "finalrun": {"worker": "bench/androidworld-exp9/worker.mjs",
                 "base_url": "https://generativelanguage.googleapis.com"},
}
STATUSES = {"completed", "failed", "cancelled", "budget_exhausted", "wall_timeout"}
FINALRUN_PACKAGES = ("app.finalrun.android", "app.finalrun.android.test")
FINALRUN_PORTS = range(8570, 8600)
GRACE_S = 5
MAX_RESULT_BYTES = 2_000_000


def answer_required(task_class):
    """Whether the official success check reads the agent's answer (env.interaction_cache)."""
    for base in task_class.__mro__:
        method = base.__dict__.get("is_successful")
        if method is not None:
            return "interaction_cache" in inspect.getsource(method)
    return False


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_result(value, request):
    if not isinstance(value, dict) or any(value.get(k) != request[k] for k in ("schema", "agent", "run_id")):
        raise EnvironmentFailure("worker_result_identity_mismatch")
    if value.get("answer") is not None and not isinstance(value["answer"], str):
        raise EnvironmentFailure("invalid_worker_answer_type")
    steps = value.get("charged_steps")
    if type(steps) is not int or not 0 <= steps <= request["max_steps"]:
        raise EnvironmentFailure("invalid_worker_budget_accounting")
    if value.get("status") not in STATUSES:
        raise EnvironmentFailure("worker_unknown_status")
    if value.get("infrastructure_failure"):
        # A terminal model HTTP/transport failure is not evidence of a task failure.
        raise EnvironmentFailure("worker_reported_infrastructure_failure: " + json.dumps(value.get("terminal_http_evidence")))
    return value


def compact_result(path):
    """Moves a large native history next to the result so the episode record stays bounded."""
    path = Path(path)
    value = json.loads(path.read_text())
    history = value.pop("native_result", None)
    if history is not None:
        data = json.dumps(history, ensure_ascii=False, allow_nan=False).encode()
        artifact = path.with_suffix(".native.json")
        with artifact.open("xb") as stream:
            stream.write(data)
        value["native_result_artifact"] = {"path": str(artifact), "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
    data = json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
    if len(data) > MAX_RESULT_BYTES:
        raise EnvironmentFailure("worker_result_oversized")
    path.write_bytes(data)
    return value


class Opponent:
    """One opponent checkout on one private emulator: worker dispatch, lease and FinalRun's driver."""

    def __init__(self, name, checkout, node, connection, driver_apks=None):
        if name not in WORKERS:
            raise ValueError("Unknown opponent")
        self.name, self.checkout, self.node, self.connection = name, Path(checkout), Path(node), connection
        self.worker = self.checkout / WORKERS[name]["worker"]
        self.adb = [connection["adb_path"], "-H", "127.0.0.1", "-P", str(connection["adb_server_port"]), "-s", connection["serial"]]
        self.lease_path = Path(connection["workspace"]) / "tmp" / ("opponent-lease-" + connection["serial"] + ".json")
        # FinalRun drives the device through its own instrumentation APK pair (path -> sha256).
        self.driver_apks = dict(driver_apks or {})
        if name == "finalrun" and len(self.driver_apks) != 2:
            raise ValueError("FinalRun needs its two driver APKs")
        self.port = self.process = None

    def identity(self):
        """Part of the experiment identity: what ran, on which model and endpoint."""
        head = subprocess.check_output(["git", "-C", str(self.checkout), "rev-parse", "HEAD"], text=True).strip()
        if subprocess.check_output(["git", "-C", str(self.checkout), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
            raise ValueError("Opponent checkout has uncommitted changes")
        return {"name": self.name, "checkout": str(self.checkout), "head": head, "worker_sha256": sha256(self.worker),
                "node_sha256": sha256(self.node), "provider": "google", "model": GOOGLE_MODEL,
                "base_url": WORKERS[self.name]["base_url"], "driver_apks_sha256": sorted(self.driver_apks.values())}

    def adb_run(self, *args, timeout=60, check=True):
        result = subprocess.run(self.adb + list(args), capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise EnvironmentFailure("adb_failed: " + " ".join(args[:3]))
        return result.stdout

    # FinalRun driver ------------------------------------------------------------------------------

    def installed_sha256(self, package):
        path = self.adb_run("shell", "pm", "path", package, check=False).decode().strip().removeprefix("package:")
        return hashlib.sha256(self.adb_run("exec-out", "cat", path)).hexdigest() if path else None

    def deploy(self):
        if self.name != "finalrun":
            return
        for path, digest in self.driver_apks.items():
            if sha256(path) != digest:
                raise EnvironmentFailure("driver_apk_changed")
        for package, (path, digest) in zip(FINALRUN_PACKAGES, self.driver_apks.items()):
            if self.installed_sha256(package) != digest:
                if b"Success" not in self.adb_run("install", "-r", path, timeout=180):
                    raise EnvironmentFailure("driver_install_failed")
                if self.installed_sha256(package) != digest:
                    raise EnvironmentFailure("installed_driver_changed")

    def driver_pids(self):
        return {p: self.adb_run("shell", "pidof", p, check=False).decode().strip() for p in FINALRUN_PACKAGES}

    def start_driver(self):
        if any(self.driver_pids().values()):
            raise EnvironmentFailure("unexpected_finalrun_instrumentation")
        forwards = self.adb_run("forward", "--list").decode()
        for port in FINALRUN_PORTS:
            if f"tcp:{port}" in forwards:
                continue
            with socket.socket() as probe:
                try:
                    probe.bind(("127.0.0.1", port))
                except OSError:
                    continue
            self.adb_run("forward", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
            self.port = port
            break
        else:
            raise EnvironmentFailure("no_free_finalrun_port")
        self.process = subprocess.Popen(self.adb + ["shell", "am", "instrument", "-w", "-e", "port", str(self.port),
                                        "-e", "app_perfect_device_id", self.connection["serial"], "-e", "class",
                                        "app.finalrun.android.FinalRunTest#testDriver",
                                        "app.finalrun.android.test/androidx.test.runner.AndroidJUnitRunner"],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        probe = Path(__file__).with_name("finalrun_driver_probe.mjs")
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise EnvironmentFailure("instrumentation_exited_before_ready")
            ready = subprocess.run([str(self.node), str(probe), str(self.checkout / WORKERS["finalrun"]["worker"]), str(self.port)],
                                   capture_output=True, timeout=30)
            if ready.returncode == 0 and json.loads(ready.stdout or b"{}").get("connected"):
                return
            time.sleep(1)
        raise EnvironmentFailure("driver_ready_timeout")

    def stop_driver(self):
        if self.process is not None:
            for package in reversed(FINALRUN_PACKAGES):
                self.adb_run("shell", "am", "force-stop", package, check=False)
            kill_group(self.process)
            self.process = None
        if self.port is not None:
            self.adb_run("forward", "--remove", f"tcp:{self.port}", check=False)
            self.port = None
        if any(self.driver_pids().values()):
            raise EnvironmentFailure("finalrun_instrumentation_still_alive")

    # Episode ---------------------------------------------------------------------------------------

    def request(self, run_id, goal, max_steps, wall_limit_s, question, result_path, trace_path, token):
        c = self.connection
        return {"schema": 1, "agent": self.name, "run_id": run_id, "goal": goal, "max_steps": max_steps,
                "wall_limit_s": wall_limit_s, "question_answer_required": question,
                "device": {"serial": c["serial"], "adb_server_port": c["adb_server_port"], "adb_path": c["adb_path"]},
                "provider": "google", "main_model": GOOGLE_MODEL, "base_url": WORKERS[self.name]["base_url"],
                "result_path": str(result_path), "trace_path": str(trace_path),
                "lease": {"metadata_path": str(self.lease_path), "token": token}}

    def write_lease(self, run_id, token, closed=False):
        c = self.connection
        value = {"schema": 1, "token": token, "run_id": run_id, "owner": self.name, "closed": closed,
                 "phase": "scoring", "serial": c["serial"], "adb_server_port": c["adb_server_port"],
                 "controller_pid": os.getpid(), "driver_grpc_port": self.port}
        temporary = self.lease_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value))
        os.replace(temporary, self.lease_path)

    def dispatch(self, run_id, goal, max_steps, wall_limit_s, question, base, key):
        """One worker run; returns its validated result. Infrastructure problems raise EnvironmentFailure."""
        result_path, trace_path = base.with_suffix(".worker.json"), base.with_suffix(".trace.json")
        if result_path.exists() or trace_path.exists():
            raise EnvironmentFailure("worker_artifacts_already_exist")
        token = secrets.token_hex(24)
        request = self.request(run_id, goal, max_steps, wall_limit_s, question, result_path, trace_path, token)
        env = {k: v for k, v in os.environ.items()
               if not any(word in k.upper() for word in ("API_KEY", "TOKEN", "SECRET", "PASSWORD", "RELAY_KEY", "JEV_KEY"))}
        env["GEMINI_API_KEY"] = key
        env["MIDSCENE_RUN_DIR"] = str(base.parent / "native-runtime" / base.name)
        try:
            if self.name == "finalrun":
                self.start_driver()
                env["FINALRUN_GRPC_PORT"] = str(self.port)
            self.write_lease(run_id, token)
            process = subprocess.Popen([str(self.node), str(self.worker)], stdin=subprocess.PIPE,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       env=env, cwd=str(self.checkout), start_new_session=True)
            try:
                process.communicate(json.dumps(request).encode(), timeout=wall_limit_s + GRACE_S)
                timed_out = False
            except subprocess.TimeoutExpired:
                timed_out = True
            # A worker can exit while an owned ADB or Node descendant survives.
            kill_group(process)
        finally:
            self.write_lease(run_id, token, closed=True)
            if self.name == "finalrun":
                self.stop_driver()
        if timed_out or process.returncode != 0 or not result_path.is_file():
            raise EnvironmentFailure("worker_timeout_exit_or_missing_result")
        return validate_result(compact_result(result_path), request)


def kill_group(process):
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            break
        try:
            process.wait(timeout=GRACE_S)
            if sig == signal.SIGTERM:
                time.sleep(0.1)
        except subprocess.TimeoutExpired:
            continue


class OpponentAgent(base_agent.EnvironmentInteractingAgent):
    """The official AW agent wrapper: one native worker run per episode, never replayed."""
    record_key = "opponent"

    def __init__(self, env, opponent, bridge, spec, key, question, base, clock=time.monotonic):
        super().__init__(env, name=opponent.name, transition_pause=None)
        self.opponent, self.bridge, self.spec, self.key, self.question, self.base = opponent, bridge, spec, key, question, base
        self.clock = clock
        self.result = None
        self.agent_failure = self.env_failure = None
        self.started = False
        self.wall_s = 0.0
        self.official_goal = None

    def reset(self, go_home=False):
        if self.started:
            raise RuntimeError("An episode agent cannot be reset or replayed")
        super().reset(go_home)

    def step(self, goal):
        if self.started:
            raise RuntimeError("A worker must not be dispatched twice")
        if goal not in (self.spec["goal"], self.official_goal) or self._max_steps != self.spec["official_max_steps"]:
            raise ValueError("Official goal/budget mismatch before task dispatch")
        self.started = True
        start = self.clock()
        try:
            self.result = self.opponent.dispatch(self.spec["run_id"], goal, self._max_steps, self.spec["wall_limit_s"],
                                                 self.question, self.base, self.key)
            if self.result["status"] != "completed":
                self.agent_failure = self.result.get("reason") or self.result["status"]
        except (KeyboardInterrupt, SystemExit):
            self.env_failure = "interrupted"
            raise
        except Exception as error:
            self.env_failure = type(error).__name__ + ": " + str(error)
        finally:
            self.wall_s = self.clock() - start
        if self.env_failure is None and self.question:
            answer = self.result.get("answer")
            if answer is not None and answer.strip():
                self.env.execute_action(json_action.JSONAction(action_type=json_action.ANSWER, text=answer))
                if self.env.interaction_cache != answer:
                    raise EnvironmentFailure("Official ANSWER did not set interaction_cache")
        return base_agent.AgentInteractionResult(done=True, data={"opponent": self.result,
            "agent_failure": self.agent_failure, "env_failure": self.env_failure})

    def capture(self, base):
        """Episode artifacts: the worker's sanitized trace as the log, and the final screen."""
        trace = base.with_suffix(".trace.json")
        base.with_suffix(".log").write_text(self.bridge.redact(trace.read_text()) if trace.exists() else "no worker trace\n")
        self.bridge.dev.screenshot().save(base.with_suffix(".jpg"), quality=80)
