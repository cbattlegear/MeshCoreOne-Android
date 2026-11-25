# MeshCore One Android

Native Kotlin/Material 3 Compose port of the MeshCore One iOS app. The Swift tree beside `android/` is the
read-only specification, pinned at `db14559b39d32322b06477c6ae676112f583db50`.

## Where things are

| What | Where |
| --- | --- |
| Scope, product constraints, module plan | [`PORTING_PLAN.md`](PORTING_PLAN.md) (sections 1-3, 5, 6, 8, 9 are live; see its banner) |
| Work packages, dependencies, acceptance criteria, Swift file to work package map | [`port-manifest.json`](port-manifest.json) |
| Architecture contracts | [`architecture/`](architecture/) |
| Decisions | [`adr/`](adr/) |
| Swift features deliberately not ported | [`not-ported.json`](not-ported.json) |
| Deliberate differences from Swift, per work package | [`deviations/`](deviations/) |
| Oracle sources, vector generators and notes that tests and comments cite (notes may name collectors or receipts removed by ADR-007) | [`evidence/`](evidence/) |
| Bundled notices and licenses | [`notices/`](notices/) |
| Module graph and Gradle task contract | [`architecture/modules.md`](architecture/modules.md); tasks in "Build and test" below |
| Agent instructions | [`.github/copilot-instructions.md`](../../.github/copilot-instructions.md) |

## How work gets done

There is one maintainer. There are no reservations, leases, locks, approval gates, agent rosters or receipts.
Open a pull request, let `android-ci` run, and the maintainer merges. A work package's dependencies in the
manifest describe what the code needs, not who may touch which file: edit whatever the change needs and rebase
if another branch got there first. [ADR-007](adr/007-maintainer-directed-process-teardown.md) records what was
removed and why.

## Build and test

Prerequisites: JDK 21 (Temurin) as `JAVA_HOME`; the Android SDK with `platforms;android-37.2` and
`build-tools;37.0.0` as `ANDROID_HOME`; Python 3.12 as `python` on `PATH` (Gradle runs several Python hooks).
Gradle 9.8.0 comes from the wrapper. Nothing is installed or licensed for you; the CI action installs the two
SDK packages and accepts the SDK licenses on the hosted runner only.

Hosted CI is the reference run, and [`android-ci.yml`](../../.github/workflows/android-ci.yml) is the canonical
command list. A cold Gradle build is heavy on a workstation; run the narrow task for what you changed and leave
the full run to CI. From `android/` (`gradlew.bat` on Windows):

```
./gradlew --dependency-verification strict :app:assembleDebug
./gradlew --dependency-verification strict verifyScaffoldTests validateModuleGraph
./gradlew --dependency-verification strict :core:protocol:test              # JVM modules: :<module>:test
./gradlew --dependency-verification strict :feature:chats:testDebugUnitTest # Android modules: :<module>:testDebugUnitTest
./gradlew --dependency-verification strict :platform:notifications:testDebugUnitTest
```

For the full PR checks locally, run the source checks below, then from `android/` run:

```
./gradlew --no-daemon --continue --dependency-verification strict --build-cache --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process -PscaffoldTestHeap=1g \
  verifyScaffoldTests validateModuleGraph runtimeDependencyInventory lintScaffold \
  :app:assembleDebug :app:compileDebugAndroidTestKotlin :core:testing:testDebugUnitTest \
  resolveScaffoldDependencies
```

Finally run `python android/scaffold/inspect_apk.py` from the repository root. This runs the app tests
locally instead of in five CI shards; it does not boot an emulator or reproduce GitHub artifact uploads.
For the additional full nightly/manual check, run
`./gradlew --dependency-verification strict :benchmark:verifyBenchmarkHarness validateModuleGraph`.
Windows may require symlink privileges, and some directory-flush durability tests can fail on the Windows
filesystem; do not treat those failures as a passing Linux run.

Repository and backup Room test contexts, notification SQLite tests, and the file-backed Room UUID-alias,
channel-occupant and shortcut-reopen regressions use isolated JUnit temporary folders rather than Robolectric's
long per-test application paths, which can exceed the Windows SQLite path limit. Absolute database paths and
named-file reuse across independent handles are preserved, so these tests still exercise real on-disk SQLite
transactions and database reopen.

