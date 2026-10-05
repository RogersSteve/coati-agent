"""Minimal ADB driver for device benchmarks. Honors ANDROID_SERIAL like adb itself."""
import io
import json
import os
import re
import shutil
import socket
import subprocess
import time

from PIL import Image

ADB = (os.environ.get("ADB") or shutil.which("adb")
       or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"))
PNG_SIGNATURE = bytes([0x89, 0x50, 0x4E, 0x47])


def adb_command(*args):
    """Build a native ADB command with explicitly configured connection values."""
    command = [ADB]
    endpoint = os.environ.get("ADB_SERVER_SOCKET", "")
    port = os.environ.get("ANDROID_ADB_SERVER_PORT", "")
    if endpoint:
        match = re.fullmatch(r"tcp:([^:]+):(\d+)", endpoint)
        if not match or match.group(1) not in ("localhost", "127.0.0.1"):
            raise RuntimeError("Bench ADB server must use an explicit local TCP endpoint")
        host, endpoint_port = match.groups()
        if port and port != endpoint_port:
            raise RuntimeError("Conflicting private ADB server ports")
        command += ["-H", host, "-P", endpoint_port]
    elif port:
        command += ["-H", "127.0.0.1", "-P", str(int(port))]
    serial = os.environ.get("ANDROID_SERIAL")
    if serial:
        command += ["-s", serial]
    return command + list(args)


# Active only during the reviewed STOP path. Never receives argv or output.
STOP_DIAGNOSTIC_OBSERVER = None


def sh(*args, timeout=30, binary=False):
    started = time.monotonic()
    try:
        r = subprocess.run(adb_command(*args), capture_output=True, timeout=timeout)
        if STOP_DIAGNOSTIC_OBSERVER is not None:
            STOP_DIAGNOSTIC_OBSERVER("adb", rc=r.returncode, timeout=False,
                                     elapsed_s=time.monotonic() - started)
    except subprocess.TimeoutExpired:
        if STOP_DIAGNOSTIC_OBSERVER is not None:
            STOP_DIAGNOSTIC_OBSERVER("adb", rc=None, timeout=True,
                                     elapsed_s=time.monotonic() - started)
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


def _wait_for_emulator_ports(ports, diagnostic_path, timeout_s=120):
    """Bounded native bind checks; TIME_WAIT is not permission to ignore conflicts.

    Each successful IPv4/IPv6 probe stays open until the whole round finishes.
    IPv6 may be unavailable locally; only explicit unsupported-family/protocol
    or missing-loopback errors skip that family. All other errors fail closed.
    Diagnostics contain only port, family, errno, elapsed time and outcomes.
    """
    import errno
    from contextlib import ExitStack
    started = time.monotonic()
    deadline = started + timeout_s
    probes = []
    unsupported = {errno.EAFNOSUPPORT, errno.EPROTONOSUPPORT}

    def record(result):
        with open(diagnostic_path, "w", encoding="utf-8") as file:
            json.dump({"timeout_s": timeout_s, "elapsed_s": time.monotonic() - started,
                       "result": result, "probes": probes}, file, indent=2)

    while time.monotonic() < deadline:
        occupied = False
        with ExitStack() as stack:
            for port in ports:
                for family, address, label in ((socket.AF_INET, "127.0.0.1", "IPv4"),
                                               (socket.AF_INET6, "::1", "IPv6")):
                    row = {"port": port, "address_family": label,
                           "elapsed_s": time.monotonic() - started, "errno": None}
                    try:
                        sock = stack.enter_context(socket.socket(family, socket.SOCK_STREAM))
                        sock.bind((address, port))
                        row["result"] = "available"
                    except OSError as error:
                        row["errno"] = error.errno
                        if family == socket.AF_INET6 and error.errno in unsupported | {errno.EADDRNOTAVAIL}:
                            row["result"] = "IPv6_unavailable"
                        elif error.errno == errno.EADDRINUSE:
                            row["result"] = "occupied"
                            occupied = True
                        else:
                            row["result"] = "unexpected_error"
                            probes.append(row)
                            record("failed")
                            raise RuntimeError("Emulator port probe failed; inspect " + diagnostic_path) from error
                    probes.append(row)
            if not occupied:
                record("available")
                return
        record("waiting")
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(1, remaining))
    record("timeout")
    raise RuntimeError("Emulator ports remain occupied; refusing to launch; inspect " + diagnostic_path)


