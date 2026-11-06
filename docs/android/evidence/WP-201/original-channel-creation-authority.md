# WP-201 selection-time origin and original creation contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned specification: `db14559b39d32322b06477c6ae676112f583db50`.
Independent exact-main parent: `35abcc1276082995eed216bf28bff04d77e176c8`,
tree `91150b99594daa3a8cb965d84e0370005a14c124`.
Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_BNQOfp8vNVw5Tlm2Lh3G0vSV`.

## Agreed bounded scope

f805 confirmed actual implementable selection/create/inspect semantics and exact
proposed signatures. 13a confirmed consumer retention and this limited scope.
Coordinator explicitly requires selection-time opaque epoch proof, not a public
UUID assertion or an epoch sampled at creation for old composed text.

Three additions only: contract, six value-shape controls, this evidence. Existing
main source remains unchanged. This source requires no prior reaction, guard,
ACK, failure, origin, completion or held interface. The independent reaction67a
and dispatch268 contracts remain unchanged; neither is this delta's parent.
No held implementation or history is imported, activated or inspected.

The private producer must authenticate opaque public marker implementations.
Neither constructing a result nor implementing a marker creates authority.
OriginalChannelCreationReceipt is a creation-incarnation/origin receipt, NOT
OriginalChannelDispatchAdmission and not physical-send permission.

## Source-derived admission

Exact-main and trusted source blobs were reverified:

| Trusted input | Blob |
| --- | --- |
| `docs/android/port-manifest.json` | `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` |
| `docs/android/automation-policy.json` | `e56438d64d2309c964353066f26e1427f17f55d8` |
| `tools/android-port/controller/capabilities.py` | `1001f43112d9bff9c9985929a203b5166c41559e` |

| New path | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/OriginalChannelCreationAuthority.kt` | `wp-owned / create` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/OriginalChannelCreationAuthorityTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/original-channel-creation-authority.md` | `wp-owned / create` |

WP-201 manifest domain/evidence descendants and policy support-test operation,
plus controller Kotlin-test regex, admit these exact operations. No original
test or assertion is modified. No controller/reservation transaction, generated
admission receipt or passing ownership validator is claimed. Policy, immutable
authorization, ledgers and retained execution locks are unchanged.

## API and transaction obligations

The role OriginalChannelCreationAuthority exposes only:

```kotlin
suspend fun selectOriginalChannel(
    sessionToken: SessionToken,
    expectedChannel: ChannelDTO,
): OriginalChannelSelectionResult
suspend fun createOriginalChannelMessage(
    intent: OriginalChannelCreationIntent,
): OriginalChannelCreationResult
suspend fun inspectOriginalChannelCreation(
    intent: OriginalChannelCreationIntent,
): OriginalChannelCreationObservation
```

Selection returns Selected(SelectedOriginalChannel(proof,channel,sessionToken)),
Stale or Rejected(local reason). It runs at actual NEW selection/composer binding
BEFORE composition, reading canonical channel and authenticated durable epoch
in the same read transaction. expectedChannel is a stale-selection assertion,
not authority. Capture is retained with that draft; no service-time refresh.

Creation intent freezes request UUID, message UUID, original selection proof,
initiating SessionToken, exact text, TextType, UInt timestamp and full Instant.
Creation validates original proof issuer/store/session, epoch and slot and
atomically reserves ALL inputs, inserts WITHOUT upsert, and records original
message-incarnation origin. Epoch is never recaptured at create. A deleted A
recreated as same-UUID B rejects A's old proof. Missing/legacy proof retains text
but supplies no grant; generic saveMessage/upsert does not repair it.

Fresh Created carries exact private receipt, canonical actual persisted
MessageDTO and original SessionToken. Service synchronously retains them before
its subsequent ownership check or cancellation return. A public DTO/result or
later row read is not an issuer or substitute.

Exact duplicate request returns AlreadyRecorded with only Recorded evidence.
Its parameter is typed OriginalChannelCreationObservation.Recorded, permitting
only RecordedCurrent or RecordedUnavailable, not NotRecorded/Conflict.
Different complete bound inputs return REQUEST_CONFLICT without mutation.
RecordedCurrent means original incarnation present, not unchanged/sendable.
Unavailable retains original committed key despite deletion/replacement/session
retirement. Neither observation contains receipt or canonical grant.

Inspect uses retained COMPLETE intent to compare request bindings, without
refreshing proof or requiring old session to be reactivated. It returns
RecordedCurrent(requestId,key), RecordedUnavailable(requestId,key), NotRecorded,
or Conflict. NotRecorded is NOT rollback/no-effect proof or permission to create
again. Exact request observation after uncertain return never restores enqueue
authority. Exceptions/cancellation propagate unchanged; postcommit return loss
is UNKNOWN. No auto retry, new request ID, key rebinding or admission reissue.
Unavailable original authority and known row commit remain distinct.

## Actual inputs and separate prerequisites

Exact-main types verified:

| Input | Blob |
| --- | --- |
| `ChannelDTO.kt` | `aca6f253e93097351da74931fcca0652cad75682` |
| `MessageDTO.kt` | `73e9e78d5c6ac11560a84521e49e07f509e54099` |
| `ProtocolValues.kt` (TextType) | `0e04b7baadea581a7bf665bf4fecf7d78fcbdcbb` |
| `RepositoryValues.kt` (EntityKey) | `bac26061dc0084fa303f88aa18c257fb8b82a7b9` |
| `ConnectionContracts.kt` (SessionToken) | `458ff91dbd9480d273092190bf363b9ee8c06188` |
| `SendEnvelopes.kt` | `d68c55365a74cdae73c3caff03979362731bd952` |

ChannelDTO has UUID/radio/index, NOT original durable epoch. Existing synchronous
service prepare with held UUID-only scope cannot create a selection proof.
No cast/adapter or late current-slot read upgrades that retained intent.

f805 has separately assigned durable epoch/origin, full stable request journal,
original insert and mutation-fenced schema producer scope. Actual SQL/transactions,
migration and complete same-value/delete/recreate/restore fencing are theirs.
Real pre-composition selection capture and exact-proof retention in composer/App
remain separately owned, required before activation; no WP-202 UI write authority
is inferred. Service must accept actual proof and freeze stable creation fields.

Enqueue and queued-attempt methods are intentionally NOT exposed. Future enqueue
must link only original committed receipt/incarnation/origin to exact pending ID.
Future queued preparation must bind actual post-bump pending, timestamp policy
and original identities; no count/stamp duplication or current read grant.
Those separately frozen APIs, producers, mutation guard, original dispatch268
implementation, accepted completion and cleanup remain genuine prerequisites.
Inline dispatch, public ID-only retry, no-pending dispatch and legacy promotion
are not implemented or implicitly supported by creation.

## Validation and honest limits

Six value controls cover stable UUIDs/UInt max/nanosecond Instant, exact original
selection/session pair, actual creation receipt/message/session pair, distinct
opaque markers, no-receipt duplicate evidence and unavailable/conflict distinction.
These controls are UNCOMPILED/UNEXECUTED, not issuer/schema/ABA/transaction proof.

Source checks cover ASCII, final newline/trailing whitespace, six test methods,
three abstract suspend methods and no unavailable authority imports. Remote
publication checks sole exact-main parent, exactly three additions and no other
tree changes. Source checks are not Kotlin compilation or passing product tests.

Execution owner `local-check:f516a7e271f444dd9cb2efcc8b995a16` remains retained.
No local Git/object processing, Gradle/compiler/controller/readiness recovery,
schema/DAO/UI edit, other worktree, held import, PR or merge is performed.
Hosted compilation/test discovery, producer transaction/schema and composer/
service acceptance remain unverified. No CI-result bundle or execution receipt
is fabricated or retained.
