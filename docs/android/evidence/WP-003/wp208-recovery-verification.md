# WP-003 bounded WP-208 recovery verification support

Issue: #452; original-family scope: #388; merged recovery repair: #453.
This is a test-only support candidate, not full WP-208 acceptance or a
dependency unlock. No production, build, manifest, frozen Swift or gate changes.

## Identity and admitted authoring

Repository: `cbattlegear/MeshCoreOne-Android`.
Assignment session: `a6f49891-8c2a-4970-aa26-ec66d845065a`.
Branch: `cbattlegear-wp208-recovery-verification`.
Base: `d7f35bbe938ca79d3bc8ab5510fb5cfa9cf8a808`.
Source: `db14559b39d32322b06477c6ae676112f583db50`.

Trusted `reserve.py admit` accepts the existing Room messaging test under
WP-003 / `reviewed-support-test` / `add-or-update-related-test`. It accepts this
new support document under `wp-owned` / `modify`. Preserve existing tests and
primary source ownership; test admission does not authorize production changes.

The actual-session claim at this base returned
`Existing WP lease is bound to different source/base/manifest/backend`.
The historical WP-003 revision1 and WP-202 revision16 receipts are unchanged.
Per trusted `AUTOMATION.md`, this failed advisory bookkeeping is not a pre-edit
lock for assigned, isolated, capability-admitted source work. It does not establish
successor authority. The archived WP-202 producer's #471 is merged; the open
WP-003 #494 file inventory does not contain the Room test or this new document.
No actual live semantic overlap was found. No claim was released, recreated,
rebound, impersonated or manually edited.

## Seven original-family correspondence

All methods below are in the existing
`android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/messaging/MessagingRoomIntegrationTest.kt`.
They exercise RoomPersistenceStore, real MessageService/ChatSendQueueService,
MeshCoreSession and raw simulated firmware with an injected scheduler.
Robolectric SDK31/native SQLite is not physical-device or hardware evidence.

| Family | Room method | Outcome |
| --- | --- | --- |
| Transient persisted-row, 200ms | `theActualTransientQueueFailureKeepsItsCommittedEnvelopeWhileParked` | Adds the missing 200ms scheduler interval after the real drain's park; retains exact pending envelope/attempt, message status, packet count and all accumulated events. |
| Transient DM no-failed-event, 300ms | `theActualTransientQueueFailureNeverPublishesAFailedBubble` | Existing 300ms observation with attemptCap2/baseDelay0.01 and no Failed event is preserved. |
| Connected-not-ready 1000ms, then READY | `actualConnectedStateCannotWakeTheParkButTheNextReadyEdgeCan` | Preserves 1000ms/READY stimulus; additionally asserts pending status and three packets before READY, then six packets at attempt2. |
| Hydrate once | `theActualSecondHydrateCannotEnqueueANewlyPersistedRowOnTheSameService` | Existing first drain/new durable row/second-hydrate no-op and foreign-radio assertions preserved. |
| Expired ACK failure | `expiredAcceptedAckCommitsFailureAndPublishesItOnceThroughTheActualRoomService` | Existing actual accepted DM, 60-second expiry, complete failed DTO, tracking retirement and exactly one event preserved. |
| Fail-all non-delivered | `failAllCommitsBothAcceptedRoomFailuresAndPublishesEachExactlyOnce` | Existing two accepted DMs, complete failed DTOs, repeat no-op and exact events preserved. |
| Fail-all delivered no-op | `failAllLeavesTheActualDeliveredRowIntactAndRetiresOnlyItsTracking` | Existing complete delivered DTO/no failure event/tracking retirement preserved. |

The first original Swift family uses an unconnected SimulatorMockTransport;
the existing Room companion uses a ready real session and transient firmware
pool failure. The retained scheduler interval addresses the observation gap,
but independent review must assess this native stimulus equivalence.
The two selected Swift test files remain byte-identical to the pin.

## Supplemental recovery methods

Five new methods exercise the maintainer-selected recoverable Android policy:

- `actualRoomFailAllRetainsEachFailedEntryAndRetriesWithoutDuplicateEvents`:
  two distinct typed storage failures, exact first/suppressed causes, healthy
  later-entry progress, retained rows/tracking, keyed retry order, full recovered
  DTOs, foreign-radio isolation, once-only events and complete teardown.
- `cancellingActualRoomFailAllPreservesUncommittedRowsAndAllowsExplicitRetry`:
  suspend the first store admission, propagate cancellation without later writes,
  retain full rows/tracking and no event, then commit an explicit retry once.
