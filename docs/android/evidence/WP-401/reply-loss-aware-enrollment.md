# WP-401: distinct loss-aware enrollment and copy-only unknown text

Repository: `cbattlegear/MeshCoreOne-Android`.
Initial implementation parent: `5cc18d54f774134b356f4ae39a2a3ac35d485965`.
Nonce-replay repair parent: `e3f3c451fcb294aae68457e949677ac4a1cfa9b2`.
Clean-main base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
Read-only Swift pin remains `db14559b39d32322b06477c6ae676112f583db50`.
The commit containing this document binds its source and controls.

## Decision, ownership and limits

The coordinator selected the recommended **explicit loss-aware NEW action generation**
while the user was unavailable. This is a coordinator decision, **not a claim that the user
selected or acknowledged enrollment**. Actual foreground acknowledgment and a production
authenticated durable witness remain required before any real transition. This is
nonactivated storage, not a bypass for those requirements or restored continuity.

WP-401 owns `NotificationReplyEnrollmentStore`, its real SQLite tables/tests and journal
binding. be9/WP-303 owns the separate app/container enrollment coordinator and witness ports;
2e owns receiver admission; b204 owns the PendingIntent adapter; aa4 owns manual-copy recovery.
No other owner's source, active diagnostic ref or retained local execution state is changed.
No retention, expiry, automatic deletion, arbitrary default budget, account or backend is added.

## Causal creation and external witness protocol

The new class sits beside `NotificationReplyStagingStore` in the same notifications messaging
package. Public construction takes Context, a predeclared generation UUID and explicit reply,
custody, issuance and copy-text budgets. Its dedicated noBackup path is generation-specific,
not the old `notification-reply-staging.db` path. Internal File/fault constructors support
real SQLite controls, not runtime provisioning shortcuts.

```kotlin
data class EnrollmentOperation(val operationId: UUID, val storeId: UUID, val creationNonce: UUID)
enum class Phase { PREPARING, BOUND }
data class CreationReceipt(
    val operation: EnrollmentOperation,
    val revision: Long,
    val state: Phase,
    val creationNonce: UUID,
    val witnessId: UUID?,
)
data class WitnessAcceptance(
    val operation: EnrollmentOperation,
    val creationNonce: UUID,
    val witnessId: UUID,
)

suspend fun prepare(operation: EnrollmentOperation): EnrollmentResult
suspend fun reconcile(
    operation: EnrollmentOperation, expectedNonce: UUID = operation.creationNonce,
): EnrollmentResult
suspend fun bind(expected: CreationReceipt, acceptance: WitnessAcceptance): EnrollmentResult
fun boundJournal(receipt: CreationReceipt): NotificationReplyStagingStore
```

The agreed **external** ordering is:

1. Capture actual foreground loss warning acknowledgment; durably retain authenticated
   PREPARING witness for the already chosen operation/new store UUID **and creation nonce**
   before storage creation. All three are mandatory independently retained inputs, with no
   generated/default nonce in the storage API.
2. Call prepare at the separate generation path. Validate exact operation/store, revision1
   PREPARING receipt and the already retained exact creation nonce. Persist exact receipt and
   witness acceptance durably before calling bind. Even restart before the first receipt return
   must verify that predeclared nonce; a newly observed nonce is never adoptable.
3. Bind compares exact PREPARING receipt and acceptance in a SQLite transaction. BOUND is
   revision2 with the same operation/store/nonce and exact accepted witness UUID.
4. Validate/reconcile exact BOUND receipt and persist/positively read the external BOUND
   witness before exposing Ready or allowing any issuance/ingress activation.

The storage class accepts a typed **opaque** operation/acceptance; it cannot authenticate
the user or independent witness implementation. UUIDs and local integrity hashes do not
provide authentication or antirollback. Calling these methods without that external protocol
does not manufacture authorization. No production authenticated witness adapter or actual
foreground acknowledgment has been supplied by this storage change.

