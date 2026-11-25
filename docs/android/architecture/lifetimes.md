# Process, radio generation and screen lifetimes

**Status:** Proposed; WP-001 supervised dependent draft, pending human review.
This draft's source links describe the original `db14559b39d32322b06477c6ae676112f583db50` reference.
The current pin is in `../port-manifest.json`; consult current source for behavior and signatures.

The original [ServiceContainer](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/ServiceContainer.swift)
is **per connection, not singleton**. Its injected persistence store is process-lived.
[ConnectionManager.buildServicesAndSaveDevice](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Connection/ConnectionManager.swift#L871)
resolves radio identity before construction. Setter/callback cycles are wired
before monitoring and explicitly cleared on teardown.

## Owners

| Lifetime | Owns | Must not own |
| --- | --- | --- |
| Process `AppContainer` | Room/repositories, preferences/secret-store adapters, clock/dispatchers, connection controller, platform notification adapter, factory bindings and source app-scoped cache/location utilities | A permanently reused radio service graph or a screen's collectors |
| `RadioSessionContainer` identified by session token | Protocol session, scoped service graph, ingestion/monitor subscriptions, ACK waiters, send drains, sync and log buffers | Database/preferences lifecycle or another generation's callbacks |
| Screen ViewModel | Immutable screen state, draft/selection policy, scoped UI observation and requested actions | Physical transport, reconnect loop, process ingestion or singleton services |

Choose manual constructor/factory DI, not Hilt. One connection owner serializes
user intent, pairing, BLE/WiFi switching, reconnect and foreground reconciliation.
The connected-device FGS is an Android execution host for that same owner, not a
second controller. Screens and widgets submit commands; they cannot create radios.
Leaving a screen or stopping a lifecycle-aware collector does not disconnect it.

## Connection ceremony

1. Claim a new process-unique **epoch + increasing generation**, with a persistent
   `radioID` only after identity resolution. Bluetooth addresses/peripheral handles
   are not radio identity. Serialize overlapping connect/switch/rebuild requests.
2. Acquire the transport/session and subscribe before protocol startup. Resolve
   the radio using existing records/public key, preserving restored `radioID`.
   Construct via injected `SessionServiceFactory`; failure/cancellation cleans up
   partially constructed children, not a published half-ready graph.
3. Wire ingestion handlers, paired sync-start/end callbacks, clean/attempted-sync
   callbacks, identity-change requests and notification action routing **before**
   starting monitors. A service emits typed signals upward rather than importing
   the concrete connection controller.
4. Save the device before orphan-send cleanup, attach the current snapshot plus
   lossless transition subscription, then start monitors/sync once. Source app
   rungs are disconnected/connecting/connected/syncing/ready; physical link state
   and BLE phase stay distinct. Send drains start only at **ready**, not link-up.

Every continuation, timer, protocol event and asynchronous factory/sync completion
carries the session token. Recheck it after suspensions before changing state or
promoting ready. Reject obsolete callbacks; a radio with the same `radioID` after
reconnect is still a different generation. Clean-sync/platform history may survive
reconnect on the controller with the source's explicit clear-on-switch/disconnect rules.

## Symmetric teardown

First invalidate the old generation and gate new drains/actions. A single
start/active/stop lifecycle owner claims transitions before suspension; teardown
also handles a partially starting graph, not just an already-active monitor.
Cancel and join owned monitor/auto-fetch/retry/sync/ACK-expiry jobs, remove pending
continuations, then clear message/discovery/identity/notification forwarders.
End every per-session subscription, including consumers registering after termination.

Stop send-queue work and persist its unresolved entries; a routine disconnect
alone must **not** rewrite pending/delivered DMs to failed. Flush pending RX inserts
and shut down the debug buffer, including partial batches before their timers fire.
Preserve debug defaults (5 s / 50 entries, 7 days / 50,000 rows) and RX defaults
(20-entry batch, 1 s partial flush, 1,000 retained rows / 100-row prune threshold).
Attempt each required cleanup even after a typed
flush failure, collect explicit teardown issues and report them; do not hide
storage failure with `try?`-equivalent success. Only bounded owned cleanup may be
non-cancellable, with cancellation otherwise propagated.

Close sockets/GATT exactly once when their physical lease ends. A platform
auto-reconnect retaining a GATT handle must transfer that single-owner lease
deliberately after the old session is stopped; it must not leave two consumers or
let an obsolete container close the new generation's handle. Clear the published
graph only after awaited teardown; retain the process database/preferences.

[EventBroadcaster](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Sources/MC1Services/Utilities/EventBroadcaster.swift)
multicasts synchronously registered streams and finishes them. The controller's
process connection broadcaster intentionally survives reconnect. `SharedFlow`
cannot supply the first behavior just by cancelling its producer: per-session
subscriptions must terminate explicitly. See [contract signatures](contracts.md).

## Implementation and evidence boundary

WP-207 implements lifecycle against factories/test doubles, not unfinished domain
services. WP-303 constructs the complete graph only after the manifest prerequisites.
Port the four [ServiceContainerWiringTests](https://github.com/Avi0n/MeshCoreOne/blob/db14559b39d32322b06477c6ae676112f583db50/MC1Services/Tests/MC1ServicesTests/ServiceContainerWiringTests.swift)
families: six constructor connections, message/discovery handler removal,
notification-forwarder removal and ACK-expiry start/stop. Add deterministic
overlapping-start/stop, factory failure/cancellation, old-generation completion,
two-radio switching, process-store survival and flush-error cleanup scenarios.
These are required future assertions, not tests executed or parity accepted by WP-001.
