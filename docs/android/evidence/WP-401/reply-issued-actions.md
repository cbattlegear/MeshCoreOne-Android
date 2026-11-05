# WP-401: nonactivated atomic issued-action registry

Repository: `cbattlegear/MeshCoreOne-Android`.
Clean-main base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
Immediate storage-binding parent: `0868f1001b3a0a2903e9d1333f0147919290cf52`.
Source reference remains `db14559b39d32322b06477c6ae676112f583db50`.
The commit containing this document binds the exact registry source and controls.
No held OPEN ancestry, active ref, runtime wiring or execution-owner state is changed.

## Ownership and exact interface

By coordinator assignment and agreement with producer b204 and receiver 2e, this journal owns
the actual SQLite registry and reply transaction; b204 owns only the unactivated Android
PendingIntent adapter/parser. The receiver remains 2e-owned, dispatch/receipt remains
13a-owned, and read-only recovery UI remains aa4-owned. No separate producer registry is
created, and there is no lookup-then-stage integration.

Source:
`android/platform/notifications/src/main/kotlin/com/meshcoreone/android/platform/notifications/messaging/NotificationReplyStagingStore.kt`,
package `com.meshcoreone.android.platform.notifications.messaging`.

The following are nested journal types:

```kotlin
data class IssuedAction(
    val token: UUID,
    val post: NotificationReplyPost, // version 1 + REPLY + original post UUID
    val logicalNotificationId: NotificationId,
    val payload: NotificationPayload,
)
data class IssuedRecord(val storeId: UUID, val sequence: Long, val action: IssuedAction)
data class IssuanceLimits(val maxRecords: Int, val maxRecordBytes: Long, val maxTotalRecordBytes: Long)

suspend fun initializeIssuance(expectedStoreId: UUID, policy: IssuanceLimits): InitializeResult
suspend fun registerIssued(expectedStoreId: UUID, proposal: IssuedAction): IssuanceResult
suspend fun admitIssued(expectedStoreId: UUID, issuedToken: UUID, exactText: String): StageResult
```

`NotificationId` is the existing `core.contracts.domain.NotificationId`.
Only the existing typed direct/channel payload families are supported. The original structural
post UUID is never replaced with an invented post identity. The unique issued token is a
separate persisted/digested column in staged records and partitions duplicate reconciliation:
two distinct genuine issuances sharing a post UUID cannot accidentally reconcile each other's
reply; neither can a legacy unissued row satisfy an issued-token submission.

## Durability and immutable identity

Explicit `initializeIssuance(expectedStoreId, policy)` migrates an existing validated v2
journal to v3 inside a single transaction. It requires the caller's independent expected
installation identity and the separately initialized custody-byte policy; it never creates
a missing database, initializes custody with a guessed budget, or adopts observed identity.
Existing v1/v2 row digests and custody remain readable and unchanged through migration.
Repeated matching migration is idempotent; changed policy is rejected.

`registerIssued` commits a unique token to version/action/post, logical notification ID and
original typed routing payload **before** the adapter exposes any PendingIntent to Android.
Returned Reserved/AlreadyReserved records bind store UUID, record sequence and exact proposal.
The adapter must verify exact equality and must not expose a PendingIntent on rejection or
indeterminate reservation. Exact replay returns the same record; differing proposal reuse
returns ISSUANCE_CONFLICT and cannot overwrite the first proposal. There is no registry edit,
delete, TTL, eviction, replacement or implicit reissue/new-token retry API.

Registry rows, counts, serialized byte totals and immutable policy have complete validation,
canonical routing, exact integer-type/range checks and installation-bound digests. Logical
IDs retain exact UTF-16 code units, including NUL and isolated surrogates, without delimiter
ambiguity: digest fields are length-framed. Writes reload actual retained rows/accounting
before reporting a result, detecting ignored writes as well as failed writes.

`admitIssued` resolves token to the original post/routing and checks expected store UUID
**inside the same SQLite transaction** that retains/reconciles exact raw reply text, stable
record/outgoing identities and initial custody. Incoming mutable extras cannot supply or
replace a payload. Unknown tokens, uninitialized registry and mismatched/lost/corrupt storage
fail closed. Same token and text return the same retained identity; changed exact text
retains an unbound conflict, not a second outgoing message or draft associated with a
possibly replaced destination. Read-only recovery entries include the issued token, but
conflict entries still expose no payload/outgoing UUID and cannot be claimed.

The legacy `stage`/`admit` APIs remain unactivated storage/test surfaces, not issuance ingress.
The issued adapter must call `admitIssued`, never independently resolve a token then call them.
There is deliberately no public registry lookup API.

