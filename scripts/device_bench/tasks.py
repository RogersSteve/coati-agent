"""Benchmark tasks and their success checks.

Each task is (package, task text, check, tags). A check returns True/False after the run, or the
task has check=None and needs a manual verdict from the saved final screenshot (see summarize.py).
Tags describe what the task exercises: native (full accessibility tree), no-tree (pixels only),
mixed (both on one screen), input (soft keyboard text entry), dependent (a control that only
appears after another action), cross-page (navigation across several pages).
"""
import re
import time

import adbdev as dev
import marketplace

LAB = "bot.coati.fixture.manual"
BILI = "tv.danmaku.bili"
# Flutter Gallery v2.9.2 (github.com/flutter-team-archive/gallery releases): a Flutter app whose
# accessibility tree is Flutter semantics (content descriptions, no view hierarchy).
GALLERY = "io.flutter.demo.gallery"


def texts():
    return " ".join((n["text"] or "") + " " + (n["desc"] or "") for n in dev.ui_nodes())


def lab_checked(title):
    nodes = dev.ui_nodes()
    row = next((n for n in nodes if n["text"] == title), None)
    if not row:
        return False
    y = (row["bounds"][1] + row["bounds"][3]) / 2
    return any(n["checked"] and n["bounds"][1] - 150 < y < n["bounds"][3] + 150 for n in nodes)


def lab_pref(name):
    """A string preference of the lab app (debuggable, so run-as can read its private files)."""
    xml = dev.sh("shell", "run-as", LAB, "cat", "shared_prefs/manual_lab_state.xml")
    m = re.search(rf'<string name="{name}">(.*?)</string>', xml)
    return m.group(1) if m else None


def lab_pref_set(name):
    xml = dev.sh("shell", "run-as", LAB, "cat", "shared_prefs/manual_lab_state.xml")
    m = re.search(rf'<set name="{name}">(.*?)</set>', xml, re.S)
    return set(re.findall(r"<string>(.*?)</string>", m.group(1))) if m else set()


def descs():
    return [n["desc"] for n in dev.ui_nodes()]


def gallery_cart_has(*items):
    shown = descs()
    return all(any(d.startswith(f"{item}\nQuantity") for d in shown) for item in items)


def gallery_theme_is(theme):
    shown = descs()
    if not any(d.startswith("Theme\n") for d in shown):
        # The agent may have closed the settings panel; reopen it to read the state.
        settings = next((n for n in dev.ui_nodes() if n["desc"] == "Settings"), None)
        if settings:
            x1, y1, x2, y2 = settings["bounds"]
            dev.sh("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(2)
            shown = descs()
    return f"Theme\n{theme}" in shown


# Setup after the app's data is cleared, per package or per task id. Gallery follows the system
# language, so pin English. M5 turns on the Car Market promotion; the later launcher start reuses
# that activity.
SETUP = {
    GALLERY: lambda: dev.sh("shell", "cmd", "locale", "set-app-locales", GALLERY, "--locales", "en-US"),
    "M5": lambda: dev.sh("shell", "am", "start", "-W", "-n", f"{marketplace.PKG}/.MainActivity", "--ez", "promo", "true"),
}

TASKS = {
    # Native pages of the Manual Lab (Chinese UI labels are quoted as they appear on screen).
    "L1": (LAB, "In the Coati 手册实验室 (Manual Lab) app, open the 「实验档案」 page",
           lambda: "页面：实验档案" in texts(), ["native"]),
    "L2": (LAB, "In the Coati 手册实验室 (Manual Lab) app, search the library (资料库) for travel, open the result, add it to "
                "favorites, then open the favorites page (收藏) and confirm it is listed there",
           lambda: "页面：收藏" in texts() and "离线旅行清单" in texts(), ["native", "input", "cross-page"]),
    "L3": (LAB, "In the Coati 手册实验室 (Manual Lab) app, open the advanced settings and turn on the 「紧凑资料卡片」 switch",
           lambda: lab_checked("紧凑资料卡片"), ["native", "dependent"]),
    # Canvas Workbench: painted controls without any accessibility node.
    "W1": (LAB, "In the Coati 手册实验室 (Manual Lab) app, open Canvas Workbench, set the brush color to blue, turn on "
                "Show grid, then tap Save canvas",
           lambda: lab_pref("canvas_saved") == "blue|grid_on", ["no-tree", "dependent"]),
    "W2": (LAB, "In the Coati 手册实验室 (Manual Lab) app, open Canvas Workbench, hide the Sketch layer, then on the Export "
                "tab set the artwork title to Sunset study and export it as SVG",
           lambda: "sketch" in lab_pref_set("canvas_hidden_layers")
           and lab_pref("canvas_export") == "svg|Sunset study", ["no-tree", "input"]),
    # Mixed Order: native list, fields and button around painted stepper and toggle.
    "O1": (LAB, "In the Coati 手册实验室 (Manual Lab) app, open Mixed Order, choose Home emergency checklist, set Copies to 3, "
                "turn on Priority delivery, enter Alex Chen as the recipient and Leave at the door as the "
                "delivery note, then submit the order",
           lambda: lab_pref("order_submitted") == "emergency|3|true|Alex Chen|Leave at the door",
           ["mixed", "input"]),
    # A system permission dialog in front of the app (the benchmark runs in TRUST mode, where the
    # agent may answer it; other modes hand it to the user).
    "P1": (LAB, "In the Coati 手册实验室 (Manual Lab) app, open Import Contacts and import my contacts",
           lambda: lab_pref("contacts_permission") == "granted", ["native", "system-dialog", "cross-app"]),
    # Flutter Gallery.
    "G1": (GALLERY, "In the Flutter Gallery app, open Shrine, sign in with username alex and password secret1, "
                    "add the Gatsby hat and the Blue stone mug to the cart, then open the cart",
           lambda: gallery_cart_has("Gatsby hat", "Blue stone mug"), ["flutter", "input", "cross-page"]),
    "G2": (GALLERY, "In the Flutter Gallery app, open Settings and set the Theme to Dark",
           lambda: gallery_theme_is("Dark"), ["flutter", "dependent"]),
    # bilibili in guest mode: a real app with dialogs, feeds and partial trees.
    "B1": (BILI, "In the bilibili app, search for minecraft and open the first video in the search results",
           None, ["mixed", "input"]),
    "B2": (BILI, "In the bilibili app, open the Popular (热门) page and open the first video listed there",
           None, ["mixed"]),
    "B3": (BILI, "In the bilibili app, open its Settings and turn on dark mode (night mode)",
           None, ["mixed", "dependent"]),
}
# Car Market (test-apps/marketplace): long loops of open detail, read, go back. These checks score
# Coati's answer (see marketplace.py), so run_app_bench.py passes them the answer.
TASKS.update({tid: (marketplace.PKG, text, marketplace.answer_check(tid),
                    ["native", "repetitive", "answer"] + (["interrupt"] if tid == "M5" else []))
              for tid, (_, _, text) in marketplace.TASKS.items()})
