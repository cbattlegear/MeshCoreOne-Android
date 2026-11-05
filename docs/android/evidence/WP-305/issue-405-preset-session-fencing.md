# WP-305 preset producer session fencing

- Repository: `cbattlegear/MeshCoreOne-Android`
- Existing issue / PR: #405 / draft #532
- Parent candidate: `cf894d9061a5998786644b730cc4d784be842b79`
- Historical tested base: `16d6ff9146142c16226cecc1f1461e0afc450137`
- Observed current main: `43341865f923d439ace1f22edac81f7fb642355c`
- Read-only source: `db14559b39d32322b06477c6ae676112f583db50`
- Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_hdAPiljWSgqiu5vzPj2KSdRX`

## Bounded defect and repair

The settings service already validates its connection context before and after executing an
operation. The remaining producer window is after `applyRadioPresetVerified` returns and before
the app adapter updates the device. Previously the editor read whichever device was current;
neither that update nor preset-success acceptance carried the initiating session through to the
replacement authority. Same-radio reconnection changes generation even if device identity is equal.

The repair carries the initiating epoch/generation/radio target through the preset port and radio
configurator. Connected-device replacement compares the expected `SessionToken` under the same
runtime monitor as replacement. A rejected replacement does not save the speculative device.
Ordinary unbound device edits and ordinary navigation Back retain their existing APIs and behavior.
The settings service also emits `DeviceUpdated` before returning. Its preset-only app collector
branch now forwards the event's original wrapped token to that same replacement guard; non-preset
events keep their existing consumer.

Preset completion rejects a retired target and exposes the existing localized not-connected error.
A same-target storage retry reuses the successful apply; a replacement target must apply again.
Acknowledged completion writes validate the initiating caller/target before persistence and before
publishing the process first-run gate. Rejection of one caller does not cancel the process writer.
The feature also validates before publishing its completion signal.

A durable preference committed while a caller is suspended is not rolled back or represented as a
failed disk write. If the initiating caller retires before live publication, the live gate stays
closed and the caller receives its rejection. A later successful attempt can retry; a process
restart reads the actual durable preference. These guards do not make persistence transactional
with radio lifecycle changes, and are not the separate navigation owner's atomic entry transition.

The paragraph above describes the reviewed `84519286f` predecessor, not a sufficient durable
completion contract. Independent source review found that its canonical true could survive a
retired completion on reload, along with unordered device persistence, missing path-hash token
guard, CONNECTING eligibility and unpropagated save failures.

## Review-repair successor (execution outstanding)

Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_IGedaZqVFDPAdeSu6ekSJu3s`.
Parent: `8583254273d85d44c3ce2a9fc48a8b0503c59595`. The separately retained receipt draft
is preserved in stash `4c14a23d3b467819286982aa9e768c77887d819c` and is not part of this patch.

- A staged completion writes only an internal pending-attempt marker; it neither overwrites
  prior true nor donates a tip. The ordered process writer seals the originating attempt before
  the final atomic canonical-true/tip/attempt-ID transaction. Pending/Sealed/Rejected CAS is the
  decision authority. Original preset-token/current-connected-state validation and sealing share
  the runtime lock. Reset/newer queued writes reject unsealed older attempts; a sealed finalizer
  completes before the next queued write. No suspending or reentrant callbacks run under the writer
  queue lock. A lost write acknowledgement is reconciled using exact attempt-ID readback; unavailable
  readback reports the original error with its readback error, not fabricated rollback.
- The process close path rejects pending decisions and drains accepted finalizers before cancelling
  the process Job. Activity recreation remains attachment-only, not cancellation. Arbitrary external
  ancestor Job cancellation is not claimed to be ordered by a delayed child handler: that broader
  guarantee remains blocked until its actual cancellation authority is wired/proved.
- Token-bound connected-device and path-hash updates await an error-bearing save. The existing
  Room transaction compares an optimistic device snapshot and process revision, invalidated by
  device writes/activation, before applying the edit; no schema change. It cannot reactivate A after
  B's activation or overwrite an intervening same-radio write, including restored-identical fields.
  Stale storage tickets raise an explicit persistence conflict; they do not return applied success.
- Both original-token settings events are guarded; non-preset device updates keep their existing
  API. Event-storage errors are logged without killing the stream; the direct preset adapter
  receives the error and exposes failure/retry. CONNECTING rejects token-bound mutations even
  while the old token is still published.

Authored additional controls cover real Room versioned compare/write and activation, editor held
save/failure and same-radio ABA, actual adapter save failure/readback retry, non-null preset
pathHashSize forwarding, reconnect-before-teardown, real preference staging/reload and uncertain
commit readback, reset/parallel-attempt order, and pre/post-actual-seal cancellation.
These controls are unexecuted at authoring time. No second CI-result bundle is retained.

The existing AppRoot raw-flag live navigation is still an explicit integration blocker in this
successor. Valid sealed durability does not grant authority over a replacement graph. WP-302 owns
the supplied immutable mounted/first-run witness operations; none is silently composed here.
First-run true-at-startup behavior, retired-witness liveness and immutable receipt delivery require
the separately attributable gate/receipt composition and exact-candidate tests before acceptance.

## Immutable feature receipt handoff (separate successor)

The feature exposes `OnboardingCompletionReceipt.Preset(presetId, originalTarget)`,
`WithoutDevice` (the explicit user decision, not proof that no graph exists) and `Restored`
(startup preference state, not a new producer). `OnboardingState.completionReceipt` retains the
original value only after acknowledged durable completion; it is not reconstructed from a later target.
`completeOnboardingWithReceipt(receipt, requireCurrent, seal)` combines that identity with
the decision-sealing path. Preset completion captures its already-applied preset/target and sealer.

