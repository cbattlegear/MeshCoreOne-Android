# Independent durable onboarding-tip prerequisite

Repository: `cbattlegear/MeshCoreOne-Android`; WP-305.
Integration base: `2e7823cec06294dd4039000de32c98c9786f5dc5`.
Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Initial assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_DoCtxb5Q3YEVJ09aV2ZCYoUq`.
Cross-writer correction: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_qti7XrNF7kfWHmfY533DiLs9`.

The initial independently isolated prerequisite copied exactly three reviewed blobs from
`ba161421a8b2b67525de240aad73111eaf3d4b3e`: ShowOnceTips.kt,
ShowOnceTipsTest.kt and SharedUiComposeTest.kt. The three base blobs on current
main equal their original base `41552c85b659396111cee6b0e0f70c404ac5a8ee`;
there is no intervening shared-tip hunk to overwrite. The broader foundation's
identical tip source remains preserved; this composition imports none of its
writer, runtime, device, navigation or presenter changes. Independent review then
required the narrowly bounded existing-writer correction below; the repaired
tip source/test files are no longer byte-identical to the historical candidate.

The preference helper atomically records completion and its pending device-menu
donation, stages/finalizes exact attempt identities, rejects stale exact readback
after an unattributed completion, and restricts device-menu claims to visible,
connected, quiescent Chats/Nodes/Map hosts. Existing displayed-tip behavior is
unchanged. No call site or presenter is mounted by this prerequisite.

All 20 original tip methods and 22 SharedUi methods are retained byte-for-byte, including
real PreferenceStore concurrency, failure rollback, cancellation, close/reopen,
staged/exact/unattributed identity and actual backup allowlist controls. Original
base methods and assertions are preserved, including newer crop controls.
Backup implementation, codecs and reset authority are unchanged.

Source checks: original method/assertion preservation and
exact-base `git diff --check`. Trusted admissions use reviewed-support-consumer
for the production path, reviewed-support-test for both existing tests and
wp-owned admission for this evidence and the WP deviation record.
Pinned Python 3.12.4 non-Gradle local readiness passed with existing Windows
toolchain state. The documented verification venv was absent; the available
pinned interpreter ran the same preflight. No installation or local Gradle ran;
the retained f516 execution ownership was neither cleared nor bypassed.

Historical exact run [41959590903](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41959590903)
passed ba161421 on its old base. That result is provenance only, not current-head
acceptance. This candidate requires its own exact-head hosted verification and
independent source review; authoritative job logs, not a second result bundle,
provide execution evidence.

## Cross-writer regression discriminator

Independent review found the existing main WriteBehindOnboardingFlags.load
writer persists only the Boolean. A completed attempt A followed by the real
OnboardingState reset and unattributed completion can therefore resurrect A's
finalized identity. The added OnboardingTipWriterTest uses the real loader,
OnboardingState, DataStore and settled persisted false/true observations, then
closes/reopens storage. Its assertions require invalidation and preserve pending
donation/display behavior and fresh attempt B. A second control requires a
settled real reset to clear a staged identity without consuming the tip.
This test-only discriminator intentionally precedes the production correction;
the immutable 4ffe reference and its already-dispatched run remain preserved.
No integration is permitted on the older review or green alone.

The correction routes only the existing main loader's unattributed Boolean
writes through one ShowOnceTips transaction that sets the gate and removes both
identity markers. It deliberately leaves donated/displayed tip state unchanged.
There is no new writer, navigation gate, async API or foundation import.
The discriminator's assertions are unchanged between failing and repaired
production candidates. Unknown names still fail; existing queue ordering,
cancellation and failure reporting remain unchanged. The retained foundation
producer confirmed no newer shared-tip edits and serialized those paths here.
Trusted reviewed-support-consumer and reviewed-support-test admissions also
cover the actual main loader and its new app regression file. Two additional
core/UI tests cover same-value unattributed writes, absence of an implicit
donation, fresh exact completion, real filesystem write failure, preserved
gate/markers/event bytes, reporter notification, reopen and retry. This makes
22 tip methods, 22 SharedUi methods and two actual-writer regression methods.
Full application-preference reset and backup/restore acceptance are not implied;
neither implementation is changed by this correction.

This supports only the durable-tip portion of WP-305 behavior/boundaries and
source-test parity. Full onboarding, original Activity/navigation witness #495,
the held #532 composition, native tip presentation/timing, physical devices,
hardware and human acceptance are not established here.
