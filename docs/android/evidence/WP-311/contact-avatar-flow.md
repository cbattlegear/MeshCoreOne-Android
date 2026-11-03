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
The new app-level `ContactAvatarMountedStoreTest.kt` support path was independently admitted
by the actual trusted CLI as `reviewed-support-test` with operation
`add-or-update-related-test`. It is directly necessary to prove the production
App contact adapter and real Room update, rather than treating a feature fake as storage evidence.
It changes no production app assembly or other owner's source.
#495's owner confirmed no live avatar-path overlap; its retained technical hold is unchanged.
The actual changed-path lists for #495 and #508 also contain none of this repair's targets.

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
  Picker registration, work and dialogs are screen-owned, outside recycled list rows.
- Missing service/store, encode failure and persistence failure are not successful saves.
  Saving includes encoding, prevents duplicate operations and resets in `finally`,
  including cancellation. The existing localized error dialog presents failures.
- Only chat contacts offer avatar editing, as in the pinned Swift source.

## Verification

Candidate checks are pending. New API31/API37 native-graphics Robolectric cases mount the
production `ContactDetailScreen`, replace only the external activity-result transport,
read real local PNG streams, invoke the production crop and JPEG encoder and inspect the
feature persistence boundary. An additional API31 app-level mounted case uses the
production `NodesEntry`, App contact adapter/service and in-memory Room database, checking
cancel/save/remove and a same-UUID foreign-radio sentinel. These are host tests, not
actual device, physical TalkBack, iOS codec, on-disk durability or hardware evidence.

`python tools\android-port\controller\validate.py` and
`python tools\android-port\portmap.py`: passed before candidate commit.
`git diff --check`: passed.
An initial affected `check.py --worktree` request was refused before execution because
affected checks require an immutable committed candidate. It is not a failed product test.

The supported exact-candidate local command for initial candidate
`cac0a05bcf07a1abe02f970ccdb805c5615bbb72` was:

```
python tools\android-port\local\check.py --commit cac0a05bcf07a1abe02f970ccdb805c5615bbb72 --affected-base 1e1ff70345f203b41f9a8b04836c2cc4640500c9
```

It was refused before Android execution:
`Installed execution runtime is old or mismatched: controller/merged_acceptance.py`.
The separate runtime owner subsequently reported a trusted, interlocked installation.
No runtime, execution locks or shared storage were changed by this repair session.
Hosted run [41543533335](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41543533335)
was superseded and cancelled. A later exact `workflow_dispatch` on
`f87b3037be8ce6e39d06e9e36230aed8822cef22`, run
[41544214214](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41544214214),
passed scope classification, controller/workflow policy tests and the pinned Swift codec
oracle. Its Android job was cancelled during Gradle project/dependency snapshot startup;
the log contains no Kotlin compilation, test execution or discovery. These successes do
not verify the avatar flow.

A fresh supported local check for that exact head was refused before launch because
the canonical `gradle-execution` lock is still held by
`local-check:f516a7e271f444dd9cb2efcc8b995a16`, bound to the unrelated exact commit
`3c5297acdc083df21ec08afd7654aeaaa103ee59`. Its interrupted owner reports no genuine
completion cleanup record; a reused PID is not proof of process completion. Current
host capacity is reported at approximately 1.6 GiB free, below the documented 8 GiB
single-execution reservation. No execution lock was cleared or recovered by this WP.
Do not retry local Android checks until the execution owner completes supported
invocation-bound recovery and atomic admission succeeds.

The original exact-head hosted run
[41576781773](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41576781773),
head `284845585dae4c1f94ab07da77741204400a212b`, **failed**. Scope classification,
controller/workflow policy and the Swift codec oracle succeeded. Android job
`125722087453` reported an unresolved top-level `assertExists` import in
`ContactAvatarMountedStoreTest.kt:17`, preventing app test compilation. It also
reported `IllegalStateException` at the shared mounted fixture's `awaitText`
timeout (`NodesScreensComposeTest.kt:158`) for the viewport-held-save case on
API31/API37 and the progress-held-save case on API31. The command timed out after
3,600 seconds. There were no retained artifacts or XML reports, so complete
discovery counts, the awaited text and failure semantics trees are unavailable.
These are actual failures, not passing product evidence; missing app reports
cannot establish any App/Room test execution.