`OnboardingEntry` / `OnboardingFlow` add optional
`onCompletionReceipt: ((OnboardingCompletionReceipt) -> Boolean)?`; legacy callbacks are preserved.
False retains the owned completed flow for the host to retain/report its original witness.
True acknowledges delivery and retires only that owned flow. Restored/missing preset identity
must never be mapped to an app `NoDevice` producer. Feature controls cover original identity after
replacement, receipt unavailability before durable acknowledgement, reset and explicit no-device versus
restored state. The mounted JVM/Robolectric control rejects delivery, recreates the Activity,
accepts the same original object and checks one retirement without rebinding to the later target.
These controls require the successor's actual hosted result before execution credit.

Read-only dependency comparison: first-run API `07e45af4f317b157c347da4aa954d24b85af2d83`
depends on offline fix `0e8e1c0bfe692e430989c26b18100ddc2ff9ba65`, which follows the
`611947f31` / `e0e7ffb7b` / `e4650ae4a` / `6893ef0ac` mounted-authority chain.
That chain relies on the unlanded WP-302 baseline at `4d9d94c1f` (presented snapshots/state store,
entry epochs, manager lifecycle stamp, registry/graph commit boundary and container wiring),
itself beyond published baseline `9733776a9`. The retained WP-305 base lacks these authorities.
Importing isolated method text would omit their atomic boundary, while a wholesale baseline merge
imports the held WP-302 branch. Neither is performed. The controller explicitly retained the
WP-302 source/reservation/publication and external-link holds. This is a concrete dependency blocker,
not authority to replace the first-run gate with another raw flag effect. Gate/witness composition
remains separate from this standalone receipt API and from device-tip display.

The receipt entry also exposes independent optional `completionDeliveryKey` and `attemptKey`
Compose keys. The first explicitly retries delivery of the *same* owned flow/receipt after an
occupied error slot clears; the second reacquires the owner's current retained coordinator only
after the host has settled/retired the exact old flow using its original authority. Neither key
constructs a producer or grants navigation. A same-mounted control holds acceptance, changes only
the delivery key, verifies the identical receipt, then changes the attempt key after acceptance and
checks the newly owned incomplete coordinator without duplicate delivery. A key increment alone
must not be treated as retirement or fresh authority.

The WP-302 owner additionally reported two source-only composition risks: recapturing a Compose-only
witness after recreation can rebind a retained WithoutDevice decision, and an automatic offline
rebuild's exposed graph may outlive its cancelled/superseded cycle before cleanup. Those local
unexecuted APIs are not treated as verified prerequisites. The bridge must retain the witness with
the process-retained exact flow (not only in `remember`) and require real rebuild cancellation/
cycle controls once that owner's source hold permits repair.

The feature state now owns the exact pending completion attempt and passes it into the durable
writer. `retireCompletion`, reset, manual flag replacement and a newer completion synchronously
reject that attempt before returning; the writer's token seal races that same attempt CAS.
Post-finalization live publication and reset acknowledgements are fenced against feature-state
replacement. Exact-owner rerun settlement calls retirement before discarding its coordinator.
Controls retire directly inside the pre-seal callback, and hold a sealed finalizer while the same
state resets, asserting true then false storage order without stale feature publication. Equal
WithoutDevice singleton receipts on distinct flows also exercise old-flow settlement without
retiring the replacement. These are authored controls until exact-head hosted execution is observed.

This does not turn Activity disposal into an abort. The held navigation bridge must call explicit
retirement for actual owned-flow retirement; no held shell source has been changed. Arbitrary external
ancestor `Job.cancel` ordering, including a delayed child's notification, remains unestablished and
blocked: an ordinary cancellation callback is not substituted for synchronous attempt revocation.

Source review of 94f found its validation callback could overwrite the visible receipt while a
previous completed flow awaited delivery. The successor separates the pending argument from a
unique immutable acknowledged publication. Compose captures that exact publication, rather than
rereading pending metadata when a delivery key changes. Completed preset controls remain disabled;
the feature rejects another completion until an explicit reset or fresh owned flow. The real writer
second-stage trap checks that an attempted second apply cannot replace or deliver an unsealed receipt,
and staging failure publishes no receipt before a successful retry.

`retireCompletion()` is terminal for that flow state, not just rejection of an already-created
attempt. The owner's fresh flow constructs a fresh state. An old suspended radio apply checks the
flow admission again before starting completion, and cannot mint an attempt after owner settlement.
This closes the source-only pre-attempt retirement hole; it is not navigation composition evidence.

The app's first-run producer owner, like its rerun owner, now checks exact coordinator identity
when settling/retiring. It can reacquire a fresh coordinator after a rejected pre-seal attempt
without reviving that terminal old state. A held real writer control retires A, creates B while
the durable flag is still false, rejects A without a canonical true, and completes B. Delayed A
settlement cannot retire B. This is producer custody/recovery only; the held AppRoot gate is not
replaced, no witness is minted here, and a durable true is never reset implicitly.

Acknowledged publications carry the state revision as well as object identity. Reset invalidates
delivery authority synchronously before its false-write suspends. A failed reset does not silently
reauthorize the old receipt or claim rollback; an explicit successful reset retry permits recovery.
A mounted real false-write control holds then fails reset, retries delivery in both intervals
without accepting the old receipt, and explicitly retries reset and completion.

