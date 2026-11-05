# WP-201 radio-owned direct-ACK exclusion contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned source: `db14559b39d32322b06477c6ae676112f583db50`.
Starting candidate: `1ee2efd10bbcb2286289a5090db25baed77471cc`.
Starting branch base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
Current local `origin/main` at assignment start: `f0c4d4b993472fc11d33e19713635395ea1b1515`.

The current-main WP-201 manifest still pins the source above and admits the
domain-contract path; the related Kotlin test path is admitted by
`reviewed-support-test/add-or-update-related-test`. This work is an isolated
successor to the starting candidate, not a rebased current-main checkout. No
held issue/ref, active `c4` diagnostic, storage branch, or service branch was
changed.

Coordinator authorization is for isolated nonexclusive v3 contract
preparation atop the preserved v2 candidate; the new contract remains
nonactivated. The service and storage owners confirmed the per-emission marker
and resolver boundary. Storage's schema work is a separate implementation and
is not included or validated by this contract candidate.

The uncapped-journal correction supersedes
`5db00defc6c7ac1e6c22237edd1d48b8d99beb35` because its total-marker cap lost
normal-history selectivity. The tracked-wire and legacy-ancestry corrections
also supersede interim source `0eea3589d989a905f0d57b2ac1937a3b2732dc5e`.
Hosted run `42077798177` targets the capped `5db` SHA and is not verification
of either corrected source.

## Contract boundary

`DirectAckRadioExclusionAuthority` is optional and additive. It does not replace
`DirectAckAuthority`, alter its old settlement result, or silently change its
prepared/legacy success controls. Consumers must surface a missing capability;
they must not fall back to `settleDirectAck`.

The optional API declares:

- `beginDirectAckAttemptRequiringEmissionTracking(intent, firstWireRequestId,
  predictedAckCode): DirectAckBeginResult` and
  `registerDirectAckDispatchRequiringEmissionTracking(attempt, wireRequestId,
  predictedAckCode): DirectAckMutationResult` create the initial and retry wires
  with immutable tracked provenance in the same transaction. Exact tracked
  replays return `AlreadyBegun` / `AlreadyApplied`;
  generic/tracked operation-ID crossings, different inputs and attempts to
  upgrade a legacy wire or downgrade a tracked attempt reject with `CONFLICT`.
  The existing generic begin/register APIs retain their untracked legacy
  behavior. There is no begin-then-tag gap.
- `DirectAckRadioWireEvidence.emissionRequestId` is non-null for physical
  emission rows and null only for genuine markerless legacy wire rows. Tracked
  registered wires with no physical emission are omitted from emitted-history
  diagnostics; no synthetic emission identity is created.
- `markDirectAckWireMayHaveWritten(attempt, wireRequestId, emissionRequestId)`
  to commit a radio/claim/wire/physical-emission marker before every transport
  invocation and return a storage-issued opaque handle. The caller creates a
  fresh emission ID even when `wireRequestId` is reused by an explicit
  rejection/backoff retry. Exact ID replay recovers the marker without replaying
  the transport command.
- `resolveDirectAckWireEmission(handle, resolutionRequestId, resolution)` to
  persist only `DefinitelyUnsent` or `Accepted(actualCode)` for that exact
  physical emission. This can refine evidence after grant retirement, but
  cannot renew message mutation authority or clear another emission sharing the
  same wire ID.
- `inspectDirectAckWireEmission(attempt, wireRequestId, emissionRequestId)` and bounded
  `inspectDirectAckRadioEvidence` for commit uncertainty and diagnostics.
  `NotFound` is not proof of non-emission; a public snapshot never authorizes
  settlement.
- `settleDirectAckExcludingRadioHistory(attempt, ackCode, roundTripTime)` with
  explicit `Settled(Applied|AlreadyApplied)`, `Nonsettling(reasons)`, and
  `Rejected(reason)` outcomes.

Storage owner `f8058663-e138-4456-9d4d-6ad11522824c` agreed to the optional
interface and these transition/settlement semantics. The implementation must
serialize marker registration and settlement in the same database transaction
boundary: a competitor registration and settlement are totally ordered, the
settlement validates the current grant and all radio claims while committing
delivery, and it never trusts an earlier service-side snapshot. If the
pre-dispatch marker cannot be durably committed, transport is not invoked.
An accepted response updates the exact emission and its registered-wire actual
metadata through the transaction that resolves its exact emission handle. Every
physical emission retains its own actual code even when a retry reuses the same
wire ID. The legacy
`recordDirectSendAccepted(attempt, wireRequestId, ackCode)` has no physical
emission ID and must not clear any per-emission marker.

## Evidence and bounds

Evidence belongs to the stable radio history, not a service/session lease.
`MAY_HAVE_WRITTEN` is committed before each transport call; it is conservative
and does not prove physical emission. Only explicit transport evidence can
refine it to `DEFINITELY_UNSENT`. An accepted actual code is retained together
with its predicted history. Contradictions persist as wildcard `Conflict`; they
never overwrite the earlier fact. Markerless historical rows with no accepted
actual remain `LEGACY_UNKNOWN`, not definitely unsent.

The service owner confirmed that `withPoolBackoff` can reuse a registered wire
ID; every physical invocation therefore has its own marker and resolution
handle. The only current definite-nonwrite signal is
`MessageWriteDispatch.cancellationDisposition == DEFINITELY_UNSENT` after the
actual send propagates `CancellationException`. Firmware `DeviceError` means
the command bytes were written and remains `MAY_HAVE_WRITTEN`. A firmware code
3 is not a generic zero-write signal: attribution to the direct operation and
deployed firmware behavior are not established for all clients/interfaces,
and older ACK entries are not proven drained. No raw `DeviceError` code is
classified as definitely unsent by this contract.

