"""Explicit native connections and reuse of the reviewed device-bench helpers."""
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import socket
import stat
import subprocess
import sys
import time

from agent import EnvironmentFailure
from protocol import digest


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args]).decode().strip()


def source_manifest(aw, host, expected_host_diff):
    aw, host = Path(aw).resolve(), Path(host).resolve()
    if git(aw, "rev-parse", "HEAD") != "6e4faf91094e72b9f51276b9aa394cbdc057c9a7" or git(aw, "diff") or git(aw, "diff", "--cached"):
        raise ValueError("AW must use the frozen environment commit and clean tracked source")
    diff = subprocess.check_output(["git", "-C", str(host.parent.parent), "diff"])
    actual = hashlib.sha256(diff).hexdigest()
    if actual != expected_host_diff:
        raise ValueError("Device-bench source diff differs from the explicit reviewed hash")
    local = Path(__file__).parent
    if git(host.parent.parent, "diff", "--cached") or git(local.parent.parent, "diff", "--cached"):
        raise ValueError("Source indexes must be empty")
    if git(host.parent.parent, "rev-parse", "HEAD") != git(local.parent.parent, "rev-parse", "HEAD"):
        raise ValueError("Host and harness must use the same Coati commit")
    if diff or git(local.parent.parent, "diff"):
        raise ValueError("Execute only committed Coati source; preserve dirty historical checkouts separately")
    untracked = git(local.parent.parent, "ls-files", "--others", "--exclude-standard", "--", "scripts/androidworld", "scripts/device_bench")
    if any(Path(n).suffix in (".py", ".sh") for n in untracked.splitlines()):
        raise ValueError("Untracked executable helper source refused")
    import android_world
    if Path(android_world.__file__).resolve().parent != aw/"android_world":
        raise ValueError("Actual AW import differs from the pinned checkout")
    files = {n: hashlib.sha256((host/n).read_bytes()).hexdigest()
             for n in ("adbdev.py", "run_app_bench.py", "perturb.py")}
    local = Path(__file__).parent
    return {"AW_import": str(Path(android_world.__file__).resolve()), "AW_HEAD": git(aw, "rev-parse", "HEAD"), "AW_path": str(aw),
            "host_HEAD": git(host.parent.parent, "rev-parse", "HEAD"),
            "host_path": str(host), "host_full_diff_sha256": actual, "host_files_sha256": files,
            "harness_HEAD": git(local.parent.parent, "rev-parse", "HEAD"),
            "harness_files_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                      for p in sorted(local.glob("*.py"))}}


def validate_connection(c, shard_count):
    ports = [c[k] for k in ("adb_server_port", "console_port", "device_adb_port", "grpc_port")]
    if len(set(ports)) != 4 or any(type(p) is not int or not 1 <= p <= 65535 for p in ports):
        raise ValueError("Distinct explicit ports required")
    if c["adb_server_port"] in (5037, 5038) or c["console_port"] % 2 or c["serial"] != f'emulator-{c["console_port"]}' or c["device_adb_port"] != c["console_port"] + 1:
        raise ValueError("Private ADB/explicit emulator serial required")
    if shard_count > 1 or c.get("read_only", False):
        raise ValueError("Multi-instance/read-only cold restart blocked: reviewed host helper does not support -read-only; do not launch shared writable AVDs")
    return {k: c[k] for k in ("adb_path", "adb_server_port", "console_port", "device_adb_port", "grpc_port", "serial", "avd_name")}


def load_credentials(path):
    path = Path(path)
    pstat, dstat = path.lstat(), path.parent.lstat()
    if not stat.S_ISREG(pstat.st_mode) or stat.S_IMODE(pstat.st_mode) != 0o600 or pstat.st_uid != os.getuid() or not stat.S_ISDIR(dstat.st_mode) or dstat.st_uid != os.getuid() or stat.S_IMODE(dstat.st_mode) != 0o700:
        raise ValueError("Credential file/parent must be owned regular0600/directory0700")
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW)) as f:
        data = json.load(f)
    def text(name):
        if not isinstance(data[name], str) or not data[name].strip():
            raise ValueError("Invalid credential format")
        return data[name].strip()
    # OpenRouter serves both the models and Jev; the google provider uses the official
    # Gemini API and Jev directly, each with its own key.
    if set(data) == {"openrouter_api_key"}:
        key = text("openrouter_api_key")
        return {"provider": "openrouter", "llm_key": key, "jev_key": key}
    if set(data) == {"google_api_key", "jev_api_key"}:
        return {"provider": "google", "llm_key": text("google_api_key"), "jev_key": text("jev_api_key")}
    raise ValueError("Invalid credential format")


