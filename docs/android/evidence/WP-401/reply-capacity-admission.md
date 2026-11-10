# WP-401: nonactivated bounded first-reply capacity admission

Repository: `cbattlegear/MeshCoreOne-Android`.
Parent: `a479539c90e88c5e37fe561fa2a7e6b7d26ecde5`.
Read-only Swift pin remains `db14559b39d32322b06477c6ae676112f583db50`.
The commit containing this document binds the storage source and focused controls.

## Ownership and activation boundary

The coordinator assigned this storage-side prerequisite after the nonce-bound enrollment
diagnostic passed. WP-401 owns the staging/enrollment stores, their capacity tests and this
evidence. b204 owns the separate issued PendingIntent adapter; 2e owns receiver admission;
aa4 owns copy-only UI. These owners confirmed no overlapping storage edits. Their sources,
active refs and runtime wiring are unchanged. No new agent/session, runtime installation,
owner recovery, dispatch, expiry, retention, eviction or production budget is introduced.

Trusted `reserve.py admit` accepted both source modifications, the new test and this document
as `wp-owned` operations. The supported claim attempt refused the historical WP-401 lease's
different source/base/manifest/backend. Its identities, authorization and bindings were not
rewritten. Current trusted policy permits isolated admitted work despite that advisory
mismatch; actual storage ownership was coordinated directly.

## Concrete storage contract

All types below belong to `NotificationReplyStagingStore`; the proposal/action type is
unchanged. All sizes and bounds are explicit caller inputs, never a production default.

```kotlin
data class IssuedRecord(
    val storeId: UUID,
    val sequence: Long,
    val action: IssuedAction,
    val maxReplyCodeUnits: Int? = null,
)
suspend fun initializeReplyReservations(expectedStoreId: UUID): InitializeResult
suspend fun reserveIssuedReply(
    expectedStoreId: UUID, proposal: IssuedAction, maxReplyCodeUnits: Int,
): IssuanceResult
suspend fun admitIssued(expectedStoreId: UUID, issuedToken: UUID, exactText: String): StageResult
```

`initializeReplyReservations` is an explicit transactional schema4-to-5 opt-in on an
externally supplied exact BOUND enrollment handle. It adds one nullable integer column
`max_reply_code_units` to the issuance table and leaves the operation/store/predeclared
nonce/witness/revision, old rows, old digests, original limits and copy-only tables unchanged.
Reopening/reconciling enrollment accepts either schema4 or schema5 and validates the whole
journal. Old v1-v3 stores are not upgraded by this API. A missing, PREPARING, stale,
wrong-nonce or wrong-witness handle cannot enable or use bounded reservations.

`reserveIssuedReply` commits the exact issued action plus positive maximum Java UTF-16
code-unit count and quota for **one initial submission** in a single SQLite transaction.
The bound participates in issuance serialized byte accounting and its integrity digest.
It must fit the existing persisted issuance policy as well as reply count/per-record/total
private-byte limits and the separate persisted custody-byte limit. No quota is widened.

For every outstanding bounded token, the store accounts for one reply row, a conservative
serialized root row with maximum permitted sequence width, the original immutable routing,
the exact declared maximum text bytes, and the initial UNCLAIMED custody receipt. UUIDs
have fixed serialized widths. All legacy/unreserved/conflict writes respect outstanding
row/private-byte reservations; custody claim/settlement growth cannot consume reserved
initial-receipt bytes. Reservations do not reserve future claim/dispatch/settlement space.
Counts, content, bound types/ranges, digests and outstanding quota are revalidated on reads
and writes; ignored writes cannot produce a Reserved or Staged success.

The first successful `admitIssued` consumes its own quota atomically with the retained
initial reply and custody. Consumption is derived from the validated root row for that
exact token, not a separately updated flag. Shorter actual text releases only the unused
quota difference through that accounting. There is no release/delete/expiry API for an
unsubmitted action; unused reservations may exhaust capacity indefinitely.

| Result or case | Truthful meaning |
| --- | --- |
| Reserved(record) | New exact action, bound and initial quota durably retained |
| AlreadyReserved(record) from `reserveIssuedReply` | Exact same token/action/bound and still-unconsumed quota reconciled |
| Rejected(REPLY_RESERVATION_CONSUMED) | Initial reply already retained; do not re-expose this action |
| Rejected(ISSUANCE_CONFLICT) | Token/action/bound mismatch, including upgrade of an old unreserved token or downgrade |
| Rejected(RECORD_CAPACITY / PRIVATE_DATA_CAPACITY / LIFECYCLE_CAPACITY) | Insufficient quota before action exposure; this proposal was not retained by this call |
| Indeterminate(token) | Possible commit; no action exposure, replacement token or new bound retry |
| Staged / AlreadyStaged / ConflictRetained | Exact submitted text durably retained/reconciled, not dispatch authority |
| StageResult.Rejected(reason) | This submitted text was not retained by this call |
| StageResult.Indeterminate(...) | Retention could not be established; neither acceptance nor definitely unsent |

