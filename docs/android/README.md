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

Repository and backup Room test contexts, notification SQLite tests, and the file-backed Room UUID-alias,
channel-occupant and shortcut-reopen regressions use isolated JUnit temporary folders rather than Robolectric's
long per-test application paths, which can exceed the Windows SQLite path limit. Absolute database paths and
named-file reuse across independent handles are preserved, so these tests still exercise real on-disk SQLite
transactions and database reopen.

| Root task | What it checks |
| --- | --- |
| `:app:assembleDebug` | Debug APK at `android/app/build/outputs/apk/debug/app-debug.apk` (local debug signing only) |
| `validateModuleGraph` | Production configurations against [`modules.md`](architecture/modules.md): cycles, forbidden project edges, Android leaking into JVM modules; writes `android/build/reports/scaffold/module-graph.tsv` |
| `verifyScaffoldTests` | Modules' registered test suites plus the Python hooks; build logic, contracts, app and production database suites need nonzero, unskipped results (`test-discovery.tsv`). Dependency-lock maintenance is separate. |
| `verifyRoomSchema` | Optional bootstrap diagnostic: a Room/KSP schema export for the toy verification fixture, not a product schema. Not part of ordinary CI. |
| `runtimeDependencyInventory` | Exact linked runtime component and POM license inputs (`runtime-dependencies.tsv`); not legal approval |
| `resolveScaffoldDependencies` | Resolves the configured component graphs, for regenerating locks |
| `lintScaffold` | `lintDebug` for every shipping/test-support Android module, excluding the unused bootstrap Room fixture |
| `checkScaffold` | Production graph, tests (including real database), inventory and lint together |

JVM modules use JUnit 5; Android modules use JUnit 4 and Robolectric 4.17 on the locked, checksummed
`org.robolectric:android-all-instrumented:12-robolectric-7732740-i7` SDK 31 artifact. That is not a substitute for an
API 37 instrumentation run.

`gradle.properties` sets no `org.gradle.jvmargs`; CI exports
`GRADLE_OPTS='-Dorg.gradle.jvmargs="-Xms64m -Xmx3072m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"'`.
Do the same locally if the Gradle daemon runs out of memory. Modules register their test suites with the root
`verifyScaffoldTests` task from their own `build.gradle.kts`, and that task also runs the Python hooks below
(emoji, localization, theme, notice and packaging checks, and the benchmark reader tests). It does not cover
`:core:testing`, which CI names explicitly. Notifications and protocol tests are already registered and are
not listed a second time in CI (Gradle deduplicated those task names already; this is command cleanup, not a
claim of fewer executed tests).

Python checks, from the repository root (all run in CI, directly or through Gradle):

```
python tools/android-port/extract_vectors.py --check      # frozen Swift vectors
python tools/android-port/portmap.py                      # PortedFrom/AndroidOnly header traceability
python tools/android-port/l10n_convert.py --check --self-test
python tools/android-port/theme_convert.py --check --self-test
python tools/android-port/emoji_convert.py --check
python android/scaffold/sync_notices.py                   # needs full git history, not a shallow clone
(cd tools/android-port && python -m unittest discover -s oracle -t .)
```

## CI

`android-ci` is the routine Android-only workflow. It never invokes Swift or schedules a macOS job.
`source-checks` always runs the Python checks and selects expensive
work using [`ci_scope.py`](../../tools/android-port/ci_scope.py). PR selection uses the complete local Git diff
against the merge base, not a truncated API file list; deletions and both sides of renames are included.
Unknown paths, build/lock/checksum changes, workflow changes and oracle/evidence tooling get the full run.
Pushes to `main` and manual runs always get the full run.

- Documentation-only PRs run source/tooling checks, without an Android build.
- Android UI/feature PRs still run **all registered product tests**, module graph verification, runtime license
  inventory, every Android module's lint, debug APK assembly/inspection and instrumentation compilation.
- Backend and backup changes run the same Android suites, including committed backup fixtures and actual
  Room restore/export tests. No live Swift system is needed to build or test the Android app.