def load_host(path, c, credentials, models, perturbation, routing=None):
    root = Path(c["workspace"])
    os.environ.update(ADB=c["adb_path"], ADB_SERVER_SOCKET=f'tcp:127.0.0.1:{c["adb_server_port"]}',
        ANDROID_ADB_SERVER_PORT=str(c["adb_server_port"]), ANDROID_SERIAL=c["serial"],
        EMULATOR_GRPC_PORT=str(c["grpc_port"]), EMULATOR_AVD=c["avd_name"],
        EMULATOR=str(root/"sdk/emulator/emulator"), ANDROID_AVD_HOME=str(root/"avd"),
        ANDROID_USER_HOME=str(root/"android-user"),
        EMULATOR_EXTRA_ARGS=json.dumps(["-no-window", "-no-audio", "-gpu", "swiftshader_indirect"]),
        RELAY_KEY=credentials["llm_key"], JEV_KEY=credentials["jev_key"], RELAY_ENDPOINT=models["endpoint"],
        JEV_ENDPOINT=models.get("jev_endpoint", models["endpoint"]),
        LLM_PROVIDER=models.get("app_provider", "openai_compatible"), LLM_MODEL=models["reasoning"], FAST_MODEL=models["fast"],
        JEV_MODEL=models["jev"], BENCH_ENV_RECOVERY="0", BENCH_PERTURB=perturbation,
        BENCH_ROUTING=routing or "BALANCED")
    for name in ("BENCH_ROTATION", "BENCH_APK_CLASSIC", "BENCH_APK_BASELINE", "BENCH_APK_JEV", "BENCH_APK_JEVNOOCR"):
        os.environ.pop(name, None)
    sys.path.insert(0, str(Path(path).resolve()))
    bench = importlib.import_module("run_app_bench")
    if Path(bench.__file__).resolve() != (Path(path)/"run_app_bench.py").resolve() or not hasattr(bench, "stop_task_before_configuration"):
        raise ValueError("Reviewed host lifecycle v2 is required; no fallback")
    for name in ("adbdev", "perturb"):
        module = importlib.import_module(name)
        if Path(module.__file__).resolve() != (Path(path)/(name+".py")).resolve():
            raise ValueError("Mixed device-bench imports refused")
    if bench.ENV_RECOVERY_ENABLED:
        raise ValueError("Host environment replay must be disabled")
    return bench


