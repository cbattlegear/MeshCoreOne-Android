# WP-215 independent current-main notification action consumer

## App fixture authorization correction

Exact hosted run `41966525445` at `67ac4eb224d2fa1da784fc125a9bc36d5d47838e`
failed `:app:testDebugUnitTest`: 1,984 tests, two failures. The channel pre-wire
control expected one failure notification but its `RecordingDelivery` always
reported DENIED. The assertion stopped before installing the replacement router,
leaving a retryable process action that contaminated the later equal-text draft
control (three router attempts instead of two). This is not evidence that the
equal-text draft was deleted or that a third wire send occurred.

The fixture now explicitly supplies AUTHORIZED delivery for these reply controls,
refreshes and asserts the service authorization, and retains the original
one-failure-notification assertion plus an exact channel-failure payload assertion.
Other harness users still default to DENIED. No production authorization or
dispatcher behavior changes. The successor must execute both failed controls
before either can be accepted.

The authoritative same-run reports discovered 18 passing
`NotificationAcceptanceRecoveryTests` cases and 31 passing
`NotificationTransactionNativeTests` cases, including the unchanged
cancel-after-write/remove/badge/notify oracle. The overall run still failed;
these service results do not accept the App controls or capacity recovery.
No second CI-result bundle is retained.

Fresh actual admissions for `ContainerTestSupport.kt` and
`NotificationActionAcceptanceTest.kt`, operation `add-or-update-related-test`,
returned `reviewed-support-test`. Same-owner scope evolution for the support
fixture again refused with
`Capability evolution caller differs from the stored session/branch/worktree`;
the historical receipt was preserved unchanged under the isolated-authoring
policy below. The initial mistaken `controller/reserve.py` invocation did not
run; actual admissions used `tools/android-port/local/reserve.py`.

After these source changes, `validate.py`, `portmap.py` and `git diff --check`
passed. Local Gradle ownership remains untouched. Full-capacity intake,
installation continuity, atomic issued admission and visible copy-only failure
handling remain separate unactivated integration prerequisites.

## Identity and provenance

- Repository: `cbattlegear/MeshCoreOne-Android`; existing issue: #514.
- Base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
- Candidate: the immutable Git commit containing this evidence; exact publication
  SHA and authoritative hosted results belong in the PR and coordinator handoff.
- Pinned Swift reference: `db14559b39d32322b06477c6ae676112f583db50`, unchanged.
- Source: `MC1Services/Sources/MC1Services/Services/NotificationActionHandler.swift`;
  original tests: `MC1Services/Tests/MC1ServicesTests/Services/NotificationActionHandlerTests.swift`.
  Existing policy/transaction assertions remain unchanged.
- Manifest SHA256: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
- Actual retained attempt/session: `c9bd4093-269f-48d9-afd2-5684c2b4d2ce`,
  reservation revision 7, historical branch `cbattlegear-notification-action-recovery`.
- Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_WInAN0ovntBFoC5dc8NUbu5e`.
- Authorized clean branch: `cbattlegear-notification-recovery-current-main`.
  The original branch still preserves `0f17a0a338a20867b0b8b43045a04b5fdfa9f702`.
  No rebase, reset, held-history import, producer edits or ledger mutation occurred.

Actual main includes receipt prerequisite #537 merge
`a6cb5d84ee6853311159747293c07e285a8b70dc` and messaging #563 merge
`5b1fa7bede0fa21c76cc6736dd6ae6b54b1532e6`. The handler and dispatcher on
this base still lacked the independent consumer correction. WP-208 confirms its
source-only #569 changes are not a dependency of this consumer.

## Scope, admissions and concurrency

Trusted `reserve.py admit --wp WP-215 --path <path> --operation <operation>`
actually returned:

| Path | Operation | Capability |
| --- | --- | --- |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/notifications/NotificationActionHandler.kt` | `modify` | `wp-owned` |
| `android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/notifications/NotificationAcceptanceRecoveryTests.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `android/platform/notifications/src/main/kotlin/com/meshcoreone/android/platform/notifications/messaging/NotificationActionReceiver.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/platform/notifications/src/androidTest/kotlin/com/meshcoreone/android/platform/notifications/messaging/NotificationActionDispatcherTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `android/app/src/test/kotlin/com/meshcoreone/android/app/container/NotificationActionAcceptanceTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `docs/android/deviations/WP-215.md` | `modify` | `wp-owned` |
| `docs/android/evidence/WP-215/notification-action-recovery.md` | `create` | `wp-owned` |

An evidence admission initially failed when Git could not allocate memory; the
same exact admission subsequently passed without changing policy or arguments.
The attempted same-owner `reserve.py evolve` on the receiver refused with
`Capability evolution caller differs from the stored session/branch/worktree`.
That actual refusal and historical receipt are preserved. Trusted
`docs/android/AUTOMATION.md` lines 15-23 and 239-247, reaffirmed by the coordinator,
permit capability-admitted isolated authoring despite historical advisory binding.
This does not grant dispatch, successor, merge or acceptance authority.

The navigation owner confirms its active consumer work does not edit dispatcher,
receiver or handler acceptance. The producer's held notification snapshot/post and
private Activity paths are untouched. MessageService remains solely WP-208-owned.
No new agents or sessions were launched.

## Implemented behavior and authored controls

The handler uses the merged process-memory receipt across the session-owned send
boundary. ACCEPTED and COMMITTED are distinct; a command response is not a recipient
ACK. A known-accepted reply is not restored as unsent when caller cancellation hides
the result. Definitely-unsent cancellation retains the existing direct draft/channel
failure behavior and retry eligibility. A non-cancellation unknown outcome is typed
`NotificationReplyOutcomeUncertain`; cancellation still propagates.

The dispatcher retains one receipt per queued action and serializes old/new router
delivery. Boolean acknowledgement is recorded inside its context before cancellable
handoff. Unknown wire outcomes are moved to process-only quarantine, not rerouted,
silently discarded or allowed to block the ready FIFO. Quarantine counts toward the
same 32-record bound, and capacity refusal remains explicit. It has no durable or
user-visible recovery claim. Separate OS redelivery is not deduplicated.

RemoteInput keeps leading/trailing whitespace and newlines in admitted replies.
Blank-only input remains rejected. Mark-read finishes started local writes and marks
commitment only after all return; pre-cancelled work never begins, partial failure
does not manufacture commitment, and no transaction/occupant authority is invented.

Authored test methods (not execution counts):

- 13 core service methods use the existing real session/protocol harness for direct
  and channel acceptance, serializer-held non-dispatch, lost firmware responses,
  caller handoff, device rejection, typed uncertainty and committed-read controls.
- 7 App methods use posted RemoteInput PendingIntents, actual container/session
  service and Room; direct/channel accepted, unknown and pre-wire outcomes plus a
  committed-write cancellation witness. This is not device or RF evidence.
- 7 new platform methods preserve the original 2 assertions/methods and cover raw
  text, replacement, uncertainty without head-of-line loss, retained capacity,
  ordinary queue capacity and cancellation at Boolean-result handoff.

Acceptance IDs addressed: `WP-215-behavior`, `WP-215-boundaries`; these additional
controls do not independently establish all source-test parity or complete WP-215.

## Actual local verification

Native Windows used pinned `C:\Python312\python.exe` (3.12.4) and the existing
same-host `ANDROID_CI_STATE`. Before any product verification:

```powershell
& C:\Python312\python.exe tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output C:\Users\camer\AppData\Local\MeshCoreOne\verification\wp215-current-main-20261104-2122
```

Result: readiness BLOCKED. The pinned Java executable could not allocate G1 virtual
space; Windows reported insufficient paging-file memory. Preflight did not run
product tests. Only the two specifically generated repository-local JVM crash logs
were removed. No process was killed and no toolchain/SDK was installed or changed.

Actual completed commands:

- `C:\Python312\python.exe tools\android-port\controller\verification_config.py --check`: passed.
- `C:\Python312\python.exe tools\android-port\controller\validate.py`: passed.
- `C:\Python312\python.exe tools\android-port\portmap.py`: passed; headers are not acceptance.
- `git diff --check`: passed for the authored source changes.

An attempted batched `--check` on validate/portmap was rejected as unsupported;
the actual documented invocations above subsequently passed. No invented Gradle
tasks or alternate local launcher were used.

The retained local Gradle execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged. No cleanup, bypass,
lock release or Gradle contention occurred. Local product discovery remains zero
because it did not run, not a passing zero-test result. Exact-commit hosted job
results/logs are authoritative; no duplicate CI-result bundle is retained here.

## Concrete remaining integration boundaries

The sole journal owner supplies clean-main extraction
`e050e9a9b9561caf53d61b95c749320c944c1a77`, parent `41552c85`, as local,
unaccepted source. It is not imported. `read()` is side-effect-free inventory;
all targets remain UNPROVED; stable outgoing UUIDs and claims are journal custody,
not send authority. Its fresh-store proof, explicit budgets, trustworthy ingress
and recovery UX are external prerequisites.

Recovered-draft UI owner `aa4b9d95-ca8d-4023-bb04-049e969e6d6c` owns the
separate list/copy integration. Its held timeline files were neither inspected nor
edited. The required consumer policy is explicit copy to a selected chat, preserving
unknown/replaced-target text without silent send, adoption, relabel or deletion.
The actual copy-only API must be coordinated before wiring it here.

No journal initialization, staging, durable settlement, automatic replay or
notification retirement is activated. Genuine ingress provenance, immutable original
contact/occupant authority, conditional action mutation and actual radio dispatch
authority remain absent. Six-byte direct-prefix collisions (#559) are not solved by
full-key equality; channel DTO/occupant snapshots are not dispatch locks or proof
against unobserved ABA. Existing ordinary positive actions are unchanged.

Process-only quarantine can still be lost on process death and is not the selected
durable recovered-draft UX. No exactly-once, durable delivery, physical API31/37,
radio, license, signing or iOS compatibility verification is claimed.

## Source-review follow-up (uncommitted, unexecuted)

Coordinator assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_iB5qkpW9T4XxqRBVEymoGiso`.
The original c042 commit is not amended or rewritten. These are working-file source
corrections only, not a new immutable candidate or claimed acceptance.

