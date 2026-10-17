"""Car Market tasks: long loops of simple steps over test-apps/marketplace, scored from Coati's answer.

Usage:
  python marketplace.py expected                 # every task with its expected answer
  python marketplace.py score <task> "<answer>"  # score one free-text answer
  python marketplace.py summary <out_dir>        # per task x mode table of run_app_bench.py records
  python marketplace.py repeat-summary <out_dir> # the same for the repeat tasks R1-R4, with time per item

Each task asks for listings matching criteria that are only on the detail pages, so the agent has
to open every E-Class listing, read it, go back and remember the matches. The expected answers are
derived from the same listings.json the app shows, and the checker reads listing IDs, counts and
prices out of the answer text, so formatting does not matter.

Tasks R1-R4 run the app in repeat mode (test-apps/marketplace/src/main/assets/repeat.json): loops of
simple steps, one tap or one glance each, checked from the app's persisted state instead of an answer.
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

PKG = "bot.coati.fixture.marketplace"
LISTINGS = Path(__file__).resolve().parents[2] / "test-apps/marketplace/src/main/assets/listings.json"
REPEAT = LISTINGS.with_name("repeat.json")
KM_PER_MILE = 1.609344


def listings():
    return json.loads(LISTINGS.read_text(encoding="utf-8"))["listings"]


def miles(m):
    return m["value"] / KM_PER_MILE if m["unit"] == "km" else m["value"]


def km(m):
    return m["value"] * KM_PER_MILE if m["unit"] == "mi" else m["value"]


def is_match(car):
    """Available 2023 Mercedes-Benz E 300, within 100 km of Los Angeles, under 50,000 miles."""
    return (car["status"] == "Available" and car["make"] == "Mercedes-Benz" and car["model"] == "E 300"
            and car["year"] == 2023 and km(car["distance"]) <= 100 and miles(car["mileage"]) < 50_000)


def no_accidents(car):
    return car["accidents"] == "None reported"


CRITERIA = ("available 2023 Mercedes-Benz E 300 that is within 100 km of Los Angeles and has under "
            "50,000 miles")
# kind: "ids" (the set of listing IDs), "count" (a number) or "cheapest" (one ID and its price).
TASKS = {
    "M1": ("ids", is_match,
           f"In the Car Market app, find every {CRITERIA}. Report their listing IDs and prices."),
    "M2": ("count", is_match,
           f"In the Car Market app, how many listings are for an {CRITERIA}? Report the number."),
    "M3": ("cheapest", is_match,
           f"In the Car Market app, find the cheapest {CRITERIA}. Report only its listing ID and price."),
    "M4": ("ids", lambda car: is_match(car) and no_accidents(car),
           f"In the Car Market app, find every {CRITERIA} and has no reported accidents. Report their "
           "listing IDs and prices."),
    # M1 again with the one-time financing promotion on the 5th detail page (see tasks.py SETUP).
    "M5": ("ids", is_match,
           f"In the Car Market app, find every {CRITERIA}. Report their listing IDs and prices."),
}


def expected(tid):
    kind, keep, _ = TASKS[tid]
    cars = [c for c in listings() if keep(c)]
    if kind == "count":
        return len(cars)
    if kind == "cheapest":
        best = min(cars, key=lambda c: c["price_usd"])
        return {"id": best["id"], "price_usd": best["price_usd"]}
    return {c["id"]: c["price_usd"] for c in cars}


ID_RE = re.compile(r"\bCM\s*[-‐-―_ ]?\s*(\d{4})\b", re.I)
WORDS = {w: i for i, w in enumerate("zero one two three four five six seven eight nine ten eleven twelve".split())}


def ids_in(text):
    """Listing IDs as "CM-3417" or "cm 3417", or bare known IDs ("3417") when none has the prefix."""
    found = {f"CM-{d}" for d in ID_RE.findall(text)}
    if not found:
        known = {c["id"][3:] for c in listings()}
        found = {f"CM-{d}" for d in re.findall(r"(?<![\d,.$])\b(\d{4})\b(?![,.]?\d)", text) if d in known}
    return found


def prices_in(text):
    """Amounts in the answer: "$39,950", "39950 USD", "$39.95k"."""
    found = set()
    for m in re.finditer(r"(\d{1,3}(?:,\d{3})+|\d{4,6})", ID_RE.sub(" ", text)):
        found.add(int(m.group(1).replace(",", "")))
    for m in re.finditer(r"\$\s*(\d+(?:\.\d+)?)\s*[kK]\b", text):
        found.add(round(float(m.group(1)) * 1000))
    return found


def count_in(text):
    """The reported count, or None when the answer does not state exactly one."""
    rest = ID_RE.sub(" ", text)
    # Drop prices, comma-grouped numbers, years and measurements so only small counts remain.
    rest = re.sub(r"\$\s*[\d,.]+\s*[kK]?|\d{1,3}(?:,\d{3})+|\b\d{4,}\b|\d+(?:\.\d+)?\s*(?:km|kilomet\w*|mi\b|miles?)", " ",
                  rest, flags=re.I)
    numbers = [int(n) for n in re.findall(r"\b\d{1,2}\b", rest)]
    numbers += [WORDS[w.lower()] for w in re.findall(r"\b(" + "|".join(WORDS) + r")\b", rest, re.I)]
    stated = re.findall(r"\b(\d{1,2}|" + "|".join(WORDS) + r")\s+(?:matching\s+|such\s+)?(?:listings?|cars?|vehicles?|results?|matches)\b",
                        rest, re.I)
    if stated:
        values = {int(s) if s.isdigit() else WORDS[s.lower()] for s in stated}
        return values.pop() if len(values) == 1 else None
    distinct = set(numbers)
    return distinct.pop() if len(distinct) == 1 else None


def score(tid, answer):
    """Scores Coati's free-text answer: `correct`, plus recall and precision of IDs for list tasks."""
    kind = TASKS[tid][0]
    want = expected(tid)
    text = answer or ""
    got = ids_in(text)
    if kind == "count":
        n = count_in(text)
        return {"correct": n == want, "expected": want, "reported": n}
    if kind == "cheapest":
        ok = got == {want["id"]} and want["price_usd"] in prices_in(text)
        return {"correct": ok, "expected": want, "reported_ids": sorted(got)}
    hit = got & set(want)
    prices = prices_in(text)
    return {"correct": got == set(want),
            "recall": round(len(hit) / len(want), 3),
            "precision": round(len(hit) / len(got), 3) if got else 0.0,
            # Matches reported with their right price, informational only.
            "prices_right": sum(1 for i in hit if want[i] in prices),
            "expected": sorted(want), "reported_ids": sorted(got),
            "missed": sorted(set(want) - got), "extra": sorted(got - set(want))}


