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
route/result/connection replacement discard the dialog. Already accepted writes
remain governed by the holder's existing captured-radio and revision checks.
Suggested-name formatting now uses the existing generated localization functions.

All five changed paths are admitted by trusted base `wp-owned` capabilities
(modify production/deviations, test existing mounted class, create per-WP evidence).
The pinned-source, one-WP/branch/PR, declared-verification and provenance
invariants are unchanged.

## Mounted regression controls

Thirteen new methods in `TraceCodeInputComposeTest` drive the real state holder after
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
  paths or repeated runs.
- Edited and failed routes with no storage write.
- Retained confirmation after same/different-radio reconnect and fresh B Save.
- Accepted A write completing after same/different-radio reconnect without
  replacing fresh B state.
- French labels/name entry at 320dp and 200% font.

## Verification

Native Windows `ci.py preflight --local --state $env:ANDROID_CI_STATE --output
<fresh absolute TEMP directory>` passed with pinned Python 3.12.4. No local
Gradle build or execution-owner recovery was attempted. Exact normal PR CI and
actual method discovery are pending; declarations are not runtime evidence.

Physical device/radio/firmware, emulator, TalkBack and full-WP-314 acceptance
remain unverified. Earlier TS-11 diagnostic refs/results and held TS-08 work
are preserved.
