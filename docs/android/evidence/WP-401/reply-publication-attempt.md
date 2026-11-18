# WP-401: permanent single app publication attempt

Repository: `cbattlegear/MeshCoreOne-Android`.
Parent: `73dcc598f2edef457dc25e363553ab80ad3e2bd3`.
Verified capacity source prerequisite: `e1d7d42dc0c4dd0cfcff8cd10f7c29c8f0317c16`.
Read-only Swift pin remains `db14559b39d32322b06477c6ae676112f583db50`.
The commit containing this document binds its source and controls.

## Agreed ownership, atomicity and lifetime

The coordinator assigned this separate nonactivated successor. Storage owner and producer
b204 agreed the real protocol before edits; consumer 2e reviewed the same-transaction
claimed-state requirement and absence of legacy fallback. Storage owns these SQLite
changes/tests; b204 owns the closed producer operation, PendingIntent construction and
Android publication; 2e owns receiver/volatile failure presentation, aa4 copy-only UI.
No other owner's source, active ref, runtime deployment, manifest/component or live ingress
is changed here. All changed paths have trusted `wp-owned` create/modify admission.
The historical advisory claim bindings remain untouched.

The narrow supported guarantee is **at most one app-controlled publication attempt per
issued token within the retained generation**, when all cooperating producers obey this
protocol. It is not a SQLite-plus-Android transaction or a time-limited lease. Caller-chosen
attempt and owner UUIDs are retained before any platform work; they are not authentication.

Android `NotificationManager.notify` and `PendingIntent.getBroadcast` cannot enlist in the
SQLite transaction. A successful notify return is not proof of display or user interaction.
An IPC submitted before caller death may still complete after local locks are released.
Holding an in-process mutex, file lock or SQLite transaction around the call cannot manufacture
an atomic platform commit or make a restarted producer know the old request was drained.
Repeated checks of unused capacity merely move the consumption/publication race.

Consequently this protocol never retries, takes over, expires or releases a possibly used
publication attempt. Death/cancellation after its durable allocation but before the platform
call can permanently lose the notification opportunity while retaining quota. A live PI
may also outlive the caller after an actual platform call; its exact reply remains eligible
for storage admission, not replayed publication. A claim is not evidence that the PI exists.
No guarantees of physical display, eventual notification availability, OS text preservation,
notification disappearance after consumption, at-most-once delivery or failed-write durability
are made. Direct out-of-protocol PI manufacture is not prevented by the database.

## Exact storage API and schema

Types and functions are nested in `NotificationReplyStagingStore`:

```kotlin
data class PublicationAttempt(val attemptId: UUID, val ownerId: UUID)
data class IssuedRecord(
    val storeId: UUID,
    val sequence: Long,
    val action: IssuedAction,
    val maxReplyCodeUnits: Int? = null,
    val publicationAttempt: PublicationAttempt? = null,
)
sealed interface PublicationResult {
    data class NewlyClaimed(val record: IssuedRecord) : PublicationResult
    data class Observed(val record: IssuedRecord) : PublicationResult
    data class Rejected(val reason: Failure) : PublicationResult
    data class Indeterminate(val token: UUID) : PublicationResult
}
suspend fun initializePublicationAttempts(expectedStoreId: UUID): InitializeResult
suspend fun reservePublicationAttempt(
    expectedStoreId: UUID,
    proposal: IssuedAction,
    maxReplyCodeUnits: Int,
    attempt: PublicationAttempt,
): PublicationResult
```

`initializePublicationAttempts` explicitly opts schema5 into schema6 only through the exact
BOUND operation/store/predeclared nonce/witness/revision handle, after capacity opt-in.
Both issuance AND reply registries must be empty, or it rejects
PUBLICATION_REQUIRES_EMPTY_GENERATION without alteration. An old registry token might
already have been exposed, so it cannot be grandfathered into a first publication attempt.
Independent route-free copy rows need not be empty and remain unchanged. Idempotent schema6
initialization does not require the now-used registries to be empty and grants no attempt.
Schema4/5 behavior remains unchanged until this explicit opt-in.

