# WP-215 issue #514: notification action acceptance recovery

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Bounded repair: issue #514; acceptance references `WP-215-behavior`,
  `WP-215-boundaries`, `WP-215-source-test-parity`. This is not whole-WP completion.
- Trusted starting base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
- Resumed trusted main: `796a872a3565a66c74f782b738a9ecb3c273fb25`,
  merged into the consumer at `5e02ff9c99c34b021e073059f8ede0b47c381dae`.
  This includes WP-107's genuinely landed #531 dispatch-provenance API.
- Read-only source: `db14559b39d32322b06477c6ae676112f583db50`.
- Shared receipt contract: `bfd2418d99395e7302ff45dade2b67cba8611857`.
- Initial combined diagnostic head: `d4c63e7027469ac63a1515e66cc0fb2f3bf53697`
  (WP-208 producer `e98a1ecb633bdc1ac1b9be6d44f4483076fdbc7b` integrated).
  Hosted run: <https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41581606473>,
  attempt 1, workflow dispatch: **failed** at
  `:feature:onboarding:mergeDebugUnitTestResources` with `Java heap space`.
  This is a provisional diagnostic, not final
  verification: the producer is correcting definite-unsent serializer provenance,
  and WP-215 is reconciling existing #495 dispatcher commitment/retry hunks.

## Ownership and admission

Assigned WP-215/session `c9bd4093-269f-48d9-afd2-5684c2b4d2ce` acquired the canonical
typed reservation on the trusted base. Authorization text and historical receipts
were not changed. Same-owner evolution admitted the new standalone notification
receipt under `wp-owned/create`; handler/test/evidence edits use WP-owned scope.
The exact existing `NotificationActionReceiver.kt` consumer uses
`reviewed-support-consumer/modify-related-consumer`. Existing dispatcher tests and
the new App integration test use
`reviewed-support-test/add-or-update-related-test`.

WP-302 confirmed it is not writing the dispatcher or handler; its queue-capacity
consumer contract remains unchanged. WP-401 owns the separate content-tap delivery
and snapshot cleanup changes. This repair does not edit NotificationService,
NotificationDelivery, AndroidMessagingNotificationDelivery or its private Activity.
Although related-consumer admission for MessageService succeeded, actual semantic
ownership is serialized to WP-208/session
`13a03908-afb8-42bd-9c96-57a27920076e`; WP-215 does not duplicate that producer's edit.
The producer explicitly agreed to consume the receipt prerequisite and preserve
the wire/owned-child boundary. The first implementation is integrated with its
original attribution; a follow-up is required because starting IN_FLIGHT before
the protocol serializer can misclassify provably unsent cancellation as uncertain.
The revised producer must prove the real transport dispatch boundary, not infer
it from a FAILED persistence row.

### Existing #495 dispatcher reconciliation

The trusted main starting tree predates #495's committed dispatcher improvements.
Those are reconciled from WP-302's committed Git objects, not by modifying its
worktree or branch: public `NotificationContentTap.enqueue(Intent)` and its
unchanged result enum; independent `NotificationActionCommit.navigationCommitted`;
and the `retryRequested`/completion handshake that preserves READY notifications
during the final bounded retry delay. WP-302 confirmed WP-215 is the sole current
dispatcher producer. Its AppContainer READY/generation seam and navigation
consumer remain untouched. Reply wire acceptance and mark-read commitment remain
separate from navigation commitment and from WP-401's proposed durable OPEN store.
No enqueue result is reinterpreted as durable navigation acknowledgement.

### Producer-first resume

The sole WP-208 producer confirmed #535 head
`0d7c139228679d88c51ad38539819ef81d060cb5` is canonical, but still open rather
than trusted-landed at this resume. Its MessageService, CommitRecoveryTest and
WP-208 deviation differ from the historical producer imported here. The consumer
must integrate the actual producer landing and any subsequent canonical test
correction before publication; blob equality against the stale consumer does not
supersede producer ownership or acceptance.

The normal separate #514 PR is authorized after that reconciliation. Existing
pull-request classification provides changed-scope hosted checks; neither workflow
nor build-resource policy is changed. Fresh exact-head checks and independent
review/receipt gates remain mandatory before merge. The historical full-graph OOM
run above is retained as historical failure, not final product evidence.
Current trusted `reserve.py admit` checks reconfirmed WP-owned handler modification
and reviewed-support-consumer dispatcher modification. Reservation identity and
historical binding remain intact; no release/recreate or authorization-text edit.

