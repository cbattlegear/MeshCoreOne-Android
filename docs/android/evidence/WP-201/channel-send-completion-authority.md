# WP-201 channel success and exact completion contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Remote parent: `1f138b71d061f426a497a93230957940efa6c29c`.
Coordinator assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_vMaD20qLlVlES2tyYPJx4GPa`.

## Bounded ownership and admission

The coordinator assigned an additive channel success/completion contract after the
failure-only repair left accepted-send replacement races. This implements only
domain types, value controls and this evidence. 808 is the separately designated
completion producer/control author; f805 remains sole combined-v4 schema/database/
store integrator; 13a owns actual service/queue integration. No schema version,
producer transaction, callback implementation, PR, merge or execution is supplied here.

The source-inspected WP-201 manifest admits the domain and WP-201 evidence paths.
Trusted `wp-owned/create` admits `ChannelSendCompletionAuthority.kt` and this file.
`reviewed-support-test/add-or-update-related-test` admits the new
`ChannelSendCompletionAuthorityTest.kt` in the contracts module test directory.
The exact path/operation checks were performed against the actual manifest and
automation policy before editing. Existing tests/assertions, authorization prose,
reservation ledgers, policies and verification locks are untouched.

## Exact counterexample source and prior art

Consumer source:
`29666b5c90fd39442778960f43e950d682ec88a3`,
ref `diagnostic/wp208-channel-neutral-source-29666b5c9`.
MessageService blob `dc880d9e5c8c88ee18da421d1c06c1893820dfd6`;
ChatSendQueueService blob `4920550ee51c4634ee0047745fb2dd10016233de`.
These actual blobs were read through serial authenticated remote requests without
fetching/decompressing local Git objects.

Inline success at 2023-2030 writes SENT by message key after transport return and
publishes an ID-only status. Queued success at 2057-2092 reuses `acceptedChannels[id]`,
writes SENT by key, then increments resend count, resets heard repeats and deletes
message repeats by key. Queue at 350-369 reuses `completedChannels[id]`, indexes
the captured text/timestamp, tracks `indexedChannels` by ID, then broadly deletes
pending sends by message key. Replacement can occur after acceptance or during
indexing/persistence failure. A new prepared row must not inherit these caches.

The existing opaque `ChannelFailureAttempt`, real shared lease and conditional
incarnation/revision fencing are reused, not replaced by a public identity DTO.
The canonical failure API blob `5897785d7a50a5d9b3648357e60a5cbb87348d52`
and neutral-event MessagingContracts blob `e94ffbe17697e5549fc811a28dccb876b79cf7e3`
remain unchanged. Existing generic persistence semantics are unchanged.

## Agreed minimal contract

13a confirmed that a completion UUID can be chosen on the existing capture before
transport invocation and retained with that original opaque attempt, mode and
actual accepted timestamp through all storage/index retries. 808 confirmed that
atomic completion plus a separate issued cleanup handle is implementable with a
dedicated completion journal. Completion entries must not be added as unknown kinds
to the existing failure-operation enum/receipt reader. 808 coordinates its exact
dedicated schema delta with f805; no separate migration/version allocation is assumed.

`ChannelSendCompletionAuthority` is a separate optional role with three operations:

- `recordChannelSendCompleted` atomically binds caller-observed acceptance, sets SENT
  and, for RESEND, performs the existing checked sendCount increment, heardRepeats
  reset and exact message-repeat deletion. All original capture/message/pending and
  lease bindings must still be current. A genuine acceptance is not recipient delivery,
  RF proof or an ACK; no ACK code or fabricated delivery tuple is added.
- `completeChannelSendPending` uses an opaque `ChannelSendCompletion`, after required
  indexing has returned, to atomically delete only the captured pending row and
  journal the exact cleanup request. It preserves every message field and retires
  authority; no broad deletion, replacement binding or cleanup of an uncaptured row.
- `inspectChannelSendCompletion` reports persisted tuple/cleanup history without
  issuing a handle. `NotRecorded` explicitly does not establish definitely unsent,
  rollback, a negative ACK or permission to send again.

Applied completion returns an issued completion handle. Exact AlreadyApplied replay
returns a handle only for its still-current exact committed version; invalidated/
retired/cleaned history has null handle and both DTOs null. The immutable accepted
tuple and cleanup identity survive without persisted payload snapshots. Exact
historical replay is read-only and does not repeat counters or delete replacements.
Changed arguments, another completion for the same capture or a different cleanup
after own commit conflict explicitly. DELIVERED is not regressed, counted or used
to manufacture a success/cleanup grant. Missing/stale/foreign/ineligible results
remain explicit typed rejections, not generic writes or readback-as-authority.

Successful own completion advances the expected grant only in its transaction and
prevents subsequent failure/remap/new completion through the old failure handle.
An independent same-value writer still invalidates. Known physical acceptance with
a thrown completion return must retain the original capture/request and retry or
inspect only that operation; it must not enter failure fallback, prepare a replacement,
bump pending attempts or resend. No durable physical-dispatch journal or exactly-once
physical-send claim is introduced. Acceptance existing only in RAM can still be lost
on process death; a missing journal is not invented negative physical evidence.
Storage exceptions and cancellation propagate, and thrown returns do not prove rollback.

## Indexing, cache and event boundaries

Consumer ownership scopes accepted/completed/indexed progress to the original capture/
version and opaque completion, not message ID. Index/persistence retries retain it
without a fresh preflight bump, prepare or wire invocation. A recreated same-key row
cannot inherit old success. After invalidation, retire the accepted old work with an
explicit diagnostic; do not bind current rows or clean up unrelated pending sends.

Actual inspected production adapter at `RadioSessionContainer.kt:216-218` calls
ReactionService.indexMessage and ignores returned pending reactions. At
`ReactionService.kt:85-97`, indexing writes the RAM message cache and removes matching
pending reactions; it is not a Room transaction. 13a independently confirmed these
facts. The outgoing callback can suspend in the generic interface, so a pre/post
read cannot atomically fence its effects against replacement. This DB contract
does not certify reaction-cache identity, pending-reaction effects or exactly-once
indexing. That separate consumer identity/retirement seam remains a reported limitation.
The contract accounts for the required ordering without falsely treating successful
indexing as new DB authority: cleanup still validates the original issued completion.

Receipts and success returns likewise cannot authorize later ID-only SENT, Resent,
Failed, mark-seen or indexing effects on a replacement. Preserve successful observable
behavior through the existing canonical neutral `PersistenceChanged(EntityKey)` hint
and actual refetch, or separately identity-fenced consumers. Neutral events confer no
status or incarnation proof. Original unrelated event variants remain unchanged.

## Source-only acceptance and residual validation

Nine new pure value controls cover issued-handle association, immutable mode/UInt32
timestamp tuples, current versus historical replay, payload-free invalidation/cleanup,
exact operation IDs, DELIVERED distinction, NotRecorded distinction, explicit rejection
families and radio/capture identity separation. These are authored values, not Room
transaction or physical-send tests. The prior 46 authored contract controls remain
unchanged; no passing test count or runner discovery is claimed.

Host resource restrictions and retained execution-owner holds forbid local builds,
tests, controller execution, fetching and lock changes. Only source admission,
whitespace/declaration checks and serial authenticated Git-data publication are used.
The exact remote parent/ref/blob identities and three-file addition scope are verified
at publication. No hosted result, generated schema validation, migration/reopen result,
real Room assertion, UI/reaction callback safety or physical radio acceptance occurred
in this contract session. Producer/consumer controls and integration remain separately
owned and source-only until actual authorized execution.