- `actualRoomFailAllStopsAfterOwnershipWithdrawalWithoutPublishingStaleFailure`:
  replace the actual signal token while the first persistence call is suspended;
  retain the first admitted write's consequence, stop later writes, retain
  unresolved tracking and propagate NotConnected without stale events.
- `actualRoomHydrationReadFailureRetriesItsOriginalEnvelopeAndThenLatches`:
  typed failed read leaves the actual durable envelope intact, retries once,
  delivers without identity/timestamp rewriting or duplicate wire sends, then
  latches only successful hydration.
- `cancellingActualRoomHydrationObserverKeepsOneSharedReadAndOneWireSend`:
  cancelled observer cannot cancel the owned shared read; the second observer
  awaits that same read and delivers the durable message exactly once.

Narrow persistence decorators inject failure/suspension at the service boundary;
successful operations delegate to the actual Room store. They are not a fake
repository or proof of SQLite-generated errors. No original family is replaced
by these supplemental methods or credited merely by its annotation.

## Trusted software verification contract

Use the existing bounded `android-scaffold` route:
`python tools\android-port\controller\ci.py run --stage scaffold`.
Its hosted job identity is `One Linux Android build`. The current deployed
workflow's actual affected/scoped arguments select modules and consumers.
For local immutable candidates, use the documented interlocked entrance:
`python tools\android-port\local\check.py --commit <candidate-SHA> --affected-base <current-origin-default-base-SHA>`.
The trusted selector must include data and services/required consumers; explicit
scopes may only add obligations, never invent Gradle tasks or remove obligations.
No whole-WP verification amendment is needed to use this declared bounded lane.

Positive actual compiled/executed method discovery and zero mandatory
failures/errors/skips are required. Cache warmth, static correspondence or prior
candidate passes are not current execution. Exact-candidate hosted job/log is
authoritative; no second result bundle is retained.

Verification-only consumer continuity must use a real canonical later producer.
At #453's merge, MessageService blob is
`4063d7a70b40136edee1062a1515b081b29bb41b`; current base is
`36ed5d591b488010f4f0c24d5313834f8d6c3de8`. Do not bind it as unchanged from #453.
At #471 merge `b1b5155d5f9a6a6934ce0cfb260e6b78a73d57f6`, MessageService already
has the current blob; ChatSendQueueService also matches its current blob
`0fb1158c4edef60ed4803d080e09b0b809b1da32`.
Candidate continuity, real producer path inventory, authenticated scope/review
and protected gates still require validation. This document creates no scope
comment, privileged check, review, publisher authority or full-WP receipt.

## Actual checks and limitations

- `python tools\android-port\controller\validate.py`: passed, 64 WPs/178 edges,
  unchanged seven human gates.
- `python tools\android-port\test_inventory.py --check`: passed, 468 pinned
  test/support paths and 5,133 declarations; inventory only, zero ported-case
  execution claimed.
- `python tools\android-port\portmap.py`: passed after removing a conflicting
  mixed AndroidOnly/PortedFrom disposition introduced during authoring. Existing
  source-backed headers remain; traceability is not feature acceptance.
- `git diff --check`: passed.
- `python tools\android-port\controller\ci.py preflight --local --output
  <fresh-absolute-run-directory>`: BLOCKED before product execution:
  `Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
  Python is the pinned 3.12.4; neither this process nor the persisted user
  environment supplies a state. The documented isolated verification interpreter
  is absent. No installation or SDK-license acceptance was attempted.
- `python tools\android-port\local\check.py --commit d82209dfe --affected-base
  d7f35bbe938ca79d3bc8ab5510fb5cfa9cf8a808 --scope module:android/core/data
  --scope module:android/core/services`: BLOCKED before product execution:
  `Installed execution runtime is old or mismatched: controller/execution_recovery.py`.
  Full tested-input candidate identity is
  `d82209dfec5059dc720971ecd449441221db71a4`; this attempt did not compile or
  discover/run product methods. The blocked command is not failed product tests.
  No runtime installation, recovery, lock release or direct-Gradle bypass occurred.

Product compilation and test execution are unverified. The candidate needs the
declared local check after actual host/runtime readiness, or exact-candidate hosted
execution and authenticated review. No iOS, device, radio, signing, upgrade,
bidirectional backup or legal evidence is claimed. Keep `wp_complete:false` and
`dependency_unlock:false`; this support does not close #452 by itself.
