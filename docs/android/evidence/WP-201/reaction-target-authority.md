# WP-201 independent main-based reaction contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift specification: `db14559b39d32322b06477c6ae676112f583db50`.
Exact current-main base: `35abcc1276082995eed216bf28bff04d77e176c8`,
tree `91150b99594daa3a8cb965d84e0370005a14c124`.
Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_BNQOfp8vNVw5Tlm2Lh3G0vSV`.

## Provenance correction and independent scope

The earlier `a303a95e2427f633a094f163bee333452bf4b3bb` remains preserved as
diagnostic history only. Its base eca8 was incorrectly characterized as unheld:
the coordinator reports held c77 strict-prehistory/schema/store semantics in
that tree even without held commit ancestry. That earlier characterization and
any main-ready interpretation are explicitly superseded.

This independently admitted delta is based ONLY on exact main above. It imports
no implementation, migration, trigger, schema, completion handle or rejection
type from that history. It adds one standalone role, ten value controls and this
evidence. All existing main files are unchanged. Retained work is not deleted,
rebased or activated. No other worktree or held candidate is inspected.

The standalone role uses only existing main DTOs/EntityKey/ParsedReaction/
SnapshotList and a new domain-local ChannelReactionRejection. It has no send,
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

| New path | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/ReactionTargetAuthority.kt` | `wp-owned / create` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/ReactionTargetAuthorityTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/reaction-target-authority.md` | `wp-owned / create` |

Manifest domain/evidence descendants and policy support glob plus Kotlin-test
regex admit these exact operations. No controller/reservation transaction,
generated receipt or passing ownership validator is claimed. Authorization,
policy, ledgers and locks remain unchanged.

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

Original ingress save preserves atomic upsert. Inserted(captured) certifies an
actually absent-before-insert row only; Existing(key) means real existing-row
save committed with NO reaction authority. Existing continues ordinary saved
message harvest/delivery/notification, not false save failure or reaction
indexing. Rejected writes nothing. Unknown postcommit return cannot be
reconciled by later Existing/UUID equality; no retry ledger is invented.

Both original history reads capture actual row+target in the same read
transaction. Offset preserves filter/order/limit/offset. Anchored refresh
preserves count-at/after anchor, max(floor,count), checked extra-row/hasMore and
display ordering, including more than 200 loaded rows. Both remain read-only,
including neutral invalidation fetch without prepare. Original binding/object
reference handoff is not equality-based regrant.

Native/MCO candidate selection remains a genuinely new never-targeted wire
lookup with original query bounds/order/format rules. It is not a cache/pending
replay substitute. A indexed, then deleted and recreated identically as B,
cannot be recovered as A by UUID/hash/text/timestamp/ranking hints.

The pinned collision oracle `ReactionServiceTests.swift` at the Swift pin,
blob `6426f1b5fd2048ee1c5e62210089b9801fcf1ce9`, lines 132-169, expects
most-recently-indexed choice. Cache ordering differs from createdAt DB ordering;
corrected reactionTimestamp differs from stored timestamp; old pending and
more than 200 loaded rows must not disappear behind a current-time fallback.
No original assertion/fault matrix is weakened or declared equivalent.

Atomic apply validates original persisted message/occupant incarnation and
reaction-identity revision/envelope, then dedups/inserts, reads newest 100,
computes existing canonical/count/earliest summary and updates that row in ONE
write transaction. Applied(successor,summary,inserted) returns actual binding
and snapshot; inserted=false is not a new event/log. Stale/Rejected write nothing.
Errors/cancellation propagate. No request journal, exactly-once or lost-successor
recovery is claimed.

## Genuine implementation prerequisites

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

Ten new value controls cover actual snapshot/corrected timestamp, opaque target
distinction despite equal DTOs, explicit successor/duplicate/stale/rejected
outcomes, radio/channel data, retained reaction metadata, empty summary, ingress
results and a 201-row anchored-window shape. They are UNCOMPILED/UNEXECUTED,
not transaction, ABA, schema, cache or native proof.

Source-only validation checks ASCII/newlines/trailing whitespace, ten test
declarations, six suspend methods, and absence of external authority symbols
in the new production/test source. Publication verifies a sole exact-main
parent and exactly three additions, with no other tree differences.

The local checkout intentionally remains divergent under the retained execution
hold. No local Git/fetch/Gradle/compiler/controller/validator, readiness recovery,
held import, alternate-index producer execution, migration/DAO edit, PR or merge
is performed here. Exact hosted compilation/test discovery, producer schema and
consumer acceptance remain unverified. Source checks are not passing tests.
