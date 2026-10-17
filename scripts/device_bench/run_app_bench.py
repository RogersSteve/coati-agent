"""End-to-end benchmark of the installed Coati debug build on a device or emulator.

Usage: python run_app_bench.py <out_dir> <task_ids|all> <modes> [reps]

Each run resets the target app, starts the task through the debug-only DebugAgentReceiver, and
waits for files/task.json to reach a final status. Results go to <out_dir>/<task>_<mode>_r<rep>.*
(.json record, .log logcat, .jpg final screen); existing records are skipped, so an interrupted
benchmark resumes where it stopped.
"""
import json
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path

import adbdev as dev
from perturb import Perturbation
from tasks import SETUP, TASKS

RECEIVER = "bot.coati/.debug.DebugAgentReceiver"
# Car Market scans take long in classic mode; raise the limit with BENCH_RUN_TIMEOUT (seconds).
RUN_TIMEOUT_S = int(os.environ.get("BENCH_RUN_TIMEOUT", "600"))
# Disable host environment restarts/replays for a strictly single-attempt smoke run.
ENV_RECOVERY_ENABLED = os.environ.get("BENCH_ENV_RECOVERY", "1") != "0"
LOG_TAGS = ["CoatiLoop", "CoatiScreen", "CoatiModel", "CoatiA11y", "CoatiAction", "CoatiDebugAgent", "CoatiUsage"]
API_KEY = os.environ["RELAY_KEY"]
ENDPOINT = os.environ["RELAY_ENDPOINT"].rstrip("/")
PROVIDER = os.environ.get("LLM_PROVIDER", "openai_compatible")
MODEL = os.environ.get("LLM_MODEL", "gemini-3.8-flash")
FAST_MODEL = os.environ.get("FAST_MODEL", MODEL)
ROTATION = os.environ.get("BENCH_ROTATION")  # e.g. "3" for a board that boots in landscape
EMULATOR_AVD = os.environ.get("EMULATOR_AVD", "Medium_Phone_API_36.0")
# Emulator host memory grows over many runs; a periodic cold restart bounds it.
EMULATOR_RESTART_EVERY = 10

JEV_KEY = os.environ.get("JEV_KEY", "")
JEV_ENDPOINT = os.environ.get("JEV_ENDPOINT", "https://api.typesafe.ai").rstrip("/")
JEV_MODEL = os.environ.get("JEV_MODEL", "jev-1.13.0")
# Extra CONFIGURE_AGENT extras per mode. An empty jev_key removes the key: Reasoning decides every
# step, i.e. the classic flow. BENCH_APK_<MODE> (e.g. BENCH_APK_CLASSIC) installs that Coati build
# before the mode runs, so a mode can use a build of another branch such as main.
# Configuration persists on the device, so every mode sets verify explicitly.
MODES = {
    "classic": ["--es", "jev_key", "", "--ez", "verify", "true"],
    # Ablation baseline: per-step Reasoning without post-action verification or completion audit.
    "baseline": ["--es", "jev_key", "", "--ez", "verify", "false"],
    "jev": ["--es", "jev_key", JEV_KEY, "--ez", "ocr", "true", "--ez", "verify", "true"],
    # The same fast path without GPU OCR, to measure what OCR adds.
    "jevnoocr": ["--es", "jev_key", JEV_KEY, "--ez", "ocr", "false", "--ez", "verify", "true"],
}
LOG_TAGS.append("CoatiJev")
LOG_TAGS.append("CoatiInterrupt")
LOG_TAGS.append("CoatiMarket")
# Robustness runs: one perturbation for every run (see perturb.py), e.g. dialog@3, notree, display.
PERTURB = Perturbation(os.environ.get("BENCH_PERTURB"))
# Fast-executor routing: a mode (FAST, BALANCED, ACCURATE) or explicit "goal,explore" thresholds. The app
# keeps the last value, so it is always sent; apps without routing ignore the extras.
ROUTING = os.environ.get("BENCH_ROUTING") or "BALANCED"


