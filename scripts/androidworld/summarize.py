"""Summarize official scores; incomplete three-trial metrics remain unavailable."""
import argparse
import json
from pathlib import Path
import statistics
from collections import defaultdict


def mean_present(records, getter):
    values = [getter(r) for r in records]
    return {"mean":statistics.mean([v for v in values if v is not None]) if any(v is not None for v in values) else None,
            "observed":sum(v is not None for v in values), "missing":sum(v is None for v in values)}


def summarize(records, compatibility=None, paths=None):
    if compatibility is not None:
        if paths is None or len(paths) != len(records):
            raise ValueError("Compatibility requires exact source paths")
        normalized = []
        for record, path in zip(records, paths):
            protocol = record["identity"]["protocol"]
            if protocol in compatibility.value["old_protocols"]:
                normalized.append(compatibility.view(record, path))
            elif protocol == compatibility.value["new_protocol"]:
                normalized.append(record)
            else:
                raise ValueError("Unmapped experiment in compatibility summary")
        records = normalized
    groups = defaultdict(list)
    for record in records:
        if record.get("phase") != "eval":
            continue
        key = (record["config"], record["identity"]["protocol"]["experiment_sha256"])
        groups[key].append(record)
    output = []
    for (config, protocol_hash), rows in sorted(groups.items()):
        expected = rows[0]["identity"]["protocol"]["experiment"]
        expected_keys = {(name, v) for name in expected["tasks"] for v in range(expected["variants"])}
        by_trial = defaultdict(dict)
        for r in rows:
            if r["trial"] not in (1, 2, 3):
                raise ValueError("Unexpected trial")
            k = (r["task"], r["variant"])
            if k not in expected_keys or k in by_trial[r["trial"]]:
                raise ValueError("Unexpected or duplicate episode; do not select a better attempt")
            by_trial[r["trial"]][k] = r
        valid = [r for r in rows if r.get("complete") is True and r.get("env_failure") is None and r.get("success") is not None]
        rates = {}
        for t, trial in by_trial.items():
            eligible = [r for r in trial.values() if r in valid]
            full = set(trial) == expected_keys and len(eligible) == len(expected_keys)
            rates[t] = {"SR":sum(r["success"] > 0.5 for r in eligible)/len(eligible) if eligible else None,
                        "eligible":len(eligible), "expected":len(expected_keys), "complete":full}
        three_complete = set(rates) == {1,2,3} and all(v["complete"] for v in rates.values())
        triple = [k for k in expected_keys if all(k in by_trial[t] and by_trial[t][k] in valid for t in (1,2,3))]
        claims = [r for r in valid if (r.get("coati") or {}).get("declared_done") is True]
        stratified = {}
        for d in ("easy", "medium", "hard"):
            eligible = [r for r in valid if r["difficulty"] == d]
            stratified[d] = {"successful":sum(r["success"] > 0.5 for r in eligible), "eligible":len(eligible)}
        def usage(r, section, field):
            return ((r.get("coati") or {}).get("usage") or {}).get(section, {}).get(field)
        def tokens(r):
            c,b = usage(r,"critical","total_tokens"), usage(r,"background","total_tokens")
            return c+b if c is not None and b is not None else None
        item = {"config":config,"experiment_sha256":protocol_hash,"episodes":len(rows),"eligible":len(valid),
                "env_failure_count":sum(r.get("env_failure") is not None for r in rows),
                "env_failure_rate":sum(r.get("env_failure") is not None for r in rows)/len(rows),
                "env_failure_policy":"Excluded from score/false-done denominators; missing scores prevent complete3-trial aggregate; no automatic replay",
                "trial_SR":rates,"SR_mean":statistics.mean(v["SR"] for v in rates.values()) if three_complete else None,
                "SR_std":statistics.stdev(v["SR"] for v in rates.values()) if three_complete else None,
                "SR_std_convention":"sample std, exactly3 complete trials",
                "pass3":sum(all(by_trial[t][k]["success"] > .5 for t in (1,2,3)) for k in triple)/len(expected_keys) if three_complete else None,
                "pass3_covered_tasks":len(triple),"pass3_expected_tasks":len(expected_keys),
                "false_done":sum(r["success"] <= .5 for r in claims)/len(claims) if claims else None,
                "false_done_declared_done_denominator":len(claims),"difficulty":stratified,
                "wall_s":mean_present(valid,lambda r:r.get("wall_s")),
                "steps":mean_present(valid,lambda r:(r.get("coati") or {}).get("steps")),
                "refunded_steps":mean_present(valid,lambda r:(r.get("coati") or {}).get("refunded_steps")),
                "fast_steps":mean_present(valid,lambda r:(r.get("coati") or {}).get("fast_steps")),
                "escalations":mean_present(valid,lambda r:(r.get("coati") or {}).get("escalations")),
                "done_claims":mean_present(valid,lambda r:(r.get("coati") or {}).get("done_claims")),
                "audit_rejects":mean_present(valid,lambda r:(r.get("coati") or {}).get("audit_rejects")),
                "manual_empty":mean_present(valid,lambda r:(r.get("manual_inventory") or {}).get("empty")),
                "manual_count":mean_present(valid,lambda r:(r.get("manual_inventory") or {}).get("manual_count")),
                "ocr_requested":mean_present(valid,lambda r:(r.get("ocr") or {}).get("requested")),
                "ocr_effective":mean_present(valid,lambda r:(r.get("ocr") or {}).get("effective")),
                "critical_calls":mean_present(valid,lambda r:usage(r,"critical","calls")),
                "critical_tokens":mean_present(valid,lambda r:usage(r,"critical","total_tokens")),
                "background_tokens":mean_present(valid,lambda r:usage(r,"background","total_tokens")),
                "total_tokens":mean_present(valid,tokens)}
        if compatibility is not None:
            item["compatibility"] = {"manifest_sha256": compatibility.sha256,
                "old_experiment_sha256": [v["experiment_sha256"] for v in compatibility.value["old_protocols"]],
                "new_experiment_sha256": compatibility.value["new_protocol"]["experiment_sha256"],
                "old_sources": [v["experiment"]["sources"] for v in compatibility.value["old_protocols"]],
                "new_sources": compatibility.value["new_protocol"]["experiment"]["sources"],
                "compatible_original_run_ids": [r["compatibility_evidence"]["old_run_id"] for r in rows if "compatibility_evidence" in r]}
        def pct(x): return "n/a" if x is None else f"{100*x:.1f}"
        item["latex_row"] = config + " & " + ("n/a" if item["SR_mean"] is None else pct(item["SR_mean"])+r" $\pm$ "+pct(item["SR_std"])) + " & " + pct(item["pass3"]) + " & " + pct(item["false_done"]) + r" \\"
        output.append(item)
    return output