`AppOnboarding.wasCompletedAtLoad` retains its construction-time process-owner flag decision, rather
than sampling a current flag after Activity recreation. A producer-only control holds acknowledgement
after the actual writer has published raw true, recreates the Activity with the same retained flow,
and observes no receipt until acknowledgement, followed by one original delivery. A separate
true-at-load shell/no-navigation control remains. This supplies a producer seam, not a replacement
for the held first-run gate: original witness custody and the sealed/stale-witness navigation
controls still require the admitted WP-302 composition. Distinct setup entries in separate tab
stacks must not adopt a shared old flow's receipt with a newly captured witness.

## Sealed-winner and explicit-reset producer repair

Parent: `7df38aebc5af58320af543156c5bf6bb49b1d19b` (local successors of published
`fc7be50b92c275d495932df6e978e95c553ea684`). These controls are authored, not Kotlin
execution evidence.

The real process-owned writer now reports the final result to the exact pending attempt before
publishing its raw flag or resuming the initiating child. A successfully sealed write can therefore
publish its original acknowledged receipt after child cancellation; cancellation does not clear
the sealed winner's pending identity. Reset/terminal retirement still revoke that exact publication.
Failed finalization releases admission without a receipt. A second completion cannot replace any
pending attempt, including a sealed winner. No-device completion also refuses an applying preset.
This is not a proof of arbitrary ancestor cancellation ordering before the seal.

Explicit reset advances a separate work generation before its false-write suspends. Preset radio
work captures that generation before applying, and completion passes one immutable captured
receipt/guard/sealer bundle rather than rereading mutable applied metadata. Old apply results and
old completion-job handlers cannot finish or clear a newer generation. Reset clears retained preset
completion, selection, retry count/cache and errors. New completion remains blocked until the false
write is acknowledged; failure requires an explicit reset retry. Lifecycle observers terminate when
their exact flow retires instead of accumulating under the process scope.

Clearing stale pairings rejects completed/retired flows, retains the actual clearing Job before
starting it, cancels it on explicit reset/retirement, and rejects late sheet dismissal. No-device
completion cannot overtake an in-flight clear. Callback controls hold the actual dependency call,
including retirement inside that call, and check cancellation plus a successful fresh clear.
This does not undo a CDM disassociation already accepted before retirement. The platform adapter's
`CompanionSetupService.removeAccessory` invokes disassociation before suspended record cleanup;
no atomic rollback or hardware cancellation guarantee is claimed by this feature-level repair.

Additional real-writer controls cover child cancellation after seal, rejection of competing completion,
failed finalization/recovery, a pre-reset radio apply returning while a new apply is held, and reset of
an already-completed retained preset followed by a genuine new apply. The held-ack recreation fixture
now intercepts the result-bearing store overload explicitly (Kotlin delegation would otherwise bypass
its older overload override). It deliberately holds delivery after raw publication; the production
writer now publishes the originating receipt before its raw flag. The process-load snapshot and
original-witness requirements still apply; AppRoot is unchanged.

The first-run error-presenter dependency remains concrete: onboarding replaces the shell, whose
snackbar is currently the sole navigation-failure consumer. Retrying an occupied failure indefinitely
cannot clear it while that shell is absent. The held navigation owner must supply an owner-safe
presenter/dismissal and deferred-versus-superseded disposition, retaining the original receipt/witness.
A mounted actual-dismissal control is still required. No held #495 source, reservations or publication
have been imported or changed.

Trusted path/operation admissions passed for all changed feature paths (`modify` / `wp-owned`),
the app flag writer and additive contracts getter (`modify-related-consumer` /
`reviewed-support-consumer`), and related app controls (`add-or-update-related-test` /
`reviewed-support-test`). The historical advisory lease mismatch and local Gradle execution hold
remain unchanged. Source validator passed (64 WPs / 1,866 references); documented `portmap.py`
and `test_inventory.py --check` exited zero (5,133 pinned case declarations, not executed tests).
An initial `portmap.py --check` invocation was rejected as an unsupported option; the documented
command above was then run. `git diff --check` passed. No local Gradle or manual hosted dispatch ran.
Published fc7 run `41935019276`, Linux job `126941702081`, subsequently finished **failure**.
Its actual log records two `PresetDeviceEventSessionTest` failures
(`presetWithPathHashCarriesItsOriginalTokenToBothMutations`,
`retiredPresetPathHashCannotUseReplacementGeneration`, both `SettingsServiceException` at
`SettingsService.kt:374`), plus `OnboardingFlagsTest`'s unavailable-readback assertion
(expected suppressed `readback unavailable`, received an empty list). The Settings owner received
the exact failures to reconcile on its bounded surface. Feature onboarding records 87 passed,
zero failed/error/skipped; that predecessor evidence does not verify these successors.

The readback repair retains both actual exception objects in a typed
`OnboardingCompletionOutcomeUnknownException`, preserving the write error as cause and readback
error as suppressed and typed data across coroutine exception transport. The original IOException,
message and suppressed-error assertions remain; additional assertions require both original object
identities and the cause. Cancellation remains cancellation rather than being wrapped as an IO error.
This implementation has not yet been executed. Separate device-writer/Settings handoffs are not
part of this source delta.

A follow-up source check orders publication-revision invalidation before lifecycle notification,
and ownership also rejects a reset awaiting acknowledgement. A synchronous lifecycle observer
control checks that reset never exposes the old receipt during notification, then completes a
fresh explicit attempt. This authored control is not execution evidence.

