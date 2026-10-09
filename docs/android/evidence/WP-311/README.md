# WP-311 evidence

Repository: `cbattlegear/MeshCoreOne-Android`
Issue: #119
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`
Merged logic predecessor: PR #69 / `7e9584751ca79b874ced996e7056926bc3fc7ae8`
Completion base: `9b60b6965ce009e58517e8451f16f94502ae41f5` (`origin/main`, including WP-316)

This increment completes the native contacts/nodes presentation and production integration:
contacts, blocked/favorite/search/sort states, discovered nodes, add and QR scan/share, contact
details, advertisements, ping, shared MapLibre location, path discovery/editing and explicit
direct/flood confirmations. Camera use is optional and manual URI paste remains available.

## Verification environment

Windows, JDK 21, portable Android SDK 37, private Gradle/Android homes, strict dependency
verification, one Gradle worker and in-process Kotlin compilation. No emulator, physical Android
device, camera hardware or radio hardware was used.

## Commands and observed results

| Command | Result |
| --- | --- |
| `gradlew.bat :feature:nodes:testDebugUnitTest --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed. JUnit XML: **16 suites, 175 tests, 0 failures, 0 errors, 0 skipped**. |
| `gradlew.bat :app:testDebugUnitTest --tests com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.contactShareContentUsesRealEncoderAndParser --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed. One selected test; all three contact types round-trip through the real encoder/parser. |
| `gradlew.bat :feature:nodes:lintDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed; HTML and SARIF reports generated under `android/feature/nodes/build/reports/`. |
| `gradlew.bat :app:assembleDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed after integrating current `origin/main`; debug APK assembled. |
| `gradlew.bat :core:designsystem:verifyThemePackaging --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed after admitting optional `CAMERA` in the exact APK permission allowlist; package `com.meshcoreone.android.debug`, min/target 31/37, notices and test-fixture exclusions verified. |
| `gradlew.bat validateModuleGraph --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed; the feature retains no `core:services` edge. |
| `python tools\android-port\portmap.py` | Passed, exit 0. |

The Gradle tasks also ran the repository localization/theme/notice checks. Their observed
sub-suites were 74 localization converter tests, 22 theme converter tests and 4 theme-notice
tests, all passing. These are supporting checks, not additional WP-311 acceptance counts.

## Source-case accounting

All **98/98** WP-311 source IDs are bound and passing; see
[`source-cases.json`](source-cases.json). The previous logic contribution bound 96 cases. The
two formerly deferred `ContactShareContentTests` cases now run in
`MeshCoreUriParserTest.contactShareContentUsesRealEncoderAndParser` against
`ContactService.exportContactURI` and `MeshCoreUriParser.parseContact`, rather than a fake codec.

The 175 feature tests comprise the predecessor's 173 logic tests plus two QR output tests:

- encoded `meshcore://contact/add` text round-trips through ZXing without changing the URI;
- blank QR payloads are rejected.

## Native completion evidence

- `NodesEntry` dispatches list, discovery and detail destinations.
- `NativeNavigationShell` supplies adaptive list/detail routing and contact/chat/map callbacks.
- `AppNodesFeature` binds process storage and dynamically re-reads `sessions.current` for
  per-connection services.
- CameraX declares only optional camera capability; permission denial provides Settings recovery
  and never removes manual paste.
- QR rendering uses correction level M and fixed black-on-white modules.
- Contact location uses shared `core:maps` MapLibre/OpenFreeMap components.
- Contact/path destructive and routing mutations remain behind explicit confirmations.
- Ordered recent hops persist in process `SharedPreferences`.

## Dependency evidence

CameraX `1.6.2` and ZXing `3.5.4` were resolved through the existing repositories. Strict
verification metadata and only the affected `feature-nodes`/`app` lock states are committed.
The APK packaging allowlist is updated for the optional `CAMERA` permission. No Google services,
analytics, accounts or unrelated permissions were added.

## Limits

This evidence does not claim camera-device, radio, Android instrumentation, iOS, signing,
release or human-review verification. Avatar replacement has no admitted Android media-picker
contract in WP-311; existing avatar data/service behavior is preserved without fabricating one.

## Historical logic evidence

The predecessor mutation checks remain applicable and were not rerun in this completion increment:
sorting, delete masking/guarding, unresolvable hops, late discovery responses, grapheme-boundary
matching, hop de-duplication, scan claim ordering and ambiguous repeater resolution were each
demonstrated to fail at least one owned test when mutated. Frozen Foundation string oracles remain
under [`oracle/`](oracle/).
