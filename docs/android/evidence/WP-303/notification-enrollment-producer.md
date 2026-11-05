# Foreground notification enrollment producer (held)

<!-- android-port-wp:WP-303 -->

## Assignment and exact dependency sources

Base: `1a8b406a67d44006f14ac043ac468b6adf07a9d7`.
Reference: `db14559b39d32322b06477c6ae676112f583db50` (unchanged).
Actual project-session claim identity: `a321cd04-b5d9-449a-9adf-9be0a560c77b`.
App conversation/runtime folder identity: `c8e06c81-e88c-429e-97af-e85f06bbfa19`.
These distinct identifiers are not substituted for the historical lease owner.

The original WP-303 producer confirmed no retained overlapping hunks. Supported
same-owner scope evolution and advisory transfers admit the distinct producer,
entry, backend, journal mapping, test helper/tests and two existing Settings
consumer paths. Historical audit bindings and authorization prose are unchanged;
transfer is attribution, not a persistent lease-owner rebind.

Dependency ancestry is deliberately limited to:

- Actual verifier/witness mapper and corrected durable witness:
  `834aaf6374e67b0b2f74bd008501264554201e72`, including reviewed witness
  `b75555a0b22e493a2a9117f25e1fcfde871ee60c`.
- Real notification journal:
  `b0af043503763bc8de22b2d950a7f524c77a966c`, including remapped test-runner
  support `854785a40aa84163deafc6176747e16ec23b61ff`.
- Existing merged coordinator from PR #571. Its algorithm is unchanged.

The actual witness PR #576 subsequently landed as trusted-main merge
`59ee802c3d1067446b6bd2c99299a05e1f299a57`, whose second parent is exactly
`b75555a0b22e493a2a9117f25e1fcfde871ee60c`. That merge provenance is reconciled;
the original candidate's execution evidence is not transferred to a changed head.

No WP-308/#530 source is imported or edited.

## Real surface and request lifetime

The actual app call path is `AppSettingsContent` -> `NotificationSettingsScreen`
-> app-owned `NotificationEnrollmentEntry`. It does not depend on the system
notification permission being granted, and does not navigate away or block mesh
messaging. Existing feature-only consumers keep their default empty extension slot.

Only the user's new-generation action reads the current witness and allocates
independent operation ID, store ID, creation nonce and acknowledgment ID. The
immutable request captures the complete observed prior witness (or unknown/lost,
never a first-install claim). The exact four displayed title/body/confirm/cancel
strings are length-prefixed in order using UTF-16 lengths without normalization,
then supplied to the actual verifier descriptor
`notification-enrollment-new-generation`, version 1.

The actual dialog confirm handler alone stages the real acknowledgment. Only a
STAGED result reaches the existing coordinator's `enroll`. The production backend
uses the real durable verifier and witness mapper; the journal mapping calls the
real generation store with the retained store ID and exact tuple/nonce/receipt.
No fake production consent, witness or journal is supplied.

Process-owned state survives a settings composition/rotation, not process death.
Pause, dialog-window focus loss, dismissal, copy replacement and disposal retire
the opaque presentation synchronously before scheduling invalidation. The
dialog's own window is observed: opening a dialog legitimately removes focus
from the activity window. Competing mounted owners and stale dialog callbacks
cannot confirm another owner's presentation.

An unattempted presentation can be shown again with the same request after
retirement. Any admitted, declined or uncertain action requires durable resume,
not another `enroll` or another identity. Only a subsequent **explicit new**
action, after durable recovery returned `NeedsAcknowledgment` and a fresh
observation still reports unknown/lost, may abandon the old tuple. Resume itself
never allocates. A conflicting recovered operation is `Superseded`, not success
for the original attempt.

## Explicit activation holds

The mounted production entry is held (`AppContainer.notificationEnrollment` is
null); its explanatory copy and disabled action are not an active enrollment.
The backend and real journal mapping require actual collaborators and explicit
limits. No retention, receipt, issuance or unbound-text budgets are selected.
Current-generation fencing across authenticated external witness and journal,
and supported publication admission remain separate integration gates.

No receiver, notification display, PendingIntent, issuance, publication, queue,
send, message delivery or automatic recreation is activated. A BOUND observation
is not restored historical data, antirollback, RF origin or dispatch authority.
No native Keystore/device or physically lost-data recovery is claimed.

The app-local XML warning resource was admitted using
`reserve.py admit --wp WP-303 --path android/app/src/main/res/values/notification_enrollment.xml
--operation add-or-update-notification-enrollment-copy` after trusted-main
merge `5640a3cf238a0c08f57f77fb78175964fdd6cdf6` of PR #577.
Its exact ten approved keys are present. Frozen Swift/generated localization inputs
are not modified; English fallback is not claimed to be reviewed translation.

## Focused evidence (source until executed)

`NotificationEnrollmentProducerTest` declares 16 methods covering independent request identities,
unknown/lost resume, exact prior observation, competing actions, delayed reads,
locked/corrupt/mismatched reads, dismissed/rotated callbacks, delayed staging,
uncertain/rejected returns, explicit later new attempts, delayed failed observation
after a foreground-owner replacement, and copy field boundaries.

`NotificationEnrollmentEntryTest` declares 15 methods and mounts the real entry and Notifications settings
extension. Controls cover held action, actual displayed descriptor, explicit
confirm/dismissal, pause, window focus loss, actual Activity recreation and
disposal/remount. Five integrated
cases use actual product verifier/witness/journal SQLite, real JCE HMAC and host
file/directory `FileChannel.force`: STAGED -> CONSUMED -> BOUND/reopen; both pause
and disposal before stage admission with no ledgers; both before consumption with
STAGED -> INVALIDATED and no witness/journal creation.
The class explicitly requests native SQLite and native graphics. Actual confirm
rechecks the dialog-owned view's attachment/focus and resumed lifecycle before
admitting the action; initial focus and attachment loss also retire the presentation.

The test-only Kotlin helper narrowly suppresses internal visibility diagnostics
to use datastore's existing constructor seams without widening production APIs
or changing the build graph. It does not return fabricated verification results.
This is host-JVM evidence, not AndroidKeystore, hardware or device durability.

`NotificationEnrollmentJournalAdapterTest` declares three native SQLite methods at API 31/37 to
exercise missing reconcile without creation, exact receipt/nonce/witness/revision
round trip, foreign witness rejection and changed nonce rejection.

- `python tools/android-port/controller/validate.py`: passed.
- `python tools/android-port/portmap.py`: passed.
- `git diff --check`: passed.
- Local Gradle: not run, per retained hard execution owner.
- Kotlin/Compose/SQLite test execution: pending exact complete hosted candidate.

The exact hosted job/log is authoritative. No second CI-result bundle is retained.
