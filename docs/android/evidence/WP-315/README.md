# WP-315 evidence (line-of-sight workflow)

Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Integrated base: `origin/main` at
`30ea05458be2a49d8757f9c0fe12a5c3609bf9c2`. This is local candidate evidence,
not a CI, merge, device, hardware or measured-reception receipt.

## Source and behavior accounting

The previously merged WP-315 logic layer accounts for all 103 owned original
cases in `docs/android/test-cases.json`: 89
`LineOfSightViewModelTests`, 6 `ChartCoordinateSpaceTests`, and 8
`FresnelZoneRendererTests`. All remain bound with `@OriginalCase`; there are
zero unbound and zero deferred owned cases. The completion layer consumes those
state/math/chart contracts rather than deriving replacement RF formulas.

The completed operator surface maps:

| Frozen Swift source | Android completion |
| --- | --- |
| `LineOfSightView.swift`, `PointsSummarySectionView.swift`, point/repeater controls | `LineOfSightScreen.kt` |
| `TerrainProfileCanvas.swift`, `TerrainProfileSectionView.swift` | `LineOfSightTerrainProfile.kt` over the tested `TerrainProfileChart` geometry |
| `LineOfSightViewModel` map points/lines/camera fitting | `LineOfSightMapPresentation.kt` |
| `ElevationService`, `RFCalculator`, process contacts/device frequency | `AppLineOfSight.kt` adapters and `LineOfSightFeatureDependencies` |
| Tools destination | `NativeNavigationShell` with process-owned dependencies from `AppContainer` |

`LineOfSightMapPresentationTest` adds four deterministic Android boundary
cases: camera preservation, stable identities/relay line opacity, terrain
obstruction pins, and source-equivalent camera fitting plus pointer-coordinate
conversion. The LoS total is therefore 9 suites and 141 tests: 103 owned
original cases and 38 Android-specific cases.

## Verification commands and observed results

Local environment: Windows, Eclipse Adoptium JDK 21.0.12, Android SDK at
`C:\android-sdk`, Gradle/Kotlin heap 3 GiB, and at most two Gradle workers.

```text
android\gradlew.bat :feature:tools:testDebugUnitTest :feature:tools:lintDebug \
  :app:testDebugUnitTest validateModuleGraph --console=plain --max-workers=2
```

Passed in 7m14s:

- `:feature:tools:testDebugUnitTest`: 35 suites, 643 tests, 0 failures,
  0 errors, 0 skipped; the LoS subset was 9 suites and 141 tests.
- `:app:testDebugUnitTest`: 41 suites, 547 tests, 0 failures, 0 errors,
  0 skipped.
- `:feature:tools:lintDebug`: passed with no reported issue.
- `validateModuleGraph`: 30 modules, no forbidden production edge or Android
  JVM leakage.

The final feature-only rerun after source camera-fit alignment used:

```text
android\gradlew.bat :feature:tools:testDebugUnitTest :feature:tools:lintDebug \
  --console=plain --max-workers=2
```

Passed in 3m10s: 35 suites and 643 tests, with 0 failures, 0 errors and
0 skipped; lint passed. The LoS subset remained 9 suites and 141 tests.

```text
python tools\android-port\portmap.py
python tools\android-port\controller\validate.py
```

Both passed. `portmap.py` reported a valid manifest with 1,866 tracked
reference files, 1,812 owned files, 65 work packages and 185 dependency edges.
The controller validator reported a valid manifest at SHA-256
`58f7ebd7f46bbe0636c71005f20776efe139a287279e4f4708b25ce6bfa3f892`.

```text
cd tools\android-port\tests
python -m unittest test_cli
```

Passed: 28 tests in 23.998s. This includes the new fail-closed capability
support-receipt test, which proves only the exact trusted path and operation are
admitted and rejects unknown capabilities and out-of-rule paths.

## Dependency and support receipts

`feature:tools` now directly consumes the already approved `core:maps` module.
`android/gradle/dependency-locks/feature-tools.lockfile` records only that
resolved MapLibre/core-maps graph; no new external dependency was introduced.
`support-scope.json` binds the app adapter to its source pin, integrated base,
session, exact support path, operation and trusted capability.

## Limits of this evidence

- No Android device or instrumentation run was performed.
- No radio/hardware or reception measurement was performed.
- No persistent elevation cache exists in the frozen WP-218/source contract.
- Open-Meteo elevation and OpenFreeMap tiles remain optional network-backed
  inputs and are not prerequisites for mesh messaging.
- Exact-head hosted CI and merge evidence belong to the PR checks and are not
  duplicated or manufactured here.
