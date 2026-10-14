# AndroidWorld APK harness

AndroidWorld initializes and resets its official tasks, allocates the official
step budget, accepts answers and scores the outcome. Coati runs as a Debug APK
in that same emulator. This is an APK adapter, not an AW backend agent.

## Dependencies and version boundary

Use Python 3.11, the AW requirements and setup instructions at official baseline
`e3fea3ccc69787570e282c99573298f1c3019a34`, and android-env 1.2.3. This harness
currently requires the separately reviewed AW environment commit
`6e4faf91094e72b9f51276b9aa394cbdc057c9a7`: private-ADB refresh and accessibility
forwarder coexistence fixes. Those AW patches are **not** part of this PR or a
public upstream release. Obtain the reviewed AW reproduction asset separately;
plain upstream AW is not claimed to satisfy this dependency. Do not silently
upgrade AW, patch site-packages or disable its forwarder.

The host (`scripts/device_bench`) and harness must use the same committed Coati
revision with empty indexes and no tracked changes or untracked helper source.
No historical local worktree is a new evaluation baseline. APK SHA-256 and
AndroidID are mandatory CLI inputs. Exact sources, generated parameters, budget,
APK, transport and condition form result identity; changed identity requires a
new result directory, not combining old and new scores.

For optional relay tests/runtime, use `requirements-relay.txt` in a separate
Python 3.11 environment. Do not replace AW's dependency environment. No relay
process, ADB reverse, credentials or paid request is used by the default plan.

## Offline verification

```sh
python test_contracts.py --aw-root "$AW_ROOT"
python test_parameter_recording.py --aw-root "$AW_ROOT"
python -m unittest test_resume_identity test_cold_reconnect_order \
  test_learning_preflight test_freeze_and_observability test_host_compatibility
# Separate relay environment; fake upstream, loopback test listeners only:
"$RELAY_PYTHON" -m unittest test_local_relay
```

Tests use synthetic temporary evidence and the actual pinned AW interfaces.
They do not load historical server results or call a model. Loopback relay tests
use an injected fake upstream and never contact OpenRouter.

## Private connection and plan

Create connection JSON outside the repository. `workspace` is a user-writable
root with sdk/, avd/, android-user/, tmp/ and logs/; specify `adb_path`,
`adb_server_port`, `console_port`, `device_adb_port`, `grpc_port`, `serial`,
`avd_name` and `read_only=false`. Ports below are examples, not a claim they are
free. Recheck ownership/availability. Only a single private writable instance is
supported: shared ADB ports 5037/5038, parallel shards and read-only clones fail
closed. Never stop another instance, use kill-server or wipe an AVD.

```json
{"workspace":"/absolute/evaluation-workspace",
 "adb_path":"/absolute/evaluation-workspace/sdk/platform-tools/adb",
 "adb_server_port":5039,"console_port":5556,"device_adb_port":5557,
 "grpc_port":8556,"serial":"emulator-5556","avd_name":"YourReviewedAvd",
 "read_only":false}
```

From scripts/androidworld, plan without credentials:

```sh
EMPTY_DIFF_SHA=$(printf '' | sha256sum | cut -d' ' -f1)
python harness.py --aw-root "$AW_ROOT" --connection "$CONNECTION_JSON" \
  --host-diff-sha256 "$EMPTY_DIFF_SHA" --android-id "$ANDROID_ID" \
  --apk-sha256 "$APK_SHA256" --config C1 --output "$NEW_RESULTS"
```

`--execute` and `--credentials` are separate explicit choices after task/funding
authorization; do not add them for installation or OCR prechecks. Credential
JSON must be owned regular 0600 with an owned 0700 parent, containing only
`openrouter_api_key`. Never print it, place it in Git, or put it in a snapshot.
Default models are the agreed `google/gemini-3.8-flash` for reasoning/fast and
`jev-1.13` for routed selection. No task-specific prompt or score checker is added.

`--provider google` uses the official Gemini API (`gemini-3.8-flash`) and Jev
(`jev-1.13.0` on api.typesafe.ai) instead of OpenRouter. Its credential JSON
holds exactly `google_api_key` and `jev_api_key` with the same file rules. AW's
2023 device clock fails TLS to both, so pass `--relay-config`. The relay then
forwards `/google/v1beta/models/<allowlisted model>:generateContent?key=…`,
authenticated by the key's digest, and `/jev/v1/systemone` with its Bearer
digest. Logged paths never include the key. The openrouter models and transport
dictionaries are unchanged, so earlier experiment identities remain valid.
Results from different providers are different experiments; never combine them.

## Conditions, learning and freezing

C1: baseline, Verify=false, manual OFF. C2: classic, Verify=true, manual OFF.
C3: classic, Verify=true. C4: jev, Verify=true. All request OCR=true; C3/C4 use
seed31/trial1 LEARNING followed by seed30 READ_ONLY evaluation. C1/C2 evaluate
seed30; scoring trials may be 1–3. The unchanged smoke_tasks.json fixes ten tasks
and execution order before scores. `--max-new-episodes` caps dispatches but does
not change expected experiment coverage. No ordinary failed episode is replayed.

On one instance: C3 learn -> freeze -> C3 eval, then independent C4 learn ->
freeze -> C4 eval. Archive inventory bytes and hashes outside Git before an
explicit fresh `--clear-learning`. Resuming learning cannot clear existing
records; learning already frozen cannot reopen as LEARNING. Never borrow another
condition's inventory or synthesize manuals.

