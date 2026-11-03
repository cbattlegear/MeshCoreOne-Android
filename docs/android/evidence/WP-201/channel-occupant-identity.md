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
`channel_occupants(radioId,index,occupantID)` authority table. The registered v1-to-v2
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
with their channel mutation. Explicit invalidation throws when unsupported by a
store rather than claiming success. No channel secret is duplicated into message
or queue rows, and channel DTO / portable backup wire shapes remain unchanged.

Replacement/clear retains unresolved outgoing rows and their queued intent with
original text/epoch, marking them failed. Ordinary resolved history retains the
existing deletion behavior. Explicit user history deletion remains destructive as
before. A stale/unknown epoch still requires a typed queue/inline failure from the
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
Keep #535's independently bounded producer candidate separate.

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

- Fourteen `ChannelOccupantPersistenceTest` methods: rename/replacement/recreation,
  DTO/batch/radio partition, late successful save, retained unresolved text/queue,
  unknown legacy/ambiguous slot, actual disk reopen/hydration, independent-context
  exclusion, whole-operation restore/callback ordering, model equality, invalidation,
  direct-writer SET/save-gap exclusion, scoped success/child/expired rejection,
  forged/foreign/exact-Job capability validation, and exception/cancellation expiry.
- One `ChannelOccupantMigrationTest` method creates a real v1 SQLite database from
  the retained KSP export, opens it through the registered production migration,
  checks retained legacy text/queue/null intent, and reopens the upgraded file.
- The existing complete KSP/SQLite boundary assertion now checks v2 and 18 tables.

No local Gradle/JVM/WSL execution occurred. Retained execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged. Normal hosted diagnostic
verification was separately authorized against this candidate; the exact run and
log remain authoritative, not a committed second CI-result bundle. No iOS,
bidirectional codec, device, hardware, signing or merge acceptance is claimed.