## Deterministic regression witnesses

The following are authored tests, **not executed passing evidence**:

| Surface | Required assertion |
| --- | --- |
| Real MeshCoreSession/MessageService, direct and channel | Firmware command returns; hold persistence tail, cancel waiting caller, retain ACCEPTED, exactly one observed wire command, no unsent draft/failure notification |
| Owned-child result handoff | Cancel only the awaiting caller during final saved-row read; retain the independently published acceptance receipt |
| Before wire | Cancellation during initial save leaves READY, no wire command, and existing direct draft/failure behavior |
| Real protocol serializer contention | Direct/channel handler cancellation while RESET_PATH holds the actual serializer leaves READY and preserves draft/failure behavior; release cannot dispatch the cancelled request, and explicit retry produces one wire command |
| Unobserved direct/channel response | Cancellation after entering the real session call produces UNCERTAIN, suppresses automatic replay and does not restore an unsent draft |
| Explicit radio rejection | DeviceError leaves READY and preserves existing direct draft/failure behavior |
| Non-cancellation uncertainty | Typed uncertain outcome rather than success or an unsent draft |
| Mark-read | Started writes finish before committed receipt/cancelled return; partial write cancellation remains uncommitted; already-cancelled invocation starts no writes |
| Real dispatcher replacement | ACCEPTED/UNCERTAIN actions are not re-invoked; an uncommitted cancelled action remains retryable; capacity stays 32 |
| Posted Android reply PendingIntent | RemoteInput -> real receiver/dispatcher -> App session adapter -> MessageService/MeshCoreSession/Room; replace dispatcher after acceptance/before Boolean acknowledgement and assert no second transmission |

The App witnesses cover direct/channel accepted replies, unknown direct/channel response
and cancellation before the wire with subsequent legitimate retry. Core witnesses
cover the tighter session-owned result-loss boundary. Existing transaction tests
are retained; their explicit cancellation target now refers to the outer caller
when the mark-read body deliberately runs in NonCancellable.
An additional App witness uses the actual Room store to assert the message is
read and contact unread count is zero while the final write's return is held,
then cancels the caller and checks the committed receipt after release. A
dispatcher witness separately prevents replay of committed mark-read actions.
Additional dispatcher cases preserve #495's navigation-commit cancellation and
READY retries during the last delay and after the bounded retry window.
The receipt also exposes `wireNotDispatched()`: only the protocol dispatch owner
may reset IN_FLIGHT to READY after the cancelled call settles with definite-unsent
proof. A guard regression forbids resetting UNCERTAIN or ACCEPTED. The protocol
producer prerequisite and real serializer contention cases remain required.

## Independent continuation and channel-origin limitation

The additional App channel-unknown witness uses the posted RemoteInput action,
real dispatcher/session/Room graph and an actual channel command with its response
withheld. It asserts UNCERTAIN after cancellation, no automatic retransmission by
the replacement dispatcher and no unsent-failure notification. It is authored,
not executed evidence for this consumer candidate. No producer or queue production
path was changed to add it.

The separate wrong-occupant report is not fixed by this cancellation receipt.
Trusted main `cf1ee845b3d6022990d7d699cd5bb2a373c8e456` has no persisted
message-to-channel-occupant binding. The queue owner confirmed its inspected
`c7aaff1` candidate still uses generic inbound `saveMessage` without occupant
provenance before posting, with no production `saveChannelMessage` caller.
Missing origin is rejected as `UNKNOWN_MESSAGE_ORIGIN`/`UNBOUND_HISTORY`, not
reconstructed from the current slot.

