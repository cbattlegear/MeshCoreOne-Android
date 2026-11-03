# Persisted queue disposition during generation retirement

Status: bounded source repair and deterministic regressions are implemented in a
local candidate; Android test execution and protected acceptance are not claimed.
Original hosted job logs remain authoritative; no second CI-result bundle is retained.

## Original observation

Notification prerequisite PR #537 is frozen at
`071f66fc4e747ce105824b630e2753060f0043ef`, base
`99479711bfdb7c621765f09d671068ca8b8318ce`.
[Run 41603709434 attempt 2](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41603709434/attempts/2),
[Android job 125821118481](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41603709434/job/125821118481),
ran 1,850 App tests with one failure. The original stack identifies
`SessionLifecycleTest`'s exact persisted park-cause assertion at line 315,
reached from line 429. Cleanup additionally reports two initial-queue teardown
issues where exactly the single retained park issue was expected. Line 145
is the `runTest` entry, not the failed assertion.

The test blob `de6939b98d1feb1f579ddcf9fd7af51e1cae202d` is unchanged at that
candidate and base. Its original failure does not print the extra issue's
operation or cause; it cannot alone prove the interleaving below.

Attempt 1 remains a separate failure: [job 125809123442](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41603709434/job/125809123442)
failed `ChatMapPreviewTest.memoryTrimDuringRenderingNotifiesTheLoadingCardAndDeduplicatesItsReplacement[31]`
at line 404. Neither attempt is erased or made successful by this candidate.

## Source ordering and bounded repair

`ChatSendQueueService.park` commits/remaps the message before reporting its
accounting receipt, then waits for a transport signal/deadline. A successful wake
can already be selected while the deadline's child cleanup is still completing.
Shutdown invalidates queue ownership before joining connection observation and
cancelling the drain. If the selected wake resumes during that interval,
`checkLifetime("park.woke")` throws a non-cancellation `NotConnected` exception.
`SendQueue.drain` consequently invokes terminal handling for the parked envelope;
terminal ownership checking fails and retains an additional
`channelQueueCallback` teardown issue.

The fix keeps the existing cancellation/requeue disposition after a park wake,
including retirement that wins after wake selection. It removes only the
post-wake lifetime check before the already-unconditional cancellation and seals
both drains at shutdown entry before invalidating ownership. Without that early
seal, direct shutdown can let `SendQueue` requeue the cancelled park and normally
complete, respawning a drain before shutdown reaches its later queue cancellation.
That retry can fail lifetime checking and invoke terminal handling. The
deterministic regression deliberately exercises direct shutdown without the
separate recovery-accounting seal. It does
not catch, suppress or filter teardown failures, change send admission, accept
a stale generation, or alter journal/fence handling. The pinned Swift
`ChatSendQueueService.swift` lines 537-556 likewise unconditionally throws
`CancellationError` after its bounded park wait.

The deterministic regression uses the real queue and sender with existing clock,
connection-signal and accounting seams. It waits for the real persisted park
receipt, selects a successful transport wake, holds deadline cleanup, starts
shutdown, holds connection-observer cancellation, then releases the parked sender
before drain cancellation. It requires the exact original park cause as the
single retained issue, no terminal/callback publication, unchanged pending row
identity/status and the identical report on repeated shutdown. No sleeps or
test-only production hook are added.

The App lifecycle assertions are not weakened. Their failure messages now expose
the actual retained issues and messaging operations if a different defect remains.

## Already-dequeued readiness work

The additional review findings on #543 head
`0dfd6abf5d05d0c1f5ecf81775eb219916c237fa`, base
`99479711bfdb7c621765f09d671068ca8b8318ce`, identify a distinct interval not
closed by the shutdown-entry seals. `SendQueue` removes an envelope under its
lock and invokes the sender after releasing that lock. An already-dequeued sender
can therefore reach or resume `awaitReady` after shutdown has invalidated
ownership, where `NotConnected` previously escaped to terminal handling instead
of preserving the durable send.

`awaitReady` now translates only a `NotConnected` produced while this queue's
ownership is closing into `CancellationException`, retaining the typed failure
as its cause. This routes the envelope through `SendQueue`'s existing
cancellation/requeue behavior; unrelated readiness failures remain unchanged.
New direct-message and channel regressions use the real service/queue, select a
readiness wake, hold the deadline timer's cancellation cleanup, and hold
connection-observer cancellation until shutdown has invalidated ownership. A further direct-message regression starts from a genuine
transient transport error and selects the parked-send wake before shutdown.
Together they assert pending identity, attempt count, `PENDING` message status,
and absence of terminal and queue-callback failures. These new tests have not
executed locally because the shared Gradle execution lock remains retained.
They do not prove physical/OEM behavior or protected acceptance for a successor
to #543.

