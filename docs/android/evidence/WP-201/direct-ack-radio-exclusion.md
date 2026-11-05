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

This correction supersedes `5db00defc6c7ac1e6c22237edd1d48b8d99beb35` because
its total-marker cap lost normal-history selectivity. Hosted run `42077798177`
targets that superseded SHA and is not verification of this corrected source.

## Contract boundary

`DirectAckRadioExclusionAuthority` is optional and additive. It does not replace
`DirectAckAuthority`, alter its old settlement result, or silently change its
prepared/legacy success controls. Consumers must surface a missing capability;
they must not fall back to `settleDirectAck`.

The optional API declares:

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

The service owner confirmed that receipt/tracker delivery is completed only
after `Settled`; `Nonsettling` discards only the exact pending ACK candidate
and leaves the logical claim waiting. A successful send response, late evidence
refinement, or raw ACK retained only in RAM cannot synthesize or publish
recipient delivery.

For the target logical claim, any matching accepted actual code from that
claim is eligible, including multiple physical emissions that reused one wire
ID; an earlier accepted emission remains eligible across a prepared or
differently accepted retry. For every other claim on that radio, each physical
emission contributes its predicted and accepted codes as known competitors.
An unresolved `MAY_HAVE_WRITTEN`, `LEGACY_UNKNOWN`, or conflict is wildcard
ambiguity. `DEFINITELY_UNSENT` excludes only that exact physical emission.

The authoritative per-emission journal has no total-row cap, TTL or eviction:
exact emission identities, replay/conflict evidence, and unresolved facts are
retained. The 4,096-row bound applies only to diagnostic samples;
`TRUNCATED` means the sample omitted history and never implies completeness.
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

## Validation and limits

Thirteen new pure classifier controls cover target accepted-code matching,
same-claim prior wires, both foreign predicted/actual code memberships,
unknown and conflicting wildcards, definite non-emission, saturation,
truncation, radio partitioning, reused wire IDs across distinct physical
emissions (including different actual codes on foreign emissions sharing one
wire ID), and a definite-unsent retry that cannot clear an earlier
possible-emission marker. They specify only the diagnostic candidate;
they are not evidence of transactional storage enforcement, real service
routing, radio hardware behavior, or durable ACK ingress.

The wire ACK has no session, graph, claim, or physical-emission origin field.
Therefore identical actual codes across distinct claims, unresolved possible
emissions, and sticky saturation are fundamentally nonsettling; the contract
preserves safety rather than inventing origin or expiry.

No local Gradle/Kotlin build or tests were run because of the explicit local
Gradle owner hold. Source-only `git diff --check` passed and 13 test annotations were
present in the contract test file; neither check establishes compilation or
test execution. Hosted exact-candidate compilation/test discovery is pending.
Physical radio testing and a real Room implementation of the optional
capability are not claimed.
