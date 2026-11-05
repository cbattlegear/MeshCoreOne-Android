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

## Preclaim missing-contact proof limitation

The separate preclaim missing-contact/terminal path has no DirectAckAttempt.
Its caller has a current session lease and message/contact identity, and may
read pending DTOs, but those public values are not mutation authority. A
positive atomic failure/cleanup would need opaque exact message and pending
incarnation/revision bindings, contact-absence revalidation, complete retained
target ancestry, a genuine birth/no-write proof, stable operation identity and
delivery-first guards. Pending-row replacements must never be broadly deleted.

Storage owner confirmed that the current producer has no genuine message-birth
or no-write certificate: generic save/upsert/import and incarnation/revision
tracking do not prove whether transport previously ran. Empty claim, intent or
marker history and an attempt count of zero cannot certify an older untracked
message. Capturing a current intent cannot create that missing provenance;
the existing no-dispatch pending-completion capability is cleanup authority,
not permission to set FAILED. Sticky saturation also prevents new failure.

Consequently this contract handoff adds no success-shaped preclaim API,
fabricated attempt/handle or interpretation of absence as proof. Service's
interim safe path retains and parks uncertain preclaim work without generic
FAILED or pending deletion; this is a known-safe-cleanup parity gap, not a
passing replacement. Delivered cleanup requires its actual existing proven
authority, not an unguarded DELIVERED DTO read.

A useful positive API requires a separately agreed/admitted creation protocol
that atomically establishes immutable no-dispatch provenance before any
transport, with explicit import/legacy treatment and every relevant writer
fenced. That cannot retroactively certify legacy rows. Coordinator was asked
to resolve that scope versus accepting the explicit retain/park deviation;
no creation protocol or producer implementation is claimed here. The
coordinator subsequently chose the bounded retain/park option: no new
preclaim positive API now, and delivered cleanup only through authority that
actually authorizes that exact operation.

### Separate fresh-creation provenance follow-up design

This is a proposed independently admitted follow-up, not a declaration,
implementation, prerequisite to the atomic-attempt guard, or promise that
existing rows become eligible.

1. Establish origin only through a new typed atomic fresh outgoing-DM creation
   operation. It inserts the message with persisted birth identity and a
   caller-known stable creation request. Preserve the existing message-
   creation return boundary independently of queue acceptance: do not merge
   message creation and sequenced pending insertion into one required
   transaction. A later typed pending attachment must bind exact pending
   identity/revision without claiming that attachment itself proves no send.
   Inline sends have no pending row.
   The store issues an opaque receipt/handle bound to issuer, exact message
   incarnation and exact pending identities. Exact creation replay observes
   the original operation, never upserts a replacement. Generic save,
   restored DTOs, supplied timestamps/counts and caller-created UUIDs do not
   certify birth. Retain tombstones so deletion/recreation, key reuse and
   lost-return replay cannot reset an old identity into fresh eligibility.
2. Maintain a durable one-way no-dispatch eligibility state. Before every
   physical invocation, atomically consume that eligibility with the actual
   pre-dispatch admission, including invocations covered only by saturation
   and every backoff/retry sharing a logical wire ID. It remains consumed
   after a thrown return, cancellation or process death; a later unsent
   outcome cannot retrospectively recreate birth proof. Any future narrower
   zero-write restoration would require its own exact evidence protocol.
   Generic message/queue DML across ordinary handles invalidates eligibility;
   typed preparation may preserve it only through a prevalidated same-
   transaction rule with explicit tests. No process-local exemption suffices.
3. Keep creation provenance separate from a connection's mutation grant.
   A current lease must capture exact eligible message/pending revisions
   before terminal work. After restart, persisted provenance alone must not
   reconstruct an old handle or revive a retired lease; a new supported
   storage capture may bind the current lease to still-eligible provenance.
   Missing/erased/invalidated provenance returns explicit uncertainty, not
   an empty-history success.
