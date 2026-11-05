# Direct ACK storage authority

## Immutable acceptance-history successor for source review only

This successor is based on `8a8441b25e832785dcd2719fb3e1750168f9638c`.
It implements the separately authorized internal retention repair, not bootstrap
acceptance or completion of WP-202. No local build, test, SQL execution, Git object
decompression, schema generation, hosted dispatch, PR or merge was performed.
The host-memory and execution holds remain in force. Independent immutable-source
review is required before execution.

New tracked first wires and retry wires atomically receive the unchanged
`EMISSION_TRACKING_REQUIRED` operation and a separate immutable
`ACCEPTANCE_HISTORY_REQUIRED:<wire UUID>` birth operation. Exact registration
replay does not add either missing marker. Older original-tracked-only wires
remain legacy/unproven even with matching physical acceptance, an empty internal
journal or sticky saturation. No historical accepted code is manufactured.

After the existing generic acceptance replay/conflict/stale checks, a genuinely
new acceptance on a tracked wire atomically appends
`TRACKED_WIRE_ACCEPTANCE:<wire UUID>` with the actual code, then updates the wire
and claim. Each internal operation uses a new unused operation primary key.
The unchanged generic rule only commits acceptance while the wire actual is null;
matching later calls replay and differing actuals conflict. Thus ordinary replay
cannot grow or overwrite this journal. A later physical resolution may change
the current wire actual but cannot replace the retained generic actual. Durable
nonpayload history is not evicted or capped; diagnostic materialization remains
bounded. Message erasure and lease retirement still erase payload snapshots.

Complete SQL predicates inspect both birth markers and every retained generic
actual, not only the latest wire actual. An unsaturated unmatched actual remains
target/foreign conflict and unresolved settlement evidence. Historical generic
codes also participate in radio-wide known-code collision checks, across distinct
messages and contacts. On sticky saturation, fully covered wires may legitimately
have no matching physical row after CoveredBySaturation; that gap alone is not
malformed/conflicting. Independently malformed journals/physical evidence and
missing birth coverage remain explicit. Bounded diagnostics return typed
InvalidData for incomplete older tracked coverage or contradictory acceptance,
rather than fabricating a legacy physical invocation. Internal kinds are filtered
in SQL before public receipt decoding; generic results, eligibility and receipt
shape are unchanged.

The existing exact delivery proof is checked before unrelated later malformed
radio history. It proves historical committed delivery/idempotency only, not
compliance with today's stronger exclusion policy. Its stored fields contain no
policy/prehistory qualification. Code/RTT/proof mismatch still rejects; malformed
new settlement still fails. Existing product DELIVERED state is preserved. An
old attempt cannot be adopted by a newly opened repository context, and receipt
inspection never reconstructs an attempt. No restart attempt/ACK recovery is
claimed. Canonical clarification lineage through remote
`56613c6edb25411bf7aef4ad9659675667d38983` supplied the retention, provenance,
saturation, old-wire mapping and historical-proof requirements. Its mandatory
bootstrap declarations are not imported without the missing metadata producer.

The guarded-failure Room source now has 23 test annotations, all UNEXECUTED.
New controls include generic 999 -> physical 888 and matching 999 -> 999,
deletion/lease retirement/file reopen, a fresh process epoch and different-contact
foreign collision; generic insertion rollback, commit then caller cancellation,
exact replay without growth, internal-operation-ID collision, initial/retry
birth-insertion rollback and conflicting/missing/stale rejection; old-marker-
only ancestry including a newer fully covered retry; exact delivered replay
after unrelated malformed evidence, mismatched RTT, proof/receipt corruption and
malformed new settlement; malformed acceptance journals despite saturation; and
real public CoveredBySaturation with no invented marker, including retained
generic 999 alongside physical 888. Conditional retained-journal positives are
not certificates of clean physical prehistory on a real installation.

Current-positive raw fixture births now explicitly include both provenance
markers. The observation control's later original-marker-only insertion remains
legacy: its expected reason set retains the existing conflict/saturation reasons
and additionally requires legacy ancestry. The receipt query control retains its
4,101-resolution load and every public receipt assertion; its raw operation count
increases from 4,102 to 4,103 for the new hidden birth marker. The preexisting MAY
diagnostic assertion is retained before generic acceptance; after acceptance the
same physical MAY is independently inspected and bounded InvalidData is required.
Its successful physical acceptance/delivery assertions are unchanged.

Global physical prehistory UNKNOWN, its fresh/upgrade/merge-restore/reopen
controls, schema/version admission and the compact message diagnostic producer
remain separate unfinished prerequisites. The three frozen bootstrap negatives
remain unexecuted/expected failing. This repair must not be used to claim a
globally sound current-policy confirmation or accepted macro WP. Genuine schemas,
consumer files, generic contracts and the held WP-203 surface are unchanged.

## Candidate boundary

This is an independently authored WP-202 repair based on published main
`41552c85b659396111cee6b0e0f70c404ac5a8ee`, not completion of the macro WP.
The pinned Swift specification remains
`db14559b39d32322b06477c6ae676112f583db50`.
The WP-201 additive contract is the exact patch from
`93125597154c8ae28041f6781c5fb851d2b6188b`, integrated as `cc5901f30`.
No held channel candidate, historical direct-ACK candidate, consumer patch,
schema export or foundation ancestry was imported.

The active channel owner confirmed there is no live main-bound schema-version
allocation/migration reservation. Main's only published schema was v1.
Trusted `persistence-schemas` admits the independently authored v1-to-v2
migration; `wp-owned` admits the repository, lifecycle, tests and evidence.
Schema-coupled tests use `reviewed-support-test`. Domain contract authorship
remains WP-201, not an interpretation of the generic consumer capability.

## Storage design

Room v2 adds persisted message/pending incarnations, message/pending mutation revisions,
session leases, logical claims with last-own-commit snapshots, immutable delivery
snapshots, additive wire-code bindings and exact-operation journals.
Every supported message/pending INSERT, UPDATE and DELETE fires persistent
SQLite triggers, including no-op status writes, DAO upserts, raw SQL replacement
and key changes. A typed operation validates the lease, incarnation, revision
and current grant in its actual Room transaction. Only its expected revision
advance can renew its own grant; nested extra mutations fail and roll back.
This is not a Boolean affected-row or matching-DTO attribution rule.

The raw-write boundary is message/pending DML on the managed database.
Arbitrary DDL, dropping/replacing authority tables/triggers, or manufacturing
authority records is not a supported application write interface. The producer
verifies canonical trigger definitions inside each transaction and fails
explicitly when they are missing/changed. New production databases use
`DirectAckStorage.callback`; existing v1 files use the non-destructive migration.
Custom Room builders must install that callback. A builder without fencing
cannot silently obtain the capability.

The separately authored WP-201 no-dispatch contract
`80e8ebc33ac2730c2e45845333425284c7e4f77a` is integrated as `994388ac4`.
Its independent capture/completion role persists exact pending/message snapshots
and lease/incarnation/revision bindings after all legitimate finalization writes.
Actual outgoing DM rows are required; normal delivered cleanup excludes resend,
and zero-attempt metadata must be exactly zero. Completion deletes only the
captured pending incarnation, journals the operation and does not create or
renew any ACK grant. Generic pending deletion still invalidates prior ACK claims.

Opaque handles additionally bind the issuing repository context. Durable lease
history orders same-epoch generations and retires earlier epochs; activation
request replay cannot replace a successor. New handles cannot adopt old issued
handles. Process-store close revokes its leases, independently of radio teardown.
Backup restore invalidates grants in its commit transaction even when the
importer would skip every message. Failed restore rolls invalidation back.

