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

Run [41596055853](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41596055853)
on `074cd9aeee0e37417318bcb27317c60f4047ef39` failed. The App log's expanded
stack locates its 5-second timeout at the actual avatar-persistence predicate
(`ContactAvatarMountedStoreTest.kt:144`), not picker or crop restoration.
Feature API31 progress/viewport cases and the API37 viewport case exhausted
`awaitAvatarState` at line119; the retained log omits the predicate description
and semantics. The command subsequently timed out after 3,600 seconds.
The available App report printed 1,848 tests with one failure, not a complete
passing closure. No avatar assertion pass or complete feature count is inferred.
The next candidate prints the specific failed stage and mounted semantics and
services the App fixture's actual test scheduler while polling persistence;
this is discriminating evidence, not an attribution of the timeout's cause.

Review thread `4451891787` also identified a real Activity-singleton collision
between overlapping navigation entries. The retained owner now maps independent
saveable entry tokens to separate contact-bound lifetimes. Releasing an outgoing
same-identity entry cannot cancel the incoming one; binding a different speculative
entry cannot close the visible entry. Mounted API31/API37 keyed-overlap tests
exercise preview removal/cancellation with an existing picker and reopening an
updated same-identity contact while the outgoing entry is disposed, then require
the surviving entry's actual picker/crop/save and correct stored target. This
models the entry composition overlap, not physical predictive-Back gesture evidence.
All previous recreation, stale-identity and cancellation cases remain.

## Real-shell held-save regression

The normal PR affected run
[41604209373](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41604209373)
on exact `e8d9cc08d5b37f4f8be882bee3f2e99fe339a5ea`, base
`99479711bfdb7c621765f09d671068ca8b8318ce`, ended **failure** before this
follow-up was authored. Linux job `125810419342` failed
`:feature:nodes:mergeDebugUnitTestResources` with `Java heap space`.
Its available-report aggregate was 6,278 discovered/passed, zero failed/errors/skipped,
but explicitly `evidence_complete: false`: mandatory App and nodes reports were
among those missing. This is not avatar-test execution or a complete product pass.
Scope classification and controller/workflow policy passed; Swift jobs were skipped.
This resource-stage failure does not establish the cause of the earlier avatar
timeouts. No build/heap policy, execution lock or historical candidate was changed.

The already-admitted App support test now adds
`realNavigationPredictiveBackCancellationPreservesAnInFlightAvatarWrite` on API31/API37.
It mounts the unchanged production `NativeNavigationShell` and its actual
`NavDisplay`/saveable-entry decorator, with production nodes content, App contact
adapter/service and real in-memory Room. Only `NodesContactService.updateContactAvatar`
is intercepted: its call waits at a gate and then forwards once to the real service;
all other service/session behavior is delegated unchanged.

Two real contact-detail entries are navigated in sequence. While the selected
entry's encoded save is held, the actual Activity Back dispatcher receives
started/progressed/cancelled events. Both production entry tags must exist during
progress, so coordinator state alone or a keyed-composition proxy cannot satisfy
the assertion. Progress/cancellation must preserve the entire navigation state,
selected contact, selected entry, saving announcement, disabled chooser and
uncancelled pending save, with no Room mutation before gate release.
After release, the test requires exactly one forwarded and completed actual service
call, the matching Room avatar bytes and decoded 512x512 image, unchanged preview
and same-UUID foreign-radio snapshots, and a usable editor whose subsequent
cancellation cannot write again. The old singleton would cancel the selected save
when the different preview contact is composed.

The installed trusted CLI re-admitted the exact App path with operation
`add-or-update-related-test` as `reviewed-support-test`, and this evidence path with
`record-evidence` as `wp-owned`. No navigation-owned source or existing fixture
was modified. WP-302's owner confirmed no active edits to the App avatar test or
`NativeNavigationShell`; this is overlap coordination, not a reservation release.
`C:\Python312\python.exe -B tools\android-port\controller\validate.py`,
`C:\Python312\python.exe -B tools\android-port\portmap.py`, and `git diff --check`
passed before publication. The new regression still needs positive hosted execution and
independent exact-head review; it is injected host dispatcher/registry behavior,
not a real OS gesture, OS picker, process-death, physical TalkBack or device result.
The existing local Gradle execution hold remains in force.

Run [41606465089](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41606465089)
on `8cfd75b0f8ed2b8d99a46fc9a5178b5d00f3ff3f` ended **failure**. Linux job
`125817505931` failed App test compilation: the two newly added `ContactDTO`
fixtures omitted the required `lastHeardTimestamp` constructor argument. Both
now explicitly use `null`, consistent with the existing mounted fixture. The
real-shell regression did not execute on that head.

