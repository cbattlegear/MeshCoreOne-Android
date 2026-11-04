# WP-209 contact-write recovery scope

Repair: [issue #517](https://github.com/cbattlegear/MeshCoreOne-Android/issues/517),
producer prerequisite for WP-302's contact-confirmation evidence in
[PR #495](https://github.com/cbattlegear/MeshCoreOne-Android/pull/495).
Owner: `services-porter`, directly assigned Project child
`b3bf2b08-7b1d-4c4d-b34a-e0cafa4ba371`.

## Source and admission

- Reference remains `db14559b39d32322b06477c6ae676112f583db50`.
- Authoring base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
- Manifest SHA-256: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
- Trusted policy Git blob: `b12794157978d6d90ab7508d4e2852fdc2d5e4a4`;
  reservation policy revision:
  `de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.
- WP-207's merged implementation `d147865c15d09b7e9f9956db3e8a0a10e54d5134`
  is an ancestor of the base. Prior WP-209 producer PR #481 is merged at
  `f581d82d64ee64577ccdfeb6120c0e5d607f11a7`.

## Current-main integration

- Current trusted main: `796a872a3565a66c74f782b738a9ecb3c273fb25`.
- Non-rewriting integration commit: `1816a3f47`; its parents are the previously
  diagnosed candidate `a58b015fbde677b1db20fe61b2fba304ce4191a2` and current main.
- The overlapping protocol edits are additive. Current main adds the separate
  `MessageWriteDispatch` messaging overloads to `MessagingSessionOps` and
  `MeshCoreSession`; the WP-209 contact overload remains on
  `ContactSessionOps` and continues to own contact persistence inside the same
  serialized response exchange. The contact guard runs at the actual serialized
  dispatch boundary. `RequestContext` now finalizes cancellation disposition and
  the contact exchange callback before releasing the serializer, so a queued
  same-radio write cannot pass between caller cancellation and recovery marking.
  Ordinary sync excludes writes through snapshot application and pruning.
  Marked recovery now fails before fetch; the earlier count-based marker-clearing
  path is disabled by the safety successor below. The messaging additions do not change contact request bytes,
  ACK correlation or caller cancellation behavior. The merge had no conflict
  markers or discarded edits.
- Main subsequently advanced from `796a872a3565a66c74f782b738a9ecb3c273fb25`
  to `99479711bfdb7c621765f09d671068ca8b8318ce` through PR #529. The intervening
  diff only added the WP-303 BLE integration test and its evidence document;
  neither path overlaps the 17 WP-209 producer paths. They were integrated
  without rewriting at merge commit `d8c51c74d0a084beaa53365c587aea9c04fa3a3d`.
  The prior successful run below remains evidence for its exact `ce21ba518`
  candidate; a new exact-head run is required for the current-main integration.
- All 17 WP-209 changed paths below were re-admitted against main
  `796a872a3565a66c74f782b738a9ecb3c273fb25` with their actual operation and
  trusted capability. The later `99479711bfdb7c621765f09d671068ca8b8318ce`
  advance is disjoint from those paths, so the existing admission cohort remains
  unchanged. The inherited WP-107 additions are base content, not new WP-209
  changed paths.

The supported `reserve.py admit --wp WP-209 --path <path> --operation <operation>`
matched each actual edit before authoring:

| Actual surface | Operation | Trusted capability |
|---|---|---|
| `core/services/.../contacts/ContactService.kt`, `ContactWriteRecovery.kt` | `modify` | `wp-owned` |
| `core/services/.../contacts/ContactsTestSupport.kt`, `ContactWriteRecoveryTests.kt` | `modify` | `wp-owned` |
| `core/services/.../device/DiscoveryTestRoles.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| WP-209 deviation and this evidence document | `modify` | `wp-owned` |
| `core/protocol/.../session/SessionRoles.kt`, `MeshCoreSession.kt`, `RequestContext.kt`, `ChannelWriteDispatch.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `core/contracts/.../domain/DeviceAndContactPersisting.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `core/data/.../repository/ContactRepository.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `app/.../container/AndroidPlatformAdapters.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `core/data/.../repository/ContactPathCoordinatorRoomTest.kt`, `ContactWriteCompletionRoomTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `app/.../container/ContactRecoveryFlagsTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |

These are Android-relative source/test surfaces; no build configuration, resource,
source pin or UI-owned file is changed. The support edits are directly necessary:
the existing protocol owner must retain the persistence continuation, Room must
make the newer-row decision atomically, and the existing process preference
adapter must report durable-write failure rather than claiming recovery was saved.
The discovery test role explicitly rejects the added abstract persistence operation,
preserving the existing repository contract invariant and its unrelated tests.
The production process container passes the same `ContactPreferenceFlags` instance
through each `SessionEnvironment` and replacement radio graph, so the in-process
epoch and sync fence are shared. Its SharedPreferences adapter uses checked
synchronous `commit()`. If a persisted recovery marker is loaded without its
in-memory dispatch provenance, sync now fails closed before snapshot capture;
there is no automatic drain-proof/recovery path for that state today. This is
not device or abrupt-process-death execution evidence.

The initial supported `claim` attempt was refused with
`Existing WP lease is bound to different source/base/manifest/backend`.
Predecessor attempt `05c48790-1100-4158-b981-c97a525a6dcb`, revision 6, is retained.
No new lease/successor is claimed and no historical authorization, ledger or
execution lock is rewritten. Trusted `AUTOMATION.md`'s current local-authoring
contract and the coordinator's explicit direction permit isolated admitted
authoring despite that historical advisory mismatch. WP-302 confirmed no active
ContactService/service-test overlap; its UI stays with its existing owner.

## Behavioral evidence to execute

`ContactWriteRecoveryTests` currently contains 42 authored deterministic methods using an actual
`MeshCoreSession` and controlled raw frames, not a fake contact session:
queued-before-send cancellation; retired caller plus late successful ACK;
identical/different replacement delivery correlation; active and late rejection;
an unanswered possibly-written command that blocks a replacement sync before
snapshot capture and remains blocked after a simulated late radio-side application;
active and orphaned local-save failure; accepted persistence-tail serialization; greater and
equal-timestamp newer metadata, including a live superseded completion that must
not return import success; failed durable recovery recording with explicit
failure and process-retained uncertainty; teardown/different-radio replacement;
queued-write exclusion after cancellation, ordinary subsequent-write admission,
marked-recovery refusal, pre-snapshot refusal while a prior exchange can still
write, invalidation of a queued cross-session write released after an omitted
snapshot is captured but before local application, fail-closed persisted markers
without dispatch provenance, and blocked write admission while a full-snapshot
batch save is held. The fence atomically checks for potentially writable exchanges
before snapshot capture, remains active through ordinary pruning, and
refuses stale snapshot application when the old exchange could have completed
during that interval. No full sync, teardown, reconnect or READY state is treated
as physical drain proof. Service persistence here is an explicit in-memory role,
not claimed Room evidence.

Eight additional service methods cover both orderings of device removal and
snapshot application, local-only deletion behind an older snapshot, cancellation
while waiting without a dispatched removal or uncertainty marker, cancellation
after remove dispatch, transport uncertainty, local-delete failure after device
acknowledgement, and rejection of removal while an Add exchange awaits its
acknowledgement. `removeContact` and `removeLocalContact` use the existing
per-radio `syncMutex`, ordering mutation and cleanup against snapshot
fetch/application without adding a non-reentrant mutex or serializing unrelated
channel/message work. V-contact protection, device `NOT_FOUND` mapping, and
local-only recovery behavior remain unchanged.

The ordering tests pause `batchSaveContacts` only after a complete contact-stream
response, and pause local `deleteContact` only after the device remove response,
so they prove the service fence rather than protocol request serialization.
Another test holds cleanup after the acknowledged device removal and local delete,
then verifies a new snapshot cannot start and observes the `NodeDeleted` event
directly: it must remain unseen while notification cleanup is suspended, then
arrive after the production `ContactCleanupCoordinator` finishes; sync-notification
order and the final event order are also asserted. Cancellation through that
coordinator after acknowledgement is asserted to keep the recovery marker and
block a subsequent contact write. Protected V-contact
remote and local removals are exercised while an Add is active and another Add
is queued; both remain true no-ops, with no remove command, local deletion,
event, recovery marker or queued-write invalidation.
Protected V-contact no-ops also return while an unrelated snapshot is paused in
persistence, without waiting for or mutating that sync fence.

The preceding 32 test methods are authored source controls, not test-discovery or execution
evidence; they remain unexecuted while the shared Gradle reservation is held.
The cleanup-cancellation control injects `CancellationException` from a suspend
cleanup callback rather than cancelling a suspended caller job. No post-failure
recovery-fence reuse control or general callback-deadlock proof is claimed.
Unknown-send injection does not establish a device-committed removal with a lost
acknowledgement.

`ContactWriteCompletionRoomTest` has three real-Room methods for transactional
radio isolation, complete local metadata preservation, stale/equal-time CAS
protection, insertion and intentional equal-time updates.
`ContactRecoveryFlagsTest` has two Robolectric methods for disk-backed adapter
replacement and explicit failed-commit reporting.

These new native regressions supplement, not replace or relabel, the frozen
ContactService original cases. They address `WP-209-behavior` and
`WP-209-boundaries`; they do not complete unrelated WP-209 acceptance.

## Verification boundary

Use the declared interlocked candidate launcher or the existing Android candidate
CI workflow. Local Windows readiness is checked with pinned Python 3.12.4,
the existing same-host state and a fresh absolute preflight output directory as
documented in `docs/android/ci/local-checks.md`.
The shared `gradle-execution` owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` lacks genuine cleanup evidence and
remains untouched. Do not start local Gradle, bypass it through another launcher,
or infer cleanup from process absence.

The exact committed candidate and its original hosted run/job logs are the
authoritative reproducible execution record; no duplicate result bundle belongs
in this directory. A manually dispatched feature-branch diagnostic is not
PR-bound protected-check, local-device, repair-acceptance or merge evidence.
Positive discovery and passing actual Kotlin/Room methods are required before
claiming their behavior verified. The run for `a58b015fbde677b1db20fe61b2fba304ce4191a2`
remains historical diagnostic evidence only; it does not verify the current-main
integration commit or its eventual successor candidate.

WP-302 owns mounted confirmation cancellation/re-entry evidence. No physical
radio, Android device, signing or hardware execution is claimed here. The
passing SwiftData restore/export oracle is not an iOS-device run.

### Exact candidate diagnostics

- Candidate `ce21ba5185d46c036949e0ceb078090cbe37beac`, run
  [41608021764, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41608021764),
  completed successfully with every selected workflow job passing, including
  the controller/policy suite, Linux Android build, Swift codec oracle and
  SwiftData restore/export oracle. The Android logs positively discovered all
  20 `ContactWriteRecoveryTests` invocations; the service test task and build
  job passed. The SwiftData oracle is hosted codec/restore evidence, not an
  iOS-device run. This feature-branch diagnostic is not PR-bound protected
  merge evidence.
- Candidate `87d045629c208c3d38e4af92a8f301db3f7d0636`, run
  [41603718675, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41603718675),
  completed with the scope classifier, Swift codec oracle and controller/policy
  jobs passing, but the Android build failed
  `teardownAndDifferentRadioReplacementCannotSaveAnOldReplyIntoTheNewRadio`.
  The test expected a replacement full sync to clear uncertainty after the old
  session had been torn down, without physical-drain proof. The producer
  correctly rejected that sync; the test now asserts the marker remains set,
  no snapshot is requested, and neither radio's local contact is misattributed.
- Candidate `26cda3e6a441dc787f5fe0465b34ccbb6c98beb6`, run
  [41605428589, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41605428589),
  passed the scope classifier, Swift codec oracle and controller/policy jobs.
  The Android build discovered 2,821 service tests but failed the same
  replacement test because it attempted to reuse the old transport object;
  `SessionTransportOwnership` correctly rejected that second owner before the
  recovery assertion. The test now uses a distinct replacement transport view
  over the same simulated radio state, without treating teardown as drain proof.
- Candidate `8841480ad2603c1d5c8184934c92af8761707d7c`, run
  [41592559682, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41592559682),
  completed successfully. It predates the serialized-admission and sync-fence
  repairs and is historical only.
- Candidate `45b590a3bc83c8da1ede7b189899bf50ca391615`, run
  [41596125999, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41596125999),
  failed three `ContactWriteRecoveryTests` assertions. The follow-up corrected
  the block-reason precedence and used metadata newer than the radio snapshot in
  the post-reconciliation retry cases.
- Candidate `577989425c08fc673535e16d12060f432014557d`, based on current main
  `796a872a3565a66c74f782b738a9ecb3c273fb25`, completed
  [run 41597617286, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41597617286)
  successfully. Every workflow job passed, including `One Linux Android build`
  and its declared parallel Gradle validation/Android tasks. The build report
  positively discovered all 18 `ContactWriteRecoveryTests` methods; the service
  test task completed without failures. This is a feature-branch workflow
  diagnostic, not a PR-bound protected check or merge/repair-acceptance gate.
- Candidate `9f58ec44e4e64b3872509a07797bac95102a8499`, based on current main
  `99479711bfdb7c621765f09d671068ca8b8318ce`, passed selected hosted jobs in
  run [41611480901, attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41611480901).
  This run predates the uncommitted manual-refresh lease changes below and does
  not verify them.

### Manual-refresh/shared-owner arbitration hold

The current worktree has uncommitted changes based on
`9f58ec44e4e64b3872509a07797bac95102a8499`. They add a per-radio manual-refresh
mutex and `ContactService.withManualContactRefresh`, which runs caller cleanup
inside the lease and preserves the existing convenience method. A deterministic
Room/service/coordinator test was added for two manual callers, cancellation
while waiting, reset ordering, and advert deferral. It has not been executed.

Source inspection found that this callback contract alone does not establish
ownership against initial connection sync or disconnect/reconnect:

- `SyncCoordinatorSync.kt:18-23` `tryClaimSync` checks only
  `isSyncInProgress`. `onConnectionEstablished` calls it at lines 222-228
  without checking `manualContactSyncActive`, then sets and clears the same
  advertisement Boolean at lines 253, 292, and failure cleanup 304-306.
- `SyncCoordinatorContacts.kt:170-187` manual claim waits for the advert claim
  and sets a Boolean but does not reserve the general sync claim.
  `performAdvertContactSync` rejects while that Boolean is set at lines 208-212,
  while `SyncCoordinatorSync.kt:326-330` clears it unconditionally on disconnect.
- `AdvertisementService.kt:473-479` implements `setSyncingContacts` as a plain
  Boolean assignment. Capturing the initiating graph's service avoids looking
  up a replacement graph during cleanup, but does not prevent same-instance
  initial-sync/manual owners from clearing one another's flag.
- The consumer graph seam is also separate: `NodesSession` exposes two
  independent service accessors (`NodesFeatureDependencies.kt:109-110`), and
  `AppNodesSession` independently reads `sessions.current` for each one
  (`AppNodesFeature.kt:86-88`). The registry getter is synchronized per read,
  not across the pair (`RadioSessionContainerFactory.kt:53-62`); the current
  state holder resolves the contact service and advert service at separate
  points (`ContactsStateHolder.kt:140,144,164`). Capturing the advert service
  once prevents cleanup from looking up a successor, but does not prove that it
  came from the same graph as the contact service. The consumer must obtain both
  from one captured graph snapshot or another atomic pair seam; no consumer path
  was modified here.
- `ContactWriteRecovery.withSyncFence` serializes contact snapshot application
  per preference-role/radio. It does not own the coordinator and advertisement
  flags, so it is not proof of safe ownership for those interleavings.

The coordinator source belongs to WP-214 (`PORTING_PLAN.md:451`), outside the
WP-209 admitted write paths. No WP-214 path was changed. Do not integrate the
manual-refresh API or publish this producer until an admitted shared contract
coordinates initial-sync, manual-refresh, and disconnect/reconnect ownership,
including owner-scoped advertisement suppression, and a deterministic
overlap/late-cleanup regression covers those paths. The current local
`gradle-execution` reservation remains held without cleanup evidence; no local
Gradle test was run. Run 41611480901 is not evidence for the uncommitted changes.

The WP-214 sync-concerns session reports its current edit scope is disjoint
(ChannelReceiveEnvelope contract, MessagePollingService, associated test and
WP-214 evidence), with no current or intended suppression edits and no active
`AdvertisementService.setSyncingContacts` admission/reservation receipt in that
session. This is not a transfer or release. The coordinator's current
open-PR-path inventory contains no published `AdvertisementService.kt` change,
but that does not prove unpublished owner intent is clear. Although
`PORTING_PLAN.md:446` maps `AdvertisementService` to WP-209, current hunk custody
and path-operation admission for the suppression API/caller cleanup remain
unestablished. No suppression file was edited and no admission or ownership
transfer is claimed.

The local Gradle execution reservation remains held without cleanup evidence;
no local Gradle command was run. The exact workflow logs remain the authoritative
hosted evidence; no second CI-result bundle was committed.

### Delete-vs-snapshot source candidate

The bounded source-only candidate is authored from HEAD
`9f58ec44e4e64b3872509a07797bac95102a8499` and adds the shared mutation fence
and the eight service regression methods above in `ContactService.kt`,
`ContactWriteRecovery.kt`, and `ContactWriteRecoveryTests.kt`. Fresh WP-209
`modify` admission passed for all three paths; `git diff --check` passed and the
trusted `validate.py` ownership/traceability validator passed. WP-209 has no
configured module verification command. The source-only candidate does not
include the unrelated dirty core-data Room test path. The new methods have not
been executed: the shared Gradle execution reservation remains held without
cleanup evidence. The core-data Room test path is not admitted for this work, so
no Room test was added or claimed.

This mutation fence addresses deletion versus a service-owned snapshot. It does
not resolve the separate protocol invalidation/retirement-to-Room-prune/marker
linearization prerequisite. The WP-107 proof-only candidate remains unimported
and does not authorize prune or recovery-marker clearing; full #517 reconciliation
therefore remains incomplete.

### Unresolved-exchange deletion admission

The bounded successor to `3c3e56edb1002ec147600553d8e972d2080c78f9`
extends deletion admission from active dispatches to the existing
`hasUnresolvedWrite` predicate. A finished exchange with an unknown outcome,
a potentially writable exchange, an accepted exchange with an unfinished local
tail, or persisted recovery without dispatch provenance cannot admit deletion.
The check runs under the existing per-radio state lock while holding `syncMutex`,
after the protected-contact recheck and before changing queued-write state.
No additional mutex, protocol callback, Room operation or unrelated sync phase
is introduced. At this preceding candidate, a terminal accepted exchange whose
local completion failed could still permit deletion. The marker-only mutation
successor below closes that admission; deletion never clears full-radio recovery.

Four additional real-session service controls are authored (36 methods in this
file in total, none discovered or executed for this successor):

- A timed-out exchange is retired; replacement remote/local deletes and sync
  are refused before and after the test radio applies that pending command
  without delivering an ACK. A newer local row, marker and empty event stream
  remain unchanged. This exercises the test radio queue, not physical firmware.
- Protected V-contact remote/local deletion remains a no-op despite retired
  unknown-write provenance.
- The original ACK/local-save-failure control admitted later deletion; the
  marker-only successor below changes it to explicit refusal with no deletion
  command or event while full-radio recovery remains marked.
- A canceled caller's late ACK is held in local persistence; both delete paths
  and replacement sync are refused. An intervening newer local frame survives
  the existing `saveContactPreservingNewer` check, and no stale navigation occurs.

The newer-frame control uses the existing in-memory atomic persistence role,
not Room evidence. No new general recovery/reuse or callback-deadlock proof is
claimed. Static admission and source validation do not compile these controls.
The retained Gradle reservation, publication and acceptance holds remain.
WP-209 `reserve.py admit --operation modify` passed for the recovery source,
service-test and evidence paths. `git diff --check` and
`python .\tools\android-port\controller\validate.py` passed (Python 3.12.4,
validator result `valid`). No Gradle, native/device test, CI dispatch or
publication was attempted.

The external prerequisite is unchanged: protocol source invalidation, successor
fetch and session retirement must participate in an ordered guard spanning
WP-202's destructive Room reconciliation/transaction and the matching WP-209
recovery-marker linearization. A source-bound terminal full-baseline proof and
a current-generation marker reread must be validated inside that guard; count
equality or an instantaneous `inspect()` is not permission. Room I/O must not
run under protocol/core/lifecycle locks. No such integration is added or enabled
here. At this preceding candidate, the count-based recovery branches remained
unaccepted; the safety successor below disables them.

### Fail-closed marked recovery

The bounded safety successor to
`254282a923ed0d5fc1c18b5b489a20f447f0c3c4` rejects a non-null recovery epoch
with the existing `ContactServiceError.SyncInterrupted` before fetching contacts,
publishing sync progress or persisting any frames. It removes the recovery-driven
full-fetch conversion and count-based `reconciled` call from `syncContacts`.
Ordinary no-recovery full/incremental sync retains its fetch, batch persistence,
progress, timestamp and existing prune policies. No new proof or success API is
introduced; recovery remains unavailable until the real cross-owner guard lands.

Two additional authored methods bring the file to 38 methods (not discovery or
execution). The equal-count control performs an independent real-session fetch
with one returned contact and total one, then exercises marked service sync with
retained terminal dispatch provenance. Full and incremental attempts throw;
batch-save/delete/marker-clear counters stay zero, newer and orphan local rows
remain unchanged, no event or notification is emitted, and subsequent writes
remain blocked. A replacement session with freshly loaded marked preferences
also cannot fetch, mutate or clear recovery. This is a simulated restart and
in-memory persistence role, not process-death or Room evidence.

The ordinary-sync control checks both full prune and incremental no-prune
behavior with no recovery marker. Existing cancellation, rejection, local-save
failure, persistence-tail, durable-marker-failure and replacement controls now
retain recovery and reject retry rather than expecting unproved count-based
repair. Greater/equal timestamp cases use independent fixtures so the first
case's deliberately retained marker cannot prevent the second case's setup.
The held batch-persistence fence control now tests ordinary successful sync,
not unavailable recovery.

WP-209 source/test/evidence modify admissions passed; `git diff --check` and the
trusted `python .\tools\android-port\controller\validate.py` passed (result
`valid`, Python 3.12.4); these are source checks, not Kotlin execution.
No local Gradle, CI dispatch, native/device verification,
publication, claim mutation or hold change is authorized or claimed. WP-107
protocol authority and WP-202 transaction/marker ordering remain prerequisites;
this gate prevents unproved recovery, not completes #517.

### Retrying failed true-marker recording

The bounded successor to `e4e1a7a83f7864af9d6128a7979711f88ca16712`
records a dispatch in `reportedUncertainties` only after
`preferences.set(recoveryKey, true)` returns successfully. A failed set still
leaves the in-process epoch blocking writes and sync, propagates its recording
exception to the existing service diagnostics, and leaves that dispatch eligible
for another report. Retry creates a fresh epoch before attempting persistence,
as the previous implementation did for a new report. Successful duplicate reports
perform neither a new preference write nor an epoch change. No false-marker
write, timer, background worker or reconciliation authority is introduced.

Two new authored methods bring this file to 40 methods, not discovery/execution:
the actual canceled Add and late terminal callback retry the true marker after
the backing store recovers; a fresh preference-role view observes false before
success and true afterward. The original persistence-failure warning is retained.
A direct same-dispatch control returns the identical recording exception on two
failed reports, succeeds on the third, then verifies duplicate reports do not
write again or rotate the epoch. Continued failure in the existing real-session
control now expects two true attempts and two persistence warnings, with no
claimed recorded value. All controls distinguish attempted true writes from
clears and keep subsequent radio writes and unproved sync blocked.

These fresh views delegate to the in-memory test backing store, not Android
SharedPreferences, Room, actual disk or process-death evidence. Read-only source
inspection confirms `SharedPreferencesContactFlags.set` throws when `commit()`
returns false; its files/tests are not changed or executed here. A successful
test-role write does not establish actual Android durability.

Removal reports without a dispatch remain a residual limitation: a failed marker
set leaves same-process recovery but `removeContact` may have no later service
report or owned completion callback to trigger retry. Repeating `require` with a
null dispatch already attempts persistence again, but no automatic retry, new
public API or safe device drain is inferred. This successor guarantees retry
eligibility when another report arrives, not eventual durability after a lone
failed removal report or process-death safety before a successful durable write.
The same limitation applies if every later true-marker attempt continues failing.

WP-209 recovery source/test/evidence modify admissions passed. Static
`git diff --check` and trusted
`python .\tools\android-port\controller\validate.py` passed with Python 3.12.4
and result `valid`; Kotlin controls remain uncompiled/unexecuted. Existing
Gradle, CI, publication, claim and acceptance holds remain unchanged. The
no-unproved-recovery service gate is preserved; WP-107/WP-202 guarded destructive
transaction and matching marker authority remain unavailable.

### Blocking mutation with marker-only recovery

The bounded successor to `5a7d582bf8d0588714f0c982d10dd3633f24c2c6`
also checks `state.epoch != null` in the mutation fence after `loadEpoch`.
This rejects a marker created by a dispatch-null uncertain removal, even when
there are no tracked Add exchanges. A successfully loaded durable marker is
also rejected. Protected V-contact early/rechecked no-ops still occur before
this check; clean-state removals and other-radio local mutations remain allowed.
The existing `SyncInterrupted` is surfaced before commands, local deletion,
cleanup, events or queued-write invalidation.

Two additional authored methods bring the source count to 42 (not execution).
The uncertain-removal family injects transport failure, then attempts repeated
remote/local deletion after either successful or failed true-marker recording.
Only the first remove command is observed; local rows, cleanup and notifications
remain untouched, no false-marker write occurs, and writes/sync stay blocked.
Successful backing-store recording is observed through a fresh preference-role
view, which also refuses both deletion paths. Protected no-ops and another
radio's local deletion are checked with the same marked role. The clean-state
control checks protected no-ops followed by a normal successful device/local
removal. The earlier accepted-Add/local-save-failure deletion control now expects
refusal rather than allowing a mutation while its recovery epoch is present.

Fresh flag views use the in-memory test store, not actual disk durability or
process-death evidence. Failed recording provides same-process recovery only.
The optional blocked-call recording retry is not introduced: blocked admission
remains an explicit refusal, without hidden preference writes or replacement
errors. Retrying a known failed true marker on a future call would need separate
recording-pending state and an explicit failure-notification policy; no eventual
recording before such a call is claimed. The previous same-dispatch report retry
remains intact, and a lone failed dispatch-null removal report still lacks an
automatic retry trigger.

WP-209 source/test/evidence modify admissions passed. `git diff --check` and
`python .\tools\android-port\controller\validate.py` passed (Python 3.12.4,
result `valid`). Kotlin controls remain uncompiled/unexecuted. No protocol,
persistence/schema/graph changes or genuine reconciliation authority are added;
all existing execution, publication, claim and acceptance holds remain.

### Fail-closed deletion identity

This bounded successor has exact parent
`3969ee63a16d3720ceb91dd319e7de9cd9a52825`. Trusted source/policy revision
`cb4a79ab906db851800bcf93f7c2ebfb5aa34571` admits `modify` as `wp-owned` for
`ContactService.kt`, `ContactWriteRecoveryTests.kt`, and this document.
The same trusted `wp-owned` modify admission also covers the directly coupled
`ContactServiceNativeTests.kt`, `ContactServiceSessionTests.kt`, and
`ContactServiceTests.kt`. Those previously clean test paths belong to the
assigned contact slice, not paused cleanup/notification hunks.
The current coordinator assignment delegates only this session's deletion
identity slice; the held manual-refresh and evidence-lineage drafts are disjoint
and excluded. No WP-215 notification or cleanup API hunk is changed.

The pinned Swift helper treats missing/failed self lookup as nonprotected, and
its local removal suppresses contact lookup failure. This user-directed safety
adaptation instead propagates lookup failures, including cancellation, unchanged.
Missing, wrong-radio or invalid-length self identity, or an invalid-length target
key, throws the existing `SyncInterrupted` with a key-free warning before any
mutation. A local row must exist and match the supplied radio, ID and public key;
absence throws existing `ContactNotFound`, and mismatch warns and interrupts.
Local protection is evaluated against the verified stored key, never just a
stale caller key. Both removal paths repeat their checks inside the existing
mutation fence before write invalidation or recovery admission. A verified
protected V-contact remains an immediate no-op outside that fence.

The existing consumer handles real remote `NOT_FOUND` with a separate local
fallback; it exposes other failures and propagates cancellation. That contract
is unchanged: an unknown self identity is not `ContactNotFound`, and a known
orphan with matching local identity can still be removed locally after actual
protocol `NOT_FOUND`. No identity refusal creates or clears a recovery marker.
Storage recovery permits a fresh call; there is no automatic retry or fabricated
radio outcome.

Eight additional authored methods bring `ContactWriteRecoveryTests.kt` to 50
methods, not discovery/execution. They cover missing/wrong-radio/0-, 31-, and
33-byte self identity and malformed target keys; ordinary and protected target
rows; identical lookup exceptions; absent/stale/mismatched local identity
(including a supplied V-key for a different row); second-read row/self failure;
actual caller cancellation while self lookup is suspended; storage recovery
followed by a normal ACK removal; actual MeshCoreSession error-code-2 local
fallback; and active/queued Add survival after identity refusal. Refusal controls
assert unchanged rows, zero device removals/local deletes/cleanup/notifications,
no marker and no event. Positive controls observe real protocol response handling
in the simulated transport and the expected local cleanup/event, not hardware.
Existing protected-contact and marker-admission controls remain unchanged.
The existing native lookup-failure deletion case now asserts refusal/no effects
and a verified retry instead of the unsafe best-effort success. Three existing
normal deletion/NOT_FOUND fixtures now seed a usable self identity so they still
exercise their original positive/error-mapping behavior. No original case or
assertion family is removed; unrelated best-effort cleanup cases are unchanged.

Source checks only: `git diff --check` passed. Python 3.12.4 ran the trusted
`controller\validate.py --repo <this absolute worktree>` from a session-local
Git-archive export of `cb4a79ab906db851800bcf93f7c2ebfb5aa34571`; result `valid`.
The first controller-only export lacked `inventory_rules`, so that invocation
failed before validation; exporting the complete trusted `tools/android-port`
subtree fixed the staging dependency and the same validator passed. No package
installation, Gradle, Kotlin compilation/discovery/execution, CI or device work
occurred.

The service fence does not confer atomic authority over arbitrary external
device/Room writers between a lookup and deletion. No guarded destructive
snapshot/Room-marker contract, durable pre-dispatch removal intent, strict
production cleanup completion or recovery acceptance is implemented here.
Those cross-owner prerequisites and all execution/publication/claim holds remain
unchanged; this candidate is local source-only for independent review.

### Next-guarded-access marker recording

This bounded successor to `acc54faff78c39617438fd13159614747e45e3a3`
adds per-preference-role/per-radio `markerRecordingPending` and pending dispatch
reports. `require` sets the epoch and pending state before attempting a true
marker. Only a successful true `set` ends pending recording and deduplicates
those reports. Epoch loading, write registration/dispatch and sync/mutation
admission retry pending recording while retaining the existing epoch. Successful
retry does not authorize mutation or snapshot work: the recovery epoch still
blocks them. Sync now refuses a marked epoch before invalidating queued writes.
Protected V-contact no-ops still return before mutation admission and perform no
marker retry or unrelated invalidation.

No retry consults `bool()` to infer durable recording. In particular, a failed
Android SharedPreferences commit may already have updated its in-memory map;
the recording exception must leave pending state intact. This is consistent
with the coordinator's source observation of AOSP `SharedPreferencesImpl`:
`commitToMemory()` precedes the disk result, whereas `getBoolean` reads `mMap`.
The candidate does not change or execute the Android preference adapter.

Repeated retry failures propagate the original preference exception. This
explicitly changes `needsContactWriteReconciliation` during pending recording:
it can throw on another failed commit instead of returning true. Successful
recording returns true, never false; the in-process epoch continues blocking
all unsafe work after an exception. Source search found no production call site
for this query beyond its declaration; the existing failure controls now assert
the identical exception and precise retry counts. Original service warning and
suppressed initial recording failure remain intact. No hidden success/default,
timer, unowned task, new public API or retry loop is added.

The existing internal matching-epoch `reconciled` helper first completes any
pending true recording, then attempts its false write, and clears pending state
only after success. Stale epochs and unresolved exchanges still cannot clear.
The service still has no caller of this helper: the internal CAS control is not
a service reconciliation permit or a substitute for WP-107/WP-202 authority.

Three additional authored methods bring recovery source to 53 methods, not
discovery or execution. A real-session uncertain removal, with no dispatch
callback, first fails marker recording while the fake memory map becomes true
and its separate durable backing remains false. Each next-access variant
(snapshot query, full sync, Add registration, remote/local removal and serialized
dispatch admission) retries once when storage recovers, leaves the epoch marked,
and produces no additional radio command, local mutation, cleanup, notification
or event. The continued-failure family makes two passes over all six accesses,
checks identical exceptions/13 true attempts, then proves other-radio local
removal does not retry that marker before a successful fourteenth recording.
The pending-report control preserves stale-epoch rejection, deduplicates later
reports after access success, and checks no new true write after an internal
matching clear and clean snapshot/sync/write admission. Existing late-callback
and uncertain-removal controls now distinguish these explicit retries from
false clears rather than expecting no guarded-access retry.

The fake's independent memory/durable maps and fresh read-only durable view are
host test-role modeling only, not Android disk or process-death evidence. No
durability is promised if the process dies before a successful set, storage never
recovers, no later guarded access occurs, or a distinct role loses the pending
in-process state. Retrying records uncertainty; it does not reconcile contacts,
prove radio drain or complete recovery.

Trusted policy `cb4a79ab906db851800bcf93f7c2ebfb5aa34571` admits WP-209
`modify` for `ContactWriteRecovery.kt`, `ContactWriteRecoveryTests.kt`, and this
document. The assigned recovery hunks are disjoint from the retained manual
refresh/comment/lineage drafts, which remain excluded. Python 3.12.4 ran
`controller\validate.py --repo <this absolute worktree>` from a complete trusted
Git-archive export of `tools/android-port`; result `valid`. `git diff --check`
passed. These are source checks, not Kotlin compilation, test discovery or
execution. No Gradle/CI/device work, publication, schema/Room/graph edit,
claim change or hold release occurred.

### Queued marker retry error identity

The bounded repair starts from reviewed candidate
`30ef44447d143f0555aded3865412e823a78027f`. Marker recording failure is added
as a suppressed exception only when it is a different instance from the primary
failure. Reusing the primary exception no longer invokes forbidden
`Throwable.addSuppressed(this)`, so the original error and both recovery warnings
survive. Marker retry, pending state, epochs and dispatch fences are unchanged.

Two real-session regressions queue a service Add behind another service Add,
cancel the first caller while marker recording fails, then deliver its late ACK.
The terminal callback retries recording before the queued dispatch retries it
again. The queued service catch retries a fourth time, either throwing the same
exception instance or a distinct recording exception. Assertions cover primary
identity, empty versus distinct suppression, both warning counts, no second
transport write or queued-row persistence, the first accepted local completion,
memory-versus-durable marker state, and successful later marker recording without
clearing recovery or admitting writes/sync.

This is an injected stable-identity regression for a source-level defect, not
a reproduced production-device failure. The current Android adapter creates
fresh recording exceptions. Device acceptance is a separate later run, not a
prerequisite for this repair. Other owners' worktrees and held drafts are unchanged.

Verification on native Windows used Python 3.12.4 and the existing explicit
`ANDROID_CI_STATE`:

- `python .\tools\android-port\controller\ci.py preflight --local --state
  $env:ANDROID_CI_STATE --output <fresh absolute temporary directory>` passed.
- `python .\tools\android-port\controller\validate.py` and `git diff --check`
  passed.
- `python .\tools\android-port\local\check.py --commit HEAD --affected-base
  cb4a79ab906db851800bcf93f7c2ebfb5aa34571`, at implementation commit
  `9632ed16888ea6ae0551d4487bbd26dcbdb5c6f6`, was blocked before execution:
  `Hard-lock collision for external resource gradle-execution: gradle-execution held`.
- `python .\tools\android-port\controller\execution_recovery.py status` refused
  candidate recovery code: `Recovery requires reviewed default-base source:
  tools/android-port/controller/authority.py`.

No Kotlin tests were discovered or executed for this repair. The two new
regressions are authored, not passing evidence. The coordinator received the
execution-lane blocker for trusted recovery/allocation; no interlock or retained
execution owner was bypassed, and no new CI-result bundle was created.

Independent source review identified that coroutine stacktrace recovery could
copy an ordinary injected `IOException` across the serializer's `Deferred.await`,
invalidating the stable-identity control. The forward-only test correction uses
an `IOException` subclass implementing `CopyableThrowable` with `createCopy() =
null`. Both controls retain strict `assertSame` and exact suppression assertions;
the default test JVM assertions and stacktrace-recovery settings are unchanged.
The production identity guard is unchanged. Exact-head hosted CI, rather than
the superseded ordinary-exception controls, must establish execution results.

### Pre-generation definitely-unsent accounting

Independent full-series review of `2e722901ffe51f70547d07a25040575825ba2bbb`
identified a distinct clean-unsent failure: Add can suspend while reading the
previous contact frame, then resume after its old session stops. Registration
still succeeds, but `SessionCore.generation()` fails before serializer admission.
The previous service catch incorrectly recorded uncertainty for this attempt.

The dispatch-aware core exchange now records `DEFINITELY_UNSENT` specifically
when generation lookup throws `SessionNotStarted`, before any owned task exists.
It rethrows the same failure. ContactService abandons only that proven-unsent
dispatch while retaining its public `SessionError` wrapper and original cause.
It neither clears nor overwrites another epoch. Other session failures without
unsent proof retain fail-closed uncertainty; exception type alone is not proof.
The queued marker-error identity guard and recording retry behavior are unchanged.

New actual-caller controls hold the previous-frame lookup, stop the real session,
then release the lookup. The clean case asserts the observed original session
failure identity, public wrapper/cause, unsent dispatch status, no command/row/
warning/marker, then a replacement session using the same preferences successfully
syncs and completes an acknowledged Add. A second schedule creates real on-wire
uncertainty while the other lookup is held; registration remains blocked after
stop, the same earlier epoch/durable marker survives, and replacement sync/Add
remain blocked without additional commands. A legacy-role typed-failure control
has no unsent proof and still records reconciliation, preventing a broad
`SessionNotStarted` exemption. A real protocol channel control also verifies
the shared pre-generation dispatch accounting before serializer admission.

`git diff --check` and `python .\tools\android-port\controller\validate.py`
passed for the authored correction. These new assertions require fresh exact-head
hosted execution and do not claim device acceptance or a production-device failure.

### Queued removal definitely-unsent accounting

The full-series review also found that removal cancellation always recorded
uncertainty, including a removal queued behind an unrelated raw protocol exchange
that never entered transport. An additive `removeContact` dispatch overload now
uses the existing serialized dispatch fence. The real session supplies actual
`DEFINITELY_UNSENT` versus `MAY_HAVE_WRITTEN` evidence; legacy roles retain unknown
outcomes rather than claiming proof. The service skips recording only for
proven-unsent failures. After transport entry or accepted local-tail failure it
still records uncertainty. It never clears an existing epoch.

The deterministic real-session regression first occupies the serializer with a
raw Add, admits removal through the service mutation fence, cancels it while
queued, then completes the raw exchange. No Remove command, local deletion,
cleanup, event, notification or new marker occurs; replacement sync and a real
acknowledged removal remain usable. A paired existing-epoch control injects a
late uncertainty report while removal is queued and proves cancellation preserves
that exact epoch/durable marker and replacement blocking. An entered-transport
control observes `MAY_HAVE_WRITTEN` and retained recovery after cancellation.
A legacy cancellation without dispatch proof still records uncertainty.

The WP-209 deviation text now describes the actual fail-closed marked state:
full/incremental sync refuses before fetch and there is no production
`reconciled` caller. No count-based marker clearing was added. Fresh hosted
execution and full-series independent review remain necessary.