`prepare` atomically allocates a previously absent path with createNewFile, then commits the
predeclared store UUID, operation, predeclared creation nonce, all mandatory policy, empty real
journal/registry/custody and copy tables in one synchronous-FULL SQLite transaction (schema4).
It does **not** call initializeNew, randomize the supplied store identity or infer freshness
from absence. Matching previously attributed operation returns its receipt; another
operation/store/nonce cannot adopt it. The required predeclared nonce rejects replacement with matching
operation/store but another creation. Positive same-operation inspection is causal local
evidence, not proof of first installation or external consent.

An existing empty, partial, foreign, corrupt or old journal path is never recreated,
migrated, truncated, deleted or treated as fresh. A crash after allocation but before the
creation commit can leave an unattributed empty file: it explicitly blocks automatic
retry/repair rather than inventing a creation receipt. No new-generation retry after uncertain
creation is implemented. A future repair needs its own real authorization and policy.
After any possible prepare invocation, restart must reconcile the retained same operation,
not invoke prepare again on an absent path. A still-AUTHORIZED external witness and missing
DB cannot distinguish a pre-allocation crash from committed creation followed by loss before
its return. Only the uninterrupted original authorized flow may start allocation; missing
recovery evidence blocks. The storage API cannot manufacture that external invocation history.

`EnrollmentResult` exposes Preparing/AlreadyPreparing, Bound/AlreadyBound and Observed with
the exact receipt, NotRetained(reason), or Indeterminate(operation, reason). Reconcile never creates.
Lost successful returns reconcile only the same operation **and the nonce known by that
call**; missing/unreadable/replaced storage remains Indeterminate, with the typed observation
failure retained separately from commit uncertainty. Invalid budgets or an expected nonce
that contradicts the supplied operation remain NotRetained input failures; observing another
operation/nonce on disk is instead Indeterminate(MISMATCH). No observation proves that a prior
creation never committed. Bind replays require
the same accepted witness UUID. Concurrent conflicting acceptances cannot overwrite the first.
All external/test observers execute only after transactions and handles close. Internal
deterministic interruption enums model crash steps without executing caller callbacks under locks.

The local successor corrects the restart assertions to require uncertainty for missing/replaced
storage and adds corrupt/unavailable restart coverage without recreation. This new method
requires fresh exact-candidate execution; earlier 340-case results do not cover it.

Inventory duplicate validation now uses content-aware keys (post, issued token, payload bytes
and exact UTF-16 bytes) instead of scanning all prior rows for each conflict. Explicit caller
capacity has no small production cap: a real SQLite 10,000-row fixture checks complete inventory
and rejects late root/conflict duplicates with valid digests; a separate hash-collision test
preserves distinct content, routing payloads and posts. These two additional methods bring this
local correction candidate to 170 reply methods at API 31/37 plus six baseline cases (346).
Neither prior 340-case evidence nor non-executing source checks verify these additions. The
separate androidTest/JVM source-set exclusion is owned by the build producer.

## Bound journal enforcement and surviving old text

Schema4 colocates the real journal, issuance, custody, enrollment and copy tables, avoiding a
witness-DB-to-journal lookup race. `boundJournal` only constructs a handle; **every journal
transaction** validates the exact BOUND operation/store/nonce/revision/witness receipt.
PREPARING fails ENROLLMENT_NOT_BOUND. Missing, changed or stale receipt fails
ENROLLMENT_MISMATCH. Holding an old handle does not bypass replacement or restored PREPARING
state. The existing codecs, original typed routing, quotas, stable IDs and conservative
custody remain unchanged. No token supplied by an old generation is registered by a new one.

Old v1/v2/v3 stores are not modified or adopted. The separate `readCopyOnly()` journal
projection validates complete surviving storage and returns only
`CopyText(storeId, recordId, sequence, exactText)`; these are observed storage coordinates,
not route, issuance, continuity, draft, claim or dispatch authority. For schema4 it also
includes retained no-route text. No payload, post, outgoing ID or custody transition is
exposed by this projection. An authenticated new generation must deliberately retain a
user-chosen copy through the no-route API; it cannot reinterpret an old token as a new send.
Actual old-generation selection/rejection in the app remains the receiver/coordinator's work.