4. A future missing-contact terminal transaction revalidates the current
   lease, captured message/pending incarnations/revisions, actual contact
   absence, genuine unconsumed provenance and complete target ancestry.
   Delivery wins without relabeling it; sticky saturation and contradictory,
   possible or legacy transport evidence deny new failure. Failure, exact
   captured pending cleanup and an immutable terminal-operation proof commit
   together. A restored contact, pending replacement or competing mutation
   rejects without partial cleanup. Read-only exact replay reconciles lost
   returns without deleting a replacement or implying a fresh proof.
5. Existing, imported, deduplicated/skipped-import, synced, simulator-seeded
   and raw-DAO-created rows never acquire birth proof merely because their
   history is empty. Restore must invalidate affected eligible provenance
   even when an entry is skipped or remapped to an existing row. Backups must
   not serialize an exportable authority certificate. Legacy/imported rows
   retain explicit uncertainty/park behavior unless separately supported
   genuine evidence exists; no migration backfills proof from absence.

Current-worktree source landmarks demonstrate why this is cross-owner work:
`MessageRepository.saveMessage` is generic upsert; `MessageService` creates
inline and retry messages via that API; `ChatSendQueueService` separately
inserts sequenced pending rows; `BackupMessageRestore` inserts, deduplicates
and remaps messages independently. Simulator/sync writers also use generic
save. These source landmarks are not a claim that the owners' later frozen
candidates were inspected. The service owner must account for pending
creation, inline/retry/notification paths, creation callbacks, retained queue
hydration and every actual physical dispatch; storage/restore owners must
account for public DAO/raw-SQL and restore invalidation.

Required acceptance includes real Room cross-handle same-value mutation,
delete/recreate and restore-skip controls; creation/terminal commit-then-throw
replay and file reopen; missing-contact restoration races; exact pending
replacement protection; pre-dispatch crash/cancellation and saturation-covered
invocation controls; all creation/callback/queue/transport surfaces; and
explicit legacy/imported noneligibility. Preserve original successful-send,
delivery, channel and cleanup assertions. No such tests or follow-up
implementation have been executed or authorized by this design.

## Separate durable message confirmation snapshot

The separately authorized additive
`DirectMessageConfirmationEvidenceInspecting` capability exposes
`inspectDirectMessageConfirmationEvidence(key: EntityKey):
DirectMessageConfirmationEvidence`. It accepts no code, reconstructed claim,
lease or mutation handle. Outcomes are `Missing(key)`, `NotEligible(key)`, and
`Present(key, identity, status, reasons)`. Missing means actual product-row
absence; NotEligible means an actual non-outgoing-DM row. Missing or malformed
persisted diagnostic identity is a typed storage failure, not either result.

`DirectMessageConfirmationIdentity(incarnationId: String, revision: Long)`
preserves the literal persisted incarnation, which storage confirmed is
`lower(hex(randomblob(32)))`, not a UUID. The value requires 64 hexadecimal
characters and a nonnegative revision, without truncating, hashing, UUID
conversion or case normalization. It is publicly constructible diagnostic
data, never an authenticated handle, and no mutation API accepts it.

Present carries actual status and a defensive nonempty
`SnapshotSet<DirectMessageConfirmationUncertaintyReason>`. The nine exact
literals are `TARGET_PROVENANCE_UNPROVEN` plus the eight spellings of the
separate radio nonfailing reasons. Every current Present includes unproven
provenance, even when it also carries a known collision or actual DELIVERED
status. There is deliberately no green/NoConflict/sufficient-provenance
variant. A diagnostic reason never relabels or fabricates delivery.

Storage reads the current exact radio/message incarnation/revision/status
and complete retained claim/wire/emission ancestry in one transaction using
indexed constant-size EXISTS aggregates, not capped diagnostic samples or
unbounded materialized histories. Target actual code evidence is compared
against all different claims' retained predicted/actual competitors; other
claims for the same message still compete. Wildcard, conflict, legacy and
sticky saturation remain distinct. Retired grants and payload erasure must
not hide retained positive collision evidence. Existing typed read failures,
closure and cancellation propagate unchanged.

