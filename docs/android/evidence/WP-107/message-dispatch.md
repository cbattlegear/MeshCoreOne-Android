# WP-107 messaging cancellation provenance prerequisite

Repository: `cbattlegear/MeshCoreOne-Android`. Bounded supervised producer support
for the active WP-208 message recovery repair. Base:
`1e1ff70345f203b41f9a8b04836c2cc4640500c9`. Frozen source:
`db14559b39d32322b06477c6ae676112f583db50`. The candidate is the commit containing
this document; actual hosted verification must bind the live exact head.

## Authority and scope

Existing WP-107 owner `protocol-porter`, session/attempt
`f8b1ae3a-f9b8-4115-a277-8a37f52cca74`. Trusted `reserve.py admit` explicitly
accepted creation of `session/MessageWriteDispatch.kt`, modification of
`session/SessionRoles.kt` and creation of `session/MessageWriteDispatchTest.kt`
under `wp-owned`. Every edit is within WP-107's declared session source/test or
own deviation/evidence paths. Supported same-identity scope evolution returned
existing revision 2 unchanged; no old authorization, binding, receipt or #525
history was rewritten, and no new controller attempt was fabricated.

WP-208 session `13a03908-afb8-42bd-9c96-57a27920076e` confirmed these exact
signatures and absence of concurrent protocol source/test changes. It owns
MessageService and receipt integration, not the protocol paths edited here.
No new session/agent, policy/runtime change, lock takeover or unrelated repair.

## Agreed additive API

Package `com.meshcoreone.android.core.protocol.session`:

```kotlin
class MessageWriteDispatch {
    val cancellationDisposition: ChannelWriteCancellationDisposition
}

suspend fun MessagingSessionOps.sendMessage(
    destination: Bytes, text: String, timestamp: Instant = Instant.now(),
    attempt: UByte = 0u, dispatch: MessageWriteDispatch,
): MessageSentInfo

suspend fun MessagingSessionOps.sendChannelMessage(
    channel: UByte, text: String, timestamp: Instant = Instant.now(),
    dispatch: MessageWriteDispatch,
)
```

These are role member overloads, not extensions. The declaration above elides
their bodies. Named `dispatch = handle` works when using existing default
timestamp/attempt arguments. Original overloads remain unchanged.

A fresh handle is required for every actual backoff attempt. The handle
internally wraps the already-landed `ChannelWriteDispatch`, sharing its one-use
claim and proven `RequestContext.cancelDispatch` classification. The real direct
send still uses its `messageSent` matcher and device-error policy; channel sends
still use bare OK. The common request fence, event correlation, orphan drain,
timeouts and session retirement are untouched.

Only read the disposition after propagated `CancellationException`:
`DEFINITELY_UNSENT` means no task exists or a closed dispatch fence prevents
that attempt from ever sending. `MAY_HAVE_WRITTEN` and `UNKNOWN` do not permit
an unsent claim. Unsupported role implementations delegate to old overloads
and leave `UNKNOWN`. Handle reuse across either send path is rejected.
No flag sampled at the service boundary, new serializer, callback, noncancellable
service continuation, ACK receipt, delivery or storage inference is introduced.

## Source and test accounting

Pinned `MeshCoreSession+Messaging.swift`, `MessagingSessionOps.swift` and
`RequestContext.swift` specify existing direct/channel wire/response/cancellation
semantics. The accounting handle is an explicit native addition, not a new
original Swift family. Existing original correlation cases for serialization,
device errors, orphan replies and queued cancellation remain relevant; original
messaging tests and the #525 monitor-contention/negative controls remain intact.

`MessageWriteDispatchTest` adds 20 supplementary real-session/role cases:

| Cases | Measurable control |
| --- | --- |
| 2 pre-cancelled | ATOMIC-start caller enters cancelled before task creation; catch observes definitely-unsent and no packet/subscription. |
| 2 real-mutex-queued | Actual resetPath owns the real serializer; cancelling queued direct/channel send proves unsent; releasing prior OK produces no old message and one fresh retry works. |
| 2 dispatched cancellation | Original caller exception preserved; old response drains before successor writes, never completes the successor. |
| 2 cooperative transport entry | Even when no frame commits, cancellation after crossing send fence stays possibly-written and same-generation retry is quarantined. |
| 2 noncooperative transport | Actual transport send history grows after cancellation returns, with an independently expected direct/channel packet; disposition remains possibly-written. |
| 2 missing response | Timeout plus later old response does not release unresolved correlation. |
| 2 retirement | Old queued task cannot dispatch after actual stop/start; a sent cancellation never upgrades to unsent. |
| 2 unsupported role | Original cancellation preserved, unknown default, fresh-handle requirement enforced. |
| 2 exact-wire controls | Independent direct/channel byte literals include timestamp, UTF-8 and high-bit attempt; existing overload emits identical bytes and preserves return semantics. |
| 1 cross-path reuse | A direct call's old unsent handle cannot be reused for a channel attempt. |
| 1 typed-error matrix | Short direct prefix remains invalid without sending; both device-error paths retain raw code 42, without unsent inference. |

These tests use the real session/parser/generation serializer, not a service
mutex or an unrelated serializer. Existing #525 JVM monitor ownership proves
cancel-versus-send linearization independently of these real-transport controls.
Independent source review requested strengthening the original noncooperative
callback-local observation. That case now uses an actual deliberately
noncooperative `MeshTransport.send` implementation with its own send history.
Unlike the shared mock's cooperative activity check, it can record the exact
packet after the suspended send resumes despite cancellation. The test asserts
one handshake packet before resume and two actual packets afterward, without
using `NonCancellable` or changing the production fence.

## Verification

- `git diff --check` - passed during authoring.
- `python tools\android-port\controller\validate.py` - passed, 1,866 frozen
  paths / 64 WPs / 178 dependency edges; source pin unchanged.
- `python tools\android-port\portmap.py` - passed; 2,004 mapping entries.
- `python android\scaffold\check_environment.py` - blocked by inherited
  non-allowlisted variables, not passing Android readiness.
- The supported reservation status reports `gradle-execution` held by
  `local-check:f516a7e271f444dd9cb2efcc8b995a16`. That owner is not cleared or
  bypassed.
- `python tools\android-port\local\check.py --commit 7faafe88f3201c304957551fc088292c93bca5df --affected-base 1e1ff70345f203b41f9a8b04836c2cc4640500c9`
  - refused with exit 1: `BLOCKED: Hard-lock collision for external resource
  gradle-execution: gradle-execution held`. No Gradle test executed and no lock
  was released. This candidate is explicitly locally unverified.

Hosted result and complete executed-method inventory are pending. Do not infer
passing JVM tests from source validators or historical #525 CI. Exact-head
hosted logs, once available, remain authoritative; no second CI-result bundle.

This producer prerequisite does not execute MessageService, notification
receipts or physical radios. Those integration outcomes remain WP-208's
separate work and are not accepted by compiling or landing this API.