class Bridge:
    def __init__(self, bench, connection, credentials):
        self.bench, self.dev, self.connection = bench, bench.dev, connection
        from relay_connection import as_credentials
        credentials = as_credentials(credentials)
        self.keys = sorted({credentials["llm_key"], credentials["jev_key"]}, key=len, reverse=True)
        self.log_cursor = None
        self.relay = None

    def redact(self, text):
        for key in self.keys:
            if key:
                text = text.replace(key, "[REDACTED]")
        return text

    def json_safe(self, value):
        return json.loads(self.redact(json.dumps(value, ensure_ascii=False, default=str)), parse_constant=lambda _: None)

    def verify_identity(self, expected_android_id, expected_apk, *, require_bindings=True):
        c = self.connection
        for port in (c["adb_server_port"], c["console_port"], c["grpc_port"]):
            with socket.create_connection(("127.0.0.1", port), timeout=2):
                pass
        def required(*args):
            output = self.dev.sh(*args).strip()
            if not output:
                raise EnvironmentFailure("Empty identity response")
            return output
        if required("emu", "avd", "name").splitlines()[0] != c["avd_name"] or required("shell", "settings", "get", "secure", "android_id") != expected_android_id:
            raise EnvironmentFailure("Device identity mismatch")
        apk = required("shell", "pm", "path", "bot.coati").removeprefix("package:")
        raw = self.dev.sh("exec-out", "cat", apk, binary=True)
        if hashlib.sha256(raw).hexdigest() != expected_apk:
            raise EnvironmentFailure("Installed APK differs from reviewed build")
        if require_bindings:
            self.check_bindings()

    def check_bindings(self):
        if self.relay is not None:
            self.relay.ensure()
        dump = self.dev.sh("shell", "dumpsys", "accessibility")
        if "Bound services:" not in dump:
            raise EnvironmentFailure("Cannot inspect bound services")
        bound = dump.split("Bound services:", 1)[1].split("Enabled services:", 1)[0]
        if "label=Coati," not in bound or "label=com.google.androidenv.accessibilityforwarder" not in bound or "Binding services:{}" not in dump or "Crashed services:{}" not in dump:
            raise EnvironmentFailure("Coati/forwarder not both actually bound")
        self.bench.prepare_device()

    def safe_clear(self):
        self.bench.stop_task_before_configuration()
        self.bench.clear_task_state()

    def begin_logs(self):
        if self.relay is not None:
            self.relay.ensure()
        self.log_cursor = self.bench.configuration_cursor()

    def run(self, goal, run_id, budget):
        self.begin_logs()
        self.bench.broadcast("bot.coati.debug.RUN_TASK", "--es", "task", goal, "--es", "run_id", run_id, "--ei", "max_steps", str(budget))

    def result(self, run_id, timeout):
        command = "if [ -f files/task_result.json ]; then cat files/task_result.json; else printf null; fi"
        raw = self.dev.sh("shell", "run-as", "bot.coati", "sh", "-c", self.bench.shell_quote(command), timeout=timeout)
        if not raw.strip():
            raise EnvironmentFailure("Cannot read TaskResult")
        try:
            result = json.loads(raw)
        except ValueError:
            # Atomic writer races cannot be assumed; malformed data is evidence.
            raise EnvironmentFailure("Malformed TaskResult") from None
        if result is not None and not isinstance(result, dict):
            raise EnvironmentFailure("Invalid TaskResult shape")
        return result if result and result.get("run_id") == run_id else None

    def stop(self):
        # Product STOP_TASK dismisses result dialogs; do not send BACK/HOME to task apps.
        self.bench.stop_task_before_configuration()

    def log(self):
        raw = self.dev.sh("logcat", "-b", "main", "-d", "-v", "epoch", "-s", *self.bench.LOG_TAGS, "CoatiManual", self.bench.CURSOR_TAG + ":I", timeout=60)
        if not raw.strip():
            raise EnvironmentFailure("Empty episode logcat")
        if self.log_cursor is not None:
            raw = "\n".join(self.bench.log_events_after(raw, self.log_cursor))
        return self.redact(raw)

    def capture(self, base):
        base.with_suffix(".log").write_text(self.log())
        self.dev.screenshot().save(base.with_suffix(".jpg"), quality=80)

    def manual_command(self, action, *extras):
        out = self.dev.sh("shell", "am", "broadcast", "-a", "bot.coati.debug."+action,
                          "-n", "bot.coati/.debug.DebugManualReceiver", *map(self.bench.shell_quote, extras))
        if "Broadcast completed" not in out:
            raise EnvironmentFailure("Manual broadcast failed")

    def manual_files(self):
        cmd = "if [ -d no_backup/app_manuals_v2 ]; then find no_backup/app_manuals_v2 -type f; fi; printf '\n__DONE__\n'"
        out = self.dev.sh("shell", "run-as", "bot.coati", "sh", "-c", self.bench.shell_quote(cmd))
        if not out.rstrip().endswith("__DONE__"):
            raise EnvironmentFailure("Manual inventory failed")
        result = {}
        for name in out.splitlines():
            if name == "__DONE__" or not name:
                continue
            if not re.fullmatch(r"no_backup/app_manuals_v2/[a-zA-Z0-9_./-]+", name) or ".." in name:
                raise EnvironmentFailure("Unsafe manual file name")
            data = self.dev.sh("exec-out", "run-as", "bot.coati", "cat", name, binary=True)
            if not data:
                raise EnvironmentFailure("Unreadable/empty manual file")
            result[name] = hashlib.sha256(data).hexdigest()
        return result

    def configure(self, settings, frozen=None):
        if self.relay is not None:
            self.relay.ensure()
        try:
            self.bench.configure(settings.mode)
        except Exception:
            raise EnvironmentFailure("Reviewed host configure failed; stop without replay") from None
        # Host classic/baseline retain OCR. Set these documented fields explicitly
        # through the official debug interface, without replacing helper code.
        cursor = self.bench.configuration_cursor()
        self.bench.broadcast("bot.coati.debug.CONFIGURE_AGENT", "--ez", "ocr", "true",
                             "--ez", "withhold_tree", "true" if settings.withhold_tree else "false")
        self.bench.wait_for_configuration(settings.mode, cursor)
        cursor = self.bench.configuration_cursor()
        self.manual_command("SET_MANUAL_MODE", "--es", "mode", settings.manual)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            logs = self.dev.sh("logcat", "-b", "main", "-d", "-v", "epoch", "-s", "CoatiManual:I", self.bench.CURSOR_TAG + ":I", "*:S")
            if any(f"Debug manual mode set to {settings.manual};" in e for e in self.bench.log_events_after(logs, cursor)):
                break
            time.sleep(0.5)
        else:
            raise EnvironmentFailure("No fresh manual-mode receiver acknowledgement")
        self.check_bindings()
        if settings.manual == "READ_ONLY":
            if frozen is None or not isinstance(frozen.get("files"), dict) or digest(self.manual_files()) != frozen["files_sha256"]:
                raise EnvironmentFailure("Required trained/frozen manuals are missing or differ")
        return {"manual": settings.manual, "mode": settings.mode, "verify": settings.verify, "ocr": True,
                "withhold_tree": settings.withhold_tree}

    def ocr_evidence(self, settings):
        from observability import ocr_evidence
        pid = self.dev.sh("shell", "pidof", "bot.coati").strip().split()
        boot = self.bench.boot_identity()
        logs = self.dev.sh("logcat", "-b", "main", "-d", "-v", "epoch", "-s",
                           "CoatiService", "CoatiJev", "OcrEngines", "*:S")
        return ocr_evidence(logs, pid, boot, settings.ocr, settings.jev)

    def wait_manual_update(self):
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            logs = self.dev.sh("logcat", "-b", "main", "-d", "-v", "epoch", "-s", "CoatiManual", self.bench.CURSOR_TAG + ":I", "*:S")
            fresh = "\n".join(self.bench.log_events_after(logs, self.log_cursor))
            if re.search(r"update stage=\S+ result=(SAVED|NO_CHANGE)|enqueue accepted=false", fresh):
                return "fresh_update_log_observed_not_a_pending_job_join"
            time.sleep(3)
        raise EnvironmentFailure("Manual update did not settle; do not freeze incomplete learning")


