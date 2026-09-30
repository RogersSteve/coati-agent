"""App-knowledge learning benchmark: does a flow learn an app manual, and does the manual help?

Usage: python run_learning_bench.py <out_dir> <modes> [sequences]

For each mode and sequence: clear Coati's app manuals, switch app knowledge to LEARNING, then run
the Manual Lab tasks of SEQUENCE in order. The target app's data is cleared before every run but
the manual is kept, so later runs can use what earlier runs learned. After each run the script
waits for the manual update that follows a completed task, then saves the manual and a record:

  <mode>_s<n>_<k>_<task>.json  status, verified, wall, steps, reasoning calls, manual stats
  <mode>_s<n>_<k>_<task>.md    the manual as it stands after that run

Compare cold (first L1) with warm (later L1) runs per mode, and the manuals between modes.
Environment variables are the same as run_app_bench.py.
"""
import json
import re
import sys
import time
from pathlib import Path

import adbdev as dev
import run_app_bench as bench
from tasks import TASKS

MANUAL_RECEIVER = "bot.coati/.debug.DebugManualReceiver"
# Cold run, two warm repeats of the same task, then a related task sharing the route
# 我的 -> 设置 -> 高级设置.
SEQUENCE = ["L1", "L1", "L1", "L3"]
UPDATE_WAIT_S = 240
# Headings and route words that show the manual captured the deep route used by L1/L3.
ROUTE_WORDS = ["实验档案", "高级设置", "设置", "紧凑资料卡片"]


def manual_broadcast(action, *extras):
    return dev.sh("shell", "am", "broadcast", "-a", f"bot.coati.debug.{action}", "-n", MANUAL_RECEIVER, *extras)


def manual_text():
    paths = dev.sh("shell", "run-as", "bot.coati", "find", "no_backup", "-name", "manual.md").split()
    lab = [p for p in paths if p.strip()]
    return "\n\n".join(dev.sh("shell", "run-as", "bot.coati", "cat", p.strip()) for p in lab)


def manual_stats(text):
    return {
        "chars": len(text),
        "headings": len(re.findall(r"^#{1,6} ", text, re.M)),
        # Pages described under the manual's page-details section (### headings).
        "pages": len(re.findall(r"^### ", text.split("## 页面详情", 1)[1], re.M)) if "## 页面详情" in text else 0,
        "route_words": [w for w in ROUTE_WORDS if w in text],
    }


def wait_for_update():
    """Wait for the manual update that follows a completed task (or its explicit skip)."""
    deadline = time.time() + UPDATE_WAIT_S
    while time.time() < deadline:
        log = dev.sh("logcat", "-d", "-s", "CoatiManual")
        if re.search(r"update stage=\S+ result=(SAVED|NO_CHANGE)|enqueue accepted=false", log):
            return re.findall(r"update stage=.*|enqueue accepted=.*", log)[-1].strip()
        time.sleep(5)
    return "timeout"


def run(tid, mode, label, out_dir):
    pkg, task, check, tags = TASKS[tid]
    if (out_dir / f"{label}.json").exists():
        return
    dev.sh("logcat", "-c")
    t0 = time.perf_counter()
    if not bench.start_task(pkg, task):
        bench.configure(mode)
        t0 = time.perf_counter()
        bench.start_task(pkg, task)
    status, state = "timeout", {}
    while time.perf_counter() - t0 < bench.RUN_TIMEOUT_S:
        time.sleep(3)
        state = bench.task_state()
        if state.get("goal") == task and state.get("status") in ("completed", "failed", "cancelled"):
            status = state["status"]
            break
    wall = time.perf_counter() - t0
    update = wait_for_update() if status == "completed" else "not completed"
    log = dev.sh("logcat", "-d", "-s", *bench.LOG_TAGS, "CoatiManual", timeout=60)
    bench.broadcast("bot.coati.debug.STOP_TASK")
    time.sleep(2)
    verified = check() if check else None
    manual = manual_text()
    m = re.search(r"Task complete at step (\d+)", log)
    rec = {"task": tid, "mode": mode, "label": label, "status": status, "verified": verified,
           "wall": round(wall, 1), "steps": int(m.group(1)) if m else None,
           "reasoning_calls": log.count("Calling reasoning model"), "manual_update": update,
           "manual": manual_stats(manual)}
    (out_dir / f"{label}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / f"{label}.md").write_text(manual, encoding="utf-8")
    (out_dir / f"{label}.log").write_text(log, encoding="utf-8")
    print(f"=== {label}: status={status} verified={verified} steps={rec['steps']} wall={wall:.0f}s "
          f"update={update[:60]!r} manual={rec['manual']}", flush=True)


def main():
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    modes = sys.argv[2].split(",")
    sequences = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    for n in range(sequences):
        for mode in modes:
            labels = [f"{mode}_s{n}_{k}_{tid}" for k, tid in enumerate(SEQUENCE)]
            if all((out_dir / f"{label}.json").exists() for label in labels):
                continue
            bench.configure(mode)
            if not (out_dir / f"{labels[0]}.json").exists():
                # A new sequence starts from no knowledge at all.
                manual_broadcast("CLEAR_MANUALS")
            manual_broadcast("SET_MANUAL_MODE", "--es", "mode", "LEARNING")
            bench.configure(mode)  # the service reads the mode when it starts
            for label, tid in zip(labels, SEQUENCE):
                run(tid, mode, label, out_dir)


if __name__ == "__main__":
    main()
