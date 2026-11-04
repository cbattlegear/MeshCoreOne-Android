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

Bounded re-review then identified the actual `Method.invoke` boundary: a target's
cancellation or VM/thread termination is wrapped in `InvocationTargetException`.
The helper now inspects only that specific outer wrapper, rethrowing its actual
target by identity when it is cancellation, `VirtualMachineError` or `ThreadDeath`,
with the original stage/timeout assertion suppressed. Ordinary reflection
failures and ordinary target exceptions retain their actual wrapper as a
suppressed diagnostic failure. Arbitrary cause chains are not unwrapped.
Additional source-only controls call a real reflected throwing target for all
three propagated categories and an ordinary target exception; existing direct
collector/cancellation controls remain. No wait budget or product assertion changed.

## Exact d4 hosted failure and bounded App host follow-up

Normal PR run `41668875550`, head
`d4df945fd98fd2889098eb9bfa4f549890ae061a`, base
`99479711bfdb7c621765f09d671068ca8b8318ce`, is terminal FAILURE.
Original Linux job `126031685885` and `android-ci` failed; controller policy
passed and Swift jobs were skipped. The App unit-test output reports 1,855
tests completed with three failures: `productionPickerCropAndRemovePersistOnlyTheSelectedRadioContact`
and both SDK variants of
`realNavigationPredictiveBackCancellationPreservesAnInFlightAvatarWrite`.
The production test fails at its first service-entry wait (d4 lines 442/448);
both predictive variants fail at their service-entry wait (236/238), before
Back dispatch. All three original suppressed observations contain exactly
`LAUNCHED`, `STARTED`, `ENCODE_STARTED` for their respective operation UUID.
There is no observed encode return/failure/cancellation, dependency lookup,
service call or job completion. Each retained snapshot reports an active,
uncancelled Main.immediate child, saving true, no error, and cleared crop image;
the production test also reports service calls zero, returns zero and no
service failure while the Activity is RESUMED.

All three failures additionally suppress Robolectric's
`UnExecutedRunnablesException`, identifying queued unexecuted Main-looper work.
The production encoder switches to Default and must resume its caller on
Main; `compose.waitUntil` and `harness.scheduler.runCurrent()` are not a
substitute for draining the paused Android Main queue. This supports a
host-continuation hypothesis, but the original observations do not prove
that crop/JPEG processing completed or identify those queued runnables as
this operation's return. No product root cause or passing fix is asserted.

The original run also logged the three API31 feature dialog timeouts.
Their read-only diagnosis is separately coordinated with WP307;
this App follow-up does not edit or investigate those feature test hunks.
The original Gradle command timed out after 3,600 seconds. Its available
failed-command report aggregate is 13,729 discovered, 13,726 passed, three
failed, zero errors/skips, with `evidence_complete: false` and missing
mandatory report directories. This aggregate is not a complete-run count
or acceptance result; the log's separate App failures are retained above.
The run artifacts endpoint returned no downloadable report artifacts.

The source-only App follow-up explicitly drains ready Main-looper work
inside the bounded polling helper, then runs the existing harness scheduler
and original condition. It does not replace encoding or store/service calls,
inject a diagnostic job into the save, or advance the host clock with
`idleFor`. The production save's original shared five-second deadline now
includes holder job completion and chooser recovery before JPEG assertions,
addressing review comment `4456405037` without a fresh completion budget.
Predictive Back keeps its original five-second entry bound and five-second
post-release bound; persistence/job completion and chooser recovery share
that post-release deadline rather than renewing it. All real Back, selected
identity, service forwarding, Room, JPEG and exact 22-stage assertions remain.
Authored controls distinguish polling from posted Main work and a real
Default-to-Main return, and reject already-expired or late successful
conditions with deterministic clock samples. These controls have not run.

Review comment `4451597214` describes an older holder-key/recreation defect.
The actual d4 source uses a saveable entry ID plus radio/UUID/public-key
launcher key and configuration-retained editor/holder state. Its mounted
test recreates the Activity before picker result delivery and again with
crop open; the original d4 failure occurs later, after those crop/cancel
assertions and Choose. This is narrow host evidence that those paths reached
the save, not successful persistence, process-death restoration, or native
picker/device acceptance. No production recreation change is made here.

