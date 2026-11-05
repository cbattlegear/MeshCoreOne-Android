# WP-305 current-main durable-tip prerequisite

## Identity and bounded scope

Repository: `cbattlegear/MeshCoreOne-Android`; source reference:
`db14559b39d32322b06477c6ae676112f583db50`.

The preserved source commit `80a395835f1f2025c571629a19699f5e6e2ba03d`
has the single parent `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
It transplants three paths from the previously published onboarding candidate
`b2beb5f257b36cd3ccce7bb641f7b2ea3747e3fc`, using their common base
`16d6ff9146142c16226cecc1f1461e0afc450137` for three-way integration:

- `android/core/ui/src/main/kotlin/com/meshcoreone/android/core/ui/ShowOnceTips.kt`
- `android/core/ui/src/test/kotlin/com/meshcoreone/android/core/ui/ShowOnceTipsTest.kt`
- `android/core/ui/src/test/kotlin/com/meshcoreone/android/core/ui/SharedUiComposeTest.kt`

The source provides atomic completion plus pending-tip donation, attempt staging
and authoritative finalized-ID readback, and eligible-tab claim policy. It is
an independent prerequisite, **not the complete app onboarding integration**.
No new app producer or native tip presenter is wired by this slice.

The production file and ShowOnceTipsTest are byte-identical to b2. The shared
Compose test retains current main's newer crop tests and differs from main only
by the AppTab import and the necessary existing TipHostState constructor argument.
All 12 existing tip test names and 48 assertion lines remain in order; three
new methods bring that class to 15. All 22 main shared Compose test names and
166 assertion lines remain in order. Assertion-line preservation is a source
check, not behavioral acceptance or Android execution.

No writer, Settings, AppState, navigation, map/timeline or other source paths
are imported. There is no merge/cherry-pick of held candidate ancestry.
The existing #532 branch, diagnostic SHA and draft state remain unchanged.

## Actual corrected-head execution, not successor execution

Authoritative b2 run:
https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41954177866/job/127005065492

The workflow **failed**. App tests compiled and ran **1,944 / 19 failed**.
Independent inspection of the hosted log's per-method records found:

| Class family | Passed methods |
| --- | ---: |
| OnboardingFlagsTest | 28 |
| OnboardingGateTest | 26 |
| Other app onboarding adapters, permissions, system pairing and Wi-Fi recreation | 33 |
| All feature onboarding classes | 102 |
| PresetDeviceEventSessionTest | 6 |
| CompanionSetupServiceTest | 20 |
| ShowOnceTipsTest | 15 |
| SharedUiComposeTest | 19 |
| Total inspected relevant methods | 249 |

Every listed method has a `passed` method outcome, not merely an absent failure
or class declaration. Flags and Gate also have actual START/END SUCCESS records.
The corrected dialog selector executed in
`mounted preset retry reads the original sealed receipt even after its radio is replaced`.
The separate dismissed-dialog/no-radio/no-selection recovery, late unknown
readiness, matching retired reset, nested reset, stale-read rejection and
real-storage wall-clock settlement controls also passed on b2.
This does not cover the current-main integration tree or main's three newer
shared Compose methods. The exact a61 compilation failure, cae ambiguous-selector
failure, a5 settlement failure and earlier diagnostics remain in the original
WP-305 evidence; none is erased or relabeled.

The 19 failures were ChannelRecoveryIntegration (5), DeviceWriterAuthorityRoom
(3), RxRegionConsumer (1), SavedDeviceRecovery (1), app SettingsPathHashPersistence
(7), SettingsPathHashRecreation (1), and NodeConfigMountedPartialImport (1).
The latter, RxRegion and SavedDevice failures include the candidate runtime's
unsupported DeviceEditPersisting wrapper cast at ConnectionManager line 610.
Settings controls include superseded-generation initialization failures; two
writer region controls disagree on knownRegions and one activation control
times out. These are failures of that composition, not proven defects on main.

## Current-main comparison and missing prerequisite

Fetched main was `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
`git merge-base b2beb5f257b36cd3ccce7bb641f7b2ea3747e3fc origin/main`
returned `16d6ff9146142c16226cecc1f1461e0afc450137`, matching GitHub's PR base
SHA despite the newer branch tip. This is not a new current-main merge result.

Main run:
https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41945240042/job/126975787429

That exact run succeeded. Independently inspected method outcomes include
ChannelRecovery 5/5, RxRegion 6/6, SavedDeviceRecovery 10/10 and
NodeConfigMountedPartialImport 1/1. Main still uses saveDevice/setActiveDevice
and has no candidate DeviceEditPersisting initialization cast.
The new app writer/Settings classes are absent on main; their candidate
failures cannot be treated as passing or failing current-main methods.

Full onboarding integration remains blocked on these absent support APIs:

- `AppConnectionPort.updateDevice(info, presetId, expectedToken)`
- `AppConnectionPort.updatePathHashMode(mode, expectedToken)`
- `ConnectionManager.sealOnboardingCompletion(expectedToken, attempt)`

AppStateRadioConfigurator requires them for original-session persistence and
atomic seal authority. The current writer owner confirmed there is no reviewed
current-main-only prerequisite; the APIs exist only in held composed ancestry.
Do not silently replace them with unversioned writes or import/reimplement that
ancestry. This is a dependency blocker, not an advisory path lock or a failed
three-way merge.

## Source checks and remaining gates

Pinned Python 3.12.4 was selected. Fresh trusted admissions accepted the
production path as `reviewed-support-consumer` / `modify-related-consumer`,
the two tests as `reviewed-support-test` / `add-or-update-related-test`,
and this evidence path as `wp-owned` / `modify`. Trusted three-way cached
application was clean; `git diff --check` against the exact parent passed.
Exact source equality, preserved main additions, test/assertion-line inclusion,
single-parent identity and unchanged active worktree/PR head were checked.
These checks do not compile or execute this successor.

No local Gradle ran under the retained `f516` execution hold, no execution
reservation was cleared, and no redundant hosted workflow was dispatched.
No second hosted CI-result bundle was retained; the linked job/log is
authoritative. The isolated local branch is a reviewable source prerequisite,
not an accepted or release-ready PR replacement.

#495 and the original Activity navigation witness remain held. Conditional
public reset/reentrancy, independent-child cancellation and explicit early
retirement controls do not establish production Activity recreation failures.
Full onboarding integration, exact-successor review/execution, actual first-run
presenter/dismissal, native tip equivalence and hardware gates remain unaccepted.