A freeze requires all expected learning records, no environment failures,
per-task update evidence, READ_ONLY mode and inventory stability for 20 seconds.
The new schema2 manifest explicitly records `empty`, `manual_count`, `file_count`,
`inventory`, learning coverage/score/source/APK summary and all learning-file and
inventory hashes. A naturally empty inventory is valid and evaluation records
"no manuals" via this manifest. Missing freeze is not legal empty training.
Legacy nonempty manifests retain their checks; legacy implicit empty manifests
are rejected. Evaluation revalidates training coverage, source/APK and inventory
before configuration and after every episode. Metadata and revisions retain
hashes; manual_count counts canonical manual.md files, not revision copies.

Learning settlement uses source terminal logs and stable inventory, not an atomic
pending-job API. These observations and late/background/outside_task usage must
remain explicit; do not attribute unrelated calls to an episode by inference.

## Official lifecycle, budget and artifacts

The pinned sources are AW suite_utils.create_suite/_run_task/
_allocate_step_budget, episode_runner.run_episode, base_agent and AsyncAndroidEnv.
Initialization precedes one RUN_TASK. Coati receives the official
`int(10 * task.complexity)` budget. Results are polled every 3 seconds, with a
host monotonic wall ceiling `min(max_steps * 60, 1800)`. Polling, relay waiting and
perception are not subtracted. Upstream Coati refunds rounds that emit no screen
action; its raw `refunded_steps` is retained, not redefined by the host.

Fresh STOP acknowledgement plus stable inactive state precede clearing task
files through the existing helper. Normal configure is STOP -> clear -> Debug
configure -> runtime acknowledgement. Cursor markers use one main log buffer
and device boot identity; missing/trimmed markers fail closed, and logcat is not
globally cleared. Blank/malformed state is not silently treated as absent.

A nonblank TaskResult.answer goes unchanged through official ANSWER and cache
checking, then official scoring/teardown. No extra reset occurs before score.
Official fractional scores are preserved; reporting's successful threshold is
>0.5. declared_done and audit fields do not replace the official score.
Files are `<task>_t<trial>[_vN].json/.log/.jpg` in C1..C4/C3_learn/C4_learn.
Interrupted artifacts, changed hashes or environment failures block automatic
replay; unknown statistics stay null. Result capture precedes official teardown.

Evaluation records include the frozen manual_inventory summary (empty, canonical manual_count and file_count); no-freeze OFF/learning records retain null rather than inventing training.

Results retain full TaskResult plus requested/effective OCR evidence from the
current APK PID, boot and main-log buffer. `OCR ready`, initialization/runtime
`OCR disabled` and `OCR unavailable` are distinguished; missing terminal evidence
stays unknown. Classic route has no JEV OCR path. Requested true is not proof of
hardware OCR. Existing records without refunded/OCR fields remain missing, not 0.

After each ten actual attempts, a persistent serial/AndroidID-bound ledger gates
the next dispatch. Explicitly allowed native cold restart closes/recreates AW,
waits at most 120 seconds for IPv4/IPv6 console/device/gRPC port release, starts
only that AVD with the same private server and verifies identity, services,
configuration and inventory before restart_verified. Skips do not count. Pending
attempts require review; never reset/delete the ledger to bypass a gate. No wipe,
shared-server shutdown, fallback startup variant or snapshot restore is used.

## Optional Debug relay and PR dependencies

The Debug loopback-network PR is required only for relay transport, not direct
HTTPS. OCR input-precheck and confirmation-rule PRs are independently reviewed;
combine the selected APK changes explicitly and record its commit/hash. Release
has neither Debug receiver nor loopback policy.

`--relay-config` JSON has exactly python (relay interpreter), device_port and
metrics_directory (outside Git). RelaySession owns local_relay.py and a private
`--no-rebind` reverse lease, removing only its mapping on exit. Listener binds
127.0.0.1; a parent stdin digest authenticates the existing Bearer value without
storing the raw key in argv/config/logs/environment. Upstream is fixed HTTPS
OpenRouter, two POST paths chat/completions and systemone, standard host CA and
hostname checks. No redirects, extra retries, cookies or proxy environment.
Raw request/response bytes are bounded, not parsed or rewritten. Whole-call
315-second relay ceiling does not shorten the app's existing 300-second timeout.
Metadata contains timing/bytes/status only, never headers, screenshots or bodies.

## Summary and historical compatibility

```sh
python summarize.py "$NEW_RESULTS" > "$SUMMARY_JSON"
```

Only named evaluation records count as episodes; transport-session/freeze files
are sidecars, learning is separate, unfinished evidence is reported. Exactly
three complete matching trials are required for SR mean/sample std and pass^3;
otherwise those metrics are null. False done's declared_done denominator,
difficulty coverage, wall/steps/refunds/fast_steps/escalations/audits, OCR evidence
and critical/background tokens retain observed/missing counts. outside_task is
not silently included. Duplicate episodes are rejected.

An optional exact four-record historical parameter-recording migration remains
available via `--resume-compatibility`; it is not a generic source/APK exception
and cannot authorize mixing a new core version with old scores. New uniform-main
trials use fresh directories, not that migration. Historical diagnostics and
one-time state recovery tools remain outside this repository.

PR candidate validation does not establish a merged-main benchmark baseline.
No formal scores, model/network authentication or new trial are claimed by the
installation/contract checks. After review and manual merge, build from main,
record commit/APK hash, validate actual OCR and obtain the next run authorization.