def load_results(root):
    """Read named episodes only; report incomplete/stage evidence separately."""
    import hashlib
    import re
    rows, paths, unfinished = [], [], []
    episode = re.compile(r"^[A-Za-z0-9_-]+_t[123](?:_v[0-9]+)?\.json$")
    for path in sorted(Path(root).glob("*/*.json")):
        if episode.fullmatch(path.name):
            value = json.loads(path.read_text())
            if "identity" not in value or "success" not in value:
                raise ValueError("Named episode lacks result identity/score")
            rows.append(value); paths.append(path)
        elif any(marker in path.name for marker in (".inprogress.", ".partial.", ".writing.", "stage-event-")):
            entry = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "kind": "unfinished_or_stage_evidence"}
            if path.name.startswith("stage-event-"):
                entry["event"] = json.loads(path.read_text())
            unfinished.append(entry)
        # Session sidecars, manifests and manual inventories are not episodes.
    return rows, paths, unfinished


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("results"); p.add_argument("--resume-compatibility"); args=p.parse_args()
    rows, paths, unfinished = load_results(args.results)
    from protocol import ResumeCompatibility
    compatibility = ResumeCompatibility(args.resume_compatibility) if args.resume_compatibility else None
    report = summarize(rows, compatibility, paths)
    for item in report:
        item["unfinished_evidence"] = unfinished
    print(json.dumps({"groups": report, "unfinished_evidence": unfinished}, indent=2))

if __name__ == "__main__": main()
