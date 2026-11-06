# WP-215 rejected notification-action custody: local source draft

## Status and identity

**BLOCKED / NOT BUILD- OR TEST-VERIFIED.** This is an independently admitted source
draft, not a feature-completion, dispatch, device-durability or acceptance receipt.
The new localized resource is still denied by the actual trusted policy. Its
resource identifiers in the draft intentionally remain unresolved until the
prerequisite is trusted and the exact operation is admitted. This candidate is
not currently buildable or publishable as a completed fix.

- Repository: `cbattlegear/MeshCoreOne-Android`.
- WP: `WP-215`, bounded pre-admission reply/mark-read capacity recovery for #514.
- Base: `5f4d9b92b2eb30d02f001567ef7e4f1dd6e80f66`.
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

If durable storage is full or unavailable, a separate process-memory fallback
holds at most 32 records and 1 MiB, with the same per-record limit and no eviction.
The result explicitly distinguishes a SQLite-reported commit, process-only
custody and actual failure to retain. The last case raises a content-free error
and a user-visible failure rather than claiming that a notification retap can
recover RemoteInput. Process-only drafts can be lost when the process dies;
there is no claim to the contrary.

Foreground presentation is attempted after retention, not used as proof of
custody. A missing launch intent, activity or permission is recorded separately.
An ordinary `startActivity` return is not proof Android displayed the activity.
Private text is not included in launch extras, logs, status toasts or lock-screen
notifications. The foreground dialog requests `SecureOn`.

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
old dialog cannot acknowledge a newer response, loss warning or launch failure.
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

Trusted-main admission is currently missing. The capability owner's local
`832e44f0c61b6e91062f966a80c0dc560b991a52` proposal is not edit authority and was
not imported. Its later independent clean-main proposal,
tree `0264a22afb1d3712f40715ef80126d65086c3f36` on base `5f4d9b92`, is also not
trusted admission and was not imported. The coordinator is arranging that
prerequisite. Neither the XML nor the policy has been edited in this candidate.
English-only authored copy, once admitted, will not count as reviewed
translations or complete-locale UI acceptance.

## Authored controls, not execution evidence

`NotificationActionReceiverCapacityTest.kt` contains 23 authored methods using the
real receiver, RemoteInput, dispatcher and private SQLite adapter. They cover
queue-full reply and mark-read, unchanged accepted normalization/FIFO, blank
input, exact code-unit reopening, count/byte/multibyte limits, bounded fallback
and exhaustion, unavailable storage/foreground, malformed existing state,
explicit/stale/failed disposition, drain cancellation and reader cancellation,
direct/room target round trips, private-directory denial, duplicate-ID rollback,
and stale-snapshot acknowledgment of newer retained responses or failures.

`NotificationRecoveryClipboardTest.kt` contains 13 authored App methods covering
the actual clipboard and mounted dialog: exact sensitive copy, inaccessible
clipboard, cancel/host destruction, single-record copy/discard, failed
copy/discard, the process-only warning, deferral behind another dialog and the
old-displayed-cancel/new-unseen-rejection interleaving, wrapped clipboard service
death and propagation of unrelated programming failures.

**Zero methods have been executed for this draft.** These counts come from static
source inspection, not runner discovery. A fresh store instance is not an
observed process-kill, native disk/fsync or device-restoration experiment.

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

Trust and actually admit the resource prerequisite, complete its source copy,
then obtain permission and exact-candidate affected hosted execution with
positive discovery. Build/type correctness, actual API/device receiver timing,
background-launch restrictions, secure-window/clipboard behavior, process-death
reopening and native storage durability remain unverified. A source-only local
draft is not sufficient to call the capacity-loss defect finished.
