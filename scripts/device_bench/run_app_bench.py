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
import sys
import time
from pathlib import Path

import adbdev as dev
from tasks import SETUP, TASKS

RECEIVER = "bot.coati/.debug.DebugAgentReceiver"
RUN_TIMEOUT_S = 600
LOG_TAGS = ["CoatiLoop", "CoatiScreen", "CoatiModel", "CoatiA11y", "CoatiAction", "CoatiDebugAgent"]
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
# Extra CONFIGURE_AGENT extras per mode. An empty jev_key removes the key: Reasoning decides every
# step, i.e. the classic flow. BENCH_APK_<MODE> (e.g. BENCH_APK_CLASSIC) installs that Coati build
# before the mode runs, so a mode can use a build of another branch such as main.
MODES = {
    "classic": ["--es", "jev_key", ""],
    "jev": ["--es", "jev_key", JEV_KEY, "--ez", "ocr", "true"],
    # The same fast path without GPU OCR, to measure what OCR adds.
    "jevnoocr": ["--es", "jev_key", JEV_KEY, "--ez", "ocr", "false"],
}
LOG_TAGS.append("CoatiJev")

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
    dev.sh("shell", "run-as", "bot.coati", "rm", "-f", "files/task.json")


def prepare_device():
    dev.sh("shell", "appops", "set", "bot.coati", "SYSTEM_ALERT_WINDOW", "allow")
    if ROTATION is not None:
        dev.lock_rotation(ROTATION)
    dev.sh("shell", "settings", "put", "secure", "enabled_accessibility_services",
           "bot.coati/bot.coati.service.CoatiAccessibilityService")
    dev.sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")


def install_mode_apk(mode):
    apk = os.environ.get(f"BENCH_APK_{mode.upper()}")
    if apk:
        print(f"--- installing {apk} for mode {mode}", flush=True)
        dev.sh("install", "-r", apk, timeout=180)


def configure(mode):
    if mode.startswith("jev") and not JEV_KEY:
        raise SystemExit("mode jev needs JEV_KEY")
    install_mode_apk(mode)
    clear_task_state()
    dev.force_stop("bot.coati")
    prepare_device()  # force-stop unbinds the accessibility service; re-enable it
    broadcast("bot.coati.debug.CONFIGURE_AGENT",
              "--es", "provider", PROVIDER, "--es", "endpoint", ENDPOINT, "--es", "api_key", API_KEY,
              "--es", "reasoning_model", MODEL, "--es", "fast_model", FAST_MODEL, "--es", "safety", "TRUST",
              *MODES[mode])
    # With permissions, models and onboarding in place, the launcher activity starts the service.
    dev.sh("shell", "am", "start", "-n", "bot.coati/.ui.main.MainActivity")
    time.sleep(6)
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
    if dev.is_emulator():
        print(f"!!! {reason}: cold-restarting the emulator", flush=True)
        dev.cold_restart_emulator(EMULATOR_AVD)
    else:
        print(f"!!! {reason}: restarting Coati", flush=True)
    configure(mode)


def start_task(pkg, task):
    dev.force_stop(pkg)
    dev.sh("shell", "pm", "clear", pkg)
    if pkg in SETUP:
        SETUP[pkg]()
    dev.key("HOME")
    time.sleep(1)
    dev.launch(pkg)
    time.sleep(7)
    dev.sh("logcat", "-c")
    clear_task_state()
    broadcast("bot.coati.debug.RUN_TASK", "--es", "task", task)
    time.sleep(2)
    return "started=true" in dev.sh("logcat", "-d", "-s", "CoatiDebugAgent")


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

    t0 = time.perf_counter()
    if not start_task(pkg, task):
        print(f"!!! {name}: runtime not ready, restarting the service", flush=True)
        configure(mode)
        t0 = time.perf_counter()
        start_task(pkg, task)
    status, state = "timeout", {}
    while time.perf_counter() - t0 < RUN_TIMEOUT_S:
        time.sleep(3)
        state = task_state()
        # task.json was deleted before the run, so any final status for this goal is this run's.
        if state.get("goal") == task and state.get("status") in ("completed", "failed", "cancelled"):
            status = state["status"]
            break
    wall = time.perf_counter() - t0
    broadcast("bot.coati.debug.STOP_TASK")  # dismiss result dialogs / stop a timed-out run
    time.sleep(2)
    clear_task_state()
    log = dev.sh("logcat", "-d", "-s", *LOG_TAGS, timeout=60)
    # Error 1 (internal) is the emulator's permanently broken capture; other codes such as 3
    # (rate limited) are transient and handled by Coati itself.
    if status == "failed" and "Screenshot failed with error: 1" in log and attempt < 3:
        # An environment failure, not an agent result: recover the device and run again.
        recover_device(mode, f"{name}: screenshots broken")
        return run_one(tid, mode, rep, out_dir, attempt + 1)

    dev.screenshot().save(out_dir / f"{name}.jpg", quality=80)
    verified = check() if check else None
    m = re.search(r"Task complete at step (\d+)", log)
    steps = int(m.group(1)) if m else len(re.findall(r"── Step \d+/", log))
    rec = {"task": tid, "mode": mode, "rep": rep, "tags": tags, "status": status, "verified": verified,
           "wall": round(wall, 1), "steps": steps, "errors": state.get("errors", []),
           # Where the decisions came from: fast JEV steps vs Reasoning steps, and hand-overs.
           "jev_steps": len(re.findall(r"CoatiJev: Step \d+ \[", log)),
           "reasoning_calls": log.count("Calling reasoning model"),
           "escalations": log.count("Escalating to the Reasoning model")}
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
