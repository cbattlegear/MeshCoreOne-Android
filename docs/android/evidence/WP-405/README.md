# WP-405 evidence

## Issue #544: first-graph process-store contact lookup

### Origin-bound resolution and completion (source only)

The Boolean retry precheck in `620ee9fb9e7d24d2eaea985e1f589245f089e633`
did not resolve a retained null-radio capture's permitted first graph. It
could skip an existing Room contact. The agreed WP-302 successor contract is
`resolveContactLinkContext(context, publicKey): ContactLinkLookupResolution`,
with `Stale` or an opaque `Current(radioId, selfPublicKey, origin)`.
The router now resolves every attempt under its delivery guard, uses that
snapshot's effective radio/self outside guards, and passes the exact same
snapshot to `completeResolvedContactLookup`. Production snapshots are minted
by the producer; the router never constructs one or re-captures the origin.

Completion returns `TargetChanged` or `Completed(ContactLinkCompletion)`.
`TargetChanged` means the context is still valid but the attempted radio/self
changed, including null resolution followed by legitimate first binding.
It maps to `ContactLookupFailed(ContactLookupTargetChanged())`, remains
visible/unconsumed even on the first attempt, and requires explicit retry.
Retry then resolves and reads the first graph rather than treating a skipped
read as absence. Actual `Completed(WrongTarget)` stays terminal/rejected;
truly stale retry stays no-write/recoverable. Holder ID/attempt arbitration,
computed-failure publication, and cancellation retention are unchanged.

Authored controls, not executed results:

- Actual graph/Room null capture, precommit caller cancellation, first binding,
  and same-delivery retry; existing-contact and first-bound self families,
  with identical replacement isolation and exact consumed-ID assertions.
- Actual adapter null resolution, first graph binding before conditional
  completion, `TargetChanged` without state mutation, re-resolution and the
  saved Room row. This deliberately split producer schedule is paired with an
  actual router/holder first-attempt `TargetChanged` retention/retry control;
  it is not represented as a mounted-host concurrency run.
- Actual graph/Room retry resolution, suspended completed Room read, then
  same-radio graph replacement or navigation-revision supersession before
  completion; no additional publication/navigation/import/consumption.
- Existing different/identical graph replacement families additionally retain
  context through actual caller cancellation during lookup, with fresh B and
  stale-attempt controls preserved.

WP-302 owns the original saved-nonself first-graph control with no last-device
preference and its preference write held, the producer implementation, and
localized host wiring. Its exact isolated snapshot-producer successor is
pending; the agreed signatures are not imported producer source or a build
result. All new Kotlin source remains uncompiled/unexecuted. Execution custody,
source composition, local-before-single-push and merge holds remain unchanged.

Source-only checks for this consumer correction (Python 3.12.4):
`python tools\android-port\controller\validate.py` PASS (1,866 tracked reference
files), `python tools\android-port\portmap.py` PASS, and `git diff --check` PASS.
These checks do not compile/discover/run Kotlin tests and do not satisfy the
required combined local product verification.

### Historical retry-origin and publication corrections

`fced652cd946129e4902e66f989d2bf08000d061`
still re-captured the active graph when retrying the same failed delivery.
The holder now retains the original opaque `ContactLinkContext` through
precommit failure/cancellation, seeds each subsequent attempt's guard with it,
and clears it only when that delivery terminates or is superseded. The router
does not recapture or increment the lookup revision on retry. Its adapter
previously required WP-302's synchronous read-only producer seam
`isContactLinkContextCurrent(context, publicKey)`, checked under the delivery
guard before any retry store read. Store I/O still runs outside all guards and
used the original captured radio. The sealed resolution above supersedes this
Boolean seam and permits only producer-validated first binding.

Stale validation or stale-context completion on retry returns visible
`ContactLookupFailed(ContactLookupContextStale())` without commit/consumption.
Completion continues through the producer's conditional validation. The same
delivery cannot silently retarget to another radio or an identical replacement
generation; only a genuinely new delivery may capture that graph. ID/attempt
Retry/Cancel arbitration remains unchanged. Added authored router controls for
pre-read rejection, post-read completion rejection and cancellation retaining
origin, plus real graph/Room different-radio and identical-radio replacement
families with no replacement read/effect/ack and a fresh B-delivery control.
The earlier real same-graph recovery control remains. The replacement fixture
checks the captured public key and delegates retry validity to the producer's
historical pure `isCapturedContactLookupCurrent` predicate using its current bound token
and captured initial-binding allowance; the current fixture instead delegates
to `resolveCapturedContactLookup` and `completeResolvedCapturedContactLookup`,
including the producer-issued target identity. It does not fabricate validity.
An additional authored recovered-lookup schedule traverses that actual pure
coordinator predicate before committing the retained delivery once.
These cases are uncompiled/unexecuted. The new producer seam is absent on this
branch until WP-302 composition; no standalone build/acceptance is claimed.

Exact isolated producer prerequisite
`b4e1ce63d90e1d48547f37ddf5ff1c01a74ba814` (parent
`4c2522d37c0b12e4e41764a8b5f926b7612076b3`) was inspected through Git
objects, not imported. Its two-argument container check shares the graph
predicate with completion and checks the captured public key. Its pure
coordinator check shares generation/token/radio/revision matching with the
completion CAS and preserves first-binding rules. Accepted self-capture also
allocates and consumes a revision. The isolated commit includes producer
regression source, not executed evidence. Consumer composition and localized
host wiring remain coordinated prerequisites; this pin does not lift a hold.

