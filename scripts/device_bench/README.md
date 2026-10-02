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
  and marks onboarding complete through the debug receiver. Everything the benchmarks use (the
  debug receivers, the ablation and perturbation switches, `files/task_result.json`) exists only
  in debug builds; the release APK runs the same agent code without them.
- Target apps: `Coati 手册实验室` (`test-apps/living-manual-lab`, v1 flavor:
  `./gradlew :test-apps:living-manual-lab:installV1Debug`) and optionally bilibili
  (`tv.danmaku.bili`, guest mode) and Flutter Gallery v2.9.2 (`io.flutter.demo.gallery`, APK from
  the [release page](https://github.com/flutter-team-archive/gallery/releases/tag/v2.9.2); the
  script pins it to English).

| Variable | Meaning |
|---|---|
| `RELAY_ENDPOINT` | Model endpoint base URL (provider or OpenAI-compatible relay) |
| `RELAY_KEY` | API key for that endpoint |
| `LLM_PROVIDER` | Optional, Coati provider id, defaults to `openai_compatible` |
| `LLM_MODEL` / `FAST_MODEL` | Optional Reasoning / Fast model names, default `gemini-3.8-flash` |
| `BENCH_ROTATION` | Optional display rotation (0-3) pinned before every run, for boards that reset it on boot |
| `EMULATOR_AVD` | Optional AVD used when an emulator has to be cold-restarted |
| `JEV_KEY` | Optional Jev key; required for `jev` and `jevnoocr` |
| `JEV_ENDPOINT` | Optional System One base URL; defaults to `https://api.typesafe.ai` |
| `JEV_MODEL` | Optional Jev model ID; defaults to `jev-1.13.0` |

For OpenRouter, set both `RELAY_ENDPOINT` and `JEV_ENDPOINT` to
`https://openrouter.ai/api`, `LLM_PROVIDER` to `openai_compatible`,
`LLM_MODEL` and `FAST_MODEL` to `google/gemini-3.8-flash`, and `JEV_MODEL` to
`jev-1.13`. Supply the OpenRouter key privately through `RELAY_KEY` and `JEV_KEY`.
The clients append `/v1/chat/completions` and `/v1/systemone` respectively;
do not append `/v1` to these base URLs. These Jev values are passed through the
debug-only `CONFIGURE_AGENT` extras `jev_endpoint` and `jev_model`. Omitting them
retains the TypeSafe defaults; an empty `jev_key` still disables Jev.

## Run

```sh
python run_app_bench.py <out_dir> all classic 3        # every task, 3 repetitions
python run_app_bench.py <out_dir> W1,W2,O1 classic 1   # a subset
python summarize.py <out_dir> [manual_verdicts.json]
```

Modes (comma-separated, alternated within each round): `classic` (per-step Reasoning with
VerifyLoop), `baseline` (per-step Reasoning without post-action verification or completion audit,
the ablation baseline), `jev` (routed execution with GPU OCR) and `jevnoocr` (routed, no OCR).

Robustness runs set `BENCH_PERTURB` to apply one perturbation to every run (`perturb.py`), and
record it as `perturbation`:

| Value | Perturbation |
|---|---|
| `dialog@N` | a "Special offer" dialog from `test-apps/interrupter` covers the app when Coati starts step N |
| `dialog@NxK` | the same dialog, closed only by the K-th dismissal (large K: it cannot be dismissed) |
| `notree` | Coati perceives screens without the accessibility tree, as on custom-drawn interfaces |
| `display` | font scale 1.3 and dark mode during the run, restored afterwards |

The dialog needs the Interrupter installed:
`./gradlew :test-apps:interrupter:assembleDebug` and
`adb install -r test-apps/interrupter/build/outputs/apk/debug/interrupter-debug.apk`.

Results are written per run as `<task>_<mode>_r<rep>.json` (status, verified outcome, steps,
wall time), `.log` (Coati logcat) and `.jpg` (final screen). Existing results are skipped, so an
interrupted benchmark resumes. Unresponsive devices are rebooted (emulators cold-restarted) and
runs that failed only because screenshots broke are retried.

`usage` in each record is the model usage Coati logs when the task ends (`CoatiUsage`): logical
calls, HTTP requests and input/output/thinking tokens per purpose (`reasoning`, `verify`,
`completion_audit`, `vision_parse`, `precommit_gate`, `selector`, ...), summed into `critical`
(calls a step waits for) and `background` (`advisor`, `manual_curation`). `outside_task` holds
calls that finished after the previous task ended, such as a late manual update.

## App-manual learning

```sh
python run_learning_bench.py <out_dir> classic 2   # 2 sequences from an empty manual
```

Each sequence clears Coati's app manuals, switches app knowledge to LEARNING and runs L1, L1, L1,
L3 on the Manual Lab (a cold run, two warm repeats, then a task sharing part of the route). After
every run it waits for the manual update and saves `<mode>_s<n>_<k>_<task>.json` (status, steps,
wall time, update result, manual size, described pages, route words found) and the manual itself
as `.md`. Compare cold with warm runs to see whether the learned manual helps. It leaves app
knowledge in LEARNING; switch it back before other benchmarks
(`am broadcast -a bot.coati.debug.SET_MANUAL_MODE -n bot.coati/.debug.DebugManualReceiver --es mode OFF`).

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
| P1 | Manual Lab | runtime-permission dialog in front of the app (answered by the agent in TRUST) |
| G1 | Flutter Gallery | Flutter semantics tree, sign-in text input, multi-page shopping flow |
| G2 | Flutter Gallery | Flutter settings panel, option behind another control |
| B1-B3 | bilibili | real app; checked by hand from the final screenshot |

Canvas Workbench and Mixed Order store their outcome in the lab app's `manual_lab_state`
preferences, which `tasks.py` reads with `run-as`.
