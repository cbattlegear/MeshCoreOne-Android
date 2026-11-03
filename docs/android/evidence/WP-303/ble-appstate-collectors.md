# Issue #383: AppState collectors over the native BLE graph

<!-- android-port-wp:WP-303 -->

Status: **source-reviewed; hosted execution passed for the unchanged test
blob on the immediately preceding head**. This evidence-only update requires
fresh exact-head CI before acceptance. This adds software controls to WP-303,
not WP-205 completion or a new receipt for the historical #529 candidate.

## Binding and custody

- Trusted main for the reviewed source: `99479711bfdb7c621765f09d671068ca8b8318ce`.
- Reconciled current main: `024dfd11e95a59f33ef4e7abe1865e36673e68f3`.
- Current-main reconciliation merge: `4ceb50dfd0048f3fe0a8208d71d0e8ab62771d31`,
  with `cd8a315e` as its second parent. It preserves the exact reviewed test
  blob. Later controller-only main commit `024dfd11` was merged as
  `55778f1dc06b9e2e32d7b0211c7a87faf15c0e6b`; the final evidence refresh is
  a descendant of that merge. Evidence-only successor
  `910752cc8b52d5b8df3dcf7bc32e873ba7eb8500` was tested against base
  `024dfd11`; this document update must receive a fresh run on its new head.
- Retained owner parent: `ba7a02e5927b2cad22f9c92cbe808dbccf26a8ce`.
- Actual producer: session `b33644ad-3a9a-4a37-af40-9a50b34ced2a`, managed
  branch `cbattlegear-ble-integration`.
- Assignment:
  `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_tA0aeLwYsyXljfw55A4ke0lE`.
