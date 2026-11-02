# Issue 401: bounded WP-304 consumer repair

Repository: `cbattlegear/MeshCoreOne-Android`. Owner: `design-system-engineer`.
Assignment/session: `9da73fe8-397a-4bbc-b3ed-89c39184632d`.
Base: `d7f35bbe938ca79d3bc8ab5510fb5cfa9cf8a808`.
Source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA-256: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
Policy revision: `fba743efe1ff4ad690fd7c8714abf15fd83b3d664060906b1debd68bc40ffefb`.
The Git commit/PR supplies the exact candidate head; this document is a repair
scope, not a CI-result bundle or a full-WP completion receipt.

Merged prerequisites were checked as HEAD ancestors: WP-301 PR469
`a5481cdc40adb841a91beea25b3eb28d89e2f2c0`, WP-005 PR427
`6e27202219d401c9464bdad26131102a312b8867`, WP-204 PR437
`9d1783c97efe70a3aac0f1ee744acdcb1b123561`. The landed WP-304 producer is
PR33, merge `67857474ef3d3012ab73c19a4943ec1334c20676`.
Issue closure is not the prerequisite evidence.

## Scope and original obligations

The exact stable original-family keys remain those in issue 401 and the unchanged
source map: 36 distinct direct families, not a fresh whole-WP claim. Source and
original parameter families remain pinned. No original expectation is weakened.

| Concern | Original families | Producer, consumer and authored assertions |
| --- | ---: | --- |
| APP-G05 | 8 | Existing `AppState`/`ConnectionUIState` and connection-manager snapshots reach `MainActivity` -> `AppConnectionStatus` -> `SyncingPillView`. Preserve `SyncingPillTest`'s connecting/syncing/ready/disconnected/failed/custom-message/hidden/tap cases. Existing AppConnectionStatusTest exercises live runtime connection/loss and mounted priority/recovery. No new connection owner. |
| APP-G11 | 5 | `ErrorLocalizationTest`: advertisement not-connected, room permission-denied, binary timeout, persistence contact-not-found, settings session delegation. Frozen original English literals and retained causes; SharedUiComposeTest additionally renders each in a real Material dialog. Resource accessors are not expected results. |
| APP-G14 | 10 | BatteryDisplayTest retains all five source icon-name families and three source color-role families. Shared native battery pixel tests assert five actual fill levels, normal/orange/red foreground and text/progress. The mounted radio-menu test inspects actual glyph fill/color for independent OCV values at 0/15/25/50/75/100 percent, including orange warning against the menu's actual container color. RSSITuningTest retains the three-row fill parameter family and source-color family; signal pixel tests inspect all three bars. Mounted scanner tests assert independent row-specific colors/bar counts on a painted Material Surface, one announcement and unchanged selection. |
| APP-G15 | 1 | ServicesBasicTest retains the re-export source family but now executes the Kotlin fixture compiled by compileProtocolApiConsumer against only the contracts API closure. Independent request bytes `14` and actual parsed `MeshEvent.Ok` prove usable exposed types. Kotlin explicit imports are an approved adaptation, not Swift namespace identity. |
| APP-G17 | 2 | ErrorUserFacingTest's protocol and native-storage dispatch families have independent visible literals, actual typed values and unchanged metadata/recovery. Existing StoragePresentationTest/SharedUiComposeTest cover native fault categories, retained causes, secret suppression and actual unlock/preference-only actions. No Apple OSStatus or live physical storage failure is claimed. |
| APP-G21 | 10 | RSSITuningTest's original single smoothing vector and RSSIScanTrackerTest's original name/helper assertions remain, supplemented with absolute -51/-72/-48 vectors. RelativeTimestampTest keeps all eight original nonempty/space-based minute/hour/yesterday/day/old-date/week-boundary families, with separately named exact native literal vectors. Stronger oracles are not retroactive Swift assertions. |

Battery artwork is original Canvas geometry, not extracted SF Symbol artwork.
Fill buckets retain 13/38/63/88 boundaries; warning thresholds retain 10/20.
Signal fill/threshold/hysteresis/smoothing source values are unchanged. Decorative
glyphs do not duplicate the enclosing row's spoken text.
Standalone and mounted battery assertions inspect painted cap/outline pixels even
at empty fill, so a missing or wrongly colored critical glyph cannot pass only
through background pixels and foreground semantics.

## Capability and decision record

The same-owner reservation was acquired and evolved through trusted `reserve.py`,
preserving its identity/attempt/audit context. Core UI and WP-304 documentation
use `wp-owned`; the existing WP-305 scanner uses `reviewed-support-consumer`;
its directly necessary App-mounted test uses `reviewed-support-test`.
No policy, controller, shared runtime, Swift source, resource or lock was edited.
Issue 493 tracks the bounded scanner support fixed in this same candidate.

The maintainer selected **Approve this native mapping** in the session before
implementation. The actual approval is also recorded on issue 401. It covers
native presentation only, not fabricated runtime/device/Apple equivalence.

## Verification boundaries

The inherited empty whole-WP manifest entry does not prohibit this bounded
landed-WP repair: `docs/android/ci/README.md` declares the shared affected/scaffold
verification route. The existing UI `verifySharedUiTests`/`testDebugUnitTest`
and App/onboarding tests are registered with the scaffold. The compiler fixture
is a dependency of UI tests, uses the already-locked compiler configuration and
API graph, and fails if compilation fails or its class is missing. Generated class
bytes are declared test inputs so fixture-only changes invalidate test execution.
Each compiler invocation clears its dedicated generated-class directory, preventing
a renamed fixture from passing through an obsolete consumer class.
Mounted glyph assertions draw their owning Compose view with native graphics;
they do not depend on unimplemented Robolectric PixelCopy callbacks or assume an
unpainted activity window has the theme surface color.

Local availability is blocked, not failed product tests: preflight requires an
explicit same-host `ANDROID_CI_STATE`; the supported local launcher then reports
`Installed execution runtime is old or mismatched: controller/execution_recovery.py`.
No Kotlin/Compose/JVM test discovery is claimed locally. Runtime deployment,
SDK-license acceptance and physical-device execution remain separate gates.

The authoritative exact-head hosted job and logs supply reproducible pass/fail
and actual test discovery. Do not commit, download/hash/revalidate or create a
second CI-result bundle. Missing rendered/device acceptance cannot be replaced
with semantic/resource tests. This repair makes no whole-WP dependency-unlock,
physical radio, TalkBack, signing, release or legal-verification claim.
