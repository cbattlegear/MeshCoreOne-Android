# WP-201 shared incoming channel message derivation

## Bounded source-only handoff

This additive helper closes the lower-layer dependency gap identified during review of the
inactive `da544fe5daec77efaf484da1515e3b777c09ac50` storage candidate. That candidate accepted
message text independently of its retained original wire occurrence. Its owner accepted the
finding; this helper does not itself fix or accept that storage candidate.

Parent: `0d10b838a181cd3bd34af1a0200435149dbc6bb0`, tree
`f3ef82d5587250e2701a990f9828ef3a7f7d5f9f`, reverified through authenticated GitHub REST.
Only the two new model source/test files and this evidence file belong to this delta.
The immutable `c8f50a99d711a0c091a65ad0b46088b9789733ae` row/harvest contract is unchanged.
No database, DAO, service, runtime, build wiring, schema, issuer, or App activation is added.

## Inputs and provenance

The Swift specification remains at `db14559b39d32322b06477c6ae676112f583db50`.
Read-only local Swift files were compared by LF-normalized Git-blob SHA-1 against the manifest:

| Input | Blob |
| --- | --- |
| `MC1Services/Sources/MC1Services/Sync/SyncCoordinator+MessageHandlers.swift` | `295009f63bf62571cb3c832ae4292da08b03612f` |
| `MC1Services/Sources/MC1Services/Sync/SyncCoordinator.swift` | `aba4c68cd42c749372a9e314f64a6ca4d05f77ad` |

Actual existing Android behavior was read from the owner's explicitly authorized immutable
main-0d10 artifacts (also reported byte-identical at storage parent 416):

| Input | Blob |
| --- | --- |
| `SyncCoordinatorMessageHandlers.kt` | `be17371c695a00be8c1ec24ae5db1970ec834eae` |
| `SyncSwiftText.kt` | `3df640d8f19b6a31c45f33cf251eea51c7262a47` |
| `SyncCoordinator.kt` | `b93a83f0367e2e8a405533dd3b2756c83a553f04` |

Also read `SyncTypes.kt`, `ProtocolValues.kt`, `DeduplicationKey.kt`, and the actual
`SyncCoordinatorMessageHandlerTests`, `SyncCoordinatorTimestampTests`, and
`SyncNativeIngestionTests`. No new Swift/macOS oracle was run. Existing native tests describe
their Unicode vectors as previously pinned from Foundation; this delta does not reverify that claim.

## Admission and dependency boundary

WP-201 manifest blob `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` owns `android/core/model/`
and `docs/android/evidence/WP-201/`. Immutable policy blob
`e56438d64d2309c964353066f26e1427f17f55d8` admits `wp-owned` create/test/record-evidence
for those paths. Source-derived path/operation evaluation passed for the new source, test,
and evidence paths; this is not an executed controller admission or a reservation receipt.
The actual trusted capability engine remains `1001f43112d9bff9c9985929a203b5166c41559e`.
Locally preserved historical policy/controller drafts differ and were neither used nor changed.

The existing model production graph has `api(project(":core:protocol"))`; using protocol
`ChannelMessage` adds no dependency, services edge, or production cycle. Test-only contracts
dependencies are not used by the new helper or tests. No dependency manifest changes are needed.

## Literal behavior

`IncomingChannelMessageDerivation` is the canonical pure helper for both future guarded service
construction and storage validation. It exposes:

- `parse(String)`: the existing Swift-Character colon split, omitting empty pieces, followed by
  the exact enumerated Foundation whitespace trim. No body/sender normalization is introduced.
- `uint32Seconds(Instant)`: the existing clamped wire-date conversion, separate from clock recovery.
- `correctTimestamp(UInt, Instant)`: strict future/past windows, fractional receive-time behavior,
  exact toward-zero receive-time range check, and explicit recoverable out-of-range error.
- `derive(ChannelMessage, Instant)`: parsed sender/body, original UInt timestamp, corrected timestamp,
  correction flag, nullable original sender stamp only when corrected, mapped text type, unknown-raw
  warning metadata, content key based on the original timestamp, and typed receive-clock error.
- `mismatches(ChannelMessage, MessageDTO)`: a stable typed field list computed from the actual wire
  and declared `createdAt`, not a caller Boolean or proof marker.

The range-error path retains the sender timestamp uncorrected and leaves `senderTimestamp` null.
Unknown raw text types map to PLAIN while retaining their raw value for existing service warnings.
Dedup delegates to the existing model `DeduplicationKey`, not a new codec.

Neither helper results nor `createdAt` authenticate a receive clock, radio origin, session,
channel occupant, reaction, or stored row. Public result construction grants no authority.
Storage must independently check the c8f radio/slot, incoming/no-contact, unset RX metadata and
wire path/SNR constraints, plus delivered status, null sender key prefix, and delivery-context
sort date before DML. Mention state is not derived without the local node input.

## Controls and verification limits

Nine new `@Test` bodies retain the original ten parser cases, ten existing native Unicode/whitespace
vectors, timestamp tolerance/fractional cases, nine receive-clock range boundaries, wire-date clamp,
original-stamp reaction/dedup behavior, range-error behavior, all 256 text-type raw values, and
individual/multiple typed wire-field mismatches. Parser-malformed forms such as empty text and
omitted colon pieces retain existing behavior rather than inventing rejection or normalization.

These are source controls only: **UNCOMPILED / UNEXECUTED**. No test discovery count is claimed.
The retained execution-owner hold `local-check:f516a7e271f444dd9cb2efcc8b995a16` was not
bypassed; no local Gradle, Kotlin compiler, controller, readiness recovery, or other-checkout
execution occurred. Source review, immutable input checks, and source-shape checks are not tests.

The storage owner must integrate before-DML validation and actual Room no-DML negatives, including
the mismatched-body fixture and valid Existing upsert fixture. The service owner must delegate
existing public parser/correction wrappers and guarded construction to the same helper, adapting
the existing return/error types and preserving guard-OFF injected dedup behavior and diagnostics.
Those owner integrations and cross-layer equivalence/no-DML controls are not supplied or accepted
by this helper delta. No RF-origin proof or positive harvest/reaction acceptance is implied.
