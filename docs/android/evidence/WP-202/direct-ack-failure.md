# WP-202 direct-ACK failure-write controls

## Immutable scope and ownership

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Integration base: `5b1fa7bede0fa21c76cc6736dd6ae6b54b1532e6`; ancestry verified.
- Merged service input: `456fd45082c6522201f79440410dedbf87764820`; ancestry verified.
- Test-only handoff: `7cb07c05850066707af8d2d3e04ea9cab2a78f04`, directly based on that integration base.
- Swift specification: `db14559b39d32322b06477c6ae676112f583db50`, unchanged.
  The current and pinned `MessageServiceACKTests.swift` blob is
  `4fd9ed43f0b55fc8999d8042c8df4fbab702c276`.
- Trusted policy blob: `b12794157978d6d90ab7508d4e2852fdc2d5e4a4`;
  trusted manifest blob: `e81605857f5aa7b893dcd63224fb27a45d7b0f3a`.
- Assigned WP-202 session: `354f20a1-bfb2-44c5-8af6-d542adae9770`.
  Paired service producer: `13a03908-afb8-42bd-9c96-57a27920076e`.
  Existing data producer `b5a401f1-8ec2-46e2-96b8-325468cd9761` explicitly
  confirmed no active semantic overlap and no #550/#561 integration prerequisite.

Actual trusted admission succeeded for `create` on
`android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/DirectAckFailurePersistenceTest.kt`
and this evidence path, both using `wp-owned`. Its invariants are one WP/branch/PR,
pinned source, declared verification and evidence provenance; validation requires
manifest-declared commands and trusted ownership/traceability validators.

The actual reservation claim refused the existing WP-202 record's different
source/base/manifest/backend binding. That historical advisory record was neither
released, recreated, rebound nor impersonated. Per the bounded assignment, this
advisory mismatch is not treated as a path-capability denial. No incumbent source,
branch or checkout was modified. No service, contract, DAO or schema edit is in
this handoff; frozen candidates, held #495 sources and channel ERROR2 semantics
remain untouched.

## Source finding and authority boundary

This is **source evidence, not an executed finding**. At the integration base:

- `MessageRepository.updateMessageStatusUnlessDelivered` returns a Boolean after
  `RoomRepositoryContext.write`, without an operation identity or commit receipt.
- `MessageDao.setStatusUnlessDelivered` excludes DELIVERED, not independently
  terminal FAILED; `setAck` refuses FAILED-to-DELIVERED.
- The service installs `failedAckRestorations` through
  `settleFailedPendingAck` only after a successful FAILED-write return.
  `reconcileAcknowledgementLocked` can retire the pending entry despite a
  non-DELIVERED readback.

A thrown return does not authenticate which writer committed FAILED. A generic
FAILED readback therefore cannot justify SENT restoration or ACK delivery:
both this writer's commit-then-throw and a competing writer's terminal failure
can produce it. No storage API/schema change or purported writer proof was
invented. The paired service repair conservatively retains the receipt-bound
ACK and original uncertainty cause rather than claiming delivery; authenticated
recovery of that FAILED row would require an additional operation-authority
contract, not inference from these tests.

## Deterministic real-Room test contract

`DirectAckFailurePersistenceTest` contains ten declared JUnit cases using native
SQLite-backed in-memory Room on Robolectric SDK31, the actual process store,
actual `MessageService`, actual `MeshCoreSession`, raw protocol ACK packets and
a deterministic coroutine clock. This is not device or hardware evidence.

The wrapper holds immediately before delegating the actual FAILED writer,
while the service holds its message mutation lock. The initial committed row
is SENT with one live pending ACK. A raw matching ACK is injected and the test
scheduler drained before releasing the writer. ACK registration is checked
through public behavior: the pending count remains one, no ACK persistence has
entered, and a second failure sweep completes without taking the held mutation
lock because the entry is already marked acknowledged. Then the wrapper lets
Room commit and throws the original typed persistence error.