def routing_extras(routing=ROUTING):
    if routing.upper() in ("FAST", "BALANCED", "ACCURATE"):
        return ["--es", "routing", routing.upper()]
    goal, explore = (float(v) for v in routing.split(","))
    if not (0 <= goal <= 1.01 and 0 <= explore <= 1.01):
        raise ValueError("Routing thresholds must be within 0..1.01")
    # The mode resets explicit thresholds; the explicit pair is then applied on top of it.
    return ["--es", "routing", "BALANCED", "--ef", "routing_goal", repr(goal), "--ef", "routing_explore", repr(explore)]

_runs_since_restart = 0


def shell_quote(value):
    # adb joins shell arguments with spaces; quote so multi-word task text stays one argument.
    return "'" + value.replace("'", r"'\''") + "'"


def broadcast(action, *extras):
    # Every extra is quoted: an unquoted empty value (jev_key "" in classic mode) vanished from the
    # joined command line, am rejected the broadcast, and Coati silently kept the previous mode.
    out = dev.sh("shell", "am", "broadcast", "-a", action, "-n", RECEIVER, *map(shell_quote, extras))
    if "Broadcast completed" not in out:
        raise RuntimeError(f"broadcast {action} failed: {out.strip()[:200]}")
    return out


def clear_task_state():
    # A leftover paused/failed task makes Coati show a recovery dialog instead of starting.
    dev.sh("shell", "run-as", "bot.coati", "rm", "-f", "files/task.json", "files/task_result.json")


def task_result(run_id):
    """Coati's files/task_result.json for this run (see bot.coati.agent.TaskResult), or None."""
    out = dev.sh("shell", "run-as", "bot.coati", "cat", "files/task_result.json")
    try:
        result = json.loads(out)
    except ValueError:
        return None
    return result if result.get("run_id") == run_id else None


def prepare_device():
    """Check prior manual grants without replacing the enabled service list."""
    services = dev.sh("shell", "settings", "get", "secure",
                      "enabled_accessibility_services").strip().split(":")
    if not any(service in services for service in (
            "bot.coati/bot.coati.service.CoatiAccessibilityService",
            "bot.coati/.service.CoatiAccessibilityService")):
        raise RuntimeError("Enable Coati accessibility through Android settings first")
    overlay = dev.sh("shell", "appops", "get", "bot.coati", "SYSTEM_ALERT_WINDOW")
    if not re.search(r"SYSTEM_ALERT_WINDOW:\s*allow\b", overlay):
        raise RuntimeError("Grant Coati overlay permission through Android settings first")
    if ROTATION is not None:
        dev.lock_rotation(ROTATION)


_installed_apk = None


def install_mode_apk(mode):
    """Installs the mode's build unless this benchmark already installed the same file. A failed
    install stops the benchmark: adb reports it only in its output (for example
    INSTALL_FAILED_INSUFFICIENT_STORAGE on a full emulator), and runs would silently use the old build."""
    global _installed_apk
    apk = os.environ.get(f"BENCH_APK_{mode.upper()}")
    if not apk:
        return
    stat = os.stat(apk)
    key = (os.path.abspath(apk), stat.st_size, stat.st_mtime)
    if key == _installed_apk:
        return
    print(f"--- installing {apk} for mode {mode}", flush=True)
    # adb prints the failure reason on stderr, which dev.sh drops.
    r = subprocess.run(dev.adb_command("install", "-r", apk), capture_output=True, timeout=180)
    out = (r.stdout + r.stderr).decode("utf-8", "replace")
    if "Success" not in out:
        raise RuntimeError(f"installing {apk} failed: {out.strip()[-200:]}")
    _installed_apk = key


CONFIG_READY_TIMEOUT_S = 180
TASK_STOP_TIMEOUT_S = 30


CURSOR_TAG = "CoatiBenchCursor"


def boot_identity():
    value = dev.sh("shell", "cat", "/proc/sys/kernel/random/boot_id").strip()
    if not re.fullmatch(r"[0-9a-f-]{36}", value):
        raise RuntimeError("Device boot identity unavailable")
    return value


def configuration_log():
    return dev.sh("logcat", "-b", "main", "-d", "-v", "epoch", "-s",
                  "CoatiService:I", "CoatiDebugAgent:I", CURSOR_TAG + ":I", "*:S", timeout=30)


