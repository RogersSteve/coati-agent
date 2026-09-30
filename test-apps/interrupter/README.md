# Coati Interrupter

A tiny fixture for robustness runs (`scripts/device_bench/perturb.py`): a floating "Special offer"
dialog that covers whatever app is in front, like a promotion that appears mid-task. It is a
separate installable application and is never packaged into the Coati APK.

- Application ID: `bot.coati.fixture.interrupt`
- Network permission: none

```sh
./gradlew :test-apps:interrupter:assembleDebug
adb install -r test-apps/interrupter/build/outputs/apk/debug/interrupter-debug.apk
adb shell am start -n bot.coati.fixture.interrupt/.PopupActivity --ei dismissals 1
```

`Not now` or Back closes it. With `dismissals` N it takes N attempts: each earlier attempt keeps the
dialog and changes its text, so a large N gives a dialog that cannot be dismissed (for testing
early termination). `Claim offer` does nothing. Events are logged under `CoatiInterrupt`.