def connect(c):
    from android_env import loader
    from android_env.components import config_classes as cc
    from android_world.env import android_world_controller as awc, interface, env_launcher
    root = Path(c["workspace"])
    adb = cc.AdbControllerConfig(adb_path=c["adb_path"], adb_server_port=c["adb_server_port"], device_name=c["serial"])
    launcher = cc.EmulatorLauncherConfig(emulator_console_port=c["console_port"], adb_port=c["device_adb_port"], grpc_port=c["grpc_port"], avd_name=c["avd_name"], android_sdk_root=str(root/"sdk"), android_avd_home=str(root/"avd"), emulator_path=str(root/"sdk/emulator/emulator"), tmp_dir=str(root/"tmp/android-env"))
    cfg = cc.AndroidEnvConfig(task=cc.FilesystemTaskConfig(path=awc._write_default_task_proto()), simulator=cc.EmulatorConfig(adb_controller=adb, emulator_launcher=launcher))
    raw = loader.load(cfg)
    try:
        controller = awc.AndroidWorldController(raw)
        env = interface.AsyncAndroidEnv(controller)
        env_launcher.setup_env(env, emulator_setup=False, freeze_datetime=True)
        return env
    except BaseException:
        raw.close()
        raise


def restart_connection(env, bridge, c, settings, frozen, android_id, apk_sha256):
    """Targeted reviewed helper, then recreate the official native connection."""
    validate_connection(c, 1)
    bridge.stop()
    env.close()
    bridge.dev.cold_restart_emulator(c["avd_name"])
    # The official connection starts the forwarder after a cold boot. Verify
    # hardware/APK identity first, then require both bindings after that setup.
    bridge.verify_identity(android_id, apk_sha256, require_bindings=False)
    new_env = connect(c)
    try:
        bridge.verify_identity(android_id, apk_sha256)
        bridge.configure(settings, frozen)
        bridge.check_bindings()
        return new_env
    except BaseException:
        new_env.close()
        raise
