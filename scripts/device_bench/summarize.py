"""Aggregate run_app_bench.py results: success rate, median steps and wall time per task x mode.

Usage: python summarize.py <out_dir> [manual_verdicts.json]

manual_verdicts.json optionally maps run names (e.g. "B1_classic_r0") to true/false for tasks
without an automatic check, after reviewing the saved final screenshots.
"""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def main():
    out = Path(sys.argv[1])
    manual = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8")) if len(sys.argv) > 2 else {}
    runs = defaultdict(list)
    for f in sorted(out.glob("*.json")):
        r = json.loads(f.read_text(encoding="utf-8"))
        verdict = r["verified"] if r["verified"] is not None else manual.get(f.stem)
        # A run succeeds only if Coati reported completion and the outcome is really there.
        r["ok"] = False if r["status"] != "completed" else verdict
        r["name"] = f.stem
        runs[(r["task"], r["mode"])].append(r)

    modes = sorted({m for _, m in runs})
    tasks = sorted({t for t, _ in runs})
    print(f"{'task':<5}" + "".join(f"{m:>26}" for m in modes))
    totals = {m: [0, 0] for m in modes}
    for t in tasks:
        cells = []
        for m in modes:
            rs = runs.get((t, m), [])
            if not rs:
                cells.append("-")
                continue
            succ = sum(1 for r in rs if r["ok"] is True)
            unknown = sum(1 for r in rs if r["ok"] is None)
            totals[m][0] += succ
            totals[m][1] += len(rs) - unknown
            good = [r for r in rs if r["ok"] is True]
            wall = statistics.median(r["wall"] for r in good) if good else float("nan")
            steps = statistics.median(r["steps"] for r in good) if good else float("nan")
            unk = f"?{unknown}" if unknown else ""
            cells.append(f"{succ}/{len(rs)}{unk} {wall:5.0f}s {steps:4.0f}st")
        print(f"{t:<5}" + "".join(f"{c:>26}" for c in cells))
    print("total" + "".join(f"{f'{s}/{n}':>26}" for s, n in totals.values()))
    pending = sorted(r["name"] for rs in runs.values() for r in rs if r["ok"] is None)
    if pending:
        print("needs manual verdict (review the .jpg):", ", ".join(pending))


if __name__ == "__main__":
    main()
