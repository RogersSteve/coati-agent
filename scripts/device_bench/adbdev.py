"""Minimal ADB driver for device benchmarks. Honors ANDROID_SERIAL like adb itself."""
import io
import json
import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image

ADB = (os.environ.get("ADB") or shutil.which("adb")
       or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"))
PNG_SIGNATURE = bytes([0x89, 0x50, 0x4E, 0x47])


def sh(*args, timeout=30, binary=False):
    try:
        r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        # A hung device must not crash a multi-hour benchmark; callers treat empty output as failure.
        return b"" if binary else ""
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def healthy():
    return sh("shell", "echo", "ok", timeout=15).strip() == "ok"


def is_emulator():
    return sh("shell", "getprop", "ro.kernel.qemu").strip() == "1"


def wait_for_boot():
    sh("wait-for-device", timeout=300)
    for _ in range(120):
        if sh("shell", "getprop", "sys.boot_completed", timeout=15).strip() == "1":
            break
        time.sleep(3)
    time.sleep(5)


def lock_rotation(rotation):
    """Pin the display rotation (0-3). Some dev boards reset it to auto-rotate on every boot."""
    sh("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    sh("shell", "settings", "put", "system", "user_rotation", str(rotation))


def cold_restart_emulator(avd):
    """Kill and relaunch an emulator (with its DLL directories on PATH), then wait for boot."""
    emu_dir = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "emulator")
    subprocess.run(["taskkill", "/F", "/IM", "qemu-system-x86_64.exe"], capture_output=True)
    subprocess.run(["taskkill", "/F", "/IM", "emulator.exe"], capture_output=True)
    time.sleep(5)
    env = dict(os.environ)
    env["PATH"] = os.pathsep.join([emu_dir, os.path.join(emu_dir, "lib64"),
                                   os.path.join(emu_dir, "lib64", "qt", "lib"), env["PATH"]])
    subprocess.Popen([os.path.join(emu_dir, "emulator.exe"), "-avd", avd, "-no-boot-anim", "-no-snapshot-load"],
                     cwd=emu_dir, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    wait_for_boot()
    time.sleep(5)


def reboot():
    sh("reboot", timeout=120)
    wait_for_boot()


def screenshot() -> Image.Image:
    for _ in range(3):
        raw = sh("exec-out", "screencap", "-p", binary=True)
        # Multi-display devices print a warning before the PNG data on stdout; skip to the header.
        start = raw.find(PNG_SIGNATURE)
        try:
            return Image.open(io.BytesIO(raw[max(start, 0):])).convert("RGB")
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("screencap failed")


def size():
    """Current (rotated) size of the default display; `wm size` reports the unrotated panel."""
    m = re.search(r"cur=(\d+)x(\d+)", sh("shell", "dumpsys", "window", "displays"))
    if not m:
        m = re.search(r"(\d+)x(\d+)", sh("shell", "wm", "size"))
    return int(m.group(1)), int(m.group(2))


def ui_nodes():
    """Visible nodes: list of dicts with text/desc/cls/checked/bounds.

    Read through Coati's own accessibility service (debug build): `uiautomator dump` suppresses
    accessibility services while it runs, and afterwards Coati's screenshots failed with error 1
    until its process restarted, so every task after a UI-checked one broke. uiautomator remains
    the fallback when Coati is not running.
    """
    nodes = coati_ui_nodes()
    if nodes is not None:
        return nodes
    print("... Coati node dump unavailable; falling back to uiautomator", flush=True)
    return uiautomator_nodes()


def coati_ui_nodes():
    sh("shell", "run-as", "bot.coati", "rm", "-f", "files/ui_nodes.json")  # never read an older dump
    sh("shell", "am", "broadcast", "-a", "bot.coati.debug.DUMP_NODES", "-n", "bot.coati/.debug.DebugAgentReceiver")
    for _ in range(10):
        time.sleep(0.3)
        raw = sh("shell", "run-as", "bot.coati", "cat", "files/ui_nodes.json")
        try:
            data = json.loads(raw)
        except ValueError:
            continue
        if not data.get("ok"):
            return None
        return [{"text": n["text"], "desc": n["desc"], "cls": n["cls"], "checked": n["checked"],
                 "bounds": tuple(n["bounds"])} for n in data["nodes"]]
    return None


def uiautomator_nodes():
    out = sh("exec-out", "uiautomator", "dump", "/dev/tty", timeout=20)
    xml = out[out.find("<?xml"): out.rfind(">") + 1] if "<?xml" in out else ""
    if not xml:
        return []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    nodes = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        if x2 <= x1 or y2 <= y1:
            continue
        nodes.append({
            "text": n.get("text", ""), "desc": n.get("content-desc", ""),
            "cls": n.get("class", "").split(".")[-1], "checked": n.get("checked") == "true",
            "bounds": (x1, y1, x2, y2),
        })
    return nodes


def key(name):
    code = {"BACK": "KEYCODE_BACK", "HOME": "KEYCODE_HOME", "ENTER": "KEYCODE_ENTER"}.get(name.upper(), name)
    sh("shell", "input", "keyevent", code)


def launch(pkg):
    sh("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")


def force_stop(pkg):
    sh("shell", "am", "force-stop", pkg)
