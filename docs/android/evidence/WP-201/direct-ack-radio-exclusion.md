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

## Read-only radio confirmation evidence

The separately discoverable optional capability
`DirectAckRadioConfirmationEvidenceInspecting` exposes only
`inspectDirectAckRadioConfirmationEvidence(radioId): DirectAckRadioConfirmationEvidence`.
It does not extend the settlement authority or require any lease/claim/grant.
Both sealed variants return the exact `radioId`:
`NoPersistedWildcard(radioId)` and
`PersistedWildcard(radioId, reasons: SnapshotSet<DirectAckRadioConfirmationWildcardReason>)`.
The latter rejects empty reasons, including through data-class copy.

The four distinct reasons are `UNRESOLVED_PHYSICAL_EMISSION`,
`CONFLICTING_PHYSICAL_EVIDENCE`, `UNTRACKED_LEGACY_ANCESTRY`, and
`STICKY_SATURATION`. Storage must report every applicable reason in one atomic
complete radio-history read, including retired leases/generations and legacy
ancestry with a known accepted code or later markers. Known accepted and
definitely-unsent physical evidence alone is not wildcard evidence; a tracked
registered wire without a marker is not a physical emission. No diagnostic
sample, fake excluded claim, TTL or generation filter may narrow the query.
This read creates no evidence, handle, grant, mutation or authority renewal.

`NoPersistedWildcard` means only that no persisted wildcard was observed in
that atomic snapshot. Known code collisions, message eligibility, grant
validity and recipient delivery are not established. The snapshot can become
stale immediately and never replaces full atomic settlement. Missing
capability is explicitly unavailable; storage errors and cancellation
propagate through existing typed failure paths, never a no-wildcard fallback.

Four added value controls cover exact radio tagging, distinct typed reasons,
snapshot isolation from mutable inputs, and nonempty construction/copy
invariants. They bring the test file to 21 tests, not evidence that the actual
storage query is complete, atomic, read-only or error-propagating. Storage
implementation and UI integration remain separately owned and this additive
contract is source-only pending review.

## Optional eventual radio observation

The frozen snapshot source `78336196243c9de8e2f28a11a0817186e1bff80d` remains
immutable. The additive `DirectAckRadioConfirmationEvidenceObserving` capability
exposes `observeDirectAckRadioConfirmationEvidence(radioId):
Flow<DirectAckRadioConfirmationObservation>` separately from that suspend query.
Events are `Inspecting(radioId)` and `Snapshot(evidence)`; the latter derives its
radio from its evidence, including when copied, rather than accepting a second
potentially contradictory radio tag.

The coordinator selected subscriber-owned cold snapshot polling: immediately
emit `Inspecting`, perform a complete atomic read, emit `Snapshot`, then delay
cancellably for 1,000 milliseconds and repeat serially while subscribed.
Every completed read is emitted; there is no cached initial result, replay,
conflation or distinct suppression. The active radio graph shares one
subscription rather than creating duplicate scans for each consumer.
Same-process invalidation may accelerate a read but cannot replace or
indefinitely postpone the periodic fallback.

Complete repeated reads eventually observe currently persisted changes made
through other Room or raw SQL handles, without relying on single-handle Room
notifications or assuming multi-instance invalidation is enabled. This is
not a per-commit event log: intermediate commits can coalesce. Query duration,
scheduling and collector backpressure contribute latency, so it is not an
instantaneous-current or hard one-second observation-age guarantee.
Every no-wildcard result is historical and grants no all-clear, eligibility,
uniqueness or delivery claim.

Consumers begin each new graph/lifecycle subscription in checking state and
fence results by collection lifetime, radio and session. During refresh they
may retain an explicitly historical notice without flashing a spinner.
Typed query/close failures and cancellation propagate unchanged; error or
unexpected normal completion must become unavailable/uncertain rather than
retain an apparent clear. Cancellation stops reads; no detached worker,
mutation, expiry, reset or send-policy change is authorized.

Two added value controls check event identity/radio propagation and copied
snapshot radio derivation, bringing the test file to 23 tests. They do not prove
poll timing, cancellation, failure propagation, graph sharing or cross-handle
visibility. Storage owns real complete-read/cross-handle/observer controls and
UI owns lifecycle/consumer controls; these remain source-only and unexecuted
here. The snapshot and settlement declarations are unchanged.

The wire ACK has no session, graph, claim, or physical-emission origin field.
Therefore identical actual codes across distinct claims, unresolved possible
emissions, and sticky saturation are fundamentally nonsettling; the contract
preserves safety rather than inventing origin or expiry.

No local Gradle/Kotlin build or tests were run because of the explicit local
Gradle owner hold. Source-only `git diff --check` passed and 23 test annotations were
present in the contract test file; neither check establishes compilation or
test execution. Hosted exact-candidate compilation/test discovery is pending.
Physical radio testing and a real Room implementation of the optional
capability are not claimed.

## Atomic failure exclusion successor

This successor starts at
`0fb7fec315cb5d76988dfc8ff2eef9240c72092b`; previous snapshot/observer refs remain
immutable. The coordinator explicitly selected the failure policy below and
storage/service owners confirmed the exact additive declarations. No storage,
DAO, schema, MessageService, queue or UI implementation is included here.

`DirectAckRadioFailureAuthority.recordDirectFailureExcludingRadioHistory(attempt)`
returns `DirectAckRadioFailureResult`: `Applied(receipt)`,
`AlreadyApplied(receipt)`, `AlreadyDelivered(receipt)`,
`Nonfailing(reasons: SnapshotSet<DirectAckRadioNonfailingReason>)`, or
`Rejected(reason: DirectAckRejection)`. Nonfailing construction and copy require
a nonempty reason set. It neither writes FAILED nor invents delivery/ACK evidence.
Missing optional capability is explicitly unavailable, not a legacy-write fallback.

