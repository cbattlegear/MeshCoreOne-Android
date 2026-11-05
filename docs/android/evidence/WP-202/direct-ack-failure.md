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

`DirectAckFailurePersistenceTest` contains eleven declared JUnit cases using native
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
| `actualRoomCommitThenCallerCancellationRetainsAckWithoutCancellingLiveMonitor` | A delegating SQLite helper cancels the real caller after successful `endTransaction`; Room propagates the original cancellation, uncertain ACK survives, and a second independent raw ACK still delivers |

The cancellation control does not manufacture a thrown return in the store
wrapper. A test-only `SupportSQLiteOpenHelper` delegates all SQLite operations,
then cancels the actual caller job only after the real transaction has ended
and its FAILED row is readable. This exercises the existing
`RoomRepositoryContext.committedTransaction` late-cancellation path. The
cancelled return is not recorded as successful writer authority. The paired
service correction `d6f278821e4468027eda5f767f3efa19adf94621` preserves that
original cancellation for caller propagation and diagnostics, while subsequent
live reconciliation uses a typed persistence error rather than cancelling
the fresh event monitor.

The cancellation control also starts a successor after the incomplete original
teardown and sends the exact original raw ACK again. Its required outcome is
explicitly unresolved FAILED with an unmatched-ACK diagnostic, not DELIVERED.
The original in-memory receipt has been retired; the teardown issue is not
a durable receipt and cannot give the successor authority to revive that row.
The genuinely late, independent terminal FAILED and earlier replacement
controls remain separate and unchanged.

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

**All eleven runtime cases are unverified.** No passing
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

## First hosted diagnostic outcome

The authoritative [run 41938806937](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41938806937)
executed exact candidate `864fe8c2b42e2bc1463faa078b3ad27a528e76af`. It failed
`:core:data:compileDebugUnitTestKotlin`: four expected event lists needed an
explicit `MessageStatusEvent` element type and the signals state/companion
factory needed explicit types to break recursive inference. Those owned test
errors are corrected in the successor. Mandatory Room and service JUnit reports
were missing; unrelated reported tests are not target acceptance. No runtime
failure reproduction or passing target case is claimed from that run.

The first candidate is not a landing candidate: in addition to its test compile
errors, service-owner review found that a real Room commit can throw
`CancellationException` on return, outside the original typed persistence catch.
The successor includes the owner's immutable cancellation correction and the
real commit-boundary control above. The existing run is not rerun or cancelled;
the successor requires its own exact pushed-SHA diagnostic dispatch.

## Second hosted diagnostic outcome

The authoritative [run 41940596230](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41940596230)
executed exact candidate `1709ec5e6b796a4ab7906d3844e9c7dddea4ceae`. Target
compilation succeeded and tests actually ran: data reported 624 tests with
11 failures; services reported 2,987 tests with six failures. Ten new Room
cases stopped at the ACK-spy barrier because the initial real send had already
called `updateMessageAck(..., SENT)`. The corrected spy counts DELIVERED writes
only, preserving the assertion that delivery persistence cannot pass the held
mutation lock. The genuinely late ACK control passed; these results do not
establish that the blocked interleavings passed.

The original Room no-ACK cancellation/explicit-retry test also failed and is
preserved without assertion changes. The service owner supplied correction
`d44b7aefae6439b613fb2548cf918afca8b2a58d`: retain cancellation uncertainty
without prematurely reporting failure before an ACK exists. The new
service-only replacement collectors explicitly observe the expected stale
NotConnected stream termination, and inline cancellation assertions preserve
the original cancellation in the recovered cause chain. These corrections
require a fresh candidate run, not acceptance from the prior failed job.

## Durable authority feasibility boundary

The current schema is version 1. `MessageEntity`, `MessageDao` and
`MessagePersisting` contain no persisted attempt receipt, message incarnation
or mutation revision. An operation UUID or matching FAILED value is not proof
of the writer. Reusing an existing status, ACK, timestamp or send-count column
would corrupt its current contract rather than create authenticated authority.

Trusted WP-202 admission succeeds for `MessageEntities.kt` and
`MeshCoreDatabase.kt` with `update-directly-required-entity-or-dao`
(`persistence-schemas`), and for `MessagePersisting.kt` with
`modify-related-consumer` (`reviewed-support-consumer`). This is not a denied
capability or historical reservation blocker. No such production/schema edit
has been made in this candidate.

A safe future contract needs a repository-owned persisted direct-attempt
identity before an ambiguous write, an atomic conditional FAILED transition
plus that exact attempt's receipt, and an atomic ACK-to-DELIVERED operation
that validates message/radio/peer/ACK identity and the current incarnation
and mutation revision. Competing status writes, upserts, deletion/recreation
and restores must invalidate stale authority; unresolved-attempt enumeration
and retirement must be durable and idempotent. A thrown return must read the
existing receipt, never mint a replacement proof. This requires a forward
schema migration and coverage of every affected mutation surface, not a local
service marker or unrestricted FAILED-to-SENT operation.

Current schema-integration producer
`f5fe197b-40c2-44b8-aa63-6643c3d3469f` was contacted for the exact shared
schema/base/version prerequisite; no held `6d41` schema, frozen `0cce`, old
channel candidate or another producer's checkout was imported or edited.
Even such a receipt cannot recover an ACK received while storage is
unavailable if the process dies before the ACK itself reaches durable ingress.
Without an acknowledged durable journal or firmware replay guarantee, that
case must remain explicitly unresolved; no process-death or exactly-once
recovery is claimed.