Further source review found that reset/retirement observers cannot be scheduled StateFlow
consumers: with a queued dispatcher, reset could return while the old preset remained completed,
or a delayed observer could erase a new apply. Flow-owned invalidation callbacks now clear retained
state and cancel the actual clear Job synchronously as part of explicit invalidation. The observable
generation remains for captured-producer guards; terminal retirement releases its callbacks.
Queued-dispatcher controls require immediate reset state, actual Job cancellation before dispatcher
resumption, fresh-apply preservation, and rejection of an old apply that had not started yet.
These authored controls have not executed in Kotlin.

## Corrected handoff composition

The separate owners supplied immutable combined Settings/core head
`0e38149e1af61663954caf3c3bd072fe50932c41` from exact fc7. After source review,
its six ordered commits were composed without conflicts onto producer head
`5e9e3deb38432df1e850922c7be6828066f99f88`:

| Original handoff | Local composition | Scope |
|---|---|---|
| `a785591c3b534fa0f0e36cf7593541d43d9ee8c9` | `7d493985e` | Await normal Settings path-hash durable event acknowledgement. |
| `43be5a6c2` | `39127cf62` | Retry/restore after persistence failure; real-store wrapper delegation. |
| `85ad11aad` (original `c8698b515`) | `1aa834a38` | Authoritative intended-field rebase and serialized device edits. |
| `2634c430e` (original `d3568bb18`) | `1cf9a5cfe` | Non-replacing initialization and guarded activation. |
| `6a1ffe99b13af2a1929b88552d5decf8e5316af4` | `816ed2aed` | Correct actual DEVICE_INFO fixture byte offset without changing token assertions. |
| `0e38149e1af61663954caf3c3bd072fe50932c41` (original `b25179d8e`) | `16f6d9aa0` | Correct two outer JUnit test placements and preserve snapshot-fetch failure semantics. |

The final correction addresses the integration review's concrete nested-test finding and the
core owner's actual d356 hosted fetch-failure regression. Production editor writes now transform
the fresh authoritative Room snapshot, await persistence before live publication, preserve newer
live fields and serialize ordinary writes with token-bound edits. Targeted concurrent watermark/OCV
conflicts retry without overwriting unrelated fields; full replacement/activation invalidates their
authority. Normal Settings waits for the AppState event consumer's persistence result and surfaces
unrecoverable failure/retry through its actual action. Detailed owner evidence remains in
`device-writer-authority.md` and `docs/android/deviations/WP-317.md`.

Trusted WP-305 admission passed for every imported production path
(`modify-related-consumer`), related test (`add-or-update-related-test`), owned evidence
(`modify`) and the imported WP-317 deviation record (`record-validation-evidence`). Composition
preserved the exact owner bytes on these paths. Source validator, documented portmap, test inventory
and diff checks passed on the composition. Owner-hosted results do not test this complete candidate;
publication requests only its ordinary automatic candidate workflow, with no local Gradle or manual
dispatch. No held #495 source is present in this composition. Complete-head Kotlin execution and
independent review remain outstanding at authoring.

Published complete candidate: `0ac28eebeee8a7ef2cde353e1e5183871b0d0cfe`.
Its automatic run is `41939227027`, Linux job `126955530142`; in progress at the
last observation, not acceptance. PR #532 retains the actual fc7 failures.

That exact run subsequently finished **failure**: `:core:services:test` discovered
2,807 tests with one failure,
`SettingsPathHashPersistenceTest.persistenceFailureReachesTheCallerAndTheSameModeCanBeRetried`
(`AssertionFailedError`, method entry line 53). Its named service records show the
other five controls passing. The original failure-object identity assertion remains in
place and was routed to the Settings owner; no app/onboarding or complete-candidate
acceptance is inferred from this early failure. Classifier/controller passed; the Swift
oracle was skipped. The exact hosted job/log is authoritative; no second CI-result
bundle is retained, hashed, uploaded or revalidated.

The core owner subsequently supplied reviewed
`7cff2cca21bfa906687980bdf653ef0727376352` (parent `b25179d8e`), composed locally
as `40e428e2af2df9d3be9f05614a22b0e931a1ea22`. The existing RX regression now
holds the actual atomic transform/publication boundary used by the repaired editor,
asserts that Germany was durably added, then commits a real newer removal before
reconnecting. The delayed old-generation notification must not restore the newer
Room/live/cache state. This preserves stale-callback rejection rather than expecting
the superseded optimistic-publication ordering. Trusted related-test admission passed.
This local successor is not covered by the published 0ac run and is not execution evidence.

The Settings owner supplied isolated
`c32e6ec9c5963529708173fda23362468a7124b0` (parent `0e38149e1`), composed without
conflict as `621f28ab420e9f232e3250af1991b1cd7dbe90d7`. Subscription replacement
detaches pending acknowledgements and installs the new owner under the event lock,
then closes channels/completes failures outside it. A real unconfined failed caller
reenters subscription creation; the control requires the superseded outer replacement
to close and the newest subscriber to acknowledge a verified retry. Cancellation while
waiting after radio verification cancels the acknowledgement, does not invent a rollback
packet, and permits an explicit verified restoration. The holder preserves unresolved
recovery instead of treating cancellation as radio rollback. All five paths were
trusted-admitted using the same related-consumer/test and validation-evidence operations.
The owner's new controls are authored, not executed; published 0ac does not include them.
The current 0ac hosted run is preserved rather than being superseded before its result.
The local composition remains provisional pending the Settings owner's bounded disposition
of buffered old-event delivery during same-token AppState collector replacement. `Channel.close`
retains buffered events, and replacement starts the new collector before cancelling the old one.
An actual consumer/Room ordering control must distinguish a stale overwrite from protection
already provided by editor serialization/cancellation. This is a source-review concern, not an
established defect or executed regression. Acceptance remains provisional pending that disposition.
The same hold includes fresh Settings-holder reentry: the composition-owned holder/scope can be
discarded after radio verification but before durable acknowledgement. A replacement holder reads
Room's old mode without the old holder's unresolved-recovery marker, so selecting that displayed
mode may be ignored despite a different radio mode. The supplied controls retain one holder or
retry the service directly; they do not establish actual fresh mounted-holder recovery. The
Settings owner has this source-derived concern for disposition and real radio/Room/reentry controls.

