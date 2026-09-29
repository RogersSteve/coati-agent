# Device benchmarks

End-to-end benchmark of the real Coati app on a device or emulator. It drives an installed
**debug** build through the debug-only `DebugAgentReceiver` (`CONFIGURE_AGENT`, `RUN_TASK`,
`STOP_TASK`, protected by `android.permission.DUMP`, so only `adb shell` can send them) and reads
`files/task.json` for the final status. Every run clears the target app's data first.

## Setup

- Python 3 with Pillow; `adb` on `PATH`, in `$ADB`, or at the Android SDK default path.
- One connected device, or `ANDROID_SERIAL` set to choose one.
- Coati debug build installed (`./gradlew :app:installDebug`). No manual setup is needed: the
  script grants the overlay permission, enables the accessibility service, configures the models
  and marks onboarding complete through the debug receiver.
- Target apps: `Coati 手册实验室` (`test-apps/living-manual-lab`, v1 flavor:
  `./gradlew :test-apps:living-manual-lab:installV1Debug`) and optionally bilibili
  (`tv.danmaku.bili`, guest mode).

| Variable | Meaning |
|---|---|
| `RELAY_ENDPOINT` | Model endpoint base URL (provider or OpenAI-compatible relay) |
| `RELAY_KEY` | API key for that endpoint |
| `LLM_PROVIDER` | Optional, Coati provider id, defaults to `openai_compatible` |
| `LLM_MODEL` / `FAST_MODEL` | Optional Reasoning / Fast model names, default `gemini-3.8-flash` |
| `BENCH_ROTATION` | Optional display rotation (0-3) pinned before every run, for boards that reset it on boot |
| `EMULATOR_AVD` | Optional AVD used when an emulator has to be cold-restarted |

## Run

```sh
python run_app_bench.py <out_dir> all classic 3        # every task, 3 repetitions
python run_app_bench.py <out_dir> W1,W2,O1 classic 1   # a subset
python summarize.py <out_dir> [manual_verdicts.json]
```

Results are written per run as `<task>_<mode>_r<rep>.json` (status, verified outcome, steps,
wall time), `.log` (Coati logcat) and `.jpg` (final screen). Existing results are skipped, so an
interrupted benchmark resumes. Unresponsive devices are rebooted (emulators cold-restarted) and
runs that failed only because screenshots broke are retried.

## Tasks

Defined in `tasks.py`; most have an automatic check against the app's UI or saved state.

| Id | App | Exercises |
|---|---|---|
| L1 | Manual Lab | native navigation |
| L2 | Manual Lab | native search input, multi-page flow |
| L3 | Manual Lab | native switch behind another page |
| W1 | Canvas Workbench | no accessibility tree: painted tabs, swatches, toggle |
| W2 | Canvas Workbench | no tree, plus a text field that has no accessibility node (IME only) |
| O1 | Mixed Order | native list and fields around painted controls; the keyboard reflows the page |
| B1-B3 | bilibili | real app; checked by hand from the final screenshot |

Canvas Workbench and Mixed Order store their outcome in the lab app's `manual_lab_state`
preferences, which `tasks.py` reads with `run-as`.