def log_events_after(log, cursor):
    # API33's monotonic formatter converts stored wall timestamps; actual
    # samples still regress after AW sets the clock. Use main-buffer order.
    if not isinstance(cursor, dict) or set(cursor) != {"boot_id", "marker"}:
        raise RuntimeError("Explicit boot-scoped marker cursor required")
    if boot_identity() != cursor["boot_id"]:
        raise RuntimeError("Device rebooted; rebuild cursor and verify connection identity")
    lines = log.splitlines()
    pattern = re.compile(r"\b" + CURSOR_TAG + r"\s*:\s*" + re.escape(cursor["marker"]) + r"\s*$")
    indices = [i for i,line in enumerate(lines) if pattern.search(line)]
    if len(indices) != 1:
        raise RuntimeError("Log boundary missing/trimmed/duplicated; refuse stale evidence")
    return lines[indices[0]+1:]


def configuration_cursor():
    cursor = {"boot_id":boot_identity(), "marker":"bench_" + uuid.uuid4().hex}
    dev.sh("shell", "log", "-p", "i", "-t", CURSOR_TAG, cursor["marker"])
    log_events_after(configuration_log(), cursor)  # Verify marker write/read and boot.
    return cursor


def configuration_task_state():
    # A missing file is distinct from an unreadable/malformed file; never clear
    # state after a failed read or assume that a malformed task is inactive.
    command = "if [ -f files/task.json ]; then cat files/task.json; else printf null; fi"
    out = dev.sh("shell", "run-as", "bot.coati", "sh", "-c", shell_quote(command))
    try:
        state = json.loads(out)
    except ValueError:
        raise RuntimeError("Cannot read task state before configuration") from None
    if state is not None and not isinstance(state, dict):
        raise RuntimeError("Invalid task state before configuration")
    return state


class StopCursorFailure(RuntimeError): pass
class StopBroadcastFailure(RuntimeError): pass
class StopStateFailure(RuntimeError): pass
class StopInactiveTimeout(RuntimeError): pass


def stop_task_before_configuration():
    started = time.monotonic()
    stage = "cursor"
    events = []
    def record(kind, **fields):
        # Closed fields: no argv/output/exception message/environment/task contents.
        row = {"kind": kind, "stage": stage, "monotonic_s": time.monotonic(),
               "since_start_s": time.monotonic() - started}
        row.update({k: v for k, v in fields.items()
                    if k in {"rc", "timeout", "elapsed_s", "ack", "status", "stable", "error"}})
        events.append(row)
    dev.STOP_DIAGNOSTIC_OBSERVER = record
    try:
        record("begin")
        cursor = configuration_cursor()
        stage = "broadcast"
        broadcast("bot.coati.debug.STOP_TASK")
        deadline = time.monotonic() + TASK_STOP_TIMEOUT_S
        inactive = {"paused", "completed", "failed", "cancelled"}
        previous = object()
        while time.monotonic() < deadline:
            stage = "ack"
            fresh = log_events_after(configuration_log(), cursor)
            acknowledged = any("Debug task stopped=" in event for event in fresh)
            stage = "state"
            state = configuration_task_state()
            stopped = state is None or state.get("status") in inactive
            stable = acknowledged and stopped and state == previous
            status = "missing" if state is None else state.get("status")
            record("observe", ack=acknowledged, stable=stable,
                   status=status if status in inactive | {"executing"} else "unknown")
            if stable:
                record("passed")
                return
            previous = state if stopped else object()
            time.sleep(0.5)
        stage = "inactive_wait"
        raise StopInactiveTimeout("STOP inactive deadline exceeded")
    except Exception:
        failures = {"cursor": StopCursorFailure, "ack": StopCursorFailure,
                    "broadcast": StopBroadcastFailure, "state": StopStateFailure,
                    "inactive_wait": StopInactiveTimeout}
        error = failures[stage]
        record("failed", error=error.__name__)
        raise error("STOP diagnostic stage=" + stage) from None
    finally:
        dev.STOP_DIAGNOSTIC_OBSERVER = None
        path = os.environ.get("COATI_STOP_DIAGNOSTICS_PATH")
        if path:
            try:
                with open(path, "a", encoding="utf-8") as out:
                    for row in events:
                        out.write(json.dumps(row, sort_keys=True) + "\n")
            except OSError:
                # Diagnostics must not change STOP success or failure semantics.
                pass