Source review found that `d4fbcb31` had incorrectly grouped retry
`WrongTarget` with no-write `StaleContext`. The actual producer publishes
`NavigationFailure.WrongTarget` and advances the lookup revision via CAS.
The corrected consumer therefore maps `WrongTarget` to terminal
`ContactLookupRejected` on every attempt, commits the published result and
consumes only that delivery, while leaving the producer's visible failure
intact. It never navigates/imports the mismatched contact. Only truly stale
retry completion remains recoverable and uncommitted. An authored real pure
coordinator retry control returns a mismatched DTO after transient failure,
asserts the exact failure/revision-only state change, no contact navigation or
consent, and once-only delivery accounting. It is uncompiled/unexecuted.
The complementary actual-coordinator schedule supersedes the lookup revision
during retry and asserts that stale completion adds no state publication,
navigation, consent, failure or consumption, retaining the retryable delivery.
An additional authored actual-coordinator schedule cancels the retry caller
immediately after the real `WrongTarget` publication, before the parent can
consume the route result, on standard and unconfined test dispatchers. It
asserts visible failure/revision-only publication, cancellation propagation,
exact terminal delivery accounting and no replay via retry, duplicate submit,
or old-attempt actions. This source is uncompiled/unexecuted; it does not
establish cancellation-at-WrongTarget execution evidence.

The adapter-only successor `1fec5c1c7751fd80989cb5d09a12b5dd7debee09`
did not close the consumer failure path: the router still used `lookupOrNull`.
The follow-up distinguishes `ContactLookupFailed(cause)` from genuine absence,
propagates cancellation, logs a generic warning without URI/key/error content,
and does not invoke contact completion or commit a failed lookup.
`PendingExternalRoute` returns `RetryableFailure`, retains the original
delivery, and excludes it from consumed IDs until actual commit, supersession
or explicit cancellation.

The retained process-memory `lookupFailure: StateFlow<LookupFailure?>` includes
`deliveryId`, monotonically increasing `attempt` and original `cause`.
`retry(expectedDeliveryId, expectedAttempt)` and
`cancel(expectedDeliveryId, expectedAttempt)` compare and claim the exact
current failed attempt under holder arbitration before suspension. Stale
actions return `Superseded`; matching cancellation returns `Cancelled` and
accounts only that delivery. A replacement clears the former failure; a late
superseded failure cannot publish over its replacement. Failure publication
occurs after in-flight cleanup and outside lifecycle/delivery-guard monitors.

Four holder schedules, one router guard/recovery method and one real
graph/Room failing-then-recovered route method are authored, not executed.
The host still ignores outcomes on this branch: WP-302 owns the required
localized failure/Try Again/Cancel consumer, passing both observed identity
fields. There is no automatic retry policy or durable delivery guarantee.
**Issue #544 is not end-to-end complete or runtime-verified.**

Source review of `ab5ae0cde3a7753cc93ae0b32cea1aadc90be8b2` found
a cancellation window between the child computing a failure and the parent
consuming `await()`. The follow-up records the child outcome in an atomic
attempt-local cell before the child returns. Non-cancellable cleanup joins
the child and publishes that cell only if the same routing still owns the
pending delivery and no commit occurred. A canceled parent therefore cannot
hide a computed failure; a superseded child still cannot publish over B.
One additional deterministic schedule cancels the submitter after computing
the failure but before parent consumption, then retries the retained exact
attempt. It is authored, uncompiled and unexecuted.

The external adapter now calls `container.processStore.fetchContact` with the
captured radio and public key, rather than the last-device-preference-gated
`offlineDataStore`. This removes a source-derived false-absence window before
the first graph's device/last-connection persistence completes. Typed capture,
completion, revision and session/generation fencing remain unchanged.

Existing reservation revision 20 covers both changed Kotlin paths; trusted
`reserve.py admit --wp WP-405 --path <path> --operation modify` accepted each.
Two authored Room-backed adapter methods cover an absent last-connection
preference, restored contact lookup, foreign-radio isolation, genuine absence,
and exact exception propagation without creating a radio graph. They are
**uncompiled and unexecuted**; WP-302 owns the separate first-graph route
regression. This is not a reproduced or verified runtime fix. The retained
execution-owner and combined-local-test-before-publication holds remain intact.
No channel/service cohort, producer lifecycle source or foreign worktree changed.
Source checks passed: `python tools\android-port\controller\validate.py`
(valid manifest, 1,866 tracked reference files),
`python tools\android-port\portmap.py` (2,003 implementation records), and
`git diff --check` (no whitespace errors). None establishes Kotlin compilation
or product-test discovery.

## Dependent external adapter for producer 208dad904

Exact producer commits `3a372fd709bd266bc987296b5338d7e78186c41a` and
`208dad904c730210d21fbc209d2d71946ca89d18` are available in the repository's
Git object database and were read without another checkout/import. The adapter
requires the latter's explicit `selectChats` argument and shared typed
capture/completion/publication API; no unsafe default implementation was added.
This branch does not contain those producer definitions and is not independently
compilable/landable. Combined source custody belongs to the coordinated owners.

Actual trusted admission accepted `modify` for each of:

- `android/app/src/main/kotlin/com/meshcoreone/android/app/deeplinks/AppDeepLinkRouter.kt`
- `android/app/src/main/kotlin/com/meshcoreone/android/app/deeplinks/ContainerDeepLinkEnvironment.kt`
- `android/app/src/main/kotlin/com/meshcoreone/android/app/deeplinks/DeepLinkModels.kt`

It accepted `add-or-update-related-test` for each of:

- `android/app/src/test/kotlin/com/meshcoreone/android/app/deeplinks/AppDeepLinkRouterTest.kt`
- `android/app/src/test/kotlin/com/meshcoreone/android/app/deeplinks/ExternalRouteReplacementTest.kt`
- `android/app/src/test/kotlin/com/meshcoreone/android/app/deeplinks/LinkConsumerIntegrationTest.kt`

