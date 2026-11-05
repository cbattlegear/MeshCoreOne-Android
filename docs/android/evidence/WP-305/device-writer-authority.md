# WP-305 device-writer authority support repair

Repository: `cbattlegear/MeshCoreOne-Android`.
Received base: `fc7be50b92c275d495932df6e978e95c553ea684`.
Parent: `94f2fca58f434a5d1bcdef94e0635c2e07cbf201`.
Read-only Swift source: `db14559b39d32322b06477c6ae676112f583db50`.
Producer branch: `cbattlegear-device-write-authority`.

This is an isolated repair for the existing onboarding producer, not a duplicate PR or a WP completion
claim. Onboarding flow/completion receipts and the separately assigned Settings hash acknowledgment
implementation remain their producers' work.

## Authority and source controls

- The received worktree was clean and exactly matched the assigned base.
- The onboarding producer confirmed no newer/overlapping core edits.
- Trusted `reserve.py admit` accepted each edited production support path as
  `reviewed-support-consumer` / `modify-related-consumer`, each test path as
  `reviewed-support-test` / `add-or-update-related-test`, and these per-WP documents as `wp-owned`.
- The attempted ordinary `claim` refused with
  `Existing WP lease is bound to different source/base/manifest/backend`.
  The historical claim was preserved unchanged. Trusted `AUTOMATION.md` explicitly distinguishes
  this historical advisory mismatch from a live source edit lock.
- The source-defined `transfer` accepted truthful onboarding-origin and repair-producer identities,
  returning `advisory: true` and `file_edit_blocked: false`. This is attribution only, not a lease
  transfer, successor, execution authorization or acceptance.
- The Settings owner transferred only the disjoint OCV callback/port/fake hunk; their hash
  acknowledgment helpers, service/event handling and UI implementation were not edited here.
- The coordinator admitted the adjacent RegionManagement consumer repair after the Settings owner
  confirmed no overlapping changes.

## Behavioral controls

`DeviceWriterAuthorityRoomTest` drives the actual AppContainer, ConnectedDeviceEditor,
DeviceService, runtime token guard and Room repository. Its interception wrapper only pauses calls
and invokes real competing writers; it delegates CAS decisions and storage failures to Room.
Cases cover both OCV interleavings, late live publication, delayed OCV versus completed preset,
ordinary editor serialization, actual successor activation, same-token watermark rebasing and the
actual Settings region consumer's default/unrelated/concurrent/successor/failure paths.
Atomic inactive initialization/repeated simulator seeding is also exercised against an existing
active successor, a committed preset/hash/OCV edit and an attempted CAS activation bypass.
A held real Room transaction also establishes that connection activation's ownership guard is
evaluated after the wait, so an already-retired queued activation cannot replace the active successor.

The existing session-bound and original pairing cases are retained. The test-only offline fixture now
seeds the authoritative device row before requesting a persisted edit; it does not fabricate a
successful production save for an absent row.

## Verification state at initial publication

- `python tools\android-port\controller\ci.py preflight --local --state <same-host-state> --output <fresh-absolute-output>`:
  passed with actual pinned `C:\Python312\python.exe` (3.12.4) and the existing Windows toolchain state.
- `git diff --check`: passed.
- `python tools\android-port\controller\validate.py`: passed; 1,866 frozen reference files,
  64 work packages and the unchanged source pin.
- `python tools\android-port\portmap.py`: exited 0.
- Kotlin/Android execution is **unverified** at this initial source publication. No local Gradle
  runner was started or recovered; the retained execution owner was not touched. Hosted
  exact-candidate job status/log, not a second retained CI-result bundle, is authoritative.

No physical radio, mounted Compose/device, hardware, signing, release or complete WP acceptance is
claimed. The region controls invoke the same real consumer functions used by the composable but
do not claim a mounted Settings UI run.

## Review-driven authority follow-up

The exact `7cff2cca21bfa906687980bdf653ef0727376352` hosted run
<https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41939043733> failed.
Its app suite discovered 1,915 tests with 14 failures; these are not acceptance evidence.
Confirmed causes included delegated stores losing the versioned-persistence interface, region
fixtures seeding a field intentionally preserved by legacy full-row saves, and a suspended
Room-transaction fixture timing out. Independently owned preset/onboarding fixes must compose
with this repair; no assertions were removed to hide their failures.