| Root task | What it checks |
| --- | --- |
| `:app:assembleDebug` | Debug APK at `android/app/build/outputs/apk/debug/app-debug.apk` (local debug signing only) |
| `validateModuleGraph` | Production configurations against [`modules.md`](architecture/modules.md): cycles, forbidden project edges, Android leaking into JVM modules; writes `android/build/reports/scaffold/module-graph.tsv` |
| `verifyScaffoldTests` | Modules' registered test suites plus Python hooks; required root suites need nonzero, unskipped results (`test-discovery.tsv`). Local default includes the entire app; CI explicitly delegates app tests to guarded shards. Dependency-lock maintenance is separate. |
| `verifyAppTestResults` | Runs app tests, optionally one `-PscaffoldAppTestShard=index/count` partition, and rejects zero, failed or skipped results |
| `:app:prepareAppTestRuntime` | Resolves app unit-test runtime artifacts and Robolectric SDKs for CI shard cache seeding |
| `verifyRoomSchema` | Optional bootstrap diagnostic: a Room/KSP schema export for the toy verification fixture, not a product schema. Not part of ordinary CI. |
| `runtimeDependencyInventory` | Exact linked runtime component and POM license inputs (`runtime-dependencies.tsv`); not legal approval |
| `resolveScaffoldDependencies` | Resolves the configured component graphs, for regenerating locks |
| `lintScaffold` | Shipping/test-support main-source lint, excluding the unused bootstrap Room fixture; test sources remain compiled/executed, not separately lint-analyzed |
| `checkScaffold` | Production graph, tests (including real database), inventory and lint together |

JVM modules use JUnit 5; Android modules use JUnit 4 and Robolectric 4.17 on the locked, checksummed
`org.robolectric:android-all-instrumented:12-robolectric-7732740-i7` SDK 31 artifact. That is not a substitute for an
API 37 instrumentation run.

`gradle.properties` sets no `org.gradle.jvmargs`; CI exports
`GRADLE_OPTS='-Dorg.gradle.jvmargs="-Xms64m -Xmx3072m -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8"'`.
Do the same locally if the Gradle daemon runs out of memory. Modules register their test suites with the root
`verifyScaffoldTests` task from their own `build.gradle.kts`, and that task also runs the Python hooks below
(emoji, localization JUnit validation, notice and packaging checks, and the benchmark reader tests). It does not cover
`:core:testing`, which CI names explicitly. Notifications and protocol tests are already registered and are
not listed a second time in CI (Gradle deduplicated those task names already; this is command cleanup, not a
claim of fewer executed tests).

Routine Python checks, from the repository root:

```
python tools/android-port/extract_vectors.py --check      # frozen Swift vectors
python tools/android-port/portmap.py                      # PortedFrom/AndroidOnly header traceability
python tools/android-port/emoji_convert.py --check
python android/scaffold/sync_notices.py                   # needs full git history, not a shallow clone
(cd tools/android-port && python -m unittest discover -s oracle -t .)
PYTHONPATH=tools/android-port python -m unittest docs.android.localization.test_l10n_convert.JUnitRetentionTest
```

In PowerShell, set `$env:PYTHONPATH = 'tools\android-port'` before the final Python command.

The app build no longer regenerates themes or localization from the pinned Swift tree on every `preBuild`.
Native theme and compiled-resource tests still exercise the committed fixtures. The localization JUnit guard
checks those actual reports without loading reference sources. To check the original converters explicitly:

```
python tools/android-port/l10n_convert.py --check --self-test
python tools/android-port/theme_convert.py --check --self-test
```

## CI

`android-ci` is the routine Android-only workflow. It never invokes Swift or schedules a macOS job.
Inherited iOS build/lint, IPA publishing and TestFlight workflows are removed from this Android fork;
the Swift specification remains untouched. Copilot setup installs the toolchain without automatically
resolving every debug/release/test dependency graph or spawning a duplicate PR validation run.
`source-checks` always runs the Python checks and selects work with
[`ci_scope.py`](../../tools/android-port/ci_scope.py). PR selection uses the complete Git diff against the
merge base; `main` pushes use the previous pushed commit. Deletions and both sides of renames are included.

- Documentation-only changes run source/tooling checks, without an Android build.
- Ordinary changes run the changed modules' tests. Shipping Kotlin/Java changes also run production lint and
  compile the app, without dexing or packaging an APK. Resource, asset, manifest and other shipping-input
  changes assemble and inspect the debug APK; test-only and developer-tool changes do not. App and shared
  app-test changes run the app suite in five guarded shards, or one for a fork PR. Changed instrumentation
  sources are compiled, not run on an emulator.