The target actual set includes valid ACCEPTED physical emissions and genuine
retained registered-wire accepted bindings, including legacy accepted actuals
alongside legacy/unproven reasons. Neither target predictions nor a public
DTO ACK is an actual binding. This is diagnostic collision evidence only,
not expansion of physical-actual-only settlement eligibility.

The result is a historical snapshot, not an event stream, mutation receipt,
settlement permission or no-write proof. Consumers own serial requery and
fence request/graph/session/radio plus diagnostic incarnation/revision.
Identical DTO values and key equality cannot bind a timeline row to that
incarnation; unknown binding remains unavailable rather than applying a
message-specific previous-incarnation claim or an apparent all-clear.
Replacement discards old identity-specific state. UI preserves actual
DELIVERED and exposes uncertainty, never infers definitely unsent/FAILED
from absent diagnostic evidence. The existing radio snapshot/observer and
atomic failure declarations are untouched.

Eight new value controls cover Missing/NotEligible and exact radio/message
identity, all status values including DELIVERED, replacement/revision
distinction, coexistence of known collision with unproven provenance, all nine
immutable reasons, nonempty construction/copy, full stored-identity literal
preservation, and malformed identity/revision rejection. Value reconstruction
is not a restart/reopen storage test. The tests do not establish completeness,
indexed SQL behavior, status atomicity, observer lifecycle or UI integration.
All eight are unexecuted under the existing local execution hold; the older
26 radio value/classifier tests remain unexecuted and unchanged.

### Migration prehistory finding

At coordinator request, storage and service owners separately examined the
supported pre-authority producer/migration lineage. They report that v1 could
physically send DMs without authority wire/emission rows, including messages
later deleted; migration backfills message identities but starts new authority
history empty. Consequently complete SQL over retained journal rows cannot
prove complete pre-authority physical history or exclude a late old ACK.
No actual migration/reopen control has executed for this finding here.

This is a distinct unresolved producer/bootstrap uncertainty policy, not a
silent change to the frozen atomic failure or settlement API, new TTL or
manufactured zero-write proof. The message diagnostic always-unproven reason
explicitly preserves that gap. The coordinator and storage owner have the
actionable requirement for genuine provenance or conservative prehistory
handling plus a real file-reopen control. This contract does not certify their
migration implementation or activate a policy. The coordinator subsequently
selected durable DB-wide preauthority-history UNKNOWN for untrusted
pre-journal upgrades, including empty/deleted history, without TTL/reset.
That requires a separate typed-reason/producer successor; this first message
snapshot freeze does not silently change the frozen 671 contract or claim
that the bootstrap policy is implemented.

## Separate physical-prehistory uncertainty contract

The coordinator initially selected durable DB-wide UNKNOWN for untrusted
pre-journal upgrades, including databases with no surviving messages/radios
and deleted/readded radio identities, then provisionally extended the strict
boundary to unproved external history on fresh installations as described
below. Storage confirmed the additive literal
`PREAUTHORITY_HISTORY_UNKNOWN` in four independent enums:
`DirectAckRadioNonsettlingReason`, `DirectAckRadioNonfailingReason`,
`DirectAckRadioConfirmationWildcardReason`, and
`DirectMessageConfirmationUncertaintyReason`. It describes missing retained
pre-authority physical history, not a synthetic wire, saturation or truncation.

The bounded `DirectAckRadioEvidenceSnapshot` now requires
`preauthorityHistoryUnknown: Boolean` with no default. Storage must read actual
DB-wide metadata in the same transaction as the rows and coverage; producers
must explicitly supply it. The diagnostic classifier independently adds the
typed reason, preserving coexistence with COMPLETE, TRUNCATED and SATURATED
samples. This intentional constructor evolution prevents an old producer from
silently manufacturing a false flag; the only current-worktree construction
site is the related test helper, updated explicitly. Other owners must update
their separately frozen producer/test construction sites when composing.