Lost successful reservation returns reconcile only the exact token/action/bound and
operation/store/nonce/witness receipt. Caller cancellation propagates; restart can reconcile
the same retained inputs. Missing or replaced storage is never recreated/adopted. This does
not add antirollback for jointly restored matching DB and authenticated witness snapshots.

Exact duplicate submissions reconcile even at full quota without allocating another row.
Changed text remains an unbound conflict only if ordinary unreserved space is available;
it cannot spend another token's reservation or create another outgoing message. Oversized
bounded-token input returns REPLY_TEXT_BOUND_EXCEEDED when within the outer journal input
limit, or PRIVATE_DATA_CAPACITY at that outer limit; it is never truncated or normalized.
Empty input is INVALID_TEXT. Nonempty whitespace, NUL, isolated surrogates, combining forms
and all original Java String code units remain exact. The bound is not a Unicode scalar,
grapheme, UTF-8 byte or Android RemoteInput enforcement claim.

The existing `registerIssued` remains explicitly registry-only, with a null text bound.
Its old actions have no reserved reply quota. A producer adopting the bounded API must
validate exact store/action/bound from Reserved/AlreadyReserved before exposing Android's
PendingIntent and must never treat the registry-only API as equivalent.

### Publication fence remains absent

A positive reservation receipt describes the database transaction, not a lease extending
through Android PendingIntent construction, notification publication or later IPC. An
already issued action can submit and consume its reservation after an AlreadyReserved
transaction returns but before a producer constructs or publishes a replacement action.
Checking again merely moves that race. The storage API has no protocol spanning those
external operations and cannot by itself promise that a newly exposed action still has
unused quota. Its consumed-token rejection applies when consumption is already visible
inside the reservation transaction, not to all later publication.

The first bounded producer successor `cec1dfdaf2e8ff90daf14108b41b78a0a478bf14`
missed this interval and is not accepted. The initial storage-owner source review also
missed that integration race and was explicitly corrected; it is not acceptance evidence.
Producer successor `56e4ac5b6f1c62ac3b42e0ab9482a5974e213838` instead returns
ExposureHeld on exact bounded reservation success and constructs no bounded PendingIntent.
That is a source contract, not execution evidence. Actual bounded action exposure remains
blocked pending a reviewed combined publication/admission fence; registry-only prepare
must not be used as a fallback. The storage quota controls remain useful but do not finish
the end-to-end before-exposure guarantee.

## Repeated, unknown and old input; real failure limits

Unknown and old-generation tokens cannot spend a current reservation or supply a replacement
route. `admitIssued` still resolves the persisted routing inside the exact admission transaction.
No receiver lookup-then-stage, mutable-extra route or fallback decoder is introduced.
The existing route-free API remains separate:

```kotlin
suspend fun retainUnbound(expected: CreationReceipt, submissionId: UUID, exactText: String): TextResult
suspend fun readUnbound(expected: CreationReceipt): TextInventory
```

These are methods/types on `NotificationReplyEnrollmentStore`. `CreationReceipt` is the exact
BOUND operation/store/predeclared nonce/witness/revision. TextResult is Durable,
AlreadyRetained, NotRetained(reason) or Indeterminate(submissionId, proposedRecordId).
TextInventory is Retained(records) or Failed(reason). Their explicit copy quotas are
independent, not reserved by issued actions. Copy records have no post/token/recipient/
outgoing identity or dispatch surface. A receiver must deliberately select an authorized
copy-only fallback; a registered-token failure is not permission to change routing.

**This is logical quota reservation, not filesystem space preallocation or a write guarantee.**
Disk full, inaccessible/corrupt storage, process death before commit, and unretained oversized
or changed replies can still lose typed notification text. Android does not guarantee its
RemoteInput survives a failed write or process death. Failed/ambiguous results must be shown
honestly by the receiver/UI; no success-shaped fallback, retained-text log or implicit retry
is provided. A full journal can suppress newly bounded actions, but already visible legacy
actions cannot retroactively be guaranteed retention or erased by this storage slice.