- Read-only specification: `db14559b39d32322b06477c6ae676112f583db50`.
- Manifest SHA-256:
  `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
- Trusted policy revision on current main:
  `f6f9e0850550b7fea570d43701fb89828e121820c3ef2c4fb36d682cf7d17def`.

The reviewed source commit `944f87d51da7e8cb1fdc229688910191ecf22337`
was directly based on `99479711`. Its test blob
`968979214fa3fb203b0c3761237f06796462a4f2` is unchanged after reconciliation
onto `cd8a315e`. The current branch's ordinary merge reports conflicts in
ChatSendQueueService, ChannelQueryAccountingTest and park-wake evidence because
its retained history predates the already-landed #543 repair. The current-main
versions were selected for those paths; comparison against current main then
shows only this collector test and evidence file changed. No unique local queue
repair was discarded, and the retained owner merge was not placed in the
candidate diff. The relevant AppState and AppContainer production files and the
BLE fixture helpers are unchanged. No production file, shared harness or
original test method is changed.

The immutable #529 receipt still binds
`73a98bcf621a4d75c421953c4004d70c12f4c05a` and its original three methods.
It does not cover the two additional methods below or their successor blob.

Integrator `b0c5d652-8ce1-4dc3-aec2-01ecce702ea7` confirmed no custody in
this BLE fixture, AppState tests or AppContainer. Owner
`a85352c6-96c7-44eb-806d-de7ad856974d` confirmed no exact test/Peer/evidence
collision, but reported frozen, unexecuted collector/dispatcher changes:
AppState blob `a56ccbff81de570b86b79bdf55cd869387c623ab` and AppContainer blob
`664da84d61214938827ae2c50cbc214480c3913c` among them. Those changes are
not imported, executed or assumed here. Future composition must review their
token-owned installation/reuse/retirement semantics against these assertions.
The paused #508 consumer was not inspected or advanced.

Source candidate `9312e184db53930660a1e483523e773185a95852` is withdrawn:
a853 identified that teardown cancels two sync-data subscriptions, not one.
Source tracing confirmed `RadioSessionContainer.subscribeMessageEvents` supplies
the second stream to `MessageEventDispatcher`, whose cancellation unregisters
it in the broadcaster flow's `finally`. The corrected successor separately
cancels the six dispatcher consumers, checks the first subscriber decrement
while four standalone AppState jobs remain, then performs AppState teardown
and checks the second decrement. No executed failure or successful test is
claimed from that source diagnosis.

Independent source review `1be` examined exact candidate
`944f87d51da7e8cb1fdc229688910191ecf22337` / base
`99479711bfdb7c621765f09d671068ca8b8318ce`; it found no blocking source defect.
That review applies to the unchanged test blob above, not to the evidence
refresh or runtime behavior. It explicitly notes that stale native callbacks
alone do not reject every stale raw SelfInfo when no settings request is pending.
Keep the existing `toolEvents` current/retired/current positive and negative
controls; they are essential and remain byte-preserved.

Trusted `reserve.py admit` accepted:

- `android/app/src/test/kotlin/com/meshcoreone/android/app/container/BleRecoveryIntegrationTest.kt`
  with `add-or-update-related-test` / `reviewed-support-test`.
- This evidence path with `record-evidence` / `wp-owned`.

The supported `reserve.py transfer` evaluation returned `advisory: true` and
`file_edit_blocked: false` for each path, retaining historical WP-303 advisory
revision 16 attribution to `be9fa0fd-35b2-4e78-bffc-917adc273f29` alongside
the actual producer and assignment above. No historical owner was impersonated,
released or used as a pre-edit lock.

## Actual collector ownership and controls

`ObserverBridge.onServicesAvailable` and `onConnectionLost` call the actual
`AppState.onSessionStateChanged` through the injected main context.
`AppState.wireServicesIfConnected` installs four session-group collectors:
settings, sync-data, advertisement and RX-log. The separately owned
MessageEventDispatcher installs six consumers, including another sync-data
subscription from the same graph. Teardown cancels both groups;
settings closes its single-consumer subscription in `finally`. Rewiring on the
same graph replaces subscriptions without bumping the graph's services version.
The pinned `AppState+Wiring.swift` cancels/re-subscribes those corresponding
per-connection tasks.

The existing production-composed fixture remains in use: AndroidGattFacade,
BleTransport, runtime link/manager, AppContainer, RadioSessionContainer and Room.
Since the original source review, current main merged #543's bounded queue
retirement repair and added a reaction persistence mutex parameter to
RadioSessionContainer. The candidate uses the current-main factory/graph and
changes neither behavior. The exact #543 hosted run
`41714959984` passed on head `f1c8220e676ff22ea3d355ec952a10f9447c84cd`;
it verifies that merged teardown candidate only and did not execute the two
new collector methods.
The new optional Peer response overrides only the firmware AppStart reply when
explicitly selected; its null default preserves all existing firmware responses.
It does not inject a SettingsEvent or replace AppState, its connection port,
the session or the persistence adapter.

`SettingsService.refreshDeviceInfo` performs a real protocol AppStart request,
whose controlled firmware bytes enter through a native GATT notification.
It then publishes its typed event to the actual settings subscription.
Only the AppState collector forwards that event to the guarded connected-device
editor and its Room save. The tests count the real native writes and wait for
both connected-device and persisted names. Existing bounded fixture pumping
drives the framework/coroutine schedulers; new Room polling is condition-based
with a coroutine delay inside that bounded operation, not a sleep race.

New JUnit methods:

1. `appStateSettingsCollectorCanCancelAndResubscribeOnTheSameNativeGraph`:
   a positive native settings query updates manager and Room; actual AppState
   dispatcher cancellation leaves zero dispatcher jobs and one fewer sync-data
   subscriber while four standalone AppState jobs remain. AppState teardown
   then leaves zero jobs in both groups and two fewer sync-data subscribers
   while the graph remains READY. A completed native query after
   cancellation cannot change either snapshot. Actual same-graph rewiring
   restores four standalone jobs, six dispatcher jobs and the original subscriber count without a services
   version bump. A new query must update both manager and Room.
2. `nativeGraphReplacementRetiresAppStateCollectorsAndConsumesFreshSettingsOnly`:
   native loss must retire both AppState and dispatcher jobs/settings wiring and all old graph
   sync-data subscriptions, with no implicit BLE rearm. Explicit health reconnect
   must install one fresh graph/token and its four collectors, retain the radio
   partition and bump services version once. A fresh settings query is a positive
   manager/Room control. Retired native callbacks must not change those snapshots
   or the fresh graph; a subsequent fresh query proves the collector remains live.

Settings-event consumption has observable positive/negative controls.
The other three AppState collector families are checked for lifecycle ownership
and sync subscriber counts, not exhaustively for their event-specific behavior.
No direct stale SettingsEvent is fabricated. This does not claim that a callback
rejected at the native BLE boundary proves every AppState token-fencing race.

## Validation and remaining gates

Observed with pinned native Windows Python `3.12.4`:

- `python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $o`
  passed using the existing native Windows state and a fresh absolute temporary
  output directory. The documented verification-venv executable was absent;
  the available pinned `C:\Python312\python.exe` ran the supported preflight.
  Windows readiness is not WSL readiness or product execution.
- `python tools\android-port\controller\validate.py`: passed, `result=valid`.
- `python tools\android-port\portmap.py`: passed, exit 0.
- `git diff --check`: passed.
- Current-main reconciliation check: passed; the candidate diff from
  `024dfd11` contains only the BLE collector test and this evidence file, and
  the reviewed test blob remains `968979214fa3fb203b0c3761237f06796462a4f2`.
- Exact-hosted predecessor run `41736738382`, head
  `910752cc8b52d5b8df3dcf7bc32e873ba7eb8500`, base
  `024dfd11e95a59f33ef4e7abe1865e36673e68f3`: completed successfully.
  The `One Linux Android build`, `Controller and workflow policy`,
  `Classify candidate scopes`, `android-ci`, and required checklist jobs
  succeeded. The Swift codec and SwiftData restore/export oracles were skipped
  by workflow selection. The App `:app:testDebugUnitTest` report positively
  discovered all five `BleRecoveryIntegrationTest` methods; all five passed,
  including both new collector methods. The raw report log binds the tested
  source to blob `968979214fa3fb203b0c3761237f06796462a4f2`.

`python tools\android-port\controller\execution_recovery.py status` still reports
the retained `gradle-execution` owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16`, bound to
`3c5297acdc083df21ec08afd7654aeaaa103ee59`; its cleanup input is missing or
noncanonical. It was not recovered, released, or bypassed. No direct local
Gradle execution or runtime deployment was attempted. The reviewed source was
published to a feature branch and a normal PR was opened; no manual hosted
dispatch occurred.

The declared verification is `android-scaffold` with the actual App unit-test
runner (`:app:testDebugUnitTest`). Independent source review and execution of
the unchanged test blob are complete. Because this evidence update creates a
new PR head, its automatic exact-head workflow must also pass before that head
is treated as accepted. Do not copy, hash or commit another CI-result bundle.

Physical Android BLE/CDM, OEM/background behavior, bond refresh, SDK/device proof
and protected gates remain separate. The approved no-implicit-rearm adaptation,
#529's bounded historical receipt and all earlier assertions are preserved.