New settlement/failure reads consult the durable marker in their serialized
write transaction for every requested radio. It blocks new confirmation;
failure cannot infer an older target never emitted from absent rows or only
the current attempt's unsent markers. Preserve genuine delivery and proven
historical guarded replay precedence. Do not conflate unknown foreign history
with radio saturation: independently proved whole-target no-write evidence
would be a different question, but current legacy/imported rows have no birth
certificate. Ordinary sending admission is unchanged. The marker has no
invented TTL, auto-clear, reconnect reset or new synthetic evidence.

Radio confirmation snapshots and their existing observer must include this
DB-wide reason even when the requested radio has no rows. Message snapshots
include it independently alongside always-unproven target provenance and any
positive known collision. A false flag does not certify external physical
history, delivery, code uniqueness or eligibility. This contract does not
allocate a schema version or modify genuine schema 3, migration SQL, DAO,
storage or service implementation.

Three new radio value/classifier controls cover independent coverage flags,
empty retained history without fake saturation, and distinct wildcard/
nonfailure reasons. One new message value control covers prehistory plus
known collision/unproven target without changing actual DELIVERED status.
Enumeration cardinality assertions now include the admitted literal (five
radio wildcard reasons, nine radio nonfailing reasons, ten message reasons);
no earlier classifier outcome assertions are removed or weakened. Source
annotation counts are 29 radio tests and nine message tests, all unexecuted.
These controls do not establish metadata durability, migration behavior,
transactional exclusion, observer visibility or cross-handle correctness.
Storage owns real migration/file-reopen and zero-surviving-row regressions;
source-authored expected-failing tests are not executed failure evidence.

### External-radio physical-history boundary remains a product gate

Pinned `PacketParser.swift:266-286` exposes a four-byte ACK plus optional
UInt32 RTT, without installation, lease, claim or origin epoch. The inspected
parser/event/tests match the approved source pin; parser blob is
`67415b52f2657edfe192e3f08ed301d3165bfac1` and ACK test blob is
`57b98cd69a8a3997e628f6ef362f39d6dd08a52f`. Current Android AckCodeBuilder
hashes timestamp, masked attempt, text and sender key to four bytes, not a
fresh-install nonce. New DB identity therefore cannot prove physical-radio
prehistory clean. The service owner additionally reports an exact external
firmware counterexample with ACK slots surviving a client switch; that is
supplemental source evidence, not a source-pin advance or hardware/all-version
verification performed here.

No evidence-backed reset/drain/exclusive-origin boundary is established.
Conservatively treating every externally unproven radio as UNKNOWN could deny
all new direct-ACK confirmations on every affected ordinary install for an
unbounded period, while sends/persistence/navigation remain available.
Observed collision frequency has not been measured. The coordinator explicitly
initially held that fresh/external-radio trust policy for a product decision.
Afterward, with the user unavailable and autonomous continuation directed,
the coordinator explicitly made a **provisional coordinator decision, not a
user-selected option**: use the strict boundary for both fresh and upgraded
installations unless genuine evidence establishes otherwise. This successor
records that explicit decision rather than silently treating a new DB as a
trusted baseline. Sending remains available; new ACK confirmations may remain
unavailable indefinitely. Preserve genuine DELIVERED and proven historical
replay precedence. No speculative TTL/connection reset/fake certificate or
authority reconstruction resolves the uncertainty.

An evidence-backed path to normal confirmation remains separately reviewed
follow-up work. Neither local journal completeness nor this contract
establishes such a path. There is no clean-baseline certificate or public
clearing operation in these declarations. Storage must implement the
provisional durable UNKNOWN policy for fresh and upgraded state separately;
this source-only handoff does not claim that it already did so.