| Case | Required outcome |
| --- | --- |
| `failedWriterCommitsThenThrowsAfterMatchingRawAckRetainsUncertainty` | FAILED readback, retained ACK, original cause reported, no delivery event |
| `expiredAckWriterCommitsThenThrowsAfterMatchingRawAckRetainsUncertainty` | Same outcome through the expiry writer |
| `throwBeforeRoomCommitStillAllowsTheRegisteredAckToDeliver` | SENT remains eligible for real ACK commit; DELIVERED readback and original trip time |
| `successfulRoomFailureReturnAllowsItsInFlightAckToDeliver` | Successful writer-return control preserves existing in-flight ACK handling |
| `independentlyTerminalFailedRowCannotBeResurrectedByAnInFlightAck` | Prior terminal FAILED remains FAILED |
| `genuinelyLateAckAfterCompletedFailureRetirementCannotResurrectRoomRow` | Retired tracking is not recreated; unmatched diagnostic, no delivery |
| `competingFailedWriterCannotBeMistakenForTheThrowingWritersCommit` | Different writer's FAILED remains terminal; uncertainty is not commit authority |
| `competingDeliveredWriterRemainsDeliveredWhenFailedWriterThrows` | Room refuses FAILED over DELIVERED; matching ACK can reconcile delivery |
| `replacementSessionCannotBorrowTheOldInstancesUncertaintyOrPendingAck` | Original teardown reports cause and retires tracking; new generation has no inherited authority |
| `replacementWhileFailedWriterAndRecordedAckAreInFlightCannotMutateSuccessorDelivery` | Replacement starts before the held writer returns; old live reconciliation and teardown cannot turn uncertain FAILED into successor delivery |

These are Android-only race/uncertainty controls, not additional claimed Swift
original-case acceptance. Existing tests and their provenance remain unchanged.
Assertions target the paired service repair; retention cases are expected to
fail on the unfixed base, but that expectation has not yet been executed.

## Checks and execution blocker

Executed successfully with Python 3.12.4:

- `python .\tools\android-port\local\reserve.py admit --wp WP-202 --path android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/repository/DirectAckFailurePersistenceTest.kt --operation create`
- `python .\tools\android-port\local\reserve.py admit --wp WP-202 --path docs/android/evidence/WP-202/direct-ack-failure.md --operation create`
- `python .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $output`
  with the existing Windows state and fresh absolute output
  `C:\Users\camer\AppData\Local\Temp\MeshCoreOne-ack-preflight-c432ccd62c1143fe8902d2cda1e7d2d9`;
  result: `environment_ready=true`. This is input readiness, not test execution.
- `python .\tools\android-port\controller\validate.py`: valid, 64 WPs and
  1,866 tracked reference files.
- `python .\tools\android-port\portmap.py`: exit 0.
- `git diff --check`: exit 0.

**Kotlin compilation and all ten runtime cases are unverified.** No passing
test-discovery count is claimed. The retained hard `gradle-execution` owner is
`local-check:f516a7e271f444dd9cb2efcc8b995a16`; no Gradle/native invocation,
recovery, runtime deployment or ownership bypass was attempted.

The existing declared software lane reaches `:core:data:testDebugUnitTest`
through scaffold verification. Legitimate exact-candidate hosted execution of
the integrated service-plus-test commit is still required, including positive
test discovery. Hosted authentication is available; no PR was requested or
created for this isolated handoff. The coordinator explicitly authorized an
`android-ci.yml` workflow dispatch on this unique pushed branch after composing
the immutable service fix `74529ee8ec743ff78e2512673878183ca3da1255`. That existing
dispatch selects full scopes and is diagnostic only: a prior app failure may
prevent the target Room cases from running. Actual task/JUnit discovery is
required, not just workflow admission. Its exact-commit job result/log must be
the authority; no duplicate CI result bundle is retained here.

This bounded repair test work does not complete the unresolved original
WP-202 acceptance scope.
