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
An accepted response updates the same evidence through the transaction that
resolves its exact emission handle. The legacy
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
the command bytes were written and remains `MAY_HAVE_WRITTEN`.

The service owner confirmed that receipt/tracker delivery is completed only
after `Settled`; `Nonsettling` discards only the exact pending ACK candidate
and leaves the logical claim waiting. A successful send response, late evidence
refinement, or raw ACK retained only in RAM cannot synthesize or publish
recipient delivery.

For the target logical claim, only a matching accepted actual code is eligible;
an earlier accepted wire of the same claim remains eligible across a prepared
or differently accepted retry. For every other claim on that radio, both its
predicted and accepted codes remain known competitors. An unresolved
`MAY_HAVE_WRITTEN`, `LEGACY_UNKNOWN`, or conflict is wildcard ambiguity.
`DEFINITELY_UNSENT` excludes only that exact wire.

The contract fixes the agreed bounds at 4,096 detailed markers and 4,096
diagnostic sample rows per radio. When marker capacity is reached, storage
commits sticky radio-wide saturation and returns `CoveredBySaturation` without
inventing a per-wire handle. Sending can continue under that persisted
wildcard, but settlement remains nonsettling indefinitely. Existing wire and
marker evidence is not deleted or expired, and saturation is never reset by
reconnect, lease replacement, or process restart. A truncated diagnostic
sample is explicitly distinct from persisted saturation and is never a clean
history proof.

The API does not durably ingest a raw ACK that was observed only in RAM and
does not promise to replay such an ACK after process death. The service owner
must buffer unresolved pre-response ACKs in process and reconcile only after
the matching actual code is persisted.

## Validation and limits

Twelve new pure classifier controls cover target accepted-code matching,
same-claim prior wires, both foreign predicted/actual code memberships,
unknown and conflicting wildcards, definite non-emission, saturation,
truncation, radio partitioning, reused wire IDs across distinct physical
emissions, and a definite-unsent retry that cannot clear an earlier
possible-emission marker. They specify only the diagnostic candidate;
they are not evidence of transactional storage enforcement, real service
routing, radio hardware behavior, or durable ACK ingress.

The wire ACK has no session, graph, claim, or physical-emission origin field.
Therefore identical actual codes across distinct claims, unresolved possible
emissions, and sticky saturation are fundamentally nonsettling; the contract
preserves safety rather than inventing origin or expiry.

No local Gradle/Kotlin build or tests were run due the explicit memory
constraint. Hosted exact-candidate compilation/test discovery is pending.
Physical radio testing and a real Room implementation of the optional
capability are not claimed.
