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
