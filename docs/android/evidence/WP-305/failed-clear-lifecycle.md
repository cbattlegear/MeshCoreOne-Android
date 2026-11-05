# Failed Repeat snapshot retirement

## Binding and scope

Repository: `cbattlegear/MeshCoreOne-Android`. WP-305 related-consumer repair for
the snapshot-lifecycle finding on PR #585, review comment `4481469541`.
Assignment: `call_uPSigjql7Puj1Aj8ebGTdNbv`; implementation session
`6f720651-7ede-4f87-9b7b-113cb2957b6b`.
Pinned Swift source remains `db14559b39d32322b06477c6ae676112f583db50`.

The repair baseline is the exact tree of
`033b3c43bc33129d273ae7772ba5e5b814d99e70`
(`4e5420ee59691c22cac4a22f4090cafb19bd3b28`). Merge commit `711ee1b96`
retains this session's earlier authority history while using that integration
tree without additional product changes. Integration should transfer only the
bounded delta against `033b3c43`, not import the historical branch ancestry.
No OnboardingFlags, runtime, Room implementation, schema, held reference,
protected map test or source-pin change is included.

Trusted `origin/main` capability admission maps the editor, shared contract and
Settings service to `reviewed-support-consumer` / `modify-related-consumer`;
the two test files map to `reviewed-support-test` /
`add-or-update-related-test`. Evidence/deviations use the manifest-owned WP-305
paths and `record-evidence` / `modify`. Settings owner `244fb3ac` transferred
the Repeat harness's bounded edit custody; OnboardingFlags remains with `6e767589`.

## Cause and repair

Verified Repeat-off is persisted before the separate snapshot clear. A clear
failure or cancellation leaves a consumed baseline in Room. If the holder observes
the already durable off value after its failure handler, the toggle becomes off;
a later manual RF edit and enable preserve that obsolete baseline. The next off
can restore RF from the previous cycle.

`RepeatSnapshotRestoreAuthority.onRestoreVerified()` is an additive synchronous
hook. The actual Settings service invokes it only after successful RF readback
and verified Repeat-off, before either persistence publication. The editor's
captured authority records retirement under the current token's short publication
guard, without changing the device value or suspending. The receipt binds stable
device identity, session token, identity epoch, all four captured snapshot fields
and a monotonic snapshot epoch. It is not created by an arbitrary clear request,
SET dispatch, transport ACK alone, failed verification or imported data.

Shared database-owned custody survives holder/editor replacement and delegating
store wrappers. Before a new awaited snapshot preparation or restore validation,
the editor retries the pending clear against the authoritative row. Unrelated
revisions/RF edits are preserved; identity changes, changed snapshots, equal-value
snapshot ABA and still-active Repeat fail closed rather than erasing new data.
Every preparation transform and actual restore admission also checks the fence.
The existing committed-all-null observer alone removes retirement records.
A genuinely active successor token retains its independently observed restoration
snapshot; a retired token cannot mark or clear its successor.

After a matching clear commits, normal preparation snapshots the current RF.
Legacy asynchronous preparation refuses the pending retirement through existing
diagnostics instead of reusing it. The original uncertain-creation custody,
partial/imported snapshot behavior, exact abort receipt, actual send permit,
runtime authority and bounded CAS retry remain in place.

## Deterministic controls

- `delayedRepeatOffObservationAfterFailedClearCannotReuseThePreviousBaseline`:
  actual App graph, service, protocol transport, editor and Room; only the holder
  observation is delayed. Covers failed clear, cancellation in clear, and
  cancellation after acknowledged restore but before the editor clear API.
  Requires durable off with the old snapshot, delayed toggle-off, actual later
  manual RF, zero additional SET while retirement still fails, then fresh
  enable/off restoring the manual RF with all four snapshot fields cleared and
  exact live/Room and frame-order assertions.
- `confirmedRestoreRetirementIsSharedAcrossEditorsAndRejectsSnapshotAba`:
  independent editors and two stores on the same actual Room database; unrelated
  full-row revision reconciles, snapshot ABA refuses without writes, and an
  actual committed explicit clear allows fresh preparation.
- `restoreRetirementRequiresVerifiedRfAndRepeatOffBeforeEitherPublication`:
  actual Settings service/protocol for success, cancellation before ACK, RF
  mismatch and Repeat mismatch. The hook precedes both durable publications only
  for verified success.

All twenty original Repeat/preset method bodies are retained. The test helper's
restore-authority decorator forwards the new hook to the actual editor authority.
The five normal-preset controls and null-token scope guard remain unchanged.

## Verification

Native Windows readiness passed with pinned `C:\Python312\python.exe`:
`tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE
--output <fresh absolute temporary directory>`. No local Gradle invocation or
retained execution-owner recovery was attempted.

Source checks passed using that interpreter:

- `tools\android-port\controller\validate.py`
- `tools\android-port\portmap.py`
- `android\parity-tests\audit.py --check`
- `android\parity-tests\semantic.py --check`
- `tools\android-port\controller\verification_config.py --check`
- `git diff --check`

Test-only predecessor `d05509a9b20cf51df04ea24c7862c32a51c76896` is published
as `diagnostic/wp305-failedclear-d05509a9`, with hosted run
`42059575897`. This exact predecessor contains the save/cancel-clear regression
before the production repair and before the additional controls; its result
must not be transferred to a successor. At writing, hosted execution is pending.
No Kotlin execution, hardware, signing, iOS UI or whole-WP acceptance is claimed
by the source checks or prior #585 greens.