1. The pre-wire App control no longer consumes the restored direct draft before
   automatic retry. NotificationService now retains exact receipt ownership beside
   its existing pending-draft map; successful acceptance clears only a draft still
   owned by that action. Public saves revoke ownership, including equal-text saves.
   Three real-session controls and two additional App controls cover no consume,
   newer text and newer identical text. Original source assertions remain.
2. A separate `NotificationReadReceipt` appended beside the canonical wire receipt
   tracks READY/STARTED/COMMITTED/UNCERTAIN and returned local-write calls. The
   canonical wire class and transitions are unchanged. The handler records
   uncertainty in a finally block before existing containment can return normally;
   the dispatcher carries both receipts and retains an uncertain read even if the
   router returns true. No blind unread-clear retry or fake wire evidence is used.
   Added one real Room failed-second-write App control, two service failure
   controls and one dispatcher retained-capacity control.
3. Full process custody still makes the receiver's ignored REJECTED_CAPACITY a
   hard integration blocker. No durable intake, visible full-capacity recovery,
   infinite memory fallback or eviction has been fabricated. The producer and
   recovery owners were informed; no journal activation follows from this fix.

Two additional existing production paths are involved:

- `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/notifications/NotificationService.kt`
  (draft methods/metadata only; no notification posting, snapshot, removal or held
  private-Activity changes);
- `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/notifications/NotificationActionReceipt.kt`
  (append-only independent local-read class; original wire class unchanged).

