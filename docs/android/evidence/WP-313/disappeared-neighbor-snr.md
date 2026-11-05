# WP-313 disappeared-neighbor SNR repair

Bounded #408 follow-up on base `5d4b62f659fbc02a0250b904db4ba4fc8df5bdc3`
in `cbattlegear/MeshCoreOne-Android`; not whole-WP acceptance.
Owner: `nodes-map-ui-engineer`; actual session:
`fc2dfe2e-baca-42ab-87a5-353e93ac6d74`.
Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_m8JwxMKWHjL48Nw6RjuOkzcC`.
The supported advisory claim admits these feature, test, deviation and evidence
paths; it does not acquire or clear native/shared execution locks.

## Source and consumer

Frozen reference remains `db14559b39d32322b06477c6ae676112f583db50`.
`MC1/Views/RemoteNodes/DisappearedNeighborRow.swift` matches manifest blob
`035df6b99927294c42f325318b98b135eed700e3`; the historical commit object
is absent from this host, so provenance was checked against the manifest blob,
not silently advanced to another Swift revision.
Swift renders the previous snapshot's one-decimal SNR, name, not-seen label,
and fallback indicator. `NeighborRows.disappeared` already produces that SNR.
The mounted `RemoteNodeStatusContent` now consumes it, retaining native stacked
text and the unchanged possible-match control. The row merges name, SNR and
not-seen text for accessibility, without swallowing the separate fallback action.
The SNR text explicitly occupies the available row width: hosted native layout
diagnostics exposed an intrinsic-width text box narrower than its measured
paragraph (80 versus 360 px at default English font), so the row no longer
depends on that shrink-wrapped text measurement. The strict overflow assertion
is retained. Feature test failures use the existing repository's full-exception
logging pattern so measurements are visible directly in authoritative job logs.

## Discriminating regression

`RemoteNeighborConfidenceComposeTest` mounts the real status content with a
persisted neighbor baseline and an ingested current response. The disappeared
fallback, exact and unresolved entries carry distinct previous SNR values
`-2.375`, `3.125`, `4.875`, independent of current `5.5`, `6.5`, `7.5`.
Literal decimal expectations are `-2.4`, `3.1`, `4.9` (French:
`-2,4`, `3,1`, `4,9`); only the existing localized unit wrapper is reused.
Each assertion checks the merged accessible row contains all three texts, scopes
the unmerged SNR semantics node to the actual row containing its identity and
not-seen child, scrolls it into view and checks text overflow.
Removing the SNR consumer makes this lookup fail even if another row has SNR.

Existing API 31/37 runs cover default and 200% font, identity re-resolution,
fallback explanation/accessibility, exact/unknown identities, current row text,
new markers and all three not-seen markers. The new French case additionally
checks the mounted localized decimal separator. No expectations use the
production number formatter to derive decimal precision or values.

## Verification boundary

This remote Linux session initially has Python 3.12.3, not pinned 3.12.4, and no
configured same-host Android state. No direct Gradle bypass, runtime installation,
Windows recovery or execution-owner release is authorized by this repair.
Actual attempted readiness command:
`python tools/android-port/controller/ci.py preflight --local --output <fresh absolute session output>`.
It exited 2 with `Explicit --state/ANDROID_CI_STATE is required`;
no product tests ran. `git diff --check` passed.
Exact-candidate hosted job results/logs are authoritative and must be linked
in the handoff; no duplicate CI-result bundle is retained here.
Physical-device/radio acceptance is not established by Robolectric.
The #402 and #519 holds remain unchanged.
