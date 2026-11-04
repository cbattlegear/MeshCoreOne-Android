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
