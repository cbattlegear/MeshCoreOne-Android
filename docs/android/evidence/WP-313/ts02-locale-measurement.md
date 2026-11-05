# WP-313 TS-02: Android locale and measurement presentation

Source-only lifecycle successor of `e0e2649de41d4fc723d79cfe64b4b8c89cd27467`,
composed against main `b22a7a987f2cbdcaa223c8f8b45506eecb24cbe6`.
It preserves the previously reviewed locale and chart changes; prior immutable
source candidates and hosted evidence are preserved, not rewritten or reused.
Original source baseline: `99f03ad2da235e66c5cbcb58e88ac4d9aa55d2c5`.
Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Relates to #408; this does not close the whole work package.

## Concrete repair

The mounted status/telemetry, location-history and metric-chart consumers read
`Locale.getDefault()` even though their strings already use the app resource
configuration. With a per-app locale different from the process locale, this
mixes translated labels with the wrong number separators, dates and units.
Those consumers now read `LocalConfiguration.current.locales[0]`, following
`remoteText`'s existing resource-locale policy. Reading the configuration also
invalidates composition on a locale change. The existing remembered neighbor
map calculation now keys its locale-dependent labels on that locale too.
No map implementation, route/identity policy, command text or stored SI value
is changed.

The history holder is retained by the Activity's `ViewModelStore` under node
public-key/radio identity, not by a composition or changing store wrapper. A
latest-value presentation provider supplies current locale, unit system and
resource resolver to grouping and name resolution. Locale changes and actual
Activity recreation therefore retain loaded snapshots, errors, loading state
and selected time range without relaunching the initial store load. Explicit
user refresh remains available. The retained loader compares an explicit
authority identity for the persistence adapters' underlying snapshot, contact
and discovered-node dependencies, rather than the freshly allocated adapter
wrapper. A changed authority or disconnected store cancels the previous job
and installs a fresh holder; changed clock or system zone does the same,
preserving the earlier reload behavior for those dependencies. A late
cancellation-insensitive completion can only mutate its detached old holder,
not the currently displayed state. Chart selection uses `rememberSaveable` with
stable chart identity and timestamp inputs rather than the unit-dependent
chart model; converting the same samples or recreating the Activity does not
reset a non-latest selection.
Sensor identity is the original channel plus `TelemetryChartGroup.key`, carried
through `MetricChartModel` independently of localized title/order/unit/value.
Sensor compositions use that identity as their Compose key. Radio metric
compositions key their invariant title resource; neighbor compositions key the
public-key prefix. The history chart subtree is additionally keyed by node
public key and radio identity, so another node with identical timestamps gets
fresh chart state. Selected map report and map visibility also reset on node
replacement, not on locale changes.
Neighbor chart selection has an explicit public-key-prefix identity, separate
from its resolved display name, because locale-aware collision resolution can
change that name without changing the neighbor.

App wiring uses the existing `SystemRemoteNodesClock` singleton instead of
allocating an identical system-clock wrapper per service adapter. A pure
Activity recreation thus keeps both the backing authority and clock dependency
stable; genuinely different injected clocks still replace the holder.

ICU's regional `LocaleData.getMeasurementSystem` is retained for normal locales
and regional `rg` overrides. Its supplemental-region lookup does not implement
the Unicode `ms` preference; `MeasurementSystem.of` now gives the three
recognized explicit preferences (`metric`, `ussystem`, `uksystem`) precedence.
Unknown preferences use the ordinary regional policy, rather than inventing a
fourth system.

Platform implementation reference for that distinction:
<https://github.com/unicode-org/icu/blob/release-68-2/icu4j/main/classes/core/src/com/ibm/icu/util/LocaleData.java>
(`measurementTypeBundleForLocale` and `getMeasurementSystem`).

## Deliberate Android/source-equivalence choices

- The pinned `LPPSensorType+LocaleUnits.swift` converts temperature, pressure,
  altitude and distance for **both US and UK** systems. Retain that source rule,
  including Fahrenheit/inHg/feet for UK; do not silently substitute common UK
  weather conventions. Metric stays Celsius/hPa/metres. The selected resource
  locale and its recognized preference determine the system.
- Preserve source conversion coefficients and per-type fraction lengths.
  Voltage is measurement-system invariant, not decimal-separator invariant:
  `3.850 V` versus German `3,850 V`. Non-sensitive units and the battery
  predicates remain unchanged.
- History altitude deliberately stays in whole metres or feet, with explicit
  `m`/`ft` symbols at every magnitude, including zero and negative altitude.
  This is the bounded Android adaptation authorized for this slice, not a
  reproduction of Foundation `MeasurementFormatter.naturalScale` (which can
  choose km/mi/in/mm). No claim of exact Foundation rendering is made.
- Keep dotted four-place coordinates, the absolute detail and relative-recency
  headline. Relative recency uses existing Android ICU text; date/time uses the
  existing platform locale/zone styles. ICU/CLDR versions may differ in spacing,
  wording and date patterns. No hardcoded Apple-style date or recency strings
  are introduced.

## Original cases and additional discriminating controls

The pinned formatting/battery families below remain intact, rather than being
replaced with generated expectations. The shared relative-recency control is
included for context; this table does not redefine the issue's family census:

