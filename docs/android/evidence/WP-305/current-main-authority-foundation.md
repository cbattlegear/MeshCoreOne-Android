# WP-305 current-main device-write and session-seal foundation

## Identity and scope

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Base and trusted admission revision: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
- Source specification: `db14559b39d32322b06477c6ae676112f583db50` (unchanged).
- Producer session: `37ae5450-e6b6-4e1d-b22a-63e695f73f0b`.
- Branch: `cbattlegear-onboarding-authority-foundation`.
- Candidate: the commit containing this document; no PR, push, hosted dispatch or merge requested.
- Acceptance relevance: prerequisite for `WP-305-behavior` and `WP-305-boundaries`, not completion
  of onboarding, its tip/UI work, Settings, device acceptance or another WP.

The coordinator assigned an independent clean-current-main foundation. Runtime/device owner
`36c8c079-3643-4bc2-876b-f4c80c3bdf4d`, storage owner
`896808b8-b03c-48e0-b93f-870598b885bd`, onboarding owner
`2f22fa7d-a4d6-4bd7-bc99-30c458e7f517`, Settings owner
`244fb3ac-2ee7-4761-874d-9617d4935209` and direct-ACK owner
`f8058663-e138-4456-9d4d-6ad11522824c` were consulted before overlapping edits.

## Source custody and adaptations

### Wired staged Flags prerequisite on the current-main foundation

The successor of c3cd86d0e2660574bf89ca5318c82fe84112c971 transfers exactly two
producer-approved files from 800df719216f378459883ed87b33b4fd4c652c7a:
OnboardingFlags.kt and OnboardingFlagsTest.kt. Both working blobs match the producer
endpoint, with all 26 authored controls, including typed uncertain IO/cancellation errors,
original failure identities, real preference readback, explicit seal/reject races and
shutdown/abandoned-waiter draining. No historical ancestry or other producer files are
imported. Historical runs are not successor execution evidence.

The four acknowledged OnboardingFlagStore overloads and the completion/reset/retirement
lock and exact-attempt semantics are extracted from that producer's feature state. The
current-main adaptation deliberately omits its preset-target receipt and navigation
publication machinery: only WithoutDevice/Restored receipts are present. Completion
and reset await persistence; retirement synchronously rejects pending attempts.
The existing synchronous setter remains supported. All original feature state cases
and assertions remain, with blocking test adapters added only around the newly
suspending calls.

The actual no-device and preset-finish consumers now await completion rather than
queueing a plain optimistic Boolean. Completion failure is visible through their
existing error surfaces; cancellation propagates. Preset success ticks wait for the
acknowledgement, duplicate no-device confirmation is suppressed while pending, flow
disposal retires its exact attempt, and process shutdown drains Flags before cancelling
the process job. Five added coordinator methods cover pending acknowledgement,
duplicate application/confirmation, visible failure/retry and explicit retirement.
Production load now reaches the c3cd ShowOnceTips staged/finalized/exact-readback APIs.

Storage owner 896808b8-b03c-48e0-b93f-870598b885bd confirmed the foundation already
contains the transaction-local guards; no storage production edit is needed. Its
218b7f5f940dac8775489dceadb82f8845eeb891 queued actual-Room control is composed
method-only, preserving all three add/remove/preset families, full DTO, all channels,
membership, revision, rejection and no-publication assertions. Imports are deduplicated.
The sole method adaptation nests cleanup finally blocks so an executor-termination
assertion cannot skip repository/database close.

Fresh trusted WP-305 admissions accepted every changed feature-owned path and the
related app consumer/test paths. The documented native-Windows readiness command
`C:\Python312\python.exe tools\android-port\controller\ci.py preflight --local --state
$env:ANDROID_CI_STATE --output
C:\Users\camer\.copilot\session-state\6e767589-3de4-4390-a618-1fafe7293cbb\files\preflight-staged-flags-01`
passed. `C:\Python312\python.exe tools\android-port\controller\validate.py`,
`C:\Python312\python.exe tools\android-port\portmap.py`,
`C:\Python312\python.exe tools\android-port\controller\verification_config.py` and
`git diff --check` passed. These are source/configuration checks, not Kotlin execution.
Local Gradle remains held by local-check:f516a7e271f444dd9cb2efcc8b995a16.