Shared contract prerequisites are `40e6c1e98ac7c4b9499bb2524378c9a9b3f6b4ab` and
`bd9e98e9d8ba53d4e11046cb67e8853b0d7c15f8`. The storage producer's immutable
`90cd18e789673209219c912a382963047b50fc5c` was composed here as
`0e3fbadd3` without importing that producer's ongoing work. It scopes revisions per device,
shares authority across stores using the same database instance, persists generic region edits
exactly, resets only removed region channel scopes atomically, and enforces the ticket's
suspending authority lease around the actual transaction. Its twelve separate real-Room
`DevicePersistenceAuthorityRoomTest` methods are authored controls, not executed results.

The runtime/editor follow-up uses the same coroutine mutex for activation commits, token-bound
editor commits and owner-retiring state transitions. A Boolean guard is not claimed to fence
retirement by itself. Lock ordering is runtime authority before the database mutex; no JVM monitor
spans suspension and teardown waits occur outside the authority mutex. Ordinary Settings events
retain their originating tokens. Post-commit caller cancellation still reconciles live fields and
the actual RX region consumer for the original token; pre-commit cancellation does not write.
The strengthened retired-region control asserts both the old Room row and channel scopes remain
unchanged after a successor loads, rather than merely checking that stale live publication fails.

The Settings owner retained service/hash lifecycle ownership. Its bounded default-scope
acknowledgment and selection/removal queue follow-up is explicitly outstanding; the new awaited
default editor method is its prerequisite, not evidence that radio verification awaits persistence.

Fresh Windows readiness and the actual `controller\validate.py`, `portmap.py`, and
`controller\verification_config.py` source commands passed without Kotlin execution. An earlier
mistaken `tools\android-port\validate.py` path and `portmap.py validate` invocation were rejected
and were corrected; neither is test evidence. Local Gradle ownership and all user holds remain
untouched. Hosted exact-candidate jobs/logs remain authoritative; no second CI-result bundle is
retained, committed, uploaded, hashed or substituted for the hosted result.

### Corrective commit-boundary review

Source review of `4a01c6c5fc23ebed20a29fc6b90dff040ccddb89` found two remaining gaps:
the outer `NonCancellable` section could defer cancellation while waiting to enter Room, and BLE
state/clear setters plus restoration-route generation replacement did not acquire the new runtime
authority gate. That candidate is therefore provisional, not accepted.

The storage producer explicitly transferred the single conditional-save `afterCommit` callback
hunk back to this producer, retaining its independently running diagnostic branch unchanged.
The corrective contract carries `onCommitted` on the ticket; Room invokes it synchronously after
the real commit and revision updates, under the same runtime lease and existing commit worker.
No broad non-cancellable save wait remains. BLE delegate setters, their pairing call-through and
restoration generation construction become suspending gate participants; all actual callers and
the reconnection test double are wired without asynchronous success-shaped fallbacks.
The same gate now also protects generic state publication (including adoption fallthrough)
and WiFi reconnect cooldown publication. Synchronous issue-only publication continues to change
only the issue under the short runtime monitor; it cannot change the active writer predicates.
Additional real-Room controls cancel a queued commit before its blocking transaction ends, cancel
inside the actual committed RX publication callback, and queue both reconnect state and live clear
behind a suspended Room commit. These remain authored controls until exact hosted discovery.
The corrective source passes `C:\Python312\python.exe .\tools\android-port\controller\validate.py`,
`C:\Python312\python.exe .\tools\android-port\portmap.py`,
`C:\Python312\python.exe .\tools\android-port\controller\verification_config.py` and
`git diff --check`. These are source controls, not Kotlin compilation or Android test execution.

### Guarded reconnection retirement correction

Independent review of immutable `8eefab22eec5652d3b8435a72a27441320d8405c` found that
new suspending state setters preceded the coordinator's cleanup boundaries, and a queued timeout
could retire its reconnect claim before waiting on unconditional state/clear operations. Pairing
cleanup had the same check-before-suspension problem. The original `4a01` no-findings review was
subsequently withdrawn because its tree binding was incorrect; neither review is an acceptance gate.

The corrective delegate captures runtime revision and owner identity and submits one guarded
state/device transition. The runtime reacquires its authority gate before checking that captured
identity and the original reconnect cycle/timer. The timeout retires its claim inside that guarded
operation, not before waiting. A stale timeout does not clear a successor or emit a lost notification.
Entry/completion transitions now run inside cleanup boundaries; cancellation resolves the entry's
teardown receipt and clears rebuilding state. Teardown also checks its original claim under the
runtime gate. Pairing cleanup checks its captured runtime and operational state under the same gate.
Three deterministic coordinator controls cover entry/completion cancellation and queued timeout
supersession; a production manager/Room control queues a switch ahead of both timeout and pairing
retirement and asserts that both old operations reject the successor.