Admission for the App file is the trusted base's `reviewed-support-test` /
`add-or-update-related-test`; this evidence uses `wp-owned` / `record-evidence`.
No local Kotlin/Gradle execution, new CI, publication, receipt, review-thread
resolution, ledger/lock/hold change or device verification is performed.
The original f54, 1c, 79 and d4 failures remain failure history.
Independent exact-successor review is required before any new publication.
Allowed source checks passed: `python android/parity-tests/semantic.py --check`
(58 groups, 5,133 families, 1,498 candidate comparisons),
`python tools/android-port/portmap.py`,
`python tools/android-port/controller/validate.py`, and `git diff --check`.
These used pinned Python 3.12.4, not Kotlin compilation or test execution.

## Atomic avatar integration and current candidate

The consumer now captures `(EntityKey, publicKey)` from the selected contact in
the configuration-retained editor lifetime before launching the picker, then
passes both unchanged to the WP-209 three-argument service method. Producer
commits `ea0dfe8b877f138635ad7df46e8f15c1163b7688`,
`ac6496f5b27ba15a2b2172a41bbff1e9358680bc` and
`ceeaa39c26f26e8082b8a37f8bc259b128a791bb` are included for the atomic
existing-row-only avatar update. This checks current radio/entity key and
public key; it does not distinguish delete/recreate with the same identity.
No incarnation field or same-identity ABA guarantee is claimed.

Consumer head `a63886d766a44d08fbc962226fb75a5de86ae0d4` / base
`cb4a79ab906db851800bcf93f7c2ebfb5aa34571` triggered hosted run
[41860811120](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41860811120).
Classification, controller/workflow policy and PR checklist passed; its
Android job failed. The available review/build diagnosis found two consumer
compile errors: the Compose editor had not delegated the retained
`avatarIdentity`, and an App test used a nodes-internal `ContactDTO.key`
extension. No product test counts are available for this failed candidate.
Both compile errors were corrected in `2efd6a64df211beb3c57220201afc83c3d68f737`.

The exact `2efd6a64df211beb3c57220201afc83c3d68f737` hosted run
[41862844614](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41862844614)
is queued; at this record it has not discovered or executed product tests.
The producer PR #552 run `41860260327` is still using its Linux Android build
job. The supported local launcher previously refused before Gradle due to an
old/mismatched installed `controller/authority.py`; no direct Gradle invocation,
lock recovery or shared-runtime cleanup was performed.

The current review also identified a possible lost update: favorite persistence
currently saves a DTO fetched before radio acknowledgement, so an overlapping
avatar set/clear could be overwritten by that stale full-row save. This is
reported to the WP-209 producer for a store-level field-specific fix and held-
acknowledgement Room/service regression. Until that is fixed and covered, this
candidate does not claim favorite/avatar write consistency or merge readiness.
Physical device, TalkBack and iOS acceptance remain separate and unclaimed.

## Favorite/avatar write-race follow-up

The bounded WP-209 producer follow-up changes favorite persistence after radio
acknowledgement from saving a stale full `ContactDTO` to an identity-checked
field-specific update of `isFavorite` and flags bit 0. The SQL update preserves
avatar bytes, other metadata and all other flag bits, and does not create rows.
Its Room/service tests hold the acknowledgement while a second repository
changes metadata and sets or clears the avatar, for both favorite directions;
they also cover cancellation, deletion/replacement and radio isolation.
Producer head `76920597c259903b2cffcbfbe2f733d0a52b4e7a` corrects a class-scope
placement error in the first version of the repository method. The method is
now visibly a class-level override following `batchSaveContacts`.

Consumer hosted run
[41864959311](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41864959311),
exact head `2df2f95e79c07e2a825167edbde87f8d32bdc1ec`, completed **failure** at
`:feature:nodes:compileDebugUnitTestKotlin`. Kotlin reported type inference
errors at three expected-avatar-list assertions because a `Bytes` actual value
was compared with the fake's nullable `Bytes?` list element type. The expected
list now explicitly declares `Pair<EntityKey, Bytes?>`, preserving the same
identity and byte assertions. This is a compile correction, not test execution.

