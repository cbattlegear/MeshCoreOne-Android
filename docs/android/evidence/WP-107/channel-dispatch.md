# WP-107 bounded channel cancellation-dispatch support

Repository: `cbattlegear/MeshCoreOne-Android`. Issues #417/#508, limited to the
protocol producer API needed to distinguish a definitely unsent cancellation.
Base: `5878b5357dc9fe9986f002fe68763aca0bc35303`. Frozen source:
`db14559b39d32322b06477c6ae676112f583db50`. Candidate head is the commit containing
this document; executable evidence must bind that exact commit, not #436.

## Authority and ownership

WP-107 / `protocol-porter`; existing session/attempt
`f8b1ae3a-f9b8-4115-a277-8a37f52cca74`, managed isolated worktree/branch.
Trusted `reserve.py admit` admitted production `session/RequestContext.kt`
modification and `session/ChannelWriteDispatchTest.kt` creation as `wp-owned`.
All changes remain under WP-107's declared production/test session directories
and its own deviation/evidence paths. Trusted invariants: one WP/branch/PR,
pinned source, declared verification and evidence provenance.

Supported `reserve.py evolve` used actual stored identity/attempt, expected
revision 2 and the existing four path scopes with create/modify operations. It
returned the same revision unchanged, including its historical base binding.
No new controller task, successor, authorization rewrite or identity rebind
was fabricated. No advisory history was deleted. AUTOMATION.md's supervised
local-source contract distinguishes this result from autonomous execution gates.

The existing WP-405 writer confirmed no concurrent protocol source/test edits
and agreed the additive API before authoring. Its service/consumer work is not
modified here. No #495/#506 changes, contact #517 repair, policy deployment,
host attestation or runtime installation is included.

## Public contract

Package: `com.meshcoreone.android.core.protocol.session`.

- `ChannelSessionOps.setChannel(index, name, secret, dispatch: ChannelWriteDispatch)`
  is additive; the original three-argument method is unchanged.
- Create a fresh `ChannelWriteDispatch` for every actual call.
- Read `dispatch.cancellationDisposition` only after cancellation propagates:
  `ChannelWriteCancellationDisposition.DEFINITELY_UNSENT` proves the producer
  has fenced off any subsequent dispatch for this attempt.
- `UNKNOWN` and `MAY_HAVE_WRITTEN` require conservative consumer uncertainty.
  Unsupported implementations' default overload stays `UNKNOWN`.
- These are cancellation dispositions, not normal-success, ACK, persistence,
  rollback, free-slot or generation-recovery receipts.

The real generation serializer owns the cancellation-versus-send decision.
`beforeSend` checks caller activity and marks possible transmission under the
same lock that closes the cancellation fence. No callback executes in that
critical section. A possibly written exchange keeps its existing response
ownership, late-response draining, deadline and quarantine. Caller cancellation
is rethrown, not suppressed or replaced by success.

The source `RequestContext.swift` serializes and retains orphaned responses but
has no public dispatch disposition. The native implementation already separates
transport-send cancellation from owned response lifetime. This bounded native
extension closes its check/mark race before exposing any unsent proof.

## Discriminating regressions

`ChannelWriteDispatchTest` adds 11 real-session/interface cases and two direct
dispatch-fence controls. They are supplementary native assertions, not newly
credited original Swift families:

| Schedule | Required assertion |
| --- | --- |
| Actual serializer occupied by battery exchange; channel queued and cancelled | Cancellation catch observes fenced `DEFINITELY_UNSENT`; releasing the first exchange and sending a successor never dispatches the cancelled channel. |
| Normal four-argument call | Independently constructed exact `0x20` bytes and bare OK completion; no success-to-cancellation inference. |
| Cooperative send suspended before transport commit | Cancel propagates; no committed frame does not downgrade `MAY_HAVE_WRITTEN`; unresolved response remains blocked. |
| Deliberately noncooperative transport | A simulated transport effect after cancellation is allowed only with `MAY_HAVE_WRITTEN`. No device result is claimed. |
| Sent cancellation then late ACK and successor | Original cancellation propagates; old ACK drains under old slot and cannot complete successor. |
| Sent cancellation without ACK | Expiry and even a later old ACK do not clear same-generation quarantine. |
| Queued cancellation plus retirement/reconnect | No old channel dispatch; successor uses fresh actual handshake. |
| Sent cancellation plus retirement/reconnect | Old handle never becomes definitely-unsent; fresh call remains functional. |
| Handle reuse | Reject before any new packet; cannot reuse an old unsent disposition for a later operation. |
| Invalid input and device error | Existing typed failures and wire validation remain intact. |
| Alternate implementation | Default overload retains `UNKNOWN` and original cancellation. |
| Cancel wins fence while caller remains active | Both ordinary and cleanup dispatch are rejected permanently. |
| Sender paused inside active-check/mark window, caller cancelled concurrently | Cancellation cannot observe an unsent snapshot inside the critical section; sender winner reports `MAY_HAVE_WRITTEN`. Real JVM latches/futures have bounded waits and joined executor cleanup, not timing sleeps. |

## Verification status

- `git diff --check` - passed during authoring.
- `python tools\android-port\controller\validate.py` - passed: 1,866 frozen
  reference paths, 64 WPs and 178 dependency edges.
- `python tools\android-port\portmap.py` - passed, exit 0.
- `python android\scaffold\check_environment.py` - blocked by inherited
  non-allowlisted environment variables. This is not passing local readiness.
- `python tools\android-port\local\check.py --commit
  a83561d773f2d470a558ca2fcfed78429b3c6be7 --affected-base
  5878b5357dc9fe9986f002fe68763aca0bc35303` - admitted the ordinary shared
  execution lane but failed cloning its candidate into the WSL runner:
  `fatal: write error: No space left on device`, `index-pack died`, exit 128.
  No JVM tests ran. The supported status command subsequently reported no
  retained hard locks. No shared storage was deleted, runtime replaced, or
  execution interlock bypassed.
- This is an explicitly locally unverified publication candidate. JVM and
  hosted verification are not claimed by this document. The exact-commit hosted
  job/log, if obtained, is authoritative; do not retain a duplicate CI-result
  bundle.

Acceptance IDs: bounded WP-107-behavior and WP-107-boundaries support.
No whole-WP, actual service/Room consumer, device, hardware, signing, protected
merge or late-ACK-to-store reconciliation acceptance is claimed.
