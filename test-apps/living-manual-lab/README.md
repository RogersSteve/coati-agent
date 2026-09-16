# Coati Manual Lab

`Coati Manual Lab` is an offline, deterministic Android fixture for Living Manual tests. It is a
separate installable application and is never packaged into the Coati APK.

## V1 identity

- Application ID: `bot.coati.fixture.manual`
- Version code: `1`
- Layout flavor: `v1`
- Network permission: none

V1 has four stable bottom tabs (`首页`, `资料库`, `收藏`, `我的`), ordinary detail and settings
pages, and the low-frequency route `我的 -> 设置 -> 高级设置 -> 实验档案`. V2 keeps the same
package and signing identity but raises `versionCode` to 2 and replaces the old `资料库`/`我的`
bottom entries with a `工具` hub. Search and preferences operate entirely on local fixed data.

## Build and install on Windows PowerShell

From the repository root:

```powershell
.\gradlew :test-apps:living-manual-lab:test :test-apps:living-manual-lab:assembleV1Debug :test-apps:living-manual-lab:assembleV2Debug

$Device = '3B15AM00LXZ00000'
$LabApk = 'test-apps\living-manual-lab\build\outputs\apk\v1\debug\living-manual-lab-v1-debug.apk'
adb -s $Device install -r $LabApk
adb -s $Device shell am start -n bot.coati.fixture.manual/.MainActivity
```

Install V2 over V1 without clearing either App. This is the deterministic layout-upgrade gate:

```powershell
$LabV2Apk = 'test-apps\living-manual-lab\build\outputs\apk\v2\debug\living-manual-lab-v2-debug.apk'
adb -s $Device install -r $LabV2Apk
adb -s $Device shell am start -n bot.coati.fixture.manual/.MainActivity
```

Reset only the fixture App while preserving Coati and its learned manual:

```powershell
adb -s $Device shell pm clear bot.coati.fixture.manual
adb -s $Device shell am start -n bot.coati.fixture.manual/.MainActivity
```

To remove only the fixture App:

```powershell
adb -s $Device uninstall bot.coati.fixture.manual
```

Do not change the application ID or signing key in future layout versions. A V3 upgrade must use a
higher `versionCode` and `adb install -r` so Android preserves the App identity and Coati can test
manual staleness against a real in-place upgrade.