The device-writer owner also has additional conditional authority/delegation/region findings
for disposition. Both imported owner deltas remain provisional; this evidence does not
accept those surfaces or substitute predecessor results for the complete successor.

## Follow-up completion and lifecycle review

The independent fc7-to-5e9 review supplied five conditional findings. Production actions
inspected by that review run through Compose on retained `Dispatchers.Main.immediate`;
no off-main admission/retirement or production reset caller was established. The following
repairs preserve the public API contracts without describing these schedules as observed
user failures:

- Unknown finalization/readback outcomes retain the exact sealed decision and block a second
  completion, including the synchronous setter. A failed explicit reset remains fenced; only
  an acknowledged false write reopens admission. A real writer control first commits true,
  loses its acknowledgement/readback, rejects a competing decision and a failed reset, then
  proves successful reset and fresh completion. There is no production reset UI or new
  authoritative reconciliation action in this change; an unresolved retired owner remains
  fail-closed rather than inventing rollback. A finalizer cancellation with unavailable readback
  retains the same unknown authority through a typed `CancellationException` subtype and keeps
  both original exception objects; the process writer still stops rather than swallowing cancellation.
- Explicit first-run retirement after sealing retains an unavailable, non-owned retired flow
  until the original writer settles, rather than constructing a fresh active false-snapshot
  owner. The writer now updates its acknowledged process snapshot before calling the exact
  result callback, outside its queue lock. The real held-finalizer control reacquires during
  that callback and requires the replacement to observe durable true, while the retired
  original has no receipt. `wasCompletedAtLoad` stays false. This supersedes the earlier
  callback-before-raw ordering above, not the held gate's original-witness requirements.
- Admission, generation capture, lazy-job registration and invalidation now share the flow's
  lifecycle lock for preset, no-device, clearing and connection work. No suspending operation
  is invoked directly in that critical section. Actual preset-target interposition with a
  concurrent reset and queued dispatcher checks that reset cancels registered old work,
  does not leave `isApplying` stuck, and allows a genuine fresh apply. A queued no-device
  control likewise proves no old completion and a successful new attempt.
- `CompanionPairingService` checks actual coroutine cancellation before each accessory
  removal. Its actual loop/`CompanionSetupService` control holds the gateway after A was
  disassociated, cancels, lets A finish, rejects B, then clears B in a fresh call. Already-issued
  A cannot be undone. This closes the held-A/next-iteration schedule, not a cross-thread
  cancellation between the per-item check and the platform side effect. No such production
  caller was found; atomic cross-thread revocation of that side effect remains unproved.
  The coordinator checked known custody; trusted support/test admission passed before edits.
- Wi-Fi, pairing/retry and demo jobs retain their initiating generation; invalidation cancels
  the registered job, and late result publication is checked under the same lifecycle lock.
  Controls let non-cancellable old Wi-Fi and pairing operations return after reset while a
  new connection is held: the old result cannot append REGION or clear the new busy state;
  the new result advances exactly once.

These are authored controls, not Kotlin execution. Source validation, inventory and diff
checks passed during authoring; the shared local Gradle hold is unchanged. No #495 navigation,
AppRoot, witness or failure-presenter source/reservation/publication was imported or edited.

## Fresh-holder recovery handoff

The Settings owner supplied isolated immutable
`2dfa078fadd21bb4533bcb129f96d10b455fed4a` (parent `98d321ce6`, which already
contains the separately imported 7cff). Only 2dfa was imported, without conflicts,
as `cdb7e4fd58ba2db2ba2a69f76fb4eebafbca05e6` after local onboarding repair
`891ddf9b92bf363b1f692ed974d21119b844314d`. All eight paths were trusted-admitted
as related consumers/tests or validation evidence, and their bytes match the isolated
owner handoff. No duplicate RX commit or held navigation source was imported.

Source review read the connection-owned attempt/ack revisions, feature adapter/recovery
guard, exact original-error cause-chain assertion, real AppState/Room ordering controls
and actual mounted API-31 Settings disposal/recreation control. New holders consult the
live session's unresolved revision before treating a same-mode selection as a no-op;
older acknowledgements cannot clear newer uncertainty. Retired subscription buffers
skip acknowledged events. Controls retain clean no-op behavior, reject an old queued
event, serialize a non-cancellable already-entered Room commit before the newer ack, and
explicitly restore radio mode zero through a fresh actual Settings composition after
the held real Room failure. The formerly failing assertion retains error type/message
and the original object at the recovered cause-chain root instead of expecting coroutine
transport to preserve the recovered wrapper's reference identity.

The owner reports exact 98d run `41940594065` failed core-services (2,809 tests / one
same identity assertion); owner run `41942349039` targets 2dfa. Neither is execution of
this complete successor. These controls address the bounded source concerns but still
need exact-head execution and independent acceptance. The device-writer owner's additional
findings remain unresolved; publication of an explicitly unverified draft candidate does
not accept those surfaces or the held first-run bridge. The published 0ac failure remains
the authoritative predecessor result, not a result to supersede or relabel as green.

