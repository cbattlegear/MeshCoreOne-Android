# WP-201 incoming provenance correction to the independent reaction contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift specification: `db14559b39d32322b06477c6ae676112f583db50`.
Exact current-main base: `35abcc1276082995eed216bf28bff04d77e176c8`,
tree `91150b99594daa3a8cb965d84e0370005a14c124`.
Correction assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_t58hnavgWyjbOEZe0xgzmwGB`.
Immutable correction parent: `67a22f7f9e75db2b8188ed890e04cc3b25d62a02`,
tree `76c29b754b56ea9b3767eaad9c80f4d28e1c234f`, sole parent exact main above.

## Concrete provenance defect and corrected boundary

The original67a API could not honestly mint incoming/history targets from a
MessageDTO containing only radio/slot. A later current-slot read could bind a
delayed A event or same-UUID replacement to B. The correction removes that grant:
save requires the retained original ChannelReactionOccurrence, candidate queries
and apply require the REACTION's own retained occurrence, and history preserves
every selected row while exposing missing provenance without a target.

ChannelReactionOccurrence.Unproven(envelope,reason) explicitly carries no grant.
Proven is an interface with NO public constructor/factory or live issuer; only
a future private storage-authenticated implementation may be accepted, never a
caller implementation. Its own exact event/session/radio/index/wire and original
occupant incarnation must be authenticated at a genuinely ordered receive
boundary. Equal/copied envelopes, object identity, occurrence UUID, native hash,
current slot and existing ChannelReceiveOrigin markers cannot mint that proof.

13a and 12e confirm NO such current producer exists. f805 agrees this contract
shape and the historical positive control; its dirty WP-202 draft is untouched.
All current ordinary incoming messages are Unproven and may persist/display
without indexing. Incoming reaction wire is also Unproven: retain explicit
bounded non-redrivable custody, not normal chat, native matching or reselection.
This contract does not implement that consumer custody or activate receive hooks.

InsertedUnproven(message,reason) means a COMMITTED original insert, not a save
failure, missing message or reaction grant. Existing(key) retains its original
committed-upsert meaning. Continue normal harvest/unread/notification/message
events for saved ordinary messages without reaction indexing. Unknown save
return preserves original envelope/DTO/cause custody; it does not recover proof.

History returns ChannelReactionHistoryEntry: CapturedChannelReactionTarget only
with authentic already-persisted original origin, otherwise
UnprovenChannelReactionMessage(message,reason). Do not filter legacy/migrated/
restored rows, change offset/order/hasMore or late-rebind displayed DTOs.
No live issuer means there is no newly proved incoming history from this delta.

Queries return ChannelReactionSelectionResult<T> with Selected, NoMatch,
Unproven, Stale or Rejected. NoMatch is an actual authenticated eligible query
without a match; the other outcomes do not authorize a fresh lookup. Apply adds
Unproven and consumes the SAME retained original reaction occurrence, including
per-item native batch/pending replay and MCO v1/v3. Both query and atomic apply
require exact reaction/message ORIGINAL occupant provenance equality, not equal
occurrence UUIDs: message and reaction are different events. A caller-supplied
ParsedReaction or ReactionDTO must be verified against that occurrence's wire.

Preserved positive: historical A target plus authenticated A reaction may apply
after live slot retirement to B. Do not compare against today's active slot or
erase A's stored origin solely due retirement. B reaction cannot match A; slot
changes fence future proof issuance. Same-ID ABA, secret/enable/lifecycle change
cannot remap either original event to a replacement. Restore/replacement or
missing/unresolved/inauthentic row origin yields no newly selected target.
Stale/rejected/unproven and UNKNOWN are not retry/reselection permission.

## Provenance correction and independent scope

The earlier `a303a95e2427f633a094f163bee333452bf4b3bb` remains preserved as
diagnostic history only. Its base eca8 was incorrectly characterized as unheld:
the coordinator reports held c77 strict-prehistory/schema/store semantics in
that tree even without held commit ancestry. That earlier characterization and
any main-ready interpretation are explicitly superseded.

This independently admitted lineage is based ONLY on exact main above. It imports
no implementation, migration, trigger, schema, completion handle or rejection
type from that history. It preserves one standalone role, all ten original value
controls, and adds six provenance-shape controls. All existing main files are unchanged. Retained work is not deleted,
rebased or activated. No other worktree or held candidate is inspected.

The standalone role uses existing main DTOs/EntityKey/ParsedReaction/
SnapshotList/ChannelReceiveEnvelope and domain-local result/provenance types. It has no send,
ACK, completion, lease, pending-cleanup or original outgoing-indexer dependency.
Outgoing completion capture/indexing and completion-successor support are
UNAVAILABLE until separately admitted/re-authored compatible contracts and
implementation exist. No empty interface, fake completion or generic fallback
is supplied to manufacture those prerequisites.

## Actual source admission and ownership

Serial authenticated REST reads verified current main and trusted inputs:

| Trusted input | Blob |
| --- | --- |
| `docs/android/port-manifest.json` | `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` |
| `docs/android/automation-policy.json` | `e56438d64d2309c964353066f26e1427f17f55d8` |
| `tools/android-port/controller/capabilities.py` | `1001f43112d9bff9c9985929a203b5166c41559e` |

| Path relative to immutable67a | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/ReactionTargetAuthority.kt` | `wp-owned / modify` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/ReactionTargetAuthorityTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/reaction-target-authority.md` | `wp-owned / modify` |

Manifest domain/evidence descendants and policy support glob plus Kotlin-test
regex admit these exact operations. No controller/reservation transaction,
generated receipt or passing ownership validator is claimed. Authorization,
policy, ledgers and locks remain unchanged.

The original main-relative creation remains admitted by wp-owned/create; this
correction modifies only those three immutable67a paths. f805 agreed revised
occurrence/history/selection/apply shapes; 12e and 13a confirmed the actual
producer limitation and per-reaction retention/custody requirements.

f805 agreed the standalone reaction-only shapes and independently authorized
provisional reaction v1-to-v2 producer route; no producer implementation is
claimed here. Its original workspace/base mismatch was reported to the
coordinator, not bypassed. 5c2 owns cache/App integration, 12e7 Sync, 13a queue.
All received the provenance correction and no-ancestry-import instruction.
No competing reaction contract draft was reported. Database/schema composition
and actual producer route/admission remain their own obligations, not inferred
from domain-file admission.

## Independent main inputs and behavior

Actual main source:

| Source | Blob |
| --- | --- |
| `MessagePersisting.kt` | `a39de1b68b190b817244e8075b6c397516f0034a` |
| `RepositoryValues.kt` | `bac26061dc0084fa303f88aa18c257fb8b82a7b9` |
| `MessageDTO.kt` | `73e9e78d5c6ac11560a84521e49e07f509e54099` |
| `DiagnosticDTOs.kt` | `439a39d0a49c3be28e26e4b829a419e82502d332` |
| `MessageRepository.kt` | `937380545fe91d5b77e4a1552be0180f34b8612f` |
| `AppChatsFeature.kt` | `509c5c835645064cc3f0dfbc69abfb4474fc35fc` |
| `ServiceContracts.kt` | `80fbc13d37e89f28e3ddf68c542f17dcf0f337f2` |
| `MessagePollingService.kt` | `8437b8a7d3c79bf793aa14fa6b69cc8b96515c30` |
| `SessionCore.kt` | `609a18d5c98aeaffee05265159e30a6a7b5e55a9` |
| `EventDispatcher.kt` | `82b4dbc2f60e854f45debb5b602d4318ec9896ef` |

Actual ServiceContracts ChannelReceiveOrigin.Proved has private constructor and
NO factory. Poller newDelivery emits only Unproved after stream dequeue or poll
result handling. Its occurrence UUID and actual ChannelMessage object distinguish
host fanout, not firmware identity, storage authentication or original epoch.
Exact-main SessionCore parses then dispatches under core lock, with no ordered
storage callback. EventDispatcher has no occupant epoch/transaction capability.
13a reports later a277 critical-owner/backlog code likewise has no proof issuer;
that local-only ref returned HTTP404 remotely and was NOT inspected/imported.
No claim of verifying that candidate or activating its consumer adapters is made.

Original ingress save preserves atomic upsert. Inserted(captured) certifies an
actually absent-before-insert row WITH authenticated occurrence origin only;
otherwise InsertedUnproven commits the ordinary row without target. Existing(key) means real existing-row
save committed with NO reaction authority. Existing continues ordinary saved
message harvest/delivery/notification, not false save failure or reaction
indexing. Rejected writes nothing. Unknown postcommit return cannot be
reconciled by later Existing/UUID equality; no retry ledger is invented.

Both original history reads capture actual row plus its stored provenance state
in the same read transaction, preserving target-free legacy rows. Offset preserves filter/order/limit/offset. Anchored refresh
preserves count-at/after anchor, max(floor,count), checked extra-row/hasMore and
display ordering, including more than 200 loaded rows. Both remain read-only,
including neutral invalidation fetch without prepare. Original binding/object
reference handoff is not equality-based regrant.

Native/MCO candidate selection remains a genuinely new never-targeted wire
lookup requiring the reaction's own authenticated original occurrence and matching
message origin, with original query bounds/order/format rules. It is not a cache/pending
replay substitute. A indexed, then deleted and recreated identically as B,
cannot be recovered as A by UUID/hash/text/timestamp/ranking hints.

The pinned collision oracle `ReactionServiceTests.swift` at the Swift pin,
blob `6426f1b5fd2048ee1c5e62210089b9801fcf1ce9`, lines 132-169, expects
most-recently-indexed choice. Cache ordering differs from createdAt DB ordering;
corrected reactionTimestamp differs from stored timestamp; old pending and
more than 200 loaded rows must not disappear behind a current-time fallback.
No original assertion/fault matrix is weakened or declared equivalent.

Atomic apply validates original persisted message/occupant incarnation and
reaction-identity revision/envelope AND original reaction occurrence/origin, then dedups/inserts, reads newest 100,
computes existing canonical/count/earliest summary and updates that row in ONE
write transaction. Applied(successor,summary,inserted) returns actual binding
and snapshot; inserted=false is not a new event/log. Stale/Unproven/Rejected write nothing.
Errors/cancellation propagate. No request journal, exactly-once or lost-successor
recovery is claimed.

## Genuine implementation prerequisites

No authenticated receive-origin producer exists. Before issuing any Proven
occurrence, an independently admitted ingress design must actually order event
capture against ALL relevant slot mutation paths/handles, bind its exact session/
event/wire and persist authentic original occupant incarnation. Do not block
protocol response dispatch behind a repository callback or activate a guard as
a contract shortcut. Raw supported DML, secret/enable/lifecycle change, same-value
upsert and same-ID delete/recreate must be reconciled in that real producer.
No schema, export or proof fixture is manufactured here; f805's draft is untouched.

Hosted producer controls remain required for A-event-before-B delayed work and
same-ID ABA, authentic A/A historical positive after retirement, B/A rejection,
forged/foreign/copied proof, same-secret repeat versus secret/enable changes,
legacy/restore/reopen/raw supported writes, per-item mixed occurrence replay,
ordinary unproven save/delivery and unproven reaction bounded custody, and
cancellation/commit-before-throw UNKNOWN without proof recovery.

The independent provisional v1-to-v2 migration must provide durable reaction
identity rotating on delete/reinsert/restore/replacement and advancing on
reaction-relevant channel/occupant/sender/text/reactionTimestamp/direction changes.
Proved-benign status/read/path/heard-repeat/summary changes must preserve safe
lineage. No all-update revision from held/unmerged history is imported, and no
new identity table/trigger is authored in this contract delta.

Original ingress/history producers and consumers must carry bindings without
late recapture, preserve cache collision/LRU ordering and exact predecessor
successors, and retain pending binding/receivedAt/arrival ordinals on requeue.
Ordinary replay failure cannot suppress already-saved incoming delivery;
cancellation propagates. These are acceptance obligations, not passing evidence.
Outgoing completion integration remains a distinct unavailable prerequisite.

## Validation and limits

Ten preserved value controls cover actual snapshot/corrected timestamp, opaque target
distinction despite equal DTOs, explicit successor/duplicate/stale/rejected
outcomes, radio/channel data, retained reaction metadata, empty summary, ingress
results and a 201-row anchored-window shape. They are UNCOMPILED/UNEXECUTED,
not transaction, ABA, schema, cache or native proof. Six additional shape controls
cover committed unproven save, mixed history visibility/order, distinct equal-wire
occurrences, non-upgrading context copies, distinct candidate outcomes and
unproven apply. All SIXTEEN are UNCOMPILED/UNEXECUTED.

Source-only validation checks ASCII/newlines/trailing whitespace, sixteen test
declarations, six suspend methods, and absence of external authority symbols
in the production/test source and preservation of the original ten method bodies.
Publication verifies sole immutable67a parent, three modified paths only, and
exactly three additions relative to main with no other tree differences.

The local checkout intentionally remains divergent under the retained execution
hold. No local Git/fetch/Gradle/compiler/controller/validator, readiness recovery,
held import, alternate-index producer execution, migration/DAO edit, PR or merge
is performed here. Exact hosted compilation/test discovery, producer schema and
consumer acceptance remain unverified. Source checks are not passing tests.

13a's source review identified a missing model import in the first independent
candidate c9b4d9a. The corrected candidate explicitly imports model.SnapshotList
in production and model.snapshot in its test. Both declarations are verified
against exact-main `Snapshots.kt`; no same-package alias is assumed. The first
candidate is superseded, not described as having passed compilation.
