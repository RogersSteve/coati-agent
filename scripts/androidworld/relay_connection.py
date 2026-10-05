"""Owned loopback relay and explicit, non-overwriting private ADB reverse lease."""
import hashlib
import json
import os
from pathlib import Path
import selectors
import subprocess
import uuid


class ReverseLease:
    def __init__(self, connection, device_port, host_port, android_id, run=None):
        c = connection
        if c["adb_server_port"] in (5037, 5038) or c["serial"] != f'emulator-{c["console_port"]}':
            raise ValueError("Explicit private ADB and emulator serial required")
        if any(type(p) is not int or not 1024 <= p <= 65535 for p in (device_port, host_port)):
            raise ValueError("Unprivileged relay ports required")
        if device_port in [c[k] for k in ("adb_server_port", "console_port", "device_adb_port", "grpc_port")]:
            raise ValueError("Relay port conflicts with emulator connection")
        self.adb = [c["adb_path"], "-H", "127.0.0.1", "-P", str(c["adb_server_port"]), "-s", c["serial"]]
        self.serial, self.android_id = c["serial"], android_id
        self.source, self.target = f"tcp:{device_port}", f"tcp:{host_port}"
        self.run = run or self._run
        self.owned = False
        self.boot_id = None

    def _run(self, *args):
        result = subprocess.run(self.adb+list(args), capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise RuntimeError("Private ADB reverse command failed")
        return result.stdout.strip()

    def identity(self):
        if self.run("get-serialno") != self.serial or self.run("shell", "settings", "get", "secure", "android_id") != self.android_id:
            raise RuntimeError("Relay device identity mismatch")
        boot = self.run("shell", "cat", "/proc/sys/kernel/random/boot_id")
        if not boot:
            raise RuntimeError("Missing device boot identity")
        return boot

    def current(self):
        mappings = [line.split()[-2:] for line in self.run("reverse", "--list").splitlines() if line.strip()]
        matches = [target for source, target in mappings if source == self.source]
        if len(matches) > 1:
            raise RuntimeError("Ambiguous reverse mapping")
        return matches[0] if matches else None

    def ensure(self):
        boot = self.identity()
        current = self.current()
        if current is not None and (not self.owned or current != self.target):
            raise RuntimeError("Refusing another reverse mapping")
        if current is None:
            self.run("reverse", "--no-rebind", self.source, self.target)
            self.owned = True
        if self.current() != self.target:
            raise RuntimeError("Reverse mapping verification failed")
        self.boot_id = boot

    def close(self):
        if self.owned:
            self.identity()
            if self.current() == self.target:
                self.run("reverse", "--remove", self.source)
            self.owned = False


class RelaySession:
    def __init__(self, connection, key, config, android_id):
        if set(config) != {"python", "device_port", "metrics_directory"}:
            raise ValueError("Unexpected relay configuration")
        self.connection, self.config, self.android_id = connection, config, android_id
        self.auth_digest = hashlib.sha256(("Bearer "+key).encode()).hexdigest()
        self.instance = uuid.uuid4().hex
        self.process = None
        self.reverse = None
        self.port = None
        self.metrics_path = Path(config["metrics_directory"])/(self.instance+".jsonl")

    @property
    def endpoint(self):
        return f'http://127.0.0.1:{self.config["device_port"]}/api'

    def start(self):
        try:
            self.process = subprocess.Popen([self.config["python"], str(Path(__file__).with_name("local_relay.py")),
                "--port", "0", "--metrics", str(self.metrics_path)], stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                env={"PATH": os.defpath, "PYTHONNOUSERSITE": "1", "LANG": "C.UTF-8"})
            self.process.stdin.write(json.dumps({"authorization_sha256": self.auth_digest, "instance": self.instance, "device_port": self.config["device_port"]})+"\n")
            self.process.stdin.close()
            with selectors.DefaultSelector() as sel:
                sel.register(self.process.stdout, selectors.EVENT_READ)
                if not sel.select(15):
                    raise RuntimeError("Relay startup timeout")
            ready = json.loads(self.process.stdout.readline())
            if ready.get("ready") is not True or ready.get("host") != "127.0.0.1" or ready.get("instance") != self.instance:
                raise RuntimeError("Relay identity mismatch")
            self.port = ready["port"]
            self.reverse = ReverseLease(self.connection, self.config["device_port"], self.port, self.android_id)
            self.ensure()
            return self
        except BaseException:
            self.close()
            raise

    def ensure(self):
        if self.process is None or self.process.poll() is not None:
            raise RuntimeError("Owned relay is not running")
        self.reverse.ensure()

    def metadata(self):
        return {"route": "loopback_adb_reverse_host_https", "upstream": "https://openrouter.ai",
                "endpoint": self.endpoint, "host_port": self.port, "serial": self.connection["serial"],
                "adb_server_port": self.connection["adb_server_port"], "instance": self.instance,
                "metrics_path": str(self.metrics_path), "extra_retries": 0, "TLS": "host_standard_CA_and_hostname"}

    def close(self):
        try:
            if self.reverse:
                self.reverse.close()
        finally:
            if self.process is not None:
                if self.process.poll() is None:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        self.process.kill()  # Only the child created by this instance.
                        self.process.wait(timeout=5)
                if self.process.stdout:
                    self.process.stdout.close()
