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

Subsequent exact owned corrections are reconciled without unrelated ancestry:
`9e25cbaea404613859a7a7a04e6032c995e32716` for storage result rendering,
and `7e7d2b8dc65a601bad61853f49cbcc3cf6a7b0e7` followed by
`de49a98b2a939793f66c5fa3fa31d95964a3600d` for verifier terminal
invalidation after late ledger allocation, followed by
`2d4c42d8f46a4e49e0c84e4e14aed17747c28156` for live allocation
ownership shared across verifier instances at the same canonical ledger path.
Notifications module bytes match the storage correction; verifier source/test
bytes match `2d4c42d8f`.
These corrections executed together at exact `e8d76e687` below; earlier results
remain historical.

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

## Focused evidence

`NotificationEnrollmentProducerTest` declares 16 methods covering independent request identities,
unknown/lost resume, exact prior observation, competing actions, delayed reads,
locked/corrupt/mismatched reads, dismissed/rotated callbacks, delayed staging,
uncertain/rejected returns, explicit later new attempts, delayed failed observation
after a foreground-owner replacement, and copy field boundaries.

`NotificationEnrollmentEntryTest` declares 17 methods. Sixteen mount the real entry
or Notifications settings extension. Controls cover held action, actual displayed descriptor, explicit
confirm/dismissal, pause, window focus loss, actual Activity recreation and
disposal/remount. Five integrated
cases use actual product verifier/witness/journal SQLite, real JCE HMAC and host
file/directory `FileChannel.force`: STAGED -> CONSUMED -> BOUND/reopen; both pause
and disposal before stage admission with no ledgers; both before consumption with
STAGED -> INVALIDATED and no witness/journal creation.
The class explicitly requests native SQLite and native graphics. Actual confirm
rechecks the dialog-owned view's attachment/focus and resumed lifecycle before
admitting the action; initial focus and attachment loss also retire the presentation.
The host fixture delivers the actual dialog `ViewRootImpl` focus event after
`Dialog.show`, processes that event before the queued initial-focus observation,
and asserts actual window focus. A view-only focus callback does not update the
attachment focus flag. Positive setup does not bypass the production guard;
an additional mounted case deliberately withholds initial focus and requires
retirement without acknowledgment.

The additional actual producer/adapter case controls queued invalidation across
copy replacement without claiming another mounted UI observation: unconfirmed A
is synchronously retired; only its actual asynchronous invalidation is delayed.
The same producer retains the request and all four IDs while the real backend
creates a different verifier for changed copy B. A stale A confirmation is refused.
B's actual explicit-action staging is held at `ALLOCATED`, then A's invalidation
is released and awaited against the shared ledger. The assertions require no
false corruption, followed by B's actual STAGED/CONSUMED/BOUND result for the
original immutable operation. No test adapter fabricates verification results.
This tests the coordinator's cross-verifier reachability hypothesis. Both API
variants executed successfully at exact `e8d76e687` with the owned datastore
repair. It does not claim that the earlier, uncorrected verifier executed this
schedule or that a retired action could authorize anything.

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
- Kotlin/Compose/SQLite producer execution: passed at exact `e8d76e687` below,
  including the corrected verifier/storage composition. These are host-JVM
  observations, not physical-device acceptance or activation.

| Exact candidate | Hosted run | Actual outcome |
| --- | --- | --- |
| `3fc62cb36c770a7c152eba4f9dfaad020f17a34b` | [41999079789](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41999079789) | App test compilation failed: invalid member-extension import and a helper nested in the preceding function. No mounted execution acceptance. |
| `3b360f056f27d9b5ceb1bfcc66be1b203db76eba` | [41999738888](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41999738888) | Compilation passed; 24 mounted failures across API 31/37. The unfocused host dialog caused real initial-focus retirement before warning assertions. |
| `aee0f1f0166bf29d45e1d14d1a311ed88eacea23` | [42002017581](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42002017581) | Compilation passed; the same 24 mounted failures remained. A parent composition side effect and view-only focus callback did not establish actual dialog window focus. |
| `7401b75380ba8dba4bd3ce8a972cc5e5f3473000` | [42004265838](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42004265838) | Compilation passed; 24 mounted failures. The strict root lookup showed that `Dialog.show` returns before the decor is attached; no initial root exists yet. The no-initial-focus negative control executed without appearing among failures. |
| `10c1d31e5252132b674d46c722dc8b9d32bcd2af` | [42009037096](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42009037096) | Compilation passed; 12 mounted failures remained. Warning/display and queued stale-action controls ran without failures. Five real-ledger methods needed Android Main queue progress after actual IO; the standalone focus-loss test also ran before the clicked warning had composed. No real-ledger acceptance. |
| `618ed213f5f7bb2d376b3c99a530a91fc8e942e0` | [42011995652](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42011995652) | All six jobs succeeded, including real mounted enrollment/lifecycle cases and SwiftData restore/export oracle. Original Linux job `127193153516` records every required producer method/API variant below. |
| `36f3aa83fbbb094a1bbb16e280145b4ff41a6c6e` | [42015816886](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42015816886) | Two failures among 2,062 app cases: the new cross-verifier method at both APIs timed out awaiting `ALLOCATED`. The fixture wired admission callbacks but not write callbacks; no allocation scheduling acceptance. Downstream missing notification reports were caused by app-suite abort. |
| `e8d76e687e3c83ec3041bb15930c7aa2cde456c9` | [42017808137](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42017808137) | All six jobs succeeded, including the actual allocation-gated producer schedule, corrected verifier and storage regressions, and SwiftData restore/export oracle. Original Linux job `127212729558` is authoritative. |

