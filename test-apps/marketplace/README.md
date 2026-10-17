# Car Market

An offline, deterministic used-car marketplace for long tasks made of many simple repeated steps:
scroll the list, open a listing, read its fields, go back, remember the matches. It is a separate
installable application, never packaged into the Coati APK. The tasks and the answer checker are
in `scripts/device_bench/marketplace.py` (tasks M1-M5).

- Application ID: `bot.coati.fixture.marketplace`, launcher label `Car Market`
- Activities: `.MainActivity` (list), `.DetailActivity` (not exported)
- Network permission: none; no login

```sh
./gradlew :test-apps:marketplace:testDebugUnitTest :test-apps:marketplace:assembleDebug
adb install -r test-apps/marketplace/build/outputs/apk/debug/marketplace-debug.apk
adb shell am start -S -n bot.coati.fixture.marketplace/.MainActivity --ez reset true
```

## Data

The 40 listings live in `src/main/assets/listings.json`, which is also the ground truth: the app
reads it at start and the checker reads the same file. `trap` entries annotate near-misses and are
never shown. The list row shows make and family (`Mercedes-Benz E-Class Sedan`), color and price
with a photo placeholder. Year, exact model, mileage, location, distance from Los Angeles, accident
history and status are on the detail page only, one `Label: value` text view each, so every
candidate has to be opened. That is why list titles carry the family, not `2023 Mercedes-Benz E 300`
(the detail page title). Units are shown as the seller entered them; the app never converts.

16 rows are E-Class (the other 24 include C-Class, CLA and GLE Mercedes and other makes). The
query "available 2023 Mercedes-Benz E 300 within 100 km of Los Angeles with under 50,000 miles"
has 5 matches: CM-3417, CM-4052, CM-1586, CM-8134, CM-9046. The near-misses:

| Listing | Fails because |
|---|---|
| CM-5208 | E 350 |
| CM-2861 | 2022 (and cheaper than every match) |
| CM-3780 | 2024 |
| CM-6943 | 50,500 mi (and cheaper than every match) |
| CM-7720 | 101 km |
| CM-2297 | distance shown as 68 mi, about 109 km |
| CM-1903 | 152 km |
| CM-6375 | Sold; the same car is re-listed as CM-9046, the last row of the list |
| CM-5561, CM-7168, CM-4429 | AMG E 53, E 450, E 450 Coupe |

Matches that need care: CM-1586 has 49,800 mi and a minor accident (excluded by M4), CM-4052 is
95 km away, and CM-8134 shows `64,000 km` (about 39,800 miles).

## Behavior

- The list is a `ListView` (only rows on screen are in the accessibility tree) of about 5 screens
  (about 9 rows each) on a phone. Opening a detail and returning keeps the scroll position.
- The detail page has a `Back` button at the top; system Back works too. All fields fit on one
  phone screen above the seller notes.
- `--ez promo true` turns on a one-time `Special financing` dialog on the 5th detail page opened
  (`No thanks`, `Check my rate` or Back closes it). It stays on until `pm clear` or `--ez reset true`.
- Reset: `--ez reset true` clears the view counter, promotion state and setting; `-S` restarts the
  process at the top of the list. `pm clear bot.coati.fixture.marketplace` does the same.
- Events are logged under `CoatiMarket` (`detail opened id=... view=N`, `promo shown`,
  `promo dismissed via ...`), so a run log shows which listings the agent opened.
