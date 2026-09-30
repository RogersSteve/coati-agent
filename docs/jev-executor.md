# JEV fast path

## What it is

`AgentLoop` is Coati's single agent loop. Without a JEV API key every step is decided by the
Reasoning model (an action with coordinates) and checked by the Fast model (before/after
screenshots). With a key (Settings → Fast executor), each step first goes to `JevStepExecutor`,
which keeps models off the critical path, and the Reasoning model becomes one route among others:

| Per step | Reasoning step | JEV fast step (`JevStepExecutor`) |
|---|---|---|
| Perceive | screenshot + tree text sent to the Reasoning model | accessibility tree when usable (no model); a compact vision parse by the Fast model when it is not; both merged on native pages with painted content |
| Decide | Reasoning model outputs an action and normalized coordinates | JEV (TypeSafe System One) chooses one host-built candidate; the host taps the element's own bounds |
| Verify | Fast model compares before/after screenshots | host evidence (pixel diff, texts, target state) + a JEV question in the same request as the next decision |
| Plan, text values, app-manual learning | inside the Reasoning call | Reasoning model in the background (`BackgroundAdvisor`) from the first step, never blocking a step |
| Completion | independent screenshot audit (Fast model) | same audit |

JEV only chooses among options the host lists; it never sees images and never produces free text.

### Routing

Both routes share one loop: capture, the system-dialog handoff (`SecurityDialogs`), safety
confirmation, the completion audit and task state. The fast path hands a step to the Reasoning step
(`FastStep.Escalate`) when:

- JEV itself picks the escalate candidate;
- three fast actions in a row had no effect, or no new page appeared for several steps (a cycle),
  unless most recent actions changed their own target (form filling on one page);
- six exploratory steps in a row had no confident choice (new pages reset the cycle check, so blind
  exploration through a large app would otherwise never escalate);
- a commit action (submit, save, send, export, confirm... on a button, item or card) is about to run
  while one Fast-model look at the screen finds a value the task asks for missing (`PreCommitCheck`);
- the completion audit rejected a DONE claim (the Reasoning step gets the audit's reason);
- JEV or the vision parse is unavailable, or the screen cannot be bound for several captures.

The Reasoning step's result is fed back to the fast path, which resumes on the next step. After 10
escalations in one task the Reasoning model keeps the task.

## Components

- `perception/` — `ParsedScreen`; `A11yScreenParser` (tree → candidates, containers borrow
  descendant labels); `VisionScreenParser` (one positional-array JSON parse, 30 s per attempt);
  `HybridPerception` (painted text in unlabelled, non-image nodes → merge a vision parse into the
  tree; the parse is reused while OCR reads the same painted lines and no pixel changed over them);
  `ScreenDiff` (1/2-scale luminance grids, ~10 ms, status bar excluded by the real inset);
  `NcnnOcr` / `OcrTextRefiner` (see OCR below).
- `jev/` — `JevClient` (hedged requests: a duplicate after 2 s, first usable answer wins; retries on
  408/429/5xx/529); `JevCandidates`; `ExplorationMemory`; `StepVerifier`; `JevDecisionEngine`;
  `BackgroundAdvisor`; `PreCommitCheck`.
- `agent/JevStepExecutor.kt` — the fast path; `agent/AgentLoop.kt` — the loop and the Reasoning step.

### Decision and verification

One JEV request carries independent questions: `action` (next candidate), `outcome` (effect of the
previous action, given a host-phrased expectation and the host check), and `text` when typing.
A certain host verdict overrides JEV: "no change" requires that no grid cell changed at all, while a
change on or beside the target (a switch knob, a counter next to "+") counts as evidence however
small. When the previous step failed (no change / drifted), the action is re-asked with that verdict
in the state; healthy steps cost one round trip.

### Exploration memory

Every fast action's destination page is recorded (element actions matched globally by text,
BACK/scrolls per page). Candidates are annotated with known destinations; candidates leading to a
fully explored page are removed; repeated no-effect candidates are excluded. Pages are compared by
the Jaccard similarity of their visible texts (0.6).

### App-manual learning

With app knowledge in LEARNING mode, the background advisor also describes each new page for the
app manual. Pages that appear while it is busy get observation-only calls (two in flight, a queue of
six), sibling native pages count as different pages (Jaccard 0.9 instead of 0.6), and on task
completion the executor waits up to 20 s for observations still in flight. None of this runs in
other modes.

### Behaviors found on device

- Gestures run through the overlay's gesture passthrough, like Reasoning steps; a gesture the overlay
  caught before the passthrough applied is repeated.
- Typing replaces a simple single-line field, falling back to caret typing where replacement is unsafe.
- An unintended exit from the task app offers `RETURN_TO_APP`.
- A decision voided by `stale_screenshot_coordinates` is re-observed, not failed.
- An unchanged frame reuses the previous vision parse.

## Evaluation

`scripts/device_bench` runs the same tasks with the classic flow (a build of `main`) and this flow
under identical conditions (RK3576 board, Gemini 3.8 Flash for Reasoning and Fast, 3 repetitions of
9 tasks: native, custom-canvas, mixed, text input, dependent settings, a system permission dialog,
cross-app, and a Flutter app). Task time is the duration Coati logs for the task.

| | classic | JEV fast path |
|---|---|---|
| verified | 27/27 | 27/27 |
| mean task time | 57.3 s | 40.6 s |
| median task time | 51.8 s | 32.4 s |
| Reasoning calls on the critical path | 176 | 12 (escalations) |

The fast path is faster on 8 of 9 tasks (up to 64% on the Flutter app, 50-59% on native and
permission tasks). It is slightly slower on L1, a route several menus deep, where JEV explores
(~30 steps, 87-158 s) before finding it.

App-manual learning (`run_learning_bench.py`, L1 three times then L3, from an empty manual, two
sequences): both flows learn the full route. With the learned manual, warm L1 runs take 5 steps in
both, 54-61 s for classic and 27 s for the fast path, which also removes its L1 exploration cost.

## OCR

PaddleOCR PP-OCRv5 mobile runs on the GPU through NCNN + Vulkan; without a usable Vulkan GPU there
is no OCR (a CPU fallback was too slow to help). It reads only text the accessibility tree does not
explain (unlabelled, non-image nodes; labelled nodes, the status bar and the keyboard are skipped)
and repairs vision parses conservatively: confident lines only, extra confident text is appended,
positions snap to matching lines, and a parse that agrees with OCR on less than 30% of its texts is
treated as a parse of another image and redone. On 14 board screens (28 parses) OCR repair raised
text matches from 51.7% to 63.7%, exact matches from 49.2% to 57.6% and tap hits from 26.4% to
32.0%, with no regressions.