The original successful hosted named-case inventory was independently compared to
the exact committed producer test declarations: 16 producer identity methods,
16 mounted methods at both API variants (32), and three native Journal mapper
methods at both variants (six). All 54 required cases are present. The same
log records six witness-adapter methods and the older verifier's 21 methods at
both variants (42). This does not cover the later verifier's 26-method source.
In particular, the five actual-ledger mounted methods cover real explicit-action
consumption/binding/reopen and both pause/dispose before stage/consumption.

The candidates from `aee0f1f01` onward reconcile actual main
`2e7823cec06294dd4039000de32c98c9786f5dc5`; the five remote-node changes
were disjoint from enrollment and its runner. Each attempt uses a distinct
immutable diagnostic ref; all prior refs and runs are preserved.

The final combined execution uses the existing reviewed runner and accepted
workflow, independently of the unready PR #580 correction. Its original hosted
JSON was read in memory and independently compared to the exact committed
method declarations and Git blobs, without retaining another result bundle:

| Exact executed surface | Required named cases and result |
| --- | --- |
| Producer identity | 16/16 exact methods |
| Entry and real producer/adapter schedules | 17 methods at API 31 and default API 37: 34/34 |
| Native Journal mapper | Three methods at both APIs: 6/6 |
| Witness adapter | 6/6 |
| Corrected foreground verifier | 26 methods at both APIs: 52/52 |
| Notification messaging remap | 167 reply methods at both APIs plus six baseline cases: 340/340 |

The app suite reports 2,062/2,062, datastore 273/273, and notifications 340/340;
each has zero failures, errors or skips. Every required method/API identity is
present exactly once. Original actual compiler maps contain 276 app, 15 datastore
and ten approved notification-messaging test inputs, each matching its exact
candidate Git blob. The notification compiler map equals all ten inputs under
the approved messaging remap root; the 25 remapped build/production/test input
blobs also match Git. Other instrumentation roots are not inferred to have run.

No PR #580 source or its acceptance is inferred. The old 336-case storage
execution is not used as acceptance of the corrected 340-case source.
The final composition also reconciles actual trusted main
`663d2029ff461ce82c0befd167e2d3b627b2ea72`
(PR #575); its declaration-reader documentation and the previously reviewed
remapping documentation are retained as disjoint sections.

Hosted dispatch command:
`gh workflow run android-ci.yml --ref diagnostic-wp303-enrollment-e8d76e687-20261105`
(passed, run `42017808137`). Every earlier diagnostic ref/run remains unchanged.
The evidence-only successor does not receive a transferred exact-head CI result;
the executable-source result above stays bound to `e8d76e687`.

The exact hosted job/log is authoritative. No second CI-result bundle is retained.

## Restart-observation uncertainty successor (diagnostic-only)

The storage owner's narrow correction
`7c93360db1d1b9726e09066a3971166c41916e77` changes reconciliation
observations of missing, corrupt, unavailable, unattributed or foreign storage
to `Indeterminate(operation, reason)`. A restart cannot use those observations
as proof that a prior prepare never committed. Invalid caller nonce/budgets and
proven precommit prepare rejection remain distinct `NotRetained` outcomes.
The existing app mapper already preserves the uncertain operation; no
production mapper or coordinator algorithm change is needed.

The successor locally composes only that owned source/test/evidence delta,
the following app consumer regressions, and actual trusted main
`9b27f8b1aa6d6d37b99aa4d37646280d9917096b`. The current-main delta is
disjoint from enrollment and its runner. No unrelated custody successor or
unreviewed PR #580 runner is imported.

Native mapper controls now require uncertainty for missing history,
prepared-then-deleted storage, corrupted retained digest and foreign replacement.
Repeated observations must neither create a new database nor repair corrupt
bytes nor overwrite the foreign operation. Malformed expected nonce still fails
before allocation; a valid request observing a different disk nonce remains
uncertain, while a new `prepare` against that foreign identity stays rejected.
Seven mapper methods run at both API variants.

An eighteenth entry/producer method mounts the real UI, consumes the real
acknowledgment and obtains a real bound witness/journal, deletes only its test
generation journal, then invokes the actual reconcile button twice. Assertions
require `Indeterminate` for the same operation, exactly four allocated IDs,
disabled new-generation action, no recreated database, and unchanged
STAGED/CONSUMED acknowledgment history.

This successor is pending fresh exact diagnostic execution. Its permission is
diagnostic-only: it does not change held PR #579, publication/integration gates,
execution owners, reservations, policies or deployed runtime. Earlier successful
`e8d76e687` results do not transfer. The storage correction adds one method, so the
new notification expectation is 168 reply methods at both APIs plus six baseline
cases (342), independently checked against the actual hosted inventory.
