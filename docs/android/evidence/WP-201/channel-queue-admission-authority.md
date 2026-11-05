# WP-201 atomic channel queue admission and retained acceptance

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Remote parent: `773d434be7700734ee8cd2cb2b46c8f47fa8d416`,
tree `ad422ff78f9bbbc41fb51ca03c9c5920b6eff99b`.
Coordinator assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_mPJ03XwtkZGZHl2qiUVgjffg`.

## Ownership and exact source-derived admission

The coordinator authorized a separate atomic queue-admission successor plus a
synchronous process-owner acceptance note. 808 owns producer-local transactions,
stable pending lifecycle definitions and producer controls; f805 alone integrates
shared schema/database/store changes. 13a owns actual service/queue calls, shutdown
ordering and replacement controls. This change supplies only domain types, eleven
authored shape/value controls and this evidence. No other owner's source is edited.

The trusted remote parent and the local admission inputs were compared as normalized
LF UTF-8 Git blobs without local Git/object processing:

| Input | Exact blob |
| --- | --- |
| `docs/android/port-manifest.json` | `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` |
| `docs/android/automation-policy.json` | `b12794157978d6d90ab7508d4e2852fdc2d5e4a4` |
| `tools/android-port/controller/capabilities.py` | `d240bf1007c6921c800401bf3e0637c296a4a0e7` |

Before creating files, the source-derived path/operation checks admitted:

| Path relative to repository | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/ChannelQueueAdmissionAuthority.kt` | `wp-owned / create` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/ChannelQueueAdmissionAuthorityTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/channel-queue-admission-authority.md` | `wp-owned / create` |

The first and third paths are manifest WP-201 descendants. The test matches the
trusted support-test glob and exact Kotlin-test support regex. The controller,
reservation transactions and verification validators were NOT executed under the
retained execution/resource hold; no admission receipt from those tools is claimed.
Authorization prose, reservation ledgers, policies, locks and existing assertions
are untouched. These source checks do not prove merged prerequisite or runtime readiness.

## Actual counterexample and real process owner

Source was read through serial authenticated GitHub REST requests, not local Git.
Consumer candidate:
`52ce453e14f7fe70bb2a98d5b1645e0cf0b67f12`,
ref `diagnostic/wp208-channel-completion-source-52ce453e1`.

`PendingSendRepository.kt` blob `15a01dcacbfbacd69526a5e6e126793f73eea895`,
69-77 selects the first ordered pending and uses checked `(attemptCount ?: 0) + 1`.
`ChatSendQueueService.kt` blob `2c5f57198a99a8ee49a6337095e1bc4106ed8398`,
338-375 and 621-626 bumps before later preparation and can hydrate work after old
acceptance claims disappear. No channel maximum-attempt policy is introduced:
post-bump count controls existing resend timestamp/disambiguation behavior, not a cap.
Generic pending UPDATE changes the Direct ACK mutation identity; a later capture
cannot recover the identity that existed before that update.

The proposed surviving owner is real, not a new service-global ID cache. Exact
inherited source at that same candidate:

| Source | Blob and relevant lines |
| --- | --- |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AndroidAppContainerFactory.kt` | `769a6fa1aca9366bc23620b9422f658ffac18707`, 111-118 creates one store/scope |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppContainer.kt` | `70d584a869e5c3dbfc90c1702150bc581e4c7c6b`, 184, 195-196 shares it; 434-441 stops runtime before process resources |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/container/RadioSessionContainer.kt` | `30665bf84ba50ce54e4ce7e7c480490910fe79ca`, 134, 182-185 passes that store; 520-521 stops queue then service, not store |
| `android/core/runtime/src/main/kotlin/com/meshcoreone/android/core/runtime/ConnectionManager.kt` | `710eee92261a0fadf4f07ac0be81205951733a58`, 319-330 waits prior teardown; 1274-1299 joins old service work |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/messaging/MessageService.kt` | `e4eccce56c60c8875b8bb41d4c360f0c8e7a74a6`, 2263-2305 joins active operations before revocation and clearing channel claims |

These are source ordering observations, not proof the new role is already wired.
The integrated store must delegate failure, completion and admission to the SAME
process-owned issuer/repository. The new note must execute in the immediate
successful-transport-return code before a cancellation/ownership check or suspension.
An observed success is then retained before its operation joins and successor
generation admission begins. An unobserved callback outcome is not made certain.

## Minimal separately typed seam

`ChannelQueueAdmissionAuthority` has two methods. Both existing failure blob
`5897785d7a50a5d9b3648357e60a5cbb87348d52` and completion blob
`2cc76fae312c0c3bc065a1ea513daec3b91034c9` remain unchanged.

`admitQueuedChannelSend(lease, requestId, key, freshTimestamp)` selects the actual
ordered CHANNEL pending itself, derives actual mode, checks real message incarnation
and stable pending lifecycle against committed completion BEFORE any bump, then
returns `PreviouslyCompleted` without a handle or mutation. Relevant committed
history with missing original lifecycle proof returns explicit `Uncertain`, not
manufactured coverage or a blanket quarantine of unrelated work.

Absent a committed match, retained original acceptance produces
`Uncertain(RETAINED_ACCEPTANCE)` with identity-only observations. Otherwise the
same transaction performs the checked legacy null-to-one bump, optional timestamp
write and post-write capture, returning `Ready(attempt, receipt, postBumpCount)`.
SEND preserves timestamp; RESEND refreshes only for count one. Status/counters/
repeats/siblings are preserved. No new limit parameter, cap, status grant or
success-shaped fallback is added.

Exact current initial-version replay returns `AlreadyReady` without another bump.
The operation/request namespace must distinguish atomic admission from old QUEUE
preparation; an old capture is not proof this operation bumped. Changed arguments
conflict; invalidated, retired or missing grants are not recaptured. A true new
pending lifecycle or message incarnation may use a new request and must not inherit
old acceptance. Storage exceptions/cancellation propagate and do not establish rollback.

`noteAcceptedChannelAttempt(attempt, completionRequestId, mode, acceptedTimestamp)`
is synchronous and performs no database/network IO or async registration. It
authenticates private immutable capture metadata, including original message and
pending birth identity, and retains the actual observed tuple in the process owner.
It returns `Registered`, exact `AlreadyRegistered` or explicit `Rejected`.
Lease retirement/revision invalidation does not erase original acceptance evidence
or renew authority. Conflicting reports cannot overwrite fences. Unresolved entries
cannot be cleared by service close, claims.clear, TTL, capacity eviction or ID-cache
replacement. Pruning needs exact committed supersession or proved identity retirement.
Rejected post-acceptance registration is reported/parked, never converted into unsent.

Stable pending birth identity is separate from Direct ACK mutation identity:
same-relationship UPDATE retains birth but still invalidates all mutation grants;
DELETE+INSERT/relationship change gets a new birth, and message recreation has a
different message incarnation. Birth must be captured originally, not inferred by
backfilling current UUIDs into historical completion. Its tables/triggers/integration
remain producer/integrator-owned, not an implementation supplied by this contract.

## Source controls, required integration evidence and limits

Eleven authored controls cover method suspension shape, actual selected payload/
mode/count association, full UInt acceptance timestamps, explicit rejections,
issued-handle separation, current replay versus historical commit and distinct
retained/unproven uncertainty observations. They are data/shape assertions, NOT a
fake producer, transaction proof, replacement execution or physical acceptance test.
The existing 55 authored value controls and all their assertions are unchanged.

Producer/consumer evidence must still cover atomic bump/timestamp rollback,
commit-then-throw replay, initial-version invalidation, old prepare/request collisions,
first ordered pending selection, null/count-overflow families, SEND/first versus
later RESEND timestamps, lease replacement, generic same-value pending UPDATE,
same-ID delete/reinsert and message ABA, exact completion precedence, missing-birth
history, and acceptance retained across real service close before completion commit.
Outage and rejected-note paths must visibly park without dispatch or broad cleanup.
No prior positive ACK/failure/completion expectations may be weakened.

Only source-derived admission, normalized-byte/whitespace/method-count checks and
serial Git-data publication are performed here. No local Git, Gradle, controller,
test runner, readiness recovery, workflow dispatch, PR or merge is run. The retained
`local-check:f516a7e271f444dd9cb2efcc8b995a16` and host-resource hold remain respected.
The eleven controls are UNCOMPILED/UNEXECUTED; no discovery or pass count is claimed.

This is not a durable prewire journal, process/store-reopen acceptance recovery or
exactly-once physical-send guarantee. Missing completion never means known unsent.
Known accepted work keeps its original opaque attempt/prewire UUID/mode/timestamp;
outage/index/cleanup retries never trigger fresh admission/bump/wire. Reaction-cache
identity safety, hardware and the separately held durable-history work remain
outside this bounded ordinary-generation claim.