## Authored controls

- `PresetSessionFencingTest`: disconnected apply and retry; replacement radio and same-radio new
  generation; stale failure; target change during a held durable write; same-target persistence-only
  retry versus replacement reapply; cancellation; unchanged-target success and demo outcome.
- `SessionBoundDeviceEditTest`: current-token replacement and persistence; disconnect, same-radio
  generation change and replacement radio between the editor read and replacement callback.
- `OnboardingRegionPresetAdapterTest`: initiating-target propagation and stale-result rejection;
  stale target rejected before demo or radio apply.
- `OnboardingFlagsTest`: retirement during persistence cannot open either live completion flow;
  cancelled completion cannot publish late or stop the writer, and a later attempt succeeds.
- `RuntimeSeamTest`: current-token acceptance; rejection after disconnect or same-radio rebuild,
  followed by acceptance of the successor token.
- `PresetDeviceEventSessionTest`: real settings service and AppState collector forward the original
  token to a recording connection authority; current-session acceptance, replacement-generation
  rejection, disconnect rejection and unchanged non-preset event behavior. This is a Room/Robolectric
  fixture, not device execution; the real runtime authority is covered separately above.

These are authored JVM/Robolectric controls, not execution or physical-device evidence.

## Admission, custody and verification

Trusted `reserve.py admit` accepted `modify-related-consumer` for the directly necessary app
onboarding adapters, flag writer, connected-device editor and runtime replacement authority.
Feature paths use `modify` / `wp-owned`; related cross-owner tests use
`add-or-update-related-test` / `reviewed-support-test`. Support admission preserves existing
contracts/cases and requires exact-candidate review and actual hosted test discovery before merge.

Own-session advisory bookkeeping was attempted without modifying historical authorization:
current-main binding was refused because it is not an ancestor of the retained head; the actual
tested ancestor was refused because the historical WP-305 lease has a different binding and
belongs to another session/worktree. That record was not evolved, cleared or impersonated.
The controller explicitly permits assigned/admitted isolated authoring despite this advisory
mismatch. The controller identified the #402 preview/timeline pause as unrelated and inspected the
actual retained WP-207 draft: it does not touch `replaceConnectedDevice`, permitting the additive
overload without modifying that draft. WP-302 inspected its actual #495 and uncommitted changes
and confirmed the narrow preset-only `wireSettingsEventStream` hunk does not overlap its navigation
work. These are hunk-specific dispositions, not reservation transfers or clearance of other holds.

Source-only commands executed:

| Command | Result |
|---|---|
| `python .\tools\android-port\controller\validate.py` | Passed: valid, 64 WPs / 1,866 tracked reference files. |
| `python .\tools\android-port\portmap.py` | Completed with exit 0; attribution inventory, not feature acceptance or test execution. |
| `git diff --check` | Passed for the authored source delta. |

No local Gradle or runtime recovery was attempted; the retained `gradle-execution` hold remains.
Historical successful hosted run `41708894306` covers only parent `cf894d9` on base `16d6ff9`,
not this repair. Exact-head hosted execution and independent review remain outstanding.
The separate WP-302 atomic navigation handoff, device-menu tip display seam, WP-503 disposition,
physical API31/37 behavior and full-WP acceptance are not established by this patch.

## Exact 82cba9 result and same-attempt recovery follow-up

Repository: `cbattlegear/MeshCoreOne-Android`; base
`16d6ff9146142c16226cecc1f1461e0afc450137`; published predecessor
`82cba9bf948376529a319b1a8a8a0bf25cf88644`. Automatic run
`41942692910`, Linux job `126967249464`, finished **FAILURE**, with
**1,933 app tests / 19 failures**. Classifier/controller passed; Swift oracles
were skipped. This supersedes the earlier in-progress status, not the preserved
0ac, fc7 or 98d failures. The authoritative hosted job/log is retained by GitHub;
no second CI-result bundle was committed, uploaded, hashed or revalidated.

Named log observations on that exact head include OnboardingFlagsTest 24 passed,
OnboardingGateTest 19 passed, OnboardingFlowCoordinatorTest 21 passed,
PresetDeviceEventSessionTest 6 passed, CompanionSetupServiceTest 20 passed and
core SettingsPathHashPersistenceTest 8 passed. Those observations include the
typed unavailable-readback repair; they do not execute this section's successor
or establish whole-WP, gate/witness or original-case acceptance.

The app's seven path-hash persistence controls and fresh mounted Settings
recreation control failed before their intended scenarios with
`CancellationException: Superseded connection generation` in connection
initialization/time synchronization. Other failures included the RX old-write
control's missing versioned-persistence wrapper, two actual Room region cases
expecting a nonempty known-region list but observing an empty list, the held
activation/retirement control's timeout, five channel-recovery controls, one
saved-device recovery control and one mounted partial-import control. Actual
failures/causes were routed to the existing Settings/device-writer owners.
Core success is not mounted Settings/Room execution credit. No unreviewed
runtime/storage successor was imported to mask these failures.

The separate immutable 169-to-891 source review supplied six follow-up concerns
and a weak-barrier test gap. The source/test changes in the commit containing
this section have these bounded dispositions:

- The real production liveness gap is repaired through the existing visible
  completion retry. An unresolved sealed decision retains its original attempt,
  receipt and revision. Retry submits readback of that exact ID to the existing
  process writer, not a fresh stage, finalization, radio apply or decision.
  Unavailable readback keeps authority fenced and retry available. Authoritative
  true publishes the original receipt; authoritative false releases admission
  but requires a subsequent explicit retry before any replacement write.