The current consumer candidate is
`aadee631b6ecbdf6eab5620307ae82d26256aafa`. Hosted run
[41868433539](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41868433539)
has passed classification and controller policy. Its Linux Gradle validation
step failed at 17:36Z: the Gradle validation command timed out after
3,600 seconds; its last streamed tasks were build-logic setup tasks. The timeout diagnostics
reported that mandatory JUnit reports were missing for the failed command.
Cached report summaries are not counted as current-candidate test evidence.
This is a command timeout, not an observed product assertion failure. The
presence of the task-graph verification init script in the command does not
establish that script as the cause.
Producer candidate `76920597c259903b2cffcbfbe2f733d0a52b4e7a` was superseded
when its owner merged current main; exact run
[41867785182](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41867785182)
was cancelled. The reconciled producer head is
`528f4cbc20bb96e722950975d012f8cf4e53a7e1`; its new exact run
[41874809733](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41874809733)
passed all jobs, including the Linux Android build, APK inspection,
backup-byte transfer, and SwiftData restore/export oracle. That head was
superseded by `ae80cce2eb06a6536e05c013c699b5af81d5dfd7`, which integrates the
latest main base. New exact run
[41881246966](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41881246966)
has passed controller and Linux Android build jobs, including the candidate
Room/service tests. Its SwiftData restore/export oracle is queued, so the exact
workflow is not complete. Source inspection at the current producer head
confirms the captured-identity avatar API and field-specific favorite
persistence remain present; current-head hosted verification is still pending.
Both PRs remain open and the consumer PR remains draft. Independent review,
exact producer and consumer results, and correct dependency merge order remain
required; no favorite/avatar write-consistency acceptance or merge readiness
is claimed.

## Follow-up provider lifetime and consumer review

Independent source review 5774231180 identified external-provider runtime
exceptions, cancellation during a stalled read, crop-selection drift on resize,
and an unusable preview in short windows. The provider path now maps runtime
failures only at external open/read/close calls to the existing media error
surface; cancellation and security failures retain their separate paths.
Decoder argument failures also surface as invalid media.

The selection owns a cancellation cleanup child before opening the stream.
Reads/opening are interruptible and cancellation closes an already-owned stream
on the IO dispatcher, rather than waiting for the blocking read's `use` to finish.
A provider that ignores both interruption and close cannot be guaranteed to
release its worker; an uninterruptible open likewise cannot be forcibly stopped.
A stream returned after cancellation is still closed before any decoding.
No URI grants or original media enter persistent state.

Added narrow open/read failure and exactly-once-close controls, a held stream
that deliberately ignores interruption but is released by close, and mounted
failure/chooser-recovery and screen-exit/no-mutation controls. These additions
are authored assertions, not passed test evidence until the exact hosted run
executes them. Shared crop fixes are assigned to the WP-304 owner; this consumer
does not edit that owner's files or duplicate geometry logic.

The admitted WP-304 handoff `6ef02462bff41987d5fd065c7809b553ecdc95fa` is
composed as `a33d8de83` without foreign producer edits. Shared geometry retains
the selected source region by scaling offsets with the viewport. The short/
large-font layout scrolls title, actions, preview, zoom and pan with a minimum
192 dp canvas / 128 dp guide. The outer 32 dp padding may clip in a short
viewport, but not the selected guide. Shared geometry and mounted tests come
from the producer; production contact-detail controls additionally exercise
360-to-840-to-360 width changes after zoom/pan, and 220 dp, 200%-font windows
at both widths with an actually visible guide and fully reachable 48 dp
controls. Cancel leaves storage untouched; Choose requires one persisted avatar.
Late-open cleanup, unchanged security failure identity and close failure controls
also cover the provider lifetime. These additions still require actual exact-
candidate execution; none establish physical-device or TalkBack behavior.

Independent follow-up of provider candidate `8f4c8b60b` correctly identified that
`openInputStream` supplied no Android cancellation signal. The final adapter uses
`openAssetFileDescriptor(uri, "r", signal)` with a per-selection signal canceled
by its lifetime cleanup. Atomic stream ownership still closes a late descriptor
without reading/decoding; failed stream creation closes its descriptor. Signal
cancellation is not invoked on normal completion, and concurrent platform
IO/security failures do not replace genuine coroutine cancellation.

All mounted avatar media fixtures register actual local ContentProviders and
descriptor-backed synthetic images rather than shadowing `openInputStream`.
Mounted controls hold provider open while ignoring thread interruption, then
assert editor-lifetime cancellation, no mutation and cleanup when a descriptor
returns after the canceled operation is released. The exact predecessor run
showed Robolectric's provider transport did not trigger the registered provider
cancellation listener, so these mounted controls do not claim to verify that
transport. New API31 Robolectric media controls exercise the actual Android
`CancellationSignal` boundary directly: an open that ignores thread interruption
is released by the signal, a late stream is closed without reading, and a
throwing cancellation listener cannot replace coroutine cancellation or leak
the stream. A separate held-read control still requires close-responsive cleanup
and exactly one close.

