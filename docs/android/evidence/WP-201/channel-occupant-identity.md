# Channel occupant persistence prerequisite for #524 / #530

Primary scope: WP-201 model/contracts/Room, with individually admitted WP-202 repository support.
Session: `a3ae3e66-361e-403e-bba9-ed490fd2c11d`.
Base: `796a872a3565a66c74f782b738a9ecb3c273fb25`.
Swift reference remains `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA256: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.

This is an **UNVERIFIED CANDIDATE**, not whole-WP acceptance, physical-radio safety,
or completion of the independent queue/fence/consumer/restore producers.

## Persisted contract

`MessageDTO`, `ChannelMessageEnvelope` and `PendingSendDTO` append nullable
`channelOccupantID: UUID?`. Message equality/hash, Room projections and envelope
conversions preserve the exact value. Message/pending saves never derive it from
the current channel. A late save can therefore retain its original text and epoch
after a replacement without becoming a send to the new occupant.

Room v2 adds nullable columns to `messages` and `pending_sends`, plus a separate
`channel_occupants(radioId,index,occupantID,unresolved)` authority table. The registered v1-to-v2
migration neither deletes data nor backfills legacy outgoing intent from current
slots. The historical KSP v1 export remains unchanged. The v2 KSP export must be
generated and its Room validation executed by the real hosted build; no handwritten
generated-schema claim is made.

`ChannelPersisting.fetchChannelOccupantSnapshot` returns channel metadata and epoch
from one Room transaction. Consumers must capture that original snapshot before
asynchronous creation; combining an old selected row with a later current epoch
is not permitted. Missing, disabled, ambiguous or unreconciled authority returns
null, never a fabricated proof. `fetchChannelOccupantID` is suitable for the fence's
already-excluded validation, not a check-then-send authorization.

The real repository establishes a fresh opaque UUID on first authoritative save,
secret change, re-enabling or reconciliation after invalidation. Same-secret rename
preserves an existing epoch. Clear/delete and device-data deletion invalidate it;
recreation and observed ABA transitions do not reuse it. All these changes commit
with their channel mutation. Repository interface methods remain abstract, preserving
the existing contract assertion rather than adding success-shaped defaults.
No channel secret is duplicated into message
or queue rows, and channel DTO / portable backup wire shapes remain unchanged.

Replacement/clear retains unresolved outgoing rows and their queued intent with
original text/epoch, marking them failed. Ordinary resolved history retains the
existing deletion behavior. Explicit user history deletion remains destructive as
before. Scoped service cleanup through `deleteMessagesForChannel` preserves
unresolved outgoing text and queue intent even when the channel row is absent;
the same method on the ordinary store remains an explicit full history erase.
A stale/unknown epoch still requires a typed queue/inline failure from the
service producer; this persistence patch does not implement a recovery UI.

## Shared exclusion and producer boundary

`ChannelIdentityMutex.PROCESS` is a real process singleton in the Android-free
contracts module, never a no-op default. Separate repository instances use it by
default; a fresh instance can be explicitly injected for isolated tests.

1. `withChannelOperation`: outer operation lane across protocol work and persistence.
2. `withChannelIdentityMutation`: inner lane for already-excluded dispatch validation.
3. `withChannelIdentityRestore`: outer then inner around the whole backup transaction.

The order is outer then inner then repository/Room. Every ordinary authority writer
takes BOTH outer and inner. An earlier inner-only candidate was insufficient: it
allowed a direct DB writer between radio SET acceptance and its persistence.
The mutexes are non-reentrant; there is no inherited-coroutine-context bypass.
Restore uses unlocked DAO helpers,
not gated repository methods, and invokes its completion callback after both lanes
are released. Calls to `invalidateChannelOccupant` must not already hold the inner
lane. All authority-changing ordinary repository entry points take outer then inner:
both save overloads, batch configured/unconfigured/prune, deleteChannel, and
deleteDeviceData/deleteDeviceAndData. Plain metadata mutations do not rotate epochs.

`ChannelPersisting.withChannelOperation { scopedChannels, scope -> ... }` is the
sole outer acquisition for a channel-service mutation. The scoped facade takes
only inner on each write, using a private gate-minted capability. The exact gate
can validate it through `requireChannelOperationScope`; caller-implemented/foreign
capabilities, child Jobs and expired scopes are rejected. Scope activity closes in
`finally` before outer release, including exception/cancellation. The service must
not nest another ordinary outer acquisition. Mutex owner tokens reject same-Job
recursive ordinary writes, and the scoped facade explicitly rejects operation
reentry; neither hangs nor becomes implicit reentrancy. Its order is scoped operation, service
mutation lock, protocol work, then scoped persistence; observer callbacks follow
outside the scope. Protocol serializer callbacks and `NonCancellable` use different
Jobs: a producer must acquire/consume its scope in the actual admitted Job, not
silently widen ownership after radio acceptance. Concrete post-ACK cancellation
integration remains the radio producer's verification obligation.

Before any actual SET, the service must call the scoped facade's
`beginChannelMutation(radioId,index)`. It durably marks the slot unresolved while
retaining its opaque UUID, so process death after SET cannot restore stale proof.
Both identity reads return null until authoritative `saveChannel(radioId,info)` or
configured batch reconciliation commits. A same-secret authoritative reconciliation
preserves the original UUID; replacement rotates it. Ordinary `saveChannel(dto)`
cannot clear the marker. Definite-unsent, rejected, ambiguous or cancelled operations
may retain uncertainty until actual radio reconciliation, never fabricate rollback
proof. Calling begin on an ordinary store or expired facade fails explicitly.
All scoped facade writes, including metadata writes, enforce active exact-Job scope.
No delayed slot-only invalidation is required. This evolves the unreleased v2
candidate schema; it does not claim migration from an earlier development v2 file.

The independent shared-fence producer (`3008e9f0-6e96-40ab-b810-8feee639494b`)
owns service/protocol operation exclusion, connection retirement and dispatch.
WP-208 (`13a03908-afb8-42bd-9c96-57a27920076e`) owns queue and inline propagation.
WP-308 (`aa4b9d95-ca8d-4023-bb04-049e969e6d6c`) owns original composer capture.
WP-405 (`82e47f77-38d4-4f6b-ba74-2ae7755c4fb9`) supplied mutation constraints only.
WP-318 (`328fdb2f-354a-419f-9239-d7590b303a3b`) was asked to coordinate backup
restore invalidation and stripping local epochs from programmatic export/import.

No other producer's service, protocol, UI or backup implementation is edited here.
Raw DAO writers must participate in the agreed discipline; the table alone cannot
prove radio identity, unobserved external ABA, reconnect continuity or transport
drain after an ambiguous write. Restore must invalidate affected/remapped authority
in its transaction and never import a local epoch or stamp imported messages from
the destination slot. Those integrations require their actual producer commits.
Keep #535's independently bounded producer candidate separate. Durable accepted-send
attempt/cleanup replay across service restart remains a separate WP-208 contract:
an epoch, SENT row or process-local ACCEPTED marker alone does not establish that
an explicit retry attempt was accepted.

## Admission and verification

Actual trusted `reserve.py admit --wp WP-201/WP-202 --path <path> --operation modify`
calls returned `wp-owned` for every changed path, including new contract/entity/test
files and these owned evidence/deviation paths. The current WP-201 owner confirmed
no active overlap. The historical WP-202 custodian is archived with merged PR #471;
its original receipt is not adopted.

Actual new-session claims for both WPs refused
`Existing WP lease is bound to different source/base/manifest/backend`.
Historical WP-201 attempt `fc4b2e79-8993-46ae-b073-9fbad90d7635` revision 11 and
WP-202 attempt `cd9a430e-3052-4a74-9c3a-b333d5354c7b` revision 16 remain unchanged.
Per `docs/android/AUTOMATION.md` and the coordinator's explicit clarification,
that advisory result is retained, not rewritten into successor authority or
treated as a pre-edit lock on admitted isolated work.

Executed with pinned native Windows Python 3.12.4:

- `python tools\android-port\controller\ci.py preflight --local --state <existing-Windows-state> --output <fresh-absolute-output>`: PASS, exit 0.
- `python tools\android-port\portmap.py`: PASS, exit 0; source provenance only.
- `python tools\android-port\controller\validate.py`: PASS, exit 0; manifest/ownership only.
- `git diff --check`: PASS, exit 0.

Declared new product regressions, not passing executions:

- Eighteen `ChannelOccupantPersistenceTest` methods: rename/replacement/recreation,
  DTO/batch/radio partition, late successful save, retained unresolved text/queue,
  unknown legacy/ambiguous slot, actual disk reopen/hydration, independent-context
  exclusion, whole-operation restore/callback ordering, model equality, invalidation,
  direct-writer SET/save-gap exclusion, scoped success/child/expired rejection,
  forged/foreign/exact-Job capability validation, exception/cancellation expiry,
  actual restart with an unresolved pre-SET marker and no DTO clear, atomic failed
  reconciliation rollback, cancelled/escaped marker and metadata-write rejection,
  and scoped orphan-slot cleanup versus ordinary explicit history erase.
- One `ChannelOccupantMigrationTest` method creates a real v1 SQLite database from
  the retained KSP export, opens it through the registered production migration,
  checks retained legacy text/queue/null intent, and reopens the upgraded file.
- The existing complete KSP/SQLite boundary assertion now checks v2 and 18 tables.

No local Gradle/JVM/WSL execution occurred. Retained execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged. Normal hosted diagnostic
verification was separately authorized against this candidate; the exact run and
log remain authoritative, not a committed second CI-result bundle. No iOS,
bidirectional codec, device, hardware, signing or merge acceptance is claimed.

Hosted diagnostic [41598097110](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41598097110)
at exact candidate `2641346c6e98d237704912fd97200fafc8f1f282` failed the original
`DomainContractTest.repositorySurfaceIsAbstractTypedAndIncludesEveryPersistenceRoleRatherThanSuccessShapedDefaults`.
New default repository methods were corrected to abstract declarations; the assertion
was not weakened. Separately, misplaced local `channelWrite`/`channelOperation`
functions were corrected to actual context members. The run did not execute the
Room persistence/migration tests; its canonical log is authoritative, not acceptance
of these corrections or the new marker tests.

The corrected independent composition at `8148c0f43bae5fc3f9d5ceea44007513861c41b6`
was executed by [41599331042](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41599331042).
All 166 model tests passed, including the unchanged abstract repository invariant.
Controller/workflow policy and the Swift codec oracle passed separately. The Linux
Android job failed in `ChannelOccupantMigrationTest` at the legacy fixture's
`SQLiteDatabase.insertOrThrow` call, before opening the production migration.
That fixture used unquoted column identifiers including SQLite's reserved `index`;
the correction uses identifier-quoted SQL and bound values, without changing the
legacy preservation assertions or production migration.
Available mandatory reports contained 5,953 tests: 5,952 passed, one failed,
zero errors/skips, with evidence explicitly incomplete. The database suite was
48 passed/one failed; the data/services suites did not produce mandatory reports.
This run does not establish Room repository, fence or fixture execution, nor does
it contain the later fresh-lease gate or held ChannelService/runtime composition.

The exact `0b26607fbf204becd9ee0a0f12f4bde9b4ba9314` successor was executed by
[41600492653](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41600492653).
Its database suite discovered 49 tests, 48 passed and the migration fixture failed
with `org.json.JSONException` at line 24. Available mandatory reports totaled
5,979 discovered, 5,978 passed and one failed, with zero errors/skips; required
data/services reports were absent. This is not integration evidence.
Static inspection of the exact retained exported v1 schema confirms that
`link_preview_data` omits optional `indices`, while the fixture unconditionally
called `getJSONArray("indices")`. The console excerpt does not expose a deeper
exception message; none is invented. Standalone `b8303ba7cdb218c7c653d0e46bdefc1011264f93`
handles only the absent optional array, still creates every present index and
still rejects malformed present arrays. The migration/data assertions are unchanged.
`e1f4c05ce40de4044823c7228882ddb90954af15` normalizes one patch line ending.
Its exact diagnostic
[41602293043](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41602293043)
was dispatched, with no passing outcome claimed here. It contains the shared
admission contract but not the journal/schema-v3 or later producer fence/service/
runtime candidates.

That exact run completed FAILURE. The database suite passed all 49 discovered
tests, advancing past the unchanged legacy-upgrade preservation assertions; the
model suite passed all 172. The data suite discovered 613, passed 606 and failed
seven. Available mandatory reports totaled 9,053 discovered, 9,046 passed, seven
failed and zero errors/skips, with App/services/UI reports absent. This is not a
passing foundation or consumer integration. The independent Swift oracle failed
at checkout (`curl 56`, early EOF, connect failure and GitHub DNS, git exit 128),
before any codec assertions; it has no oracle verification credit.

The seven data failures are separately diagnosed:

- Six failing cases in `InitialSchemaMigrationBoundaryTest` (two),
  `UnsignedPathReopenTest` (two), `NativeRepositoryBoundaryTest` and
  `BackupTransactionTest` retained a hardcoded native schema version 1 although
  this candidate registers v2. The console identifies their assertion failures,
  not a fabricated deeper assertion message. Their preservation/rollback/unsigned/
  unsupported-schema assertions remain intact; only the declared current-schema
  comparison changes. `RoomBoundaryTest` still independently asserts the explicit
  generated schema version and shape.
- `NativeRadioPolicyTest` exposes a real foundation regression: an added DTO-save
  secret-change branch deleted ordinary history before its unchanged `single`
  oracle. Frozen Swift `saveChannel(_ dto)` applies metadata without history
  deletion, unlike authoritative `ChannelInfo`. The branch is removed while
  retaining atomic epoch rotation. Existing Room dto/batch assertions now also
  discriminate retained ordinary/pending DTO history, unchanged original stamps
  and subsequent authoritative cleanup. The policy assertion is not weakened.

Standalone `c590e7e1cb873018b4b0a316be5723e8b91ae86d` contains only the source
correction and native fixtures. Standalone
`d45a6f056e7da20a3d2253cd1107f24ff3a71960` changes only the backup fixture's
schema assertion. Its ordinary WP-202 `modify` admission was correctly denied;
the actual producer WP-203/session `68581ff8-206b-49d8-8d03-b1c128ec16b3`
serialized that exact one-line support change. Own-checkout
`add-or-update-related-test` then passed the existing `reviewed-support-test`
capability and its unchanged-case/exact-hosted-discovery invariants. No policy,
historical lease or producer identity was rewritten. Both commit diffs are
compatible with the independent e1f4 schema-v2 candidate and contain no journal/
schema-v3 or held producer bytes; consumers import only those diffs, never parents.
Their corrected assertions still require fresh exact-candidate execution.

## Shared connection admission support

The runtime lifecycle owner and fence owner agreed the bounded WP-201
`ChannelDispatchAdmission(token, connectionIdentity)` support contract. It compares
the immutable `SessionToken` by value and the actual connection object by reference.
`tryAcquire` atomically increments a 63-bit admitted count only while unrevoked;
wrong bindings, revoked state and count overflow return null. `revoke` atomically
sets the remaining bit, retaining existing admissions. Each returned `Permit.complete`
decrements at most once, even concurrently, and never resets revocation. These
operations are nonsuspending and do not call user code, take locks, close transport
or establish physical drain. Runtime graph publication and the actual fence's
consumption/cleanup remain the respective producers' responsibilities.

Production path admission passed under WP-201. A proposed contracts-module test
path was denied; no file was created there. Six regressions instead use WP-201's
admitted model-module contract test surface, as the existing `DomainContractTest`
does: value/reference mismatch, revoke before admission, retained admitted count,
concurrent idempotent completion, acquisition/revocation race and exact count
overflow. The exact e1f4 model suite containing these declarations passed all
172 discovered tests in run 41602293043. That is not runtime-consumer or drain evidence.

## Separately assigned queued channel-send journal

Coordinator assignment `call_9WQQN5ukC9BgNcRYtZDZNKoR` explicitly adds the bounded
WP-201/202 storage prerequisite, separate from occupant identity and the independent
#535 candidate. WP-208 producer `13a03908-afb8-42bd-9c96-57a27920076e` confirmed it
owns the real queue/channel retry consumer, that wire timestamps are UInt seconds,
and that channel command success has no additional wire ACK metadata.

All modified model/contracts/database paths and repository/test support paths
passed actual trusted WP-201/WP-202 `modify` admissions. Historical reservations are
not adopted or rewritten. Production queue, inline send, UI, held ChannelService
and runtime code are not edited by this journal candidate.

`PendingSendDTO` appends nullable `channelDispatchState` and `channelAttemptID`.
Null is unknown legacy state, never inferred READY from a SENT message or occupant
proof. New proved channel submissions through the enqueue/retry transaction receive
READY with no active attempt. Generic channel upsert cannot create dispatch proof,
reset an existing state/pointer, replace its payload, or overwrite it as a DM.
Automatic orphan/legacy purges retain channel intent; ordinary pending deletion and
fresh retry enqueue reject retained unknown, DISPATCHING, ACCEPTED or UNCERTAIN
submissions. Explicit user message/history/device-data deletion remains distinct.

Five real abstract methods on `PendingSendPersisting`:

- `beginQueuedChannelAttempt(key, expectedOccupantID, wireTimestamp)` compares the
  exact radio/pending/message IDs, saved/queued channel index, original occupant
  UUID and exact text, then atomically writes DISPATCHING, its current attempt UUID
  and immutable journal metadata before the caller may dispatch. Another retained
  unresolved submission for that message blocks begin even if this row was READY
  before the other attempt started.
- `settleQueuedChannelAttempt(key, outcome)` CASes that same active attempt:
  ACCEPTED is cleanup-only; DEFINITELY_UNSENT returns the submission to READY with
  no current pointer; UNCERTAIN never re-arms. Repeated identical terminal
  settlement is idempotent even after completion or a later attempt. A fresh UUID
  is required for every subsequent actual wire attempt, including pool backoff.
- `completeAcceptedQueuedChannelAttempt(key)` accepts only the exact accepted
  attempt. In one transaction it applies SENT unless already DELIVERED, commits the
  actual wire timestamp, deletes only its exact accepted pending row and marks the
  journal completed. Only a resend increments sendCount, zeroes heardRepeats and
  deletes repeat rows, once. A different live submission for the message causes a
  typed atomic rejection before any mutation; no newer outcome is overwritten.
- `fetchQueuedChannelAttempt(key)` and `fetchQueuedChannelAttempts(pendingKey)`
  provide real exact-key readback/history, including immutable `isResend` and
  completed state for accepted hydration cleanup. Missing reads return null/empty;
  no command returns fake success or defaults from an unsupported implementation.

Conflicts use the existing `PersistenceStoreException(InvalidData)` with typed
`QueuedChannelAttemptRejected(pending, reason)` cause, reported through the standard
repository mechanism. No new UI-error variant or unknown/retry UX is selected.
Malformed persisted states/phases fail projection explicitly rather than becoming
READY, accepted, or silently omitted.

Schema v3 adds nullable state/pointer columns plus
`queued_channel_attempts(radioId,attemptID,pendingID,messageID,channelIndex,
channelOccupantID,wireTimestamp,isResend,phaseRawValue,isCompleted)`, keyed by
radio/attempt UUID. No foreign cascade may discard uncertain evidence during
ordinary queue cleanup. Explicit full device-data erase clears its partition.
The registered v2-to-v3 migration creates no attempt records and stamps no legacy
queue state; v1-to-v3 chains the existing migration. Generated v3 schema validation
must come from real KSP/Room execution, not a handwritten schema export.

Sixteen authored real-Room regressions discriminate exact begin metadata and
saved text/proof mismatch, independent-context single-claim exclusion, failed
pre-wire/acceptance/completion rollback, definite-unsent fresh-attempt reuse guards,
unknown/uncertain preservation and retry rejection, real DISPATCHING/UNCERTAIN and
ACCEPTED disk reopen, idempotent resend cleanup after a later explicit submission,
atomic refusal of a newer live message owner, radio partitions, malformed state,
late delivery/nonresend counters and explicit device-data erase. Actual Room
transaction-executor schedules additionally cancel before begin executes and at
committed begin/acceptance/completion return, then reconcile exact stored state
without inferring acceptance or repeating resend bookkeeping. These follow the
existing native repository commit-return cancellation fixture, not a fake store.
The migration fixture exercises retained exported v1 and a v2 database produced by the actual
v1-to-v2 migration, preserving original text/proof and null unknown state through
upgrade/reopen. This is not a fabricated v2 KSP export.

These are storage-boundary declarations, not actual queue/session wire-count
assertions. Crash-left DISPATCHING never proves either acceptance or nonacceptance;
the real producer must hydrate only READY, clean ACCEPTED without radio calls and
expose unknown/uncertain intent without automatic replay. Producer adoption,
post-return cancellation handling and exact-candidate composed execution remain
required. No inline-send journal, recovery UX, hardware drain, bidirectional codec
or exactly-once delivery is claimed.

Journal source checks passed using `C:\Python312\python.exe`:
`tools\android-port\portmap.py`, `tools\android-port\controller\validate.py`
and `git diff --check`. These validate source provenance, controller policy and
whitespace, not Kotlin/Room execution. The retained local Gradle execution owner
was not changed or used. The immutable journal candidate remains UNVERIFIED
until its own exact-candidate hosted run discovers and passes the required suites.