- Full dependency-graph resolution (`resolveScaffoldDependencies`) runs for build/dependency/tooling changes,
  on `main`, and on manual runs. Ordinary builds still use strict locks and checksum verification for every
  consumed configuration, plus module-graph and linked-runtime license checks. `resolvePersistenceDependencies`
  remains available explicitly; it no longer runs as a side effect of data tests.
- `benchmark-harness` compiles release/profile/benchmark targets on `main` and manual runs only, as before.

The existing `android-ci` aggregate requires successful source checks and success for the selected Android
build. It accepts `skipped` only when scope deliberately omitted the build. No new required branch status or
merge gate is added.

[`backup-compatibility.yml`](../../.github/workflows/backup-compatibility.yml) is a separate **manual-only**
diagnostic for explicitly requested live iOS/Android backup compatibility evidence. It does not run on PRs
or pushes, is not a dependency of Android CI, and is not a merge gate. Its pinned v1 harness already executes
the codec oracle, so the redundant standalone Swift job is removed. Existing bidirectional v1/v2 checks are
preserved there; committed reference fixtures remain part of ordinary Android tests.

```
gh workflow run android-ci.yml --ref <branch>   # run it on demand, including the benchmark job
gh workflow run backup-compatibility.yml --ref <branch> # explicitly opt into live compatibility diagnostics
gh run download <run-id> -n debug-apk           # fetch the debug APK from a run
```

The pinned MIT `gradle/actions/setup-gradle` v5 action replaces whole-home cache archives. It deduplicates
downloaded dependencies/wrappers across entries, removes unused restored state after successful builds, and
reports cache use in the job summary. The Android build writes on `main` and same-repository PR revisions.
In opted-in compatibility runs, the narrow backup producer seeds compiled database/codec state early for
subsequent Room jobs. The v2 Room job waits for that seed instead of racing another cold compilation; a v1
test failure does not prevent separately reporting v2 results. Fork PRs, Room follow-ups and benchmark jobs are
read-only. GitHub scopes PR cache writes to the PR ref: they cannot
replace `main`'s entries. No Build Scan publication, dependency submission or new credentials are enabled.
The first run after the cache-provider change is cold; speed claims must distinguish cold from warm runs.

Both optional Room interop follow-up jobs enable Gradle's build cache rather than only downloading a cache archive
they cannot use for task outputs. Backup tests declare fixture inputs and per-test-task exports, so cache hits
also restore the files required by Swift. Live Swift-input tests still run for their distinct input/filter.
CI permits four isolated app test JVMs within a six-worker Gradle budget on the public Linux runner. Other
modules retain one test JVM each. BLE, preferences, shared UI and themes no longer restart that JVM after
every test class; they use Gradle's ordinary worker reuse, like the existing app runner, while Robolectric
continues to create its per-test SDK/application sandboxes. Local app tests remain single-fork unless
`-PscaffoldAppTestForks=2` (up to 4)
is requested. Do not copy the CI concurrency onto a memory-constrained workstation.
The main Gradle invocation uses `--profile`; its local HTML task timings and raw JUnit XML are retained
on successful as well as failed runs. This makes task cost, test counts and failures inspectable without
publishing a Build Scan. Add `--profile` to the same narrow local Gradle commands when investigating a
slow task; local wall times are not directly comparable to a hosted Linux runner.

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

The two toy `ScaffoldDatabaseTest` assertions and toy-schema assembly are also removed from ordinary CI,
not from the optional bootstrap diagnostic. They demonstrate Room's own generic upsert/rollback behavior
with a three-field fixture, already covered more meaningfully by the production database/repository suites.
The root nonzero-report guard now checks `core:database`, not the toy fixture. Production KSP schema generation,
migrations, radio partitioning and real SQLite transaction/reopen tests remain. Database tests now also declare
the schema JSON directory as a cache input, so schema-only edits cannot reuse stale migration/schema results.

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