### Timeline identity binding

A standalone message snapshot does not atomically bind existing timeline
DTOs, which lack the persisted diagnostic identity. The UI owner selected a
conservative identity-independent unconfirmed label for outgoing FAILED DMs
and an aggregate SOME-messages warning, not attribution of old snapshot
reasons to a potentially identical recreated DTO. Row-specific diagnostics
remain unavailable until a genuine atomic timeline row/identity projection
exists. Storage/UI owners were asked for a real identical-key/identical-DTO
delete/recreate and delayed-response rejection control; value equality tests
are not that race evidence. No timeline-binding API or UI integration is
claimed here.

## Tracked-wire cross-table consistency clarification

Coordinator review of storage candidate 751 identified a reachable public-API
case: generic `recordDirectSendAccepted` may bind an actual code on a tracked
registered wire without any physical marker. If guarded failure considers
only accepted physical-emission rows, it can misclassify that target as never
invoked and bypass foreign uncertainty. A retained wire actual versus only
UNSENT physical rows creates the same no-write-proof contradiction.

The failure capability now explicitly requires reconciliation of registered
wire acceptance and complete physical ancestry before the known-safe
exception. Neither accepted-without-marker nor accepted-versus-only-UNSENT is
proof that the target never emitted. Preserve generic acceptance behavior;
do not manufacture a physical marker, reclassify tracked provenance as legacy,
ignore the accepted binding or repair rows during a read.

This is not a blanket rejection of mixed outcomes on a logical wire. Separate
genuine accepted and definitely-unsent invocations sharing one wire are valid;
an unsent retry neither erases nor contradicts an earlier accepted physical
emission. Raw field decoding and semantic cross-table assessment must remain
distinct, with existing typed failure paths preserved.

This KDoc/evidence-only clarification changes no declarations, result enums,
classifier implementation, schema, generic API or test assertions. Actual
public-API Room controls for generic acceptance without a marker, generic
acceptance after UNSENT, foreign MAY/known-code collision, and the valid
accepted-plus-unsent-retry case belong to storage. They have been requested;
no execution or producer integration is claimed by this clarification.

Storage confirmed the precise consistency rule: a valid tracked wire accepted
binding needs any valid physical ACCEPTED row for the same wire and actual
code. No marker, only UNSENT/MAY rows, or only mismatched physical actual codes
are inconsistent. A matching genuine physical resolution can subsequently
establish consistency without repair or an automatically fabricated marker.

New failure and full message diagnostics use corresponding target/foreign
conflicting reasons. Full radio confirmation uses
`CONFLICTING_PHYSICAL_EVIDENCE`. Strict settlement uses
`TARGET_ACCEPTANCE_UNRESOLVED` even if another target wire has a valid accepted
emission, or `UNRESOLVED_OTHER_CLAIM_EMISSION` for a foreign inconsistent wire.
The bounded diagnostic DTO cannot truthfully encode a tracked markerless
accepted binding: it must surface existing typed invalid-data/unavailable
failure instead of dropping the row or inventing a legacy/physical emission.
Structurally malformed physical fields retain their existing typed read/
settlement failure behavior, distinct from valid-but-inconsistent cross-table
values. No policy permits a generic API change.

### Legitimate saturation-covered acceptance exception

Coordinator follow-up identified a necessary exception to the preceding
unsaturated consistency rule. `CoveredBySaturation` intentionally permits
physical dispatch without a per-emission row/handle, after which the service
may legitimately record generic wire acceptance. On a radio already sticky
saturated, an unmatched tracked-wire actual is therefore not automatically
corrupt or conflicting. Earlier UNSENT/MAY/mismatched physical rows do not
rule out that additional saturation-covered invocation.

