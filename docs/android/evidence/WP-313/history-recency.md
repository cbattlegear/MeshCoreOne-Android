# WP-313: remote-history recency repair

## Scope and provenance

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `nodes-map-ui-engineer`.
Base: `cb4a79ab906db851800bcf93f7c2ebfb5aa34571` (current main at inspection).
The prior investigation base `99479711bfdb7c621765f09d671068ca8b8318ce`
is not treated as the current candidate.
Swift remains pinned to `db14559b39d32322b06477c6ae676112f583db50`;
the reference commit was fetched and the original files read before editing.

The advisory reservation binds session `2a1ca22d-35a1-4bb2-b641-99b39fcdc4d1`,
WP-313's existing feature/deviation/evidence paths, manifest SHA-256
`11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`
and policy revision
`de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.
The candidate head and authoritative automated verification belong to the PR
and exact-commit hosted job, not a second committed CI-result bundle.

Source-derived omission: pinned `LocationReportRow.swift` and
`LocationReportCallout.swift` render `LocationReportFormat.relativeTime` above
absolute details. Android already implements the calendar-unit selection and
localized ICU rendering in `history/LocationReportFormat.kt`, but
`ui/RemoteNodeHistoryRoute.kt` did not consume it.

The route now shares the same two-line rendering across its report buttons,
inline selected report and full-map selected report. Existing click callbacks,
report filtering, coordinates, altitude, absolute timestamps and surface
placement are unchanged. ICU narrow-style text remains the existing native
adaptation of Swift's abbreviated formatter. The clock is read when rendering,
as Swift reads `.now`; no periodic refresh policy is added.

This repair addresses only the recency omission within `WP-313-behavior` and
`WP-313-source-test-parity`. It does not claim all-WP completion, settle the
unresolved placement/accessory questions or establish device acceptance.

## Focused regression cases

- `HistoryFormattingTest`: zero and subsecond fallback; exact second/minute/hour/
  day/week boundaries in both directions; local calendar days over 23-hour and
  25-hour DST transitions; missing/invalid coordinates preserve time-only details.
  Existing calendar-unit, absolute-time, coordinate and sea-level cases remain.
- `RelativeTimeLocalizationTest`: actual Android ICU text for past, future and
  zero amounts in English, plus Japanese past/future text (API 31 and 37).
  API31's ICU68 falls back from absent English narrow relative patterns to
  short `hr.`/`sec.` patterns; API37 has `h`/`s` narrow text. This native
  variation is retained, not replaced with fixed English. The historical
  patterns are independently recorded in
  [ICU release-68-2 English data](https://github.com/unicode-org/icu/blob/release-68-2/icu4c/source/data/locales/en.txt).
- `RemoteHistoryRecencyTest`: mounted route with an injected fixed clock and
  local map surface. Requires the report button to expose both headline and
  unchanged detail, opens that report's full map and checks both lines, and
  selects an inline map report to check both lines there (API 31 and 37).

The existing module task is `:feature:remotenodes:testDebugUnitTest`; focused
selectors are `*HistoryFormattingTest`, `*LocationReportFormatTest`,
`*RelativeTimeLocalizationTest` and `*RemoteHistoryRecencyTest`.
Use the reviewed local launcher or exact-candidate hosted runner, not direct
Gradle bypasses.

## Local execution limit

This clean Linux host has no configured `ANDROID_CI_STATE`; the documented
`ci.py preflight --local --output <fresh absolute session output>` refused with
`Explicit --state/ANDROID_CI_STATE is required`.
The reviewed `local/check.py --commit HEAD --stages preflight` also refused:
`Missing installed execution-interlock runtime; reviewed installation required`.
Installed Python is 3.12.3, not pinned 3.12.4, and Java is 27, not pinned
21.0.12.1+1. No toolchain or shared-runtime deployment was performed.

Therefore local Android/JVM tests discovered/executed: **0** (not a passing
suite). The cases above are authored but locally unverified. Hosted verification
is required before merge. Physical-device, TalkBack and later device-acceptance
execution have not occurred.