Joint loss of DB and witness remains **unknown**, never evidence of never-provisioned state.
A restored PREPARING snapshot cannot satisfy an externally retained BOUND receipt. A jointly
restored matching BOUND DB/witness snapshot is not detectable as rollback by these local
receipts; no antirollback, never-before-issued or RF-origin guarantee is claimed.

## Unknown-token text is genuinely separate copy-only storage

Unknown issued tokens continue to fail `admitIssued` before staging; no fake post/recipient
identity is introduced. The new surface explicitly retains raw text without any route:

```kotlin
data class UnboundText(
    val storeId: UUID,
    val sequence: Long,
    val recordId: UUID,
    val submissionId: UUID,
    val exactText: String,
)
suspend fun retainUnbound(
    expected: CreationReceipt, submissionId: UUID, exactText: String,
): TextResult
suspend fun readUnbound(expected: CreationReceipt): TextInventory
```

TextResult is Durable(record), AlreadyRetained(record), NotRetained(reason) or
Indeterminate(submissionId, proposedRecordId). TextInventory is Retained(records) or
Failed(reason). Exact externally supplied BOUND receipt is checked in the text transaction.
Exact stable submission ID + exact UTF-16 code units reconciles idempotently; different
attempted text under that submission gets a separate unbound row, never overwriting the first.
NUL, isolated surrogates, combining forms and surrounding whitespace remain exact BLOB code
units. These rows have no post, destination, issued token, outgoing ID or claim API.

All counts, sequences, byte totals, canonical IDs, types, column layouts and generation-bound
integrity hashes are validated. No partial inventory or silently ignored write is accepted.
Byte budgets count serialized identities and raw text, not SQLite page/journal overhead.
Count/per-record/total limits are mandatory and persisted separately from reply/custody/
issuance policy. Full/failed/ambiguous storage never evicts or reports success.

**OS notification UI does not guarantee that unretained typed text survives storage failure
or process death.** NotRetained and Indeterminate must be made honestly visible by the
receiver/recovery UI; this change does not implement that UX or guarantee lossless intake
under exhausted/unavailable durable storage. Registry reservation still does not reserve
arbitrarily large future reply/copy capacity. No active ingress, automatic replay/send,
notification retirement, expiry or deletion is wired.

## Authored controls and actual verification

`NotificationReplyEnrollmentTest` authors 31 real-SQLite methods at API31/37, covering:
absent/allocated/foreign/corrupt state; op/store/nonce/witness mismatch; PREPARING/BOUND
restart and concurrent enrollment; actual transaction interruptions at allocation, journal
creation, preparing write, bind write and text write; post-commit lost returns; missing and
replacement stores; restored PREPARING snapshot and joint local loss; journal-handle fencing;
unknown-token versus explicit copy-only retention; exact UTF-16 duplicates/conflicts;
count/per-record/total budgets; concurrent admissions; ignored/aborted inserts/accounting;
old-journal byte preservation/manual-copy projection; malformed rows and policies.

The nonce-bound successor has **112 named journal/enrollment methods** (28 staging, 16 lifecycle,
13 installation-admission, 24 issuance, 31 enrollment) and 224 configured method invocations,
plus six baseline notification tests: 230 total. The exact successor ran in the separately
authorized discovery composition:

- Composition commit: `3fdd4862f8b93159fdf0285fd5327ae6b6bddf17`
- Composition parents: `e381b6fc5ff8f4aee904fcc288190ce004eb9cf3` and
  `be849bdd90a7dc6e42c93a5f40f9db1f831a34f6`
- Composition tree: `a1816c6c82459f2d6128a3dff805020b0d9b1664`
- Unique ref: `diagnostic-wp401-enrollment-nonce-3fdd4862`
- Exact hosted run: [41968603305](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41968603305),
  terminal **success**, all six jobs successful.