Bounded diagnostics retain SATURATED coverage and actual recorded physical
rows rather than throwing InvalidData solely for the unmatched wire actual.
Full radio/message diagnostics retain the saturation reason, without adding
a conflict solely for that gap. Structural malformed data and independent
genuine conflicts still keep their prior semantics. New settlement and
failure remain blocked by saturation; delivery and proven historical replay
retain precedence. No marker, handle, legacy row or zero-write proof is
fabricated. Unsaturated missing-match behavior remains as reviewed above.

Storage/service owners were asked for a real public-path
CoveredBySaturation -> physical send -> generic acceptance -> diagnostic
control, including earlier UNSENT/MAY/mismatched rows. This successor changes
KDoc/evidence only; declarations, classifier, generic behavior and existing
test assertions are unchanged. No execution of those controls is claimed.

### Overwritten generic acceptance is not resolved evidence

Storage found a further public-path counterexample: tracked begin, generic
Accepted(999) without a marker, then a physical marker resolved Accepted(888).
The physical resolver can overwrite latest wire acceptance from 999 to 888;
comparing only that latest value against physical rows falsely appears
consistent and loses the earlier 999 entirely. A read-side comparison cannot
recover evidence that was never retained.

The strict contract therefore requires immutable retention of every
successfully committed generic accepted actual on a tracked wire, atomically
with that acceptance. Every retained binding participates in strict
consistency and diagnostic code comparison. Later generic or physical
acceptance cannot erase an earlier unmatched actual. A genuine matching
physical acceptance can reconcile its exact code while retaining the
historical binding; it cannot clear other unmatched codes or renew authority.
The sticky-saturation exception remains: a missing physical match alone can
be legitimate there, but new settlement and failure remain blocked.

Generic public eligibility, results and receipt shape must remain unchanged.
An internal evidence record is not a fabricated wire, attempt, ACK or
delivery tuple. The storage owner proposed an immutable internal acceptance
journal; implementation mechanism, support admission and producer edits
remain with storage/coordinator, not this contract worktree. No internal
journal implementation is claimed by this KDoc-only requirement.

Requested real controls include generic 999 -> physical 888 -> file reopen
retaining unmatched 999; generic 999 -> physical 999 matching without erasing
history; multiple retained bindings with only one matched; acceptance
commit-then-throw and exact replay; foreign known-code comparison; and
legitimate saturation-covered acceptance. These controls are not replaced by
DTO/value reconstruction or the 38 existing unexecuted tests. No execution,
compilation, migration, schema or public generic behavior change occurred
here.

The coordinator explicitly approved this bounded contract clarification.
Nonpayload acceptance binding evidence must survive product deletion, lease
retirement and file reopen while existing payload-erasure rules remain.
Denied acceptance must not create an accepted fact; replay must preserve the
original operation semantics without regranting permission. Genuine delivery,
proven historical replay and the sticky-saturation exception keep precedence.
No missing historical binding may be manufactured during migration or read.

### Independent acceptance-history birth provenance

The admitted storage repair identified a necessary limit on retrospective
coverage: an older emission-tracked wire may already have lost a generic
actual before immutable acceptance retention existed. An empty new journal
or a latest wire actual matching a physical row cannot prove that no earlier
unmatched actual was overwritten.

New tracked initial/retry wire creation therefore atomically records a
separate immutable acceptance-history-required birth provenance alongside
the unchanged emission-tracking provenance. Strict completeness requires
both. This is retention coverage from birth, not a message no-dispatch
certificate, authenticated public token or proof of external radio history.
Existing wires must not acquire it by backfill, replay, generic acceptance,
physical resolution or DTO equality. Original replay/denied semantics remain;
an older tracked wire without this marker stays legacy/unproven even when
its current actual matches a valid physical row.

Internal birth markers and immutable accepted-code entries must remain
excluded from public receipt/bookkeeping projections. Generic API/results/
eligibility, payload erasure, delivered/proven replay precedence and the
legitimate sticky-saturation gap exception are unchanged. No missing
historical actual may be manufactured. No public method signature or schema
version is selected or changed in this contract clarification.

