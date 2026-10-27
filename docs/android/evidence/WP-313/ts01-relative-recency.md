# TS-01: location-history relative recency

## Scope and source

WP-313 / `nodes-map-ui-engineer`; source-only allocation
`ROOT-WP313-TS01-RECENCY-SOURCE-ONLY-001`.
Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`.
The single assigned original family is
`MC1Tests/Formatters/LocationReportFormatTests.swift` /
`LocationReportFormatTests::Relative time for a past report is non-empty()` /
`single`.

The original stimulus is Unix second 1,000,000 with a report 7,200 seconds earlier.
Its required predicate is nonempty rendered relative text, not an exact English
spelling or a `RelativeTime` data value. Frozen `LocationReportRow.swift` shows
the relative headline above the absolute detail and opens the report's map.

## Actual producer and consumers

`ui/RemoteNodeHistoryRoute.kt` renders the existing
`LocationReportRowText.relativeTime(report, clock.now, zone).format(locale)`
above unchanged `detailLine` content. Existing selected-report detail, inline
map, full-map dialog, and button action remain in place. The existing ICU
renderer, date arithmetic, locale/zone selection and injected clock are reused;
no dependency, provider, global ticker or new formatting policy is introduced.

The management history route, saved-contact history route and direct-node
telemetry history route already mount this same consumer. No changes were made
to those entry points, other WPs, or the merged TS-25 CLI-lifetime behavior.

## Mandatory test locators and assertions

File:
`android/feature/remotenodes/src/test/kotlin/com/meshcoreone/android/feature/remotenodes/ui/RemoteNodeHistoryRecencyTest.kt`.

Class:
`com.meshcoreone.android.feature.remotenodes.ui.RemoteNodeHistoryRecencyTest`.

Declared Robolectric SDKs: **31 and 37**, US English, 360 x 800 dp.

- `pastReportRendersNonemptyRelativeTextThroughAndroidIcu`: calls the actual
  feature formatter and Android ICU renderer at the frozen fixed timestamps;
  asserts nonempty output and matches an independent direct Android ICU
  two-hours-past oracle. English spacing/spelling is not hardcoded as Swift
  parity.
- `mountedHistoryShowsRecencyAndAbsoluteDetailAndStillOpensTheSelectedReport`:
  loads one fixed report through the actual history-store interface and mounts
  the real history route. Asserts a visible nonempty relative headline matching
  the independently specified Android ICU two-hours-past result, retained
  absolute timestamp, four-decimal coordinates, sea-level altitude, minimum
  48 dp row height and one actual snapshot read. Clicking the row must open the
  real map dialog with the selected report's absolute details still visible.
  Only persistence and the map surface are local fixtures; the row, formatter,
  ICU renderer, route, selection and dialog are real production code.

Existing pure `history/LocationReportFormatTest.kt` remains unchanged. Its
structured-value assertion is not reported as mounted rendering evidence.

## Verification state

**Native tests, SDK executions, Java/Gradle builds and lint: NOT RUN.**
The two methods across two SDK configurations describe intended discovery,
not observed runner counts. No test discovery or behavioral PASS is claimed.
The source-only authorization prohibits heavy execution, prep, transport and
publication. The WP manifest still has `configured=false`, `commands=[]`;
the separately documented affected launcher does not authorize bypassing an
execution admission or this source-only boundary.

Declared lightweight commands for this freeze:

- `python .\tools\android-port\controller\validate.py`
- `git diff --check`

Actual command results and immutable candidate/tree/path/diff hashes are
recorded in the producer's private frozen handoff after execution; they are
not substituted for native test results. No reproducible hosted CI bundle is
created. No Swift, device, TalkBack, hardware, adaptation approval, human-gate
or whole-WP acceptance is claimed.

## Traceability-only correction after the first guarded attempt

The supported trusted047 attempt for candidate `554e0823e877c76ab0534c9aff2558da8a290405`
stopped during Python verification: `portmap.py` exited 2 because this ported
test also declared an `AndroidOnly` disposition. The producer reproduced that
same error locally before removing only the conflicting header line. The pinned
`PortedFrom` provenance remains; every test body, helper, assertion and SDK
configuration is unchanged, as is the production route.

The prior attempt did not reach preflight, scaffold or inspect. Neither method
on either SDK was discovered or executed: all four mandatory method/SDK
identities remain **MISSING_NOTRUN**. This correction is source-only under
`ROOT-WP313-554E082-TRACEABILITY-SOURCE-ONLY-001`; no native retry, preparation or
publication is authorized. Actual local `portmap.py`, controller validation and
diff-check results are bound in the new private frozen source handoff, not
represented as Kotlin/compiler/JUnit results. The old prepared snapshot and
all prior failure receipts remain immutable.
