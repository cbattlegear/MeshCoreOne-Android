# WP-314 TS-13: mounted trace-path Save

- Repository: `cbattlegear/MeshCoreOne-Android`; issue #415.
- Integration base: `83fc29b14f082ef1806f2d68516668128f63b1d4`.
- Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Scope: missing mounted Save/name flow only; no TS-08 or other WP acceptance.

## Source and implementation

Pinned `SavePathRowView.swift` generates a suggested name, rejects blank names,
allows cancellation, and passes the original nonblank name to `savePath`.
`TraceResultsSectionView.swift` shows that action only for successful results
that are not already associated with a saved path. Existing holder eligibility,
dispatched width, run history, radio attribution and persistence remain authoritative.

The Android list now mounts a localized Material name dialog and Save action.
Its callbacks capture the rendered connection, route and results, recheck current
eligibility before launch, and call the existing holder/storage operation.
Saving disables repeated confirmation; an actual failure keeps the name and
displays the existing localized save-failure copy for retry. Cancellation and
route/connection replacement discard the dialog; same-batch progress retains it.
Already accepted writes
remain governed by the holder's existing captured-radio and revision checks.
Suggested-name formatting now uses the existing generated localization functions.
Same-batch results arriving during a suspended save are drained before adoption.
A failed-after-create batch save retains its path/run identities so retry reads
actual stored runs and appends only missing ones; it also compares the requested
name with the current stored name. Deleting that partial path clears its receipt.

All five changed paths are admitted by trusted base `wp-owned` capabilities
(modify production/deviations, test existing mounted class, create per-WP evidence).
The pinned-source, one-WP/branch/PR, declared-verification and provenance
invariants are unchanged.

## Mounted regression controls

Fourteen new methods in `TraceCodeInputComposeTest` drive the real state holder after
actual fake-radio dispatch/response correlation and persist through
`InMemoryTracePathStore` (not Room). They cover:

- Suggested names, whitespace-only rejection, cancel/reopen, raw nonblank names,
  exact radio/bytes/dispatched width/SNR/run persistence and no duplicate Save.
- Completed batch runs and their existing storage semantics.
- Storage failure with diagnostics, retained name, visible error and real retry.
- Held storage creation and repeated retained-confirmation rejection, including
  another batch result arriving while the accepted write remains suspended.
  The new result must be persisted too; a failed held write must retain its
  visible name/error and retry all completed runs.
- Failure before/after an appended run commits must retry the same saved-path
  UUID and stable run identities, preserving accepted data without duplicate
  paths or repeated runs, including A-to-B-to-A name retries.
- Mounted failure/cancel, fixture-store deletion plus the real holder deletion
  notification, then mounted Save must create a new usable path rather than
  reusing a deleted partial-save UUID.
- Edited and failed routes with no storage write.
- Retained confirmation after same/different-radio reconnect and fresh B Save.
- Accepted A write completing after same/different-radio reconnect without
  replacing fresh B state.
- French labels/name entry at 320dp and 200% font.

## Verification

Native Windows `ci.py preflight --local --state $env:ANDROID_CI_STATE --output
<fresh absolute TEMP directory>` passed with pinned Python 3.12.4. No local
Gradle build or execution-owner recovery was attempted. The five declared
source commands passed: `controller/verification_config.py --check`,
`controller/validate.py`, `portmap.py`, `test_inventory.py --check` and
`extract_vectors.py --check`, all under `tools/android-port/` with pinned Python.
`git diff --check` passed.

The normal exact-head checks and review on
[PR #593](https://github.com/cbattlegear/MeshCoreOne-Android/pull/593) are the
authoritative runtime evidence; consult their original hosted reports for
actual discovery and outcomes. No diagnostic pair or additional runner is used,
and declarations alone do not establish passing execution.

Preserved failed predecessor `059937a9843c950ac93d7e2cf17955a4b6ed3d3a`,
run [42123958812](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42123958812),
discovered 816 Tools tests with three assertion failures: the French 200%-font
confirmation height, and the two held-save methods selecting a disabled field
via its intentionally absent SetText action. The successor retains the 48dp
assertion and explicitly sizes both dialog actions, and checks the disabled
field's actual EditableText and Disabled semantics without weakening its name
assertion. App discovered 2016 tests and independently failed the unchanged
map-preview memory-trim loading-card timeout at line 412. That separate owned
surface is untouched. Downstream mandatory reports were incomplete; no passing
aggregate is inferred from this run. Superseded cancelled runs are not passes.

Physical device/radio/firmware, emulator, TalkBack and full-WP-314 acceptance
remain unverified. Earlier TS-11 diagnostic refs/results and held TS-08 work
are preserved.