Production authenticated/durable witness, actual foreground acknowledgment, current-generation
transactional ingress authority, caller budgets/text bound, receiver visible failure UX,
producer bounded API mapping and recovery UI integration remain prerequisites. Structural
display identity and local digests are not authentication or RF-origin proof.

## Focused controls and verification

`NotificationReplyCapacityTest` authors 29 real-SQLite Robolectric methods at API31/37,
including opt-in migration/preservation, missing/PREPARING/stale identity, explicit bounds,
count/private-byte/custody saturation, exact one-byte admission boundary, reserved quota
protection against ordinary writes and custody growth, exact UTF-16, blank/empty input,
repeated exact/changed text, old/unreserved/foreign tokens, independent copy quota,
concurrent reservations/admission/legacy writers/competing bounds, ignored/aborted writes,
lost committed returns, caller cancellation after commit, storage loss and same-operation/
store replacement with a different nonce. Fault callbacks run only after transactions close.
Caller-interruption controls model a lost process return, not an actual Android process kill.
All previous journal controls remain unchanged.

Native Windows readiness used the already installed pinned Python3.12.4 and existing
same-host toolchain state with a fresh absolute preflight directory. The guide's suggested
verification-venv path was absent; selecting the existing pinned interpreter did not install
or bypass an execution runtime. No direct Gradle or retained-owner recovery was attempted.

| Command/evidence | Result |
| --- | --- |
| `python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute directory>` | Passed with existing native Windows state |
| `git diff --check` | Passed before source freeze |
| `python tools\android-port\controller\validate.py` | Passed: manifest valid |
| `python tools\android-port\portmap.py` | Passed: provenance inventory generated |
| `:platform:notifications:testDebugUnitTest` for exact capacity composition41e361 | Passed on hosted Linux: 288 discovered/run/passed, zero failures/errors/skips; not run locally |

The supported exact-commit command
`python tools\android-port\local\check.py --commit e1d7d42dc0c4dd0cfcff8cd10f7c29c8f0317c16`
refused execution: `Installed execution runtime is old or mismatched: controller/authority.py`.
No product tests ran through that command and no runtime installation or owner bypass followed.

Independent coordinator source review of exact `e1d7d42` found no additional substantive
storage issue; this does not establish Android publication fencing or execution.
Discovery owner a0 published separate capacity-only composition
`41e361a033ab658ffb369d893530d28c9188e460`, tree
`60e0fdb5124be76f917be4aa62fe949bf220ab01`, parents
`d59773a10df7cfd462ddcbc7fe3ccc698ca859e7` and
`e1d7d42dc0c4dd0cfcff8cd10f7c29c8f0317c16`, on unique ref
`diagnostic-wp401-capacity-41e361a0`. All notification source/test blobs match exact e1d7,
with no producer adapter mixed in. Hosted [run41973722094](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41973722094)
completed **SUCCESS**, with all six jobs successful. The original Linux job127067704291
log proves 288 notification cases discovered/run/passed and zero failures/errors/skips.
All 141 journal method names have actual base and `[31]` executions at configured API37/31:
Capacity29/58, Enrollment31/62, Staging28/56, Lifecycle16/32, Admission13/26 and
Issuance24/48, plus six baseline cases. The actual notification task ran without a
cache/UP-TO-DATE suffix. Discovery owner a0 independently parsed the original hosted log;
the storage owner also confirmed terminal exact head, successful jobs and the original
notification report/source bindings.

The compiler captured nine exact test-source blobs and 24 remapped build/source inputs.
These include CapacityTest `d941e0e61bbe206313b6d0e0033125bad0c10bdb`,
EnrollmentStore `723ef711a093b77df2a861419e9b56b2abda5eae`, StagingStore
`eb9147b5a280dbc65ba2650f40aca2e4fdc34f26` and approved diagnostic build mapping
`9c5e1f4f6ed16f68ff629e6768afdd9185d2da61`. All previous 112 journal method sources
remain identical to the earlier nonce candidate. No duplicate CI-result bundle is
committed or required. This documentation successor does not alter those Kotlin/test bytes.

This exact run verifies the storage candidate, not the later bounded producer56e4,
receiver/UI integration, a combined publication fence, authenticated foreground witness,
physical Android process survival or RF-origin/dispatch authority. All exposure and
activation holds above remain in force.

The earlier successful `3fdd4862` / run41968603305 proves the be849 nonce-enrollment
source only, NOT schema5 capacity reservations. Neither its 230 executions nor the separate
registry-only producer diagnostic can be reused as capacity acceptance evidence.
