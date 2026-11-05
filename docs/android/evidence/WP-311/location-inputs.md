# WP-311 mounted list location inputs

Bounded issue #410 follow-up; pinned source remains
`db14559b39d32322b06477c6ae676112f583db50`.

## Source and native behavior

`MC1/State/AppState.swift` supplies `bestAvailableLocation`: existing one-shot
phone fix, otherwise the currently connected device's coordinates. The actual
`MC1/Views/Contacts/ContactListActions.swift` and `DiscoveryView.swift` pass that
location to sorting and use Last Heard when Distance is selected without a fix.
Discovery observes coordinate changes rather than allocating location identity.
`MC1/Services/LocationService.swift` produces one-shot fixes, not continuous GPS.

The existing Android LocationManager adapter now exposes those successful
one-shot results as an observable, permission-filtered flow. It does not initiate
a fix, invoke a permission requester, register location listeners or add tracking.
Its existing advert cache and one-shot cancellation/timeout behavior stay intact.
Authorization is refreshed when the UI resumes collection and on the adapter's
existing permission/fix callbacks. Denied/revoked access withdraws the phone fix
from this UI stream, without destroying the separate existing advert cache.
The App nodes adapter combines it with observable connection snapshots, preferring
the phone fix and otherwise the live connected-device coordinates (not an offline
saved device or an invented coordinate).

Both real mounted screens collect while RESUMED, include the coordinate in their
sort invalidation, and restore the source Last Heard fallback when unavailable.
Permission prompts and background location access are never initiated here.
Core navigation, list loading and radio commands remain independent of location.

## Focused acceptance controls

- `NodesLocationApi31ComposeTest` / `NodesLocationApi37ComposeTest` inherit
  `contactsDistanceReordersOnLocationChangesAndFallsBackWhenUnavailable` and
  `discoveryDistanceReordersOnLocationChangesAndFallsBackWhenUnavailable`.
  Actual mounted sort selection and changing coordinates reverse visible row
  positions without reselecting sort; withdrawing location restores Last Heard,
  deliberately different from name order. No radio command is sent.
- `LocationManagerLocationProducingTest.foregroundLocationsObserveExistingOneShotFixesAndWithdrawOnRevokeWithoutPromptingOrTracking`
  observes two real platform one-shot callbacks, denied/revoked permission,
  resume/recollection, zero prompts and no location-listener registration by
  collection alone. Existing cache-retention and cancellation cases are unchanged.
- `NodesLocationIntegrationTest.actualNodesAdapterPrefersAuthorizedPhoneThenLiveRadioAndWithdrawsDisconnectedFallback`
  exercises the actual AppContainer nodes dependency, live radio-coordinate
  updates, phone precedence, unavailable phone fallback and disconnect withdrawal.

Existing API31/37 mounted controls retain 200% font and RTL. Synthetic service
graphs and Robolectric LocationManager delivery are not physical location,
permission-dialog, radio or device acceptance.

## Admission and execution

Feature/evidence paths are WP-311 owned. Existing App adapter/composition paths
use `reviewed-support-consumer` / `modify-related-consumer`; App tests and the
test harness extension use `reviewed-support-test` / `add-or-update-related-test`.
These changes are necessary only to feed and prove the assigned mounted consumers;
no build, dependency, resource, source-ownership or held-scope changes are included.

No local Gradle execution: the user's low-memory host restriction and existing
execution owners are preserved. Acceptance requires exact-head hosted CI with
nonzero discovery and the named methods above, independent review and live rules.
Prior PR results do not transfer to this candidate.