Tracked initial/retry registration is required for every modern producer.
Tracking provenance is committed atomically with wire creation; a later marker
cannot upgrade an existing untracked wire, and generic registration cannot
downgrade a tracked attempt. A tracked registered wire with no physical marker
has not been invoked and contributes no physical emission; it cannot establish
target acceptance. Storage rejects marker registration on an untracked legacy
wire. Legacy markerless history remains distinct even if a later marker exists.

The service owner confirmed that receipt/tracker delivery is completed only
after `Settled`; `Nonsettling` discards only the exact pending ACK candidate
and leaves the logical claim waiting. A successful send response, late evidence
refinement, or raw ACK retained only in RAM cannot synthesize or publish
recipient delivery.

For the target logical claim, any matching accepted actual code from an exact
physical emission in that claim is eligible, including multiple emissions
that reused one wire ID; an earlier accepted emission remains eligible across
a prepared or differently accepted retry. A legacy wire-level accepted code
does not prove an exact physical emission or rule out earlier invocations. It
remains wildcard-ambiguous while preserving the accepted code and prediction
as known competitor values. For every other claim on that radio, each physical
emission contributes its predicted and accepted codes as known competitors.
An unresolved `MAY_HAVE_WRITTEN`, `LEGACY_UNKNOWN`,
`LEGACY_ACCEPTED_UNKNOWN`, or conflict is wildcard ambiguity.
`DEFINITELY_UNSENT` excludes only that exact physical emission.

The authoritative per-emission journal has no total-row cap, TTL or eviction:
exact emission identities, replay/conflict evidence, and unresolved facts are
retained. The 4,096-row bound applies only to diagnostic samples;
`TRUNCATED` means the sample omitted history and the diagnostic classifier
reports it as incomplete. This does not block settlement: the storage
transaction must query the full journal and may settle selectively beyond
4,096 rows.
Normal journal growth does not create saturation. `CoveredBySaturation` and
`SATURATED` only report a sticky wildcard already persisted (for example,
conservative legacy history); it is never reset by reconnect, lease
replacement, or process restart. Storage must use bounded/paged working memory
or database-side evaluation without dropping journal rows, and surface
persistence failures before physical dispatch.

The API does not durably ingest a raw ACK that was observed only in RAM and
does not promise to replay such an ACK after process death. The service owner
must buffer unresolved pre-response ACKs in process and reconcile only after
the matching actual code is persisted.

## Resolution operation replay

The emission receipt's `resolutionRequestId` reports latest committed metadata;
it is not the complete replay history or necessarily the caller's replayed ID.
Storage must atomically retain each resolution operation's identity, exact input
and committed result independently of that latest field, without retiring older
identities when a new resolution commits or the database/process reopens.
An existing durable operation journal may provide this history; the contract
does not prescribe a schema change.

Required real-storage sequence: resolve emission E with r1 `Accepted(123)`, then
r2 `Accepted(123)`, reopen, and replay r1. The replay must be `AlreadyApplied`
without moving latest metadata back from r2 to r1. Reusing r1 with `Accepted(456)`
or `DefinitelyUnsent` must return sticky `Conflict`, retaining the first accepted
actual and any unsent observation. Replaying a prior successful operation must
not clear that conflict, restore old evidence or renew authority. A lookup of
only the emission row's latest ID cannot satisfy these cases.

Within a claim, each resolution ID binds its exact wire, physical emission and
resolution input; reuse across distinct emissions is `Conflict`, even for the
same accepted code. The original operation entry is never overwritten.
File-reopen controls retain a genuine opaque emission handle and validate its
persisted original issuer/lease/emission binding rather than the receiver's
current issuer. This permits historical evidence refinement only, not product
grant renewal or relaxation of existing attempt/lease issuer fences. Actual
process death cannot reconstruct that handle through the current API; retained
operation history does not imply handle recovery or restart replay support.

The contract value tests distinguish latest metadata from an older replay's
operation identity and preserve conflict receipt data. They do not execute this
storage sequence or prove persistence across reopen. Actual Room/journal/reopen
controls are requested from the separately owned storage implementation.

## Validation and limits

Fifteen pure classifier controls cover target accepted-code matching,
same-claim prior wires, both foreign predicted/actual code memberships,
unknown and conflicting wildcards, legacy accepted-code wildcard retention and
its preservation alongside a later physical marker,
definite non-emission, saturation,
truncation, radio partitioning, reused wire IDs across distinct physical
emissions (including different actual codes on foreign emissions sharing one
wire ID), and a definite-unsent retry that cannot clear an earlier
possible-emission marker. They specify only the diagnostic candidate;
they are not evidence of transactional storage enforcement, real service
routing, radio hardware behavior, or durable ACK ingress.
Two additional receipt-value controls cover older-replay/latest-metadata
distinction and sticky-conflict representation, bringing this file to 17 tests.

The wire ACK has no session, graph, claim, or physical-emission origin field.
Therefore identical actual codes across distinct claims, unresolved possible
emissions, and sticky saturation are fundamentally nonsettling; the contract
preserves safety rather than inventing origin or expiry.

No local Gradle/Kotlin build or tests were run because of the explicit local
Gradle owner hold. Source-only `git diff --check` passed and 17 test annotations were
present in the contract test file; neither check establishes compilation or
test execution. Hosted exact-candidate compilation/test discovery is pending.
Physical radio testing and a real Room implementation of the optional
capability are not claimed.