The same advisory owner/attempt
`d76f0641-87d4-4218-92e9-3cdf3f972471` evolved revision 18 to 20 for the two
new exact test-path capability entries; the existing integration entry was
already covered and remained revision 20. A stale revision-18 evolution attempt
was refused without mutation, then retried against actual revision 19.
Authorization prose, foreign reservations and the retained execution lock
were not changed. Per-WP deviations/evidence remain within owned paths.

Seven router and two real-container integration test methods were added.
Existing replacement methods retain cancellation, once-only delivery, state,
back-stack and exact identity assertions while consuming the new producer.
No new test has been discovered or executed. Source checks actually passed:

```powershell
python .\tools\android-port\controller\validate.py
# PASS: valid manifest/reference ownership
python .\tools\android-port\portmap.py
# PASS: 2,003 implementation records
git diff --check
# PASS: no whitespace errors
```

These are not a Kotlin build or Android/native test result. Product execution
remains blocked by `local-check:f516a7e271f444dd9cb2efcc8b995a16` and its
missing genuine supervisor cleanup proof. The WP-405 local-tested-before-
publication condition also applies if these consumer hunks are combined into
#495; source transfer cannot waive it. Existing PRs/runs are preserved, and no
push, independent consumer landing or combined acceptance occurred.

WP-302's later frozen source exploration
`28883bb7995e1179a8b13878309863b7861cb2af` was inspected through the shared Git
object database. Only its attributable external replacement-fixture correction
is adopted on this lineage: hold the physical successor handshake, drain
retirement, snapshot actual retired navigation identity/generation, then assert
the runtime successor is still unbound without assuming A remains bound.
The exact no-mutation/no-extra-read assertions remain. Test cleanup progresses
its separate UI scheduler while closing the real container. No new method or
runtime result is claimed, and no producer/environment/lifecycle implementation
is imported from that source exploration. Existing admission covers this test;
no claim mutation, product execution or publication occurred.

The eventual producer composition must retain the Room fixture at WP-302 head
`c4d8b73184dd02da370a2650c3fa3a218ec16231` (tree
`191db8b627647cf25d1e7514ce5949c6ce27a2c2`), which supersedes support commit
`c8d11bd06e13e74273bcaa7f81ccb6c88f8cc808` and descends from the inspected
`28883bb7995e1179a8b13878309863b7861cb2af` exploration. The predecessor's
`android/app/src/test/kotlin/com/meshcoreone/android/app/rooms/RoomAccessibilityComposeTest.kt`
edit implements the two required typed `ChatLinkEnvironment` methods in the
existing fixture and adds
`contactLinkActionInRoomNavigatesToTheCapturedSavedContact`. The support diff was
inspected directly and preserves existing assertions. The successor removes an
unreachable `ConfirmationStaged` success branch: an absent contact still invokes
the fixture's existing `error("Unexpected contact import")`, not fake success.
The corrected fixture blob is `660cb0193c7f4e023598b160425fa3c80fd1ca82`.
Omitting the interface support leaves that
fixture unable to implement the new interface. It depends on producer types and
is not imported into this older, still-dependent worktree in isolation.
Source inspection is not mounted execution evidence; the fixture regression and
combined Kotlin compilation remain unexecuted here. This prerequisite does not
waive the local-before-publication gate or transfer the retained channel cohort.

## Terminal-ID producer seam: source-only follow-up

The existing coordinator assigned the exact-ID restoration support seam
to this producer. Actual trusted admissions passed:

```powershell
python .\tools\android-port\local\reserve.py admit --wp WP-405 --path android/app/src/main/kotlin/com/meshcoreone/android/app/deeplinks/PendingExternalRoute.kt --operation modify
python .\tools\android-port\local\reserve.py admit --wp WP-405 --path android/app/src/test/kotlin/com/meshcoreone/android/app/deeplinks/PendingExternalRouteTest.kt --operation add-or-update-related-test
```

Same advisory reservation revision 18 already covers these paths/operations.
The narrow accessor returns an immutable copied `SnapshotSet<String>` from
the actual bounded terminal-consumed history under its existing mutex.
Supersession, rejection and commit-then-cancellation are not success receipts.
No callbacks or routing effects are added; WP-302 activity restoration is not
implemented in this branch.

Four additional test methods and new assertions in three retained methods
cover exact IDs through actual drain cleanup, cold/in-flight identical
replacement, rejected routing, retryable cancellation/failure and bounded
snapshot detachment. They are source definitions, not discovered/executed
tests. Product compilation/runtime evidence for this new seam is absent.
The interrupted invocation and retained execution owner below are preserved;
no retry, lock release, unmerged consumer import or publication occurred.

## Resumed source candidate: verification pending

The user approved resumption, removing the earlier administrative pause/local
execution hold but not supported execution admission, required local results,
independent review, device evidence or frozen shared-state gates.
The two retained unpublished commits remain in history.
Actual merged producer PR #525 (`2d4c0eb4fdd629ef960698fa2e4562bc29c7c9d8`)
is now available through current main
`1e1ff70345f203b41f9a8b04836c2cc4640500c9`, integrated without conflicts in
`132112943c2600126924805bbe2065b9be1a1682`.

Actual trusted admission commands returned the reviewed support capabilities:

```powershell
python .\tools\android-port\local\reserve.py admit --wp WP-405 --path android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/contacts/ChannelService.kt --operation modify-related-consumer
python .\tools\android-port\local\reserve.py admit --wp WP-405 --path android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/contacts/ChannelServiceNativeTest.kt --operation add-or-update-related-test
```

The existing advisory revision 18 includes both paths/operations. This is not
a privileged controller receipt or source-ownership transfer.
The service consumes fresh dispatch handles for set and clear; only proven
unsent cancellation avoids quarantine. Six new native definitions exercise
both operations, real queued/sent transport schedules and an UNKNOWN negative
control. Existing delegated wrappers retain their actual holds by forwarding
the new overload. No producer test result is consumer acceptance.