def wait_for_configuration(mode, cursor):
    deadline = time.monotonic() + CONFIG_READY_TIMEOUT_S
    jev = "true" if mode.startswith("jev") else "false"
    verify = "false" if mode == "baseline" else "true"
    expected_jev = JEV_MODEL if mode.startswith("jev") else "off"
    expected = (f"Agent initialized: reasoning={PROVIDER}/{MODEL}, "
                f"fast={PROVIDER}/{FAST_MODEL}, jev={expected_jev},")
    while time.monotonic() < deadline:
        events = log_events_after(configuration_log(), cursor)
        configured = None
        for index, event in enumerate(events):
            if ("Debug agent configured:" in event and f"jev={jev}," in event
                    and f"verify={verify}," in event):
                if mode in ("jev", "jevnoocr"):
                    ocr = "true" if mode == "jev" else "false"
                    if f"ocr={ocr}," not in event:
                        continue
                configured = index
            if configured is not None and index > configured and expected in event:
                prepare_device()  # Recheck prior UI grants after asynchronous reload.
                return
        time.sleep(0.5)
    raise RuntimeError("No fresh matching runtime initialization after configuration")


def configure(mode):
    if mode.startswith("jev") and not JEV_KEY:
        raise SystemExit("mode jev needs JEV_KEY")
    install_mode_apk(mode)
    prepare_device()  # Never replace grants or force-stop the accessibility owner.
    stop_task_before_configuration()
    clear_task_state()
    cursor = configuration_cursor()
    broadcast("bot.coati.debug.CONFIGURE_AGENT",
              "--es", "provider", PROVIDER, "--es", "endpoint", ENDPOINT, "--es", "api_key", API_KEY,
              "--es", "reasoning_model", MODEL, "--es", "fast_model", FAST_MODEL, "--es", "safety", "TRUST",
              *MODES[mode], "--es", "jev_endpoint", JEV_ENDPOINT, "--es", "jev_model", JEV_MODEL,
              *PERTURB.configure_extras(), *routing_extras())
    # With permissions, models and onboarding in place, the launcher activity starts the service.
    dev.sh("shell", "am", "start", "-n", "bot.coati/.ui.main.MainActivity")
    wait_for_configuration(mode, cursor)
    dev.key("HOME")


def task_state():
    out = dev.sh("shell", "run-as", "bot.coati", "cat", "files/task.json")
    try:
        return json.loads(out)
    except ValueError:
        return {}


def recover_device(mode, reason):
    """Emulators whose capture broke need a cold restart. Physical devices are never rebooted by the
    benchmark (a reboot drops adb reverse tunnels and heats the device); restarting Coati is enough."""
    if not ENV_RECOVERY_ENABLED:
        raise RuntimeError("Environment recovery disabled: " + reason)
    if dev.is_emulator():
        print(f"!!! {reason}: cold-restarting the emulator", flush=True)
        dev.cold_restart_emulator(EMULATOR_AVD)
    else:
        print(f"!!! {reason}: restarting Coati", flush=True)
    configure(mode)


def start_task(pkg, task, run_id, tid=None):
    dev.force_stop(pkg)
    dev.sh("shell", "pm", "clear", pkg)
    for key in (pkg, tid):
        if key in SETUP:
            SETUP[key]()
    dev.key("HOME")
    time.sleep(1)
    dev.launch(pkg)
    time.sleep(7)
    dev.sh("logcat", "-c")
    clear_task_state()
    broadcast("bot.coati.debug.RUN_TASK", "--es", "task", task, "--es", "run_id", run_id)
    time.sleep(2)
    return "started=true" in dev.sh("logcat", "-d", "-s", "CoatiDebugAgent")


def task_usage(log):
    """The task's model usage summary that Coati logs when the task ends, or None."""
    m = re.search(r"Task usage status=\w+ (\{.*\})\s*$", log, re.MULTILINE)
    return json.loads(m.group(1)) if m else None


