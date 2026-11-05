# Direct ACK storage authority

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

Logical claims precede dispatch. Predicted and returned codes from all wires on
the same live claim remain eligible. Only typed retry/status/send-count and exact
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
