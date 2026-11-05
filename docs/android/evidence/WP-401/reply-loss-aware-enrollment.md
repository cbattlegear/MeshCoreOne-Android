# WP-401: distinct loss-aware enrollment and copy-only unknown text

Repository: `cbattlegear/MeshCoreOne-Android`.
Parent: `5cc18d54f774134b356f4ae39a2a3ac35d485965`.
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
data class EnrollmentOperation(val operationId: UUID, val storeId: UUID)
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
suspend fun reconcile(operation: EnrollmentOperation, expectedNonce: UUID? = null): EnrollmentResult
suspend fun bind(expected: CreationReceipt, acceptance: WitnessAcceptance): EnrollmentResult
fun boundJournal(receipt: CreationReceipt): NotificationReplyStagingStore
```

The agreed **external** ordering is:

1. Capture actual foreground loss warning acknowledgment; durably retain authenticated
   PREPARING witness for the already chosen operation/new store UUID before storage creation.
2. Call prepare at the separate generation path. Validate exact operation/store, revision1
   PREPARING receipt and atomically generated creation nonce. Persist exact nonce and witness
   acceptance durably before calling bind. A known nonce must always be checked on recovery.
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
predeclared store UUID, operation, fresh creation nonce, all mandatory policy, empty real
journal/registry/custody and copy tables in one synchronous-FULL SQLite transaction (schema4).
It does **not** call initializeNew, randomize the supplied store identity or infer freshness
from absence. Matching previously attributed operation returns its receipt; another
operation/store cannot adopt it. A known expected nonce rejects replacement with matching
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
the exact receipt, NotRetained(reason), or Indeterminate(operation). Reconcile never creates.
Lost successful returns reconcile only the same operation **and the nonce known by that
call**; missing/unreadable/replaced storage remains Indeterminate. Bind replays require
the same accepted witness UUID. Concurrent conflicting acceptances cannot overwrite the first.
All external/test observers execute only after transactions and handles close. Internal
deterministic interruption enums model crash steps without executing caller callbacks under locks.

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

`NotificationReplyEnrollmentTest` authors 29 real-SQLite methods at API31/37, covering:
absent/allocated/foreign/corrupt state; op/store/nonce/witness mismatch; PREPARING/BOUND
restart and concurrent enrollment; actual transaction interruptions at allocation, journal
creation, preparing write, bind write and text write; post-commit lost returns; missing and
replacement stores; restored PREPARING snapshot and joint local loss; journal-handle fencing;
unknown-token versus explicit copy-only retention; exact UTF-16 duplicates/conflicts;
count/per-record/total budgets; concurrent admissions; ignored/aborted inserts/accounting;
old-journal byte preservation/manual-copy projection; malformed rows and policies.

These are authored controls, **not executable acceptance**. The preexisting 81 named journal
methods remain; expected total is 110 journal/enrollment methods and 220 API-configured
invocations before baseline/adapter tests. Actual discovery must positively observe all names
in the exact committed candidate, with compiler-captured source/clean-input proof.

The still-active earlier diagnostic
[41966642435](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41966642435)
is bound to `e381b6fc5ff8f4aee904fcc288190ce004eb9cf3`, which contains frozen5cc and excludes
this successor. No result from it can verify this enrollment/copy implementation.
No local Gradle, retained-owner recovery, runtime change or active-ref advance is performed.
Structural validator outcomes are recorded at the immutable source handoff; they cannot
prove Kotlin compilation, SQLite execution, actual enrollment or human acknowledgment.

| Command | Result |
| --- | --- |
| `git diff --check` | Passed |
| `python tools\android-port\controller\validate.py` | First attempt blocked by Git object subprocess failure; one bounded process-only low-memory retry passed |
| `python tools\android-port\portmap.py` | Passed on the same bounded retry |
| `:platform:notifications:testDebugUnitTest` | Not run locally; retained execution-owner/readiness gate preserved |

The bounded retry set only process-local `pack.threads=1` and `pack.windowMemory=16m`;
no repository settings, reference pin, lock, runtime or persisted toolchain state was changed.
