# WP-201 ordered receive-provenance issuer investigation

Repository: `cbattlegear/MeshCoreOne-Android`.
Trusted main: `35abcc1276082995eed216bf28bff04d77e176c8`,
tree `91150b99594daa3a8cb965d84e0370005a14c124`.
Swift specification: `db14559b39d32322b06477c6ae676112f583db50`.
Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_hl8rCzeMZRnYuRWeQRvAm0GC`.

## Result

A genuine issuer cannot be implemented from the VERIFIED existing protocol
semantics. The host can order its own callbacks and database transactions, but
cannot determine which original occupant produced a radio-buffered channel
message delivered after a slot replacement or reconnect. No verified receive
epoch, origin-bearing queue item, or acknowledged atomic queue/configuration
cutover is exposed. A process lock cannot recover information missing upstream.

This is a bounded conclusion about the inspected client/specification, NOT proof
that every firmware implementation lacks a possible extension. Firmware queue
implementation, exact firmware builds and hardware behavior were not inspected
or tested. Firmware may have undocumented behavior; undocumented behavior is not
an admissible authority guarantee. No positive issuer or fake proof is authored.

The approved source-only reaction contract
`67ec8bffe3d1eb66e1c896b9fbdebf7fc790b136` remains unchanged. Its all-Unproven
producer boundary is correct until the missing upstream capability is supplied.

## Wire and buffer trace

1. Channel receive V1 contains slot index, path length, text type, sender UInt32
   seconds and text. V3 adds SNR and two RESERVED bytes. Neither version carries
   radio boot identity, receive sequence, original slot incarnation, occupant UUID,
   channel secret identity or configuration epoch. Pinned Swift explicitly calls
   the two bytes reserved; interpreting them as an epoch would invent semantics.
2. PacketParser maps receive opcodes to those payloads. MESSAGES_WAITING and
   NO_MORE_MESSAGES are bare events with no cutover identity or receive watermark.
   Sender time is remote wall-clock data, not a local receive/configuration order.
   ChannelInfo reports current index/name/secret, not an earlier message's origin.
3. GET_MESSAGE is one byte and its response matcher accepts any matching channel
   message or NoMoreMessages in the current host exchange. SET_CHANNEL sends
   index/name/secret; simple waits for an unvalued OK. Neither response proves
   receive quiescence, a lossless drain, a flush, or ordering of every upstream
   in-flight message relative to configuration changes.
4. BLE notification callbacks enqueue Bytes into the connection ingress channel;
   the single collector later emits them. TCP reads accumulate framed payloads
   and enqueue decoded Bytes. TCP delimiter/length framing and transport ownership
   protect framing/host generation, not channel origin. Buffering exists before
   storage can see an occurrence.
5. SessionCore parses, checks the active host generation, offers the SAME event
   to registered response channels and then dispatches subscriber events. The
   main EventDispatcher has bounded subscriber queues and explicit drop reporting.
   No storage epoch is captured at that boundary. A repository suspension under
   the protocol response lock would risk blocking responses and still not
   identify firmware-buffered messages.
6. MessagePollingService retains pending/parked/observed-during-poll deliveries
   and pairs poll/stream echoes. newDelivery assigns a host occurrence UUID AFTER
   event/poll receipt, always ChannelReceiveOrigin.Unproved. Object identity
   helps host fanout pairing; it does not identify a firmware packet's occupant.
7. Session teardown ends host pending exchanges/subscriptions and cancels that
   generation. A fresh transport/session guards against old HOST callbacks, but
   the protocol has no verified promise that radio receive backlog is cleared,
   retagged or fenced. An old queued packet delivered on a new connection receives
   the new host SessionToken without acquiring original occupant provenance.

### Exact source pins

Paths below are repository-relative; all Android blobs are from trusted main.

| Source | Blob | Relevant lines |
| --- | --- | --- |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/parser/Parsers.kt` | `d328ac0717b6468d7d10cd05c069d32d1765d696` | 226-245, 279-286 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/parser/PacketParser.kt` | `45dbd242550209176e12a252468ed8378697c9ef` | 47-54, 72 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/model/PacketCodes.kt` | `f4dbd4f41f5114dd0f1f336c0694e09595e45a80` | 5-66, 74-120 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/command/PacketBuilder.kt` | `1579f9ac7dd0a2aab8b5c84ff98e736ef7490b0a` | 120, 162-165 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/session/MeshCoreSession.kt` | `6390ad66bf4a7f33bae2fd067c80721990ad6cbb` | 84-86, 272-282 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/session/SessionCore.kt` | `609a18d5c98aeaffee05265159e30a6a7b5e55a9` | 190-204, 271-291, 338-362, 465-480, 643-646 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/event/EventDispatcher.kt` | `82b4dbc2f60e854f45debb5b602d4318ec9896ef` | 21-27, 34-55, 106-135 |
| `android/core/ble/src/main/kotlin/com/meshcoreone/android/core/ble/BleTransport.kt` | `0b43f8fea951c742f3dee7ff8f77614af73850b9` | 42-56, 241, 503-511 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/transport/tcp/WiFiTransport.kt` | `1a98c245342f6770cbc340cc4dba9cdfc452f666` | 50-55, 172-184, 230-242 |
| `android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/transport/tcp/WiFiFrameCodec.kt` | `1f9ef9e0d85a3fb35083fa58939c25ebc9cfec83` | 8-24, 34-81 |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/ServiceContracts.kt` | `80fbc13d37e89f28e3ddf68c542f17dcf0f337f2` | ChannelReceiveOrigin / ChannelReceiveEnvelope |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/messaging/MessagePollingService.kt` | `8437b8a7d3c79bf793aa14fa6b69cc8b96515c30` | newDelivery / delivery queues / poll echo pairing |

Pinned Swift `MeshCore/Sources/MeshCore/Protocol/Parsers+Messaging.swift`,
blob `12edb8d6a2f4cc2ffb9fe706036d95c34c458fed`, lines 97-145, independently
documents V3 reserved bytes and the same slot-only channel message layout.
Pinned ProtocolInternals.md blob `f1982a7f732bfad8eaf92e1a627aaa2821bb1590`
describes OK as command success, not a receive/configuration barrier.

## Indistinguishable histories

These are analytical counterexamples, not hardware-reproduced traces:

| History A | History B | Host observation after replacement |
| --- | --- | --- |
| A receives payload P; device delays queue delivery; slot becomes B; P is dequeued | Slot becomes B; B receives equal P; P is dequeued | Identical slot/path/type/time/text/SNR and host current B |
| A receives P before disconnect; slot changes; reconnect returns queued P | Reconnect under B; B receives equal P | New host generation plus identical receive payload |
| A is deleted and recreated with the same UUID/secret as B; old P remains buffered | New same-UUID B receives equal P | UUID/secret/slot equality cannot distinguish incarnation |

The protocol does not exclude either side through an exposed epoch or verified
barrier. Therefore attaching today's database epoch at notification, parse,
critical dispatch, poll dequeue or save labels both histories the same. In one
history that label is wrong. An earlier Android callback can reduce host delay,
not reconstruct the missing radio queue origin.

Draining until NoMoreMessages before SET_CHANNEL leaves a receive race between
the observation and configuration; draining afterward cannot classify messages
already mixed across the cutover. A local mutex does not pause radio reception.
Sleep/grace periods, a matching GET_CHANNEL result, sender timestamps, reconnect,
same-secret equality and a dropped/cleared host queue do not close this gap.
Destructive flush is not a lossless barrier and is not authorized here.

## Participating occupant mutation audit

f805 supplied the inventory; the principal main routes below were also read
directly. Its new reaction-v2 message identity draft is NOT a channel epoch issuer.

| Boundary | Actual main behavior / missing ordering |
| --- | --- |
| `ChannelRepository.saveChannel(info/dto)` | Resolve slot or row, full upsert; secret/enable can change. No receive epoch or radio-queue ordering. |
| Batch configured/unconfigured/prune | Upserts configured rows; deletes messages/channels for unconfigured/pruned slots. Must fence each original slot, not only the UI operation. |
| Secret change / delete / same-ID recreate | Repository clears associated messages in some paths; public DAO/raw writes can bypass that algorithm. Same UUID cannot establish continuity. |
| Benign metadata | Unread, mute, favorite, last-date paths use full upsert; flood scope has targeted SQL. Future epoch rules must distinguish proved-benign updates from real occupant changes, not blindly stale all historical A work. |
| Device/radio deletion | DeviceRepository clears radio messages, pending rows and channels; host-session replacement is a distinct lifecycle boundary. |
| Backup restore/remap | Match by stable secret or slot, merge metadata, allocate free slot/new UUID, remap radio/slot. No imported message origin can be manufactured from current/restored rows. |
| DAO/SQL/other handles | Public insert/upsert/delete/clearRadio and ordinary Room SQL are separate writer surfaces; per-context mutex is not a database-wide or cross-process gate. |
| Radio-side changes | SET_CHANNEL, reconnect/config sync and other clients/firmware-local changes need protocol coordination; a database trigger cannot order an unobserved radio mutation. |

Direct source bindings:

| Source | Blob | Relevant lines |
| --- | --- | --- |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/ChannelRepository.kt` | `27d187f0f248774ebe673ab8260b88d5734c6c94` | 28-149 |
| `android/core/database/src/main/kotlin/com/meshcoreone/android/core/database/RadioMappings.kt` | `adae49dfd32681ef7ff1801e9c08fd2572107016` | 62-72 |
| `android/core/database/src/main/kotlin/com/meshcoreone/android/core/database/RadioEntities.kt` | `c40b78200a83bc8b094ab87e5cc5a2de529a8187` | 91-107 |
| `android/core/database/src/main/kotlin/com/meshcoreone/android/core/database/RadioDaos.kt` | `d790fb213b6f0e0846271ea89dce42655603bad1` | 17-20, 82-91 |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/DeviceRepository.kt` | `9e069c60b5f696a970716ff9c51a1fb99bc27252` | 130-144 |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/BackupDatabaseRestore.kt` | `9ef3eefccaac1d0a9bf0742f752cd29f9aae8c2e` | 144-183 |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/BackupRestoreMetadata.kt` | `36a7b70fc3d55c697b27f490cff107d3c4d1cec2` | 54-66 |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/RoomRepositoryContext.kt` | `85d5593ac6f45783acbefc5efeabb58a24f111d4` | per-context read/write/restore/close mutex |

