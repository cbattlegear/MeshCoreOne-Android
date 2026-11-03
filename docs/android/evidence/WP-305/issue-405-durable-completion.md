# WP-305 issue #405 durable completion follow-up

- **Repository:** `cbattlegear/MeshCoreOne-Android`
- **Reference:** `db14559b39d32322b06477c6ae676112f583db50`
- **Base:** `99479711bfdb7c621765f09d671068ca8b8318ce` (`main`)
- **Reviewed predecessor head:** `b91f5fcbbe2f533093f2eab6e84aa641c0c9a103`
- **Navigation follow-up:** based on `b91f5fcbbe2f533093f2eab6e84aa641c0c9a103`; the lifecycle-safe
  busy-control candidate is `3abfd74ebb56b0b6ba2aad98435ac1e35a4a732b`. Its exact-head Android CI
  passed; the automated review workflow completed, but the actual PR review was COMMENTED, while a
  separate source-only review found no new blocker. Intermediate findings and corrections are recorded below.
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
- New mounted real-shell regressions hold radio apply and completion persistence separately. They
  verify toolbar Up, adaptive-rail tab selection, and onboarding's mounted system-Back handler keep
  the busy rerun mounted; failures remain visible, completion retry does not reapply the preset,
  and ordinary navigation resumes after successful completion. The held-commit regression asserts
  the unique completion-error message and retry control are displayed before retry. `NavDisplay.onBack`
  also uses the same guard. With an active onboarding path, the flow's BackHandler is the reachable
  system-Back entry and consumes Back before the shell callback; a busy flow cannot normally have an
  empty path.
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

## Verification

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
| `git diff --check` | Passed for the source and evidence diffs. |
| Local Android/Gradle unit tests or build | Not run: the shared local Gradle execution reservation remains held without cleanup proof and was not cleared, bypassed, or contended for. |
| Exact-head hosted run [41630362683](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41630362683) | Passed on `825b6ad9a007203499a088e10102a54ce7b74f3d`; the Settings-rerun mounted suite marker succeeded and its four then-current cases passed. This candidate preceded the lifecycle leak correction. |
| Independent review of `825b6ad9a007203499a088e10102a54ce7b74f3d` | Run [41630377176](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41630377176) found that eager process-scope `stateIn` retained each discarded rerun coordinator (`4454336766`). Replaced that derived flow with lifecycle-owned Compose observation; this was not acceptance. |
| Exact-head hosted run [41632599536](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41632599536) | Failed on `057cd5160be8d3e732961cf655e4e5c075ec3c17`: 1 of 1,863 app tests failed because the new lifetime regression incorrectly required an empty process scope despite app adapter collectors. The actual `SettingsRerunNavigationTest` suite marker recorded failure. |
| Independent review of `057cd5160be8d3e732961cf655e4e5c075ec3c17` | Run [41632615910](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41632615910) confirmed the test-oracle issue (`4454530325`); the regression now captures and compares the live app-adapter job baseline after each successful rerun. |
| Exact-head hosted run [41635010095](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41635010095) | Passed on `3abfd74ebb56b0b6ba2aad98435ac1e35a4a732b`, base `99479711bfdb7c621765f09d671068ca8b8318ce`: candidate classification, controller/workflow policy, Linux Android build, and `android-ci` passed; the repeated-rerun test was reported passed and `SettingsRerunNavigationTest` ended successfully. Swift codec/backup oracles were skipped. |
| Copilot review of `3abfd74ebb56b0b6ba2aad98435ac1e35a4a732b` | Workflow run [41635033665](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41635033665) succeeded, but its actual PR review `5752191635` was **COMMENTED — Needs a closer look** and retained navigation thread `4452978212`, noting that its cited run predated the lifecycle changes. A separate `bd` source-only review found no new blocker. This is not a clean approval or acceptance receipt. |
| Exact-head hosted run [41637952922](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41637952922) | Passed on exact head `8bc05c981f9ac4e0083a1d6ae73fb509bc8c5e59`, base `99479711bfdb7c621765f09d671068ca8b8318ce`: candidate classification, controller/workflow policy, Linux Android build, and `android-ci` passed; `SettingsRerunNavigationTest` emitted a successful suite marker and the build completed successfully. Swift codec/backup oracles were skipped. |
| Copilot review of `8bc05c981f9ac4e0083a1d6ae73fb509bc8c5e59` | Review run [41637968718](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41637968718) completed with actual PR review `5752453234` **COMMENTED — Needs a closer look**. It identified API 31/37 permission-dialog and device Back/dismissal behavior as unverified. The specific in-app Settings-navigation thread was later answered and resolved in [reply `4454941214`](https://github.com/cbattlegear/MeshCoreOne-Android/pull/532#discussion_r4454941214) based on the mounted controls and exact-head CI; this is not a clean review or acceptance receipt. |
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