WP-401 identified its committed producer
`39389b517bf427d8b549dc00a9683dbd38d5f044`: a per-built-post UUID in the action
URI and extras prevents a later build from rewriting the retained token. It does
not supply durable reply consumption or distinguish a fresh submission from
redelivery of identical text through that token, and does not reach the current
`NotificationResponse`. Its inactive durable OPEN store is not reply provenance.
The producer/history/action-identity prerequisites remain separate. The coordinator
subsequently selected one logical quick-reply send per posted action token under
assignment `call_VVsNf3MAXXZHXhcTq68iZXxr`: exact redelivery reuses its receipt;
differing later text must be retained recoverably with an explicit fresh-action
or open-app path; an intentional identical second reply requires a fresh action.
This decision is not yet activated by the current consumer. It must not be
implemented by inventing a per-receiver UUID or using a text hash as submission
identity.

### Remaining producer and consumer boundaries

| Boundary | Actual current evidence | Required integration |
| --- | --- | --- |
| Posted reply token | WP-401's immutable per-post UUID plus action | Versioned validated envelope and durable staging of exact text before dispatch, separate from OPEN |
| Original channel occupant | Missing from actual inbound persistence path | Receive/session-owned capture persisted with the original message; unproved origin blocks transmission and retains text |
| Outgoing row | WP-208's `PendingMessageCreation` observes one process-memory row creation | Real stable outgoing identity and outcome API bound to the staged action, not an incoming-origin substitute |
| Wire outcome | `NotificationActionReceipt` is process-memory acceptance/proven-unsent/uncertain state | Durable consumer must not infer safe retry from a missing result, a FAILED row or a restarted process |
| Conflicting reply text | Existing direct `saveDraft` overwrites a session-memory map entry; channel has no equivalent durable draft | Preserve original and conflicting text without overwrite, including denied notifications and failed posting |
| Displayed channel target | WP-401/WP-214 are coordinating a distinct typed display target from the same displayed DTO | Propagate into tap callbacks and WP-302 validation; never reinterpret display identity as RF or quick-reply origin |

Trusted main `cf1ee845b3d6022990d7d699cd5bb2a373c8e456` was integrated at
`a24f1271eb9be7b027cd34ff40290df7310d040d` without changing the historical
producer-first gate. The source pin remains unchanged. Shared callback path
admissions were reconfirmed against current trusted rules; production changes
must still reconcile the actual producer and consumer owners.

## Actual local verification

Native Windows used `C:\Python312\python.exe`, Python `3.12.4`, and the inherited
same-host `ANDROID_CI_STATE`, with a fresh absolute preflight output directory.
The optional documented verification-venv interpreter is absent; the pinned
system interpreter was selected without installing or changing tooling.

- `python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh-absolute-output>`: passed readiness.
- `python tools\android-port\controller\verification_config.py --check`: passed.
- `python tools\android-port\controller\validate.py`: passed.
- `python tools\android-port\portmap.py`: passed traceability; headers are not behavioral acceptance.
- `git diff --check`: passed for the current edit set.

The shared `gradle-execution` reservation remains retained by
`local-check:f516a7e271f444dd9cb2efcc8b995a16`. No cleanup, bypass, direct Gradle,
competing acquisition, runtime installation or fabricated execution proof occurred.
Product tests have not run locally; missing execution is not a failed product test.
The original eight frozen `NotificationActionHandlerTests` configuration,
reaction-preview and channel-fallback cases and existing Kotlin counterparts
remain unchanged. Source/send helpers were inspected read-only at the fixed pin.

## Provisional hosted diagnostic result

The existing Android candidate CI workflow, runner entry, scope classifier and
toolchain pins were byte-compared with the trusted starting base and unchanged.
The feature branch was pushed explicitly unverified, then the existing
`workflow_dispatch` entrance ran the initial combined `d4c63e7` candidate.
Controller/workflow policy and the Swift codec oracle jobs passed. The Linux
Android build failed from Java heap exhaustion in the unrelated onboarding
resource-merge task after 10m43s. The log reaches `:core:services:compileTestKotlin`
without reported compilation errors in these cases, but does not establish
execution/discovery of the new service, App or dispatcher tests. No passing
behavioral count is claimed.

Workflow dispatch selects the full graph. The coordinator was asked to provide a
legitimate affected-scope hosted route for the revised producer/consumer candidate,
not to change unrelated build memory or bypass the retained local reservation.
The final exact-commit job/log/method discovery remains authoritative; do not
attach a second hashed CI-result bundle.
Robolectric/hosted results do not prove physical device, API-specific on-device
background/radio, durable delivery, signing or full WP acceptance.