The same original log reports API31 `saveContinuesWhenTheProfileRowLeavesTheLazyViewport`
and `savingAnnouncesProgressAndPreventsASecondSelectionUntilTheStoreCompletes`
failing with `AvatarVisibleTextTimeout`. In these methods that type identifies
the exact post-gate-release synthetic error-text wait, whereas held-store entry,
saving progress and viewport stages have distinct types. Those earlier stages
therefore returned in these failed invocations; this does not establish a
complete passing test or diagnose the final error-visibility cause. The command
then timed out after 3,600 seconds; GitHub lists zero retained artifacts. No
complete suite result or API37 pass is inferred.

The follow-up retains the final exact visible-error requirement, but first
separately requires the active lifetime's completed save and exact typed error,
a showing dialog, and the exact dialog text semantics. Each stage has its own
failure type so short original job logs can distinguish state propagation,
dialog presentation, text and bounds visibility without manufacturing a cause.
No timeout, coverage, store, viewport or accessibility assertion is relaxed.

Run [41615580279](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41615580279),
attempt 1 on exact `2eea5cb559f41ab394e5ac2433094f7313fdb0f2` / base
`99479711bfdb7c621765f09d671068ca8b8318ce`, ended **failure**, distinct from
the prior `8cfd75b0f` run. App executed 1,853 tests with three failures.
The original expanded stack locates the existing mounted-store timeout at the
actual Room-persistence predicate, line 320; line 229 is the `runTest` wrapper.
The two API variants of the real-shell regression failed before gesture dispatch:
its unscoped contact-name readiness query matched the detail heading, detail
information text and list-row text (three nodes), not a unique element. The
follow-up queries the exact heading within the actual navigation entry instead;
both-entry overlap, held save, full state/selection and single-write assertions
are unchanged.

API31's two held-failure cases now report `AvatarFailureDialogTimeout`, after
the exact active-holder error state and completed-saving predicates returned.
This narrows the observed failure to dialog presentation/discovery; it does not
prove that a visible dialog exists or that the cause is fixture-only. The fixture
previously used `ShadowDialog.getLatestDialog()`, which in Robolectric 4.17 is
the most recently *shown* dialog and is not reset when that dialog is dismissed.
It now selects the last still-showing dialog from `getShownDialogs()`, retaining
the showing-dialog, exact error semantics and visible-bounds requirements, and
adds a distinct started-lifecycle prerequisite to diagnose UI collection.
This discovery correction does not itself claim to resolve the reported failure.

The mounted-store case now transparently records calls to the real avatar service,
forwards each unchanged and rethrows any actual exception. Separate bounded stages
require encoder-to-service entry, matching target key, actual Room bytes and real
service completion, including removal. Timeout assertion bodies include the
synthetic fixture's mounted semantics, lifecycle and recorded service stage so the
existing App failure-stack reporter can expose them in the original job log.
No fake successful write or replacement store is used. The existing required JPEG,
foreign-contact, recreation, cancel and remove checks remain. Complete required
reports were still missing from the failed run; no suite/device acceptance is inferred.

The independent source review of `f54bd768c` confirmed the entry-heading,
Back/state/Room and real-service-forwarding assertions, but identified two
fixture limits: still-showing dialog discovery lacked current-failure identity,
and sequential diagnostic stages accumulated fresh timeout budgets. The local
follow-up was prepared while immutable run `41625098790` remained in progress,
without a successor push or local Android execution.

The App stages now share one monotonic five-second deadline beginning immediately
after crop confirmation; only the remaining budget is available to each stage.
Removal has its own original five-second deadline. Feature error-state,
lifecycle, new-dialog, exact-text and visibility stages share one ten-second
deadline captured immediately before releasing the held failure. No stage can
reset these budgets, including lifecycle; a successful condition after the
deadline is rejected rather than accepted.

Before gate release, the feature snapshots all previously shown dialog identities.
A candidate must be newly shown, still showing, and have a context whose Activity
is the current host. Once selected, the exact dialog decor is pinned through text,
visible-bounds and OK-action assertions instead of reselecting a global root.
Until selection, only the current host root is pumped; unrelated native windows
cannot become the queried semantics root. An added API31/API37 adversarial flow
shows a same-message stale dialog on the current Activity before the snapshot and
a same-message foreign-Activity dialog after it. Neither may satisfy the new
failure identity. The real failed save must produce its own error dialog; moving
the foreign window to the latest position cannot change the pinned root, and OK
must dismiss the actual error while both decoys remain showing. The one failed
attempt and unchanged stored avatar are still required.

These are source-only fixture changes pending compilation, execution and successor
review. Native decoy windows carry real TextView messages, not manufactured
production Compose errors. Context unwrapping and dialog snapshots establish a
test-host Activity/new-presentation identity, not an OS-issued failure token or
device-level dialog proof. No production changes, lease/lock changes or timing
extension are intended.

