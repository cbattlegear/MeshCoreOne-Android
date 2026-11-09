# WP-202: RX-log UUID case aliases (#615)

Repository: `cbattlegear/MeshCoreOne-Android`.
Base: `184d855d0cd7a19d6d6f60f51c4c1efd29cead13`.
Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Issue: <https://github.com/cbattlegear/MeshCoreOne-Android/issues/615>.
Acceptance scope: bounded repairs to `WP-202-behavior` and
`WP-202-boundaries`, not full WP-202/source-parity acceptance.

## Confirmed source defect and explicit data policy

The base still writes uppercase UUIDs and decodes lowercase/mixed-case text.
RX composite primary keys and DAO predicates compare binary text. A canonical
upsert can therefore add a second physical spelling of one logical identity;
decoding into a typed map or applying a pending overlay can hide one value.

This repair chooses **explicit rejection**, not automatic normalization, deletion,
merging, first/last-winner selection, or a schema migration. Any noncanonical-case
radio/entry key in the affected RX radio partition refuses its ordinary save,
typed RX read/overlay, enrichment, clear, device-data deletion or retention
operation with the existing reported `PersistenceStoreError.InvalidData`.
The error explains that explicit data repair is required. This includes a lone
alias and aliases with identical payloads: typed equality does not authorize
discarding either physical record. Raw key/payload bytes remain untouched.
Healthy radio partitions remain usable.

The test fixtures remove their own aliases only after preservation assertions
to allow fixture cleanup; that is not a production repair policy. No automated
user-data repair or new recovery UI is implemented here.

## Transaction and buffer behavior

- Inspect raw key spellings before UUID decoding/map coalescing, in the same
  Room transaction as the affected read or mutation. Binary comparisons detect
  lowercase/mixed-case entry keys; case-insensitive radio matching also includes
  aliases excluded by the ordinary canonical radio predicate.
- Recheck all pending radio partitions in the actual flush/write transaction,
  before upsert and after it (including trigger-introduced aliases). Automatic
  retention validates its affected cached radio partitions before pruning and
  after each radio's work. Independent contexts cannot substitute their own
  stale count or a process-local admission check for this database boundary.
- Save admission recomputes its logical count from the validated persisted and
  pending union. Repeated canonical saves still use the existing last-pending
  upsert semantics, count one identity, and retain the twenty-save-call batch
  boundary. The one-second timer, retention threshold (>1100 then keep 1000),
  transaction ownership, commit/cancellation protocol and reporter are unchanged.
- Only the existing real after-commit callback clears the original pending
  batch, publishes retention counts and increments the RX-initiated-save count.
  Rejection, rollback or precommit cancellation retains the batch. A failed
  timer reports once and does not spin. A failed close leaves the store open.
  Generic writes that incidentally flush pending RX entries roll back their
  own changes if the RX guard rejects.

This is not a universal SQL constraint: direct DAO/raw external writers may
still introduce noncanonical spellings. Repository operations detect them when
they next touch that radio; this patch does not claim to stop a writer after
the repository transaction has already committed. No global process registry,
identity ledger, schema allocation or retained-resource lock is changed.

## Authored real-Room regression controls

`RxLogUuidAliasRoomTest` uses the real Room database and complete row snapshots
(including blobs), not a mocked alias detector. It authors controls for:

- Radio-only, entry-only and both-key lowercase/mixed-case aliases; no enqueue
  or physical change after rejection; healthy-radio saves remain usable.
- Equal and conflicting preexisting duplicates; fetch/count/retention/clear
  reject rather than collapse or choose a payload.
- Pending overlay collisions introduced after admission; explicit and incidental
  flush reject, retaining both physical values and pending data while rolling
  back an unrelated channel write.
- Region/decryption enrichment and device deletion refusal, plus canonical
  enrichment/clear and repeated saves with exact timed/batch boundaries.
- Retention refusal without deleting old/conflicting rows, then ordinary
  threshold behavior after test-only cleanup.
- Mid-batch SQL failure, trigger-created aliases, staged-write cancellation,
  and late postcommit cancellation without replaying a cleared batch.
- Timer/close failure without spinning, dropping the batch or closing early.
- Two file-backed Room handles with independent repository contexts: a second
  writer commits an alias after the first queued its row; flush revalidation
  refuses and preserves both payloads. Normal canonical behavior is checked
  separately from alias rejection.

These are source controls, **not executed or discovered test evidence**.
No Gradle, native SQLite/Robolectric, Android, device or hosted test was run.
The retained local hard-resource owner remains unresolved; GitHub Actions was
reported in outage by the coordinator. Neither was bypassed and no blind rerun
was started. Future authorized verification must use the existing declared
software lane (`ci.py preflight`, `ci.py run --stage scaffold`, `ci.py inspect`)
with actual nonzero `:core:data` test discovery and exact-candidate results.

## Isolated candidate evidence

The task is authored from the immutable base in session source files using a
separate Git index, not by altering held #561/#550 or importing their code.
The adjacent generated source receipt supplies exact preimages, admitted
WP-202 capabilities, candidate tree/patch bytes and allowed static Git checks.
Static whitespace, scope and preimage checks do not substitute for compilation,
actual Room execution, or acceptance. All changed paths are WP-202 owned.
