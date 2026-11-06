# WP-209: independent current-main channel DTO reseed repair

Tracking: [#601](https://github.com/cbattlegear/MeshCoreOne-Android/issues/601).
Keep open until the composed repair is verified and merged.

Base: `35abcc1276082995eed216bf28bff04d77e176c8`.
Reference: `db14559b39d32322b06477c6ae676112f583db50`.
This candidate is independently authored on landed main. It imports no held
`eca8`/`c77` implementation, schema, authority table, migration or diagnostic.
The prior authority branch at `dfee983ed380e95bc79ed4f6aa630642e85fdbfd`
was clean and preserved before the authorized non-destructive same-worktree
switch to `cbattlegear-channel-slot-main-601`.

## Source finding and bounded change

`BackupDatabaseRestore.insertChannels` generates a new UUID for a new imported
channel. `MockDataProvider` simulator seeds retain their original fixed UUIDs.
The base `ChannelRepository.saveChannel(dto)` queries only the incoming UUID:
its subsequent upsert inserts another row at the imported radio/index.
This is a source finding until the exact candidate's controls actually execute.

The immutable Swift inputs exhibit the same source interaction:
`MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupBatchInsert.swift`
constructs the placed channel with `UUID()`;
`MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift`
matches DTO save only by `dto.id`; and
`MC1Services/Sources/MC1Services/Simulator/SimulatorConnectionMode.swift`
replays `MockDataProvider.channels` through that save API. Immutable `git show`
reads and a diff against the pin confirmed these facts with no drift in those
three files. The initial guessed simulator `Services/` path was nonexistent;
the verified `Simulator/` path above is the actual source.
This repair is an explicit Android safety adaptation, not a claim that unique
slots already have equivalent Swift behavior. No iOS runtime test, reproduction
or source change occurred.

The repair first respects the exact radio/UUID match and its existing index.
When the UUID is absent, only a unique same-radio/index row with the same secret
and enable state may be reconciled. The current row is retained completely:
the replay is a true no-op, issuing no channel DML or save. It must not overwrite restored name, unread/date, favorite, notification
or flood-scope metadata with seed defaults. A missing-ID secret or enable-state
mismatch fails with typed InvalidData before any mutation; main has no safe
persisted occupant rotation/invalidation primitive to substitute here.
Exact scoped-ID updates retain their established `ChannelEntity.applying(dto)`
semantics. A duplicated active slot fails with typed
`PersistenceStoreError.InvalidData` before either known-ID or unknown-ID
mutation. No duplicate is guessed, consolidated or deleted. Unrelated radios
and slots are untouched.

This is DTO reconciliation, not occupant/dispatch authorization. A changed-key
DTO with an unknown UUID is refused, retaining text and pending rows. A known-ID
DTO continues to use the established DTO-update behavior without deleting text.
The actual-radio `saveChannel(radioId, info)` still deletes prior history when
its currently stored secret differs; that path is unchanged. No durable
incarnation or original-message/pending revision proof is supplied by this fix.

## Authored controls and actual evidence

`ChannelReseedRoomTest` adds seven real-Room test methods using the existing native
SQLite fixture, not a persistence fake:

- Actual `AppBackupService` customized-metadata empty-store import followed by
  three fixed-DTO replays; complete imported channel metadata/UUID,
  sent/incoming/unsent messages and a channel pending row survive.
- Missing-ID changed-key DTO replay fails without changing channel/history/pending; subsequent
  different-key actual-radio update retains its existing cleanup behavior.
- Ambiguous legacy same-slot rows reject both known and missing UUIDs without
  deleting channels, unsent text or the pending row.
- Same UUID across foreign radios, unrelated slots and exact-ID wrong supplied
  index remain partitioned and preserve existing exact-ID application semantics.
- New unoccupied slot still uses the supplied UUID.
- Missing-ID enable-state mismatch fails with current state/text/pending retained;
  explicit known-ID updates preserve their original behavior.
- SQLite INSERT/UPDATE/DELETE rejection triggers permit matching unknown-ID
  replays but reject an explicit known-ID update, proving the fallback issues no
  channel DML rather than redundantly upserting the same row.

Source checks performed:

- `git diff --check`: passed.
- `python tools\android-port\portmap.py`: passed.
- `python tools\android-port\controller\validate.py`: passed.

Seven methods are source-authored, not seven executed tests. Kotlin compilation and
JUnit execution have not occurred. The retained local execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged and was not executed
through or bypassed. Exact-head hosted execution and independent review remain
required.

WP-217's sole `SimulatorOnboardingIntegrationTest.kt` delta from
`d668a031565554243abd2b01281195fb86b0bfcb` is also composed without importing
its parent history. Its added
`freshStoreBackupImportPreservesChannelRowsAcrossSimulatorReconnects` method
uses actual `AppBackupService` empty-Room import, four real fixed seed DTOs,
retained user text and three actual onboarding/simulator reconnects. It checks
unique slots, retained imported UUIDs/history, foreign-radio isolation, the
existing seed-family assertions and no physical-radio/HTTP calls. All previous
App assertions remain unchanged. This added method is likewise
unexecuted; clean patch application is not compilation or product evidence.

The local successor also composes only the independent App test delta
`5386a5444ae9385afa6df2e987d88dc32d7bfa69`, not its parent history.
`mountedAmbiguousSimulatorSeedShowsFailureAndRetriesAfterRepair` mounts actual
onboarding with a duplicate slot whose existing row has the fixed fixture UUID.
It requires localized InvalidData, disconnected state, retained channels and
user messages, then fixture-only duplicate removal and a successful retry to
READY with retained identity/history and offline boundaries. This additional
method remains uncompiled/unexecuted.

The reviewed prior candidate
`7ce63fbaf329d4eb1f0922343d9acb0754c8b795` was published unchanged at
`diagnostic/wp209-channel-reseed-7ce63fbaf3`, with remote SHA verification and
empty same-ref run checks before dispatching existing `android-ci.yml` once:
[42142531863](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42142531863).
That run belongs only to `7ce`, not this local successor. It is not replaced,
cancelled or overwritten by this preparation. No run result is transferred to
the additional test or any subsequent SHA. No reproducible CI-result bundle is
copied or committed here; the exact hosted job/log is authoritative.

Independent review identified that the earlier `7ce` fallback improperly applied
the unknown-ID DTO to the current row. The original new changed-key/toggle tests
also incorrectly blessed that behavior. This local correction strengthens those
new controls to require visible mismatch refusal and adds a customized imported
metadata witness. No pre-existing test or known-ID API behavior is weakened or
changed. `7ce` is a superseded diagnostic, not the safe successor or merge target.
The matching fallback's early transaction return also avoids triggering future
identity/epoch invalidation on a redundant identical write. Existing context
transaction ownership is retained, and no staged RX save is requested.

The independent WP-217 App delta
`11aa115491aeca28b46dfc1584d8456f4ecccc53` adds two further mounted cases:
restored changed secret and restored disabled state, each with a missing fixed
seed UUID and customized metadata/history. Both require localized InvalidData,
disconnected state and retained data/offline boundaries, followed by fixture-only
secret/enable reconciliation and successful retry with the imported UUID and
metadata retained. Only this test-file delta is composed, not its parent history.
There are now seven new Room methods and four new App methods relative to main;
all remain uncompiled/unexecuted on this local successor.

The baseline main run
[42139516955](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42139516955)
had a separate observed `ChatMapPreviewTest` failure. It is neither evidence for
this candidate nor a waiver or proof of flakiness.

Trusted `reserve.py admit` accepted `modify-related-consumer` for
`ChannelRepository.kt`, `add-or-update-related-test` for
`ChannelReseedRoomTest.kt`, `record-evidence` for this file, and `modify` for the
WP-209 deviation. Admission does not transfer any other producer's custody.
