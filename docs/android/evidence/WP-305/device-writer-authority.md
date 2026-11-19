# WP-305 device-writer authority support repair

Repository: `cbattlegear/MeshCoreOne-Android`.
Received base: `fc7be50b92c275d495932df6e978e95c553ea684`.
Parent: `94f2fca58f434a5d1bcdef94e0635c2e07cbf201`.
Read-only Swift source: `db14559b39d32322b06477c6ae676112f583db50`.
Producer branch: `cbattlegear-device-write-authority`.

This is an isolated repair for the existing onboarding producer, not a duplicate PR or a WP completion
claim. Onboarding flow/completion receipts and the separately assigned Settings hash acknowledgment
implementation remain their producers' work.

## Authority and source controls

- The received worktree was clean and exactly matched the assigned base.
- The onboarding producer confirmed no newer/overlapping core edits.
- Trusted `reserve.py admit` accepted each edited production support path as
  `reviewed-support-consumer` / `modify-related-consumer`, each test path as
  `reviewed-support-test` / `add-or-update-related-test`, and these per-WP documents as `wp-owned`.
- The attempted ordinary `claim` refused with
  `Existing WP lease is bound to different source/base/manifest/backend`.
  The historical claim was preserved unchanged. Trusted `AUTOMATION.md` explicitly distinguishes
  this historical advisory mismatch from a live source edit lock.
- The source-defined `transfer` accepted truthful onboarding-origin and repair-producer identities,
  returning `advisory: true` and `file_edit_blocked: false`. This is attribution only, not a lease
  transfer, successor, execution authorization or acceptance.
- The Settings owner transferred only the disjoint OCV callback/port/fake hunk; their hash
  acknowledgment helpers, service/event handling and UI implementation were not edited here.
- The coordinator admitted the adjacent RegionManagement consumer repair after the Settings owner
  confirmed no overlapping changes.

## Behavioral controls

`DeviceWriterAuthorityRoomTest` drives the actual AppContainer, ConnectedDeviceEditor,
DeviceService, runtime token guard and Room repository. Its interception wrapper only pauses calls
and invokes real competing writers; it delegates CAS decisions and storage failures to Room.
Cases cover both OCV interleavings, late live publication, delayed OCV versus completed preset,
ordinary editor serialization, actual successor activation, same-token watermark rebasing and the
actual Settings region consumer's default/unrelated/concurrent/successor/failure paths.
Atomic inactive initialization/repeated simulator seeding is also exercised against an existing
active successor, a committed preset/hash/OCV edit and an attempted CAS activation bypass.
A held real Room transaction also establishes that connection activation's ownership guard is
evaluated after the wait, so an already-retired queued activation cannot replace the active successor.

The existing session-bound and original pairing cases are retained. The test-only offline fixture now
seeds the authoritative device row before requesting a persisted edit; it does not fabricate a
successful production save for an absent row.

## Verification state at initial publication

- `python tools\android-port\controller\ci.py preflight --local --state <same-host-state> --output <fresh-absolute-output>`:
  passed with actual pinned `C:\Python312\python.exe` (3.12.4) and the existing Windows toolchain state.
- `git diff --check`: passed.
- `python tools\android-port\controller\validate.py`: passed; 1,866 frozen reference files,
  64 work packages and the unchanged source pin.
- `python tools\android-port\portmap.py`: exited 0.
- Kotlin/Android execution is **unverified** at this initial source publication. No local Gradle
  runner was started or recovered; the retained execution owner was not touched. Hosted
  exact-candidate job status/log, not a second retained CI-result bundle, is authoritative.

No physical radio, mounted Compose/device, hardware, signing, release or complete WP acceptance is
claimed. The region controls invoke the same real consumer functions used by the composable but
do not claim a mounted Settings UI run.
