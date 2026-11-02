# WP-311 contact-avatar consumer repair (#520)

Repository: `cbattlegear/MeshCoreOne-Android`. Authoring base:
`1e1ff70345f203b41f9a8b04836c2cc4640500c9`. Reference:
`db14559b39d32322b06477c6ae676112f583db50`.

## Authority and scope

The assigned isolated session implements the missing contact-detail photo/document selection,
decode, crop, cancellation, JPEG save and removal journey. It reuses the accepted WP-304
crop implementation from #522 without modifying it. This addresses a bounded part of
WP-311-behavior and WP-311-boundaries; it does not assert whole-WP parity or #398 acceptance.

The supported `reserve.py claim` attempt was **refused** with
`Existing WP lease is bound to different source/base/manifest/backend`. No claim,
successor, transfer or receipt evolution is represented as successful. The incumbent
WP-311 identity, branch, dirty files and ledger remain untouched. Trusted
`AUTOMATION.md` permits admitted isolated authoring despite this advisory refusal.
Actual CLI `admit` returned `wp-owned` for feature modify/create/test and this
record-evidence path; pinned-source, declared-verification and provenance invariants apply.
#495's owner confirmed no live avatar-path overlap; its retained technical hold is unchanged.

## Behavior and native adaptation

- System image-only photo picker (with native fallback) and document picker need no broad
  media/storage permission. Only granted `content:` streams are read. No URI, persistent
  permission or original image is retained, and no network loader is used.
- Input is bounded at 20 MiB while streaming, independent of provider length metadata.
  Oversize, corrupt or inaccessible input reports an error without mutation. The existing
  EXIF-aware decoder bounds pixel dimensions before crop.
- Cropping retains the shared component's host-window width mapping and accessible controls.
  Confirmation creates a real 512x512 JPEG at quality 80 off the main thread.
  Cancellation never calls persistence.
- The holder includes radio ID and public key as well as UUID in its UI lifetime key.
  Late results cannot target a replacement holder; saving rechecks the selected radio
  after encoding and captures the target key/service/store before mutation.
- Missing service/store, encode failure and persistence failure are not successful saves.
  Saving includes encoding, prevents duplicate operations and resets in `finally`,
  including cancellation. The existing localized error dialog presents failures.
- Only chat contacts offer avatar editing, as in the pinned Swift source.

## Verification

Candidate checks are pending. New API31/API37 native-graphics Robolectric cases mount the
production `ContactDetailScreen`, replace only the external activity-result transport,
read real local PNG streams, invoke the production crop and JPEG encoder and inspect the
feature persistence boundary. These are host tests, not actual device, physical TalkBack,
iOS codec, durable Room or hardware evidence.

`python tools\android-port\controller\validate.py` and
`python tools\android-port\portmap.py`: passed before candidate commit.
`git diff --check`: passed.
An initial affected `check.py --worktree` request was refused before execution because
affected checks require an immutable committed candidate. It is not a failed product test.
