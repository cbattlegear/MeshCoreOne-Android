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
