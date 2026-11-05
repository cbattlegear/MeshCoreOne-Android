# WP-401: unactivated bounded reply producer

The containing commit binds this source-only successor. Its immutable inputs are producer
`14f2ef5479492001ac6c012618ee34d355d62a86` and capacity storage
`e1d7d42dc0c4dd0cfcff8cd10f7c29c8f0317c16`. Swift remains pinned at
`db14559b39d32322b06477c6ae676112f583db50`.

## Contract and ownership

The coordinator assigned the producer boundary only. The staging/enrollment implementation is
unchanged from e1d7 and remains 637-owned; receiver admission remains 2e-owned. The original
14f2 hostile PendingIntent controls, private reject-only component and manifest declaration are
preserved. Neither #515 nor #570, any active diagnostic ref, the historical advisory receipt,
nor the retained local execution owner is changed.

`NotificationReplyIssuanceAdapter.prepareReserved(proposal, maxReplyCodeUnits)` requires the
caller to supply a positive, representable Java UTF-16 code-unit bound. There is no default.
It invokes the actual store's `reserveIssuedReply(expectedStoreId, proposal, bound)`. Only
Reserved/AlreadyReserved with the exact store, entire typed proposal and bound return
`ExposureHeld(record)` and NEVER construct or return a PendingIntent. Null or mismatched bounds/identity return CORRUPT_STORAGE. Capacity, consumed,
conflict and other rejected results pass through without token construction. Indeterminate
passes through without exposure or replacement-token generation. Cancellation propagates.

Existing `prepare(proposal)` still invokes registry-only `registerIssued`, requires a null bound
and promises no future reply quota. Neither method initializes or migrates storage. The caller
must separately supply an actual bound enrollment handle and explicitly enable reservations.
The parser preserves raw text, including oversized input, without truncation or normalization;
only atomic `admitIssued` decides whether submitted bytes were retained.

## Reservation-to-publication race: exposure blocked

Source review of the initial cec1 proposal found a substantive interleaving: a prior action is
already visible; a second preparation reads AlreadyReserved and pauses; the receiver retains
the first submission and consumes that quota; the second preparation then constructs/re-exposes
the old token without remaining reserved capacity. A standalone recheck before returning cannot
protect a later Android notification publication. Neither the storage owner nor receiver owner
provides a cross-process issuance/admission/publication exclusion or durable fence.

The corrected API therefore only reports an exact transaction observation, not continuing
capacity or permission to expose an action. `ExposureHeld` may describe quota consumed after
the transaction. This is intentionally NOT the completed exposure boundary: actual bounded
notification publication remains blocked until the combined producer/receiver/platform
protocol supplies and tests the exclusion. No caller may convert the observed record into
a PendingIntent or publish it as a guaranteed reserved action.

The bounded reservation is logical quota for ONE initial submission, not disk preallocation,
an OS input cap, guaranteed writes, repeated/conflicting-input capacity, RF identity or sending.
Failure after notification text entry may still leave text unretained. The private destination
still rejects every delivery with an explicit not-retained diagnostic; no ingress is activated.
This log is not user-visible recovery, durable retention or a successful acknowledgment.

## Admission and evidence

Actual trusted admissions: adapter `modify` -> wp-owned; new focused test
`add-or-update-related-test` -> reviewed-support-test; this document `create` -> wp-owned.
The legacy advisory mismatch is not mutated or represented as a transferred receipt.

Ten new SDK31/37 test methods are authored against actual enrollment-bound SQLite storage and
real PendingIntents. They cover exact observation/retry, full row quota, consumed-token no-recreation,
registry/bounded conflicts, exact raw oversized input, every typed rejection, ambiguous and
cancelled results, mismatched returned receipts, real lost-return reconciliation, real
post-commit storage loss, invalid bounds, stale identity and missing storage. A deterministic
barrier pauses the real AlreadyReserved return while an actual prior PendingIntent submits text
and atomic admission consumes the quota; cancellation of the prior token followed by resumption
must produce only ExposureHeld and never recreate the token (FLAG_NO_CREATE control). Previously
visible tokens are explicitly test-created fixtures, not a bypassing production producer.
Fixture witnesses
are explicitly test-only, not production authenticated installation evidence.

At source freeze these new methods are NOT compiled or executed. Local Android execution is
blocked by the previously established installed `controller/authority.py` mismatch; it is not
bypassed. Exact diagnostic composition must preserve the original ten adapter methods
(20 executions), discover the ten new methods (20 executions), bind compiled source bytes and
report failures/errors/skips honestly. The existing module task is
`:platform:notifications:testDebugUnitTest`; the discovery owner supplies its admitted hosted
task/selector wiring. Original hosted logs, not a second result bundle, are authoritative.

Historical exact composition 413d95303ab801dc34fd99c4a05842bca2969362/run41970510796 passed
188 notification executions including all 20 original producer controls. That does NOT verify
this successor. No device, cross-UID, runtime installation, release or activation evidence is
claimed here.