Logical claims precede dispatch. A returned code supersedes only its own wire's
prediction; earlier accepted wires on the same live claim remain eligible.
Before acceptance, storage still recognizes the prediction; consumers must buffer
pre-response ACKs until the actual code is bound rather than settle speculatively.
Only typed retry/status/send-count and exact
pending completion operations preserve the prevalidated grant. Generic retry
or queue mutation invalidates it. Explicit resends preserve terminal baselines;
acceptance never uses a temporary FAILED-to-SENT relabel. Matching delivery is
one transaction and includes its immutable committed DTO/code/RTT receipt.
Historical exact-operation replay never renews authority or deletes a replacement.

## Verification state

Source-only preparation passed:

- `C:\Python312\python.exe .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $output`
  with the existing same-host state and a fresh absolute TEMP output directory:
  exit 0, pinned Python 3.12.4. The documented verification-venv path was absent;
  the available pinned interpreter executed the successful preflight.
- `python tools/android-port/controller/validate.py`: exit 0.
- `python tools/android-port/portmap.py`: exit 0.
- `git diff --check`: exit 0.

The new source contains 20 repository and four database test methods, including
actual Room migration from the checked-in v1 export, same-value independent
FAILED controls before/after own commit, cancellation before/after commit,
explicit rollback failure, nested mutation revision fencing, typed retry codes,
terminal resend, queue replacement, raw/DAO writes, separate contexts/handles,
file reopen, restore and revocation. These are authored assertions, not discovered
or passing tests. No Room/KSP/Gradle execution or generated v2 schema is claimed
at the initial source-only stage.

Own advisory reservation enrollment was attempted using the original assignment
prose, session `f8058663-e138-4456-9d4d-6ad11522824c` and base `41552c85`.
The supported command refused: `Existing WP lease is bound to different
source/base/manifest/backend`. No historical receipt was inspected, transferred,
released or rewritten. Exact `add-migration` and
`update-directly-required-entity-or-dao` admissions pass for this repair; a
generic `create` probe on the database path correctly refuses. The coordinator
reviewed the refusal against trusted main `docs/android/AUTOMATION.md` and
confirmed this is advisory historical binding mismatch, not a pre-edit lock or
live semantic collision. Assigned, capability-admitted isolated authoring
continues after recording the actual attempt. No receipt is claimed as acquired;
no owner/authorization/binding/ledger mutation or held-work release occurred.

Source review after the first diagnostic dispatch added a conflicting-pending
insert guard: raw `INSERT OR REPLACE` remapping a pending key to a different
message must fence both old and new messages, including an inline ACK claim
with no captured pending ID. This is a conservative DML-attempt fence for that
conflicting remap; ignored conflicting inserts may also invalidate the old
claim, never grant permission or change message content. Its two-message control
is a successor assertion, not a result of the earlier exact-head run.

