# WP-215 issue #514: notification action acceptance recovery

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Bounded repair: issue #514; acceptance references `WP-215-behavior`,
  `WP-215-boundaries`, `WP-215-source-test-parity`. This is not whole-WP completion.
- Trusted starting base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
- Read-only source: `db14559b39d32322b06477c6ae676112f583db50`.
- Shared receipt contract: `bfd2418d99395e7302ff45dade2b67cba8611857`.
- Candidate head and authoritative hosted result: pending real WP-208 integration
  and exact-candidate execution; no Kotlin execution is claimed at this stage.

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
the wire/owned-child boundary. Its implementation is required before integration
can be claimed.

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

Exact-commit hosted diagnostic execution must use the existing reviewed Android
candidate CI workflow after genuine producer integration. Its real job/log/method
discovery is authoritative; do not attach a second hashed CI-result bundle.
Robolectric/hosted results do not prove physical device, API-specific on-device
background/radio, durable delivery, signing or full WP acceptance.
