# WP-313: disappeared-neighbor SNR consumer repair

## Candidate and scope

Repository: `cbattlegear/MeshCoreOne-Android`; issue: #408.
Fresh dedicated worktree base and fetched `origin/main`:
`5d4b62f659fbc02a0250b904db4ba4fc8df5bdc3`.
The local `main` ref was not used as authority: it differed from fetched remote
main. No rebase, rewrite or reuse of blocked #558 at
`ff6e1bd0d59cd5d69025bab8eddc09a449c7e643` occurred.
Candidate identity is the Git commit containing this evidence, not a second
CI-result bundle. Physical-device acceptance remains separate.

Swift remains pinned to `db14559b39d32322b06477c6ae676112f583db50`.
`git show db14559b39d32322b06477c6ae676112f583db50:MC1/Views/RemoteNodes/DisappearedNeighborRow.swift`
shows the prior neighbor's SNR formatted with exactly one fractional digit,
alongside name, localized not-seen and fallback-confidence disclosure.
The source file is unchanged between the pin and this base.

On this actual main, `NeighborRows.disappeared` already computes localized
`snrText` from the previous snapshot, but `RemoteNodeStatusContent` rendered
only name and not-seen. The repair consumes that existing text through
`RemoteValue` and retains not-seen as a separate visible text node.
It adds no formatter, resource, resolver, map, transport or snapshot behavior.
Fallback-confidence actions and exact/unknown identities are unchanged.
This addresses only this omission within `WP-313-behavior`,
`WP-313-boundaries` and `WP-313-source-test-parity`; it does not complete the
whole issue or alter #402/#519 held work.

## Mounted regression

The existing `RemoteNeighborConfidenceComposeTest` mounts the real
`RemoteNodeStatusContent` with a prior snapshot and current neighbor response.
Its default- and double-font cases run on Robolectric API 31 and 37.
The repair extends those cases, rather than testing only a row model:

- The disappeared fallback, exact and unknown rows require their unique prior
  SNR values `-2.5`, `3.0` and `4.0`, using the actual localized SNR resource.
  None equals the live response's `5.5`, `6.5` or `7.5`.
- Each SNR must appear exactly once, be scrollable into view, and sit between
  its corresponding identity and not-seen marker in mounted layout.
- SNR text must use the requested font scale without visual overflow.
- Existing three not-seen markers, current-neighbor text/new markers, accessible
  48dp fallback buttons, explanation dialog, exact/unknown confidence absence
  and catalog-recomposition assertions remain.

Removing only the production fix removes all three asserted standalone SNR
texts. This is the regression's discriminating condition; no executed
red/green run is claimed.
The declared module runner is `:feature:remotenodes:testDebugUnitTest`;
the relevant class is
`com.meshcoreone.android.feature.remotenodes.ui.RemoteNeighborConfidenceComposeTest`.
Use the reviewed affected launcher or exact-candidate hosted runner, not a
direct Gradle bypass.

## Admission and verification

Session: `98b561e2-9dbb-4523-984a-d2138b9de791`.
Automatic `reserve.py claim` was attempted with this assignment and exact base;
it refused a historical WP-313 source/base/manifest/backend binding mismatch.
The old audit record was not released, recreated, edited or impersonated.
Per trusted `controller/render.py`, this advisory historical mismatch alone is
not an edit lock. `reserve.py admit` admitted the two Kotlin files and the
WP-specific evidence/deviation paths through trusted `wp-owned` rules for
`modify` / `record-evidence`. No cross-owner support path is needed.

Native Windows uses pinned Python **3.12.4** (`python` resolves to
`C:\Python312\python.exe`). The guide's optional verification-venv interpreter
was absent; no dependencies, toolchain or execution runtime were installed.

Commands and actual results:

- `python .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $output`
  with a fresh absolute `MeshCoreOne-snr-preflight-<UUID>` directory under
  `$env:TEMP`: **passed**, exit 0.
- `python .\tools\android-port\local\check.py --commit HEAD --stages preflight`:
  **blocked before execution**, exit 1:
  `Installed execution runtime is old or mismatched: hook_test.py`.
- `python .\tools\android-port\portmap.py`: **passed**, exit 0.
- `python .\tools\android-port\controller\validate.py`: **passed**, exit 0,
  `result: valid`; pinned inventory 1,866 files, 64 WPs and 178 edges.
- `git diff --check`: **passed**, exit 0.

Android/JVM tests discovered/executed locally: **0**, not a passing suite.
The mounted assertions remain unverified until separately authorized runtime
deployment and a supported check, or the exact-candidate hosted job. No claim
of real-device, TalkBack, hardware or iOS execution is made.