Dependency-free commands actually passed:
`python .\tools\android-port\controller\validate.py` (valid);
`python .\tools\android-port\portmap.py` (2,003 implementation records);
`git --no-pager diff --check` (no whitespace errors).
These do not establish Kotlin compilation or test discovery.

The exact supported execution attempt was:

```powershell
python .\tools\android-port\local\check.py --commit 43c8fb940ca3cfd0fd6fde566b922ea89abb6b6d --affected-base 1e1ff70345f203b41f9a8b04836c2cc4640500c9
```

Result: **BLOCKED**, exit 1, `Installed execution runtime is old or mismatched:
controller/merged_acceptance.py`. No product compilation or test discovery/
execution occurred. This is a runtime readiness refusal, not an App test
failure or a passing consumer result. The separately assigned execution owner
was notified; no runtime installation, direct Gradle, unsafe WSL invocation,
blind hosted retry or publication is substituted. Capacity cleanup alone is
neither a slot grant nor proof that this build fits.

After the execution owner completed the guarded trusted-runtime update, the
same supported command was retried with head
`3c5297acdc083df21ec08afd7654aeaaa103ee59` and the same base. The launcher
admitted that invocation as
`local-check:f516a7e271f444dd9cb2efcc8b995a16`; original reports are at
`/home/cbattagler/meshcoreone-work/local-checks/run-OY53EZj7`.
The trusted selector required full scaffold because the existing dynamic
project dependency declaration in `android/core/ui/build.gradle.kts` could
not be narrowly classified. This was the selector's required fallback, not
a manually widened test command.

Python passed 16 scaffold, 110 parity-accounting and 71 local-tool fixtures,
with zero failures/errors/skips. Host preflight and the graph/dependency
inventory command passed. The original scaffold log records completed
`:core:services:compileKotlin` and ends during subsequent compilation; it
contains no final build result or product-test discovery/result.

On resumption after interruption, the shell handle was unavailable and the
original supervisor's `cleanup.json` was absent. Supported recovery status
retained the hard execution owner. Read-only inspection found its recorded
Windows PID reused by a different, later-created process and no matching
Linux build process; neither observation proves owned-child cleanup.
The execution owner was given the exact invocation for guarded recovery, and
the waiting WP-311 consumer was told no lane release was established. No
ownership record, process or retained report was altered and no duplicate
invocation was started. Available Linux disk space was approximately 1.6 GiB;
this is not proof that disk exhaustion caused the interruption.
The candidate remains unverified and unpublished.

The execution owner independently confirmed the exact receipt/admission and
missing cleanup proof. Trusted `execution_recovery.py` conservatively treats
any existing process at the recorded PID as potentially live; the receipt
does not retain launcher birth identity. Both ordinary recovery and the
invocation-owned cancellation route therefore refuse this reused PID.
PID exit alone would still not supply the missing supervisor cleanup proof.
The owner escalated this supported-recovery gap to the coordinator without
signalling the unrelated process, modifying the ledger or releasing the lane.
The existing emergency route requires a verified full Windows restart after
the invocation plus explicit original-host/preboot abandonment attribution;
neither is established here. Product execution remains blocked on supported
execution-owner recovery, not an administrative pause or an App test failure.

## Form-instance follow-up: historical source-only held candidate

This follow-up builds on local unpublished
`288b6f558fb21174c7251bfdcb451d986775350a`, not on a new hosted pass.
Form-scoped compare-and-set completion prevents a returned UUID or terminal
outcome from adopting a replacement form. Region preference setup remains
within that submission; original cancellation and ordinary region-failure
handling remain. Four additional holder methods define the reopened identical
page/name, committed failure/cancellation, Create-to-Share and held-region
controls. These are definitions, not discovered/executed test counts.

The user explicitly selected `Keep local Android/Gradle hold`. No local
checker, direct Gradle/compiler, native runner, runtime installation or
ownership workaround is used. Dependency-free controller/portmap/whitespace
checks do not establish Kotlin compilation or behavior.

The separate recovered-drafts-list UX is now selected, with explicit user
copy into a chosen chat and preservation of unbound original text. Its UI and
durable occupant provenance are not implemented. Actual trusted admission for
`add-or-update-recovered-draft-copy` on the App default
`platform_strings.xml` returned DENIED (no trusted path/operation rule).
There is no candidate-policy, generated-resource or English-copy workaround.

## Issue #417 allocation-through-save candidate: exact-head failure

This local follow-up starts from `0c8e3cb22816673d2373fb20f2cbab3744387eaf`,
against trusted/current main `5878b5357dc9fe9986f002fe68763aca0bc35303`,
with source pin `db14559b39d32322b06477c6ae676112f583db50` unchanged.
It implements fresh service-owned allocation, shared read/write/batch-save
serialization, observed-slot authority and explicit uncertain-write quarantine.
See `docs/android/deviations/WP-405.md` for lock ordering, adaptation and limits.
The existing advisory owner `d76f0641-87d4-4218-92e9-3cdf3f972471` evolved
revision 13 -> 14 -> 15 with homogeneous production/test operations; this is
not a privileged current-controller receipt.

Published head `9782d78c8580f10da71784396e019e3150c5bea7` failed
[run 41463646130](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41463646130).
The Android job `125338919312` original log reports 1,893 App tests completed,
31 failed. Twenty confirmation cases reported superseded-generation
cancellation; eight timed out waiting for confirmation consumption; three
region cases returned null. The log does not identify their failing region
vector. Classifier and controller/policy jobs passed; aggregate Android failed.
No rerun or second CI-result bundle is produced.

