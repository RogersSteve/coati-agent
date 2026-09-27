# Screenshot-bound coordinate compatibility and device validation

## Contract and supported scope

A model spatial action is bound to the ScreenContext that produced its request. The screenshot API captures DEFAULT_DISPLAY; the encoder scales the complete bitmap to at most 720 pixels wide without cropping or removing system bars. Normalized coordinates therefore map to raw screenshot pixels (the full-frame resize cancels on each axis). Raw dimensions must equal the captured display dimensions. UI tree getBoundsInScreen points are already absolute and are not rescaled.

Display state is captured before and after screenshot acquisition and checked again after confirmation/overlay settling, immediately before execution. A changed rotation, size, density, window identity/package, bounds, insets or magnification rejects the spatial action. Unknown geometry fails closed rather than guessing an offset. This is a pre-dispatch check, not an atomic guarantee against display changes after the check or during a gesture. UI tree/screenshot temporal alignment is outside this change.

Supported mapping: default display, unmagnified full-screen windows, full-frame proportional image resize; fullscreen content may exclude system bars. Partial/freeform/split-screen, secondary displays, magnification, crop/letterbox transformations and unknown window metadata are unsupported and rejected where detected. No universal OEM/device compatibility is claimed. Device checks cover stable portrait actions and rejection after rotation, not new landscape actions, focus-and-input or scroll device end-to-end behavior.

Tap/longpress prefer a complete top-level normalized x/y pair; the existing documented params pair is a normalized fallback. Mixed/incomplete pairs are rejected. Swipe uses its own normalized endpoint pairs without needing unrelated top-level x/y. Focus-and-input requires the existing top-level pair. Malformed/non-integral/out-of-range coordinates fail instead of becoming zero or raw pixels. The low-level AgentAction.fromMap API remains a pixel API. Scroll retains its semantic action and pixel-distance amount but uses the bound full display center. There is no change to prompts, Verify, completion, experience, safety confirmation or budgets.

## Offline validation

Run in Windows PowerShell at the repository root, with the Android SDK and JDK configured:

```powershell
.\gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease --offline
python -m unittest discover -s scripts -p test_coordinate_fixture_evidence.py
```

Tests reproduce 1080x2400 versus 1080x2209 mis-scaling and cover resolutions, system bars, portrait/landscape, image scaling, params precedence, swipe, focus, scroll, UI points, invalid geometry and stale binding. Final audit reruns both test suites rather than counting UP-TO-DATE test results as new execution. Debug fixture tests exercise production resolver/dispatch guard and actual-touch hit criteria. Production behavior still requires device validation.

## Debug-only device fixture

CoordinateFixtureActivity and CoordinateFixtureControlReceiver require android.permission.DUMP and exist only in Debug. No model gateway or evaluation adapter is used. Each new run permits tap/longpress/swipe once, using measured local targets and the production screenshot, conversion and ActionEngine path. Actual touch events must hit the targets. After three hits it arms one stale-binding check; its execution sink is a trap, so it never sends an unsafe old-coordinate click even if the guard regresses.

Before installation, identify the intended idle device explicitly. Back up its installed base APK, verify its hash/signature/version and ability to restore with install -r. Stop if split APKs, signing incompatibility or downgrade prevents safe restoration. Never uninstall or clear data. Installation may stop services; ask the operator to restore accessibility if needed. Keep the product overlay service stopped while accessibility is bound. Keep the fixture foreground; no blind retries. Reports record bounded lifecycle/focus/service diagnostics and preserve the first failure. Startup still uses its existing 700ms delay; the prior intermittent phone foreground failure was not reproduced or proven fixed.

PowerShell commands below require explicitly assigned $Adb, $Serial, $Run (new ID), $Out (new directory outside Git), $Python and the appropriate verified APK. Do not run until device use is authorized:

```powershell
& $Adb -s $Serial install -r $FixtureApk
if ($LASTEXITCODE -ne 0) { throw 'Install failed; stop' }
# Confirm accessibility bound and product service stopped before the only launch.
& $Adb -s $Serial shell am start -W -n bot.coati/bot.coati.debug.CoordinateFixtureActivity --es op run --es run_id $Run
# When three hits are displayed, export and validate; STOP means no restart.
& $Python scripts/coordinate_fixture_evidence.py --adb $Adb --serial $Serial --run-id $Run --out $Out --phase initial
if ($LASTEXITCODE -ne 0) { throw 'Evidence failed; preserve report and stop' }
$Initial = Get-Content "$Out/initial-report.json" -Raw | ConvertFrom-Json
# Operator rotates while fixture stays foreground; verify same instance before sending once.
& $Adb -s $Serial shell am broadcast --receiver-foreground -n bot.coati/bot.coati.debug.CoordinateFixtureControlReceiver --es op validate-stale --es run_id $Run --es instance_id $Initial.instance_id
# Require result=0/accepted, then export final once validation finishes.
& $Python scripts/coordinate_fixture_evidence.py --adb $Adb --serial $Serial --run-id $Run --out $Out --phase final
if ($LASTEXITCODE -ne 0) { throw 'Final evidence failed; stop' }
```

The exporter saves a failed report before rejecting it; preserve that report and do not restart. After export, restore original direction/rotation settings and the same device's verified original APK with install -r, then read back its hash. Restore services manually if needed. Backup APKs do not back up private app data. Reports/screenshots remain local and are not committed.

## Completed bounded device evidence

| Device/configuration | Raw / service resources | Result |
|---|---|---|
| AndroidWorld emulator, default portrait | 1080x2400 / 1080x2209 | Three real hits; rotation to 2400x1080 rejected old binding; zero stale dispatches |
| OnePlus PLK110, default portrait | 1272x2772 / 1272x2772 | Three real hits; rotation to 2772x1272 rejected old binding; zero stale dispatches |

Both runs used zero model calls. Original APKs and display settings were restored. Emulator fixture APK SHA256: 6E8BA585FBF6982C48BE356CD4ECC4B0178D6FA0E1C910D5F9C8FF452C783B75. Phone fixture APK SHA256: 724BB308AB4D93D992873D4333C22DB1E66D191F4D1E7AC96F38D10BFA7478A1; only Debug diagnostics changed between these builds.

Local final-report SHA256 values (reports and four screenshot hashes revalidated during final review):
- Emulator: 0e6ce5217f7e994a10e7da00bb41e6111208b2cfd49801852882cb557b449ca8
- Phone: 0c69b9e61523683bdd07c7e4afcca79b3c8f28185e5bc8a72d523f17d16da5db

The emulator is a regression configuration, not an AndroidWorld dependency: no AW task, relay, judge or benchmark code is included in this fix.

Final audit: Debug 487 and Release 470 JVM tests freshly executed, all passed with zero skips; Python 3 passed. Release build validated (unchanged production inputs were UP-TO-DATE); Release Lint 0 errors/14 warnings. Actual Release DEX and merged manifest contain no fixture/diagnostic/evaluation entry. Release APK SHA256: 313ADCF3F3414F68008B382541F111C244254751AE37B21912A65231560E8538.