- The process queue owns an admitted read through initiating-child cancellation.
  A read already in progress may settle canonical knowledge after reset/retirement,
  but revision/owner checks prevent delivery of the revoked receipt. A queued
  reset follows it and must acknowledge false before reopening completion.
  A new real MeshCoreStorage/ShowOnceTips control covers actual committed data,
  lost acknowledgement, unavailable initial readback, held original-ID read and
  child cancellation/reset/retirement. It asserts one stage/finalization, no stale
  receipt, original receipt identity after child cancellation, and actual storage
  false after reset.
- Concurrent public resets serialize their complete durable false operation
  using a mutex and validate revision before submission and after acknowledgement.
  A real writer-boundary control holds the older reset before submission, starts
  the newer reset, proves it cannot submit or admit true early, then requires
  false/false/true ordering and a durable true final snapshot. No production reset
  caller was established; this repairs the API contract rather than claiming an
  observed user failure.
- Preset retry captures alert/applied identity and generation under the lifecycle
  lock, and revalidates that generation at admission. Preset results also check
  generation after potentially reentrant admission/target callbacks. The control
  resets/replaces the target from an admission callback: old retry cannot apply
  to the successor; a genuinely fresh apply still completes.
- Wi-Fi and pairing results validate their initiating generation after the
  potentially reentrant admission callback, before advancing. A control resets
  from that callback and rejects old success for each port, then accepts a fresh
  connection. No production callback-triggered reset was found.
- Still-mounted first-run content observes process-owned availability revision.
  When an explicitly retired sealed owner settles known-uncommitted, it is
  replaced without Activity recreation or a second setContent. The mounted
  control clicks the actual welcome action and requires the fresh owner's path,
  not the retired owner's path, to advance. Successful completion does not
  increment that revision/remount into a restored-receipt loop. Reruns do not
  inherit the first-run revision. Activity recreation alone still does not retire
  the process owner.
- The concurrent admission/reset control now waits for actual JVM monitor
  contention owned by the applying thread, using ThreadMXBean, before releasing
  target capture. A signal merely announcing that reset was scheduled is no
  longer its race barrier.

New mounted no-device and preset controls click the existing localized Try Again
action. The preset control replaces the radio and disables new radio application
before retry, and requires delivery of the original target receipt with one
radio apply and one finalization. These are screen/writer controls, not acceptance
of the held AppRoot/navigation bridge. All eight new test methods and strengthened
barrier are **authored, not executed** at publication of this follow-up.

Pinned Python 3.12.4 and trusted capability admissions were checked. Declared
source-only commands passed: `python .\tools\android-port\controller\validate.py`
(64 WPs / 1,866 references), `python .\tools\android-port\portmap.py` (exit 0,
attribution only), `python .\tools\android-port\test_inventory.py --check`
(468 pinned paths / 5,133 declarations, not Android execution), and
`git diff --check`. Exact-successor hosted discovery/review remains required.
Shared local Gradle ownership was not cleared, bypassed or contended for.
The #495 navigation/source/reservation/publication hold, original entry/activation
witness, first-run presenter/dismissal, unknown retired-owner recovery, physical
API31/37/radio evidence and native tip UX remain unresolved as applicable.

### Exact 3f22 compilation result

Automatic run `41946087662`, Linux job `126978620790`, on exact
`3f22b5eaad4165640e8576df8b77548d53a07eea` failed
`:app:compileDebugUnitTestKotlin`. The new reset fixture omitted its
`OnboardingFlagStore` import (line 343, with a consequent adapter type error at
352) and used kotlin.test argument ordering with JUnit's `assertEquals` (358).
The isolated correction adds the import and puts the diagnostic message first;
it changes no assertion or production behavior. App tests did not execute on
3f22, so the new app/mounted controls remain unverified. Classifier/controller
passed; Swift oracles were skipped. The corrected successor requires its own
automatic exact-head result; this failure is not relabeled as test success.

## Snapshot-bound 82-to-3f22/a5 review dispositions

The independent immutable review supplied five source-only concerns. On actual
parent `a5d7af3da905ccf62c41c7cefe7cce279a481240`, the following narrowly
related repairs and controls were authored while its run `41947420835` was
preserved. None of these controls has executed at authoring time:

- The dismissed preset-error recovery gap is production-reachable without a
  reset: unknown finalization/readback plus radio disconnection must not make
  the original-ID readback action disappear. Preset content now uses the
  coordinator's admission policy, and the primary button exposes localized
  Try Again for same-attempt reconciliation independently of selected preset
  and radio availability. Ordinary radio apply retains its selected-ID/radio
  requirements. A new actual mounted control applies without selecting a row,
  loses acknowledgement/readback, disconnects, dismisses the actual Android
  error dialog through Back, proves ordinary flow Back is fenced, then clicks
  the enabled primary recovery button. It requires the original target receipt,
  original ID read twice, and exactly one radio apply/finalization.
- A readback-only queue record no longer rejects or registers among pending
  completion writes. Worker validation still rejects stale queued reads; reads
  cannot reject a newer unsealed decision merely by being enqueued. A concurrent
  control holds the original read after validation, resets, stages a newer
  completion, then proves the old read is actually submitted before releasing
  the fresh stage. The newer real writer seals/finishes; the stale read fails.
  This is public API/concurrent-reset safety, not a found production reset path.