## Explicit acceptance and capacity boundary

| Result | Meaning |
| --- | --- |
| IssuanceResult.Reserved(record) | Exact reservation bytes retained after real transaction |
| IssuanceResult.AlreadyReserved(record) | Exact existing reservation reconciled |
| IssuanceResult.Rejected(reason) | This proposal was NOT RETAINED by the call |
| IssuanceResult.Indeterminate(token) | Retention could not be established; do not expose PI or mint replacement |
| StageResult.Staged / AlreadyStaged / ConflictRetained | Exact submission retained/reconciled in reply journal |
| StageResult.Rejected(reason) | This submission NOT RETAINED; do not enqueue/retire as accepted |
| StageResult.Indeterminate(post, proposedRecordId) | Possible commit, not proof of acceptance or unsent state |

Lost reservation/admission returns reconcile only exact bytes in the expected installation.
A different valid database containing the identical proposal cannot satisfy a lost return.
Missing or unreadable storage after commit is indeterminate; no automatic recreation occurs.
Reads, reconciliation, reopening and registration never send, replay, claim or settle.

Issuance count/per-record/total serialized-content byte limits are mandatory persisted policy,
separate from reply count/private-data limits and custody-byte budget. They do not measure
SQLite page/journal overhead. **A reservation reserves registry space only, not future reply
capacity or a promise to retain arbitrarily large RemoteInput.** Full reply/custody/private-data
capacity can still reject admission. No production budgets, unbounded fallback, eviction or
retention policy are selected here. The receiver/UI owners must preserve a genuinely visible
not-retained/uncertain result and must not retire process/notification recovery prematurely.
Reply text is never included in store logs or failure messages.

## Still missing: trustworthy provisioning and integration

This substrate provides durable *app-issued* action identity once it is genuinely provisioned;
it does not authenticate a human, original public key, current channel occupant or RF recipient.
All recovery target authority remains UNPROVED. Current-main UPDATE_CURRENT posting, mutable
RemoteInput and nonexported receiver do not themselves establish this durable issuance binding.

No real independently retained first-provisioning/installation witness has been supplied.
Database/marker absence, firstInstallTime and data-directory absence cannot distinguish
fresh installation from lost/cleared/restored state. Ordinary startup must not call initializeNew
or copy an observed store UUID into trusted expected state. This remains an explicit missing
primitive and blocks activation; fixture setup is not production evidence of it.

Producer adapter/parser, non-reused PendingIntent token identity, receiver exact-untrimmed
ingress and visible full-capacity handling, read-only recovery UI, caller budgets and protected
provisioning policy still require their owners' independent reviewed integration. No runtime
wiring, dispatch/settlement/retirement authority, automatic send/replay or retention policy is
introduced. #514/#570 full-capacity end-to-end recovery is not declared complete.

## Authored controls and verification boundary

`NotificationReplyIssuanceTest` authors 24 real-SQLite Robolectric methods at API31/37:
explicit bound migration/reopen; legacy row/custody preservation; invalid/changed budgets;
missing/unbound/mismatched storage; immutable duplicate/conflicting proposals; exact UTF-16
and routing; same-post distinct-token and legacy separation; unbound text conflicts; unknown
tokens/invalid identities; exact serialized byte/count/total limits; custody/private-data/full
admission; concurrent competing proposals/admissions and count saturation; ignored row or
accounting writes; rollback; lost successful reservation and reply returns; post-commit loss;
real replacement installation with identical proposal; corrupt policy/row/schema; unavailable
storage. These complement the earlier 28 staging, 16 lifecycle and 13 admission methods.
Total source inventory is **81 named journal methods**, expected 162 configured invocations.
These are authored counts, **not observed execution evidence**.

Manifest/portmap/whitespace checks are the only local structural checks; they do not compile
Kotlin or execute SQLite. Commands on this successor:

| Command | Result |
| --- | --- |
| `git diff --check` | Passed |
| `python tools\android-port\controller\validate.py` | Passed: manifest valid |
| `python tools\android-port\portmap.py` | Passed: provenance inventory generated |
| `:platform:notifications:testDebugUnitTest` | Not run locally; execution-owner/readiness gate |

The host readiness/pagefile and retained native execution-owner
boundary remain unchanged: no local Gradle, lock recovery or runtime deployment is attempted.
The exact-composition diagnostic [41964293724](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41964293724)
is bound to the earlier `72abb4f55516d3b01b311c70c689b8848727369c`, not this successor.
It cannot establish this registry's compilation, method discovery or SQLite behavior.
A new authorized exact-successor composition/hosted run must positively observe all committed
named methods and API expansion with compiler-captured source/clean-input proof.