ChannelEntity's slot index is nonunique and has no occupant epoch. Ambiguous
same-slot rows cannot be resolved into authority by first-row selection.
Future mutation fencing must cover every admitted DML/restore/lifecycle route
across handles/reopen/process boundaries; process serialization alone is insufficient.

## Concrete safe route

NOW: retain normal unproven message persistence, harvest/notification/display
and complete history order. Do not index those rows. Retain each unproven
reaction's exact occurrence in explicit bounded non-redrivable custody; no
native/MCO lookup, fallback, chat substitution or silent loss. UNKNOWN save is
not rollback, and known-committed incomplete delivery is not save-UNKNOWN.
No issuer, guard, capture hook or consumer is activated by this investigation.

TO ENABLE: obtain firmware source/specification and exact-build evidence for
one of these capabilities, then separately admit its protocol/storage/UI work:

1. Origin-bearing receive records: a radio boot/session identifier plus durable
   channel configuration incarnation/epoch captured at RADIO receive/decrypt/
   enqueue and carried unchanged through every radio/transport backlog. An
   ordered sequence and configuration ACK/watermark must bind each epoch to the
   exact configuration. The mechanism must distinguish same-ID/same-secret ABA,
   reconnect, reset and external configuration writers; a key hash alone cannot.
2. Alternatively, a documented and verified acknowledged quiesce/drain/cutover
   protocol: stop new ingress, account for every queued/in-flight record, finish
   the old epoch without loss, atomically change configuration/epoch, then resume.
   A receive sequence/watermark or equivalent protocol guarantee must prove all
   radio, BLE/TCP and host buffers are covered and external writers cannot bypass
   the cutover. Existing bare NoMoreMessages/OK does not provide that guarantee.