Exact c3cd run 41982352370 remains active while this successor is authored; its ref is
unchanged and no duplicate is dispatched. This wired successor requires independent
source review and its own fresh exact-head hosted verification. Device-token preset
consumer/receipt integration, Settings Repeat snapshot integration and the separately
owned OCV ABA repair are not supplied or accepted by this slice. No full onboarding,
device, hardware or whole-port completion is claimed.

### Bounded producer tip prerequisite composition

Exact 79028d017892ff7e24c9ac3432a24c9ecb1558e4 run
[41979048695](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41979048695)
finished SUCCESS before this successor was authored; that result is not transferred.
The successor applies only the producer-attributed 41552-to-ba161421a8b2b67525de240aad73111eaf3d4b3e
diff for ShowOnceTips.kt, ShowOnceTipsTest.kt and SharedUiComposeTest.kt.
Producer 2f22fa7d-a4d6-4bd7-bc99-30c458e7f517 confirmed exact custody and no tip overlap
with its separate historical 65d uncertain-readback repair. Fresh trusted capabilities admitted
the production path and both test paths. No AppFlags, feature, navigation or staged writer
is imported, and no completion of the full onboarding foundation is claimed.

The exact three-file slice adds atomic completion plus tip donation, staged/finalized attempt
identity, atomic removal of stale finalized identity on unattributed completion, and original
Chats/Nodes/Map host eligibility. All 20 tip methods and 22 SharedUi controls, including
failure/reopen/reset/backup cases and original assertions, are preserved verbatim from the
producer endpoint. Existing backup implementation is unchanged. Producer old-base run
41959590903 is provenance only, not current-base acceptance. This successor requires its own
fresh exact-head hosted validation; existing active refs and local Gradle hold remain untouched.

### Current-main ancestry integration

Exact beb97a679707730ee51208c0045cae7bffb0528c run
[41973550183](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41973550183)
finished SUCCESS, including Linux Android, controller, Swift codec and SwiftData restore/export
jobs. The actual reported methods include initializationKeepsItsRuntimeLeaseWhileTheActualRoomTransactionIsHeld,
differentAliasWriterRequiresRadioReadmissionBeforeEditorCanClearTheLastScope, both CLI TX
readback cases and the queued-successor runtime barrier. CLI/writer/persistence/notification
and onboarding suites reported SUCCESS. Compiled test source bindings include CLI blob
2be161afdb35867b87ac86bd21f2713382e222e6 and writer blob
0351114e0a77c9b110620a366fd8a381186f9ee4, matching that exact committed head.
Authoritative results remain in the hosted log, not a second retained CI-result bundle.

The owned branch then merges exact current main f93e2012049feb4d6be728f32bd661665d71161c
through normal ancestry. Git resolved the merge without conflicts: main contributes only its
notification-enrollment and LOS changes/docs relative to the prior candidate. Foundation and
CLI controls are preserved. This changed tree requires its own fresh exact-head hosted check;
beb97's success is not transferred. Settings integration, device and whole-port acceptance
remain distinct.

### Follow-up: actual committed CLI observer seam