def answer_check(tid):
    """A tasks.py check that run_app_bench.py calls with Coati's answer instead of reading the screen."""
    def check(answer):
        return score(tid, answer)
    check.needs_answer = True
    return check


def summary(out_dir):
    """Per task x mode: success, mean recall/precision, median wall time, steps and critical model calls."""
    runs = defaultdict(list)
    for f in sorted(Path(out_dir).glob("M*_*.json")):
        r = json.loads(f.read_text(encoding="utf-8"))
        if r["task"] in TASKS:
            log = f.with_suffix(".log")
            r["detail_views"] = (log.read_text(encoding="utf-8").count("detail opened id=")
                                 if log.exists() else None)
            runs[(r["task"], r["mode"])].append(r)

    def med(rs, key):
        vals = [key(r) for r in rs if key(r) is not None]
        return statistics.median(vals) if vals else float("nan")

    def mean(rs, key):
        vals = [key(r) for r in rs if key(r) is not None]
        return sum(vals) / len(vals) if vals else float("nan")

    print(f"{'task':<5}{'mode':<10}{'ok':>6}{'recall':>8}{'prec':>7}{'wall':>7}{'steps':>7}{'calls':>7}"
          f"{'jev':>6}{'reas':>6}{'views':>7}")
    for (tid, mode), rs in sorted(runs.items()):
        ok = sum(1 for r in rs if r["status"] == "completed" and r["verified"])
        sc = [r.get("score") or {} for r in rs]
        recall = mean(sc, lambda s: s.get("recall"))
        prec = mean(sc, lambda s: s.get("precision"))
        calls = med(rs, lambda r: ((r.get("usage") or {}).get("critical") or {}).get("calls"))
        print(f"{tid:<5}{mode:<10}{f'{ok}/{len(rs)}':>6}{recall:>8.2f}{prec:>7.2f}{med(rs, lambda r: r['wall']):>7.0f}"
              f"{med(rs, lambda r: r['steps']):>7.0f}{calls:>7.0f}{med(rs, lambda r: r.get('jev_steps')):>6.0f}"
              f"{med(rs, lambda r: r.get('reasoning_calls')):>6.0f}{med(rs, lambda r: r['detail_views']):>7.0f}")


