# Nonactivated loss-aware notification enrollment

<!-- android-port-wp:WP-303 -->

This is a new bounded coordinator assignment, not reopening the accepted
implementation in PR #475 or issue #399. Base:
`41552c85b659396111cee6b0e0f70c404ac5a8ee`; reference remains
`db14559b39d32322b06477c6ae676112f583db50`; actual session:
`be9fa0fd-35b2-4e78-bffc-917adc273f29`.

Current trusted manifest SHA-256:
`11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`;
policy revision:
`de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.
The supported current-base claim refused its historical binding. Current
`render.py` explicitly makes a historical advisory mismatch nonblocking;
ADR 006 distinguishes assignment/admission from concurrency bookkeeping.
Supported same-owner scope evolution reached revision 18, preserving the
historical identity, base, policy and authorization bytes. No receipt rebind,
terminal-worker claim, private ledger edit, or release/recreate occurred.

## Implemented boundary

`NotificationEnrollmentCoordinator` requires explicit injected witness and
journal ports; it has no production defaults and is not constructed by
`MeshCoreApplication` or `AppContainer`. The coordinator's decision is to support
an explicitly acknowledged NEW action generation, not to assert the user chose
that policy or that an unknown installation was never provisioned.

The witness adapter must authenticate an actual foreground acknowledgment of
lost/unknown continuity, bound to the exact operation and prior observation.
It must durably retain AUTHORIZED before initial creation, the exact PREPARING
receipt before journal binding, and the exact BOUND receipt afterward. Every
witness mutation is conditional on the complete current record. Receipt checks
include operation ID, store ID, creation nonce, phase, revision and witness ID.
Operation ID, store ID and creation nonce are a REQUIRED predeclared triple
independently retained in AUTHORIZED before any preparation. The nonce is not
discovered from the journal, even after a lost first return. Receipts must repeat
that exact nonce; witness revisions are exactly AUTHORIZED 1, ACCEPTED 2, BOUND 3.

Only the uninterrupted return from a **newly** retained foreground authorization
may call `prepare` once. Idempotent acknowledgment replay and restart use
read-only same-operation reconciliation. If no journal survives, that may be
lost committed storage, not proof creation never happened: recovery blocks.
Even a crash immediately before first allocation deliberately requires a later
explicitly acknowledged new operation rather than automatic recreation.
The predeclared nonce is always retained and checked. Cancellation propagates; uncertain
returns never cause the coordinator to mint another operation or store.

`BoundSnapshot` is an observation, **not an issuance or dispatch capability**.
No sends, queue insertion, PendingIntent creation, bootstrap, token acceptance,
expiration, record eviction, or old-store modifications occur here. A later
consumer still needs real transactional current-generation fencing and bound
journal validation; a final witness read is not a substitute for that authority.

## Coordinated storage and recovery dependencies

Storage owner 637 froze the separate, unactivated candidate
`be849bdd90a7dc6e42c93a5f40f9db1f831a34f6` (successor of
`e3f3c451fcb294aae68457e949677ac4a1cfa9b2`).
Its `NotificationReplyEnrollmentStore` models predeclared operation/store IDs,
PREPARING revision 1, BOUND revision 2, immutable creation nonce and the exact
accepted witness ID. The repaired API requires the independently retained
operation/store/nonce triple on prepare AND reconciliation, rejecting same
operation/store replacement with a different nonce. Its real journal and
enrollment checks share schema 4.
Existing empty/partial/foreign paths are not recreated. This candidate is not
imported, compiled or activated by this change; local coordinator DTOs are
explicit adapter ports, not a claim that the storage integration is complete.

Unknown issued-token admission in the earlier journal rejects before text
retention. Receiver owner 2e confirmed that queue acceptance is process-only,
not durability. Storage owner 637 therefore owns separate copy-only unbound
text retention: exact UTF-16, stable submission identity, no post, destination,
outgoing ID, claim or dispatch surface, and explicit durable/not-retained/
indeterminate results. Surviving old journal text remains unbound; physically
lost bytes cannot be reconstructed. Receiver and visible recovery UI adapters
must neither auto-send/reassociate that text nor retire an unretained action.
No such adapters are activated by this candidate.

## Platform limit and activation prerequisites

Android backup exclusion and a surviving authenticated Keystore binding can
support a stated positive-continuity model, but missing database/marker/key
cannot distinguish first use from cleared or lost state. A UUID or authenticated
old snapshot is not monotonic antirollback evidence:

- <https://developer.android.com/identity/data/autobackup>
- <https://developer.android.com/training/data-storage/app-specific>
- <https://developer.android.com/privacy-and-security/keystore>

The actual authenticated durable witness adapter, real foreground acknowledgment
control, storage mapping, current-generation action fencing, and visible
not-retained/uncertain text recovery remain activation prerequisites. There is
no honest guarantee that raw reply input survives failed persistence plus process
death. A receiver cannot promise an automatic foreground activity launch:
<https://developer.android.com/guide/components/activities/secure-bal>.
No runtime, protected setting, TTL, capacity budget, or expiry policy is selected.
Frozen #495/#518 and other producers' source remain untouched.

## Verification

`NotificationEnrollmentCoordinatorTest` declares 26 tests using deterministic
port doubles, not real SQLite, Keystore or Android process-death evidence. Cases
include acknowledgment ordering, unknown/lost state, explicit failures, partial
startup, receipt substitutions, superseding witnesses, lost returns and late
cancellation at every write, same-operation restart, and refusal to recreate
missing storage after possible preparation.

Predecessor `740033157da71b1bba1d9c983761c16948caf061` passed hosted run
[41967981159](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41967981159).
Independent source review still found that nonce-less AUTHORIZED reconciliation
could accept replacement storage before retaining the first receipt. This
successor requires the predeclared nonce and adds lost-prepare-return/restart/
replacement controls; the earlier passing run does not verify the successor or
disprove that gap. The completed predecessor run was preserved before publishing
the repair; fresh exact-head execution is required.

- `python tools/android-port/controller/validate.py`: passed.
- `python tools/android-port/portmap.py`: passed.
- `git diff --check`: passed.
- Documented native Windows `ci.py preflight --local --state
  $env:ANDROID_CI_STATE --output <fresh absolute directory>`: **BLOCKED** before
  test execution. The pinned Java `-version` process could not reserve memory
  (paging-file exhaustion, errno 1455). No test result or failure is inferred.

The declared `:app:testDebugUnitTest` is selected through the ordinary candidate
CI. The exact candidate hosted job/log is authoritative; this document does not
retain a second reproducible result bundle or claim unexecuted tests passed.
No hardware, iOS, native Keystore durability, crash-filesystem or UI verification
is claimed.