The persistence producer transferred its exact six-hunk stronger-boundaries test patch: rollback
guards now inspect staged SQL device/channel state before refusal, and the lease test observes the
actual Room transaction-executor request, not just lease entry. All twelve test methods are preserved.
These successor controls are authored, not executed. The isolated exact `8eef` hosted diagnostic
is run `41943682256` on `diagnostic-device-authority-8eefab22e-7dc0b9a1`; it remains unmodified and
cannot execute this corrective successor. No second CI-result bundle is retained.

That exact `8eef` diagnostic failed at `:app:compileDebugKotlin`: the producer's token-overload
plumbing omitted the `SessionToken` import in `AppStatePlaceholder.kt` (lines 83/87/89/92/94,
with cascading overrides-nothing errors). The successor adds the actual contracts-domain import.
Both target Room suites were absent, not passing or failing. Controller/workflow policy and
Swift codec jobs passed; downstream mandatory JUnit absence remains unverified execution.
The production-editor cancellation control now also observes an actual submitted Room transaction
command before cancelling, rather than treating lease entry as sufficient queue evidence.

Per-method quiet listener support was requested for only the two authority classes. Trusted
admission refused `android/app/build.gradle.kts` / `modify-related-consumer` for WP-305; no build
file was edited. The concrete blocker was handed to the Project for an admitted support owner.

The Project identified the actual existing `app-build-launcher` operation
`update-build-or-manifest`; that exact trusted admission succeeded for
`android/app/build.gradle.kts`. No rule/policy/reservation was changed. The clean existing listener
hunk is extended with only the two actual authority test classes, emitting their runner-provided
class/method START and END/result type at quiet level. No predicted method count/outcome, filtering,
task/workflow change, altered assertion or suppressed failure is introduced. This support successor
is separate from exploratory run `41945162289` at exact
`fbf12f347754864cc2317e2f43645ccd3a72c2c0`; that run remains unchanged.

The persistence reviewer identified a separate committed-outcome classification defect:
`editDevice` could send a `PersistenceStoreException` from the already-committed consumer callback
to the pre-commit `onSaveFailure` mapper. The persistence producer supplied the exact two-hunk
repair against `2713871`: record commit observation before calling the consumer, then rethrow that
original exception without invoking the save-failure mapper or retrying. Its added thirteenth
real-Room method checks exception identity, one transform/callback, no save-failure callback, and
the committed row/revision. The original twelve methods remain intact. This is transferred source,
not executed evidence; the running `2713871` diagnostic cannot cover this successor.

Exploratory exact `fbf12f347` run `41945162289` failed after 1,936 App tests with four failures:
the producer's `reconnectStateAndLiveClearCannotCrossAnActualRoomCommitLease` fixture timed out,
two independently owned preset path-hash fixtures reported expected mode 1/readback 0, and the
standalone onboarding suppressed-readback assertion failed. The persistence class emitted a real
class SUCCESS marker, not individual method evidence. No failure is removed or reclassified as
passing. The producer's state/clear fixture is changed from a held Room transaction to the actual
submitted transaction-executor boundary already used by the cancellation and storage controls;
all blocked-state, commit, live-clear and persisted-row assertions remain. This is a new candidate,
not a revision of either diagnostic ref.

### Exact executed repair handoff

Code candidate: `486f22c3a9e0d91023146df7f3d9c06f4299910f`.
Authoritative hosted result:
[run 41947555936](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41947555936),
dispatched through the existing full `android-ci.yml` workflow on unique ref
`diagnostic-device-authority-486f22c3a-b198d7fe`, after checking a clean intended head,
matching remote SHA and no preexisting run on that ref.

The actual Gradle runner emitted paired method START/END SUCCESS for all 21
`DeviceWriterAuthorityRoomTest` methods and all 13 `DevicePersistenceAuthorityRoomTest`
methods. This includes both OCV interleavings, ordinary-save/preset and successor ordering,
actual Room cancellation/commit authority, queued timeout/pairing rejection, staged-row/scope
rollback, two-store revisions and the committed-callback exception control. The corrected
state/clear Room-command witness executed successfully without dropping its prior assertions.
`RxRegionConsumerTest` emitted a class END SUCCESS; core runtime and services test tasks ran
without reported failures. The controller/workflow and Swift codec jobs also passed.

The Project subsequently confirmed independent immutable `501a26be0` to `486f22c3a`
fixture-source review found no correctness issue. It verified the actual Room transaction-executor
command pause, real RoomPersistenceStore delegation and production authority path, with every
blocked setter, live-clear, state, accepted-commit and persisted-row assertion retained. This closes
the fixture-source review only; the exact hosted execution above remains separate evidence.

