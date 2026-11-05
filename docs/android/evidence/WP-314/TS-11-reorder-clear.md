# WP-314 TS-11: mounted trace path editing controls

## Candidate and source

- Repository: `cbattlegear/MeshCoreOne-Android`
- Work package / acceptance: WP-314, TS-11, `WP-314-behavior`, `WP-314-boundaries`
- Candidate branch: `cbattlegear-trace-path-controls`
- Candidate base: `f93e2012049feb4d6be728f32bd661665d71161c`
- Source reference: `db14559b39d32322b06477c6ae676112f583db50`
- Candidate commit: this evidence is committed with the source and mounted-test changes; use the containing Git commit for exact-candidate diagnostics.

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
| `git diff --check` | PASS |
| Android compile / mounted Compose tests / test discovery | Pending exact-candidate hosted workflow |

The trusted WP-314 manifest has `verification.configured=false` and an empty
`commands` list. For an admitted bounded repair of a landed WP, the trusted
`docs/android/ci/README.md` lane permits the existing WP-003 preflight, scaffold,
and inspect commands; no manifest mutation, Gradle task, test selector, or new
runner was added. The exact candidate is being sent through the existing
`android-ci.yml` manual full-verification workflow on this fresh branch. Test
discovery and execution counts remain unavailable until that hosted run completes.

No device, firmware, radio reception, or physical accessibility evidence is claimed. This TS-11 UI
change does not alter radio or firmware behavior. This evidence covers only the TS-11 slice and does
not complete WP-314 or accept its other original-case families.
