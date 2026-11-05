# WP-201 identity-bound channel failure contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Remote parent: `56613c6edb25411bf7aef4ad9659675667d38983`.
Coordinator assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_T9vYyDzjGkTkPHNSe2X6W8Dd`.

This is the separately assigned additive channel-failure contract for the demonstrated
`a624d11463180d56c64e8fd1f5a054c88b3467b8` replacement race. It is not a new
direct-ACK physical-history certificate or an implementation of storage/service/UI.
The existing generic interfaces, their results and the direct-ACK contracts are unchanged.

## Admission and actual source

The manifest's WP-201 entry admits
`android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/`
and `docs/android/evidence/WP-201/`. The trusted `wp-owned` capability admits
`create` on the new `ChannelFailureAuthority.kt` and this evidence file. The related new
`android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/ChannelFailureAuthorityTest.kt`
is admitted by `reviewed-support-test/add-or-update-related-test`; it changes no existing
test or assertion. Rules were source-inspected in `automation-policy.json` and
`controller/capabilities.py`, including the exact test-path regex. No authorization,
reservation ledger, policy, verification lock, schema or other owner's source was edited.

Exact consumer source was read through serial authenticated remote blob requests:
MessageService `2407d27dffa9506ec359354667ec93946e13f123` and ChatSendQueueService
`dcabe666006335b3c1bdefc23d13703b5787e129`, at the a624 commit above.
MessageService saves inline at 1856, later reads queued rows at 1888 and writes
resend timestamps at 1897. The generic failure fallback can mutate a replacement;
queue terminal at 463-487 separately writes FAILED, publishes by ID and broadly
deletes pending rows. Its remap at 239-243 sets PENDING, not SENT.

Service's subsequent source-only regression freeze is
`d510b334a9eb1d1d2d0312b86e36be691c1f49ae`; its reported real-Room test blob is
`eb143331edacccb38157de34088ea75af215895c`. Those controls are unexecuted and
expected to expose the old producer gaps; they are not passing evidence here.

## Agreed seam and limits

The service owner and assigned Room producer (808) agreed atomic new creation/capture,
atomic preparation after preflight, a conditional remap returning a same-incarnation
successor, and atomic failure/exact pending cleanup. Storage owner f805 provided
advisory facts: persisted 64-hex message/pending incarnations and revision counters
already cover channels; generic writes fence both. 808, not f805, owns this new
channel producer and its combined schema integration.

`ChannelFailureAuthority` is a separate optional role using the actual persisted
`DirectAckLease` through a producer-validated bridge, not a forged public identity
or an implicit requirement to use DM sending. No default fake implementation exists.
Unavailable implementations/captures must be explicitly surfaced.
The service identified the missing channel-only lease role; the producer confirmed
explicit `activateChannelFailureSession` and `revokeChannelFailureSession` entries.
They use the same actual
session issuer and shared caller-known token/request, not a second independent
activation. One lifecycle owner revokes the shared lease after admitted persistence
completion; old lease replay cannot revoke a successor. Existing generic/direct
activation declarations remain byte-for-byte unchanged.

- New creation inserts and captures in one transaction; it never upserts a replacement.
- Preparation returns actual transaction-bound message/pending snapshots, performs an
  optional resend timestamp before binding, and binds an exact CHANNEL pending ID.
  Null pending ID means no cleanup authority. It does not bump attempts.
- Preparation binds the current transaction's rows, not an earlier envelope, DTO,
  generic save, preflight bump or identical-field replacement. Missing original
  authority for legacy work is retained as uncertainty, not recovered by key readback.
- Generic same-value writes invalidate. Typed own FAILED may advance its grant for
  later terminal cleanup. Typed PENDING remap returns a versioned successor for the
  same incarnations; old versions cannot perform new operations or renew authority.
- Terminal failure and deletion of only the captured pending row commit atomically.
  Exact historical replay reports the committed operation without changing replacements.
  DELIVERED is preserved, without failure or implicit deletion.

The producer selected dedicated history with no persisted message/pending payloads.
Receipts return live DTOs only under same-transaction exact bindings and active lease
proof; noncurrent receipts have both DTOs null. Operation/identity tombstones and
immutable request-argument bindings permit commit-then-throw reconciliation without
payload reconstruction. Inspection never issues a handle. Exceptions and cancellation
propagate; no exception is converted into fabricated uncertainty or rollback.

## Publication and observable behavior boundary

A failure commit is not permission for a later message-ID-only Failed event or
mark-failure-seen operation. Another writer can replace the row after return.
UI owner confirmed the current timeline is event-driven, not Room-subscribed, so
silently dropping failure events would lose normal observable channel behavior.
Use neutral invalidation that refetches actual state without applying a status or
mark-seen effect, or separately identity-fenced events. This contract does not add
an event variant or claim consumer wiring is complete.

The existing generic successful SENT, resend bookkeeping and successful broad pending
deletion paths also have replacement risks. They were reported to the coordinator
and owners; this failure-only contract does not clear or silently change those paths.
No historical original-channel identity or physical-radio birth proof is invented.

## Source-only acceptance and residual work

Seven new pure value controls preserve opaque-handle association, remap successor
identity, immutable typed operation history, historical exact-pending cleanup,
DELIVERED/result distinctions, all explicit rejections and retired payload absence.
They do not implement Room, simulate radio safety or prove transaction behavior.
Existing 38 direct-ACK value methods and original assertions remain untouched.

Local builds, Kotlin/Gradle tests and controller execution were not run under the
retained execution-owner hold and acute host-memory restriction. Test annotations
are authored-method counts, not runner discovery. Source-only checks cover exact
path/rule matching, whitespace, declarations and remote blob/ref/diff identity.
No hosted result, migration, hardware, lifecycle execution or UI acceptance is claimed.
The producer's exact-candidate Room controls and consumer/UI integration remain
separately owned. No PR, merge, runtime activation or schema version is selected here.