## Issue #417 local outcome/fixture follow-up: Android unverified

The unpublished follow-up starts from the failed exact head above. The same
stored advisory identity evolved revision 15 -> 16 -> 17 -> 18 for the
existing pending dialog, feature/app channel rendering seams and existing
region-consumer test. It retains actual returned store UUIDs through later
fetch/cache failure or cancellation, prevents same-confirmation resubmission,
and preserves read-only cached hashtag navigation after disconnect.

Added definitions, not execution evidence: six native outcome cases, three
holder methods covering both join kinds, four mounted post-save failure/
cancellation methods and one mounted offline options-sheet method. Existing
methods/assertion families remain. Fixture corrections use indexed empty
ChannelInfo responses instead of unindexed negative packets that the actual
query matcher ignores. The partial READY fixture now covers observation
persistence failure, explicit authoritative recovery and a real held retry
batch; it does not prove automatic delayed timeout retry or recovery of an
uncorrelated wire reply. Region-join assertions now include vector/failure
diagnostics without removing any vectors.

The additional holder regression suspends the first allocation before its
commit, submits a second consent programmatically and requires immediate
rejection without a second service call, lost progress or fabricated result.
It then releases the original operation and checks the exact saved row and
returned receipt. Production submission admission is one StateFlow
compare-and-set rather than a separate guard and progress update.

Declared local checks: `python .\tools\android-port\controller\validate.py`
PASS (1,866 references, 64 WPs, 178 edges);
`python .\tools\android-port\portmap.py` PASS (1,999 implementation records);
`git --no-pager diff --check` PASS. An initial mixed AndroidOnly/PortedFrom
annotation caused a traceability-disposition failure and was corrected without
changing validator policy. No local Android/Gradle execution or manual workflow
dispatch is authorized or performed; compilation and runtime behavior of this
local follow-up remain explicitly unverified. Trusted native-copy admission,
producer API landing and coordinated occupant/restore/notification ownership
remain prerequisites for their affected integration; none is inferred from
source-only checks or another owner's unpublished candidate.
Do not resolve the high review thread or infer full #417/#398 acceptance from
source changes or these dependency-free checks.

## Issue #417 mounted observation correction: prior exact-head pass

The corrected head `0c8e3cb22816673d2373fb20f2cbab3744387eaf` passed
[run 41456929553](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41456929553)
against base `5878b5357dc9fe9986f002fe68763aca0bc35303`.
App discovered and passed 1,881 cases, zero failures/errors/skips; all 26
mounted confirmation records passed, including eight held-after-save records.
This result belongs only to that prior head, not the new allocation candidate.

Exact head `ce5bf82f41e7e69aab05bea73c0f03d33551c4ed` failed
[hosted run 41453274809](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41453274809)
against base `5878b5357dc9fe9986f002fe68763aca0bc35303`.
App discovered 1,881 cases: 1,873 passed, eight failed, zero errors/skips.
All four `heldRealJoin` variants failed on SDK 31 and 37, at lines
365/369/374: the expected committed/existing `pendingChannel.id` was null.
The Android job `125303663630` log is authoritative; no duplicate result
bundle is retained.

The actual mounted root consumes `pendingChannel` after opening its route.
The correction retains all 13 declared methods and their SDK families; it
does not convert the failure into a null-only success assertion. Bounded
observation of handoff consumption is paired with exact lasting selection,
top destination, preserved prior stack/other stacks, one selected-conversation
entry and its real editable composer. Existing confirmation identity,
submitting/resize, metadata, packet and once-per-consent checks remain.
That observation correction subsequently passed at the exact head above.
No local Android/Gradle or manual workflow dispatch is authorized or run.

The fixture still pauses after ACK and Room save, not before A's `0x20`
reply. It cannot cover the separately recorded pre-ACK two-consent slot
allocation race, late-ACK cancellation or service reconciliation.
The new allocation candidate above adds distinct pre-ACK coverage; the older
pass does not establish that repair or protocol reconciliation.

Cold-start support assessment used exact trusted-main consumer operations
for `MainActivity`, `MeshCoreApplication`, `AppContainer`,
`ConnectionManagerAppPort` and `AppState`. The existing advisory attempt
`d76f0641-87d4-4218-92e9-3cdf3f972471` evolved revision 11 -> 12 -> 13
using production-only and then related-test-only calls. An initial mixed
operation call was refused atomically because the CLI cross-products paths
and operations; it did not deny the subsequently admitted exact pairs.
Historical binding and authorization remained unchanged. No ledger rewrite,
new identity, successor, #495 source/reservation change, or controller
acceptance occurred.

No startup source repair is claimed: cache initialization must not stand in
for the initial foreground teardown/provenance boundary, and awaiting the
existing foreground job can await a physical reconnect without conveying a
successful outcome. The exact unresolved methods and required producer split
are recorded in `docs/android/deviations/WP-405.md`. Required cold saved
contact/channel, initialized-empty, failure/retry, cancellation and
replacement regressions remain unexecuted; no full #398/#417 acceptance
follows.

## Issue #417 consumer-only follow-up: failed first execution

Finding: https://github.com/cbattlegear/MeshCoreOne-Android/issues/417#issuecomment-6578143740.
The original schedules were static/unexecuted. Source inspection at #508
`48474607c10e50a27933fb0cc0992905f2666e45` confirmed dual-slot clearing,
per-entry dialog mounting and holder identity tied to the unrelated slot.
Its exact current-base run `41442628576` passed against
`5878b5357dc9fe9986f002fe68763aca0bc35303`; that run does not execute this repair
or establish any new finding's outcome.

