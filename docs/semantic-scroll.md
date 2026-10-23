# Verified semantic scroll fallback

Some Flutter lists ignore accessibility-injected gestures even though Android reports successful
dispatch. Both the classic agent and routed executor use `ActionEngine`; its Swipe and Scroll paths
now share a fallback, without shell input, root, an app allowlist or a JEV dependency.

The original gesture runs first. The engine observes three times, 350 ms apart, after an initial
screenshot-interval wait. A pixel change or changed App-node signature suppresses the fallback to
avoid double scrolling. Only a comparable, unchanged screen permits one advertised semantic action.
Success after that action requires **both** changed pixels and a changed node signature. The engine
never treats `performAction(true)` alone as movement or retries a semantic action internally.

Target selection uses the highest-layer application window, excluding Coati's own windows, input
methods and focused external system dialogs. The swipe start must be inside the innermost matching
scrollable container. Directional actions take priority; generic forward/backward requires an axis
established by collection metadata or an unambiguous child row/column. A child at its boundary does
not redirect the action to an ancestor. Unknown layouts fail explicitly rather than guessing an axis
from the screen's aspect ratio. Editable fields and sliders are excluded from this fallback.

Window/package, path, bounds, scrollability and the advertised action are rechecked immediately
before the semantic action. Screenshot evidence masks Coati's overlay and system bars. A screenshot
failure, changed target, missing action, rejected action or absent visible effect returns a distinct
`SCROLL_*` failure and feedback to re-observe instead of repeating an unchanged scroll.

Pixel evidence uses a 64-by-64 sample of the target viewport: at least 128 unmasked samples and a
channel difference above 24 in at least 1% of samples. This is deliberately conservative: identical
looking rows or unknown accessibility layouts may return an unverified failure. This does not add
semantic actions to canvas content that does not advertise them. It does not change OCR or target
application versions.

## Validation

Run `./gradlew :app:testDebugUnitTest` (Windows: `.\gradlew.bat :app:testDebugUnitTest`). Tests cover
direction mapping, nested boundaries, original-gesture changes, verified/unverified effects,
cancellation, missing actions, window identity, keyboard/system-dialog guards and text/slider drags.

The debug-only, DUMP-protected `ScrollActionReceiver` invokes one bounded real ActionEngine call
without a model. Exit the Coati overlay service first; keep its accessibility service enabled. Each
run ID is claimed once, with output at `files/scroll-actions/<run_id>/result.json`; release builds have
no receiver. For a device on the Shrine product page of Gallery v2.9.2, with coordinates adjusted to
that device, for example:

```powershell
adb -s $serial shell am broadcast -n bot.coati/.debug.ScrollActionReceiver `
  --es run_id shrine-forward-01 --es expected_package io.flutter.demo.gallery `
  --ei x1 1017 --ei y1 1386 --ei x2 254 --ei y2 1386 --el duration_ms 600
adb -s $serial exec-out run-as bot.coati cat files/scroll-actions/shrine-forward-01/result.json
```

Passing `--es direction RIGHT` instead invokes the screenshot-bound `AgentAction.Scroll` path.
ADB only requests and captures the diagnostic action; the action backend uses accessibility APIs.

Phone regression must check Shrine forward/backward/boundaries, Gallery home carousel, a native
vertical list (including an already working gesture), nested containers, no-action pages, Gallery
settings and keyboard/modal guards. Record screenshots and independent content/offset observations;
these local checks are separate from formal task scores and do not authorize a benchmark rerun.