## Closed one-shot publication successor

The later, unwired `AndroidMessagingNotificationDelivery.publishIssuedReplyOnce` owns
construction and `notify` together and returns no PendingIntent. It calls
`reservePublicationAttempt` with the exact bound installation, typed proposal, reply
bound and caller-supplied attempt/owner UUIDs. Only `NewlyClaimed` permits construction
and one app-level `notify` call. `Observed` returns `AttemptHeld`; rejection and
indeterminate outcomes never construct an action. Receipt identity and bound are
checked before construction. Cancellation propagates and never releases the permanent
claim; platform rejection likewise cannot authorize a retry.

This is at most one conforming APP publication attempt, not an atomic Android IPC
fence, display receipt, ongoing visibility promise or guaranteed retention of entered
text. Admission can consume quota while the sole first `notify` call is pending.
Permission and request checks before claiming are not promises that later platform
calls will succeed. The historical `prepareReserved` path remains exposure-held.

The isolated producer composition is
`3e56dc5580ea8e39758cd71f0f8dfbf70f6f4992`, with parents
`2c3a71902aec417b6920ffb778a2064c33489a0e` and the real corrected storage successor
`3768bcf61685f4c47bf3220903899a88c3da9cb5`. The storage source blob is
`954e71f68512dbab778d59e4920f0b137382836c`. No storage implementation is copied or
changed by the producer. Full hosted diagnostic
[41983088348](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41983088348)
is the authority for that exact composition; its result must be inspected, not inferred
from the separate schema-6 diagnostic.

That run completed FAILURE. The original Linux job127097519641 logged
`:platform:notifications:compileDebugKotlin`,
`:platform:notifications:compileDebugUnitTestKotlin` and
`:platform:notifications:testDebugUnitTest` without FROM-CACHE markers, followed by
`BUILD SUCCESSFUL in 12m 39s`. Post-Gradle evidence validation then failed:
`Cannot establish all inherited Kotlin JUnit declarations for changed test source`.
The nested Robolectric `CountingNotificationManager : ShadowNotificationManager()`
cannot be resolved by that candidate's trusted source-declaration checker. Therefore
there is no accepted method inventory/count or full diagnostic success. Controller,
classification and Swift codec jobs passed; Android CI failed and the SwiftData job
was skipped. No second CI-result bundle is retained. The bounded discovery-support
dependency was escalated to the coordinator without changing the verifier or
substituting a mock for the real PendingIntent/shadow-notify control.

Five new producer methods cover first publication and consumed replay, real
PendingIntent submission while a shadowed notify return is blocked, pre-claim
permission/request failures, terminal platform failure, and post-commit cancellation
followed by restart observation. Four execute on API31 and API37; the Android
notification runtime-permission method executes on API37 only (nine expected
executions). The existing declared task is
`:platform:notifications:testDebugUnitTest`. The blocked-notify control uses
Robolectric, not real Binder/device evidence.

The earlier producer diagnostic
[41982119166](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41982119166)
on exact `2c3a71902aec417b6920ffb778a2064c33489a0e` failed production compilation at
the storage Array.plus overload ambiguity; no notification tests executed. All
earlier refs and runs are preserved, including the separately failing storage
diagnostics. Source review and structural checks do not repair that execution gap.

## Rejected-input recovery successor

The producer-owned parser now reads RemoteInput independently before structural
identity rejection. `Rejected(reason, readableExactText, inputStatus)` carries
optional exact nonempty text solely as volatile recovery data. Input status
distinguishes READABLE, EMPTY, MISSING and MALFORMED. Stale, missing or malformed
identity retains its rejection reason and never becomes `ReadyForAdmission`.
Malformed/unreadable Parcelable input is explicit; there is no replacement text,
normalization, legacy decode or broad exception fallback. Valid-identity empty input
retains its prior structural behavior. Diagnostic `toString` redacts rejected text.

Three real-Intent methods execute on API31 and API37 (six expected executions) and
cover stale/missing/malformed identity with exact whitespace/non-BMP text, absent
and empty input, and a non-text Parcelable in Android's actual RemoteInput results
Bundle. The parser calls neither storage admission nor send/dispatch and makes no
persistence claim. WP-215 owns any later mapping to transient NOT_RETAINED and must
bind its own candidate and execution evidence.

Original parser refs are retained. The corrected-storage parser source pin
`bb1d7eb99dcc03cc3c6a5185335dd6a9c7470e6e` descends from the isolated producer above;
its source blob is `15190721b0d18dd32150a3febd8dac8d998f6a9d` and test blob is
`0bbed4eb425644c32854a254f762e583bee92a86`. At this documentation update its controls
have not yet executed. Neither this producer nor parser activates live ingress,
changes the private reject-only component or modifies the #515 worktree.