| Original family | Retained case/control and supplement |
| --- | --- |
| Location altitude non-empty localized length | Existing `LocationReportFormatTest`; independent literal 42 m / 138 ft plus the mounted actual history row |
| Location sea-level altitude still renders | Existing case; literal `0 m` / `0 ft`, negative and large altitude boundaries in `LocaleMeasurementParityTest` |
| Relative time for a past report non-empty | Existing `LocationReportFormatTest`, `RelativeTimeLocalizationTest` and actual history recency mounted controls are retained |
| Battery minimum 3.0 V | Existing `LppDataPointDisplayTest`: 0% |
| Battery maximum 4.2 V | Existing case: 100% |
| Battery midpoint 3.6 V | Existing case: 50% |
| Battery 3.9 V | Existing case: 75%; invariant under all tested display locales |
| Battery below minimum | Existing case: 0% |
| Battery above maximum | Existing case: 100% |
| Battery integer input | Existing case: null |
| Battery non-voltage input | Existing case: null |
| Battery percentage input | Existing case: null |
| Voltage suffix | Existing case; literal localized voltage values across the independent locale/unit matrix |
| Temperature localized formatting | Literal 25.5/25,5 Celsius and 77.9/77,9 Fahrenheit, independent of production conversion/format helpers |
| Celsius-to-Fahrenheit conversion | Existing numerical checks retained; new literal displays exercise actual measurement-system lookup |
| Voltage not locale-sensitive | Existing measurement-system invariant check retained; matrix preserves V while separately asserting decimal localization |
| Altitude uses locale-appropriate unit symbol | Existing original check retained; literal matrix and mounted actual rows discriminate metre/foot presentation |

`LocaleMeasurementParityTest` checks API 31 and 37 against literal expectations
for US, UK, Germany, Canada, all three explicit `ms` preferences and a regional
`rg` override, including pressure, distance, altitude, voltage and temperature.
It does not calculate expected values with production helpers.

`RemoteLocaleMeasurementComposeTest` mounts actual production consumers at
360dp/200% font on API 31 and 37 with process locale deliberately left US:

1. Encode real LPP records, decode/apply through `NodeStatusStateHolder`, mount
   `RemoteTelemetrySection`, change only app configuration to German and then
   US-with-metric-preference. Assert literal visible values and removal of stale
   Fahrenheit/feet; assert cached SI data is unchanged and no fetch is requested.
2. Load two genuine snapshot records through the actual history holder/route.
   Assert both its production chart readout and actual location report change
   units/separators with app configuration; dotted coordinates remain unchanged.
   Select ALL rather than default MONTH and use the actual slider's SetProgress
   action to select the earlier 20.5 C sample (68.9 F), distinct from latest
   25.5 C (77.9 F). Change resources to German, assert ALL and the earlier
   20,5 C sample remain selected, and assert all four store-call counts remain
   one. Explicitly refresh with a snapshot failure, retain cached data/error,
   switch to US-with-metric-preference, then assert ALL, 20.5 C, report details
   and error remain with every store-call count still two. Recreate the mounted
   ComponentActivity, reinstall the route and assert the holder, selected
   range, earlier chart sample, cached data and error survive with no third
   store call. This detects unintended reload, lost Activity-scoped state and
   unit-dependent scrub reset. The store is deterministic test data, not a
   claim of real persistence.
3. Load Humidity and Power histories on the same channel with identical two
   timestamps. Select earlier Humidity 40.5% and leave Power at latest 20.5 W.
   Assert the real localized titles order Humidity before Power in English,
   but Leistung before Luftfeuchtigkeit in German; assert each retained its own
   sample (40,5% and 20,5 W) without any extra snapshot read. Switch the node
   public key, keeping timestamps identical, then assert the new node starts at
   latest Humidity 60,5% and Power 20,5 W with exactly one new load.
4. Mount a real neighbor `MetricChartModel`, scrub its earlier sample, then
   change only its resolved title while retaining its public-key-prefix
   identity. Assert the displayed title changes but the selected sample does
   not reset to latest.
5. Keep the same node/radio while replacing a history adapter with a fresh
   wrapper over the same authority; assert it does not reload. Then switch to a
   different authority while the first read is suspended in a
   cancellation-insensitive wait, assert the old job is canceled and verify the
   new store's data wins after the old read completes. Disconnect, reconnect to
   the same authority, then replace it with a third store; assert fresh reads
   and content. Changing the clock object and system zone each reloads the
   connected store once. Teardown restores the prior process zone/locale.
6. Exercise the production adapter identity directly: fresh wrappers over the
   same three backing roles compare equal, while replacing any single role
   changes identity without invoking the roles' own equality or read methods.

## Admission, verification and limits

The actual immutable-main `CapabilityEngine`, its `errors`/`paths`/`schema`
imports, policy and manifest were loaded in memory without ledger access.
Eleven feature/evidence paths admit as WP-313 `wp-owned`: eight production
`modify`, two test `create`, one owned evidence `create`. Capability revision:
`7680d62ed91fa7b9455647745901c07ac962966fba9ae26ab69e09a498911396`.
This is path-rule evidence, not an identity receipt or a claim mutation.
The coupled app clock substitution uses `app-build-launcher` with the named
operation `update-launcher-or-composition`, not generic app-file modification.

The original `e0e2649` exact-head hosted run passed, but does not verify the
subsequent lifecycle-review repair. This repair is published to the existing PR
for fresh automatic checks and review; no diagnostic dispatch is added. No local
build/test, Git fetch/decompression, new worktree or lock change was performed.
No prior candidate's checks transfer. No physical-device, exact
Foundation-oracle execution, real radio/persistence, or whole-WP acceptance is
claimed.