def repeat_config():
    return json.loads(REPEAT.read_text(encoding="utf-8"))


def _repeat_tasks():
    cfg = repeat_config()
    watch = cfg["watch"]
    return {
        "R1": f"In the Car Market app, add the first {cfg['favorite_count']} listings to your favorites.",
        "R2": "In the Car Market app, add these listings to the compare list: " + ", ".join(cfg["compare"]) + ".",
        "R3": "In the Car Market app, hide every listing marked Sold.",
        "R4": (f"In the Car Market app, refresh the listings until the price of {watch['id']} drops below "
               f"${watch['threshold_usd']:,}, then report its new price."),
    }


# Repeat tasks: one tap or one glance per item, many items (see repeat.json).
REPEAT_TASKS = _repeat_tasks()


def repeat_items(tid):
    """How many unit steps a task is made of: rows to favorite, compare or hide, or refreshes."""
    cfg = repeat_config()
    return {"R1": cfg["favorite_count"], "R2": len(cfg["compare"]), "R3": len(cfg["sold"]),
            "R4": cfg["watch"]["drop_at_refresh"]}[tid]


def parse_session(xml):
    """The app's market_session preferences: string sets and the refresh count."""
    def strings(name):
        m = re.search(rf'<set name="{name}">(.*?)</set>', xml or "", re.S)
        return set(re.findall(r"<string>(.*?)</string>", m.group(1))) if m else set()
    m = re.search(r'<int name="refreshes" value="(\d+)"', xml or "")
    return {"favorites": strings("favorites"), "compare": strings("compare"), "hidden": strings("hidden"),
            "refreshes": int(m.group(1)) if m else 0}


def score_repeat(tid, state, answer=None):
    """Scores a repeat task from the app state (and, for R4, the reported price)."""
    cfg = repeat_config()
    if tid == "R4":
        watch = cfg["watch"]
        reported = watch["new_price_usd"] in prices_in(answer or "")
        refreshed = state["refreshes"] >= watch["drop_at_refresh"]
        return {"correct": refreshed and reported, "refreshes": state["refreshes"], "price_reported": reported}
    want = {"R1": {c["id"] for c in listings()[:cfg["favorite_count"]]},
            "R2": set(cfg["compare"]), "R3": set(cfg["sold"])}[tid]
    got = state[{"R1": "favorites", "R2": "compare", "R3": "hidden"}[tid]]
    return {"correct": got == want, "done": len(got & want), "of": len(want),
            "missed": sorted(want - got), "extra": sorted(got - want)}


def repeat_summary(out_dir):
    """Per task x mode: success, median wall time, steps, wall time per item and tokens."""
    runs = defaultdict(list)
    for f in sorted(Path(out_dir).glob("R*_*.json")):
        r = json.loads(f.read_text(encoding="utf-8"))
        if r["task"] in REPEAT_TASKS:
            runs[(r["task"], r["mode"])].append(r)

    def med(rs, key):
        vals = [key(r) for r in rs if key(r) is not None]
        return statistics.median(vals) if vals else float("nan")

    def tokens(r):
        usage = r.get("usage") or {}
        return sum((usage.get(p) or {}).get("total_tokens") or 0 for p in ("critical", "background")) or None

    print(f"{'task':<5}{'mode':<10}{'ok':>6}{'wall':>7}{'steps':>7}{'s/item':>8}{'jev':>6}{'reas':>6}{'ktok':>7}")
    for (tid, mode), rs in sorted(runs.items()):
        ok = sum(1 for r in rs if r.get("verified"))
        wall = med(rs, lambda r: r["wall"])
        print(f"{tid:<5}{mode:<10}{f'{ok}/{len(rs)}':>6}{wall:>7.0f}{med(rs, lambda r: r['steps']):>7.0f}"
              f"{wall / repeat_items(tid):>8.1f}{med(rs, lambda r: r.get('jev_steps')):>6.0f}"
              f"{med(rs, lambda r: r.get('reasoning_calls')):>6.0f}{med(rs, tokens) / 1000:>7.0f}")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "expected"
    if cmd == "expected":
        for tid, (_, _, text) in TASKS.items():
            print(f"{tid}: {text}\n    expected: {json.dumps(expected(tid))}")
    elif cmd == "score":
        print(json.dumps(score(sys.argv[2], sys.argv[3]), indent=1))
    elif cmd == "summary":
        summary(sys.argv[2])
    elif cmd == "repeat-summary":
        repeat_summary(sys.argv[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
