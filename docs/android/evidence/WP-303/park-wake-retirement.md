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
