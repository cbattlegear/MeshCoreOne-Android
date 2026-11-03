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
  Recovery sync also excludes writes through snapshot application, pruning and
  marker clearing. The messaging additions do not change contact request bytes,
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

`ContactWriteRecoveryTests` contains 32 deterministic methods using an actual
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
partial-snapshot rejection, pre-snapshot refusal while a prior exchange can still
write, invalidation of a queued cross-session write released after an omitted
snapshot is captured but before local application, fail-closed persisted markers
without dispatch provenance, and blocked write admission while a full-snapshot
batch save is held. The fence atomically checks for potentially writable exchanges
before snapshot capture, remains active through pruning and marker clearing, and
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
then verifies a new snapshot cannot start and `NodeDeleted` is emitted only after
the production `ContactCleanupCoordinator` finishes; its callback seam suspends
at notification cleanup and sync-notification order is asserted. Cancellation
through that coordinator after acknowledgement is asserted to keep the recovery
marker and block a subsequent contact write. Protected V-contact
remote and local removals are exercised while an Add is active and another Add
is queued; both remain true no-ops, with no remove command, local deletion,
event, recovery marker or queued-write invalidation.
Protected V-contact no-ops also return while an unrelated snapshot is paused in
persistence, without waiting for or mutating that sync fence.

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