Storage owns the actual marker/journal mechanism and controls for new-wire
atomic creation, rollback/dedup, denied/replayed acceptance, older wire without
retention provenance, overwrite, deletion and file reopen. These remain
separate producer evidence; no executable validation is claimed here.

## Historical commit replay versus safety-policy provenance assessment

Coordinator requested assessment of older RADIO_DELIVERY operations that
may predate physical-prehistory UNKNOWN and complete generic-acceptance
retention. Candidate persistence on actual devices must not be assumed
impossible. The current public `DirectAckReceipt` and generic AlreadyApplied
semantics distinguish historical committed state from current mutation
authority, but carry no exclusion-policy version or proof-coverage identity.
`DirectAckRadioSettlementReceipt.AlreadyApplied` wraps that same receipt.
An operation's existence alone therefore does not identify which stricter
history policy was applied when it committed.

Three separate propositions must not be conflated: the historical operation
committed; the product currently has DELIVERED status; the original exclusion
met the present safety policy. Exact tuple replay can establish the first
without establishing the third. Preserve actual product DELIVERED and
ordinary generic idempotency; neither missing newer proof provenance nor a
later diagnostic UNKNOWN authorizes downgrading or rewriting product state.
Historical replay must not silently manufacture a current-policy certificate.

The actionable investigation is with the storage owner: identify the actual
old RADIO_DELIVERY operation fields and replay precedence, then construct a
persisted old-policy counterexample and reopen under the stricter producer.
Possible older prehistory or overwritten-acceptance cases are investigation
targets, not executed counterexamples or verified old-candidate behavior
claimed here. Determine whether an explicit proof-policy provenance/UNKNOWN
classification is necessary after that evidence; do not infer a version from
timestamps, the latest wire, current build or an empty new journal.

No public result, version field, diagnostic reason, replay behavior or
DELIVERED semantics is changed by this assessment. Actual old-proof source
and producer controls remain pending; this is not a declaration that all
historical RADIO_DELIVERY operations are safety-proven or invalid.

### Actual producer proof fields and supported replay boundary

Storage owner subsequently reported the actual persisted proof shape:
RADIO_DELIVERY uses a fresh unused operation UUID, kind literal, ACK-code
argument, nullable RTT maximum and null pending ID; the delivery row carries
code/RTT, claim/message identity and own DTO snapshot subject to erasure.
Neither record carries an exclusion-policy version, prehistory qualification
or acceptance-birth coverage. The older source gap permits a preauthority
old ACK -> new accepted K -> committed RADIO_DELIVERY sequence. This is
owner-reported source evidence, not an executed device/migration counterexample
or an assumption that older candidates never persisted.

Consequently strict AlreadyApplied is explicitly historical commit/idempotency,
not proof that an older exclusion satisfied today's policy. Preserve
DELIVERED rows, actual receipt permission, payload erasure and generic public
semantics. Existing always-unproven message diagnostics and independently
observed UNKNOWN remain diagnostic; do not silently certify the old decision
from current metadata or downgrade product status. No speculative public
proof-version/result change is required for this bounded clarification.

There is an important actual reopen limit: DirectAckAttempt is opaque and
issuer-context bound. A real new store context rejects the old attempt as
INVALID_HANDLE; inspection never reissues or reconstructs it. Persisted
delivery survives, but this is not support for invoking strict settlement
with a reconstructed historical attempt after restart. Same-context exact
replay remains read-only, with actual permission and relevant tuple/proof
mismatch rejected as CONFLICT, never a new strict proof or mutation.

Storage is authoring public Room controls for historical commit followed by
foreign malformed evidence, exact read-only replay, conflicting tuple and
relevant proof corruption. These and an actual reopen/old-handle rejection
control remain separately owned and unexecuted here. This successor changes
only contract KDoc/evidence; no declarations, replay implementation, public
version, schema, generic behavior or test expectations are changed.
