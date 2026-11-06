# WP-305 issue #405 durable completion follow-up

- **Repository:** `cbattlegear/MeshCoreOne-Android`
- **Reference:** `db14559b39d32322b06477c6ae676112f583db50`
- **Base:** `99479711bfdb7c621765f09d671068ca8b8318ce` (`main`)
- **Reviewed predecessor head:** `b91f5fcbbe2f533093f2eab6e84aa641c0c9a103`
- **Navigation follow-up:** based on `b91f5fcbbe2f533093f2eab6e84aa641c0c9a103`; the lifecycle-safe
  busy-control candidate is `3abfd74ebb56b0b6ba2aad98435ac1e35a4a732b`. The retained-flow identity
  repair is `3a552f5fbfc41b29a9e18b93bc11b65d4f262c5e`; its exact PR successor
  `d073659d35df83166af2c7a5fa7a814897b55a0c` passed hosted Android CI. Intermediate findings and
  corrections are recorded below.
- **Local-only completion-handoff follow-up:** based on published
  `c277a10ac1fe657e4b48cfa32fdad4ae26298518`; prepared for independent source review before push
  or hosted CI. The added mounted control has not been executed.
- **Worktree branch:** `cbattlegear-wp-305-onboarding-recovery`
- **PR:** [#532](https://github.com/cbattlegear/MeshCoreOne-Android/pull/532) (open draft)
- **Manifest / policy:** `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` / `b12794157978d6d90ab7508d4e2852fdc2d5e4a4`
- **Scope:** persist onboarding completion before exposing the completed gate; atomically persist
  completion and device-menu-tip donation; retain retry after storage failure; constrain claims to
  the original eligible tabs; protect preset completion from selection, cancellation, and radio
  replacement races.
- **Acceptance groups:** `APP-G06` donation/pending/show-once lifecycle and `APP-G08` actual
  completion preference/readback and resume boundary.

## Changes and regression coverage

- `OnboardingFlagStore` awaits persistence before publishing completion/reset state. The app adapter
  acknowledges durable writes only after the process preference store confirms them and atomically
  persists the first-run flag with the pending tip event.
- Failed preset-completion persistence retains the already-applied preset for a persistence-only
  retry. A new radio-apply attempt clears that cached success before starting, including when a
  different preset partially changes the radio and then fails.
- Cached preset success is bound to the live `SessionToken` epoch, generation, and radio ID.
  Completion retry rechecks the current target; reconnecting another radio cannot reuse the old
  radio's success.
- While no-device completion is committing, confirm/cancel controls are disabled and sheet
  dismissal/system Back are blocked until the durable write resolves.
- No-device completion is serialized against active retry/demo/pairing/Wi-Fi work; connection
  starts and success continuations cannot navigate away while the completion write is held.
- First-run onboarding retains its flow coordinator on the process scope across Activity recreation.
  Settings reruns use a separate retained coordinator and in-memory flags, reset after that rerun's
  completion callback.
- The shell uses the flow's synchronous busy fence for Settings-rerun tab selection, toolbar Up, and
  `NavDisplay.onBack`. Its disabled semantics observe `preset.state` and
  `isCompletingWithoutDevice` through composition-lifecycle collectors, rather than a derived
  process-scope `stateIn` job. Successful completion still uses its existing programmatic navigation path.
- The shell re-resolves the retained flow on each current-route composition and its synchronous
  navigation callback reads the live top route and current coordinator, rather than relying on a
  remembered route-active Boolean. Rerun completion callbacks are accepted only from the currently
  owned flow; the shell also refuses a stale completion navigation when onboarding is no longer the
  active route, so an off-tab completion cannot pop the selected tab's entry.
- New mounted real-shell regressions hold radio apply and completion persistence separately. They
  verify toolbar Up, adaptive-rail tab selection, and onboarding's mounted system-Back handler keep
  the busy rerun mounted; failures remain visible, completion retry does not reapply the preset,
  and ordinary navigation resumes after successful completion. The held-commit regression asserts
  the unique completion-error message and retry control are displayed before retry. `NavDisplay.onBack`
  also uses the same guard. With an active onboarding path, the flow's BackHandler is the reachable
  system-Back entry and consumes Back before the shell callback; a busy flow cannot normally have an
  empty path.
- The identity-schedule regression disposes rerun flow A and pops/reopens setup in one UI-thread
  transaction, without a Compose idle/frame barrier between the route changes. It asserts onboarding
  is the active destination before and after while both the navigation entry ID and retained coordinator
  change, then holds flow B's apply and verifies tab, Up, route-action and system-Back controls stay
  blocked until completion. A separate mounted test completes stale flow A after B has replaced it and
  verifies A cannot deliver a completion callback. An off-tab navigation regression verifies an old
  completion cannot pop the currently selected tab's entry.
- A repeated-successful-rerun mounted regression uses the real `AppOnboarding.Rerun` and process scope.
  It captures the actual adapter-job identity baseline after constructing app bindings, then checks
  after each of two successful reruns that the same adapter jobs remain active, the process scope stays
  active, and no per-flow sharing job remains.
- The pending permission kind is now saveable across Activity recreation. A denied location result
  queued in an `ActivityResultRegistry`, saved/restored into a new registry after recreation, verifies
  both `DENIED` state and `LocationPermissionResultReporting(false)`.
- Deterministic regressions cover held completion writes, dismissal/Back attempts, replacement-radio
  retry, partial preset change followed by failure, selection changes while application is held,
  held connection/write ordering, rerun isolation, and Activity recreation/retry.
- The original ten `OnboardingStateTests` IDs remain attributed in `OnboardingStateTests.kt`; the
  eleventh navigation-default source family remains in WP-302-owned `OriginalNavigationCases.kt`.
  New cases are additional regression coverage, not original-case credit. No pinned source or source
  case inventory was changed.

## Local-only completion-before-effect investigation

At published `c277a10ac1fe657e4b48cfa32fdad4ae26298518`, the no-device operation can finish its
actual preference write, publish `hasCompletedOnboarding`, and clear `completing` before the mounted
completion effect runs. The synchronous shell guard then permits Up. An immediate setup callback can
push another entry while `Rerun` still retains the completed flow A; the new host would observe A's
completed flag. This is a source-derived ordering, not an executed reproduction or a device finding.
The prior rapid-replacement test explicitly retires A and does not discriminate this interval.

The local candidate treats a completed flow as non-leaveable until its existing host completion
callback navigates and retires it. The shell observes the existing completed StateFlow with
composition-lifecycle collection so the enabled presentation has the same fence. The programmatic
completion path remains ungated; no per-flow process job or entry-provider/identity seam is added.
Failures still have `hasCompletedOnboarding == false` and retain normal retry/navigation behavior.

The new mounted control pauses the Compose clock, calls the real `confirmNoDevice` operation, and
checks that completion is published, the write is no longer busy, and A is still owned. In that same
UI-thread turn it invokes the previously mounted Up/setup/tab callbacks and Activity Back, without
calling `onFlowCompletionHandled` or inserting a frame. The candidate must retain A's original route,
entry ID and step until the actual effect runs. After resuming frames, it must navigate to Chats,
retire A, and allow a fresh entry/coordinator B to advance and leave normally. Against c277's source,
the callback sequence can pop/reopen and switch tabs before those assertions. That negative control
has not been executed; no failing or passing Kotlin result is claimed for this candidate.

Source-only checks for the local follow-up:

- Trusted `reserve.py admit` rules passed on default-base
  `99479711bfdb7c621765f09d671068ca8b8318ce`: shell `modify-related-consumer` ->
  `reviewed-support-consumer`, coordinator `modify` -> `wp-owned`, and mounted test
  `add-or-update-related-test` -> `reviewed-support-test`. No foreign reservation was borrowed,
  evolved or cleared.
- Documented native Windows `ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute directory>`
  passed using Python 3.12.4 and existing same-host state; it did not run Gradle.
- `python tools\android-port\controller\validate.py` passed (64 WPs / 1,866 tracked reference files).
- `python tools\android-port\test_inventory.py --check` passed (468 pinned paths / 5,133 declarations);
  this checks frozen-source inventory, not discovery or execution of the new Android test.
- `git diff --check` passed. No local Gradle, hosted CI, device verification or independent review of
  the local successor has occurred. The shared Gradle execution hold remains unchanged.

## Verification

### Unpublished Wi-Fi recreation follow-up based on d81

The source-derived race is the pairing sheet's composition-owned `LaunchedEffect` calling
`wifi.reset()` again after Activity recreation. A failed process-owned connect delivered while
no host is mounted leaves `isConnecting == false`, so the next mount clears its address, port
and error. If failure arrives after the new effect runs while still connecting, that reset
does not repeat. Neither ordering was executed for this local candidate.

The local fix resets Wi-Fi state at the intentional `openPairingSheet(WIFI)` transition instead
of at composition attachment. Reattaching the same retained sheet cannot reset it; closing and
intentionally reopening a settled sheet still resets it, and `WiFiConnectionCoordinator.reset`
still preserves in-flight operations. The permission-request effect remains in place. No Wi-Fi
transport, cancellation, shell or process-scope behavior is changed.

`OnboardingWiFiRecreationTest` authors four cases: failure between old-host destruction and new
attachment; failure after reattachment while connecting; fresh intentional reopen after failure
versus in-flight reopen; and connection cancellation with retained input and no success. The two
recreation cases mount real `OnboardingFlow` using the same process-owned `AppOnboarding` and flow,
assert fields and error displayed, and retry the same address/port through the mounted control.
These are authored assertions, not executed Kotlin or physical-device evidence.

Trusted default-base admissions passed for `OnboardingFlow.kt` and `OnboardingFlowCoordinator.kt`
(`modify` -> `wp-owned`), and the new app test (`add-or-update-related-test` ->
`reviewed-support-test`). `WiFiConnectionCoordinator.kt` was admitted but did not require an edit.
Read-only named custody checks found broad advisory scopes, not an actual competing Wi-Fi hunk or
specific edit hold. The coordinator corrected the earlier inference of a named unpublished code
producer; no global clearance or reservation transfer is claimed, and no paused producer was
inspected or awakened. Trusted-main isolated assigned/admitted authoring applies.

Python 3.12.4 Windows preflight passed with existing same-host state and a fresh absolute output;
`validate.py` passed (64 WPs / 1,866 reference files), `test_inventory.py --check` passed
(468 pinned paths / 5,133 declarations), and `git diff --check` passed. No local Gradle, new hosted
CI, negative-parent execution or device test occurred. Publication remains held for independent
exact-source review; PR #532 stays draft and human/device/execution holds remain unchanged.

| Check | Result |
|---|---|
| `python tools\android-port\controller\validate.py` | Passed: 64 WPs / 1,866 tracked reference files; manifest SHA-256 `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`. |
| `python tools\android-port\controller\verification_config.py --check` | Passed; trusted manifest overlay/configuration validation only. |
| `python tools\android-port\controller\workflows.py` | Passed; controller workflow/trust-boundary validation only. |
| `python tools\android-port\controller\test_runner.py --quiet` | Passed: 874/874 controller fixture tests, 0 failed/errors/skipped; not Android behavior evidence. |
| `python tools\android-port\portmap.py --repo .` | Exit 0; traceability only, not behavior acceptance. |
| `python tools\android-port\test_inventory.py --check` | Passed (468 paths / 5,133 declarations); inventory check only, not Kotlin execution. |
| Trusted admission for `AppOnboarding.kt` (`modify-related-consumer` → `reviewed-support-consumer`) and `OnboardingGateTest.kt` (`add-or-update-related-test` → `reviewed-support-test`) | Passed on the current base; no WP-502-owned mounted-test path was edited. |
| Trusted admission for `NativeNavigationShell.kt` (`modify-related-consumer` → `reviewed-support-consumer`), `OnboardingFlowCoordinator.kt` (`modify` → `wp-owned`), and new `SettingsRerunNavigationTest.kt` (`add-or-update-related-test` → `reviewed-support-test`) | Passed for this assigned worktree. |
| Independent read-only review of identity-schedule repair `3a552f5fbfc41b29a9e18b93bc11b65d4f262c5e` | No significant findings; confirmed the A→B replacement and stale-completion regressions and disjointness from WP-302 entry-provider/identity hunks. This is source review only, not hosted CI or acceptance. |
| `git diff --check` | Passed for the source and evidence diffs. |
| Local Android/Gradle unit tests or build | Not run for the identity-schedule successor: the shared local Gradle execution reservation remains held without cleanup proof and was not cleared, bypassed, or contended for. |
| Exact-head hosted run [41630362683](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41630362683) | Passed on `825b6ad9a007203499a088e10102a54ce7b74f3d`; the Settings-rerun mounted suite marker succeeded and its four then-current cases passed. This candidate preceded the lifecycle leak correction. |
| Independent review of `825b6ad9a007203499a088e10102a54ce7b74f3d` | Run [41630377176](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41630377176) found that eager process-scope `stateIn` retained each discarded rerun coordinator (`4454336766`). Replaced that derived flow with lifecycle-owned Compose observation; this was not acceptance. |
| Exact-head hosted run [41632599536](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41632599536) | Failed on `057cd5160be8d3e732961cf655e4e5c075ec3c17`: 1 of 1,863 app tests failed because the new lifetime regression incorrectly required an empty process scope despite app adapter collectors. The actual `SettingsRerunNavigationTest` suite marker recorded failure. |
| Independent review of `057cd5160be8d3e732961cf655e4e5c075ec3c17` | Run [41632615910](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41632615910) confirmed the test-oracle issue (`4454530325`); the regression now captures and compares the live app-adapter job baseline after each successful rerun. |
| Exact-head hosted run [41635010095](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41635010095) | Passed on `3abfd74ebb56b0b6ba2aad98435ac1e35a4a732b`, base `99479711bfdb7c621765f09d671068ca8b8318ce`: candidate classification, controller/workflow policy, Linux Android build, and `android-ci` passed; the repeated-rerun test was reported passed and `SettingsRerunNavigationTest` ended successfully. Swift codec/backup oracles were skipped. |
| Copilot review of `3abfd74ebb56b0b6ba2aad98435ac1e35a4a732b` | Workflow run [41635033665](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41635033665) succeeded, but its actual PR review `5752191635` was **COMMENTED — Needs a closer look** and retained navigation thread `4452978212`, noting that its cited run predated the lifecycle changes. A separate `bd` source-only review found no new blocker. This is not a clean approval or acceptance receipt. |
| Exact-head hosted run [41637952922](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41637952922) | Passed on exact head `8bc05c981f9ac4e0083a1d6ae73fb509bc8c5e59`, base `99479711bfdb7c621765f09d671068ca8b8318ce`: candidate classification, controller/workflow policy, Linux Android build, and `android-ci` passed; `SettingsRerunNavigationTest` emitted a successful suite marker and the build completed successfully. Swift codec/backup oracles were skipped. |
| Copilot review of `8bc05c981f9ac4e0083a1d6ae73fb509bc8c5e59` | Review run [41637968718](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41637968718) completed with actual PR review `5752453234` **COMMENTED — Needs a closer look**. It identified API 31/37 permission-dialog and device Back/dismissal behavior as unverified. The specific in-app Settings-navigation thread was later answered and resolved in [reply `4454941214`](https://github.com/cbattlegear/MeshCoreOne-Android/pull/532#discussion_r4454941214) based on the mounted controls and exact-head CI; this is not a clean review or acceptance receipt. |
| Exact-head hosted run [41644349372](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41644349372) | Passed on exact head `d073659d35df83166af2c7a5fa7a814897b55a0c`, base `99479711bfdb7c621765f09d671068ca8b8318ce`: candidate classification, controller/workflow policy, Linux Android build, and `android-ci` passed. `SettingsRerunNavigationTest` emitted a successful suite marker and all 8 JUnit case records passed, including rapid A→B replacement, stale completion, off-tab completion, held apply/commit and existing regressions. Swift codec/backup oracles were skipped. |
| Exact-head Copilot review of `d073659d35df83166af2c7a5fa7a814897b55a0c` | Actual review [5753080028](https://github.com/cbattlegear/MeshCoreOne-Android/pull/532#pullrequestreview-5753080028) is **COMMENTED — Needs a closer look**, reports no open issues, and retains the concrete API 31/37 device-validation limitation for permission callbacks and native Back/sheet handling. It is not a clean approval or acceptance receipt. |
| Exact-head hosted run [41615052625](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41615052625) | **Passed** on exact head `b91f5fcbbe2f533093f2eab6e84aa641c0c9a103`, base `99479711bfdb7c621765f09d671068ca8b8318ce`: candidate classification, controller/workflow policy, and Linux Android build passed; selected Python assertions 108/108; localization discovery 25/25; `OnboardingGateTest` emitted 8 JUnit case records and its suite marker succeeded. Swift codec/backup oracles were skipped. |
| Independent exact-head review | Reviewer `bd348` reviewed `b91f5fcbbe2f533093f2eab6e84aa641c0c9a103` against base `99479711bfdb7c621765f09d671068ca8b8318ce`; no new consequential source finding. The registry/Activity test is not OS-dialog or process-death proof. This is not a merge/acceptance receipt. |
| Protected navigation thread and acceptance | The Settings-navigation review thread was resolved only for the covered in-app navigation behavior. Physical-device Back, API 31/37 permission dialogs and sheet-dismissal lifecycle remain unverified. No acceptance receipt, merge, or product/native-tip equivalence decision is claimed. |

Historical compiler repair: run [41611347165](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41611347165)
failed on `c80da6cc44c96c4114eeaf4865142eb7416fb12b`. Its actual Kotlin diagnostics showed
`DeviceScanCoordinator` call sites binding trailing `onConnected` lambdas to the newly appended
Boolean gate parameter. Commit `0c833d38ddf17efaa262fd7987be53adb3b62531` reordered that parameter
and named the positional test-helper callback; its exact-head run
[41612757746](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41612757746) passed.

## Remaining acceptance and deviations

- The app-level tip claim/display consumer is not connected to the active WP-302 tab-change/navigation
  host. Persisted donation is available for an eligible host, but no visible display or user-seen
  claim is asserted.
- Native tip presentation remains a separate product/UI-equivalence decision. The original
  1.5-second pre-donation delay is not ported by this persistence follow-up. WP-502's mounted test
  remains separately owned and is not integrated or executed by this candidate.
- This permission regression proves saved Activity recreation with a restored registry only; it does
  not exercise an OS permission dialog or process death.
- Physical API 31/37 permission-dialog and device Back/sheet-dismissal behavior, radio/BLE/Wi-Fi
  hardware, native-tip UX review, and full WP-305 acceptance remain unverified. Mounted shell
  behavior is covered by Robolectric Compose tests, not physical-device evidence.

## Current-main completion-publication handoff

The local producer follow-up is based on current `main` `f6cf1bf73aad361ef3327c4e31a8ff5107272904`.
It exposes an opaque `OnboardingCompletionPublication` (receipt plus non-reused revision) and a
synchronous producer guard. The guard checks object identity, revision, completed state, durable
preference readback and reset/retirement/uncertainty state while holding the producer lock, then
executes only the navigation commit action before releasing that lock. The mounted regression reuses
the same completion callback across Activity recreation, rejects an old publication after an equal
receipt is republished, interposes a concurrent reset while the commit action is held, and rejects
the publication after reset. This new regression has not been executed.

The publication callback now also receives the exact `OnboardingFlowCoordinator`, and the new
callback form requires the host to pass the coordinator it pinned when capturing its navigation
witness. The same pinned coordinator is threaded through `OnboardingEntry` and `OnboardingFlow`;
the effect still rejects a coordinator no longer owned by the process dependencies. The mounted
regression asserts callback identity across recreation and after a deliberate owner replacement.
WP-302 must capture the witness before starting the flow and pass that same coordinator to the
entry; the producer does not capture or refresh navigation state.

Settings reruns also expose `onFlowCoordinatorEntryRemoved(coordinator)`, a narrow host callback
for permanent navigation-entry removal only (never Activity/composition disposal). The rerun owner
retires only the exact current coordinator, rotates to a fresh in-memory flag store and owner
revision, and retains any unsettled retired coordinator until its settlement completes. The rerun
uses `InMemoryOnboardingFlagStore`, so its completion write has no disk-backed uncertain outcome;
the regression instead interposes after sealing, removes the exact entry, mounts a fresh flow, and
then lets the old attempt settle. The new flow remains incomplete and current, and stale removal is
idempotent. This regression is not yet executed. The #302 host must call this seam only after actual
entry removal and outside the manager/registry/graph commit boundary.

Trusted `reserve.py admit` accepted the three onboarding production paths as
`wp-owned/modify`, the mounted regression as `reviewed-support-test/add-or-update-related-test`,
this evidence path as `wp-owned/record-evidence`, and the two recovery integration-test fixture
updates as `reviewed-support-test/add-or-update-related-test`. `git diff --check` passed;
`python tools/android-port/controller/validate.py` passed (64 WPs / 1,866 tracked reference
files); `python tools/android-port/test_inventory.py --check` passed (468 pinned paths /
5,133 declarations). Native Windows `ci.py preflight --local` exited 0 and invoked
`android/scaffold/check_environment.py`; no Gradle command was run because the shared
execution reservation remains held. These checks do not execute the new Kotlin regression.

Exact hosted run [42139553314](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42139553314)
on `32544b2da2220ee03d5b62431d81d625025ca8bd` failed at
`:app:compileDebugUnitTestKotlin`: adding the new optional `showFlow` parameter after the existing
receipt handler redirected legacy trailing-lambda test calls to the new callback type. The mounted
test did not execute. The helper parameters are reordered in the successor; fresh exact-head CI is
required.

For lock-order review only, paired consumer PR #495 was inspected at exact head
`9a9b2e0fae9fc9a7aa8b8887270799a4d0781842` against base
`8eaa4ecd35fb25f32aca5d0d3cd0414e3eaa4bcd`; that candidate is open, unmerged and not current-main
compatible yet. Its navigation boundary nests ConnectionManager -> SessionRegistry -> current
RadioSessionContainer, with the coordinator commit inside the boundary. The producer guard therefore
precedes that chain. The inspected commit path does not acquire the producer lock in reverse; it
checks the retained completion witness and session through the boundary, and retires only the
navigation witness on commit. Reset takes/releases the producer lock before preference I/O;
writer completion callbacks run after releasing the writer queue lock; AppContainer drains the
onboarding writer before runtime teardown; and flow-retirement callbacks run after the guarded
navigation callback returns. RadioSessionContainer teardown marks the graph closed under its fence,
then performs suspending service shutdown outside it. This is a bounded exact-source inspection, not
an executed concurrency test of #495 or proof of its eventual integration.

No producer/consumer integration, mounted tip display, native-tip equivalence or full WP-305
acceptance is claimed by this handoff. The local shared Gradle execution reservation remains held;
local Gradle execution was not attempted.

### Multi-entry Settings-rerun ownership follow-up

After merging current `main` `35abcc1276082995eed216bf28bff04d77e176c8` as
`35acb964eb6ff1a869da580bf32fa42e22547ba9`, `AppOnboarding` exposes
`fun createRerunOwner(): OnboardingFeatureDependencies`. Each call creates an independent rerun
owner, retained coordinator, in-memory flag store, revision and retirement set. The existing
`forRerun()` remains a legacy single-entry singleton; it is not safe for concurrent navigation
entries. WP-302 must create one owner for each live in-memory rerun stack entry, regardless of tab
or entry point, and retain that exact owner by stable entry identity across Activity recreation.
The first-run gate remains on its durable process-owned flow. The host must retire a rerun owner
only after permanent entry removal, not on composition disposal or temporary tab hiding.

The new mounted `OnboardingGateTest` case creates two rerun owners, starts and holds A's sealed
completion, mounts B, recreates the Activity while retaining both owners, then releases A and
checks the delayed publication is delivered with A's coordinator only. It removes A's entry and
asserts B remains owned and incomplete. The test is authored but has not run. The existing
`MainActivity` still calls the legacy `forRerun()`; the active WP-302 host must switch to an
entry-keyed retained owner for every rerun entry before multi-entry navigation is considered
integrated. The broader candidate remains unpublished while exact-head run `42141885753` runs
against the older `fb940ee` head; that run cannot verify this follow-up.

### Same-session preset serialization

Source inspection confirmed the concern: `SettingsService` releases its radio-operation mutex
when `applyRadioPresetVerified` returns, while `ConnectedDeviceEditor` independently serializes
Room edits and validates only the session token. Two preset continuations for the same live
session therefore had no shared authority across radio readback and device-metadata persistence.
`AppStateRadioConfigurator` now holds one container-owned mutex across verified radio writes,
token-bound device persistence, and optional path-hash persistence. `AppContainer.onboarding` is
process-owned and lazy, and every rerun owner delegates to its same preset port, so concurrent
entries use that same serialization boundary.

`DeviceWriterAuthorityRoomTest.concurrentOnboardingPresetsSerializeRadioAndDeviceMetadata`
uses the real same-session container, fake protocol radio and Room persistence. It pauses A's
device write after A's verified radio result, starts B, asserts B cannot write the radio before
A's metadata commit, then verifies B's radio and device metadata are the final values. This
interposition is authored, not executed; no source-level race is claimed as an observed failure.

The factory and regression were added in source commit
`0e6854ecb5aca216ae503008e4a469e533ea19a2`, parent
`35acb964eb6ff1a869da580bf32fa42e22547ba9`, with current-main merge base
`35abcc1276082995eed216bf28bff04d77e176c8`. On that candidate, Windows
`ci.py preflight --local` passed with the existing same-host state and a fresh absolute output
directory; `controller/validate.py` passed (64 WPs / 1,866 tracked reference files);
`test_inventory.py --check` passed (468 paths / 5,133 declarations); and `git diff --check`
passed. The new Kotlin test and other Android tests were not run because the shared Gradle execution
reservation remains held. The local commit is not pushed; PR #532 still points to `fb940ee`, and
its active run is not evidence for this candidate.

The shared radio-to-metadata serialization boundary and Room interposition regression are in
`aa880b22e524907586d876c80459197099893c85`, parent
`bfc9e4a9f052cf5b83d0d774d21dfd769553b99a`; the merge base remains
`35abcc1276082995eed216bf28bff04d77e176c8`. Trusted default-base admission passed for
`AppOnboarding.kt` (`reviewed-support-consumer/modify-related-consumer`) and
`DeviceWriterAuthorityRoomTest.kt` (`reviewed-support-test/add-or-update-related-test`), and
`git diff --check` passed before commit. The new interposition test and all other Android tests
remain unexecuted because the shared Gradle reservation is held. Run `42141885753` is still
in progress on older head `fb940ee72ab565555db46e54988789fd1756f54c`; it is not evidence for
this candidate. The `aa880b2` commit is local and unpushed, and PR #532 still points to `fb940ee`.

## Additive follow-up: retry fixture and simulator writer authority

The related `HistoryReactionReplayFailureTest` repair is in commit
`121bea50975de31f7f10ca4b85185ca13ca7d4e3` (parent
`a002a8d08807f162536d9291197af42472ef585d`). It restores
`DeviceEditPersisting by store` on the test's three persistence wrappers while retaining their
reaction overrides and fault cases; the production versioned-persistence guard remains unchanged.
The source-reported fixture failure and requested repair are tracked at
[issue #406 comment](https://github.com/cbattlegear/MeshCoreOne-Android/issues/406#issuecomment-6675594458).
Trusted `reviewed-support-test` / `add-or-update-related-test` admission passed for
`HistoryReactionReplayFailureTest.kt`. Exact-head run
[42153267661](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42153267661)
targets `121bea50975de31f7f10ca4b85185ca13ca7d4e3` and failed. Its Android logs report 32 unique
failing `HistoryReactionReplayFailureTest` cases; the first setup failure is
`UnsupportedOperationException: Connection initialization requires versioned device persistence`
at `ConnectionManager.establish:610`. The remaining `FaultStore` wrapper was still typed only as
`PersistenceStoreProtocol`, hiding the real Room store's `DeviceEditPersisting` capability. The same
run also failed two mounted `OnboardingGateTest` enabled-state cases with `ComposeTimeoutException`
in `awaitEnabled`; the log reports queued main-looper runnables after the timeout. The local,
unexecuted follow-up now types `FaultStore` as `RoomPersistenceStore` and delegates both
`PersistenceStoreProtocol` and `DeviceEditPersisting`; its bounded Compose wait drains the
Robolectric main looper before checking enabled semantics. These edits are not covered by run
`42153267661`. No local Gradle test was run because the shared Gradle execution reservation remains
held.

The narrow producer-side authority primitive and Room regression controls are in local commit
`b7f3d81591ced6c7e57e2651177d726c1f886351` (tree
`3a1b38812f5e7a57a464febf8762fad5d1c1ea26`, parent
`121bea50975de31f7f10ca4b85185ca13ca7d4e3`). The internal
`DeviceRepository.writeWithDeviceAuthority` delegates to the existing writer/transaction, so revision
and authority-revision updates remain post-commit and conditional on `save()`. The new real-Room test
covers committed ghost-only retirement invalidating a ticket captured beforehand without changing
the canonical DTO, no-write canonical reseed preserving a fresh valid ticket, and ignored/rolled-back
retirement preserving rows and ticket authority. Trusted current-main admissions passed for
`DeviceRepository.kt` (`reviewed-support-consumer` / `modify-related-consumer`) and
`DeviceRepositoryAuthorityWriteTest.kt` (`reviewed-support-test` / `add-or-update-related-test`);
the policy blob matches current main `0d10b838a181cd3bd34af1a0200435149dbc6bb0`. `git diff --check`
and the new test file's whitespace check passed. No Gradle test was run and no push/publication was
made. These tests exercise the shared writer primitive only, not the actual
`saveSimulatorDevice` / `resolveSimulatorDevicePreferenceConflict` call sites; those must be
composed and tested against the exact APIs before claiming stale-ticket coverage. The primitive
depends on the unmerged WP-305 writer foundation, which current main does not contain.

A read-only source review of the initial test commit found that its ignored-retirement case was
only a no-write branch and that the test lacked the `DeviceDTO.toEntity` import. Corrected in local
commit `a0a6a8e4f2f9cd20e44c6783b5014630eab8118c` (tree
`e2a2f70fcd48f54981876a9ac2007d21d636320f`, parent
`ce1b4a6fbb30dbf5bf6d55f2b00412808fdbed1e`): the Room test now installs a `RAISE(IGNORE)` delete
trigger, asserts the DAO reports zero deleted rows, throws a typed `PersistenceStoreException`
after a staged canonical-row write, and verifies rollback restores both rows and leaves the old
ticket usable. This is still a primitive-level control, not a call through the two #602 APIs, and
has not been compiled or executed.

### Follow-up after exact-head run 42153267661

At the time this follow-up was first recorded, the worktree was based on local `HEAD`
`113b46a39fef60cf389174c3d632ff95675207f1`
(tree `4c845cf815efcbedb827442bf9d368dbf21874cf`, parent
`a0a6a8e4f2f9cd20e44c6783b5014630eab8118c`), merge base
`35abcc1276082995eed216bf28bff04d77e176c8`, against `origin/main`
`0d10b838a181cd3bd34af1a0200435149dbc6bb0`. The changes below were uncommitted at that point and
are not part of run `42153267661`.

The exact run emitted 54 unique App test failure markers: 32 `HistoryReactionReplayFailureTest`, 3
`CliSettingsReadbackTest`, 4 `TraceToolsEntryLifetimeTest`, 1 `RxRegionConsumerTest`, 1
`NodeConfigMountedPartialImportTest`, 3 `DeviceWriterAuthorityRoomTest`, 7
`SettingsPathHashPersistenceTest`, 1 `SettingsPathHashRecreationTest`, and 2 mounted
`OnboardingGateTest`. The local source-only repair addresses the missing `DeviceEditPersisting`
delegation in the first five wrapper families; CLI now observes only successful conditional device
writes. The two region controls now seed the corresponding Room rows because `saveDevice` preserves
stored known regions. The `DeviceWriterAuthorityRoomTest` activation control remains unresolved: its
hosted failure is `UncompletedCoroutinesError` after one minute. All seven settings-persistence
failures and the recreation failure occur during the initial `h.manager.connect(...)`, before the
settings actions. Five persistence cases and the recreation case report
`CancellationException: Superseded connection generation` at `ConnectionManager.establish:670`
after `initialSync`; the other two persistence cases report it at
`syncDeviceTimeIfNeeded:795` and `:787`. Both fixture families call `foreground.activityStarted()`
immediately before connecting. The log does not establish why the generation retires.

For the two mounted onboarding failures, the run's model/publication assertions passed before the
mounted enabled-button waits timed out. Source inspection found that the known-uncommitted completion
callback emitted `unsettled=false` before clearing `pendingAttempt`, while the Compose gates also read
synchronous retry guards and received no later unsettled-flow emission. The local fix clears the
attempt guard before publishing settled state and adds a regression that observes the guard at that
emission. The mounted assertions remain, and the bounded test wait also idles Robolectric's main
looper as the run reported queued callbacks. These are source-grounded repairs, not an executed fix;
the model-ordering and test-looper effects remain separately unverified.

The producer-side worktree also stages the shared device authority revision before caller-registered
post-commit callbacks, so a later callback exception cannot suppress authority invalidation after a
committed write. Backup imports stage the same authority update when they insert devices; the restore
transaction applies its device-authority callback before the preference post-commit hook. Added Room
controls cover insertion invalidating an unchanged canonical-row ticket, no-op import preserving a
fresh ticket, rollback preserving a valid ticket, and a committed write followed by a throwing
post-commit callback rejecting a stale ticket. These controls remain primitive-level; no #602
`saveSimulatorDevice` / `resolveSimulatorDevicePreferenceConflict` caller integration is present.

The separate #495 stale-editor issue remains a bounded cross-boundary acceptance blocker: the
caller-side binding is now frozen, but cannot provide retirement ordering until the manager producer
is reviewed and composed. The original session-currency check before suspending Room I/O was
insufficient; an in-transaction predicate alone does not close the check/commit race. WP-209's first
frozen manager candidate `3dbcd1b3b4281509cefe0914e432114efa5a0a14` was not accepted after
WP-302 identified a cancellation/reconnect-authority admission hole. WP-209 supplied corrected
candidate `4180c56030b33b6dff71de2fa7b00d870323ca19` (tree
`47ab8dc1de21c4c45c4fad563c7fc86a17bbd4b3`), retaining
`captureDeviceWrite(): Pair<SessionToken, DeviceDTO>?` and
`withDeviceWrite(token, deviceId, block): T?`. It binds capture/admission to live establishment
authority and shares the generation drain through awaited terminal storage outcomes, but WP-302's
review found two further post-success-marker revocation windows. WP-209 corrected these in
`2a0e196bf38c4eda0e5ea609d107364cbff2694c` (tree
`0ab2453cd1a00d9775595d4260543b938d14d71b`); its exact `ConnectionManager.kt` blob is
`024a9c30aa38ab085ae9e0226ef316d1464b4961`. WP-302 then found a caller-lifetime cancellation
window in that candidate: a permanently cancelled initiating Job can revoke an already-established
process-owned generation. WP-209 supplied another corrected source candidate
`78c011d163c55e1f592ce670ca75eac2c709d485` (tree
`168e0ab9d77d6cb71e62480e25e4179964fe8f41`), exact manager blob
`91ba84242605000d27f284cfbbde831f2125edd5`. It moves establishment settlement to terminal
successful work completion, keeps reconnect-cycle equality after settlement, and adds post-return
caller-cancellation controls. WP-302's review found another owner/duplicate completion window:
successful shared work can remain READY but refuse edits when the original caller is cancelled
before resuming after `work.await`. The latest source-only successor is
`7dbf35ed7a471a48ef309e194cd201163f007edc` (tree
`c20c6e96cd8f201a29db1826bf8a109f74f08b69`, exact manager blob
`79abbf437643593e130d5e24a8a4d2de2305535e`). It moves settlement to the exact successful work
completion callback registered before waiters and adds controlled CONNECTING duplicate/gated-owner
and cancelled-work controls. WP-302 has been asked to review this exact candidate; that review is
pending. WP-302 found that the callback can skip settlement when the initiator is already cancelled,
leaving successfully completed work and a READY graph installed but permanently unwritable while
cancellation handling is delayed. A held real work child after the success marker and gated-owner
dispatcher expose this ordering. WP-209's new source-only candidate is
`87d6601ebba1cfb29aefee9796f2faefb30dd24d` (tree
`6529054ddc1b3664f0cf3fb93d61d1e0534781bc`, exact manager blob
`c9ca20135c54db9780d4405acfd023ffac4c347b`). It safely retires when original cancellation wins
before settlement despite successful work and prevents a duplicate from returning success for a
revoked completion; the real work-child/gated-owner/CONNECTING-duplicate control covers the ordering.
WP-302 rejected `87d`: kotlinx-coroutines 1.10.2 can publish a `Deferred`'s completed state before
running completion listeners, so `work.await()` may return before settlement. Its live check then
accepts an unsettled successful graph; cancellation before the delayed callback can leave READY
permanently unwritable after owner cleanup is unreachable. Exact source anchors were sent to WP-209.
WP-209's updated source-only successor is
`fb0eef752f361babf29057f20f63a1008d1a7844` (tree
`1bfd1a5efd465becb80af31a582f294fdfd9976c`, exact manager blob
`ca51f7faa664cdd5e8a0fd3d3c02b4d2aae5fef1`). It reportedly settles the exact active operation/work
synchronously under the manager lock before every successful shared await return, using original
initiator authority; the completion callback and revoked-success cleanup remain, and a blocked-listener
fast-path control is authored. Independent review and extraction remain held; no runtime execution is
claimed. Neither 78c, 7db, 87d nor fb0 is accepted, executed or published, and none is composed. The reported minimal production
composition is `ConnectionManager.kt` plus canonical `ChannelIdentityMutation.kt`
(`a4cf97a36327721e9df7eb9afffbf12b37511d3c`); the cumulative WP-209 ancestry and 411d data/runtime
test graph change are not required for the runtime API.

The bounded caller delta now adds an explicit manager-lease policy to `ConnectedDeviceAccess`,
atomic capture and manager lease delegation in `ManagerDeviceAccess`, and lease coverage for the
whole ordinary `persistEdit` retry/Room/publication and direct known-region add/remove operation.
Guarded capture/admission failure refuses without legacy fallback; explicitly guard-off fakes retain
the old no-token behavior. The caller never recaptures after suspension. New real-Room paused-store
controls cover delayed stale same-ID capture rejection, commit and rollback drains on disconnect,
successor installation drain, direct region-add and region-remove drains, guard-off parity, and
missing guarded-capture refusal. WP-302 source review found that reusing the captured DTO as the
post-`sessionEdits` merge baseline could overwrite a queued same-generation update. The caller now
reads the latest DTO through the original token only after the mutex and lease are acquired, then
validates its device identity; the direct region path uses the same fresh, token-bound baseline for
the default-scope decision. The existing real-Room 868→915 queued-edit assertion remains, and a new
queued default-scope/region assertion verifies it does not clear a scope changed by the earlier edit.
These controls are authored, not executed. The caller freeze is local commit `fca33e0adbf`; the
freshness correction and added Room control are local commit `dcb37285c8d` directly after it. Both
follow WP-305 writer commit `113b46a39fef60cf389174c3d632ff95675207f1` and intentionally reference the
pending manager API, so they are not yet a compilable composed candidate. The earlier uncommitted
fixture, onboarding-state and authority-control repairs were subsequently frozen in local commit
`eff1f33e911`.

The exact producer composition still requires favorable independent review of `fb0...` and the minimal
canonical `ChannelIdentityMutation.kt` dependency as well as `ConnectionManager.kt`; no cumulative
WP-209 ancestry is imported. Trusted current-main admissions for the caller files were previously
verified. On caller head `dcb37285c8d`, `python tools\android-port\controller\validate.py` passed
(64 WPs / 1,866 tracked reference files), `python tools\android-port\test_inventory.py --check`
passed (468 paths / 5,133 declarations; inventory only), `python tools\android-port\portmap.py --repo .`
exited 0 (traceability only), and `git diff --check` passed. No Kotlin compilation,
Room/Android test or new hosted run occurred because the shared Gradle reservation remains held.
PR #532 remains draft at pushed head
`121bea50975de31f7f10ca4b85185ca13ca7d4e3`; the local commits remain unpushed and unpublished.

Documented native-Windows readiness preflight passed with pinned Python 3.12.4 and existing
same-host state. On local HEAD `3e31e4d4ebd`, the pinned native-Windows Python 3.12.4 run of
`controller/ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute temp dir>`
reported `result: valid`; `controller/validate.py`, `test_inventory.py --check`,
`portmap.py --repo .`, and `git diff --check` all exited 0. These are readiness, ownership,
inventory and traceability checks only. No Kotlin compilation, Room/Android test, local Gradle
execution or new hosted run was performed; the shared Gradle execution reservation remains held
without cleanup proof. PR #532 remains draft at pushed head
`121bea50975de31f7f10ca4b85185ca13ca7d4e3`; no push or PR update was made for this worktree.