- An admitted reset now counts as unsettled owner work from invalidation through
  the complete acknowledged false operation. This includes pre-submission
  suspension. The narrow non-cancellable settlement boundary retains custody
  through initiating-child cancellation; it does not invent rollback or let a
  fresh owner finish true ahead of a still-capable old false writer. Retirement
  blocks owner replacement until actual storage settlement; terminal/revision
  checks still reject the old owner's result. A control pauses a delegating
  reset before actual queue submission, retires/cancels its owner, requires
  reacquisition to remain the unavailable exact old owner, releases false, then
  completes the fresh owner and requires actual writer false/true order and
  durable true. No Activity-recreation early-retirement claim is made.
- The flow's Wi-Fi completion callback captures generation before dismissing the
  sheet and revalidates after dismissal before appending REGION. A deterministic
  control uses the real flow with a queued producer and an unconfined sheet
  collector, so synchronous sheet-dismissal reset occurs within that callback.
  Old success cannot append; a fresh connection appends once. No production
  reset observer was established.
- The existing mounted retired-owner control no longer calls the side-effecting
  retained-owner accessor before its observed action. It first clicks the still
  mounted welcome control, then reads ownership and requires only the fresh
  owner's path to advance. No setContent, Activity recreation or accessor-induced
  availability signal can manufacture the tested automatic refresh.

These are four new methods and one strengthened existing mounted control.
Original assertions/receipt identity and held entry/activation witness semantics
remain intact. Trusted path/operation admissions and the documented validator,
portmap, pinned inventory and diff checks passed; these source-only results do
not compile or execute Kotlin. No runtime/storage owner delta, #495 source,
reservation or publication was imported; no local Gradle or manual CI rerun.

## Exact a5 result and residual reset/readiness controls

Exact published `a5d7af3da905ccf62c41c7cefe7cce279a481240`, automatic
`41947420835` / Linux `126983053481`, completed **FAILURE**:
**1,939 app tests / 20 failures**. App test compilation completed; this does
not retroactively turn 3f22's compilation failure into a pass. Classifier and
controller passed; Swift oracles skipped. OnboardingGateTest has an actual
suite END/SUCCESS record on a5, not execution of local 431 or this successor.
The existing 19 owner-surface failures from 82 remain; no corrected runtime,
storage or Settings owner handoff was imported.

The additional owned failure is
`OnboardingFlagsTest.real committed readback survives child cancellation but reset and retirement revoke its receipt`.
The authoritative full failure stack locates `assertFalse(hasUnsettledCompletion)`
at exact a5 line 321 (method-entry summary 271). After initiating-child
cancellation, `runCurrent()` does not establish that actual Room readback has
finished on its I/O executor. The successor adds a bounded wait on the process
owner's observable actual settlement before retaining that assertion and all
receipt/storage/count assertions. This is a proposed test-order correction,
not an executed diagnosis or a claim that the underlying production control
has passed. All existing cases and new control semantics remain required.

Independent source review of a5-to-local
`4319a193a4b48a4fa64a4a77918d8a68a5dede9c` closed the earlier dismissed-retry,
stale-read enqueue ordering, Wi-Fi-reset callback and self-triggered mounted
test schedules, but supplied five residual source-only findings. The commit
containing this section is the isolated 431 successor:

- Revision authority is reserved before synchronous lifecycle/unsettled
  publication, and completion admission, synchronous setter and reconciliation
  all fence every admitted reset until its actual settlement. A reentrant
  nested-reset control runs R2 from R1's actual unsettled observer, attempts
  completion after R2 settles while R1 remains admitted, requires rejection and
  only the nested false write, then permits a fresh explicit true after R1
  settles. It fails if the old R1 can adopt new authority or overwrite fresh true.
- An acknowledged reset settles only the exact attempt captured at its
  admission, even when retirement subsequently rejects the old owner's result.
  The new real-writer control first produces a sealed unknown write/readback,
  holds false before submission, retires, acknowledges false and requires the
  old unsettled flag to clear. It then completes a fresh owner with false/true
  writer ordering and no retired receipt. A reset failure does not clear
  unknown sealed authority.
- Reconciliation readiness is an observable StateFlow, updated when uncertainty,
  active readback or reset/retirement authority changes, and collected by actual
  preset content. A mounted control captures/cancels the actual sealed
  completion child, allows Compose to observe not-applying with recovery still
  unavailable, then lets the process writer report unknown. Without unrelated
  UI mutation, localized primary Try Again must become enabled and reconcile
  the original ID/target with one radio apply and one finalization.
- The Wi-Fi callback rechecks the full connection-admission condition after
  dismissal, including no-device completion busy state. A queued flow producer
  plus unconfined dismissal observer synchronously admits no-device completion
  before its attempt job runs. Old Wi-Fi success must not append REGION; the
  queued no-device job completes its original receipt.
- The held stale-read test now requires exact IllegalStateException rejection
  text `Onboarding reconciliation was retired` and exactly one storage readback
  invocation (the initial uncertainty). A worker that wrongly reaches the
  always-throwing fake for the stale read cannot satisfy either condition.

These are four new methods and strengthened existing assertions, not execution.
Independent child cancellation and reentrant public reset/no-device observers
are conditional API schedules, not demonstrated Activity recreation or observed
product callers. Original receipts, no-reapply behavior and gate/witness holds
are unchanged. Pinned Python 3.12.4, trusted path/operation admissions,
`validate.py` (64 WPs / 1,866 references), `portmap.py` (exit 0),
`test_inventory.py --check` (468 pinned paths / 5,133 declarations) and
`git diff --check` passed during authoring. Exact-successor hosted discovery
and independent review remain required. The exact a5 failure remains recorded;
no second CI-result bundle, local Gradle, manual dispatch or #495 changes.
