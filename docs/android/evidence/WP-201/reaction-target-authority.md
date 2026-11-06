# WP-201 channel reaction target contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift specification: `db14559b39d32322b06477c6ae676112f583db50`.
Exact unheld producer base: `eca8e9db28fbf451e5541421f2e3b80d6d2f0cbe`,
tree `c4d66a9452c3f6ef2b3df57a55d70095320e0122`.
Coordinator assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_BNQOfp8vNVw5Tlm2Lh3G0vSV`.

## Bounded ownership and admission

This adds a separate `ReactionTargetAuthority.kt`, ten value controls and this
evidence. It does not modify retained Direct ACK, channel failure/completion,
inspection, queue admission, neutral-event, service, cache, App, Sync, DAO,
schema, trigger or migration files.

13a confirmed no contract draft overlap and owns queue adoption. 5c2 confirmed
no contract overlap and owns cache/reaction service/App integration. 12e7 owns
Sync ports/handlers/helpers. f805 accepted the core capture/atomic mutation/
successor semantics and owns repository production, not implemented here.
3008 reported no active reaction DAO/direct-ACK identity-trigger edits; its
schema-5 composition is independently owned and must be preserved. That factual
report is not a custody transfer or blanket path admission.

Actual authenticated REST reads verified the base and trusted rules before edits:

| Trusted input | Blob |
| --- | --- |
| `docs/android/port-manifest.json` | `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` |
| `docs/android/automation-policy.json` | `e56438d64d2309c964353066f26e1427f17f55d8` |
| `tools/android-port/controller/capabilities.py` | `1001f43112d9bff9c9985929a203b5166c41559e` |

| New path | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/ReactionTargetAuthority.kt` | `wp-owned / create` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/ReactionTargetAuthorityTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/reaction-target-authority.md` | `wp-owned / create` |

Manifest WP-201 owns domain/evidence descendants; the test matches the trusted
support glob and Kotlin test-path regex. Source-derived path/operation admission
does not claim an executed controller/reservation transaction, generated receipt,
merged prerequisites or passing validators. Policy, authorization, reservations
and locks remain untouched.

## Exact source and prerequisites

Landed `35abcc1276082995eed216bf28bff04d77e176c8` was read directly. It does not
contain `ChannelSendCompletionAuthority.kt` or `ChannelSendRepository.kt`.
Therefore this completion-consuming contract is based explicitly on reachable
unheld `eca8`, not falsely represented as compiling against landed main.
f805 also identified its unmerged Direct ACK prerequisite by prefix
`00d3e2e4...`; that owner-reported prefix is not a verified full commit identity
or an instruction to import its chain. No held combined migration is imported.
The source facts below do not authorize using those unmerged tables on schema-v1
main. Actual identity storage, schema/version composition and benign-write
revision support require independent producer admission and owner serialization.

| Exact eca8 source | Blob / observation |
| --- | --- |
| `ChannelSendCompletionAuthority.kt` | `2cc76fae312c0c3bc065a1ea513daec3b91034c9`; opaque original completion and historical null-handle distinction |
| `ChannelSendRepository.kt` | `ba3f1399dd0448c6fc2029803ec0215840def655`; private issued completion and exact capture lineage |
| `DirectAckStorage.kt` | `bb8f89eaf7d8441dc4feefa75ef5097aff0bd111`; message incarnation plus revision; same-key UPDATE advances revision, delete/reinsert rotates identity |
| `ChannelSendStorage.kt` | `2ab6c57ab56e0c0ea43e1562a779f595609fc929`; original immutable origin/envelope separate from current slot |
| `MessageDaos.kt` | `082ea7f2a58a3ea02fbbb7f7c1863ec4c841cac5`; stored timestamp/createdAt candidate ordering; newest reaction ordering and sender/emoji dedup |
| `DiagnosticDTOs.kt` | `439a39d0a49c3be28e26e4b829a419e82502d332`; original reaction identity, wire and receivedAt fields |

Actual landed consumer sources were also read: `ReactionService.kt`
`f247e79e767f4d3d02141b41fd2e919c43e0cb25`, `MessageLRUCache.kt`
`695c66fea63b2e34c972fa6fa4441aa47be609fb`, incoming handler
`be17371c695a00be8c1ec24ae5db1970ec834eae`, helper
`4fc7ff905df92c70d065a2617638764ea97dcd4c`, and App history
`509c5c835645064cc3f0dfbc69abfb4474fc35fc`.
The existing service separates existence/save/fetch/summary across suspension;
the cache has only IDs and text/time metadata; original save and later harvest
precede indexing; App history fetch precedes a separate preparation hook.

## Agreed authority and outcomes

The contract requires an opaque `ChannelReactionTarget` only with the actual row
from the original candidate/window read transaction or exact original
`ChannelSendCompletion`. Public DTO/key equality cannot authenticate issuance.
Targets bind both incarnation and expected revision, not incarnation alone.

`captureChannelReactionTarget(completion)` cannot use historical
AlreadyApplied(null). `findChannelReactionTarget` and
`fetchChannelReactionTargets` preserve existing genuine DB fallback semantics.
Both `fetchChannelReactionWindow` overloads are original history reads with
bindings in the same transaction, not a second capture of displayed IDs. The
offset overload preserves `fetchMessages`; the anchored overload preserves
`fetchMessageWindow` count-at/after anchor, max(floor,count), checked extra-row
read, hasMore/drop-extra logic and display ordering, including more than 200
loaded rows. They remain read-only: neutral invalidation may fetch without
preparation. The original captured DTO reference and opaque target must cross
the preparation hook without DTO equality/UUID recapture.

`saveIncomingChannelMessageWithReactionTarget` preserves the existing original
`saveMessage` upsert atomically. Inserted(captured) is issued only when the key
was actually absent before insertion. Existing(key) means the existing-row save
committed, with no reaction token or new-insertion claim; it is not fake rollback
or a silently skipped upsert. An existing-row write still invalidates prior
authority even if its values are equal. Rejected performs no save. Consumer
delivery behavior for a real save remains separate from reaction eligibility.
12e7 confirmed Existing continues normal harvest/refetch, unread, notification
and message-event processing without logging a false save failure. It does not
permit reaction indexing, dropping pending work or rebinding by key.
The ingress API supplies no retry ledger: postcommit throw/cancellation is
UNKNOWN, and a later Existing cannot recreate the lost original target.

`applyChannelReaction(target,reaction)` validates original identity/revision/
recorded origin and the actual reaction relationship in the same write
transaction as deduplication, insertion, newest-100 summary calculation and
summary update. No replacement row, partial insert, duplicate overwrite or
ID-only summary callback is allowed.

Applied contains the explicit post-write target/snapshot, summary, inserted flag
and optional exact completion successor. inserted=false is not a new
ReactionReceived event or saved log. Stale/Rejected grant no mutation, indexing
success or cleanup. Candidate-query targets cannot acquire completion lineage.
Only a target minted from the original completion may conditionally advance
that exact still-current lease/pending/capture/completion through its one owned
summary write. Generic invalidation is never waived.

The separate `CompletedChannelReactionIndexer` requires the original completion.
Indexed returns the final target and original/own-write-successor completion
only after actual bound cache insertion and ordered pending replay return.
Queue cleanup uses that exact returned completion, not a key recapture.
No default implementation or Boolean success fallback is supplied.

No persisted reaction operation/successor replay journal exists. A summary commit
followed by cancellation/throw may lose the successor: the outcome is UNKNOWN.
Old-target retry is Stale, not rollback or permission to reselect. Exceptions and
cancellation propagate; no exactly-once or process-death recovery is claimed.

## Discriminating consumer blockers, not parity exclusions

The pinned original `ReactionServiceTests.swift` blob
`6426f1b5fd2048ee1c5e62210089b9801fcf1ce9`, lines 132-169, explicitly expects
most-recently-indexed choice for equal text/timestamp/hash. Existing pending
indexing applies to the actual triggering indexed message. A bounded current-time
DB fallback sorts createdAt instead, may miss old/corrected timestamp rows or
more than 200 candidates, and cannot replace those semantics silently.

12e7 supplied the decisive ABA schedule: index A, delete A, recreate identical B
with the same radio/UUID/channel/sender/text/reactionTimestamp, then consume A's
unbound cache entry. A later full hash/text/time/ID/ranking-hint query can mint B,
not prove A. 5c2 withdrew its ranked-unbound replay proposal. No such selector or
equivalence claim is added.

Actual safe cache producers need the original-transaction bindings declared
above and must preserve bindings through harmless own harvest/status/read writes
using producer-validated lineage or a separately reviewed durable reaction
identity revision. A strict generic revision cannot be refreshed by ID/DTO
equality. This document invents no alternate identity source, revision table or
benign-writer exemption. Actual original ingress capture/benign-write lineage
is an explicit unmet producer prerequisite, not a fake
implementation, default fail-closed replacement of all positives or silent
feature exclusion. The original history-window implementation and opaque
fetch-to-prepare handoff likewise remain separately owned integration work.

Cache/pending owners must preserve indexedAt/collision order and exact
predecessor-only successor advance/eviction. Legacy entries cannot overwrite
protected entries by UUID. Pending batches retain original receivedAt,
queueKeyOrder/queueEntryOrder and chosen binding on requeue; no bound stale entry
becomes unbound. Ordinary replay failure is surfaced without suppressing delivery
or notification for an already-saved incoming message; cancellation propagates.
Existing fault matrices/assertions stay intact, with reviewed equivalent
transaction/fault controls for the new path. This file does not claim that any
cache, App, Sync, Room or incoming-delivery control has passed.

## Validation boundary

Ten authored controls cover opaque identity vs equal public snapshots,
actual/corrected timestamps, explicit target and completion successors,
duplicate-vs-new outcomes, stale/rejected variants, retained reaction metadata
legitimate empty summary, ingress result distinctions and a window carrying
more than 200 rows with hasMore. They are value/shape controls only, not storage
issuance, ABA, transaction, notification or cache proof.

Source checks cover ASCII, final newline, trailing whitespace, method counts,
exact referenced declarations and an isolated three-addition remote compare.
They do not establish compilation, test discovery or passing producer behavior.
No local Gradle/compiler/controller/validator/Git-object operation, readiness
recovery, held-source import, other-worktree inspection, DAO/migration edit,
PR, dispatch or merge occurs. The retained execution-owner hold remains.
Actual hosted compilation/tests, producer admission/composition and all original
capture/consumer obligations above are prerequisites, not completed by a
contract-only handoff.

Actual source-only check outcomes before publication:

- Serial `gh api repos/cbattlegear/MeshCoreOne-Android/contents/<path>?ref=eca8e9db28fbf451e5541421f2e3b80d6d2f0cbe`
  reads verified the trusted inputs and prerequisite blobs listed above. Landed
  consumer/anchored-query reads used exact `35abcc1276082995eed216bf28bff04d77e176c8`;
  the collision oracle read used the pinned Swift commit, not a held candidate.
- PowerShell text checks using `ReadAllText` and regex: passed ASCII,
  final-newline and trailing-whitespace checks for all three additions; exactly
  ten `@Test`/test-function declarations and eight suspend-role declarations.
- Manual source review checked the declared DTO/rejection/completion names,
  separate inserted/existing/stale results, no default implementation, and
  absence of the rejected ranked-hint/first-insert-only API drafts.

These are source results only. The ten tests remain uncompiled and unexecuted.
Remote immutable parent/tree/ref and three-addition compare are checked as part
of the handoff; the self-containing evidence file does not predict its own hash.