The original run [41625098790](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41625098790),
attempt 1 on exact `f54bd768c281dcdfbbe3836292204fac56598689` / base
`99479711bfdb7c621765f09d671068ca8b8318ce`, subsequently ended **failure**.
Linux job `125880450451` reports App 1,853 tests, three failures.
`productionPickerCropAndRemovePersistOnlyTheSelectedRadioContact` failed at the
encoder-to-service-entry stage, with `calls=[], returned=0, serviceFailure=null,
lifecycle=RESUMED`; its printed host semantics included the contact and chooser,
not a completed persistence action. This establishes no service invocation in
that observed window, not the reason encoding/selection did not reach it.
Both `realNavigationPredictiveBackCancellationPreservesAnInFlightAvatarWrite`
variants timed out at line 165 before any predictive Back event. The initial
evidence/report attributed that line to crop presentation; that attribution was
incorrect. Immutable `f54bd768c` line 165 is the `entered.isCompleted` held-service
entry wait, after the crop Choose click at line 164. The crop presentation wait
is lines 160-163 and returned. Thus both variants reached crop confirmation but
did not observe service entry within five seconds; why remains unproven.
Their subsequent saving, Back and held-write assertions did not execute.

API31's `saveContinuesWhenTheProfileRowLeavesTheLazyViewport` and
`savingAnnouncesProgressAndPreventsASecondSelectionUntilTheStoreCompletes`
again reported `AvatarFailureDialogTimeout`, at line 170. The original log
ultimately records a 3,600-second command timeout and incomplete mandatory reports.
No API37 or whole-product pass is inferred. The prepared dialog-identity/deadline
follow-up does not purport to fix the unproven production/presentation cause.
It is retained as an immutable local source-only candidate for independent review;
the PR head remains `f54bd768c` until that review and a publication decision.
Fresh exact-head/current-base normal affected hosted execution is still required.

## Source-only pre-service diagnostic

The subsequent bounded source trace follows Choose through the captured
`ContactDetailLifetime.scope`, `holder.saveAvatar`, Default-dispatched JPEG
encoding, cancellation checks, live radio/service/store resolution, matching
Room contact lookup and finally `updateContactAvatar`. The actual App nodes
adapter delegates the contact lookup and avatar service to the live graph.
Normal detail disposal closes the lifetime/scope; configuration disposal does
not. Source inspection alone does not establish which step failed in run
`41625098790`, and no speculative production change was made.

The App test now records a best-effort retained-lifetime observation before Choose
and on a failed wait: active/cancelled scope and child-job flags, actual dispatcher,
saving/error state, synthetic selected identity and image presence. Test-only
reflection reads the feature's existing internal ViewModel/holder so no public
product observer or behavior is added; a missing/mismatched reflective contract
fails explicitly, rather than producing a reassuring fallback snapshot.
`ViewModelProvider` is get-or-create: an empty lifetime list does not prove a
previously captured owner was found. Fields are read sequentially, not atomically.
A separate no-store dispatcher probe in that exact retained scope records Main
entry, a Default hop, Main return and its actual completion/cancellation class.
The probe is a real child launched before Choose and appears among the observed
children. It can warm or perturb scheduling; it is not read-only or timing-neutral.
Its trace records are not correlated to individual retained lifetimes when
multiple entries exist. It is not proof the encoder job ran.

The unchanged real session/store are wrapped transparently to record current-radio,
service/store resolution and contact-lookup entry/return/identity outcome.
The lookup `identityMatches` field checks UUID and radio only, not public key;
the production holder's additional public-key check remains unchanged.
Lookup entry without return cannot distinguish suspension from exception or
cancellation. All reads still delegate to the production App adapter and real Room, and all
service writes still forward/rethrow as before. Thus a failed assertion can
provide hypotheses about lifetime cancellation, saving/error state, lookup
progress and dispatcher activity, without conclusively identifying an encoder
job or distinguishing every failure/suspension schedule. They do not replace
encoding, bypass identity checks or fake entry.
No new waits or deadline extensions are added. Both predictive variants retain
all Back/selection/Room assertions, and the mounted save retains its exact
five-second total deadline. These diagnostics are an unexecuted hypothesis
discriminator, not a confirmed product failure or passing fix.

Independent review of `ebff0dae9` found that a diagnostic collector exception
could replace the original timeout while constructing the failure message.
The local correction first creates an assertion naming the original stage and
retaining its timeout as the cause. Successful diagnostic text is a suppressed
assertion; a collector/reflection/assertion failure is itself suppressed on the
stage assertion. Diagnostic cancellation and VM/thread termination propagate
with the stage assertion suppressed, retaining both failures without swallowing
cancellation. Added controls exercise reflection failure, assertion failure and
cancellation preservation. These controls are source-only until actual hosted
execution; no static check is represented as their runtime result.
