# Issue #383: AppState collectors over the native BLE graph

Status: **source-only, locally UNVERIFIED candidate** for independent review.
No Kotlin compilation, test discovery, hosted acceptance or device result is
claimed. This adds software controls to WP-303, not WP-205 completion or a new
receipt for the historical #529 candidate.

## Binding and custody

- Trusted main: `99479711bfdb7c621765f09d671068ca8b8318ce`, rechecked against
  the remote before editing.
- Retained owner parent: `ba7a02e5927b2cad22f9c92cbe808dbccf26a8ce`.
- Actual producer: session `b33644ad-3a9a-4a37-af40-9a50b34ced2a`, managed
  branch `cbattlegear-ble-integration`.
- Assignment:
  `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_tA0aeLwYsyXljfw55A4ke0lE`.
- Read-only specification: `db14559b39d32322b06477c6ae676112f583db50`.
- Manifest SHA-256:
  `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.

The source candidate is parented directly to trusted main and contains only
the two-path delta. A separate retention merge on the managed owner branch
keeps that candidate reachable alongside the previously handed-off queue repair
history, without rewriting either history or creating another branch. Only the
main-parented source commit is eligible for this assignment's composition or
review; the retention merge is not a test-only verification candidate. Before
this delta, the test and relevant AppState and AppContainer production files
were identical to trusted main. No production file, shared harness, prior test
method or historical evidence file is changed by the source candidate.

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

`python tools\android-port\controller\execution_recovery.py status` still reports
the retained `gradle-execution` owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16`, bound to
`3c5297acdc083df21ec08afd7654aeaaa103ee59`; its cleanup input is missing or
noncanonical. No recovery, lock release, alternate execution namespace, direct
Gradle, runtime deployment, push or hosted dispatch was attempted.

The declared verification is `android-scaffold` with the actual App unit-test
runner (`:app:testDebugUnitTest`), subject to normal admission. Independent
source review and fresh exact-candidate hosted positive discovery/execution of
all five methods in the changed test file remain required. Do not copy, hash or
commit another CI-result bundle.

Physical Android BLE/CDM, OEM/background behavior, bond refresh, SDK/device proof
and protected gates remain separate. The approved no-implicit-rearm adaptation,
#529's bounded historical receipt and all earlier assertions are preserved.