### Hosted readiness-control setup failure

[Run 41621349810](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41621349810)
at published head `2d41498d71926cf03384d3ce0085cd4ad95f09d1`
(base `99479711bfdb7c621765f09d671068ca8b8318ce`) failed both new
readiness controls before their race assertions. The original log reports
`IllegalArgumentException: Disconnected snapshot cannot retain an active generation`
from `ConnectionSnapshot` at `ConnectionContracts.kt:82`, reached by
`assertReadinessRetirement` at line 732. Core services discovered 2,805 tests,
with two failures; the required App report was missing. This run is not acceptance.

The owner correction starts these controls in `CONNECTED` with their existing
token, matching a valid generation that cannot yet drain sends, rather than the
invalid `DISCONNECTED`/non-null-token combination. It preserves both selected-wake
timer-cleanup and observer-cancellation barriers and every existing assertion.
Additional assertions require the token and non-draining state, the actual
`queueReadiness.woke` stale-generation diagnostic, and zero direct/channel wire
sends. No production contract or shared fixture is relaxed. The correction
requires fresh exact-head hosted execution; local Gradle remains held.

## Custody and verification

Actual producer: session `b33644ad-3a9a-4a37-af40-9a50b34ced2a`, WP-303,
starting base `99479711bfdb7c621765f09d671068ca8b8318ce`.
Pinned read-only Swift: `db14559b39d32322b06477c6ae676112f583db50`.

The queue owner session `13a03908-afb8-42bd-9c96-57a27920076e` explicitly serialized
the park/shutdown diagnosis and repair here. Successor journal owner
`befda86a-836d-4dd6-9229-12f061e9c136` excludes this park-tail and shutdown-entry hunk.
Runtime owner `5b3965ae-e65d-43e7-a171-e8b67d7b9ba7` changes manager/factory dispatch
revocation, not this queue/test hunk. No manager, graph, notification candidate,
journal, shared harness, build or dependency changes are included.

Trusted `modify-related-consumer` admits the queue production path as
`reviewed-support-consumer`; `add-or-update-related-test` admits
`ChannelQueryAccountingTest.kt` as `reviewed-support-test`. Ordinary `modify`
correctly does not admit the cross-owner queue path. `SessionLifecycleTest.kt`
and this evidence path are WP-303-owned. Supported advisory transfer preserves
origin and actual producer attribution without creating a lease or review verdict.

Safe checks: `python tools\android-port\controller\validate.py`,
`python tools\android-port\portmap.py`, and `git diff --check` passed during
candidate preparation. They do not prove Kotlin compilation or execution.

The first isolated test-only diagnostic `dd9f62e73663e272681869d29ec24e9d29aead2b`,
[run 41611247590](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41611247590),
was cancelled after source inspection found its clock gate also affected initial
queue readiness. It is not a red-control proof. Corrected test-only control
`fc23df8cea7c4566b90aacd4521218c0086b856a`,
[run 41611384284](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41611384284),
gates only after a genuine park receipt; execution was pending when this note was
written. Both diagnostic refs are retained. Manual workflow diagnostics have
`base:null`; they are not PR-base protected acceptance.

The retained local `gradle-execution` owner remains untouched. No local Kotlin
compile/tests, physical/OEM radio, AppState collector, notification delivery,
whole-WP completion or #383 closure is claimed. Exact hosted execution and
independently attributable source review are still required.

## Separate replacement-retirement repair (review 5751190186)

The successful exact-head hosted run
[41624643879](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41624643879)
belongs to `dbcc51df72eb7b7412bf6d10123f03274f0eb64e` on base
`99479711bfdb7c621765f09d671068ca8b8318ce`. Its shutdown controls remain intact.
It does not accept a replacement-retirement successor. The later source review
identified a separate P2 scheduling window, withdrawing the earlier broad
no-blocker conclusion without invalidating the executed shutdown controls.

The foreign-token observer invalidated ownership before halting either drain.
Its coroutine and the sender are separate children, with no shared critical
section across invalidation and cancellation. A selected readiness sender can
throw retirement cancellation, requeue its envelope, and return normally.
`SendQueue` then permits its completion handler to restart that lane before
`cancelDrain` sets `halted`. This is avoidable scheduling/diagnostic churn;
executed endless looping, duplicate transmissions, data loss and deadlock are
not claimed. The narrow repair calls both existing `stopAfterCurrent` methods
before replacement invalidation. It adds no nested locks or suspension and
changes neither `SendQueue` nor awaitReady/park/shutdown.

