# WP-311 mounted list location inputs

Bounded issue #410 follow-up; pinned source remains
`db14559b39d32322b06477c6ae676112f583db50`.

## Source and native behavior

`MC1/State/AppState.swift` supplies `bestAvailableLocation`: existing one-shot
phone fix, otherwise the currently connected device's coordinates. The actual
`MC1/Views/Contacts/ContactListActions.swift` and `DiscoveryView.swift` pass that
location to sorting and use Last Heard when Distance is selected without a fix.
Discovery observes coordinate changes rather than allocating location identity.
`ContactsSidebarContent.swift:153-155` requests an authorized one-shot on mount,
including when an earlier phone fix is cached.
`MC1/Services/LocationService.swift` produces one-shot fixes, not continuous GPS.

The existing Android LocationManager adapter now exposes those successful
one-shot results as an observable, permission-filtered flow. A resumed collection
with granted permission refreshes one bounded foreground fix (10 seconds),
retaining any cache. It never invokes a permission requester or adds continuous tracking.
An overlapping list observes the exact existing request's completion without
owning it. If that acquiring list leaves and cancels its request, the remaining
observer follows the current request and may acquire a replacement within its
original 10-second budget, including when another observer wins an intermediate
handoff. Provider failures are not retried; cancellation handoffs share the same
bounded deadline rather than extending it.
An explicit advert/onboarding request atomically takes over a list-owned slot,
canceling that retired platform fix and fencing its late callback. Lists then
observe the explicit request, without owning or canceling it. Competing explicit
requests retain the existing RequestInProgress behavior. If an explicit owner
cancels, surviving lists may recover within their unchanged deadline.
Callback completion derives cache publication and the observer result together
under the adapter monitor. A callback rejected because its owner is already
inactive reports cancellation, never an uncached success that could strand
remaining observers.
Its existing advert cache and one-shot cancellation/timeout behavior stay intact.
Authorization is refreshed when the UI resumes collection and on the adapter's
existing permission/fix callbacks. Denied/revoked access withdraws the phone fix
from this UI stream, without destroying the separate existing advert cache.
The App nodes adapter combines it with observable connection snapshots, preferring
the phone fix and otherwise the live connected-device coordinates (not an offline
saved device or an invented coordinate). Actual device-coordinate changes are
observed through the existing device-access change stream.

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
  resume/recollection and zero prompts or requests when denied. Additional
  `coldForegroundFixIsBoundedAndCollectorCancellationCannotPublishItsLateCallback`
  and `foregroundCollectorObservesButDoesNotCancelAnotherOwnersExistingOneShot`
  prove the bounded list-owned request, late callback fencing, and preservation
  of a preexisting advert/onboarding request through list disposal.
  `disposingAcquiringCollectorHandsOneBoundedFixToTheRemainingObserver`
  specifically holds acquiring A and observing B, disposes A, rejects A's late
  callback and requires B's replacement real platform callback to publish a fix.
  `lastForegroundObserverFollowsTwoDisposedAcquiringOwnersWithinItsOriginalDeadline`
  holds A/B/C, disposes A then the replacement-owning B, rejects both retired
  callbacks, and requires surviving C to acquire and publish its own callback.
  The separate handoff deadline control advances 8 seconds, hands over, then
  advances 2.001 seconds and requires cancellation at the original deadline.
  Explicit-takeover cancellation recovery is also covered with both retired
  platform callbacks retained and rejected.
  `cancelledOwnerOverlappingCallbackCannotReportAnUncachedSuccessToRemainingList`
  holds only the adapter monitor, cancels the real owning continuation on a
  bounded test thread, delivers its callback before cleanup can claim the
  monitor, and requires the remaining list's replacement callback. This is a
  deliberate race control, not a sleep-based ordering assumption.
  `resumedForegroundCollectionRefreshesCachedFixAndRetainsItThroughBoundedTimeoutWithoutPrompting`
  verifies actual adapter recollection on API31/37 with a cached fix, fresh
  callback publication, cache retention through the unchanged timeout and no prompt.
- `NodesLocationIntegrationTest.actualNodesAdapterPrefersAuthorizedPhoneThenLiveRadioAndWithdrawsDisconnectedFallback`
  exercises the actual AppContainer nodes dependency, live radio-coordinate
  updates, phone precedence, unavailable phone fallback and disconnect withdrawal.
- The same App integration class mounts the real public nodes entry over Room
  with the real LocationManager adapter. Its cold authorized control verifies
  a pending platform one-shot and callback-to-visible-distance-order transition
  without another feature requesting a fix. Its denied control verifies Last
  Heard, no prompt and no pending platform fix.
  `resumedMountedListRefreshesAnExistingFixWhileRetainingItsOrderUntilTheNewCallback`
  pauses/stops and resumes the actual mounted host after a successful fix,
  requires another real platform request while preserving cached location and
  row order, then requires the new callback to reverse distance order.
- `ShortcutAdvertLocationTest.listFirstForegroundFixCannotStarveExplicitPhoneAdvertOrCancelItsTakeover`
  starts the real cold list fix before the real PHONE pre-advert path, requires
  explicit takeover, rejects another explicit request and the old callback,
  disposes the list without canceling the advert, and requires actual verified
  location commands before the advert. No cache-shaped success is accepted.

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

The original `803f35b` hosted run `42126554703` failed App unit-test compilation
at the newly authored untyped `listOf(null)` assertion; no App execution result
is inferred. The successor supplies the explicit nullable element type and
repairs the independent cold-cache, radio-observation and recency-fixture findings.
The `6f549e0` hosted run `42128297934` subsequently failed App test compilation
on the new test's unsupported generic Robolectric application accessor. The
successor uses the actual nongeneric API; no App runtime result is inferred.
The `4f56c79` run `42130151439` executed 2,033 App tests, with 2 new mounted
failures locating Distance after an injected Sort touch. The successor drives
the actual mounted OnClick semantics and waits for popup composition; row-order
expectations and the real callback transition remain unchanged. Other suites'
results are not transferred to the successor.
The `4415934` run `42131884693` passed, including the actual mounted cold/denied
controls, list-first advert, bounded handoffs, real cache and explicit-owner
controls. It remains predecessor evidence only: the callback/cancellation-race
successor requires its own exact-head hosted results and independent review.
The `7243d95` run `42134558417` also passed. Its outcome is historical evidence
for callback/cancellation arbitration, not acceptance of the subsequent
populated-cache resume refresh and its new mounted and adapter controls.