Schema6 adds nullable canonical UUID text columns `publication_attempt_id` and
`publication_owner_id` to the issuance table. Both must be absent or both present, with
unique attempt IDs across claimed tokens. A claimed row also requires a positive explicit
UTF-16 bound. The immutable issuance digest and serialized-content quota include both IDs;
all old null-field digests remain unchanged. Issuance count/per-record/total limits already
bound the number and bytes of attempt records: there is no new guessed policy or budget.

`reservePublicationAttempt` creates the exact bounded issuance and permanent attempt in
ONE synchronous-FULL SQLite transaction, with the original first-reply row/private-byte/
initial-custody reservation. Actual retained rows/accounting are reloaded before success.
It never claims an existing unclaimed reservation, widens limits, changes text bounds or
adopts a token from another generation. Attempt IDs cannot be reused for another token.

| Result | Meaning and permitted use |
| --- | --- |
| NewlyClaimed(record) | First uninterrupted successful creation only; conforming producer may enter one closed platform operation |
| Observed(record) | Existing first record, including same-attempt restart or another contender; NO publication permission |
| Rejected(reason) | No new attempt retained by this call; NO platform callback |
| Indeterminate(token) | Possible commit/lost return; NO callback, replacement attempt or automatic retry |

Same exact action and bound with any already-claimed attempt returns Observed of the first
record, even after reply consumption; never a new grant. Changed action/bound, reuse of an
attempt ID for another token, or trying to upgrade an existing unclaimed token rejects
ISSUANCE_CONFLICT. Schema below6 rejects PUBLICATION_NOT_INITIALIZED. Any error after a
possible commit returns Indeterminate: there is deliberately no post-commit reconciliation
that can turn a lost first return into NewlyClaimed. Cancellation propagates; restart can
only observe the retained permanent record. A pre-commit rollback may be retried if the
caller actually retained the rejection, but uncertainty does not authorize platform work.

Schema6 `registerIssued`, legacy `stage` and legacy `admit` reject PUBLICATION_REQUIRED.
`reserveIssuedReply` can still retain a no-attempt bounded reservation for ExposureHeld,
but that token cannot be promoted in place or admitted. It has no publication authority.
`admitIssued(expectedStoreId, token, exactText)` checks the stored permanent attempt in
the SAME transaction as token resolution and text retention; unclaimed token rejects
ISSUED_TOKEN_UNCLAIMED. Unknown old tokens remain ISSUED_TOKEN_NOT_FOUND; wrong store
and bound receipt retain existing IDENTITY_MISMATCH/ENROLLMENT_MISMATCH errors. Receiver
maps these to its typed presentation, never a legacy route, send or saved-text assertion.

All exact UTF-16, idempotent duplicate, unbound conflict, explicit quota and copy-only
semantics from e1d7 remain. Admission does not require a fictitious notify completion write.
Consumption may precede the sole publisher's eventual IPC completion; this protocol
prevents another conforming publisher, not that ordering or transient stale platform UI.
The storage returns no PendingIntent and executes no external callback under DB locks.

## Required producer and receiver integration

b204's future closed `publishOnce` operation must use ONLY NewlyClaimed from this API,
validate exact store/action/bound/attempt/owner, construct the PI and call the actual poster
internally, and return no PI to a caller for later publication. Observed, Rejected,
Indeterminate and cancellation never authorize that callback. All platform exceptions or
uncertainty after claim leave the attempt permanent; there is no retry/release. Existing
bounded APIs remain ExposureHeld, and schema6 registry-only PI preparation fails closed.
No alternate actual producer path may bypass the gate.

The receiver calls actual `admitIssued`, preserves the raw String and never falls back to
mutable-route decoding, dispatcher or send on rejection. Indeterminate/not-retained text
can be shown as aa4's separate volatile TransientRecoveryText only while the real in-memory
String exists; no persistence or survival after process death is implied. Durable copy-only
projection remains `UnboundRecoveryKey(storeId, recordId, sequence)` plus exact text, with
explicit current-generation consumer fencing; the source-only aa4 contract does not itself
provide an authenticated adapter or active route.