New DM and channel controls use actual persisted envelopes and the real token
transition observer. A manual coroutine dispatcher selects the primary readiness
wake and holds its cancelled timer cleanup. A genuinely waiting opposite lane
causes `triggers.finish()` to dispatch cancellation before either `cancelDrain`.
At that dispatch boundary the test holds the observer, releases the primary's
timer cleanup, and runs its continuations to quiescence or one observed restart.
The primary's signal waiter has already completed and left the signal lock;
its completion does not depend on running the held cancellation continuation.
This models the independent sender's permitted progress while the observer is
delayed, without sleeps, reflection or an added production lifecycle hook.
Assertions require the exact old-token `queueReadiness.woke` diagnostic before
lane cancellation and prohibit the subsequent `queueReadiness` restart.
Both pending IDs, zero attempts, PENDING statuses, no terminal/callback failure,
no query accounting, no fake-transport send commands and the new snapshot token
are checked. These controls are not yet claimed to have executed.

Integration producer is session `5143af9b-d73a-400e-9eb4-3ac0bf879b14`.
Both b336 and current WP-208 journal owner bef explicitly confirmed the observer
hunk and new replacement cases are disjoint from their held work. Existing
readiness helpers and journal cases remain untouched. A real saved channel
occupant is established, but the current baseline envelope has no occupant-ID
field: future journal composition must pass that captured ID through its API,
not manufacture one or omit the discriminator. No journal/model API is imported.
Trusted production/test/evidence path-operation admissions passed. The automatic
claim still reports the historical WP-303 lease binding mismatch; its identity,
authorization and revisions are not rewritten. Advisory producer attribution
does not constitute a lease or verdict.

The local Gradle execution hold remains in force. Fresh exact-successor hosted
execution, independently attributed source review, scope/staging and all normal
protected gates remain required; no acceptance receipt or merge is authorized.

## Initial readiness preemption controls (thread 4454516878)

The exact predecessor `a7813c38b48f8351b9a35c17646aa635c510a96b` passed
[hosted run 41632534206](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41632534206):
Services 2807 and App 1850 discovered/run/passed, zero failures/errors/skips.
Its selected-wake controls do not prove retirement before the initial check.
There is no existing injected callback or suspension between real dequeue and
the initial lifetime check. Real CPU preemption is reachable there, but sleeps
or a dispatcher gate before dequeue would not faithfully control that interval.

The independently reviewed design adds one internal, non-suspending nullable
checkpoint as the first `awaitReady` statement, after actual dequeue and outside
the queue lock. It defaults to null and production composition never configures
it. The default adds only a null check: no suspension, lock, new job, exception
conversion or public constructor/API is introduced. Instrumentation in production
source is an explicit tradeoff; instruction timing is not literally identical.
`SendQueue`, existing readiness conversion and every prior control are unchanged.

Separate DM/channel controls enqueue real persisted envelopes. The one-shot
checkpoint clears itself BEFORE starting actual shutdown with `UNDISPATCHED`,
then returns without waiting. Shutdown seals and invalidates ownership, but its
connection observer's independently held non-cancellable finally blocks the join
before either drain shutdown/cancellation. The test advances the scheduler before
asserting that gate. The active real sender must emit exactly one old-token
initial `queueReadiness` stale result, with no `queueReadiness.woke`, clock wait,
send command, terminal diagnostic, callback failure or query accounting. The
original persisted ID, zero attempts and PENDING status are asserted before and
after observer release and completed teardown. Unexpected checkpoint errors are
recorded and rethrown, then explicitly rejected by the test so they cannot
masquerade as valid sender cancellation.

Source-only mutation argument: reverting ONLY the initial check to raw
`ownership.checkLifetime` throws typed NotConnected rather than cancellation.
Real `SendQueue` then invokes its real terminal callback, which records
`terminal.<id>` and fails its retired lifetime check; the new terminal and
teardown assertions reject that path even if the row remains PENDING. No executed
mutation result or direct assertion of the swallowed cancellation's cause is
claimed. Existing exact retained-cause controls remain intact.

All three production/test/evidence path-operation admissions were rechecked.
b336 and bef confirmed this checkpoint adjacency is disjoint from their held
work. The channel fixture establishes a genuine saved occupant; future journal
composition must pass that captured ID through its then-available envelope API
and genuine admission, not import or bypass held journal work here. The local
Gradle hold remains untouched. This successor requires independent exact-source
review before publication and fresh exact-head hosted execution afterward.