def run_one(tid, mode, rep, out_dir, attempt=1):
    global _runs_since_restart
    pkg, task, check, tags = TASKS[tid]
    name = f"{tid}_{mode}_r{rep}"
    if (out_dir / f"{name}.json").exists():
        return
    _runs_since_restart += 1
    if dev.is_emulator() and _runs_since_restart > EMULATOR_RESTART_EVERY:
        _runs_since_restart = 1
        recover_device(mode, f"{name}: periodic emulator restart")
    if not dev.healthy():
        recover_device(mode, f"{name}: device unresponsive")
    if ROTATION is not None:
        dev.lock_rotation(ROTATION)

    PERTURB.before_task()
    t0 = time.perf_counter()
    if not start_task(pkg, task, name, tid):
        if not ENV_RECOVERY_ENABLED:
            raise RuntimeError("Task was not accepted; single-attempt run stopped")
        print(f"!!! {name}: runtime not ready, restarting the service", flush=True)
        configure(mode)
        t0 = time.perf_counter()
        start_task(pkg, task, name, tid)
    PERTURB.task_started()
    status, state = "timeout", {}
    while time.perf_counter() - t0 < RUN_TIMEOUT_S:
        time.sleep(3)
        state = task_state()
        # task.json was deleted before the run, so any final status for this goal is this run's.
        if state.get("goal") == task and state.get("status") in ("completed", "failed", "cancelled"):
            status = state["status"]
            break
    wall = time.perf_counter() - t0
    perturbation = PERTURB.finish()
    broadcast("bot.coati.debug.STOP_TASK")  # dismiss result dialogs / stop a timed-out run
    time.sleep(2)
    result = task_result(name)
    clear_task_state()
    log = dev.sh("logcat", "-d", "-s", *LOG_TAGS, timeout=60)
    # Error 1 (internal) is the emulator's permanently broken capture; other codes such as 3
    # (rate limited) are transient and handled by Coati itself.
    if (ENV_RECOVERY_ENABLED and status == "failed"
            and "Screenshot failed with error: 1" in log and attempt < 3):
        # An environment failure, not an agent result: recover the device and run again.
        recover_device(mode, f"{name}: screenshots broken")
        return run_one(tid, mode, rep, out_dir, attempt + 1)

    dev.screenshot().save(out_dir / f"{name}.jpg", quality=80)
    # Answer checks score the answer Coati gave (Car Market); the others inspect the device.
    score = check((result or {}).get("answer")) if getattr(check, "needs_answer", False) else None
    verified = score["correct"] if score else check() if check else None
    m = re.search(r"Task complete at step (\d+)", log)
    steps = int(m.group(1)) if m else len(re.findall(r"── Step \d+/", log))
    rec = {"task": tid, "mode": mode, "rep": rep, "tags": tags, "status": status, "verified": verified,
           "wall": round(wall, 1), "steps": steps, "errors": state.get("errors", []),
           # Where the decisions came from: fast JEV steps vs Reasoning steps, and hand-overs.
           "jev_steps": len(re.findall(r"CoatiJev: Step \d+ \[", log)),
           "reasoning_calls": log.count("Calling reasoning model"),
           "escalations": log.count("Escalating to the Reasoning model"),
           # Model calls and tokens per purpose, critical path vs background (see ModelUsageMeter).
           "usage": task_usage(log),
           # Coati's own account of the run: completion claims, audit decision, budget (TaskResult).
           "result": result,
           "perturbation": perturbation}
    if score:
        rec["score"] = score
    (out_dir / f"{name}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / f"{name}.log").write_text(log, encoding="utf-8")
    print(f"=== {name}: status={status} verified={verified} steps={steps} wall={wall:.0f}s", flush=True)


def main():
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    task_ids = list(TASKS) if sys.argv[2] == "all" else sys.argv[2].split(",")
    modes = sys.argv[3].split(",")
    reps = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    for rep in range(reps):
        for mode in modes:
            configure(mode)
            for tid in task_ids:
                run_one(tid, mode, rep, out_dir)


if __name__ == "__main__":
    main()