- A change to one module no longer runs every other module's tests or app integration tests on that push.
  Cross-module behavioral regressions can therefore wait until the full suite; app compilation still checks
  shipping integration when production code changes. Packaging-only failures from code changes can also wait
  until the full run. Shared test fixtures, unknown paths, build/lock/checksum changes and CI/oracle/evidence
  tooling conservatively select the full run.
- The complete suite, all production lint, module graph and runtime license inventory, dependency resolution,
  APK and instrumentation compilation run nightly at 05:17 UTC and on an unfocused manual run. Full
  `benchmark-harness` release/profile/benchmark builds run on those full non-PR runs. Strict locks and
  checksum verification still apply to every configuration consumed by focused runs.

The existing `android-ci` aggregate requires successful source checks and the selected Android build, app
test preparation and shards, and full-run lint/packaging job. It accepts `skipped` only when that part was
deliberately omitted. No new required branch status or merge gate is added.

[`backup-compatibility.yml`](../../.github/workflows/backup-compatibility.yml) is a separate **manual-only**
diagnostic for explicitly requested live iOS/Android backup compatibility evidence. It does not run on PRs
or pushes, is not a dependency of Android CI, and is not a merge gate. Its pinned v1 harness already executes
the codec oracle, so the redundant standalone Swift job is removed. Existing bidirectional v1/v2 checks are
preserved there; committed reference fixtures remain part of ordinary Android tests.

```
gh workflow run android-ci.yml --ref <branch>   # run it on demand, including the benchmark job
gh workflow run android-ci.yml --ref <branch> -f focus_path=android/core/protocol/src/main/kotlin/Packet.kt
gh workflow run backup-compatibility.yml --ref <branch> # explicitly opt into live compatibility diagnostics
gh run download <run-id> -n debug-apk           # fetch the APK when the selected scope built one
```

The pinned MIT `gradle/actions/setup-gradle` v5 action replaces whole-home cache archives. It deduplicates
downloaded dependencies/wrappers across entries, removes unused restored state after successful builds, and
reports cache use in the job summary. The Android build writes on `main` and same-repository PR revisions.
In opted-in compatibility runs, the narrow backup producer seeds compiled database/codec state early for
subsequent Room jobs. The v2 Room job waits for that seed instead of racing another cold compilation; a v1
test failure does not prevent separately reporting v2 results. Fork PRs, Room follow-ups and benchmark jobs are
read-only, as are the app shards. GitHub scopes PR cache writes to the PR ref: they cannot
replace `main`'s entries. No Build Scan publication, dependency submission or new credentials are enabled.
The first run after the cache-provider change is cold; speed claims must distinguish cold from warm runs.

Both optional Room interop follow-up jobs enable Gradle's build cache rather than only downloading a cache archive
they cannot use for task outputs. Backup tests declare fixture inputs and per-test-task exports, so cache hits
also restore the files required by Swift. Live Swift-input tests still run for their distinct input/filter.
When app tests are selected, a separate job compiles them and resolves their runtime dependencies and
Robolectric SDKs in parallel with the other Android checks. It saves those inputs in the Gradle cache before
five read-only app jobs partition **all compiled class names** deterministically; nested classes stay with their
parent and exact patterns avoid prefix-sibling duplicates. Resolving the runtime once avoids five simultaneous
downloads of large test dependencies. Fork PRs cannot write the shared cache, so they use one app shard and may
compile the tests again. A cache hit is not required for correctness, but fork runs can take longer. On a
full run, lint, APK assembly/inspection and instrumentation compilation start independently of preparation,
in parallel with the remaining tests.
Every shard rejects zero, failed or skipped results, and the existing aggregate requires all shards to pass.
`-PscaffoldExternalAppTests=true` and `-PscaffoldExternalPackaging=true` delegate app tests and APK-specific
checks only in the CI build job; without them the local `verifyScaffoldTests` command still runs the entire app
and packaging checks. `-PscaffoldAppTestShard=0/5` (indices 0-4) runs the same
partition locally; invalid indices fail, and every shard has a distinct task-cache input.
The pure-JVM services module no longer adds a hidden whole-app dependency to its own `check` task or
re-adds app execution to the root. App integration coverage belongs to the default root run or its CI shards,
not a second cross-module test dependency.
Shared UI's test task likewise no longer runs four other modules' tests as hidden prerequisites; their own
focused runs and the full suite still execute them.

