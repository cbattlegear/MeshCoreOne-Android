# WP-201 read-only channel origin and creation inspection

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Exact producer base: `eca8e9db28fbf451e5541421f2e3b80d6d2f0cbe`,
tree `c4d66a9452c3f6ef2b3df57a55d70095320e0122`.
Coordinator assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_VFt3VaZErFV0R1f4NvljLaRs`.

## Bounded ownership and admission

The coordinator assigned this current contract workstream, not the explicitly held
historical WP-201 session. This supplies a separate optional
`ChannelMessageInspectionAuthority`, ten authored value/shape controls and this
evidence. f805 owns the actual query/Room implementation; 13a owns service receipt
and recovery consumption; 3008 owns the separately coordinated channel fence.
No storage/gate/schema/runtime/consumer or existing creation contract is edited here.

The exact base became reachable after the consumer published its authorized branch.
Serial authenticated GitHub REST reads verified the commit/tree and these trusted
inputs before source-derived admission:

| Trusted input | Blob |
| --- | --- |
| `docs/android/port-manifest.json` | `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` |
| `docs/android/automation-policy.json` | `e56438d64d2309c964353066f26e1427f17f55d8` |
| `tools/android-port/controller/capabilities.py` | `1001f43112d9bff9c9985929a203b5166c41559e` |

These are the actual current-base rules, not silently substituted older local
policy bytes. Before editing, each new path/operation was source-admitted:

| New path | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/ChannelMessageInspectionAuthority.kt` | `wp-owned / create` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/ChannelMessageInspectionAuthorityTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/channel-message-inspection-authority.md` | `wp-owned / create` |

Manifest WP-201 admits the domain/evidence descendants. The test matches the
trusted support glob and exact Kotlin-test regex. Controller/reservation operations
and validators were not executed; no generated admission receipt, prerequisite
verification or runtime readiness is claimed. Authorization, ledgers and locks are
unchanged. No held source/branch or other owner's worktree was inspected.

## Actual inputs and preserved draft

The exact base contract is `ChannelFailureAuthority.kt`,
blob `4f29f70f7e373d10cb0b73f863b0c9d60c471e33`. It defines
`ChannelHistoryScope(radioId, channelIndex, expectedOccupantID)`,
`ChannelMessageCreation(messageID, origin, text, textType)` and existing create/replay.
This new role consumes those types without modifying their declarations or signatures.

Read-only producer inputs:

| Source | Blob / relevant behavior |
| --- | --- |
| `ChannelSendRepository.kt` | `ba3f1399dd0448c6fc2029803ec0215840def655`; 267-270 occupant UUID equality only; 309-388 CREATE/reconcile |
| `ChannelSendStorage.kt` | `2ab6c57ab56e0c0ea43e1562a779f595609fc929`; persisted CREATE/origin metadata |
| Channel creation/origin digest helper | `4451b12e86b250b722ac85f8f651f8e83149bcd7`; full digest, original origin, timestamp and Instant seconds+nanos |

CREATE uses the message ID as stable capture request ID, stores an immutable
one-way creation digest and original message incarnation. The original origin
record stores radio/message/index/occupant UUID, incarnation, envelope digest and
current flag, not original text/time snapshots. Digest mismatch cannot reconstruct
the old caller's payload. Exact comparison may prove the supplied complete tuple;
it must not label a mismatched caller intent as what committed.

f805 explicitly identified its existing mutable draft as noncompliant: it still
requires a structurally recognized old lease and loses committed proof when the
original row/origin is not current. That draft's reported working hashes are
contract `5fe7d7436e7d87431e6e2ed7f56cb5c58a93042d` and producer
`41cf6502c64dc5f2dfa5a013229d97e1ebe989df` (not published/reviewed source).
They remain preserved, not adopted or overwritten. The separate role avoids
competing edits to its `ChannelFailureAuthority.kt` draft.

## Agreed minimal read-only seam

f805 and 13a agreed both methods need no lease. Full immutable key/creation
includes radio; requiring the old opaque issuer after reopen is incorrect, while
activating a new session only to read is an unnecessary lifecycle barrier.

`inspectChannelMessageOrigin(key)` returns `Found(receipt)`, `NotRecorded(key)` or
typed `Rejected`. It reads ORIGINAL persisted scope, never occupant B sampled from
the current channel slot. Raw/unbound legacy rows with no origin return NotRecorded,
not an invented default scope.

`inspectChannelMessageCreation(creation, timestamp, createdAt)` returns:

- `Committed(exactReceipt)` only when the immutable persisted CREATE digest matches
  the full caller tuple, including origin, message ID, exact text/type, UInt timestamp,
  Instant seconds/nanoseconds and canonical creation defaults. Historical proof
  survives cancellation, lease replacement, reopen, erased/recreated original rows
  and noncurrent/unresolved occupants. It does not issue a handle or INSERT.
- `Conflict(key, original?)` when a committed CREATE exists but the requested tuple
  differs. It preserves knowledge of a DIFFERENT creation without pretending caller
  arguments are original. Optional original observation requires independent stored
  evidence; no caller/current-slot scope or lost text/time is fabricated.
- `NotRecorded(key)` or `Rejected` otherwise. Neither authorizes recreation or proves
  a still-unsettled operation rolled back. Errors/cancellation propagate unchanged.

The exact receipt echoes matched caller creation/timestamp/createdAt as PROVED
inputs, not persisted payload recovery. Matching digest with missing origin can
still establish the commit, but currentness/payload binding remains UNVERIFIED
and canonical DTO is null.

Two facts are deliberately separate. `ChannelMessageStoredState` describes actual
original-row binding (ORIGINAL/MISSING/INVALIDATED/UNVERIFIED).
`ChannelMessageOriginCurrentness` describes original persistent occupant/radio
authority (CURRENT/RETIRED/UNVERIFIED). Canonical DTO is permitted only for ORIGINAL
after same-transaction original incarnation and full relevant envelope/origin digest
match. It may remain safely readable as HISTORY when authority is retired or
unverified; even CURRENT is not a dispatch or cleanup grant. Reads never clear a
radio marker, change revisions, activate/revoke, or destroy user history.

## Shared durable gate, unresolved state and retention dependency

3008 confirmed no gate-file overlap and agreed with the two-fact observation.
The exact base only compares occupant UUID at the slot; it lacks the shared durable
resolved-radio/opaque-epoch proof required to certify CURRENT. Its implementation
must report UNVERIFIED, not manufacture dispatch safety from UUID equality.
Persistent pre-SET unresolved state must survive until real reconciliation; a
message/pending deletion is not radio resolution. Replacement/clear/recreation/
ABA/restore cannot inherit occupant proof; a same-key authoritative rename may
preserve it only through the actual separately owned authority.

3008 also reported that the existing replacement test deletes message and pending
history, contrary to retaining original text. This contract does not repair or
approve that mutation behavior. Original history must remain readable/parked when
authority changes; explicit user erasure is a different operation. The coordinator
was notified that gate/unresolved/retention implementation needs separate storage
ownership. No held historical gate/schema implementation is imported.

13a's confirmed recovery rule is to retain original intent/text and park for
missing/unverified/retired origin or unsafe payload. Historical exact Committed with
null canonical DTO suppresses blind recreation; service receipts must distinguish
this from a currently available message. Observation never substitutes for the
shared operation/identity/Room gate and live connection/token checks at dispatch.

## Source controls and verification limits

Ten new value/shape controls cover exact tuple/nanosecond preservation, historical
commit without canonical payload, independent authority/currentness, original
versus replacement scope, known conflicting creation without invented payload,
NotRecorded distinction, UInt/text/type/identity families, typed rejections and
suspending query signatures without lease/handle parameters. These are authored
data assertions, not Room transaction/concurrency/retention or radio tests.

Producer evidence still needs actual Room controls for commit-then-cancellation,
new-context reopen without old lease, full-digest mismatch families (including
nanoseconds), original row delete/recreate/ABA, retained history after occupant
replacement, original digest edits, missing origin, unknown radio marker, read-only
no-DML behavior and absence while a create remains unsettled. Currentness requires
the separately admitted durable authority; no passing proxy can certify it.

The retained `local-check:f516a7e271f444dd9cb2efcc8b995a16` / missing canonical cleanup
and local resource hold remain respected. No local Git/fetch/Gradle/readiness recovery,
controller, test runner, schema generation, workflow dispatch, PR or merge is run.
Only bounded source reads, source-derived admission, byte/shape checks and serial
Git-data publication are performed. Ten controls are UNCOMPILED/UNEXECUTED; no test
discovery or passing count is claimed. The remote handoff is based on exact eca8
so its existing creation types are present; saved local files are not a claim that
the intentionally unadvanced local checkout compiles against that newer base.
