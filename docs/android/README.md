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
./gradlew --dependency-verification strict verifyScaffoldTests verifyRoomSchema validateModuleGraph
./gradlew --dependency-verification strict :core:protocol:test              # JVM modules: :<module>:test
./gradlew --dependency-verification strict :feature:chats:testDebugUnitTest # Android modules: :<module>:testDebugUnitTest
./gradlew --dependency-verification strict :platform:notifications:testDebugUnitTest
```

Repository and backup Room test contexts, notification SQLite tests, and the file-backed Room UUID-alias
regression use isolated JUnit temporary folders rather than Robolectric's long per-test application paths,
which can exceed the Windows SQLite path limit. Absolute database paths and named-file reuse across independent
handles are preserved; these tests still exercise real on-disk SQLite transactions.

| Root task | What it checks |
| --- | --- |
| `:app:assembleDebug` | Debug APK at `android/app/build/outputs/apk/debug/app-debug.apk` (local debug signing only) |
| `validateModuleGraph` | Production configurations against [`modules.md`](architecture/modules.md): cycles, forbidden project edges, Android leaking into JVM modules; writes `android/build/reports/scaffold/module-graph.tsv` |
| `verifyScaffoldTests` | Modules' registered test suites plus the Python hooks; the four root suites need nonzero, unskipped results (`test-discovery.tsv`) |
| `verifyRoomSchema` | A real Room/KSP schema export for the verification fixture, not a product schema |
| `runtimeDependencyInventory` | Exact linked runtime component and POM license inputs (`runtime-dependencies.tsv`); not legal approval |
| `resolveScaffoldDependencies` | Resolves the configured component graphs, for regenerating locks |
| `lintScaffold` | `lintDebug` for every Android module |
| `checkScaffold` | Graph, tests, schema, inventory and lint together |

JVM modules use JUnit 5; Android modules use JUnit 4 and Robolectric 4.17 on the locked, checksummed
`org.robolectric:android-all-instrumented:12-robolectric-7732740-i7` SDK 31 artifact. That is not a substitute for an
API 37 instrumentation run.

`gradle.properties` sets no `org.gradle.jvmargs`; CI exports
`GRADLE_OPTS='-Dorg.gradle.jvmargs="-Xms64m -Xmx3072m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"'`.
Do the same locally if the Gradle daemon runs out of memory. Modules register their test suites with the root
`verifyScaffoldTests` task from their own `build.gradle.kts`, and that task also runs the Python hooks below
(emoji, localization, theme, notice and packaging checks, and the benchmark reader tests). It does not cover
`:core:testing` or `:platform:notifications`, which CI names explicitly.

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

`android-ci` is the only Android workflow. Jobs: `source-checks` (Python checks above), `build` (Gradle tests,
Room schema, module graph, lint, debug APK, APK inspection), `swift-codec-oracle` and `swift-backup-oracle`
(pinned Swift codecs and a real SwiftData restore, on macOS), and `benchmark-harness` (pushes to `main` and
manual runs only). The `android-ci` job aggregates the rest and fails unless every one succeeded.

```
gh workflow run android-ci.yml --ref <branch>   # run it on demand, including the benchmark job
gh run download <run-id> -n debug-apk           # fetch the debug APK from a run
```

The Gradle cache is restored on every ref and saved only by successful runs on `main`.
Backup interop tests declare their fixture inputs and per-test-task export outputs, so cached results also
restore the backup files required by the Swift oracle.

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
- **Backups, Android to iOS**: the `swift-backup-oracle` job restores the Kotlin export with real SwiftData and
  exports it again. Nothing yet feeds that fresh Swift export back into the Kotlin restore test
  (`-Pwp203InteropInputDir=<dir>` on `:core:data:testDebugUnitTest`), so the live round trip in both directions is
  not automated. Do not claim it until it is.
- **Instrumentation tests** (`android/accessibility-tests/src/androidTest`) are compiled by CI, never run. No result
  here covers TalkBack, an API 31 or 37 device, a physical radio, signing or release behavior.
- **Manifest process fields**: `owner`, `human_gate`, `supervised`, `planned_state`, `write_paths`, `verification`,
  `agents` and the plan hashes in `port-manifest.json` are historical planning metadata. Nothing reads them and they
  gate nothing; `depends_on`, `acceptance`, `inventory` and `implementations` are the live content.