Each app runner permits two isolated test JVMs within two Gradle workers. Other
modules retain one test JVM each. BLE, preferences, shared UI and themes no longer restart that JVM after
every test class; they use Gradle's ordinary worker reuse, like the existing app runner, while Robolectric
continues to create its per-test SDK/application sandboxes. Local app tests remain single-fork unless
`-PscaffoldAppTestForks=2` (up to 4)
is requested. Do not copy the CI concurrency onto a memory-constrained workstation.
The main Gradle invocation uses `--profile`; its local HTML task timings and raw JUnit XML are retained
on successful as well as failed runs. This makes task cost, test counts and failures inspectable without
publishing a Build Scan. Add `--profile` to the same narrow local Gradle commands when investigating a
slow task; local wall times are not directly comparable to a hosted Linux runner.

The initial cold-cache experiment with six workers/four app JVMs was a regression: run `45480019781`
took 40m59s overall (40m33s for Linux, 64.1 combined runner minutes) and failed two app polling deadlines.
Gradle reported exhausted 768 MiB metaspace, and multiple native test families were substantially slower
than baseline. The revision reduces simultaneous workers and raises the daemon metaspace cap to 1.5 GiB
rather than weakening assertions or treating a timeout as a speedup. The ten-minute goal is not yet verified.
The corrected single-runner experiment `45487772017` passed in 28m32s (27m54s Linux). Its profile identified
15m03s of app tests and 4m04s of app unit-test lint analysis. That is still too slow: the subsequent revision
shards the app instead of oversubscribing one runner and omits test-source lint analysis. Full run `45495442962`
passed in 26m44s (38.73 combined runner minutes); the main job alone took 21m28s, so this still missed the
ten-minute goal. Its four shards ran 3,503 distinct app cases with no overlap or failures: all 3,489 prior
cases plus 14 cases added to `main` between runs. A manual focused feature-and-APK run `45504471649` passed
in 11m55s (11.78 combined runner minutes) with a cold Gradle User Home cache. Its profile spent 1m45s in
external dex merging and 1m53s compiling the app. Code-only changes now retain app compilation without APK
packaging; the full APK check remains for shipping resources and full runs. Full PR run `45504192155` passed
in 18m21s (30.80 combined runner minutes), still above the ten-minute goal; it executed 3,388 distinct app
cases with no shard overlap or failures. Compared with the prior sharded run, all other cases remained, 140
duplicate SDK executions were removed, and 25 cases were added on `main`.
The first parallel-preparation run `45509519897` failed after 16m10s: four shards hit Maven Central HTTP 429
while independently downloading test-runtime artifacts, and the remaining full Gradle build took 13m32s.
Run `45512733276` prepared the runtime once and passed all five shards without rate-limit failures, finishing
in 13m34s with 35.57 combined runner minutes. Its 3,412 distinct app cases include all 3,388 cases from the
previous full run and 24 added on `main`, with no overlap, failures or skips. Full-run lint and packaging took
7m44s after app preparation, so the current revision starts it independently; the new overlap still requires
hosted verification.

### Test relevance audit

Baseline artifact inspection found 18,607 distinct testcase identities, not 18,607 independently written tests.
Protocol and service families account for 8,385 cases but only about 20.5 summed test seconds; the two largest
parser families alone exercise 3,183 independent/truncation boundaries in about 0.3 seconds. Removing those
would discard useful malformed-radio-input protection without meaningful runtime savings.

Removed the ten obsolete `ScaffoldLauncherTest`/`ScaffoldNavigationTest` assertions about the old
not-yet-ported shell; the installed launcher now uses the real App navigation, covered by
`NavigationActivityTest`, `NavigationComposeTest`, `NavigationStateTest` and real graph/Room integration.
`ComposerNavigationLifetimeTest`, `ChannelDraftRecoveryAdapterTest`, `MapMountedConsumerTest` and
`ChatMapPreviewTest` now execute their full cancellation/transaction/cache/mounted-state scenarios once on
SDK 31 rather than repeating them on SDK 37. These suites use injected services/renderer ports and do not test
API-gated behavior. In the baseline that removes 171 redundant SDK reruns and about 103 summed test seconds,
not 171 distinct product scenarios. Native permission, notification, platform and dedicated SDK 31/37
accessibility/layout tests retain their API coverage. Original Swift parameter/vector families and backup
proof assertions are unchanged; actual remaining counts and wall time come from the hosted run.

