# WP-204 nonactivated notification enrollment witness

## Identity and scope

Repository: `cbattlegear/MeshCoreOne-Android`.
Producer session: `a830aafb-8aa1-4bf3-8692-c5bf1c017e70`.
Branch: `cbattlegear-notification-enrollment-witness`.
Actual branch base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
Candidate identity is the containing Git commit, not the historical merged #537.
Frozen Swift reference remains `db14559b39d32322b06477c6ae676112f583db50`.
Manifest digest at admission:
`11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`;
policy revision:
`de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.

The coordinator assigned an independent Android-backed durable witness, not
foreground UI, live routing, journal integration or notification activation.
The old merged receipt branch/ref is preserved. No #495/#518 changes or edits to
other producers' coordinator/storage files are included.

Inspected source references:

- Coordinator #571 at `740033157da71b1bba1d9c983761c16948caf061`.
  Its nullable-nonce API is not adopted; its owner is evolving the successor.
- Storage at `be849bdd90a7dc6e42c93a5f40f9db1f831a34f6`: predeclared
  `(operationId, storeId, creationNonce)`, PREPARING revision 1,
  BOUND revision 2 and exact witness acceptance.

Independent DTOs avoid a datastore dependency on app or a producer's unpublished
types. A later adapter must map every identity, nonce, revision, receipt, outcome
and CAS field, never inspect-and-adopt a replacement nonce.

## Admissions

Trusted `reserve.py admit` returned `wp-owned/create` for the isolated production
and test enrollment paths under `android/core/datastore/src`. The evidence path
is `wp-owned/record-evidence`; appending the bounded native adaptation to
`docs/android/deviations/WP-204.md` is `wp-owned/modify`.
Typed transfers bind the coordinator assignment to this producer. They reported
`advisory: true`, `file_edit_blocked: false`. The historical DATA-20 WP-204
reservation was not rewritten, released or recreated; its declared edits are
disjoint from this enrollment implementation.

## Implemented behavior, not activation

The default platform implementation uses application `noBackupFilesDir`,
Android SQLite exclusive transactions, DELETE journal/FULL synchronous mode,
existing-file and directory synchronization, and nonexportable AndroidKeystore
HMAC-SHA256 keys. Occupied aliases fail; existing-key authentication never
generates a key. Database observation never allocates a missing file. Framework
locale metadata initialization and destructive default corruption recovery are
disabled. On API31, coherent observation uses an existing-file read/write
connection with a transaction containing SELECTs only, not a purported
read-only framework transaction.

An append-only authenticated history and authenticated current head retain all
generations and transition rows without expiry/capacity policy. Each generation
binds its operation triple, stable witness ID, acknowledgment ID, exact original
expected observation and revision. Full-record CAS is checked inside the native
transaction, not a process-local mutex.

AUTHORIZED revision 1 retains the operation before journal prepare. ACCEPTED
revision 2 retains the exact PREPARING journal receipt before bind. BOUND revision
3 retains the exact BOUND journal receipt and same nonce/witness ID. Stored
outcomes require authenticated positive readback. A possible write with lost
acknowledgment is Indeterminate. Existing authorization never returns fresh
prepare permission; callers must reconcile the original triple. No Ready or
dispatch-authority API is provided.

The foreground verifier is a **mandatory injected trust boundary with no
production implementation**. It must verify the actual versioned loss-warning
acknowledgment and independently durably consume it before an uninterrupted
NewlyVerified return. AlreadyConsumed, Required, Rejected and Indeterminate do
not allocate a witness. A UUID, cached grant, Boolean or biometric success alone
cannot satisfy this boundary. Test verifiers are explicitly doubles, not proof
that this missing product interaction exists.

Missing DB/key state is unknown-or-lost, never first-install evidence. Surviving
corrupt/partial archives are blocked, not replaced. New authorization after
whole-DB loss requires a genuinely new foreground acknowledgment and preserves
orphaned old keys. There is deliberately no automatic recovery of a surviving
corrupt archive.

**No same-device snapshot antirollback:** a previously authenticated complete
DB snapshot with its surviving keys can be restored undetectably. No UUID,
digest, HMAC chain, positive readback or no-backup directory establishes a
hardware monotonic epoch. This lane cannot promise general restored-state
detection, OS text survival, current-generation ingress fencing or readiness.

## Verification state

At initial authoring, no Kotlin compilation or test execution is claimed.
The two test classes contain 25 native-SQLite methods at SDK31/37 and three
framework-lock/key-specification methods at SDK31/32/33/37. Tests use explicit
test-only JCE keys; even successful Robolectric execution is not AndroidKeystore
device, reboot, process-death, power-loss or restore-device evidence.

The documented verification-venv interpreter was absent. The installed pinned
Python 3.12.4 ran:

`python tools/android-port/controller/ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute output>`

Readiness failed before product tests: pinned Java `-version` exited 1.
The JVM reported insufficient native memory while mapping its G1 virtual space;
this is a host-readiness failure, not a Kotlin test result.
The retained local Gradle execution owner was not bypassed, cleared or disturbed.
An affected invocation combining `--worktree` with `--affected-base` was rejected
as an unsupported combination before execution; it is not a test failure.

Actual final static/hosted outcomes must be reported with the exact candidate.
An exact-commit hosted run/log is authoritative; do not retain a second CI-result
bundle here.

Prepublication static commands passed: `python tools/android-port/controller/validate.py`,
`python tools/android-port/portmap.py`, and `git diff --check`.
These validate manifest/traceability/formatting, not compilation or assertions.

## Remaining gates

Real foreground acknowledgment producer/verifier, frozen coordinator adapter,
storage integration, current-generation transactional admit fencing, actual
AndroidKeystore/device behavior and independent exact-candidate review remain
separate gates. There is no production wiring, activation, merge or feature
readiness claim.