The repair removes the invalid extension import (the assertion is a member of
`SemanticsNodeInteraction`). The held-save fixtures now independently await entry
to the actual fake store before inspecting pending UI, using a bounded monotonic
deadline with the existing native semantics fixture rather than the shared
100-iteration loop. They explicitly assert
the polite live-region semantics, visible disabled chooser, exactly one store
attempt, unchanged persistence while held and recovery after failure. The
viewport case scrolls to the danger-zone action and asserts that the avatar
chooser really left the viewport before releasing the held store; merely finding
the earlier Edit Path button did not establish eviction. Gates are cancelled in
`finally` on assertion failure. This does not yet distinguish scheduler latency
from an actual production visibility issue; exact-candidate execution must do so.

The nine actual paths/operations were re-admitted using installed trusted
`reserve.py`. The prior advisory claim refusal remains unchanged; no fresh
persisted lease or historical-owner identity is claimed.
`C:\Python312\python.exe -B tools\android-port\controller\validate.py`,
`C:\Python312\python.exe -B tools\android-port\portmap.py`, and `git diff --check`
passed for the repair before publication. Android re-verification is pending.
Use the existing PR-event affected-candidate selection for the changed
App/nodes closure rather than another full-suite `workflow_dispatch`.

The first PR-event run
[41590130225](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41590130225),
head `c8fedba8324584ffadeb53884f8f9bd344563cd5`, failed at nodes test compilation:
that module does not declare the Compose test-rule library used by the initial
fixture repair. No avatar product assertions ran on this head. The follow-up
uses only the existing manual native-semantics fixture and dependencies, adding
stage-specific timeout diagnostics without changing the product, build graph or
required assertions. The App `assertExists` import error did not recur in the
reported compilation output; App product execution still is not established.

## Configuration recreation repair

The subsequent source review identified a real lifetime gap: plain `remember`
lost `picking`, decoded image and crop geometry on Activity recreation, and
`key(holder)` made the Activity Result saveable registration depend on a new
holder instance. The repair retains a single active contact-detail lifetime in
the existing Activity ViewModelStore, bound to radio/UUID/public key. Its child
scope, holder, pending request and crop survive configuration destruction; normal
detail disposal, identity replacement and final ViewModel clearing cancel and
clear them. Decoder work uses the application resolver, not the destroyed Activity.
Activity Result registration is keyed by stable contact identity. Neither a URI
nor media bytes are saved to a Bundle, written to disk or given persistent grants.
This does not claim process-death restoration of private contact/media selection.

New API31/API37 mounted tests recreate the actual ComponentActivity and replace
the external result registry with a fresh registry restored from its genuine
saved-state mapping. They exercise both pending picker contracts, cancellation,
stale identity, crop zoom/pan/image retention and a held save across recreation.
The App/Room mounted case also recreates before picker return and during crop
before inspecting the actual selected-contact persistence and foreign-radio sentinel.
No MainActivity or navigation-owned source is changed. These assertions await
exact-candidate hosted execution and do not supply device/TalkBack evidence.

The superseded `de6c13ee` run
[41591386784](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41591386784)
ended cancelled after the recreation repair was published. Its original job log
nevertheless contains two concrete failures: the App foreign sentinel compared
an unsaved DTO (`lastHeardTimestamp=null`) with Room's normalized persisted value
(`0`), and the API31 viewport fixture exhausted its repeated `ScrollBy` search
at `NodesScreensComposeTest.kt:142`. Neither is a product pass. The follow-up
compares the complete foreign contact to a pre-action Room snapshot, retaining
full no-mutation equality and explicit foreign-key/avatar assertions. The
viewport fixture invokes the production lazy list's native `ScrollToIndex`
action for the final card of the no-location fixture, then independently requires
the danger-zone action to be visible and the avatar chooser to be outside the
viewport before releasing the held store. It no longer relies on fixed 600-pixel
scroll increments. The earlier `2848455` timeout cause remains unproven.
