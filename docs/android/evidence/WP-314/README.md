# WP-314 evidence

Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Integrated base: `698b806413a3d33c8420449b793eb335348785f5`.

## Implemented scope

- Live trace listener and per-generation app/session adapter.
- Editable repeater path, hash width, automatic return, single/batch execution, partial/failure and
  cancellation states.
- Saved-path load/delete/run persistence and source-compatible recent-hop preferences.
- Shared MapLibre route result with hop labels, numeric SNR accessibility text, and source SNR line
  quality.
- Repeater/sensor discovery, localized sorting, existing-contact state, add action, scan timeout and
  explicit stop.
- Adaptive tools-home navigation through the existing Navigation 3 list/detail shell.

## Local verification

Windows, JDK 21.0.12.1, Android SDK 37.2/build-tools 37.0.0:

```text
./gradlew :feature:tools:compileDebugKotlin :app:compileDebugKotlin
  PASS

./gradlew :feature:tools:testDebugUnitTest :feature:tools:lintDebug
  PASS
  35 suites, 641 tests, 0 failures, 0 errors, 0 skipped
  WP-314 trace/discovery suites: 13 suites, 196 tests

./gradlew validateModuleGraph
  PASS: 30 modules; no forbidden production edges or Android JVM leakage

python tools/android-port/portmap.py
python tools/android-port/controller/validate.py
  PASS
```

The tools module dependency lock was regenerated because WP-314 now consumes the already-approved
`:core:maps` MapLibre adapter. No dependency version or verification-metadata change was made.

## Evidence limits

No physical radio, device, GPS, release signing, installation, or hardware reception verification
was performed. Deterministic topology and protocol fixtures are simulations and are not reported as
measured packet reception.
