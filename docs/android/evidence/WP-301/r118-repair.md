# WP-301 R118 consumer repair

Issue: [#400](https://github.com/cbattlegear/MeshCoreOne-Android/issues/400).
Repository: `cbattlegear/MeshCoreOne-Android`; owner: `design-system-engineer`.
Base: `4322381d521f9ae1ecdddd2569679be7b6edd725`.
Source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`;
policy: `fba743efe1ff4ad690fd7c8714abf15fd83b3d664060906b1debd68bc40ffefb`.
Session/attempt: `d7b411d8-e874-409b-8dc6-ad9d7a3dc28c`.
The candidate is the commit introducing this document and its subsequent PR
repair commits; exact hosted jobs and review bind their actual head, not this
document's historical base.

## Confirmed decisions and scope

The user explicitly selected **"Retain the accessible Material adaptation and
raw-string identity (Recommended)"** on 2026-10-29. The question distinguished
approval of existing native semantic surfaces, contrast-fitted foregrounds and
persisted scheme identifiers from UIKit pixel equivalence, screenshot-golden,
physical-display and hardware verification. Those latter results are not claimed.
All ten themes remain unlocked. Billing, store state, entitlement/refund
enforcement, upsell and account dependencies remain intentionally absent.

`AppState.swift:222-230` at the source pin refreshes the theme service during
`notifyDataRestored`. The native process state lacked that dependency and call.
The existing service now reaches AppState from the actual AppContainer and its
refresh is awaited before the restore notification completes. Typed failures
propagate; asynchronous preference observation is retained, not used as a
substitute for the explicit restore boundary. No radio service owns the theme
service and no second instance is constructed.

The source `AppColorSchemePreference.id == rawValue` contract is now an explicit
native property. The mounted settings picker keys each scheme by that string;
DataStore continues to serialize `system`, `light`, `dark`, never enum ordinals
or names. Selection and forced-dark Ember behavior are unchanged.

Primary edits use `wp-owned`. Existing App/settings consumers use
`modify-related-consumer` (`reviewed-support-consumer`); the App harness and new
integration tests use `add-or-update-related-test` (`reviewed-support-test`).
Same-owner reservation revisions 1-4 preserve the original authorization and
identity. Initial generic `modify` support queries were rejected; the proper
named operations were then admitted without a policy change.

Prerequisite merged repairs were verified through GitHub: WP-005 #427
(`6e27202219d401c9464bdad26131102a312b8867`) and WP-204 #437
(`9d1783c97efe70a3aac0f1ee744acdcb1b123561`); both are ancestors of this base.
WP-003's original #4 merged as `979dd73b2fc3cb5cbeea07b1c809f5c9283289ee`.

## Concern-to-consumer evidence

| Concern | Actual boundary and assertions |
| --- | --- |
| APP-G09 | `ThemeConsumerIntegrationTest` constructs the production AppContainer over Room and a real file-backed MeshCoreStorage/ThemeService. It checks AppState and container share that service, default/missing-key semantics, real backup preference restoration through `AppState.notifyDataRestored`, native `ThemeServiceHost` -> `ChatTimeline` -> `MessageBody` layout/span changes, forced-dark Ember, retained composition state and observer counters. Closing the service must make restore notification fail with `OwnerClosed`, detecting an omitted refresh even if background observation wins a successful restore race. |
| APP-G13 | Existing complete `IdentityGamutTest`, `ThemeContrastTest`, `ThemePolicyTest` and `ThemeComposeTest` retain independent pinned palette/identity parameters, original contrast exceptions, all 38 effective frames, raw canvas/card/row semantics and actual Material surfaces. The unchanged App `NativeMessagingAccessibilityTest.renderedDirectAndChannelSpansClearTheirPaintedBubblesInAll38EffectiveFrames` measures actual rich-text spans against their painted bubbles, not only isolated role pairs. New process-restore coverage reads actual TextLayoutResult base/link styles and checks contrast on the corresponding live Material bubble roles. User-approved Material adaptation does not relabel raw source tokens or claim UIKit pixels. |
| APP-G16 | `ThemePolicyTest.preferenceCases` checks exact case order, all three raw IDs and round trips. The new mounted scheme-selection test uses the real AppearanceScreen/controller/service/store, checks selected semantics, exact persisted `id`, explicit AppState refresh and effective host scheme for dark/light/system. |
| APP-G19 | No store or billing graph is introduced. Existing `ThemeServiceTest` unlocked adaptations retain all ten themes, persisted selection, restore and defaults. New App tests restore named themes while disconnected without ownership/account services. Removed billing-only families remain exclusions, not passing ports. |

## All 36 distinct direct original families

The four concern counts overlap; their sum is not a new family count. Existing
`collect_evidence.py --inventory --self-test` provides the full 58-family WP-301
source inventory (55 mapped, three removed) and original parameter cardinalities.
The following is the bounded #400 subset, not new global completion credit.

| Frozen source suite | Direct original family | Native disposition |
| --- | --- | --- |
| AppColorSchemePreferenceTests | allCases is exactly system, light, dark and id equals rawValue | Explicit raw ID property, policy test and real mounted persistence selection. |
| AppStateThemeWiringTests | AppState exposes a storeState wrapping an idle StoreService | Removed commercial graph; not a passing Android port. |
| AppStateThemeWiringTests | AppState exposes a themeService defaulting to the default theme | Same process instance/default tested through actual AppContainer/AppState. |
| AppStateThemeWiringTests | notifyDataRestored does not wipe a restored paid theme while the store is unloaded | Real backup preference/AppState/host restore; all themes unlocked. |
| ErrorUserFacingMessageTests | store service error dispatches to concrete mapping | Removed StoreService error dispatch; not a passing Android port. |
| MessageTextThemeTests | default theme's hashtag color is the one baked into the hashtag run | Raw source role retained; native foreground fits the actual bubble under the approved adaptation. Existing source-text and mounted span suites plus real restore consumer. |
| MessageTextThemeTests | incoming hashtag color is baked: different hashtagColor yields different output | Existing source-text assertion plus actual theme-restore rich-text change. |
| MessageTextThemeTests | outgoing text color is baked: different outgoingTextColor yields different output | Existing source-text assertion plus actual outgoing theme-restore rich-text change. |
| IdentityGamutTests | avatar glyph clears AA against the identity fill in both appearances | Complete original 1,212 parameters, native glyph fitting separate. |
| IdentityGamutTests | every identity color clears AA against its surfaces in both appearances and contrasts | Complete original 2,424 parameters. |
| IdentityGamutTests | hue is stable across appearance and contrast for a given name | Complete original 50 identities. |
| ThemeContrastTests | avatar glyph clears AA against the identity fill for every theme and appearance | Complete original 3,800 parameters. |
| ThemeContrastTests | channel, repeater, and room avatars resolve to distinct on-anchor hues for every gamut theme | Complete original nine themes. |
| ThemeContrastTests | gamut-derived category colors clear AA against the list canvas | Complete original 102 parameters. |
| ThemeContrastTests | identity colors clear AA against their surfaces for every theme and appearance | Complete original 15,466 parameters. |
| ThemeContrastTests | incoming hashtag links clear WCAG AA 4.5:1 against the incoming bubble in every appearance | Complete original 34 non-default frames; mounted native bubble audit keeps actual consumer floors. |
| ThemeContrastTests | outgoing text clears WCAG AA 4.5:1 against the accent in every appearance | Complete original 34 non-default frames; System exception retained, native readable text fitted separately. |
| ThemeStructureTests | Default theme imposes no chrome tint, deferring to the system | Raw policy preserved; Material semantic mapping approved. |
| ThemeStructureTests | Default theme paints no surfaces | Raw policy preserved; native system tiers remain explicit. |
| ThemeStructureTests | Ember paints the canvas but not the card tier | Raw canvas/card policy preserved. |
| ThemeStructureTests | Every painted theme defines both canvas and card tiers | Raw source tiers preserved. |
| ThemeStructureTests | Every theme defines a usable identity gamut | Original anchors/saturation preserved. |
| ThemeStructureTests | Migrated themes' outgoingTextColor resolves differently in light vs dark | Original per-appearance source roles preserved. |
| ThemeStructureTests | Only the System theme pins fixed category avatar colors | Original fixed category colors preserved; native glyph accessibility adaptation separate. |
| ThemeStructureTests | Paid themes impose their accent on chrome | Named-theme raw chrome tint preserved, no entitlement requirement; accessible Material foreground mapping approved. |
| ThemeServiceErrorTests | notOwned carries the productID and has a non-empty localized description | Removed commercial failure; not a passing Android port. |
| ThemeServiceOwnershipTests | a theme owned via the bundle is accessible and selectable | Approved unlocked adaptation: every theme selectable without bundle/ownership. |
| ThemeServiceOwnershipTests | owning the bundle makes every theme available | Approved unlocked adaptation: every theme always available. |
| ThemeServiceOwnershipTests | refreshFromUserDefaults reverts an unowned restored theme once the store is loaded | Approved unlocked adaptation: no ownership-triggered reversion; restored known theme retained. |
| ThemeServiceOwnershipTests | refunding the bundle reverts the selected theme to default | Removed refund behavior; not a passing Android port. |
| ThemeServiceOwnershipTests | the load() walk does not wipe a persisted theme on an empty ownership read | Approved unlocked adaptation: known persisted theme retained with no store walk. |
| ThemeServicePureTests | with no purchases, only the default theme is available and paid themes throw on setCurrent | Approved unlocked adaptation: all ten available/selectable, no purchases or entitlement errors. |
| ThemedSurfaceRowFillTests | canvas-only theme (Ember) leaves rows on the system tier regardless of flattening | Original row-fill policy preserved; native system-tier representation approved. |
| ThemedSurfaceRowFillTests | card theme emits no row fill when flattened, so the iPad Settings sidebar keeps native selection | Original transparent row-fill decision retained; no claim to UIKit sidebar rendering. |
| ThemedSurfaceRowFillTests | card theme paints rows with the card tier in a normal (non-flattened) context | Original card-tier decision retained. |
| ThemedSurfaceRowFillTests | default theme has no surfaces, so themed row backgrounds are a no-op | Original no-op modifier decision retained. |

## Verification and limits

Executed locally before publication:

- `python tools\android-port\controller\verification_config.py --check`: valid.
- `python tools\android-port\controller\validate.py`: valid; 64 WPs, 178 edges,
  unchanged frozen source.
- `python tools\android-port\theme_convert.py --check --self-test`: 22 discovered,
  22 passed, zero failures/errors/skips; ten themes, 44 color assets, 89 primary
  inputs, twelve recovery locales.
- `python docs\android\evidence\WP-301\collect_evidence.py --inventory --self-test`:
  eight reader tests passed; static inventory 58/55/3 and 92 existing module
  methods. This is not new native execution or the App integration test count.
- `python tools\android-port\portmap.py`: the first run correctly rejected mixed
  PortedFrom/AndroidOnly dispositions in the new test header. The corrected
  source-linked header passed.

The documented local preflight returned `BLOCKED: Explicit
--state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
The existing retained local Gradle execution lock is tracked in #440. Neither
condition authorizes bypass, runtime installation, manual lock deletion or
claims of native test success.

After committing `e0e493e39ebee70cc9ae4d5eb2bc67e024273560`, the supported
`python tools\android-port\local\check.py --commit
e0e493e39ebee70cc9ae4d5eb2bc67e024273560 --affected-base
4322381d521f9ae1ecdddd2569679be7b6edd725` returned `BLOCKED: Installed execution
runtime is old or mismatched: hook_test.py`. Existing supervised runtime
reconciliation is tracked in #444. Hosted execution remains the independent
route; no candidate helper was installed into the privileged runtime.

The trusted shared software lane and change-scoped hosted workflow apply:
`ci.py preflight`, `ci.py run --stage scaffold`, and `ci.py inspect` with the
actual selected obligations. Empty WP-301 macro commands do not independently
block this eligible, capability-admitted repair; see `controller/render.py`,
`controller/verification.py` and `docs/android/ci/README.md`.
Exact-candidate hosted jobs/logs and independent automated review are mandatory
before merge. Do not retain a second reproducible CI-result bundle here.
No new goldens, Swift/UIKit run, physical display, hardware, signing, release
or human-gate execution is asserted.