Local Gradle remains blocked by retained execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` and missing canonical cleanup proof.
There is no bypass or ownership release. Separate admitted tooling owners
provided first-export-only hosted schema recovery; only genuine KSP output
became the v2 schema. Hosted exact-head results/logs, not a second result
bundle, will be authoritative.

The first hosted diagnostic is
[run 41958103719](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41958103719),
attempt 1, exact candidate `fade600f73220faf2f67880b0fbc1f1751f379ec`.
It failed `:core:contracts:compileTestKotlin` on a type-inference error at
`DirectAckAuthorityTest.kt:133`. The collector also rejected two identical bare
KSP headers in Gradle's parallel console stream; it produced no artifact.
This run provides no Room migration or data-suite acceptance and no recovered
schema. The respective contract and tooling owners are producing bounded fixes.

The corrected diagnostic is
[run 41959471917](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41959471917),
attempt 1, exact candidate `9b9953aee68b7aef9fb1de4fb7d5cf35363ebdfa`.
It retained genuine generated `2.json` plus original provenance in artifact
`13384687094`. Schema SHA256 is
`d9fb07803c1710afa82ff92cfcd0c82fc3871f511029b6862d939c1bfc69ae62`,
Room identity `e04a68a685879f5293d881f381622531`, version 2 with 24 entities.
Both temporary diagnostic paths were retired to exact trusted-main bytes
before the generated schema was copied unchanged into the previously absent
repository path. The original downloaded provenance remains in session custody;
it records two task-heading displays, not two executions or independent compiler
completion.

That corrected run still failed `:core:data:compileDebugKotlin`: a cross-module
nullable `pendingId` needed a stable local value for smart casting. The successor
uses that local binding. Its Swift oracle independently failed Python setup
with `getaddrinfo ENOTFOUND raw.githubusercontent.com`. Neither failure is Room
test acceptance. Normal successor validation must run without either overlay.

The concrete service/repeater regression and original successful ACK families
remain the consumer owner's work against this producer. Old Boolean-wrapper
tests are not evidence of typed producer calls. No durable ACK ingress,
restart recovery, device/radio, whole-WP, release, signing or merge acceptance
is claimed.

## Independent P1 successor: issued intent and erasure

The normal overlay-free predecessor
[run 41960514028](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41960514028),
exact candidate `84094ff1fa0413884d403e25674de652b574e748`, failed
`:core:database:testDebugUnitTest`: 52 tests, one failure,
`DirectAckStorageTest.exportedV1MigratesThroughRoomWithoutChangingAnyMessageColumn`,
`org.json.JSONException`. The genuine v1 `link_preview_data` entity omits optional
`indices`. The fixture now treats only an absent property as an empty array;
a present malformed property still throws. Migration and every-column equality
assertions remain intact. This is a source repair, not a passing migration claim.

Independent source review also found that the original key-only begin could bind
a replacement message after the consumer had already cached earlier wire inputs.
It found that invalidation alone retained deleted/redacted payload snapshots.
These are real defects in the predecessor, not accepted limitations.

The exact WP-201 successor `3726b4448f574ba4aaa04a1043eb8bc01bdc9aba`,
integrated as `46dd00d6a`, replaces the unsafe begin overload with persisted
`captureDirectAckIntent` / `inspectDirectAckIntent` and
`beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)`.
Preparation writes finish before capture. The issued message/pending snapshots,
not any earlier fetch, supply wire inputs. Begin revalidates the original
incarnations and message/pending mutation revisions atomically, consumes outstanding intents
for that message, and creates the claim/code. Exact begin replay checks its
already-persisted claim rather than trying to recapture a changed row.

Nullable embedded snapshots now have independent nonpayload identity headers.
Message delete, key replacement, raw replacement and payload-field changes null
all message/pending snapshots in intent, claim, delivery and no-dispatch history
in the same SQLite mutation transaction. Status, ACK, retry, failure-seen and
send-count bookkeeping are not payload redaction. Pending UPDATE/DELETE/REPLACE
nulls pending snapshots in intent/no-dispatch history; its owning message snapshot
remains until message erasure. Own no-dispatch completion explicitly stores
`pending = null`, never reinserting the deleted DTO after trigger erasure.
Exact tuple/operation replay retains IDs, code/RTT and commit identity with
non-CURRENT permission and nullable erased payload, never a fake DTO or new grant.
This is logical SQL-visible erasure, not a forensic free-page wiping guarantee.

The deletion trace covers message/dependents, contact conversation, direct DAO/raw
message deletion, message replacement/redaction, and device-data/device-and-data
deletion through their message/pending DML. Channel conversations cannot acquire
direct-message grants; changing a direct message into a channel message is itself
a payload mutation. A device-record-only delete, contact-record-only delete or
ghost-identity change intentionally retains conversation data in the published
source; this repair does not invent a cascade. Other-radio/channel queue semantics
are unchanged.

The original 20 repository controls are adapted to issued capture/begin. Nine
additional actual-Room methods are authored: before-begin text/same-value/identical
DTO ABA, same-ID pending replacement/preflight, fresh issued inputs after an old
cache window, eleven deletion/redaction/replace routes across every history, own
pending completion erasure/replay, cancellation-after-capture recovery, and
foreign-context/restore/generation fencing, retired-lease erasure, and issued-intent
invalidation through a second file handle plus reopen. Their assertions are not discovered
or passing evidence. The consumer owns actual wire derivation and service barriers.

The tracked genuine v2 predecessor remains byte-for-byte unchanged at the digest
above. This still-unmerged main-v1-to-v2 lane now needs a guarded tracked-schema
refresh to generate its changed 25-entity model; neither deleting the predecessor
nor handwriting a schema is permitted. The separate tooling owners are preparing
that diagnostic. No active ref was advanced. Shared Windows paging/memory
exhaustion interrupted initial Git/Python operations. A later bounded source-only
pass using pinned Python 3.12.4 completed documented same-host preflight,
`validate.py`, `portmap.py` and `git diff --check` with exit 0.
Trusted `reserve.py admit` also confirmed the migration and schema boundary test
operations. These source results do not prove Kotlin/Room behavior. No current Kotlin/Room execution,
new export, publication or acceptance is claimed. The retained local Gradle
execution-owner gate remains untouched.

The renewed independent review also names session revocation. A canonical lease
active-to-inactive SQLite trigger nulls the retired lease's payload snapshots in
all four histories, including delivery through its claim identity. Explicit
revoke, successor activation and process close therefore erase snapshots in
their own transaction without deleting the actual product message/pending row.
Nonpayload operation/code/RTT/phase tombstones remain, with stale/non-CURRENT
permission. The additional Room control checks each route and unchanged product
rows; it is authored, not passing runtime evidence.

## Guarded tracked-schema refresh

[Run 41964501226](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41964501226),
attempt 1, exact candidate `ac2bac8dcf2fb4f74dfaaa7ca27347cba5a2eedc`,
passed the tracked-predecessor guard, collection and upload. Artifact
`13386339538` retains the genuine new v2 schema and original provenance:
SHA256 `c8b324628f0ece66967c9ea9025567208bc23c4681f5973f72f7308d5cf732b2`,
Room identity `a3393bbacc299c85a57cb7e0a5d564fc`, 25 entities.
The guard bound the previous committed/worktree digest `d9fb0780...`, exact head,
run/attempt, clean tracked path and fresh output time. Two bare KSP task-heading
displays are observations, not two executions or independent compiler proof.
The original schema/provenance remain in session custody. Both temporary
workflow/fixture overlay paths were reversed before copying generated bytes
unchanged into the tracked schema path; the predecessor was never deleted.

The overall run failed `:core:contracts:compileTestKotlin` at
`DirectAckAuthorityTest.kt:105:44`: unsafe access on nullable `MessageDTO`.
The contract owner is producing an assertion-preserving fix. No Room suite
acceptance follows from the recovered schema. Swift codec and controller jobs
passed on that exact head, but this does not establish full product acceptance.
The temporary overlay's local fixture executed 18 tests successfully after
setting its required `tools/android-port` Python module path; its commit prose
mistakenly said 31. The actual command output, not that prose, is authoritative.

## Restore-entry payload erasure follow-up

Independent exact-head consumer review of
`3afa654fbd35ceb3baa4f705434508546041c229` confirmed a further privacy gap:
`withBackupRestore` cleared current flags only. If import skipped an unchanged
product row, no message/pending DML trigger erased its historical snapshots.
The sole storage producer now erases all claim, delivery, intent and no-dispatch
snapshots explicitly at restore entry, inside the existing restore transaction.
Nonpayload identities, operation journals, phase/code/RTT and ordinary product
rows remain unchanged. The helper requires an active transaction and verifies
the managed fencing triggers; it cannot report success outside the transaction.
No entity, migration, trigger or generated-schema shape changes are needed.

Two actual-Room methods are authored: a duplicate/unchanged-row skip observes
all four histories already nulled inside the transaction and verifies persisted
tombstones and unchanged product rows after commit; typed failure and cancellation
observe the same in-transaction erasure, then assert exact restoration of every
history, product row and current grant after rollback. These are source assertions,
not executed or passing evidence. Existing exact-head normal run 41965047472
predates this follow-up and cannot establish its execution.

An additional public `AppBackupService.importBackup` control uses a real unchanged
envelope and asserts the actual MESSAGES skipped/inserted/merged counts. Its
before-commit hook directly inspects all four erased histories. Success retains
the erasure; injected typed failure and cancellation restore exact history rows
and current permission. All paths preserve the ordinary message and pending
rows. This companion avoids treating a manually simulated skip as importer
execution; it too remains authored rather than executed evidence.

The overlay-free predecessor run 41965047472 failed `core:data` with 642 tests,
one failure in the multi-route erasure control; `core:database` reported 52/52.
The fixture supplied preview blobs to generic `saveMessage`, but the existing
`MessageDTO.toEntity` deliberately stores those fields as null. The shared
authority test setup now uses the supported `updateMessageLinkPreview` before
intent capture to persist real blobs, preserving its original full-DTO equality
and SQL-column erasure assertions. No production mapping or oracle is changed.
This fix is source-derived from the real mapping; successor execution is pending.

## Backup fixture fencing diagnostic

Exact `dc6fa1e8ad8e4d8c618957fec6f94a75bf56acd3`
[run 41966079500](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41966079500)
failed `core:data`: 645 tests, 116 failures. The numerous backup failures report
`DirectAckStorage.kt:302`, which is the canonical-trigger verification check,
not the active-transaction check. `BackupRoomTest` constructed its primary
in-memory database, file databases and independent destinations without the
production `DirectAckStorage.callback`. Restore now correctly rejects those
unfenced databases. The shared backup fixture now installs the actual production
callback on all three builders. No backup expectations, compatibility rules,
production checks or transaction/erasure guarantees are relaxed.

A discriminating public-import control removes one fencing trigger from an
otherwise configured real Room database, then asserts the exact rejection
before the import commit hook, unchanged histories in all four tables and
unchanged product message/pending rows. The earlier real-import duplicate-skip,
failure and cancellation controls remain intact. The separate preview fixture
setup failures in this run were already repaired by the supported preview
mutation. These changes are source-checked assertions pending a new immutable
hosted run, not passing runtime evidence.

The exact `542ffa448cc235106cca8003eed19a6b3f0e06fe`
[run 41967931135](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41967931135)
completed with `core:data` 646 tests / 4 failures. The remaining failures are
the three `LegacyImportLifecycleTest` methods and
`ParityPhoneTimestampClampOriginalPredicateTest`'s real-import method, all at
the same canonical-trigger check. Both classes inherit `RepositoryTest`,
whose separate in-memory builder also omitted the production callback.
That shared builder now installs `DirectAckStorage.callback`; all original
import, timestamp and repeated-restore assertions remain unchanged.
The preceding repair did not establish whole-run acceptance. This additional
fixture correction requires its own exact-head hosted execution.

The exact `5f282399aeb17d5909e3cd656ac109ff3b75a3c2`
[run 41969637946](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41969637946)
completed with `core:data` passing and `:app:testDebugUnitTest` reporting 1,973
tests / 23 failures. Nineteen restore failures again reported missing canonical
triggers at `DirectAckStorage.kt:302`; the affected app/parity tests share
`RoomProcessTest`, which delegates to `openMemoryDatabase` in
`ContainerTestSupport.kt`, a third callback-less builder. That shared builder
now installs the production callback. Four remaining `BackupRegistrationTest`
assertion failures also stem from the restore aborting before their intended
post-commit assertions (including an `IllegalStateException` instead of the
expected refresh `IOException`), per the exact failure log. Existing app and
parity assertions are unchanged; another exact-head run is required.

The shared `openMemoryDatabase` callback repair completed successfully on exact
candidate `cc13f06530ae8e23ff9f169178ba5f093e95921d`:
[run 41971548941](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41971548941),
attempt 1, overall `success`. Hosted protocol-suite evidence reports 4,926
discovered/run/passed, zero failed/errors/skipped; the backup sub-suites report
`core:data` 646/646 and `core:database` 52/52. Swift codec, controller policy
(1,478/1,478) and actual SwiftData restore/export oracle also passed. The run
executed before the local accepted-send bookkeeping follow-up below and does
not validate that follow-up or close the ACK-origin issue.

## Accepted wire versus inline completion

The exact WP-201 contract commit
`2b885851a60e4317247ac41589dfa7e10f9fb550` was cherry-picked locally as
`1d15f42198cd6ddc66607a08f7046ad4286e3367`. Its additive
`DirectAckBookkeeping.SendCompleted(wireRequestId)` contract separates actual
accepted-code binding from the inline PENDING-to-SENT transition. The local
producer implementation now records the returned wire code and ACCEPTED phase
without changing the product status; only `SendCompleted` can promote the
current PENDING row with that exact claim-scoped accepted wire when the claim
mode is `SEND`. A review identified that the API KDoc did not explicitly name
the mode boundary; the producer now rejects `SendCompleted` for RESEND claims
before product mutation. The actual-Room negative control asserts
RESEND+PENDING with an accepted wire leaves the product row, message
incarnation/revision, and operation journal unchanged. The SEND positive and
existing retry/terminal controls remain. Operation replay binds the wire ID,
and unknown/predicted-only wires reject. This follow-up is unexecuted; local
Gradle remains unavailable under the retained execution-owner gate, and no
active diagnostic ref was changed or rerun.

Verification after composing this follow-up: `git diff --check` passed, and the
declared Windows readiness command
`python .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute temp directory>`
passed with pinned Python 3.12.4. The declared
`python .\tools\android-port\controller\ci.py inspect --state $env:ANDROID_CI_STATE --output <fresh absolute temp directory>`
did not run product tests and exited 2 because `android/scaffold/inspect_apk.py`
had no available APK input. No `ci.py run`, Gradle, or hosted candidate run was
started for this committed follow-up; the execution-owner/publication hold
remains in force.

## Unresolved ACK origin boundary

### Persisted cross-generation exclusion prerequisite

The coordinator assigned a separate producer successor after the service owner
reported an executed cross-generation control: a possibly written, unresolved
generation-1 send followed by a new graph over the same store can let a raw ACK
settle a generation-2 message. A lease fences mutation grants, not radio emissions.
This is not durable ACK ingress or proof that a received ACK originated from a
specific dispatch.

Isolated schema-only preparation is frozen at
`3e582bcf25d13412f2b81f4c44b6a36baefbc1bf`, parent
`fc84a775a4e6a343a24de38e5246074a9cc92fc1`, above the preserved
`4e25e25ae69d91b313ae704d31864a3d5fb8226b` v2 producer. The intermediate
`fc84` schema draft was superseded before dispatch after the consumer confirmed
that pool-backoff calls can reuse a registered wire ID. The corrected marker
key is `(radioId, claimId, wireId, markRequestId)`: each physical transport call
requires a fresh marker, so proving a later call definitely unsent cannot clear
an earlier possibly written call using the same wire ID.

This source adds v3 emission and per-radio sticky-saturation tables and a
forward 2-to-3 migration, without altering v2 tables, historical wire rows,
snapshots, journals or trigger fencing. The existing v2 export is retained:
Git blob `2a1c5c66d5c42b53b146becf2ed085cffee93f68`, SHA-256
`c8b324628f0ece66967c9ea9025567208bc23c4681f5973f72f7308d5cf732b2`.
No generated v3 JSON is present or manufactured. Schema-only preparation is
nonexclusive: current main `c0e9dc862e4b2c9e962e6f0237d0824e23b0d298` still
publishes v1; other-owner version collisions must be reconciled before promotion.
The held channel candidates were neither inspected nor modified.

The new optional settlement must validate the current grant and query all
radio-scoped persisted exclusion facts in the same actual Room transaction as
delivery. A previously returned public snapshot cannot authorize settlement.
Unproven legacy wires remain wildcards even with a known accepted code or a
later definitely-unsent marker. A single legacy accepted code does not prove
that every earlier physical pool-backoff call was excluded. Known foreign
predicted/actual code membership is retained independently of that wildcard.
For the current valid claim, accepted actual codes supersede only
their own predictions, and earlier accepted same-claim retries remain eligible.

The initial frozen contract caps the new marker budget at 4,096 physical calls
per radio. Its overflow transaction persists sticky radio saturation before
reporting coverage, without fabricating an exact-wire marker or handle. This
does not block sends, but indefinitely suppresses automatic confirmation after
ordinary known sends fill the budget. The coordinator explicitly rejected
that liveness behavior as production parity. This historical hard-cap design is
not accepted or ready for consumer integration. No expiry,
deletion of old receipt/wire history, or automatic reset may turn unknown
history into clean evidence.

The coordinator resolved the additional requirement by authorizing the
schema-unchanged authoritative per-emission journal without a total-row cap,
consistent with existing durable wire/operation history. The local successor
does not create saturation from row count, delete known/unsent rows, or erase
exact handles/replay/conflict facts. Durable storage is not bounded. Radio-wide
comparison uses SQL aggregates; diagnostic results alone are limited to 4,096
rows and report TRUNCATED when omitting history. A complete transactional
comparison can safely settle despite a truncated diagnostic sample. Existing
persisted saturation, if present, remains a sticky wildcard; it is not cleared
by restore, reopen or lease replacement.

The canonical contract owner corrected the incompatible total-cap wording from
`5db00defc6c7ac1e6c22237edd1d48b8d99beb35` in
`0eea3589d989a905f0d57b2ac1937a3b2732dc5e`. A further tracked-wire provenance
revision is still required below. New source controls retain 4,100 known
accepted/definitely-unsent physical markers, replay the earliest exact
resolution, reopen the file, and settle a disjoint accepted code. A second
reopen retains a later unknown marker and explicitly prevents settlement.
These are authored controls, not execution evidence. Persistence errors and
cancellation propagate before any caller may physically emit; there is no
storage-failure fallback allowing an unrecorded send.

Only the exact transport invocation's proven definitely-unsent cancellation
can resolve its marker as unsent. A firmware `DeviceError` is not proof of
zero command writes. An unresolved earlier call therefore remains a wildcard
even after a later backoff call succeeds. Late accepted-code refinement must
identify the exact physical marker and be independent of the product row's
retired mutation grant; it must never clear another marker or renew authority.
The old `recordDirectSendAccepted(attempt, wireId, code)` signature cannot
identify a physical invocation and must not silently resolve every marker
sharing a wire ID.

Trusted policy/manifest were read through the GitHub API at immutable current
main under the memory constraint. `wp-owned` covers the repository/tests/evidence;
`persistence-schemas` admits forward migration and directly required entities/DAO
changes, with real migration and export comparison required. Source snapshots
were published through bounded GitHub REST tree/commit creation, not local Git
decompression, local builds or new worktrees. The genuine KSP export route and
independent fixture are owned by the infrastructure/oracle lanes. New Room
migration, same-wire physical-call and separate-handle concurrency controls are
authored but unexecuted at this stage. Neither schema-only commit establishes
the optional producer implementation or runtime acceptance.

The later local producer draft integrates the additive contract from
`5db00defc6c7ac1e6c22237edd1d48b8d99beb35`; it is not in the frozen schema-only
source. It uses complete SQL aggregates inside the delivery transaction, never
a diagnostic sample as permission. A `RADIO_DELIVERY` operation discriminator
prevents a historical legacy settlement receipt from being presented as new
exclusion-aware settlement evidence. This operation is not product bookkeeping
and is excluded from that public receipt list. New producer controls cover
exact physical retries, contradictory resolutions, retired-grant refinement,
restore/reopen, competing handles, rollback and cancellation. All remain
unexecuted, and the final canonical provenance and actual execution gates remain open.

The service owner identified a distinct pre-dispatch liveness gap: a newly
registered logical wire can be cancelled or lose its grant before any physical
invocation. Absence of a marker cannot distinguish that case from a legacy wire
that emitted without premarking. The agreed additive seam is
`beginDirectAckAttemptRequiringEmissionTracking` and
`registerDirectAckDispatchRequiringEmissionTracking`. They atomically create the
wire and an immutable `EMISSION_TRACKING_REQUIRED` operation, binding the wire ID,
prediction and version 1. Generic/tracked mode crossing, old-wire upgrade and
request-ID collisions are explicit conflicts; no begin-then-tag gap is allowed.
This uses the existing operation journal and changes no schema metadata.

Premarking requires that tracked provenance. A tracked but unmarked wire is
absent from emitted-history diagnostics and does not compete before transport.
All physical invocations still require a committed exact marker first. Legacy
wire-level evidence has no invented physical ID; unknown ancestry remains
independently wildcard even when physical marker rows or accepted metadata
later exist. The new optional source-only legacy-known positive was corrected
to assert explicit Nonsettling under this requirement; the previously executed
legacy settlement suite and its successful-ACK assertions are unchanged.
Additional real Room source controls cover tracked begin rollback, exact mode
replay/conflict, never-invoked positive selectivity, legacy/unsent plus known
acceptance across file reopen, and competing atomic registration/settlement.
None of these new controls has executed.

Independent review found a brace/scope defect in the frozen schema-only
separate-handle test. The DB-only successor repaired that scope, but its
query-callback BEGIN observation does not establish an underlying SQLite attempt:
Room 2.8.5 invokes the callback before delegate.beginTransaction. It is not
accepted blocking evidence. The frozen source and export overlay remain
untouched. The live Room boundary comparison is updated locally from schema
v2/25 entities to v3/27 entities and the four-field physical marker key;
historical v2 migration inputs remain unchanged. Actual v3 KSP output was
subsequently obtained as recorded below, not manufactured. Published main subsequently advanced to
`99f03ad2da235e66c5cbcb58e88ac4d9aa55d2c5`; its published delta contains only
WP-313 resolver tests/evidence, not a database version change. Later product
integration must bind and verify a fresh exact candidate against that base.

A separate DB-only successor is frozen at
`3d2f7177aee225078f5234301721d83cb33b554c`, parent `3e582`, tree
`a6efbaed5c6e4db0e413b0b57de44a3048974b30`, ref
`diagnostic/wp202-physical-journal-db-20261105-1225`. Its six database source/test
paths include the scope repair, live boundary update, complete-history queries,
exact wire/operation lookups and LF normalization. Entity/migration metadata
is unchanged; v1/v2 schema blobs remain `0e6ce7706f75e08c4a70555178b2fdebb98f2184`
and `2a1c5c66d5c42b53b146becf2ed085cffee93f68`, and v3 remains absent.
It contains no optional data producer or canonical contract integration.
The earlier exact schema-export diagnostic
[42078121256 attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42078121256)
failed in its init-script task guard before KSP executed; it produced no
schema/provenance artifact. That failed run, `3e582` and its `ce176` overlay stay
immutable. The infrastructure owner is preparing an independently reviewed
successor route; no old result is transferred.

### Genuine v3 custody and coherent producer successor (unverified)

The corrected dedicated KSP route executed on schema source
`3d2f7177aee225078f5234301721d83cb33b554c`, diagnostic head `7933`, fixture
`e602`, in [run 42080209234 attempt 1](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42080209234/job/127431111349).
Production `:core:database:kspDebugKotlin` actually executed and succeeded;
collector/upload succeeded after the ordinary Gradle aggregate failed.
The ordinary failure was `:core:database:compileDebugUnitTestKotlin` at
`DirectAckExclusionStorageTest.kt:73:37`, where the mixed SQLite bind array
inferred a reified intersection type. The successor explicitly uses
`arrayOf<Any?>`; this is a source repair, not a passing compilation claim.

The genuine generated `3.json` is imported byte-for-byte from immutable Git blob
`e7bf0f62999896bca4d36a2a636096d962b12638`, 132613 bytes, SHA256
`4661fe56e21ac17c6b088fe794d0890294f01ac92825f321e36d1a067c78f28b`.
The original artifact is `13441870104`. Independent custody/schema and static
migration review found all 25 prior entities unchanged, exactly two added
entities, the four-field physical marker key, and matching migration CREATE
statements. This is generated-byte custody and static comparison only, not
Room migration or product acceptance. The failed aggregate remains failed;
no second CI-result/provenance bundle is committed or substituted.

The canonical optional contract is now exact bytes from
`7af7eefe319c31e5b96e2749e1e6296ffcd63aa1`, blob
`886418aa7eaf464b39ae5e3bafc1f83bbe6abae1`. It incorporates the tracked
registration/legacy-evidence contract and the later durable replay
clarifications without changing the declarations of those clarifications.
The producer uses the exact long `RequiringEmissionTracking` names.

Every new resolution ID is atomically journaled in `direct_ack_operations`,
scoped by radio/claim and bound to the exact logical wire, physical emission,
input kind/code and committed result. The first entry is immutable.
`direct_ack_emissions.resolutionRequestId` remains only latest metadata.
Old exact replay does not replace that metadata, rewind wire acceptance or
clear later conflicts. Reusing an ID with another code, unsent input or physical
emission makes that emission sticky Conflict without rewriting the first entry.
Internal resolution/provenance/proof entries are not public product bookkeeping.
This evolves no entity or schema metadata.

The new Room source controls exercise r1 Accepted(123), r2 Accepted(123), file
reopen, r1 AlreadyApplied with latest r2; conflicting code/unsent/cross-emission
reuse; independent physical IDs; conflict retained over another reopen; and
resolution-journal insertion failure rolling back emission/wire/claim updates.
Historical replay requires a retained genuine handle whose original
issuer/lease/emission is persisted. The receiver's new issuer cannot acquire
product grant/attempt authority from this historical refinement. No handle is
reconstructed from a receipt and no restart handle-recovery API is introduced.

A separate unresolved-MAY control closes the old store, cancels its owner scope,
closes the actual file, then opens a fresh store/scope/ProcessEpoch on that file.
It checks the exact old wildcard remains before and after the later unrelated
claim, whose different accepted code must remain Nonsettling without product,
receipt, incarnation, operation or delivery mutation. Restore also retains the
wildcard. This is file-backed store-graph recreation, not an observed OS-process
death or durable ACK-ingress/restart-recovery claim.

Both producer race controls and the DB-only contention control now require an
actual competing Room transaction to fail with SQLiteDatabaseLockedException
while the first writer is held, using busy_timeout=0 on the pinned primary
connection. They release the writer, retry, and assert the committed full-history
result (unknown competitor blocks; tracked markerless registration does not).
Query callback ordering and job-incomplete observations are not the oracle.
These source controls, the replay controls and all current successor runtime
behavior remain unexecuted under the retained local-execution hold. An exact
coherent candidate must obtain its own hosted compilation, discovered tests and
Room migration/reopen results; no predecessor aggregate result transfers.

### Bounded internal-journal query repair (source-only successor to dfe)

Independent review of immutable `dfe282e29b42ac8cf1c19270b14ddf4eb46e202b`
found that receipt filtering occurred after loading every operation for a claim.
Exact bookkeeping replay also loaded that list to locate one request. Further
inspection found two pending-completion presence checks with the same problem.
Uncapped durable resolution history must not become an uncapped receipt working
set even when there is only one logical wire.

The successor moves internal-kind exclusion into a new SQL `receiptOperations`
query, preserving request ordering, public bookkeeping and pending receipt
semantics, and explicit unknown-kind failure. Exact replay uses the existing
primary-key `operation(radio, claim, request)` query. Both pending-presence paths
use a SQL EXISTS result. No production path calls the full `operations` list;
that DAO remains available for existing controls. Public receipt/wire shapes
are unchanged and no journal fact is removed or capped.

`DirectAckReceiptJournalQueryTest` authors a real Room same-claim fixture with
4,101 resolution journal entries and one wire, then checks the two public
bookkeeping/pending records, exact replay, input collision, completed-pending
permission and exclusion-aware delivery. Its query callback records SQL only:
it refuses an unfiltered operation-journal SELECT and requires the filtered
receipt, exact-ID and pending-EXISTS statements. This does not rely on callback
timing to establish transaction contention. The test is authored, not executed.
This repair changes no schema/entity metadata and is frozen separately from
the later optional complete-radio confirmation query.

### Separate complete-radio confirmation snapshot (source-only)

The separately authored canonical optional read capability from
`78336196243c9de8e2f28a11a0817186e1bff80d` is imported exactly, Git blob
`e5c9bfd74d529c1524dbdb6469643859dc44141b`. The implementation is separate
from the bounded-receipt repair and does not change atomic settlement.
`inspectDirectAckRadioConfirmationEvidence(radioId)` runs one four-EXISTS
SQL aggregate across emissions, legacy wires/provenance and saturation inside
`RoomRepositoryContext.read`, which uses a real Room transaction. Every
applicable reason is retained; no row sample, claim sentinel, generation
filter, lease, grant, handle, TTL or authority mutation is involved.

The optional capability reports the exact requested radio. Known accepted
and definitely-unsent physical history alone is not a wildcard; untracked
ancestry remains wildcard even with accepted metadata. Unknown physical
states and accepted rows missing actual codes conservatively report unresolved
physical evidence. A NoPersistedWildcard result is only the complete atomic
snapshot's absence of wildcard evidence, never code uniqueness, delivery
eligibility, permission, or a guarantee of continued freshness.

`DirectAckRadioConfirmationPersistenceTest` contains seven authored real Room
controls: known-code collision without wildcard; all four typed reasons and
radio isolation with unchanged storage; distinct conflict/unresolved reasons;
an unknown physical row deliberately outside the diagnostic cap; original
MAY evidence after actual file reopen without adopting a grant/handle;
two ordinary same-file handles with a held multi-table transition, explicit
SQLite BUSY negative control, committed aggregate and rollback isolation;
and typed SQL/closed-store failures plus cancellation instead of all-clear.
They have not compiled or executed under the local execution hold.
No new schema/entity metadata is introduced.

This suspend snapshot is not a live invalidation/observation stream. Current
`MeshCoreDatabase.open` does not enable multi-instance invalidation; a
single-instance Room Flow is not evidence that a different handle's commits
are observed. Cross-handle/raw-writer observation and UI refresh semantics are
being coordinated separately. No stream, synchronous freshness, App integration,
accessible live warning, or runtime acceptance is claimed by this snapshot.

### Malformed physical-evidence fail-closed repair (source-only)

Further independent review found that immutable dfe/4384 could ignore a
DEFINITELY_UNSENT row with an actual code or without a resolution identity,
and could accept an ACCEPTED row without a resolution identity. The separate
3f179 confirmation snapshot had equivalent blind spots. These predecessors
remain immutable and have not received transferred acceptance.

The successor shares an explicit SQL malformed-physical predicate across
target/competitor unresolved classification and the complete confirmation
aggregate. Every physical row must have UInt-range prediction/actual codes,
a recognized state, and the correct actual/resolution shape for that state.
The accepted-target query additionally requires a resolution identity and
valid prediction/actual ranges. The public complete-radio snapshot and new
exclusion settlement both reject any malformed physical evidence in that
radio with the existing typed `PersistenceStoreError.InvalidData`, without
changing product or evidence. This includes malformed target rows even when
another target emission supplies valid matching acceptance. Other radios
are unaffected.

This supersedes 3f179's authored unknown-state/null-actual warning result:
malformation now explicitly fails as typed InvalidData, not a successful
NoPersistedWildcard or delivery. Valid MAY/conflict/legacy/saturation evidence
retains its typed wildcard semantics; genuine definitely-unsent evidence with
null actual and nonnull resolution remains excluded from uncertainty.

`DirectAckMalformedEvidenceTest` authors a twelve-case raw-SQL corruption matrix
on both the target and a competing claim: unsent with actual/missing resolution;
accepted with missing resolution/null/negative/overflow actual; prediction
underflow/overflow; unknown state; MAY with actual/resolution; and conflict
without resolution. It asserts explicit warning/settlement InvalidData,
unchanged product/incarnation/operation history, no delivery, and radio
isolation, plus a malformed-target/second-valid-acceptance control and a valid
zero-write positive permitting genuine target settlement. These are authored
real Room controls, not execution evidence. No schema/entity metadata,
legacy failure API, consumer file, held source or genuine schema bytes change.

The same successor imports the previously omitted canonical base
`DirectAckAuthority.kt` KDoc bytes from WP-201 7af7eefe, blob
`aee117e9a012ed5f33d9519e04807fe7c78b06bf`. The earlier exact 7af/783 byte
claims concerned `DirectAckRadioExclusion.kt` only; the base authority file
still inherited blob `bfdc036b`. The import changes documentation, not
declarations or implementation, and prevents a composition from falsely
claiming the entire older contract tree was already canonical.

### Separate collector-owned confirmation observation (source-only)

The observer implementation imports exact canonical exclusion-file bytes from
`0fb7fec315cb5d76988dfc8ff2eef9240c72092b`, blob
`facbbe32c0dcd8cc7f9fa3a47b8fd69a852aa24f`. It is additive to the immutable
783 snapshot and separate from the bounded-receipt/malformed-evidence repairs.
Each collector starts a cold serial loop: Inspecting, complete atomic read,
Snapshot, then cancellable `delay(1_000)` before the next Inspecting/read.
There is no initial cached value, replay/conflation/distinct suppression,
detached publisher, global Room builder change or evidence mutation.

The coordinator explicitly selected periodic complete reads rather than
unproven one-handle invalidations. Other-handle/raw-SQL committed state is
eventually observed while collection continues, independent of multi-instance
notifications. Intermediate commits can coalesce. Scheduling, query duration
and collector backpressure add latency; no hard one-second age bound,
every-commit delivery or synchronous UI freshness is claimed. NoWildcard
always describes a historical snapshot, not delivery eligibility or permission.
The active radio graph must share one subscription and fence radio/session
lifetime in its consumer; that integration is not owned by this storage source.

Read failures, malformed evidence, closed-store failure and cancellation
terminate through the existing typed paths. The consumer must show unavailable
on errors or unexpected completion and may retain a historical warning during
Inspecting without a flashing spinner. Polling is not a TTL, reset or eviction
policy and cannot authorize sending or change FAILED/DELIVERED.

`DirectAckRadioConfirmationObservationTest` authors two real same-file-handle
controls without multi-instance invalidation: cold initial read; persisted
state committed through the other DAO and raw SQL handle; full reasons for
MAY/conflict/legacy/provenance/saturation; rollback and radio isolation;
cancelled subscription stopping queries; and a barrier-ordered commit between
initial Inspecting and its query. Real SQL failure and closed-store collection
terminate without a cached clear. All are authored, unexecuted controls.

The inherited e69 base-authority KDoc import is not accepted as generic API
compatibility: review found that aee/7af incorrectly removed the existing
generic prediction-before-return rule. WP-201 is preparing a split canonical
successor preserving generic behavior and restricting accepted-only settlement
to the new radio-exclusion API/activated service. Final composition must import
that correction; this observer does not change legacy production semantics.

## Atomic guarded failure successor: source only

This successor is independently layered on immutable observer source
`a2c1b66d9f567b497f4002c9022b2efc9c0aa713`. It imports the exact WP-201
canonical saturation clarification from
`671343259886e9c7eb0d7a8c836d4e7e8e2a0d1a`:
`DirectAckRadioExclusion.kt` blob `dccd38dea9fc646932e7e04957dc392c40e1ddb6`.
The corrected generic `DirectAckAuthority.kt` blob is
`8e59fda1ada8178ad687d13de1ae98f65fae6bb1`. The previously inherited
aee/7af generic KDoc is explicitly superseded, not retroactively accepted.
Legacy generic settlement still matches each wire's `accepted ?: predicted`,
including an unaccepted prediction before return. Only radio-exclusion
settlement requires accepted physical evidence. Generic production methods
are unchanged by this successor.

The optional `DirectAckRadioFailureAuthority` delegates to one serialized
Room write transaction. Genuine own delivery and protected initially DELIVERED
RESEND baseline win without mutation. Otherwise an exact existing
`RADIO_FAILURE` journal proof returns historical AlreadyApplied, even after
later history, invalidation, restore or revocation; it does not renew authority.
A generic FAILED phase without this proof is Rejected(CONFLICT), never upgraded.
New failure requires current lease, message incarnation/revision and grant,
complete history, the FAILED write, expected-revision renewal and proof insertion
in the same transaction. Proofs use the existing operation journal, are excluded
from public receipt bookkeeping, and are read with LIMIT 2 to reject duplicates.
There is no entity, migration, schema-version or generated-schema change.

One constant-size SQL projection inspects all target physical emissions and
tracking provenance plus full retained foreign history for the radio.
Target MAY, conflict, malformed evidence or legacy ancestry blocks failure.
ANY valid accepted target actual code colliding with another claim's known
predicted/actual code blocks failure; an additional noncolliding actual is not
proof that the blocked emission failed. Foreign unresolved/conflicting/legacy
history blocks a possibly emitted target. Malformed target/foreign evidence is
mapped to the corresponding typed conflict reason for this failure operation;
the existing snapshot and settlement malformed-input typed errors are unchanged.

Sticky saturation ALWAYS blocks every NEW failure, even a tracked target with
no marker or only represented DefinitelyUnsent rows. CoveredBySaturation may
permit an additional physical invocation without a row/handle; the current
contract supplies no overriding target-specific proof. Only on a nonsaturated
radio may a fully tracked markerless/all-unsent target use the known-safe path
despite unrelated foreign ambiguity. Delivery and exact guarded replay retain
their earlier read-only precedence. Nonfailing has nonempty typed reasons,
writes no product status/proof, and renews no grant.

`DirectAckRadioFailurePersistenceTest` contains 11 authored actual-Room controls:
own MAY and earlier MAY/later unsent; target legacy/conflict/malformed evidence;
ANY accepted-code collision plus all foreign wildcard kinds; nonsaturated
no-marker/all-unsent positives with foreign MAY; both saturated markerless and
represented-unsent cases including CoveredBySaturation; exact historical proof
replay and generic-failure rejection; delivery/baseline precedence; malformed
foreign evidence and current-grant rejection; proof-insertion rollback and
post-commit caller cancellation; cancellation during proof insertion; durable
proof through restore/delete/recreate/reopen with no handle reissuance; and
two ordinary same-file handles with an explicitly asserted SQLite BUSY failure
followed by a complete-history Nonfailing retry. Internal proof exclusion from
public receipt bookkeeping is asserted. These controls are NOT executed.

No local build/test, KSP, migration execution, hosted dispatch, runtime/device
acceptance, PR or merge is claimed. Exact canonical blob checks are source
checks only. Older storage-only diagnostic run `42084764103` binds only composed
candidate `74dfb3d08b89a1ae6a8cf04203b00bf276009bba`, not this source.
The retained local execution hold remains in force.

The missing-contact/preclaim path has no positive authority in this successor.
Generic save/upsert/import, incarnation/revision, captured intent, zero-attempt
metadata and empty claim history do not establish trusted birth/no-write proof.
The coordinator selected explicit unavailable/uncertain retain-and-park rather
than generic FAILED or unsynchronized pending deletion. New birth provenance
is a separate design, not implemented or inferred for old rows. A separate
message-level durable collision observation contract is also not included in
this frozen radio observer/failure surface.

### Blocking pre-authority migration-history finding

Source inspection found that the existing v1-to-v2 migration creates empty
claims/wires and backfills only surviving message/pending identities. The
v2-to-v3 migration creates empty emission/saturation tables. Legacy detection
only sees existing wires. A supported pre-authority send can therefore leave
no journal after upgrade, including when its message/radio was deleted before
migration. A delayed ACK sharing a new tracked attempt's accepted code is not
excluded by the existing journal queries. Complete retained SQL history is
not proof of complete physical prehistory.

The service owner independently traced published main
`f8b1d31190f3e02370df147eb95428c9611de2c3`, MessageService blob
`14568ac582098c3fe98bbd2c0d0d3f78ea5365f5`: generic saveMessage followed by
physical session.sendMessage, including queued retry, with no ACK-authority
wire registration. Published database blob
`fddaf0eb11805586e72b3e9ec848aa5fa6fbbee7` is version 1. This is a concrete
unsupported completeness claim, not evidence that old messages were unsent.
No actual migration/reopen execution is claimed for this finding.

Full safety acceptance remains BLOCKED. The coordinator selected a separate
durable DB-wide pre-authority UNKNOWN latch, including empty upgrades and
removed/re-added radios, without TTL/reset and without inventing target birth
proof. That successor requires separately admitted migration/version ownership,
canonical coordination and authentic hosted schema generation. The genuine
v3 schema and earlier immutable source candidates remain unchanged. Two
expected-failing real-file v1 migration/reopen regressions are being frozen
separately before implementation, not included as passing guarded-failure
acceptance. This finding also bounds every earlier COMPLETE/NoWildcard statement
to retained rows; those historical labels do not establish physical prehistory.

## Separate pre-authority migration regression and fixture repair

This diagnostic successor is layered after guarded-failure source
`75127240ba71853726933348a849e8ea03b1cce5`. It changes only the database
exclusion test and this evidence, before any bootstrap production/migration edit.
Two actual-file controls create v1 from its genuine exported schema, represent a
sent outgoing DM with actual code 999, optionally delete the message so no radio
rows survive, migrate through the supported v1-to-v2-to-v3 chain, register a new
tracked accepted physical code 999, close and reopen the same file, and require
persisted exclusion uncertainty after both opens. They deliberately assert the
required safe outcome, not the current unsafe empty-history outcome. They are
EXPECTED TO FAIL against the current producer, but have NOT been executed.
This models the retained/deleted pre-authority-send counterexample; it is not
hardware evidence of a physical send or ACK.

The older exact candidate `74dfb3d08b89a1ae6a8cf04203b00bf276009bba` hosted
run `42084764103` attempt 1 failed in build job `127446949709` at
`:core:database:testDebugUnitTest`: 56 discovered database tests, one failure,
`DirectAckExclusionStorageTest.v2MigrationRetainsLegacyUnknownAndKnownCodesAcrossReopen`,
reported `org.json.JSONException`. Compile succeeded; runtime/migration acceptance
did not. The controller, scope and Swift oracle jobs passed. The execution owner
reported 3329 available discovered tests / 3328 passing / one failing / zero errors
or skips, with incomplete mandatory reports. No result is transferred to a later
source, and no second CI-result bundle is generated or retained.

Source inspection found a concrete fixture JSON fault consistent with that error:
genuine v1/v2/v3 exports omit `indices` on `link_preview_data`, and v2 additionally
omits it on the authority entities. Unconditional getJSONArray("indices") throws.
The shared exported-schema fixture loader now tests presence, then strictly reads
any present array. Required entities/setup queries remain strict and nonempty;
malformed present fields still throw. Existing migration assertions are preserved.
This is a source diagnosis/repair, not independent reproduction of the hosted error.
The log remains authoritative:
https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42084764103/job/127446949709

No schema version, migration, exported JSON, production source, contract, service
or observer changes occur in this diagnostic freeze. The database test file now
has six test annotations: four existing controls and two expected-failing
prehistory controls. All successor controls remain UNEXECUTED under the retained
local execution hold. The original source/run and genuine v3 export stay immutable.

### Separate fresh-install physical-history regression

The coordinator provisionally selected strict safety while the user is unavailable,
not a user-selected trust boundary. Both fresh and upgraded stores must treat
unowned external/pre-authority physical history as UNKNOWN absent genuine proof;
new confirmations may remain unavailable indefinitely while sending continues.
No new local database, radio UUID, reconnect, APP_START or payload import is a
physical-history certificate. No TTL/reset or guessed safe epoch is introduced.

A third, separate expected-failing control creates an actual fresh Room file,
records a new accepted physical code 999, reopens it, and requires uncertainty on
both reads because the supported creation path has no evidence excluding an
external previous installation's code-999 send. It is an adversarial environmental
assumption/control, not physical-radio execution. It is UNEXECUTED, as are the two
earlier v1 migration controls. The database test now has seven annotations.
No production/migration/schema defaults change in this diagnostic source.

The actual local creation callback installs identities/triggers only. Backup
restore erases authority payloads and inserts/remaps imported devices/messages;
it supplies no physical send-history journal or certificate. Device save, ghost
reconciliation and radio deletion likewise establish registry identity only.
They cannot clear a future DB-wide unknown latch or establish a trusted first-use
boundary. The protocol/firmware owners separately reported client-switch delayed
ACK counterexamples; no external source pin or hardware acceptance is advanced
by this storage diagnosis.

Implementing the successor remains gated on negative-control source review,
typed schema/version admission, corrected canonical contracts and supported
hosted schema generation. Existing successful-ACK assertions must not be changed
to success-shaped unknown outcomes or passed with a fabricated production birth/
physical-history certificate. Conditional retained-journal algorithm controls
do not establish confirmation availability on real fresh installations.

The same separate source also supplies the directly necessary owned data-test
wiring for the service owner's public migration control: the Test tasks declare
`core/database/schemas` as an input and pass its actual absolute location as
`roomSchemaDirectory`, using the Android root project's directory provider.
There is no CWD fallback, copied schema, invented DDL, dependency/version change
or test assertion change. The trusted `dependency-resolution-metadata` rule admits
this `update-directly-required-build-wiring` on the existing owned data build
surface. Build/test execution remains unverified. Actual immutable schema blobs
1=`0e6ce7706f75e08c4a70555178b2fdebb98f2184`,
2=`2a1c5c66d5c42b53b146becf2ed085cffee93f68` and
3=`e7bf0f62999896bca4d36a2a636096d962b12638` were independently read through REST:
all have root keys formatVersion/database and setupQueries INSIDE database.
The relayed root-level setupQueries guess was false and was not implemented.

### Separate accepted-code mismatch repair

This successor is based on the fixture-only `94af032ee0a7bbe21fee822c86f63edac8745187`
candidate, not a rewrite of `ce8307fca342121e28d9576141a8df714ecaafcb` or its failed
run 41980426052. The fixture successor changes only the three new controls'
initial rows from the shared SENT default to explicit PENDING; no assertions
or production behavior were changed in that repair.

The separately requested mismatch repair changes current-claim matching from
`predicted == ack || accepted == ack` to `(accepted ?: predicted) == ack` on
each persisted wire, while searching all wires in the claim. It does not erase
historical predicted/actual values, add expiry, gate sends, or prove ACK origin.
Actual-Room controls reject the contradicted prediction without row, revision,
receipt, operation-journal or delivery mutation, then deliver using the accepted
code, both before and after the claim's own failure. Earlier accepted wires
remain eligible with a newer retry either prepared or accepted.

Two existing post-acceptance success controls deliberately change their ACK
inputs/expected committed code from prediction 123 to returned code 456:
`ownFailureAndMatchingAckCommitAtomicallyWithExactTupleReplay` and the terminal
resend loop of `earlyPredictedAckAndExplicitDeliveredOrFailedResendsPreserveTerminalBookkeeping`.
Their success, immutable delivery, exact replay, conflicting RTT, terminal
bookkeeping and independent-mutation assertions remain. This is an explicit
behavioral correction, not unchanged parity evidence. The latter method's
separate early-before-response case remains a storage capability control;
it does not authorize consumer speculative settlement before actual acceptance.
The existing retry control still asserts retention of all predicted/actual
history, which is not the same as all those codes remaining eligible.
No mismatch runtime acceptance is claimed until its own exact-head hosted run.

No producer-side ACK-origin proof is established. The pinned source parser
`MeshCore/Sources/MeshCore/Protocol/PacketParser.swift:276-286` emits only the
four-byte ACK code and optional trip time. `MessageSentInfo` carries route,
expected ACK code and suggested timeout
(`MeshCore/Sources/MeshCore/Events/MessagingPayloads.swift:6-21`). The Swift
service registers a speculative ACK entry before `session.sendMessage`
(`MC1Services/Sources/MC1Services/Services/MessageService+SendDM.swift:75,105`);
the session subscribes, awaits `transport.send(data)`, then waits for the
message-sent result (`MeshCore/Sources/MeshCore/Session/MeshCoreSession+EventWaiting.swift:158-173`).
The ACK has no wire-request or message identity. `AckCodeBuilder` documents
recipient exclusion and the two-bit attempt mask
(`MC1Services/Sources/MC1Services/Services/AckCodeBuilder.swift:18-30`), so
distinct dispatches can have identical raw ACK codes.

The Android session's `onWriteAttempt` callback runs immediately before the
transport send
(`android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/session/SessionCore.kt:583-591`); its
`MessageWriteDispatch` only classifies cancellation as definitely-unsent or
possibly-written, not as a transport emission receipt. A before-send persisted
marker therefore proves possibility, not actual emission or ACK origin.
13a/a59 reviewed a conservative wildcard proposal: reject later same-radio
dispatch admission until the earlier possible wire's actual code is known,
and retain code-collision tombstones. A timeout/error/cancellation before
`MessageSentInfo` leaves that wildcard unresolved; because no source-proven
ACK horizon or definite-non-emission outcome exists, strict stale-ACK safety
would permanently block later sends for that radio. That is a substantial
unapproved liveness change, not a bounded producer fix. No ambiguous ACK is
attributed by latest tracker, DTO, request UUID or predicted code, and no
wildcard/dispatch-admission implementation was added. This remains a concrete
blocker requiring either a verified transport/protocol guarantee or an explicit
product decision on permanent fail-closed behavior. No #569 consumer code was
edited or integrated.