Exact fd6d50bc3 hosted run 41971269263 executed 2,012 App tests with one failure:
the newly added negative-case wait for a committed 22 readback timed out. The original
positive typed-TX case passed, as did writer, persistence and notification suites.
SettingsService.setTxPowerVerified checks mismatched readback and throws before publishing
DeviceUpdated; therefore the negative case intentionally has no settings commit to observe.
The bounded successor removes only that new three-line wait. Original negative wire/readback,
no-OK, Room/live 22 and no-unexpected-power assertions, event drain, and the positive 17
committed-write observer/wait remain intact. This corrects the added test requirement rather
than requiring a fake successful commit. The CLI producer was notified to serialize this repair.
Producer correction b26999d04978efab83036014d46681d7b52ac4b6 independently supplied the
same removal plus explanatory comment; the composed CLI file exactly matches its bytes.
No production changes or transfer of fd6 results to the successor are claimed.

Exact `dcfdc5e2ac6672cde8489eae19125847b9d3cec7` hosted run
[41967846619](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41967846619)
executed 2,012 App tests with one failure: the typed-TX CliSettingsReadbackTest at line 177.
DeviceWriterAuthorityRoomTest, DevicePersistenceAuthorityRoomTest and
DeviceNotificationAuthorityRoomTest each reported END SUCCESS, including the repaired
initialization witness. This is actual dcfd evidence only, not a successor result.

CLI owner `bd67ff1a-bae4-46aa-a27c-4b31128744ab` supplied test-only
`ea3c8dcd0c8c32bc94ed3dc763d828bf935f0d7f`, parent exactly 688b5eb19. Its CLI test file is
transferred byte-identically onto dcfd (direct comparison passed). The previous recorder
overrode saveDevice, which the versioned editor no longer calls. The corrected recorder
delegates saveDeviceIfUnchanged to actual Room and records only true committed results;
explicit eventual checks observe those commits. The positive 17 and negative 22 actual
readback, wire-command, no-fake-OK and Room assertions remain unchanged. No production or
CLI consumer behavior is edited. Fresh trusted WP-305 add-or-update-related-test admission
returned reviewed-support-test and git diff --check passed.
Independent successor source review and exact-head hosted execution are separate gates;
the original failed runs and local Gradle hold remain unchanged.

### Follow-up: held-initialization Room command witness

Exact `688b5eb19f35adf2c9557a86573e077299cbec8a` hosted run
[41964562525](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41964562525)
failed: 2,012 App tests executed, two failed. The new initialization lease control timed out
after one minute; the separate CliSettingsReadbackTest typed-TX case failed at withCli line 177
and was referred to its current producer. Neither result is attributed to another head.
The authoritative hosted log is retained by Actions, not duplicated in this repository.

The initialization control's external `async(IO) db.withTransaction` blocker proved entry into
a separate transaction but did not prove initialization submitted a Room command. The timeout
does not identify a production deadlock. The bounded successor uses the same dedicated
Room transaction-executor command barrier as the existing cancellation/reconnect controls:
beforeInsert arms the barrier under the runtime lease, then the test waits separately for
insert entry and the actual submitted Room command. An undispatched disconnect queues at
the real runtime gate. All original blocked-retirement, unchanged-snapshot, disconnected/no-live
and no-late-activation assertions remain; an additional target-row assertion proves the
queued insert really committed before retirement. The test releases the command and joins
the executor before closing runtime/store/database. No production behavior changes.
Runtime-owner review of `ebb62314694b5332809f97dda4ac33cba616e54d` found the bounded
oracle/topology sound, source-only, and requested unconditional resource cleanup if the worker
termination assertion fails. A subsequent cleanup-only commit nests finally blocks so runtime,
repository and database close are each attempted even if an earlier close or assertion throws;
the worker-termination assertion remains. The diagnostic targets that successor, not ebb.

Fresh `reserve.py admit` returned reviewed-support-test. `git diff --check` and pinned
`C:\Python312\python.exe tools\android-port\controller\validate.py` passed (1,866 reference
files, 64 WPs, 178 edges). These are source checks only. The existing local Gradle hold and
all prior immutable refs/runs remain unchanged; a fresh exact-head diagnostic is required.

### Follow-up: acknowledged region-removal composition