`ChannelConfirmationConsumerTest` and the API-independent navigation lifecycle scenarios also run once on
SDK 31 instead of duplicating their Room, radio and Compose behavior on SDK 37. The six navigation cases
that exercise API-gated notification permission and delivery still run on both. Based on the previous XML,
this removes another 140 SDK repetitions, not product scenarios; dedicated API and layout suites remain.

The two toy `ScaffoldDatabaseTest` assertions and toy-schema assembly are also removed from ordinary CI,
not from the optional bootstrap diagnostic. They demonstrate Room's own generic upsert/rollback behavior
with a three-field fixture, already covered more meaningfully by the production database/repository suites.
The root nonzero-report guard now checks `core:database`, not the toy fixture. Production KSP schema generation,
migrations, radio partitioning and real SQLite transaction/reopen tests remain. Database tests now also declare
the schema JSON directory as a cache input, so schema-only edits cannot reuse stale migration/schema results.
Cached protocol, runtime, services, shared UI and feature tests also declare the fixture, source and localization
files they read directly, so edits to those inputs invalidate their previous results.

## Maintenance

- **Dependency locks and checksums**: pins live in `android/gradle/libs.versions.toml`, the lock files under
  `android/gradle/dependency-locks/` and `android/**/gradle.lockfile`, and `android/gradle/verification-metadata.xml`.
  After a reviewed version change, regenerate them with `--write-locks --write-verification-metadata sha256` on a run
  that exercises assembly, the test suites and `lintScaffold`, so late-resolved SDK configurations are locked, and
  review the diff. Checksums are trust-on-first-use. This procedure comes from the earlier build guide and was not
  re-run during the process teardown.
- **Notices**: `python android/scaffold/sync_notices.py --write --gradle-archive <verified gradle-9.8.0-bin.zip>`
  regenerates the declared notice and artwork outputs; without `--write` it only checks.
- **Generated outputs**: `python tools/android-port/l10n_convert.py --write` (localization, including
  `docs/android/generated/l10n-key-map.json`, which embeds the converter's own hash, so run it after any edit to the
  converter), `theme_convert.py --write` (themes and `icon-map.json`), `emoji_convert.py --source-dir <dir>`,
  `extract_vectors.py --regenerate`.
- **Moving the Swift reference pin**: there is no tool for it. The old `resync.py`/`bootstrap.py` were retired and
  are in Git history at `b9193a1fb9`. Search for the old SHA (`git grep db14559b39d32322b06477c6ae676112f583db50`) and
  update `REFERENCE_SHA` in `tools/android-port/portlib/model.py`, the manifest `reference` block and `inventory`,
  `not-ported.json`, `reference-amendments.json`, the converters and generators, and every
  `// PortedFrom: <path>@<sha>` header. The checks above refuse to run against a drifted pin. The upstream repository is
  `Avi0n/MeshCoreOne`.
- **Python dependency**: only `docs/android/evidence/WP-102/generate_vectors.py` needs a non-stdlib package
  (`cryptography`); CI does not run it.

## Known gaps

- **Backups, iOS to Android**: `BackupInteropTest` restores a committed Swift-produced fixture
  (`android/core/testing/fixtures/reference-codec/reference-envelope.*`) into a real Room database in every build.
- **Live backup compatibility**: the manual-only compatibility workflow feeds a pinned SwiftData export back
  into Room and exercises the newer self-telemetry backup revision in both directions. Routine Android CI
  does not run these diagnostics. Results are evidence only for the exact run and revision, not a device test.
- **Instrumentation tests** (`android/accessibility-tests/src/androidTest`) are compiled by CI, never run. No result
  here covers TalkBack, an API 31 or 37 device, a physical radio, signing or release behavior.
- **Manifest process fields**: `owner`, `human_gate`, `supervised`, `planned_state`, `write_paths`, `verification`,
  `agents` and the plan hashes in `port-manifest.json` are historical planning metadata. Nothing reads them and they
  gate nothing; `depends_on`, `acceptance`, `inventory` and `implementations` are the live content.