WP-302 explicitly reported no active competing channel-dialog/relocation
edit. Its #495 contact/room-auth and owner-metadata changes are retained as
future disjoint reconciliation requirements, not imported APIs. Trusted
`app-build-launcher` / `add-direct-support-wiring` and
`reviewed-support-test` / `add-or-update-related-test` admission covers the
exact consumer/test paths. Supported same-owner evolution reached revision
11, preserving historical authorization/binding; #495 and its reservation
remain frozen and untouched.

The original five `ChannelConfirmationConsumerTest` methods are retained.
Eight additional methods declare:

- Fresh identities for identical/different same-kind staging; old dismissal
  and successful completion cannot affect the replacement, for both kinds.
- Both cross-kind staging orders, cancellation and successful acknowledgement,
  retaining the other slot and completed navigation.
- Real-coordinator reentrant publication observing acknowledgement and
  navigation in one state before identical replacement.
- Real adaptive shell, existing conversation, compact/tiled resize in both
  directions, one accessible dialog, metadata and cancellation preserving
  the unseen other kind without a connection/write.
- Real-graph channel write held after the actual command and Room save:
  independent hashtag arrival keeps submitting disabled across resize and
  one consent produces exactly one command with original secret/scope.
- Identical and distinct channel replacements before that held join returns
  survive the old completion without obsolete navigation.
- Reverse independent channel arrival while a hashtag join is held, including
  temporary preemption/cancellation/reappearance with submitting still
  disabled, followed by preserved independent pending request and one write.

The held-write gate wraps the production persistence protocol after the
real firmware acknowledgement and Room save; it is not a replacement router,
callback-only action or invented radio result. The failed hosted execution
and unexecuted correction are distinguished above. No local Android/Gradle
is run. No physical-device or integrated
lifecycle claim follows. External/chat held-lookup session replacement and
confirmation/write session ownership remain blocked on WP-302's actual
accepted shared boundary after #506; no full #417 completion or merge follows
from this consumer-only candidate.

## Issue #504 related map-consumer synchronization follow-up

Exact selection candidate `d2d85f1fbb6557d8cafc71756a89c096c1277287`
compiled and executed in hosted run `41430849175`. All 29 selected
router/replacement/pending/existing-channel cases passed, but the complete App
suite failed: 1,865 completed, 1,863 passed, two failed, zero errors/skips.
The unchanged failures were the malware refresh preference observation timeout
and `ChatMapPreviewTest.rendererExceptionIsARecoverableFallbackRatherThanAStuckSpinner[31]`.
That is not a passing candidate.

The map test clicked Retry and immediately asserted a pin. The actual path is
`AppChatMapPreviews.retry` -> `MapSnapshotStore.request` -> a launched rendering
coroutine -> resolution flow -> Compose image state. Click completion does not
await rendering or its UI publication. The directly related test now uses the
same bounded 10-second real-pin semantics wait already used by neighboring
cases before retaining the original displayed-pin and exact two-request
assertions. The initial renderer exception, retry fallback and no-spinner
assertions remain unchanged; no map production, timeout threshold or repeated
attempt is added. This confirms a missing ordering barrier in source, not the
sole cause of the historical failure or an executed failing baseline.

Trusted admission is `reviewed-support-test` /
`add-or-update-related-test`; the same stored advisory owner evolved revision
7 to 8. WP-302 identified only a distinct unmerged edit in the neighboring
`sharedLoadingFailureAndRetryReachBothCardsWithoutDuplicateRenders` method,
which is unchanged here. New exact-head hosted results are pending; no local
Android/Gradle is run. The separate WP-204 producer #521 remains unmerged and
its candidate/evidence is not imported or attributed to this repair.

## Issue #504 selection follow-up: new execution pending

The previous exact candidate `ab5e5fabf7413ba54fa24fd56c25d5d6d4760eb6`
passed hosted run `41395949358` against actual main
`d85ac9f8a5367615099bc215acc8d022c11eb75b`: App 1,857 and chats 443
discovered/run/passed, zero failures/errors/skips. That run does not verify the
new selection repair below.

WP-302's static finding identified Settings -> suspended A -> rejected B
retaining A's obsolete temporary Chats selection. The App router now leaves
navigation untouched until a guarded target commit; external confirmations
select Chats in the same StateFlow update as staging, avoiding a reentrant
collector between selection and request publication. The environment carries
an explicit selection flag; existing navigation staging/clearing callers retain
their default behavior. No WP-302 lifecycle or lookup-ordering source is
imported. The deliberate pending-lookup timing
adaptation is documented in `docs/android/deviations/WP-405.md`.

Eight additional `ExternalRouteReplacementTest` methods exercise the actual
router/NavigationCoordinator: rejected replacement retains original selection
and every stack (including rail tool detail); user choices during lookup are
not rolled back; committed contact results survive rejected B; valid map,
channel, hashtag and identical-URI replacements commit their own selection and
metadata without damaging unrelated stacks. Additional methods cover the exact
rail-width boundary and Back, approved tool cleanup on a committed switch,
reentrant StateFlow submission during publication, and unchanged internal
routing/clearing semantics. All previous assertions are retained; only the fake
environment signature is updated to carry the atomic selection flag.
Definition inventory is now 15 replacement + 8 pending + 5 router =
28 targeted methods, not an execution count.

Existing owned router/test/deviation/evidence operations were admitted by
`tools/android-port/local/reserve.py admit` under `wp-owned`. The directly
necessary `NavigationCoordinator` staging-method wiring was admitted under
`app-build-launcher` / `add-direct-support-wiring`; the same stored advisory
owner evolved revision 6 to 7, preserving historical binding and authorization
context. WP-302 confirmed it is not actively editing those methods; its unmerged
stageContactLink/completion-CAS overlap must be reconciled in the actual order.
No ownership,
lease binding, source pin, policy or review verdict was rewritten. New Kotlin
execution remains unverified until the next normal PR-triggered scoped run.
No local Android/Gradle or manual workflow dispatch is used.