Then persist the mapping and authenticate each exact occurrence/session/wire,
not just its shared epoch; atomically save message plus original origin. Unknown
mapping, reconnect, external mutation, counter reset, restore or ambiguity remains
Unproven. Retain authentic A history across retirement; authenticated A reaction
may match A even after B becomes live, but B must never match A.

Acceptance must include byte-equal old/new payloads, buffered A before/after B,
same-ID ABA, secret/enable changes versus benign repeats, reconnect/reset, raw
supported writers/other handles, restore, per-item mixed batches, overflow and
cancellation/commit ambiguity. Hardware/firmware verification is a separate gate.
An app/storage source-only test cannot certify these upstream semantics.

## Admission, coordination and validation

This one new WP-201 evidence path is admitted by trusted manifest descendant
`docs/android/evidence/WP-201/`, capability `wp-owned`, operation `create`.
Trusted inputs were reverified: manifest `e81605857f5aa7b893dcd63224fb27a45d7b0f3a`,
policy `e56438d64d2309c964353066f26e1427f17f55d8`,
engine `1001f43112d9bff9c9985929a203b5166c41559e`.
No controller/reservation transaction, transfer receipt or ownership-validator
execution is claimed. f805, 13a and 5c supplied bounded owner findings consistent
with the inspected source; no other checkout or held candidate was accessed.

Validation is source investigation and immutable publication ancestry/diff only.
No JVM/Gradle tests, device tests, firmware execution, schema export, readiness
recovery or proof issuance occurred. Existing contract controls remain explicitly
unexecuted. Retained local-check owner f516a7e271f444dd9cb2efcc8b995a16 is unchanged.
No production/test source, policy, dirty owner draft, guard, PR or merge is changed.