The successor of `e83fb622f550ed74df0fb925a398c9c8d38764c4` composes only the
producer-approved contract/editor seam from `725495e42bc6279bd9af5c1559b412f0919b928a`:
`KnownRegionRemovalOutcome { Removed, Retired, NeedsScopeReconciliation }` and the
three-argument `persistKnownRegionRemoval(region, originalToken, acknowledgedDefaultFloodScopeName)`.
Runtime/device and Settings owners confirmed exact custody and no competing edits.
The existing two-argument API remains unchanged. Each authoritative CAS transform validates
the normalized projected default against the acknowledged radio default before persistence
or live publication; mismatch returns NeedsScopeReconciliation without treating it as an
unexpected diagnostic failure. The foundation's sessionEdit/deferred observer notification
ordering remains intact. No Settings collector, adapter, service-replan or OCV code is imported.
Settings owns those consumer changes at `d52b9a925a8413037c929fa97dd3b7ce40816545`.

The added Room control
`differentAliasWriterRequiresRadioReadmissionBeforeEditorCanClearTheLastScope` is method-text
identical to corrected producer `350eb42a16fd02cf5e8431543a5dceb02f1e99d0`, verified by direct
comparison. It retains all original assertions and the indexed-channel reset/preassertion;
the writer class now has 19 authored controls. Both alias orders and enabled/disabled scope
families use actual protocol fake-radio readback, competing Room CAS rejection, no rejected
publication and explicit radio re-acknowledgment. This is manual ACK evidence design, not
execution of the Settings replanning queue. The 16 storage controls already in the foundation
remain unchanged. Producer-reported 350 writer26/persistence16 success is not execution
evidence for this different current-main adaptation.

Fresh current trusted admissions returned reviewed-support-consumer for the existing contracts
and editor paths and reviewed-support-test for the writer test. The pinned command
`C:\Python312\python.exe tools\android-port\controller\ci.py python --output C:\Users\camer\.copilot\session-state\6e767589-3de4-4390-a618-1fafe7293cbb\files\python-region-ack-01`
passed manifest/provenance/notices/parity/semantic checks, scaffold 16/16 and parity 110/110,
zero skips. `git diff --check` passed. These checks cover the source working diff, not Kotlin:
the existing local Gradle hold remains untouched; exact-candidate compilation, Room execution
and independent composed-seam review are outstanding.

### Follow-up: queued reconnection failure authority

The independently reviewed foundation `8344344ff180faa3754928edd42c9e38e69adad1`
remains immutable. A subsequent bounded repair checks the captured connection revision and
reconnection coordinator generation, plus closed state, **inside** `deviceCommitAuthority`
before `handleReconnectionFailure` changes counters, owners, device state or publishes DISCONNECTED.
A stale waiter returns without claiming or closing any owner; successor connect retains its
normal captured-predecessor cleanup. Existing accepted-failure cleanup and cancellation paths
are unchanged. Runtime owner `36c8c079-3643-4bc2-876b-f4c80c3bdf4d` confirmed this repair is
owned here and has no competing edits.

The added real-manager `RuntimeSeamTest` control holds the actual write authority, queues a
successor WiFi connect first and an old failure second, then holds successor transport connect.
It asserts CONNECTING is not overwritten, no DISCONNECTED transition/loss/retry effect occurs,
predecessor services close once, and the successor reaches READY with a new token/live device
without closing its services or transport. All previous cases and assertions remain.
Before the repair, the queued failure would publish DISCONNECTED and increment rebuild failures
before its later ownership check; this is a source-level discrimination, not an executed result.

Fresh pinned-Python `reserve.py admit` calls admitted ConnectionManager as
`reviewed-support-consumer`, RuntimeSeamTest as `reviewed-support-test`, and this evidence as
`wp-owned`. `git diff --check` and
`C:\Python312\python.exe tools\android-port\controller\validate.py` passed (1,866 reference files,
64 WPs, 178 edges). These results apply to the follow-up working diff, not Kotlin execution.
Local Gradle remains held by `local-check:f516a7e271f444dd9cb2efcc8b995a16`;
no recovery, bypass, Kotlin compilation or test execution occurred. Exact follow-up hosted
execution and independent review remain required. Settings' separate three-argument removal
API/ACK guard is neither changed nor claimed integrated.