- Actual module task: `:platform:notifications:testDebugUnitTest`, observed in the Linux Android job
  without an UP-TO-DATE/FROM-CACHE suffix.
- Notification suite: **230 discovered, 230 run, 230 passed, zero failed/errors/skipped**.
- Per-class JUnit identities: StagingStore 28 base methods/56 execution records; Lifecycle
  16/32; Admission 13/26; Issuance 24/48; Enrollment 31/62; the six unchanged baseline cases
  add six single executions. The platform test report records the two configured method
  executions as plain and `[31]` identities (API37 and API31 respectively).
- `compiled_test_sources` includes the Enrollment test blob
  `9fdff42727121f0eb6b655a11d9a53fb0bd9082a` and the four existing journal test blobs.
  `remapped_jvm_inputs` includes exact approved build blob
  `9c5e1f4f6ed16f68ff629e6768afdd9185d2da61`, all five test blobs, and journal production
  source. The exact production blobs are EnrollmentStore
  `f428495ba5ff608eec26eb72cbcffdd08501b10d` and StagingStore
  `97b2db7706a3023df4036ec75f5879a6f10e5239`. The controller's exact-candidate checks passed.
- Swift codec oracle, controller/workflow policy, SwiftData restore/export and final `android-ci`
  jobs all succeeded. The earlier issuance-only run
  [41966642435](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41966642435)
  remains bound only to its older `e381b6fc` source and is not counted as evidence for enrollment.

This is actual hosted Kotlin compilation and Robolectric/SQLite execution for the source/test
blobs in `be849bdd9`; it is not production installation provisioning, authentic acknowledgment,
hardware/process-kill/power-loss testing, antirollback, reply-capacity guarantee or activated
recovery. No local Gradle, retained-owner recovery, runtime change or active-ref advance occurred.
The bounded local structural verification remains separate:

| Command | Result |
| --- | --- |
| `git diff --check` | Passed |
| `python tools\android-port\controller\validate.py` | First attempt blocked by Git object subprocess failure; one bounded process-only low-memory retry passed |
| `python tools\android-port\portmap.py` | Passed on the same bounded retry |
| `:platform:notifications:testDebugUnitTest` | Not run locally; retained execution-owner/readiness gate preserved |

The bounded retry set only process-local `pack.threads=1` and `pack.windowMemory=16m`;
no repository settings, reference pin, lock, runtime or persisted toolchain state was changed.

## Independent-review nonce replay correction

The initial `e3f3c451f` API generated its nonce during prepare, then allowed existing-path prepare
and reconcile without an expected nonce. A caller could lose its first successful return,
restart with only operation/store, and observe a replacement using those same two IDs but a
different nonce. In-method commit-error reconciliation checked the nonce, but that did not
protect a new process or existing-path call. This was a real source defect, not covered by
the initial authored replacement tests.

The successor makes the creation nonce a **required predeclared EnrollmentOperation field**
that must be independently retained with authorization before any prepare. Every prepare replay
and reconcile compares the exact triple; nullable/nonce-less recovery is removed. An explicitly
supplied reconcile nonce must also equal the operation's retained nonce. Receipt and acceptance
must carry that same nonce. No nonce is generated or adopted in storage.

Two added real-SQLite controls suspend the original caller only after real commit/handle close,
cancel it before any result is returned, then use a new store handle. The prepare regression
first proves exact original-triple reconciliation, replaces the DB with another real journal
sharing operation/store but a different predeclared nonce, and requires both existing-path
prepare and no-extra-argument reconcile to reject without altering bytes. The bind control
proves that a lost return still reconciles the exact pre-retained nonce/witness. These model
post-commit caller death with actual coroutine cancellation and reopened SQLite handles;
they do not claim a physical device/process-kill or power-loss experiment.

The required independent nonce still does not authenticate consent, establish never-before-
provisioned state or detect a jointly restored identical authorized triple/DB/witness snapshot.
Missing post-attempt storage still blocks read-only recovery, rather than authorizing recreation.
