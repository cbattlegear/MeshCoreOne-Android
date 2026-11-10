# ADR 006: Automatic capability reservations

**Status:** Superseded by [ADR-007](007-maintainer-directed-process-teardown.md), which removed reservations,
capability admission and the repository tooling this ADR described. Kept as history.

*Original status: accepted policy amendment; repository tooling activation was deferred to WP-003.*

## Context

WP assignment and user authorization were incorrectly implemented as mutable per-file permission prose.
That caused already-authorized WP-218 and WP-302 work to stop when implementation discovered App support,
traceability validators, annotations, fixtures, and dependency-verification checksums. The installed helper
also exposes only whole-WP `claim`/`release` operations: it cannot add an already-owned same-path capability
or represent a disjoint semantic edit. Requiring a new receipt, blob signature, or authorization qualifier
for those discoveries would repeat the defect.

Reservations are concurrency bookkeeping. They are not a second grant of permission.

## Decision

### Assignment and admission

A user's assignment of a WP/session authorizes ordinary implementation, tests, evidence, generated outputs,
and directly necessary support edits that a trusted rule maps to a manifest-declared capability. It does not
authorize unrelated product scope, repository administration, fleet activation, or protected human gates.

The worker or coordinator automatically acquires the initial reservation before editing. When an authorized
implementation discovers another path, operation, or generated output, it automatically evolves the same
owner's reservation when a trusted `path + operation -> capability` rule matches. The worker does not stop
to ask the user for a receipt, authorization rewrite, qualifier, blob signature, checksum tuple, or fixture
name. A missing helper operation is recorded as a tooling collision and coordinated; it is not replaced by
an approval ceremony.

Unknown paths or operations, and operations outside every assigned capability, fail closed with one blocker
that identifies the unmatched path, operation, WP, and capability rule needed. They do not become authorized
merely because they are mentioned in a PR.

### Typed shared capabilities

Shared mutable surfaces use stable capability IDs defined in `docs/android/automation-policy.json`.
Definitions describe allowed operations, invariants, required validation, and coordination behavior rather
than future byte values. The initial capability types cover:

- dependency catalogs, locks, and verification/checksum metadata;
- App build, manifest, launcher, and composition support;
- traceability validators and their mappings/tests;
- generated resources and their generators;
- persistence schemas and migrations; and
- trusted workflows and their validation.

Ordinary WP-owned paths retain their manifest capability. A support path is admitted only through one of
those trusted rules. Capability definitions may permit adding newly resolved POM checksums or generated
entries without enumerating those bytes in authorization prose, while still requiring the appropriate
build, integrity, provenance, or migration checks.

### Reconciliation and escalation

For a supported shared surface, disjoint semantic edits may proceed through an automatic transaction or
deterministic three-way merge. Actual overlapping semantic edits serialize automatically or transfer to the
current producer; identities and evidence stay attached to the originating WP. Stale compare-and-swap state
fails closed and is retried from the current base. Existing active WP-218 and WP-302 reservations continue;
this amendment neither pauses them nor forces path surrender.

Escalation is limited to:

- an unresolved semantic ownership collision after automatic serialize/transfer/reconcile;
- source, manifest, or policy drift;
- a protected human, license, hardware, signing, or release gate;
- missing authentication or tool capability; or
- a substantive product decision.

A newly discovered checksum, path, annotation, generated output, validator mapping, or test fixture is not
itself an escalation reason.

### Authorization, receipts, and evidence

Authorization prose is immutable audit context. It is not executable capability state and is not rewritten
when a reservation evolves. A reservation receipt binds the repository, WP, actual task/session/PR identity,
base/source/manifest/policy revisions, capability IDs, operations, and actual normalized paths. Implementation
evidence binds resulting bytes, generated provenance, commands, tests, and outcomes.

Protected-path approval remains a merge-to-main gate. Exact-head local validation before push, serialized
current-base merge validation, one WP/branch/PR, the pinned Swift source, real parity tests, and the prohibition
on bypasses, release/recreate, and manual SQLite edits remain unchanged.

## Migration and compatibility

WP-003 must introduce a versioned capability-reservation record alongside current receipts:

1. Preserve each historical authorization string byte-for-byte as immutable audit context.
2. Add capability state transactionally without releasing or recreating the current reservation.
3. Preserve repository, WP, attempt, backend, task/session/issue/PR identities, source/manifest/policy
   bindings, repair count, and current execution state.
4. Derive only capability IDs, operations, normalized actual paths, and a migration revision from trusted
   rules; never infer new product authorization from free-form prose.
5. Use compare-and-swap against the complete prior record. A stale write leaves the old record intact and
   returns one actionable reconciliation result.
6. Keep legacy readers functional during the transition, and make repeated migration idempotent.
7. Do not require release/recreate, worker relaunch, path surrender, or manual ledger editing.

Installation of this policy is not activation. Dispatch stays paused; no schedule, repository setting,
credential, fleet, or active WP is changed by this amendment.

## WP-003 measurable acceptance

The stacked tooling layer must add deterministic tests that prove:

1. **App3 transfer:** an App support edit transfers to the current producer automatically while preserving
   the originating WP identity and without a second authorization request.
2. **Validator2 handoff:** a validator mapping/test handoff retains both producers' evidence and does not
   turn the validator path into a permission gate.
3. **Annotation plus 37-POM evolution:** one annotation and 37 newly resolved POM checksum entries evolve
   the same reservation under the dependency-metadata capability without exact-byte authorization prose.
4. **Same-path disjoint merge:** two semantic edits to one supported file merge deterministically, retain
   both attributions, and run the capability's required validation.
5. **Stale CAS:** a stale capability update changes no state and returns a current-revision retry result.
6. **Third-party/controller collision:** an actual external or controller owner collision serializes or
   transfers to the current producer; an unresolved collision emits the single allowed blocker.
7. **Protected path:** local work and evidence proceed, but merge to `main` remains blocked until the real
   protected-path approval is bound to the exact head/current base.
8. **Wrong capability:** an unmatched path or disallowed operation fails closed with the WP, path,
   operation, and expected rule in one blocker.
9. **Identity compatibility:** migrating a current receipt changes none of its actual identities, bindings,
   authorization text, execution state, or repair count; a second migration is a no-op.
10. **No regression of gates:** pinned source, declared tests, exact-head/current-base checks, hardware,
    license, signing, and human gates remain enforced.

These are tooling acceptance requirements, not claims that the current WP-000 policy branch implements them.
