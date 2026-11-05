# WP-313 TS-02: Android locale and measurement presentation

Source-only candidate based on `99f03ad2da235e66c5cbcb58e88ac4d9aa55d2c5`.
Successor of source-only `8441dbb99090855a9c0426d69b13d7eddfcb4d2f`;
that immutable candidate is preserved, not executed or rewritten.
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

The history holder is remembered by data identity/store/clock/zone, not locale
or units. A latest-value presentation provider supplies current locale, unit
system and resource resolver to grouping and name resolution. Locale changes
therefore retain loaded snapshots, errors, loading state and the selected time
range without relaunching the data-load effect. Chart selection is remembered
by its timestamp list rather than the unit-dependent chart model; converting
the same samples does not reset a non-latest selection.

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
   and error remain with every store-call count still two. This detects both
   unintended reload and unit-dependent scrub reset. The store is deterministic
   test data, not a claim of real persistence.

## Admission, verification and limits

The actual immutable-main `CapabilityEngine`, its `errors`/`paths`/`schema`
imports, policy and manifest were loaded in memory without ledger access.
All eight candidate paths admit as WP-313 `wp-owned`: five production `modify`,
two test `create`, one owned evidence `create`. Capability revision:
`7680d62ed91fa7b9455647745901c07ac962966fba9ae26ab69e09a498911396`.
This is path-rule evidence, not an identity receipt or a claim mutation.

No local build/test, Git fetch/decompression, new worktree, lock change,
workflow dispatch or hosted execution was performed for this source candidate.
Tests above are authored controls, **not passing results**. Exact candidate
source review precedes hosted execution; no previous candidate's checks
transfer. Merge remains behind #586's bounded landing window and all other
explicit holds. No physical-device, exact Foundation-oracle execution, real
radio/persistence, or whole-WP acceptance is claimed.