## Issue #504 candidate: validation pending

This follow-up is separate from merged PR #347. Its integrated base is
`99919a0a8049c2da2bfab4c6cdc08bca787b3045`; the Swift source remains
`db14559b39d32322b06477c6ae676112f583db50`. Existing same-owner advisory
reservation `d76f0641-87d4-4218-92e9-3cdf3f972471` evolved from revision 4
through 6 using supported `reserve.py evolve`, admitting staging/router
modification under `wp-owned`. Historical authorization/binding fields were
preserved, not rewritten or treated as a fresh live execution grant. WP-302
owns its frozen contact lookup ordering/session work; this candidate does not
change its API, navigation state or environment projection.

Source characterization: previously B replaced `pending` while A held
`inFlight`; B's `drain()` returned `Staged`. A's completion released its slot
but did not drain B, and A's post-lookup consumer could still publish an obsolete
confirmation/navigation result. Identical URI text with a distinct ID had the
same defect. This is source-derived characterization, not an executed failing
regression.

The candidate cancels/retires A at B submission, routes B independently and
atomically guards validity/cancellation with synchronous router writes.
`ExternalRouteReplacementTest` defines seven methods covering known/unknown
contact results, distinct/identical URI deliveries, duplicate IDs, deliberately
non-cooperative A, the window before B enters the router, actual navigation and
confirmation state, channel/hashtag paths, metadata and once-only delivery.
The synchronous-write method additionally asserts that both real contact
navigation and confirmation effects hold the exact submission-invalidation
monitor, rather than relying on an unprotected check/write sequence.
The post-commit cancellation method cancels the caller immediately after the
real contact navigation/confirmation write and requires cancellation to
propagate without leaving a replayable ID. It covers both queued and unconfined
dispatch with known and unknown contacts, retaining the original pre-commit
cancellation/retry case.
`PendingExternalRouteTest` retains all five original assertions and adds
failure/retry, A/B/C pre-router replacement and a forced queued-submission
caller-ownership regression. The latter queues B from the initial child's
cancellation handler while A holds the staging mutex, checking that staging
and routing claim cannot interleave across callers. Total targeted
definitions: 20 methods across these two classes and `AppDeepLinkRouterTest`;
this is an inventory, **not a discovered/passed test count**.

Light validation performed:

```text
python .\tools\android-port\controller\validate.py
PASS: 1,866 tracked reference files, 1,786 owned, 80 excluded, 64 WPs,
178 dependency edges; manifest SHA-256
11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4.
git --no-pager diff --check
PASS for the candidate edits at authoring.
```

Kotlin compilation, regression execution, Android preflight, lint and App suite
discovery are **NOT RUN**. Local Android/Gradle is prohibited during runner
recovery; no remote heavy slot or live execution binding is inferred from
advisory path admission. A focused issue #504 PR was subsequently authorized
for normal change-scoped hosted validation; its unexecuted Kotlin results
remain explicitly unverified until the actual hosted job reports them.
No direct Gradle/compiler bypass or manual full workflow dispatch is authorized.
The historical passing results below must not be attributed to this candidate.

## WP405-LINKS repair verification status

The APP-G03/APP-G04/APP-G07 repair batch passed supported affected local
verification on the assigned Linux host at candidate
`35c1064d56f6affc92d3e2c585db0ed732230dd8`, tree
`2e60fbb49f81dcc1cf4b9af8fd146828e399f73d`, against execution baseline
`3a292b6498e8dcef0c374022a93c8e30c68f5bd5`. This is local automated
evidence, not hosted, physical-device, native, signing or human acceptance.
The separate historical section below must not be reused as repair evidence.

The repair preserves private-secret hashtag warning/scope metadata through the
mounted confirmation and reuses the unchanged protocol grapheme helper with the
shared 30-byte region limit in external, chat and QR parsers. Original source
remains pinned to `db14559b39d32322b06477c6ae676112f583db50`.
The feature-parser capability was freshly admitted after trusted support landing
`f2f481d2e7c2a9029808943529c98c98ef879299`; the same owner's reservation
evolved to revision 4 before that edit.

The committed regression definitions require these real consumer surfaces:

| Test class | Defined repair methods | Required behavior |
| --- | --- | --- |
| `app.container.LinkConsumerIntegrationTest` | 4 | Actual retained Intent delivery/binding, original San Francisco focus, once-only consumption and external/chat confirmation metadata |
| `app.container.ChannelConfirmationConsumerTest` | 5 | Mounted warning/scope before consent, exact confirmed firmware/Room state, disconnected disabled action and cancellation |
| `app.container.ChannelRegionConsumerTest` | 4 | External/chat/QR parser-to-real-join and persisted region/secret, absent/empty values, padded ASCII and whole combining-grapheme boundaries |
| `app.container.MentionNavigationConsumerTest` | 2 | Actual mounted mention producer/action to contact destination and non-mention dispatch, preserving all original encoded-name vectors |
| `app.platform.ChatMapPreviewTest` | 2 coordinate methods | Independent mounted San Francisco/London thumbnail clicks replace prior focus with exact coordinates |

Fresh source-bound JUnit reports discovered and passed 4 link integration,
10 confirmation, 4 region and 4 mention cases. Both coordinate methods passed
on API 31 and 37 (4 cases); the complete map-preview class passed 22 cases.
These named results were read while the execution snapshot still had the exact
tested HEAD, before slot reuse. No required case failed, errored or skipped.
The QR assertion covers the production parser/join seam, not camera or device
evidence.

The actual affected plan selected `module:app` and `module:feature/chats`.
The assigned canonical runner executed:

```text
env -i HOME="$HOME" PATH=/ai_cache/meshcoreone/python/3.12.4/bin:/usr/bin:/bin LANG=C.UTF-8 PYTHONDONTWRITEBYTECODE=1 ANDROID_LOCAL_HOME=/ai_cache/meshcoreone/execution/single-shared ANDROID_LOCAL_PYTHON=/ai_cache/meshcoreone/python/3.12.4/bin/python3.12 ANDROID_PORT_LEDGER=/ai_cache/meshcoreone/control/android-port-shared.sqlite3 /ai_cache/meshcoreone/python/3.12.4/bin/python3.12 -B tools/android-port/local/check.py --commit 35c1064d56f6affc92d3e2c585db0ed732230dd8 --affected-base 3a292b6498e8dcef0c374022a93c8e30c68f5bd5 --parallel-jvm
```

The derived obligations are complete `:app:testDebugUnitTest` and
`:feature:chats:testDebugUnitTest` runners, App/chats Kotlin compilation and lint,
`:feature:chats:check`, `:app:compileDebugAndroidTestKotlin`,
`validateModuleGraph` and `runtimeDependencyInventory`. No arbitrary Gradle
filters, direct execution workaround or full-scaffold widening is requested.
Result: terminal exit 0, Python/preflight/scaffold passed, and the owned launcher
printed final `AFFECTED LOCAL CHECK PASSED` after current-base revalidation and
owned-child/resource cleanup. App discovered/ran/passed 1,571 tests; chats
discovered/ran/passed 392 tests. Both had 0 failures, 0 errors and 0 skipped.
Lint passed with 50 App and 4 chats warnings. Module graph validation covered
30 modules; runtime inventory recorded 195 inputs. App AndroidTest compilation
passed; APK assembly was not selected by this affected plan and is not claimed.

The first run at `3c64b9845` terminated with exit 1 before App discovery because
the mention test used the single-action API for a list-valued semantics property.
The tested candidate fixes that using the existing custom-action list/idle-thread
pattern without weakening navigation assertions. No hosted-first debugging,
test filtering, direct Gradle or execution-interlock bypass was used.

The exact-commit hosted job/log remains authoritative for hosted checks. No
second CI-result bundle is committed. This evidence update changes documentation
only after the tested candidate; it does not change tested production/test bytes.

## Historical original implementation evidence

## Scope and provenance

- Work package: `WP-405`
- Owner: `platform-integrations-engineer`
- Integrated base: `e4a4eb1e41ed4616c66271c519ce1637c6375c83`
- Frozen Swift source: `db14559b39d32322b06477c6ae676112f583db50`
- Frozen Swift tree: `8918fdc604341e6996a68c88f6bb1c02b9c2f87e`
- Manifest digest: `58f7ebd7f46bbe0636c71005f20776efe139a287279e4f4708b25ce6bfa3f892`
- All five owned production sources and four owned Swift test files were compared to the frozen revision without drift.

The 47 owned frozen Swift test cases are named by `@SourceCases` annotations in
the WP-405 JVM tests. They are exercised by 26 discovered tests covering
contact/channel/map parsing, malformed and encoded input, query injection,
identity handling, hashtag/mention support, flood-scope application, typed
confirmation staging, pending cold-start delivery, duplicate suppression, and
cancellation retry.

## Security boundary

The exported manifest entry points are limited to:

- `meshcore://map`
- `meshcore://contact/add`
- `meshcore://channel/add`
- `meshcoreone://hashtag/<name>`

Routing recognizes only those typed forms. It can navigate to an existing
object or stage a typed confirmation request. There is no generic command,
shell, radio administration, or mutation dispatch surface reachable from an
external URI. Internal mention URIs are not exported.

## Verification

Run after integrating `origin/main` at the base above, with the repository
credential-stripping launcher, Temurin 21.0.12.1, compile SDK 37.2,
build-tools 37.0.0, strict dependency verification, and explicit isolated
Gradle/Android caches:

```text
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 1024m \
  -GradleArguments @(
    ':app:testDebugUnitTest',
    '--tests', 'com.meshcoreone.android.app.deeplinks.*',
    ':app:lintDebug',
    ':app:assembleDebug',
    'validateModuleGraph',
    '--dependency-verification', 'strict'
  )
```

Result: passed. Deep-link JVM discovery was 26 tests in 4 suites; 26 passed,
0 failed, 0 errors, 0 skipped. App lint produced 0 errors and 28 existing
warnings. `:app:assembleDebug` and `validateModuleGraph` passed.

```text
.\android\gradlew.bat :app:testDebugUnitTest \
  --tests com.meshcoreone.android.app.navigation.NavigationActivityTest \
  --dependency-verification strict --no-daemon
```

Result: passed; 4 activity/navigation lifecycle tests passed, 0 failed,
0 errors, 0 skipped. This verifies the WP-405 `MainActivity` integration
against the current WP-303 onboarding binding.

```text
python .\tools\android-port\portmap.py
```

Result: passed with exit code 0.

```text
python .\tools\android-port\controller\validate.py
```

Result: valid with nonzero inventory (1,866 tracked reference files, 65 work
packages, 185 dependency edges).

An exploratory unfiltered `:app:testDebugUnitTest` run at a constrained 768 MiB
heap discovered 387 tests before the Robolectric worker exhausted metaspace;
this was an environment-capacity failure, not a WP-405 assertion failure. The
declared targeted WP-405 run and required app lint/build/module-graph checks
above all passed at the final integrated base.

## Ownership adaptations

The manifest-declared deep-link source/test and WP-405 documentation paths are
used directly. `AndroidManifest.xml`, `MainActivity.kt`, and
`NavigationState.kt` are directly necessary app-launch/navigation support
edits admitted by the trusted app-build-launcher capability. The manifest edit
is a protected-path change and remains subject to the repository's normal
human review/merge protection.
