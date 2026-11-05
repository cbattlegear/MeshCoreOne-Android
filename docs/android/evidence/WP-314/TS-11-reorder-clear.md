# WP-314 TS-11: mounted trace path editing controls

## Candidate and source

- Repository: `cbattlegear/MeshCoreOne-Android`
- Work package / acceptance: WP-314, TS-11, `WP-314-behavior`, `WP-314-boundaries`
- Candidate branch: `cbattlegear-trace-path-controls`
- Candidate base: `f93e2012049feb4d6be728f32bd661665d71161c`
- Source reference: `db14559b39d32322b06477c6ae676112f583db50`
- Verified source/test candidate: `2cb5dbbeabd97d94a1a32d9d8ab5f46a5c5d8e3f`
- Changed implementation paths:
  - `android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/trace/TracePathUi.kt`
  - `android/feature/tools/src/test/kotlin/com/meshcoreone/android/feature/tools/trace/TraceCodeInputComposeTest.kt`

The pinned Swift trace list uses `onMove` and `onDelete`, and the trace screen presents a destructive
clear-path confirmation before calling `clearPath`. The Android UI now exposes accessible up/down
controls, edge-disabled states, existing removal, and a localized clear-path confirmation. Moves use
the state holder's original-list insertion-offset contract. All edits call the existing holder, so its
saved-path/result invalidation, error clearing, trace-hash override reset, and auto-return behavior
remain authoritative.

## Regression coverage added

`TraceCodeInputComposeTest` mounts the production `TracePathListContent` with the real
`TracePathStateHolder`. Its new interactions cover up/down ordering and boundary eligibility,
48 dp controls, existing hop removal, stale saved-path/result invalidation, clear confirmation and
cancellation, hash-override reset, and preservation of the auto-return setting.

## Checks and execution status

| Check | Result |
|---|---|
| Pinned Windows preflight: `python .\\tools\\android-port\\controller\\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $output` with a fresh absolute TEMP output and pinned Python 3.12.4 | PASS (exit 0; readiness only) |
| `git diff --check` | PASS on the source/test candidate |
| Full existing `android-ci.yml` manual workflow, run [41984214970](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41984214970), exact head `2cb5dbbeabd97d94a1a32d9d8ab5f46a5c5d8e3f` | PASS; all six workflow jobs succeeded |
| `:feature:tools:testDebugUnitTest` mounted/JVM tests | PASS: 719 discovered, 719 run, 719 passed, 0 failed/errors/skipped |
| Mounted test discovery in `TraceCodeInputComposeTest` | PASS: both `mountedPathControlsReorderWithinBoundariesAndPreserveRemovalInvalidation` and `mountedClearPathRequiresConfirmationAndResetsTheTraceHashOverride` are in the 39-method runner inventory; both are included in the passing module run |
| Swift codec oracle and SwiftData restore/export oracle | PASS |
| APK inspection and static alignment | PASS; this is static hosted evidence, not device execution |

The trusted WP-314 manifest has `verification.configured=false` and an empty
`commands` list. For an admitted bounded repair of a landed WP, the trusted
`docs/android/ci/README.md` lane permits the existing WP-003 preflight, scaffold,
and inspect commands; no manifest mutation, Gradle task, test selector, or new
runner was added. The earlier exact-candidate run `41982469986` discovered 719
tools tests but failed one mounted assertion because the expected accessible
label omitted the hash rendered by `TracePathHop.displayText`; that run remains
failed evidence and is not treated as a pass. The successor derives expected
labels from the real holder's current hop and passed the full existing workflow.
The successful run's actual method inventory contains both added mounted tests,
and its module JUnit report records all 719 cases passing with no skips/errors.
The mounted tests run in JVM Robolectric/Compose, not on an emulator or physical
device.

No device, firmware, radio reception, or physical accessibility evidence is claimed. This TS-11 UI
change does not alter radio or firmware behavior. This evidence covers only the TS-11 slice and does
not complete WP-314 or accept its other original-case families.

## Current-main integration successor

The preserved source/test candidate was composed without rewriting commits onto main
`1a8b406a67d44006f14ac043ac468b6adf07a9d7`, producing `e5645c5f1f06cbf94f7db8a75f09d35c1e8b96fd`.
Independent review of that exact head found a real running-edit lifetime defect:
the holder invalidated path bytes but retained its pending trace tag. No acceptance
receipt was issued for that candidate.

The successor retires active execution on path edits at the actual holder/runner boundary,
not only at rendered UI eligibility. It fences outstanding send/lookup/batch continuations
and saved-history refreshes so old responses, errors and timeouts cannot resurrect edited
presentation or overwrite a newer run. The explicit native safety adaptation is recorded
in `docs/android/deviations/WP-314.md`. Added assertions include held-send success/failure,
late-response and timeout controls, partial-history preservation, a newer execution,
suspended saved-path work, and stale mounted reorder/clear-confirmation callbacks.
Retained row callbacks additionally bind the captured hop UUID at its index, and history
refresh binds the full radio/path key and a separate path-selection revision, including
clear/reselect while preserving valid refreshes across new runs on the same selection.
Auto Return changes retire the old wire route; unchanged assignments remain no-ops.
Adjacent unmounted save APIs retain accepted durable history without reselecting a
cleared/changed route after held create/fetch.
Existing assertions are retained except the explicitly documented active-batch continuation
expectation adapted below. Successor compile, actual method discovery and execution are
pending; neither historical passing run nor source inventory proves this successor.

The original current-main diagnostic `41987819845` passed its exact `e564` software checks
but did not clear the independent lifecycle finding. Subsequent `34ed` diagnostic
`41988598761` failed the existing mounted batch-edit control because it expected the
continuing batch to automatically send the edited route. Its 725 tools cases included one
failure and mandatory aggregate reports were incomplete; no passing acceptance is inferred.
The control is updated to the explicit safety adaptation: retire on edit, retain/hide old
history, reject late responses and require a new explicit run of the changed route with
isolated statistics. Its original route/statistics discrimination remains asserted.
Batch mode changes now retire pending execution in both directions. History refresh also
orders actual snapshots by fetch ticket so a held older return cannot regress visible runs.
Exact `d444` run `41989167627` discovered 727 tools cases with two failures; exact `f080`
run `41989676506` discovered 731 with the same two failing methods. In addition to the batch
control, the mounted retained-row callback exposed Compose rebinding its Clickable delegate
after a keyed row moved. Explicit immutable semantics callbacks now preserve the rendered
hop/index guard. These failed runs remain authoritative for their own heads only.
Independent `9ac` review additionally identified a retained move-down action whose hop stayed
at the same index while its live boundary changed, and a stable-feature screen reconnect
that did not retire the holder. The successor checks the live boundary, makes unchanged
moves true no-ops, and retires the actual mounted radio/generation lifetime without discarding
the editable route or committed history. New mounted and virtual-time cases cover held
lookup/send, both radio-change and same-radio reconnect, old response/timeout/refresh, and
successful fresh traces. Active `9ac` diagnostic/ref remain immutable and do not verify these
successor changes.
