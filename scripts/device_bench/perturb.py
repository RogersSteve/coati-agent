"""Perturbations for robustness runs: conditions a deployed agent meets that clean benchmark states
do not have. run_app_bench.py applies the one named by BENCH_PERTURB to every run:

  dialog@N     the Interrupter dialog (test-apps/interrupter) appears when Coati starts step N
  dialog@NxK   the same dialog, closed only by the K-th dismissal (a large K cannot be dismissed)
  notree       Coati perceives screens without the accessibility tree (withhold_tree), as on
               custom-drawn interfaces; actions still use the platform's input services
  display      font scale 1.3 and dark mode during the run, restored afterwards

Other harnesses (such as an AndroidWorld adapter) can use the same functions directly.
"""
import re
import threading
import time

import adbdev as dev

INTERRUPT_PKG = "bot.coati.fixture.interrupt"
INTERRUPT_ACTIVITY = f"{INTERRUPT_PKG}/.PopupActivity"
DISPLAY_FONT_SCALE = 1.3


def show_dialog(dismissals=1):
    """Covers the app in front with the Interrupter dialog."""
    dev.sh("shell", "am", "start", "-n", INTERRUPT_ACTIVITY, "--ei", "dismissals", str(dismissals))


def close_dialog():
    dev.force_stop(INTERRUPT_PKG)


def set_display(font_scale, dark):
    dev.sh("shell", "settings", "put", "system", "font_scale", str(font_scale))
    dev.sh("shell", "cmd", "uimode", "night", "yes" if dark else "no")


class Perturbation:
    def __init__(self, spec):
        self.spec = spec or ""
        self.kind = self.spec.split("@")[0]
        self.step = self.dismissals = None
        if self.kind == "dialog":
            m = re.fullmatch(r"dialog@(\d+)(?:x(\d+))?", self.spec)
            if not m:
                raise SystemExit(f"BENCH_PERTURB: expected dialog@N or dialog@NxK, got {self.spec!r}")
            self.step, self.dismissals = int(m.group(1)), int(m.group(2) or 1)
        elif self.kind not in ("", "notree", "display"):
            raise SystemExit(f"BENCH_PERTURB: unknown perturbation {self.spec!r}")
        self._stop = threading.Event()
        self._watcher = None
        self.injected_at = None

    def configure_extras(self):
        """CONFIGURE_AGENT extras; set on every run because the configuration persists on the device."""
        return ["--ez", "withhold_tree", "true" if self.kind == "notree" else "false"]

    def before_task(self):
        """Called before the target app is launched for a run."""
        self.injected_at = None
        if self.kind == "display":
            set_display(DISPLAY_FONT_SCALE, dark=True)

    def task_started(self):
        """Called right after the task was sent to Coati (logcat was cleared just before)."""
        if self.kind != "dialog":
            return
        self._stop.clear()
        self._watcher = threading.Thread(target=self._inject_at_step, daemon=True)
        self._watcher.start()

    def finish(self):
        """Undoes the perturbation after a run and describes what was applied."""
        self._stop.set()
        if self._watcher:
            self._watcher.join(timeout=10)
            self._watcher = None
        if self.kind == "dialog":
            close_dialog()
        if self.kind == "display":
            set_display(1.0, dark=False)
        if not self.kind:
            return None
        return {"spec": self.spec, "injected_at_s": self.injected_at}

    def _inject_at_step(self):
        started = time.monotonic()
        marker = f"── Step {self.step}/"
        while not self._stop.is_set():
            if marker in dev.sh("logcat", "-d", "-s", "CoatiLoop", timeout=20):
                show_dialog(self.dismissals)
                self.injected_at = round(time.monotonic() - started, 1)
                print(f"--- perturbation: dialog shown at step {self.step} ({self.dismissals} dismissals)", flush=True)
                return
            self._stop.wait(1.0)
