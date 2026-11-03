# WP-215 issue #514: notification action acceptance recovery

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Bounded repair: issue #514; acceptance references `WP-215-behavior`,
  `WP-215-boundaries`, `WP-215-source-test-parity`. This is not whole-WP completion.
- Trusted starting base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
- Read-only source: `db14559b39d32322b06477c6ae676112f583db50`.
- Shared receipt contract: `bfd2418d99395e7302ff45dade2b67cba8611857`.
- Initial combined diagnostic head: `d4c63e7027469ac63a1515e66cc0fb2f3bf53697`
  (WP-208 producer `e98a1ecb633bdc1ac1b9be6d44f4483076fdbc7b` integrated).
  Hosted run: <https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41581606473>,
  attempt 1, workflow dispatch. This is a provisional diagnostic, not final
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

## Deterministic regression witnesses

The following are authored tests, **not executed passing evidence**:

| Surface | Required assertion |
| --- | --- |
| Real MeshCoreSession/MessageService, direct and channel | Firmware command returns; hold persistence tail, cancel waiting caller, retain ACCEPTED, exactly one observed wire command, no unsent draft/failure notification |
| Owned-child result handoff | Cancel only the awaiting caller during final saved-row read; retain the independently published acceptance receipt |
| Before wire | Cancellation during initial save leaves READY, no wire command, and existing direct draft/failure behavior |
| Unobserved direct/channel response | Cancellation after entering the real session call produces UNCERTAIN, suppresses automatic replay and does not restore an unsent draft |
| Explicit radio rejection | DeviceError leaves READY and preserves existing direct draft/failure behavior |
| Non-cancellation uncertainty | Typed uncertain outcome rather than success or an unsent draft |
| Mark-read | Started writes finish before committed receipt/cancelled return; partial write cancellation remains uncommitted; already-cancelled invocation starts no writes |
| Real dispatcher replacement | ACCEPTED/UNCERTAIN actions are not re-invoked; an uncommitted cancelled action remains retryable; capacity stays 32 |
| Posted Android reply PendingIntent | RemoteInput -> real receiver/dispatcher -> App session adapter -> MessageService/MeshCoreSession/Room; replace dispatcher after acceptance/before Boolean acknowledgement and assert no second transmission |

The App witnesses cover direct/channel accepted replies, unknown direct response
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

Exact-commit hosted diagnostic execution must use the existing reviewed Android
candidate CI workflow after genuine producer integration. Its real job/log/method
discovery is authoritative; do not attach a second hashed CI-result bundle.
Robolectric/hosted results do not prove physical device, API-specific on-device
background/radio, durable delivery, signing or full WP acceptance.
