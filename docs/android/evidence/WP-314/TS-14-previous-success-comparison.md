# WP-314 TS-14: previous-success trace comparison

- Repository: `cbattlegear/MeshCoreOne-Android`; relates to issue #415.
- Integration base: `48753d7c958cc9a9513e32c6424a2d572119b05d`.
- Read-only reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Scope: missing mounted previous-success comparison only; not full WP acceptance.

## Source and implementation

Pinned `TraceResultsSectionView.swift` gives batch RTT precedence; otherwise a
successful saved-path result uses `ComparisonRowView.swift` when `previousRun`
exists. That row shows current RTT, absolute zero-decimal percentage and localized
increase/decrease for nonzero deltas, and previous RTT with abbreviated date.
Zero previous RTT produces zero percent; equal RTT omits the change indicator.

The real Android `TraceResultCard` now consumes the existing `previousRun` and
`RttComparison.of` policies at that same duration branch. History still comes
from the real holder's saved-path match, accepted run append and storage refresh.
No new state, history-selection, storage, radio, graph-lifetime or batch-progress
policy is introduced. Existing Save and held TS-08 work are unchanged.

A wrapping Material row supports compact/large-font presentation. Date and
percentage use the current Android locale; the existing comparison resource is
formatted with the original Long RTT, without narrowing production data.
Increase/decrease has localized accessibility text and a visible directional
marker, not color alone. Sparkline/history navigation is not part of this bounded
assignment and is not claimed implemented.

All four edited paths are admitted by trusted integration-base `wp-owned`
capability rules (modify UI/deviations, test existing mounted class, create
per-WP evidence). Canonical owner is `tools-ui-engineer`; session
`d1e00c92-03fb-4cce-a228-7d9895e6ab82`; capability revision
`7680d62ed91fa7b9455647745901c07ac962966fba9ae26ab69e09a498911396`.
Pinned-source, one-WP/branch/PR, declared-verification and provenance invariants
remain enforced. The workspace integrated the squash-landed predecessor through
a normal merge with a tree identical to current main, preserving prior refs.

## Mounted controls

Eight new methods in existing `TraceCodeInputComposeTest` mount the actual trace
list, dispatch through `FakeTraceSender`, correlate actual responses on the real
holder and append/fetch through `InMemoryTracePathStore` (not Room). They cover:

- Latest previous success despite unsorted older history and intervening failure.
- Increased and decreased RTT, localized direction and percentage semantics.
- Equal RTT with no delta and zero baseline with finite zero percent.
- Empty/failed-only history: no comparison until a second real success is stored.
- Unsaved success and timed-out single trace without stale comparison.
- Batch average precedence even though successful saved history exists.
- French compact 320dp / 200% font, localized date/percentage/direction and
  reachable comparison bounds.

No existing tests/assertions are removed or changed. Source declarations are
not runtime evidence until ordinary exact-head CI discovery is audited.

## Verification

Native Windows readiness passed with pinned Python 3.12.4, the existing same-host
toolchain state and a fresh absolute TEMP output via
`C:\Python312\python.exe .\tools\android-port\controller\ci.py preflight --local
--state $env:ANDROID_CI_STATE --output <fresh absolute TEMP directory>`.
No local Gradle, shared execution-owner recovery, new worktree or extra diagnostic
workflow was started.

Declared source checks passed using `C:\Python312\python.exe`:
`.\tools\android-port\controller\verification_config.py --check`,
`.\tools\android-port\controller\validate.py`,
`.\tools\android-port\portmap.py`,
`.\tools\android-port\test_inventory.py --check` and
`.\tools\android-port\extract_vectors.py --check`. `git diff --check` passed.
Normal exact-candidate PR CI and actual method discovery are pending publication.
The original hosted result/log is authoritative; no second CI-result bundle is
retained. Physical device, firmware, real radio, emulator and TalkBack execution
remain unverified. This does not accept TS-08, unrelated TS families or all WP-314.
