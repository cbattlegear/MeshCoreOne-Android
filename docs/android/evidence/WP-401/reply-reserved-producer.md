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
