# JEV fast path

## What it is

`AgentLoop` is Coati's single agent loop. Without a JEV API key every step is decided by the
Reasoning model (an action with coordinates) and checked by the Fast model (before/after
screenshots). With a key (Settings → Fast executor), each step first goes to `JevStepExecutor`,
which keeps models off the critical path, and the Reasoning model becomes one route among others:

| Per step | Reasoning step | JEV fast step (`JevStepExecutor`) |
|---|---|---|
| Perceive | screenshot + tree text sent to the Reasoning model | accessibility tree when usable (no model); otherwise one compact vision parse by the Fast model |
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
- three fast actions in a row had no effect, or no new page appeared for several steps (a cycle);
- JEV or the vision parse is unavailable, or the screen cannot be bound for several captures.

The Reasoning step's result is fed back to the fast path, which resumes on the next step. After 10
escalations in one task the Reasoning model keeps the task.

## Components

- `perception/` — `ParsedScreen`; `A11yScreenParser` (tree → candidates, containers borrow
  descendant labels); `VisionScreenParser` (one positional-array JSON parse); `ScreenDiff`
  (1/8-scale luminance grids, a few ms, status bar excluded by the real inset).
- `jev/` — `JevClient` (hedged requests: a duplicate after 2 s, first usable answer wins; retries on
  408/429/5xx/529); `JevCandidates`; `ExplorationMemory`; `StepVerifier`; `JevDecisionEngine`;
  `BackgroundAdvisor`.
- `agent/JevStepExecutor.kt` — the fast path; `agent/AgentLoop.kt` — the loop and the Reasoning step.

### Decision and verification

One JEV request carries independent questions: `action` (next candidate), `outcome` (effect of the
previous action, given a host-phrased expectation and the host check), and `text` when typing.
A certain host verdict (no pixel, text or target-state change) overrides JEV. When the previous step
failed (no change / drifted), the action is re-asked with that verdict in the state; healthy steps
cost one round trip. A pixel change on the target counts as evidence even when tiny (a switch knob).

### Exploration memory

Every fast action's destination page is recorded (element actions matched globally by text,
BACK/scrolls per page). Candidates are annotated with known destinations; candidates leading to a
fully explored page are removed; repeated no-effect candidates are excluded.

### Behaviors found on device

- Gestures run through the overlay's gesture passthrough, like Reasoning steps.
- Typing replaces a simple single-line field, falling back to caret typing where replacement is unsafe.
- An unintended exit from the task app offers `RETURN_TO_APP`.
- A decision voided by `stale_screenshot_coordinates` is re-observed, not failed.
- An unchanged frame reuses the previous vision parse.

## Evaluation

`scripts/device_bench` runs the same tasks with the classic flow (a build of `main`) and this flow
under identical conditions. Results are recorded in the pull request.

## OCR

On 96 ground-truth phrases from 6 screenshots, ML Kit's bundled Chinese model matched 77%, below the
vision model's own 91%, so it is not used. PaddleOCR PP-OCRv4 mobile matched 96/96 on the same set
(desktop evaluation); on-device latency and its benefit to parsing are still to be measured.
