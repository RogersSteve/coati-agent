# JEV step executor

## What it is

An alternative per-step runtime (`JevAgentLoop`) selected in Settings → Step executor. The classic
runtime (`AgentLoop`) asks the Reasoning model for every action (with coordinates) and the Fast model
to verify every action from two screenshots. The JEV runtime keeps models off the critical path:

| Per step | Classic (`AgentLoop`) | JEV (`JevAgentLoop`) |
|---|---|---|
| Perceive | screenshot + tree text sent to the Reasoning model | accessibility tree when usable (no model); otherwise one compact vision parse by the Fast model |
| Decide | Reasoning model outputs an action and normalized coordinates | JEV (TypeSafe System One) chooses one candidate id; the host taps the element's own bounds |
| Verify | Fast model compares before/after screenshots | host evidence (pixel diff, texts, target state) + a JEV question in the same request as the decision |
| Plan, hints, app-manual learning | inside the Reasoning call | Reasoning model in the background (`BackgroundAdvisor`), never blocking a step |
| Completion | independent screenshot audit (Fast model) | same audit |

JEV only chooses among options the host lists; it never sees images and never produces free text.
Text to type comes from the background plan (`text_values`).

## Components

- `perception/` — `ParsedScreen` model; `A11yScreenParser` (tree → candidates, containers borrow
  descendant labels); `VisionScreenParser` (one positional-array JSON parse, ~1-2k output tokens);
  `ScreenDiff` (1/8-scale luminance grids, a few ms, status bar excluded by the real inset);
  `OcrTextRefiner` (ML Kit; off by default, see OCR below).
- `jev/` — `JevClient` (hedged requests: a duplicate after 2 s, first usable answer wins; retries on
  408/429/5xx/529); `JevCandidates`; `ExplorationMemory`; `StepVerifier`; `JevDecisionEngine`;
  `BackgroundAdvisor`.
- `agent/JevAgentLoop.kt` — the runtime; `agent/AgentRuntime.kt` — interface shared with `AgentLoop`.

### Decision and verification

One JEV request carries independent questions: `action` (next candidate), `outcome` (effect of the
previous action, given a host-phrased expectation and the host check), and `text` when typing.
A certain host verdict (no pixel, text or target-state change) overrides JEV. When the previous step
failed (no change / drifted), the action is re-asked with that verdict in the state; healthy steps
cost one round trip. A pixel change on the target counts as evidence even when tiny (a switch knob).

### Exploration

JEV reliably picks a visible target but guesses poorly when the target is off-screen. The host keeps
an exploration memory: every action's destination page (element actions matched globally by text,
BACK/scrolls per page). Candidates are annotated with known destinations; candidates leading to a
fully explored page are removed; low-confidence choices and repeat revisits are re-asked over untried
entries only. A page cycle or 8 steps without a new page hands one step to the classic Reasoning
model; after 3 such fallbacks the task fails explicitly instead of looping.

### Robustness behaviors found on device

- Typing replaces a simple single-line field, falling back to caret typing where replacement is unsafe.
- An unintended exit from the task app offers `RETURN_TO_APP`.
- A decision voided by `stale_screenshot_coordinates` is re-observed, not failed.
- Capture is retried when rate-limited or when windows are changing (no coordinate binding).
- Android 14+ hides "accessibility data sensitive" windows (runtime permission dialogs) from
  non-accessibility-tool services: no tree and no coordinate binding. The loop then offers only
  coordinate-free actions (BACK dismisses such dialogs).
- `takeScreenshot` is bounded to 5 s (the platform occasionally never calls back).

## Configuration

Settings → Step executor: choose Reasoning model or JEV and verify a JEV API key. JEV mode uses the
configured Fast model for vision parses and the Reasoning model for background planning.
Debug builds expose `DebugAgentReceiver` (DUMP permission): `CONFIGURE_AGENT` (including
`vision_only` and `ocr` switches), `RUN_TASK`, `STOP_TASK`, `OCR_BENCH`.

## Device benchmark (2026-09-29)

Emulator (Android 16, x86_64), Gemini 3.8 Flash through an OpenAI-compatible relay for all model
roles, JEV 1.13.0, confirmation mode TRUST, app data cleared before every run, 3 runs per cell.
Scripts: `scripts/device_bench/` (`run_app_bench.py`, `summarize.py`). Bilibili results were judged
from the saved final screenshots; one ambiguous run is counted as a failure.

| Task | JEV (tree) | JEV (vision only) | Classic |
|---|---|---|---|
| L1 find a deep page (Manual Lab) | 3/3, 76 s | 2/3, 214 s | 1/3, 79 s |
| L2 search, favorite, confirm | 3/3, 25 s | 3/3, 72 s | 3/3, 79 s |
| L3 toggle an advanced switch | 3/3, 15 s | 3/3, 51 s | 2/3, 236 s |
| B1 bilibili: search, open a video | 3/3, 60 s | 3/3, 207 s | 0/3 |
| B2 bilibili: first Popular video | 2/3, 48 s | 3/3, 68 s | 0/3 |
| B3 bilibili: turn on dark mode | 0/3 | 2/3, 97 s | 0/3 |
| Total | 14/18 | 16/18 | 6/18 |

Times are medians of successful runs. Caveats:

- The classic runtime fails every bilibili run at step 1: the startup dialog window cannot be
  coordinate-bound, and its coordinate tap throws `missing_screenshot_coordinates`. This is an
  existing robustness gap of `AgentLoop`, not a model-quality result.
- Classic lab failures were coordinate misses (a tap slightly off the target, repeated) and one
  out-of-range coordinate; JEV taps element bounds and does not miss.
- JEV fails tasks that need reasoning across dependent controls (B3: "dark mode follows system"
  must be turned off before "dark mode" can be turned on), and spends extra steps on exploration.
- JEV latency was usually ~1 s but had a heavy tail (p90 ≈ 12 s on one day); hedging mitigates
  isolated slow requests, not service-wide slowdowns.
- Emulator only; the emulator's `takeScreenshot` broke after most runs and needed reboots, which the
  scripts detect and recover from. Results on physical devices are not yet measured.

## OCR

On 96 ground-truth phrases from 6 screenshots, ML Kit's bundled Chinese model matched 77% (720 px)
/ 75% (full resolution), below the vision model's own 91%; replacing model text with it would add
errors, so OCR refinement is off by default (`ocr` debug switch). PaddleOCR PP-OCRv4 mobile matched
96/96 on the same set (desktop evaluation) and is the planned replacement.