def cold_restart_emulator(avd):
    """Restart only the selected AVD, preserving private ADB and emulator ports.

    Require ANDROID_SERIAL, an explicit local ADB server, EMULATOR_GRPC_PORT,
    and EMULATOR (or ANDROID_HOME/ANDROID_SDK_ROOT). Optional
    EMULATOR_EXTRA_ARGS is a JSON list of display/resource options.
    """
    serial = os.environ.get("ANDROID_SERIAL", "")
    match = re.fullmatch(r"emulator-(\d+)", serial)
    if not match or not (os.environ.get("ADB_SERVER_SOCKET") or
                         os.environ.get("ANDROID_ADB_SERVER_PORT")):
        raise RuntimeError("Restart requires an explicit serial and ADB server")
    console = int(match.group(1))
    grpc = int(os.environ.get("EMULATOR_GRPC_PORT", "0"))
    if console % 2 or not 5554 <= console <= 5682 or not 1 <= grpc <= 65535:
        raise RuntimeError("Invalid console or explicitly configured gRPC port")
    if grpc in (console, console + 1):
        raise RuntimeError("Emulator ports must be distinct")
    suffix = ".exe" if os.name == "nt" else ""
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    executable = os.environ.get("EMULATOR") or (
        os.path.join(sdk, "emulator", "emulator" + suffix) if sdk else "")
    if not executable or not os.path.isfile(executable):
        raise RuntimeError("Configured emulator executable is unavailable")
    extra = json.loads(os.environ.get("EMULATOR_EXTRA_ARGS", "[]"))
    if not isinstance(extra, list) or not all(isinstance(v, str) for v in extra):
        raise RuntimeError("EMULATOR_EXTRA_ARGS must be a JSON string list")
    allowed_flags = {"-no-window", "-no-audio", "-no-boot-anim"}
    allowed_values = {"-gpu", "-memory", "-cores"}
    i = 0
    while i < len(extra):
        option = extra[i]
        if option in allowed_flags:
            i += 1
        elif option in allowed_values and i + 1 < len(extra) and not extra[i + 1].startswith("-"):
            i += 2
        else:
            raise RuntimeError("Only display/resource emulator options are allowed")
    # Validate the connection configuration and AVD before any shutdown.
    command = adb_command("emu", "avd", "name")
    server_port = int(command[command.index("-P") + 1])
    if not 1 <= server_port <= 65535 or server_port in (console, console + 1, grpc):
        raise RuntimeError("ADB server and emulator ports must be valid and distinct")
    name = sh("emu", "avd", "name").splitlines()
    if not name or name[0].strip() != avd:
        raise RuntimeError("Selected emulator does not match the requested AVD")
    response = subprocess.run(adb_command("emu", "kill"), capture_output=True, timeout=30)
    if response.returncode or b"OK" not in response.stdout:
        raise RuntimeError("Targeted emulator console shutdown failed")
    ports = (console, console + 1, grpc)
    import tempfile
    avd_home = os.environ.get("ANDROID_AVD_HOME")
    release_dir = os.path.join(os.path.dirname(os.path.abspath(avd_home)), "logs") if avd_home else tempfile.gettempdir()
    os.makedirs(release_dir, exist_ok=True)
    fd, release_log = tempfile.mkstemp(prefix=f"emulator-port-release-{console}-", suffix=".json", dir=release_dir)
    os.close(fd)
    _wait_for_emulator_ports(ports, release_log)
    env = dict(os.environ)
    # android-env may remove this parent variable; restore the validated port
    # only for the emulator child, which uses it for native ADB registration.
    env["ANDROID_ADB_SERVER_PORT"] = str(server_port)
    emu_dir = os.path.dirname(executable)
    if os.name == "nt":
        env["PATH"] = os.pathsep.join([emu_dir, os.path.join(emu_dir, "lib64"),
                                     os.path.join(emu_dir, "lib64", "qt", "lib"), env.get("PATH", "")])
    command = [executable, "-avd", avd, "-port", str(console),
               "-grpc", str(grpc), "-no-snapshot-load", "-no-snapshot-save", *extra]
    # Emulator startup diagnostics contain no model configuration or environment.
    import tempfile
    for name in ("RELAY_KEY", "JEV_KEY"):
        env.pop(name, None)  # The emulator child does not need model credentials.
    avd_home = env.get("ANDROID_AVD_HOME")
    log_dir = os.path.join(os.path.dirname(os.path.abspath(avd_home)), "logs") if avd_home else tempfile.gettempdir()
    os.makedirs(log_dir, exist_ok=True)
    fd, log_path = tempfile.mkstemp(prefix=f"emulator-cold-{console}-", suffix=".log", dir=log_dir)
    process = None

    def diagnostic(stage, error_type=None):
        with open(log_path+".json", "w", encoding="utf-8") as out:
            json.dump({"stage": stage, "error_type": error_type, "command": command,
                       "serial": serial, "adb_server_port": server_port, "avd": avd,
                       "console_port": console, "device_adb_port": console+1, "grpc_port": grpc,
                       "pid": process.pid if process is not None else None,
                       "returncode": process.poll() if process is not None else None,
                       "stdout_stderr_log": log_path, "port_release_diagnostics": release_log}, out, indent=2)

    try:
        with os.fdopen(fd, "wb") as log:
            # A POSIX emulator must outlive the host invocation's process group.
            # Keep Windows process creation behavior unchanged.
            process = subprocess.Popen(command, cwd=emu_dir, env=env,
                                       stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=(os.name == "posix"))
        diagnostic("spawned")
        time.sleep(0.5)
        if process.poll() is not None:
            raise RuntimeError(f"Emulator exited during startup; inspect {log_path}")
        wait_for_boot()
        if process.poll() is not None:
            raise RuntimeError(f"Emulator exited before boot verification; inspect {log_path}")
        name = sh("emu", "avd", "name").splitlines()
        if not name or name[0].strip() != avd or not healthy() or sh("shell", "getprop", "sys.boot_completed").strip() != "1":
            raise RuntimeError(f"Restarted emulator identity/health check failed; inspect {log_path}")
        diagnostic("boot_and_avd_health_verified")
    except BaseException as error:
        diagnostic("failed", type(error).__name__)
        raise




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
    """Read nonempty nodes through Coati without disrupting other services."""
    nodes = coati_ui_nodes()
    if not nodes:
        raise RuntimeError("Coati node dump unavailable or empty; no UIAutomator fallback")
    return nodes


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
    """Fail closed: UIAutomator may interrupt enabled accessibility services."""
    raise RuntimeError("UIAutomator is disabled for isolated Coati benchmarks")


def key(name):
    code = {"BACK": "KEYCODE_BACK", "HOME": "KEYCODE_HOME", "ENTER": "KEYCODE_ENTER"}.get(name.upper(), name)
    sh("shell", "input", "keyevent", code)


def launch(pkg):
    sh("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")


def force_stop(pkg):
    sh("shell", "am", "force-stop", pkg)