The full workflow remains **failed**, not accepted: App discovered 1,937 tests with three failures.
They are `PresetDeviceEventSessionTest.presetWithPathHashCarriesItsOriginalTokenToBothMutations`,
`PresetDeviceEventSessionTest.retiredPresetPathHashCannotUseReplacementGeneration`
(expected path-hash mode 1, radio readback 0), and the standalone
`OnboardingFlagsTest` unavailable uncertain-commit readback suppressed-error assertion.
These independently owned predecessor failures remain unchanged; their producer repairs are
not imported here. The hosted failed-command report states evidence complete with 14,023 discovered,
14,020 passed, three failed, zero errors and zero skipped across its available mandatory reports.

The code/API handoff was sent to the Project, onboarding/Settings counterpart and persistence
producer. The default-scope service queue follow-up remains separately owned and under review;
this result does not verify that absent composition, close onboarding, approve a merge, or
provide hardware/device evidence. No local Gradle execution or retained-owner recovery occurred.
No existing diagnostic ref, sibling run, draft PR, source pin, #495 hold or historical lease was
changed. This summary references the authoritative hosted log; no duplicate CI-result bundle
is committed, uploaded, hashed or substituted.

### Normalized default-scope alias follow-up

The Settings producer's independent review found a coupled editor/storage mismatch: raw region
`P` at exactly thirty UTF-8 bytes and raw `P+suffix` share one radio scope. Removing `P` must not
clear the selected scope while the longer alias remains. This follow-up does not import Settings
service/UI changes or alter the editor API. The editor recomputes normalized selected/removed
names and surviving aliases from each authoritative CAS snapshot, including after a conflict;
its legacy radio-clear decision uses the same survivor rule. The persistence producer transferred
the exact repository validation hunk: normalize both removed names and the selected default,
permitting a retained default only while a matching normalized survivor exists. Raw channel-scope
reset semantics are unchanged.

Two new real-Room controls cover mixed short/long multibyte aliases in both removal directions,
explicit last-alias default-clear rejection, and concurrent alias insertion/removal forcing a
fresh CAS-derived survivor decision. Existing assertions remain intact. Source controls
`controller/validate.py`, `portmap.py`, `controller/verification_config.py` and `git diff --check`
pass; these new controls require their own exact hosted execution and do not inherit the earlier
34-method result. Radio-to-Room ordering across Settings operations remains the separate
Settings producer's queue contract, not a guarantee manufactured by this storage guard.

Independent exact `edd4a7f` to `a7fed6eb9` review identified that the optional editor
`clearDefaultFloodScope` callback sampled the original live regions before a later CAS retry.
There is no safe compensation for that external radio side effect when a concurrent alias is
added or removed. Exact-tree call-site search found no supplied implementation (only the
constructor default and its invocation). The follow-up removes this unused callback and
`clearRadio` flag rather than offering an unsafe two-authority API. Legacy Job removals and
awaited removals now share the same persistence-only boundary. A third production-editor/real-Room
control exercises the legacy Job path with both concurrent alias insertion and removal, requiring
two actual rejected tickets, correct persisted/live defaults and unchanged live generation.
All previous controls and assertions remain. This is not a radio test.

The review's actual Settings entry-point mismatch belongs to the Settings producer, and its
targeted `DeviceRepository.removeDeviceKnownRegion` mismatch belongs to the persistence producer.
Neither integration is accepted by this editor-only change. The exact `a7fed6eb9` diagnostic
`41952095327` is preserved, not cancelled or retargeted, and cannot execute this successor.

The persistence producer transferred immutable `1a422b8679eb36a34a2ca6a97de2cadedffc1294`
(parent `b62`) as a narrow patch, not a whole-file replacement. Composition onto editor successor
`83a63efa86a9d9cbc7a4e78226b690ec4dcd6e6e` retains the newer committed-callback control and
all thirteen existing persistence methods. Only the targeted `removeDeviceKnownRegion` hunk,
two new tests and their seed helper are imported; the existing `utf8Prefix` import is deduplicated.
The targeted writer now retains the exact selected default while a normalized alias survives
and clears it on last-alias removal, without changing raw channel reset semantics. The transferred
test/helper block matches the producer's immutable bytes. Its controls cover actual same-target
CAS rejection/retry and both last-alias/default directions using ASCII and mixed thirty-byte UTF-8
names, with exact raw-channel and revision assertions. The composed source has twenty-four writer
and fifteen persistence methods; these are source counts, not execution evidence. Settings radio
integration remains separately owned and unaccepted pending composed execution.