The eight distinct reason literals are:

- `TARGET_UNRESOLVED_PHYSICAL_EMISSION`
- `TARGET_CONFLICTING_PHYSICAL_EVIDENCE`
- `TARGET_UNTRACKED_LEGACY_ANCESTRY`
- `KNOWN_OTHER_CLAIM_CODE`
- `UNRESOLVED_OTHER_CLAIM_EMISSION`
- `CONFLICTING_OTHER_CLAIM_EVIDENCE`
- `UNTRACKED_OTHER_CLAIM_ANCESTRY`
- `RADIO_HISTORY_SATURATED`

New failure writes validate current grant/incarnation/revision and complete
persisted radio history in the same serialized transaction as the failure and
durable guarded-operation proof. Target MAY, conflict, malformed evidence or
untracked legacy ancestry without genuine definite-no-write proof retains
unknown/SENT/PENDING, never FAILED. Malformed target/foreign evidence maps to the
corresponding conflicting reason; this does not change read-only warning or
settlement error semantics. No row repair is authorized.

Sticky radio saturation always blocks a new guarded failure, including when
the tracked target has no physical rows or all represented emissions are
definitely unsent. `CoveredBySaturation` can permit a physical invocation
without an emission row/handle; the current contract provides no complete
target-specific proof that overrides that wildcard.

On a nonsaturated radio only, if complete tracked evidence proves no target
physical invocation or every target emission definitely unsent, unrelated
foreign history cannot have delivered that target and the known-safe failure
path remains. Otherwise foreign wildcard blocks failure. For accepted target emissions,
ANY accepted actual code confused with another claim's known predicted/actual
code blocks failure; an unambiguous alternative does not prove a different
physical send failed. No invented predicted ACK, bounded/UI snapshot, missing
diagnostic row, generic DeviceError3 inference, expiry or reset grants write
permission. ERROR2 and the generic Boolean/channel APIs are unchanged.

Own delivery wins as `AlreadyDelivered` before guarded replay; preserve the
initially DELIVERED RESEND baseline guard too, without fabricating a new
delivery tuple. Otherwise exact previously committed guarded failure replay is
historical/read-only `AlreadyApplied`, even after later blockers, invalidation
or revocation. Receipts retain actual permission and payload-erasure behavior.
An older generic FAILED commit without guarded proof is `Rejected(CONFLICT)`,
not guarded replay or a newly proven failure. Errors and cancellation propagate.

### Generic versus radio-exclusion settlement documentation

Earlier `DirectAckAuthority.kt` blob
`aee117e9a012ed5f33d9519e04807fe7c78b06bf` incorrectly documented generic
settlement as requiring prior acceptance. Storage confirmed the incumbent
generic predicate is `(acceptedCode ?: predictedCode) == ackCode` for each
registered wire: an unaccepted prediction is eligible before return, but its
own accepted actual supersedes it once present. Other registered wires remain
independently eligible. This successor corrects only that KDoc, preserving all
generic declarations and production behavior. The accepted-actual-only rule
is explicitly documented on `settleDirectAckExcludingRadioHistory`; no
settlement signature, result, classifier or implementation changes.

### Admission and source-only controls

The existing WP-201 domain/evidence admission covers these exact production
and evidence paths; the related test uses
`reviewed-support-test/add-or-update-related-test` and preserves all existing
cases/assertions. Rechecked manifest blob
`e81605857f5aa7b893dcd63224fb27a45d7b0f3a` and policy blob
`b12794157978d6d90ab7508d4e2852fdc2d5e4a4` in this isolated worktree.
No authorization prose, policy, reservation ledger or other owner's source
was changed. This is not a fresh-main rebase or a dependency/merge claim.

Three added value controls check nonempty construction/copy, all eight
immutable reasons, and target/foreign uncertainty versus authority rejection.
Source review also found seven older classifier fixture calls querying
456/789/987 while their target was accepted only for 123; those calls would
also yield TARGET_CODE_UNREGISTERED, contradicting their foreign-only expected
sets. The fixtures now accept the queried target code, leaving all original
expected assertions and production classification intact.

`git diff --check` passed. A source annotation count found 26 tests; none were
executed. Source comparisons confirmed the generic authority's non-comment
declarations are unchanged, and the snapshot/observer declarations remain
unchanged. These are source checks, not Kotlin compilation, test discovery,
Room transaction/replay/close/race proof or service integration evidence.
No Gradle/Kotlin build, hosted tests, runtime activation, PR or merge was
performed under the retained local owner hold. Storage owns real atomic
guard/current-grant/cross-handle/race/replay controls; service owns central and
queue failure routing controls. The contract-only handoff does not claim
those producers are integrated or verified.

### Saturation exception clarification

Service source review identified that the immutable
`9ca8f2f38e70105ab6ed2bfb133bbc3bb529b55d` no-marker/all-unsent wording was
unsafe if interpreted as overriding sticky saturation: a physical invocation
authorized by `CoveredBySaturation` need not have an individual emission row.
This documentation-only successor explicitly makes `RADIO_HISTORY_SATURATED`
mandatory for every new failure on a saturated radio, before considering the
nonsaturated known-safe exception. Delivery and exact guarded historical replay
remain read-only and keep their precedence. No new cap, clearing, expiry,
target-covered-invocation fact, schema or send-policy change is introduced.

Only the failure capability KDoc and this evidence changed. Generic authority,
all declarations, snapshot/observer source, settlement and the 26 unexecuted
test methods are unchanged. `git diff --check` and source-preservation checks
passed; no build or test execution occurred. Storage/service own actual
saturation/no-marker/all-unsent producer and consumer controls.