Selected hunks, not ancestry, were extracted from producer-approved immutable sources:

| Source | Selected behavior |
|---|---|
| `9972903c07f0bd500c1c4c1e74a39b3f73fb331c` | Versioned device contracts/helper, editor, Room context/delegation, runtime/reconnection authority, DeviceService OCV targeted writes, simulator insert-only seed, coupled test harnesses and RX controls |
| `5d7847b8ec612ec41a742ddf8b0a3efc4d2dc8bc` | DeviceRepository CAS/per-device revision and normalized region guards; 16 authored storage controls |
| `b2beb5f257b36cd3ccce7bb641f7b2ea3747e3fc` | OnboardingCompletionAttempt including isSealed; four runtime token/seal controls |

Only the added device hunk in DeviceAndContactPersisting was taken. Newer main contact APIs,
notification contracts/tests, message authority, DAOs and database schema are untouched.
No prohibited/held source was inspected or imported.

This is **not byte-equivalent to those sources**, nor does their hosted execution validate this head:

- AppConnectionPort exposes awaited `updateDevice(SelfInfo, String?, SessionToken): Boolean` and
  `updatePathHashMode(UByte, SessionToken): Boolean`; unsupported implementations throw rather than
  returning fake success. Production adapters retain the original token through the editor.
- Live publication stays inside the successful Room commit's runtime lease. Only the resulting
  known-region observer notice is deferred until the Room/runtime/editor locks have all released.
  The deferred notice also runs during late cancellation unwinding and retains its token/sequence.
- Runtime initialization insert and existing-row/identity conditional writes also retain the
  generation write lease across their actual Room work, not only pre-commit eligibility checks.
- OCV callbacks update only OCV fields under the original token, preventing an independent OCV
  writer's delayed full DTO from erasing newer preset/path-hash/live fields. The Settings event/ACK
  collector and UI are unchanged.
- The legacy optional clear-default callback remains available and is invoked outside write locks.
  Token-bound region removal remains persistence-only; Settings owns verified radio changes.

The Room authority registry is process-local and shared for stores using the **same MeshCoreDatabase
object**, matching the process-store topology. This is not a durable revision scheme across different
database objects/processes. Schema version 1 is unchanged here, not asserted as a future invariant:
the direct-ACK producer's independently authorized v2 migration must compose later. Its close/restore
and message-authority hunks are disjoint from this foundation.

## Admission and reservation result

Each extracted/edited production Kotlin path was evaluated with:

```text
python tools\android-port\local\reserve.py admit --wp WP-305 --path <canonical Git path> --operation modify-related-consumer
```

Result: `reviewed-support-consumer`, including runtime, connectivity, data, services, contracts,
app adapters and the narrow OCV callback. Existing-consumer-only, necessity, original-case/consumer
preservation, semantic-overlap coordination and exact-candidate review invariants apply.
Each added/updated Kotlin test path used `add-or-update-related-test` and returned
`reviewed-support-test`. This document and the deviation addition used `modify`, returning `wp-owned`.

The initial typed claim was actually attempted with this session, base and assignment:

```text
python tools\android-port\local\reserve.py claim --wp WP-305 --session-id 37ae5450-e6b6-4e1d-b22a-63e695f73f0b --authorization "Implement a bounded current-main device-write and session-seal foundation needed to finish onboarding, independently of other active lanes." --base 41552c85b659396111cee6b0e0f70c404ac5a8ee --path docs/android/evidence/WP-305/
```