Copilot comment 4472191328 also found the source chooser, unlike the repaired
crop, had no short-window scrolling. Its whole content now scrolls, including the
title and Cancel, rather than allowing fixed chrome to starve the action viewport.
An existing-avatar mounted case at 220 dp / 200% font requires full reachable
photo/file/remove/cancel targets, unchanged bytes on picker/cancel returns and
one actual removal. Exact hosted run [41915483387](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41915483387)
for candidate `68442bb62e0d7344bcca659874ba4f0d823e35c7` failed. It reported the
shared `cropDialogFitsTheMeasuredCanvasInAShortLandscapeWindow` assertion at
`SharedUiComposeTest.kt:595`, and the consumer's short/large-font crop test at
`ContactAvatarFlowTest.kt:1023` on both API31 and API37 (the zoom Slider target
was short of 48 dp). These independent failures are addressed by the WP-304
owner's immutable patch `3cbbc9deebd837cefb6c2668c01116979d7c43ba`, now
composed as `89ee9f663fb3f17a8f4a389588442d3ac3b8e866`: the shared test scrolls
before measuring the actual canvas node and verifies the visible crop guide,
and the Slider uses `sharedTouchTarget()`.

The same run timed out in the mounted held-open provider cases on API31/API37.
The screen lifetime was not required to wait for an uninterruptible framework
transport; the mounted control now asserts screen-lifetime cancellation and,
after test-controlled release, checks late descriptor cleanup and no mutation.
Signal cancellation and late stream cleanup are separately exercised at the
direct signal-aware provider boundary, including a throwing listener. Three
gated save-error controls reached the holder's expected failed state with
`isSavingAvatar=false`, but the mounted tree still showed "Saving photo" and no
new Activity-owned error dialog. Gate release is now dispatched on the UI
thread, and test diagnostics retain the save-stage sequence and distinguish
holder state from dialog semantics.

The App `ContactAvatarMountedStoreTest` suite completed successfully in that
run; this is not a pass for all App tests or the affected closure. The failed
command also reported missing mandatory JUnit directories for some modules, so
its available-report totals are incomplete and do not establish aggregate
acceptance. The successor candidate is `b5e2fd6c65a26782598ca5ad49c0e6695b8fd4d1`;
exact hosted run `41926857045` is pending. No candidate pass is claimed yet.

Comment 4472524286 found that a throwing cancellation/close callback in the cleanup
child could still override coroutine cancellation. Cleanup now takes stream ownership
once, uses standard close/suppression handling even if signal cancellation throws,
and applies the same parent-context cancellation precedence as the read/open path.
Expected provider cleanup failures are retained as suppressed cancellation diagnostics,
not published as a new media error on a closed editor. Added pure held-read controls
for throwing cancel, throwing close and both together, plus an actual held-open
provider whose cancellation listener throws after releasing a late descriptor.
These controls require exactly-once cleanup/cancellation and no post-exit media error;
normal close failures still surface when the operation has not been cancelled.

Hosted candidate `91614d8e6`, run `41899043987`, failed
`:feature:nodes:testDebugUnitTest` (279 tests completed, 40 failed).
Most new-flow failures occurred before crop presentation: the fixture overrode
the signal-aware untyped asset method, while read-mode resolver opening actually
dispatches to the typed-asset provider method. The fixture now implements that
real callback and includes a direct resolver/decode control. Two pure failure
assertions also incorrectly assumed immediate exception identity across coroutine
stack-trace recovery; they now require the exact original failure in the cause
chain, preserve security failure type/message, and still reject successful reads.
The failed command lacked the mandatory App test report and is not aggregate
acceptance. No positive discovery/execution of the new shared crop methods is
inferred from this failed run.

## Current-main integration and successor candidate

The producer dependency is now merged: current `origin/main` is
`eea2a1c23f9e5a7927a22795946fdf5ced79a823`, including #552 merge
`43341865f923d439ace1f22edac81f7fb642355c`. This worktree merged that exact
main tip cleanly as `ab0cae413`; no avatar/shared-crop path conflicted. The
current local candidate therefore includes the merged identity-checked
preferences/OCV/telemetry writes and block-transition behavior without editing
the #552 producer branch.

On the merged tree, `C:\Python312\python.exe -B tools\android-port\controller\validate.py`
passed (valid manifest; 64 work packages), `C:\Python312\python.exe -B tools\android-port\portmap.py`
exited 0, and `git diff --check` passed for both the worktree and merge commit.
The predecessor hosted run
[41926857045](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41926857045)
for `b5e2fd6c65a26782598ca5ad49c0e6695b8fd4d1` remains in progress and is not
acceptance evidence for this merged candidate. Android/Gradle tests were not
run locally because the supported execution runtime is still held; exact-head
hosted verification is required. No physical-device, TalkBack or iOS
verification is claimed.