Both map to the current manifest's WP-215-owned notification directories, but
fresh actual CLI admission/evolution and exact successor review remain **pending**:
the coordinator explicitly prohibited process retries during host exhaustion.
No historical #537 acceptance is inherited. Receipt owner
`a830aafb-8aa1-4bf3-8692-c5bf1c017e70` confirms no active producer edits since
#537 merged; its earlier overlap warning referred to historical ownership only.
WP-401 was asked to flag actual draft-method overlap. No ledger was changed.

Read-only inspection checked actual constructor/callback signatures and preserved
the original controls. No executable/static-validator rerun, independent Git
identity recheck, commit, push or hosted test was attempted for this successor.
The prior c042 static results do not validate these additional edits.

## Resumed correction candidate after host readiness recovery

The preceding uncommitted/no-execution status is historical, not a result for the
successor. Under assignment
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_udnqj5HioGvH3zIWgs5dcCqY`,
lightweight Git confirmed HEAD c042 and all nine modified files intact. Nothing was
reset, switched, discarded or imported. This successor commit preserves c042 as its
parent; its exact publication SHA belongs in the PR and coordinator handoff.

Fresh actual trusted admissions passed for both additional production paths:
`NotificationService.kt --operation modify` and
`NotificationActionReceipt.kt --operation modify`, capability `wp-owned`.
The original admission matrix still applies to the seven previously admitted
paths. A same-owner evolution attempt for NotificationService is recorded
separately; the historical branch-bound receipt remains unchanged and is advisory,
not independent proof of a live semantic collision.

The actual resumed evolution returned
`Capability evolution caller differs from the stored session/branch/worktree`;
no ledger entry was modified to evade it. The isolated-authoring policy above
still applies to these admitted source edits.

WP-401 confirms no overlap with draft-method metadata, and no durable authenticated
reply ingress/full-recovery implementation in held #515. Its neutral capacity log
does not repair rejection or supply retention. The receiver's full-capacity intake
finding therefore remains an explicit **integration hold**, not a solved feature.
No dispatch, retirement, storage initialization or recovered-text copy is activated.

Actual repeated checks on the review corrections:

- `C:\Python312\python.exe tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output C:\Users\camer\AppData\Local\MeshCoreOne\verification\wp215-review-20261104-2200`: passed host readiness.
- `C:\Python312\python.exe tools\android-port\controller\verification_config.py --check`: passed.
- `C:\Python312\python.exe tools\android-port\controller\validate.py`: passed.
- `C:\Python312\python.exe tools\android-port\portmap.py`: passed.
- `git diff --check`: passed.

Readiness does not clear or acquire the retained Gradle owner. No local product
execution occurred; new controls remain authored, not passed test counts. The
exact-head hosted run must establish actual discovery and behavior before any
acceptance claim. No second CI-result bundle is committed or retained.

`C:\Python312\python.exe android\parity-tests\semantic.py --check` also passed on
the resumed edits (58 groups, 5133 families); this is not independent behavioral
acceptance or an executed product regression result.

## Post-publication test corrections

The published review candidate is
`1633712fc1394d5ceeb86ecf253622a3f47e97cf`, draft #570. Its exact-head hosted
run is `41964966213`; the hosted logs/result are authoritative. Source inspection
after publication found two JUnit4 message-last `assertEquals` calls and corrected
them to JUnit4's message-first signature without changing the assertions.

The original App pre-wire draft-consumption assertion is preserved as its own
positive control. A distinct App case leaves the restored draft unconsumed across
the accepted retry, as do the newer/equal-text draft cases. Successful and partial
service read controls now also assert the independent local-read receipt and
returned-write count without weakening the original wire-receipt assertions.
These follow-up edits remain local until the active exact-head run is terminal;
they do not retroactively alter or validate its candidate.

The producer/storage owners are coordinating a nonactivated unique issued-token
registry plus atomic admission of exact raw reply text. No current initialization
witness or visible-copy/full-capacity failure interface has been supplied. Legacy
UPDATE_CURRENT reissue may change original payload extras; missing payload extras
also cannot be treated as a legacy-dispatch guard because mutable fill-in can add
them. The producer's proposed fixed rejecting codec sentinel needs its own actual
PendingIntent controls. None of these proposed interfaces is activated here.