Production authenticated witness, actual foreground acknowledgment, trusted current-generation
ingress/UI wiring and caller limits remain separate prerequisites. Joint rollback of matching
DB/witness snapshots is still not prevented. This opt-in does not bind schema6 antirollback
to hardware or change the external witness receipt into an Android publication fact.

## Authored controls and actual verification boundary

`NotificationReplyPublicationTest` authors 24 real-SQLite methods at API31/37:
empty-generation opt-in/copy preservation; pre-schema6 potentially exposed token refusal;
legacy reply refusal; missing/PREPARING/stale/uncapacitated initialization; first-only grant/
same-owner and competing-owner observation; legacy bypass refusal; unclaimed token rejection;
admission with no invented post receipt; consumed replay/conflict; immutable action/bound/
attempt reuse; explicit capacity/private/custody failure; exact text/oversize; concurrent
publishers and admission; consumption between claim return and hypothetical platform work;
lost committed return; caller interruption after commit; ignored/aborted writes; missing
storage; unknown/old tokens; nonce replacement and corrupt partial claims.
The interruption controls model lost caller returns, not actual Android process-kill evidence.
These tests never claim to execute Android notify or prove physical visibility.
All previous 141 journal methods remain unchanged.

| Command/evidence | Result |
| --- | --- |
| Pinned Python `ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute directory>` | Passed with existing native Windows toolchain |
| `git diff --check` | Passed before source freeze |
| `python tools\android-port\controller\validate.py` | Passed: manifest valid |
| `python tools\android-port\portmap.py` | Passed: provenance inventory generated |
| `:platform:notifications:testDebugUnitTest` for this successor | Not run locally; exact-candidate hosted verification pending |

Local interlocked product verification remains refused by the old installed
`controller/authority.py` runtime, as recorded on the prerequisite; no runtime installation,
owner recovery or direct Gradle bypass is attempted. The previous exact run41973722094
proved e1d7's 288 notification cases only. It cannot verify this schema6 successor, its
24 added methods, the future closed producer, receiver integration or publication behavior.
Expected journal inventory is 165 named methods at two configured APIs plus six baseline
cases (336); these are authored expectations, not observed execution.

The initial exact storage-only composition `decff0830bf3c093e0882e576bf0a850c7e50dae`
(parents d59773a10 and e88d8590b) ran as
[41980907900](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41980907900)
and completed FAILURE. Linux job127090616644 failed
`:platform:notifications:compileDebugKotlin` at StagingStore line1001: mixed nullable
integer/string `arrayOf` inferred an intersection type and made array concatenation
ambiguous. The repair explicitly declares `arrayOf<Any?>`, matching existing SQL bind
argument types; it changes no storage protocol, tests or expectations. No schema6 product
tests executed successfully in this failed run and no 336-case acceptance is claimed.
Classify/controller/Swift codec jobs passed; android-ci failed and the SwiftData job was
skipped after the Android failure. A new exact successor diagnostic is required.

The typed-array successor `915949e39b0b0db50c6d255cae4bb48982862e29` was composed
as `664bb77680875790e681d20155317f5acc766497` (parents d59773a10 and 915949e39).
[41981885876](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41981885876)
also completed FAILURE: Linux job127093729380 failed
`:platform:notifications:compileDebugKotlin` at line1001. Explicit element typing
removed the intersection-type error, but `+` still admitted both the single-element
and array overloads for `Array<Any?>`. The next repair explicitly selects
`values.plus(elements = arrayOf<Any?>(...))`, preserving the twelve SQL bind values,
their order and all test assertions. Neither failed diagnostic executed the notification
tests or produced 336-case discovery/compiler-input evidence. Classify/controller/Swift
codec passed; Linux Android and android-ci failed; SwiftData was skipped. All prior source
refs and runs remain unchanged. This successor requires its own exact hosted execution;
the explicit overload selection is not itself compilation or behavior evidence.