Result: `BLOCKED: Existing WP lease is bound to different source/base/manifest/backend`.
The historical WP-wide receipt belongs to a different producer/base. It was not edited, evolved,
released, recreated or impersonated. Trusted AUTOMATION.md's local coordination contract and the
coordinator explicitly permit isolated capability-admitted authoring after retaining this result.
No new autonomous ledger worker, successor, live execution or merge authority is claimed.

## Actual local results

Host: native Windows; `python --version` reported the pinned `Python 3.12.4`. The documented
verification-venv path was absent, so the existing pinned `C:\Python312\python.exe` was used.
The existing same-host ANDROID_CI_STATE was passed explicitly; no tools were installed or recovered.
Each controller invocation used a fresh absolute session-private output directory.

| Exact command | Actual result |
|---|---|
| `python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output C:\Users\camer\.copilot\session-state\6e767589-3de4-4390-a618-1fafe7293cbb\files\preflight-foundation-01` | Exit 0; documented scaffold readiness check executed |
| `python tools\android-port\controller\validate.py` | Valid: 1,866 tracked reference files, 64 WPs, 178 dependency edges; reference pin unchanged |
| `python tools\android-port\portmap.py` | Exit 0; many-to-many provenance derived without editing shared progress |
| `python -m unittest discover -s tools\android-port\tests -p test_consumer_support_capabilities.py -v` | 41 discovered/run/passed; no failures/errors/skips |
| `python tools\android-port\controller\ci.py python --output C:\Users\camer\.copilot\session-state\6e767589-3de4-4390-a618-1fafe7293cbb\files\python-foundation-02` | Exit 0: manifest, provenance, pinned notices, parity accounting and semantic checks; scaffold 16/16 and parity 110/110 passed, zero skips |
| `git diff --check` | Passed |

These are source/Python results, **not Kotlin compilation, Room execution or product acceptance**.
No duplicate hosted CI-result bundle is retained here; any future exact-commit hosted job/log is
the authoritative automated result.

## Authored Kotlin controls, not execution evidence

- DeviceWriterAuthorityRoomTest: 18 controls (17 exact independent foundation controls from 997,
  plus the new held-Room initialization/retirement control).
- DevicePersistenceAuthorityRoomTest: 16 exact controls from 5d, including staged SQL rollback,
  real Room transaction requests, per-device/shared-store authority and normalized aliases.
- DeviceNotificationAuthorityRoomTest: 3 new controls for public AppConnectionPort old/new tokens,
  callback reentry into Room/runtime/the same editor, and exactly-once committed late-cancel notice.
- SessionBoundDeviceEditTest: 8 exact token/replacement/durable-error controls.
- RuntimeSeamTest: the four source token/seal controls plus a new concurrent explicit reject/seal
  CAS control; reconnect/disconnect seal assertions are strengthened.
- Existing reconnection, data-device and pairing cases are retained. The source's three native
  reconnection and three data-device controls are carried with their necessary harness changes.
- All current-main RX cases remain. Their pauses move to the real CAS or post-commit notification
  boundary; a retired pre-commit edit must leave Room unchanged. The source's committed-cancellation
  RX control is also carried.

Seven writer-source controls requiring the separately owned newer Settings radio/region helpers
are **not imported** into this clean-main foundation. They are not existing main tests, exclusions
from acceptance or passing controls; their producer retains them. No current-main test is deleted,
disabled or weakened to bypass a missing dependency.

## Remaining gates

Local Gradle remains held by `local-check:f516a7e271f444dd9cb2efcc8b995a16`; no invocation,
bypass, recovery, cancellation or receipt mutation was attempted. Kotlin/Room/runtime tests,
compilation and lint have **not run on this candidate**. Prior producer counts are source custody,
not passing evidence for this adaptation.

The immutable candidate must receive independent producer review before hosted validation.
No hosted ref was advanced/cancelled, no PR opened and no merge requested. Physical radio,
Android device, signing, release and human/legal acceptance were not performed.
