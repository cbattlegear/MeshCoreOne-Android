# WP-215 rejected notification-action custody: local source draft

## Status and identity

**BLOCKED / NOT BUILD- OR TEST-VERIFIED.** This is an independently admitted source
draft, not a feature-completion, dispatch, device-durability or acceptance receipt.
The resource prerequisite has now landed and its eleven English keys are present
after fresh trusted admission. Resource resolution is no longer a known missing
input; compilation and hosted test execution remain unperformed.

- Repository: `cbattlegear/MeshCoreOne-Android`.
- WP: `WP-215`, bounded pre-admission reply/mark-read capacity recovery for #514.
- Base: `5f4d9b92b2eb30d02f001567ef7e4f1dd6e80f66`.
- Landed policy reconciled: `a6c77661f07bd7a43eb97da9ec4ed8e2b0ada866` (#620).
- Swift specification: `db14559b39d32322b06477c6ae676112f583db50`, unchanged.
- Branch: `wp215-notification-capacity-recovery-20261106`, same isolated worktree.
- Producer session: `a830aafb-8aa1-4bf3-8692-c5bf1c017e70`.
- Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_2Ofm4TOIT6aG2sMekEG1GBgm`.
- Candidate: the local source commit containing this record; exact commit and
  changed blob identities are supplied in the coordinator handoff, not a
  self-referential hash inside this file.

## Bounded behavior

Only an actual dispatcher `REJECTED_CAPACITY` for `REPLY` or `MARK_READ` enters
recovery. Accepted replies keep their existing trim behavior. The recovery copy
instead retains the original RemoteInput UTF-16 code units, whitespace, typed
payload, original message identity, action and radio/channel reference. The
accepted dispatcher object is unchanged, including its 32-item FIFO, retry,
installation, cancellation and head-removal behavior. This repair does not
silently fix or import the separately held dispatcher proposal.

Recovery is separate from accepted/uncertain action receipts. It never enqueues,
transmits, marks read or opens the target conversation automatically. A channel
index is a reference from the original notification, not evidence that a
replacement channel occupies the same slot. Copy is manual recovery, not
permission to replay. Duplicate platform deliveries can create duplicate drafts;
no authenticated notification-token deduplication or exactly-once claim is made.

The private SQLite file is under the app's credential-protected `noBackupFilesDir`.
It requests full synchronous transactions, has no destructive migration or
automatic reset, and limits logical custody to 32 records, 128 KiB UTF-8-accounted
payload/text per record and 1 MiB total. Original text is stored as UTF-16 code-unit
bytes rather than normalized by a text encoder. SQLite page growth is capped at
1,024 pages; page size and journal overhead are not conflated with the logical
1 MiB content budget. No private text or exception message is logged.
Both SQLite opening paths install a non-destructive corruption handler rather
than Android's default handler, which can delete the corrupt database. A corrupt
file is a typed unavailable-storage result, not an empty replacement store.

If durable storage is full or unavailable, a separate process-memory fallback
holds at most 32 records and 1 MiB, with the same per-record limit and no eviction.
The result explicitly distinguishes a SQLite-reported commit, process-only
custody and actual failure to retain. The last case raises a content-free error
and a user-visible failure rather than claiming that a notification retap can
recover RemoteInput. Process-only drafts can be lost when the process dies;
there is no claim to the contrary.

Rejected-action storage work now runs on a separate single-worker FIFO after
`goAsync()`, not on the receiver main thread. The handoff independently bounds
queued plus running work to 32 entries, 128 KiB per entry and 1 MiB total using
the same logical UTF-8 accounting. Overflow/oversize input is explicitly not
retained; no older entry is evicted. Status/generation publication uses a separate
short lock that never encloses SQLite work. Accepted dispatcher behavior is
unchanged.

Each admitted handoff schedules an eight-second nominal deadline on a separate
executor and finishes the actual broadcast pending result exactly once, either
after work settles or at that deadline. The deadline does not cancel a possibly
committing SQLite call, release its occupied handoff budget, claim loss, or claim
durable retention. It republishes the manual-open requirement and logs a
content-free unconfirmed-outcome warning. The captured original input stays in
the bounded worker queue while that process survives; it is not a committed
recovery record and is not yet available in the recovery dialog. The later
retention result alone chooses the saved/process-only/not-retained status.
Finishing a broadcast removes its process-lifetime protection: process death
before actual custody can lose an in-flight or queued original, and a commit
interrupted at that boundary can have an unknown outcome. Scheduling an
eight-second callback is not a hard real-time guarantee or proof against ANRs.
Native receiver timing and process-kill evidence remain separate gates.

The foreground host also reads a status-only snapshot without the custody/storage
lock while a complete read is pending. `recordsAvailable = false` is explicitly
not an empty-record result. The status-only pending surface has no items or
copy/discard controls; a completed failed read can expose known process-memory
items and their explicit copy/discard controls without claiming disk completeness.
The localized storage notice describes unavailable/busy records and an unconfirmed pending
save. A generation change cannot hide newly published loss/manual-open warnings
behind a blocked database read. Closing this incomplete surface hides only that
observed unavailable generation locally; it acknowledges no flags or unseen
records, and a later complete snapshot can display the actual originals.
Pending and completed-failed read surfaces have distinct dismissal identities at
the same generation, so closing the former cannot hide newly discovered
process-memory text in the latter. The generic recovery title does not assert
that a rejected action exists merely because a signal-free storage read failed.
Per-host reads are serialized with a cancellable mutex to avoid accumulating
blocked IO readers on each status update.
An initial pending read with no actual custody/failure signal shows no warning
dialog. A failed storage read remains `recordsAvailable = false`, even if
process-memory items are available; dismissing it cannot acknowledge unknown
durable records or clear loss/manual-open flags.
Successful Copy/Delete does not assign a dismissal generation at all: disposal's
own refresh drives the next snapshot. An older operation cannot hide a concurrent
loss warning by reading the latest collected generation after its IO resumes.
Busy/error state belongs to the mounted host rather than the current item key.
A status-only replacement cannot re-enable another disposition or abandon an
in-flight operation's failure. That failure stays visible when the original
record returns; starting another operation or explicitly closing clears the
displayed operation error, not the underlying custody.
Temporary competing-dialog deferral occurs only after the operation's remembered
state and scope are established, so it hides recovery without cancelling an
already running disposition. Both action callbacks also recheck the current busy
state before launch; disabled presentation alone is not duplicate admission
protection against a retained enabled callback.
Admission also checks the latest mounted visible item/revision and the live
custody revision before any clipboard access or IO launch. A retained callback
for an already disposed item, a hidden surface, or a revision changed before
recomposition is rejected. An internal synchronous admission observer lets
mounted controls count launches without waiting speculatively for duplicate IO.

Recovery does not start an activity from the reply/mark-read broadcast: Android 12
prohibits notification trampolines, and background-launch denial need not throw.
Instead the content-free status tells the user to open MeshCore One themselves.
The existing foreground host loads recovery when opened or resumed. Neither
manually opening the app nor a status toast is proof of durable custody.
Private text is not included in launch extras, logs, status toasts or lock-screen
notifications. The foreground dialog requests `SecureOn`.
The original null-action content-tap path is unchanged here, not claimed repaired
or reconciled with held #495.

Platform references:

- <https://developer.android.com/about/versions/12/behavior-changes-12#notification-trampolines>
- <https://developer.android.com/guide/components/activities/secure-bal>
- <https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs#broadcast-receiver-timeout>

The foreground surface offers only explicit copy, delete and cancel. Cancel,
Back/dismissal, host destruction and cancelled reading do not dispose records.
Copy uses a sensitive clipboard item and checks the exact copied text before
requesting deletion of that one record. If clipboard access or deletion fails,
clipboard failure requests no deletion; an unconfirmed disposal is surfaced as
uncertain, not proof the record still exists. Failures before the deletion
transaction commits preserve the original. There is no
automatic aging, replacement of old drafts, channel resampling or accepted-action
replay.

Snapshots carry the actual version observed under the custody lock. A dialog can
acknowledge failure flags only if that exact version is still current. Closing an
old dialog cannot acknowledge a newer response, loss warning or manual-open requirement.
Recovery waits behind existing connection-recovery dialogs without changing
custody or acknowledging its unseen state.

Before a durable insert, the serialized target must decode to the exact original
typed payload. Private-directory denial is classified inside the typed storage
boundary rather than thrown while constructing the adapter. Duplicate recovery
IDs are corruption; a disposition affecting anything other than one row rolls
back its transaction instead of deleting multiple rows.

## Actual admissions and ownership

The trusted `reserve.py admit` command succeeded on the base above for:

| Path | Operation | Capability |
|---|---|---|
| `android/platform/notifications/src/main/kotlin/com/meshcoreone/android/platform/notifications/messaging/NotificationActionReceiver.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/MainActivity.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/platform/notifications/src/androidTest/kotlin/com/meshcoreone/android/platform/notifications/messaging/NotificationActionReceiverCapacityTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `android/app/src/test/kotlin/com/meshcoreone/android/app/NotificationRecoveryClipboardTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `android/platform/notifications/src/main/res/values/notification_action_recovery.xml` | `add-or-update-notification-action-recovery-copy` | `reviewed-support-notification-action-recovery-copy` (fresh admission after landed #620) |
| `docs/android/evidence/WP-215/notification-intake-capacity-recovery.md` | `record-evidence` | `wp-owned` |
| `docs/android/deviations/WP-215.md` | `record-evidence` | `wp-owned` |

Supported `transfer` outputs preserve the historical WP-215 attribution and
identify this isolated producer; they do not evolve or reassign another owner's
lease. The historical claim remains untouched. Generic `modify` was rejected and
was not used as authority.

The action/replay owner confirms no receiver/MainActivity intake-recovery edit.
The held #495 owner confirms that its `80865c2` intake and capacity-result
semantics are unchanged and that this intake-only boundary has no direct
dispatcher-state-machine overlap. Actual composition must still verify that only
rejected actions enter recovery. No held #495 source was edited/imported; #515 was
not inspected. Posting/PendingIntent token mutation is a separate boundary, not
fixed or claimed here.

## Resource prerequisite

The exact required source path is:

`android/platform/notifications/src/main/res/values/notification_action_recovery.xml`

with operation `add-or-update-notification-action-recovery-copy`, WP-215 only.
Its eleven `notification_action_recovery_` keys are `title`, `rejected_reply`,
`rejected_mark_read`, `saved`, `memory_only`, `not_retained`, `storage_unavailable`,
`copy_failed`, `discard_failed`, `launch_failed` and `original_target`.
Only `original_target` has a formatter: exactly one `%1$s`.

Historically, trusted-main admission was missing. The capability owner's local
`832e44f0c61b6e91062f966a80c0dc560b991a52` proposal is not edit authority and was
not imported. Its later independent clean-main proposal,
tree `0264a22afb1d3712f40715ef80126d65086c3f36` on base `5f4d9b92`, is also not
trusted admission and was not imported. PR #620 subsequently landed normally at
`a6c77661f07bd7a43eb97da9ec4ed8e2b0ada866`; the existing branch reconciles that
actual main history, not a proposed policy tree. A fresh exact WP/path/operation
admission returned `reviewed-support-notification-action-recovery-copy` before
creating the product XML. Its actual UTF-8 bytes pass the landed immutable support
validator. No policy was authored in this lane. English-only authored copy does
not count as reviewed translations or complete-locale UI acceptance.

## Authored controls, not execution evidence

`NotificationActionReceiverCapacityTest.kt` contains 29 authored methods using the
real receiver, RemoteInput, dispatcher and private SQLite adapter. They cover
queue-full reply and mark-read, unchanged accepted normalization/FIFO, blank
input, exact code-unit reopening, count/byte/multibyte limits, bounded fallback
and exhaustion, unavailable storage/foreground, malformed existing state,
explicit/stale/failed disposition, drain cancellation and reader cancellation,
direct/room target round trips, private-directory denial, duplicate-ID rollback,
and stale-snapshot acknowledgment of newer retained responses or failures. The
real receiver control captures all attempted activity starts and requires none
for rejected-action recovery while original private custody remains available.
Separate reply and mark-read controls assert this boundary. A corrupt-byte
fixture asserts the database file is preserved and new text uses process-only
fallback rather than silent recreation.
Receiver controls now use registered ordered broadcasts and wait for their real
pending-result completion instead of invoking rejected `onReceive` calls
directly. A gated private-directory lookup asserts off-main execution, return
before storage settles, and completion only after release. Additional handoff
controls cover FIFO/count bounds, original input at the receiver boundary,
byte/oversize rejection, and deadline completion without cancellation, premature
budget release or double finish. These are authored controls, not evidence that
the Android receiver deadline has been exercised on a device.

`NotificationRecoveryClipboardTest.kt` contains 28 authored App methods covering
the actual clipboard and mounted dialog: exact sensitive copy, inaccessible
clipboard, cancel/host destruction, single-record copy/discard, failed
copy/discard, the process-only warning, deferral behind another dialog and the
old-displayed-cancel/new-unseen-rejection interleaving, wrapped clipboard service
death and propagation of unrelated programming failures.
A mounted blocked-storage control gates an actual custody call, observes the
independently published manual-open/loss status, invokes a stale unavailable
dialog callback, requires no disposition/acknowledgment, and then releases storage
to require both actual originals.
Two further mounted controls require no warning during a signal-free gated
initial read and no flag acknowledgment after a failed storage read.
Four later controls cover same-generation pending-cancel/failed-read process-copy
visibility, an honest signal-free completed-failure title, and blocked Copy and
Delete with a newly published loss before completion. Read-state test tags ensure
the failed-read acknowledgment control waits for the mounted completed read,
rather than accidentally cancelling its indistinguishable pending predecessor.
The Copy/Delete success controls now hold the Compose frame clock while publishing
the loss and releasing the older disposition, require the disposal refresh before
any warning frame, then require the newer warning after frames resume. A fail-once
mounted Delete control explicitly observes item-to-status-only replacement and
disabled Cancel while blocked, then requires the failure and original record
together after storage resumes.
The paused-frame controls witness the IO-side disposal refresh, not independently
the later UI coroutine continuation settling before that frame; no stronger
execution claim is made. Three additional mounted declarations cover a
competing-dialog hide/unhide during gated failed Delete (busy remains set and
the later failure is shown), plus same-event and retained-callback duplicate
Copy/Delete invocation while real IO is gated.
Four further controls cover settled stale Copy/Delete after the next item appears,
idle retained callbacks during competing-dialog deferral, and a live revision
change in the same event before recomposition. Synchronous admission counts also
strengthen the duplicate-in-flight controls; a rejected callback must not launch
an operation at all, independent of IO scheduling.

**Zero methods have been executed for this draft.** These counts come from static
source inspection, not runner discovery. A fresh store instance is not an
observed process-kill, native disk/fsync or device-restoration experiment.
The initial `8fcfd4dff` corrupt-byte method was mistakenly nested inside the
existing-version test. The follow-up moves it to class scope while preserving
the original test body. A static comment/string-stripped brace-depth check now
finds all 25 receiver and 13 App `@Test` declarations at class depth. This repairs
source structure; only later authorized hosted runner discovery can establish
which methods actually execute.
That 25-method count describes the discovery-fix commit; the off-main follow-up
adds four receiver declarations, for 29 receiver and 13 App controls, still zero
executed.
The independent-status follow-up adds one mounted App declaration, for 29 receiver
and 14 App controls; none have executed.
The startup/failed-read follow-up adds two App declarations, for 29 receiver and
16 App controls; none have executed.
The same-generation/disposition follow-up adds four App declarations, for 29
receiver and 20 App controls; none have executed.
The operation-state follow-up adds one App declaration, for 29 receiver and
21 App controls; none have executed.
The deferral/admission follow-up adds three App declarations, for 29 receiver and
24 App controls; none have executed.
The stale-visible-action follow-up adds four App declarations, for 29 receiver
and 28 App controls; none have executed.

Actual allowed static checks:

- `git diff --check`: passed.
- A read-only comparison of the complete `NotificationActionDispatcher` object
  with the base, after newline normalization: identical.
- `reserve.py admit` / advisory `transfer` commands above: succeeded for the
  stated paths/operations only. This is capability evidence, not Kotlin execution.
- `python tools\android-port\controller\validate.py`: passed; pinned manifest,
  source ownership and DAG valid. This does not compile candidate Kotlin.
- `python tools\android-port\controller\verification_config.py --check`: passed;
  configuration valid, feature verification remains unconfigured. No feature or
  dependency acceptance is implied.

An independent source-only review of the original local `571fd742f` draft found
the stale-dialog acknowledgment interleaving. The successor source repairs that
finding with version-bound snapshots/conditional acknowledgment and deterministic
authored controls. The original review is not a review of the successor bytes.

No Gradle, Android, hosted test or instrumentation command was run. The retained
execution reservation `local-check:f516a7e271f444dd9cb2efcc8b995a16` was not cleared,
bypassed or contended for. No hosted dispatch, push, merge, activation, policy or
protected-setting change occurred. Current WP-215 manifest verification remains
unconfigured; this document does not invent replacement verification commands.

## Remaining gates

The resource prerequisite and source copy are now present. Obtain delegated
exact-candidate affected hosted execution with
positive discovery. Build/type correctness, actual API/device receiver timing,
background-launch restrictions, secure-window/clipboard behavior, process-death
reopening and native storage durability remain unverified. A source-only local
draft is not sufficient to call the capacity-loss defect finished.
