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

The repair first respects the exact radio/UUID match and its existing index.
When the UUID is absent, only a unique same-radio/index row may be reconciled.
`ChannelEntity.applying(dto)` retains that row's UUID/radio/index and established
DTO field-update semantics. A duplicated active slot fails with typed
`PersistenceStoreError.InvalidData` before either known-ID or unknown-ID
mutation. No duplicate is guessed, consolidated or deleted. Unrelated radios
and slots are untouched.

This is DTO reconciliation, not occupant/dispatch authorization. A changed-key
DTO continues to use the established DTO-update behavior without deleting text.
The actual-radio `saveChannel(radioId, info)` still deletes prior history when
its currently stored secret differs; that path is unchanged. No durable
incarnation or original-message/pending revision proof is supplied by this fix.

## Authored controls and actual evidence

`ChannelReseedRoomTest` adds six real-Room test methods using the existing native
SQLite fixture, not a persistence fake:

- Actual `AppBackupService` empty-store import followed by three fixed-DTO replays;
  imported UUID, sent/incoming/unsent messages and a channel pending row survive.
- Changed-key DTO replay preserves existing identity/history; subsequent
  different-key actual-radio update retains its existing cleanup behavior.
- Ambiguous legacy same-slot rows reject both known and missing UUIDs without
  deleting channels, unsent text or the pending row.
- Same UUID across foreign radios, unrelated slots and exact-ID wrong supplied
  index remain partitioned and preserve existing exact-ID application semantics.
- New unoccupied slot still uses the supplied UUID.
- Repeated disable/enable replay keeps current UUID and related rows.

Source checks performed:

- `git diff --check`: passed.
- `python tools\android-port\portmap.py`: passed.
- `python tools\android-port\controller\validate.py`: passed.

Six methods are source-authored, not six executed tests. Kotlin compilation and
JUnit execution have not occurred. The retained local execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged and was not executed
through or bypassed. Exact-head hosted execution, WP-217's real simulator App
control composition and independent review remain required.

The baseline main run
[42139516955](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42139516955)
had a separate observed `ChatMapPreviewTest` failure. It is neither evidence for
this candidate nor a waiver or proof of flakiness.

Trusted `reserve.py admit` accepted `modify-related-consumer` for
`ChannelRepository.kt`, `add-or-update-related-test` for
`ChannelReseedRoomTest.kt`, `record-evidence` for this file, and `modify` for the
WP-209 deviation. Admission does not transfer any other producer's custody.
