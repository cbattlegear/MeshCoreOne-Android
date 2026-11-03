# WP-309 history replay failure and cancellation successor

Repository: `cbattlegear/MeshCoreOne-Android`. Bounded concern:
`NM-HISTORY-REACTION-REPLAY` in #406. Base:
`fd3efe2be3f1837d39636433cc82fae80f4294ae`. Trusted main at investigation:
`796a872a3565a66c74f782b738a9ecb3c273fb25`. Frozen Swift:
`db14559b39d32322b06477c6ae676112f583db50`.

## Confirmed pre-existing failure path

`ReactionService.indexMessage` / `indexDMMessage` atomically take the complete
matching batch from their bounded pending queues. The App history consumer then
checks the real store for a duplicate and invokes the service persistence helper.
The helper logs store errors and returns null; cancellation propagates. Previously
the App neither restored the drained entries nor surfaced null as a failed load.
Existence-read failure/cancellation also lost the batch. If save committed before
fetch/summary failure, the duplicate guard skipped all later reconciliation.

The pinned `ChatTimelinePopulator.persistPendingReactionIfNew` has the same
nullable persistence/duplicate-guard pattern. Its four original indexing families
exercise success, scope, duplicate rows and unknown local name, not these failure
schedules. This issue is not introduced by the preceding older-page overlap diff.
The native recovery change is explicit in D-15, not silently attributed to Swift.

## Bounded implementation and custody

The App consumer retains a synchronous restoration action for every exact drained
pending object. A finally block restores the current and every later uncompleted
match, while successful earlier matches remain acknowledged. No broad catch or
non-cancellable I/O hides failures. A null persistence result becomes a typed
`PersistenceStoreException` for the existing timeline diagnostics/error surface.

On retry, a committed reaction row is preserved. The service's extracted
`reconcileSummary` rebuilds its summary from real stored reactions with the
existing internal `ReactionParser.buildSummary`, writes it successfully, and
returns it to the actual timeline. New rows still use the existing
`persistReactionAndUpdateSummary`, which calls that same reconciliation helper
after saving. No second parser, summary algorithm or persistence store is added.

`ReactionService.requeuePendingReaction` and `requeuePendingDMReaction` accept
the exact objects so `receivedAt`, parsed reaction, raw text and scope survive.
They use the existing lock, key canonicalization, queue and maximum count of 100.
Existing indexing/hash/cache/tie selection/clear behavior is unchanged. Pending
retry remains session-lifetime and bounded; this is not durable pending storage
across disconnect or an automatic retry worker.

Trusted-main `reviewed-support-consumer` / `modify-related-consumer` admits the
existing App consumer and exact service file. The new App test is admitted by
`app-build-launcher` / `add-direct-support-wiring`; docs are `wp-owned`. Automatic
claim attempts returned the existing source/base/manifest/backend mismatch.
Historical reservations/identities/authorization and the retained local
execution owner were not cleared, impersonated or recreated.

WP-216 evidence session `4bed84c3-a500-4b2e-845b-5d151574b27d` reports no active
implementation custody. The historical owner/courier reports no established
current queue producer; the retained grapheme producer
`8c13652e-6958-4710-a800-2dde6cb2f05e` explicitly reports no active/unmerged
queue changes and merged #365. No current semantic overlap was identified;
these reports are coordination, not independent grants of capability. No other
worktree was read or modified.

The historical/courier search cannot exclude unseen allocations or drafts:
overall current producer custody remains unknown, not "cleared." Authorization
comes from the coordinator's assigned bounded repair and these actual
trusted-main path/operation admissions, not merged PRs or old session labels:

| Actual path | Operation | Admitted capability |
| --- | --- | --- |
| `android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/reactions/ReactionService.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/app/src/test/kotlin/com/meshcoreone/android/app/container/HistoryReactionReplayFailureTest.kt` | `add-direct-support-wiring` | `app-build-launcher` |

The actual supported claim calls for these paths returned the historical
binding mismatch, not a new successful reservation receipt. No open WP-216 PR
or contacted active service writer was identified, but those bounded checks do
not prove global absence. A discovered semantic collision still requires
serialization/transfer. This successor does not change `ChatViewModel` or its
preview/render-cache seams, and did not wake the potential #402 producer again.

## Real-store fault and cancellation regressions

`HistoryReactionReplayFailureTest` adds 30 methods to the existing App source set.
Twenty-eight independently named methods cover both DM/channel scopes, each with
typed failure and real child-coroutine cancellation at seven boundaries:
existence read, before save, after committed save, before fetch, after completed
fetch, before summary update, and after committed summary update.

All selected-operation decorators delegate to actual `RoomPersistenceStore`.
The post-operation boundaries occur only after the actual Room call returns.
Cancellation parks that actual operation in a `CompletableDeferred`-announced
gate; the parent cancels and joins the child refresh. It is not a fabricated
successful result or a swallowed CancellationException.

Each method queues Bob/Carol/Dave for the same independently specified
`Hello`/1704067200 hash `b45pc4ek`, then interrupts Carol. Assertions require:

- Bob alone is acknowledged; the retained Carol/Dave objects equal the originals,
  including original arrival timestamps and order.
- The actual row/summary partial-commit state matches the injected boundary.
  Existing row UUIDs/metadata survive retry rather than being upserted as new rows.
- A second failure during duplicate-summary repair still retains the complete
  uncompleted batch. Cancellation cases with committed rows also cancel after
  the duplicate branch's real reaction fetch and require unchanged stored rows.
- Final explicit refresh yields exactly three scoped rows and summary count three
  in the real store, mounted timeline, and actual shared rendered reaction fragment.
  Another refresh stays idempotent and the service has no remaining matches.
- Store failures reach the timeline's typed passive error; cancellation has no
  displayed persistence error and remains a cancelled child.

Two additional methods restore 101 exact channel/DM objects through the new
entry points and require exactly the existing newest-100 bound, unchanged
metadata, foreign contact/channel isolation and canonical channel sender lookup.
The prior nine real-history success/scope tests remain unchanged and mandatory.

## Verification state

Native Windows input preflight passed with Python 3.12.4, inherited same-host
state, and a fresh absolute output directory:

```powershell
python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh-absolute-output>
```

`git diff --check`, `python tools\android-port\controller\validate.py` and
`python tools\android-port\portmap.py` passed before publication.
The supported local launcher again refused execution:

```powershell
python tools\android-port\local\check.py --commit HEAD --affected-base 796a872a3565a66c74f782b738a9ecb3c273fb25 --scope module:app
```

Result: `Hard-lock collision for external resource gradle-execution:
gradle-execution held`. Owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged. No direct Gradle,
lock recovery, runtime installation, device deployment or local JVM bypass ran.

The successor requires its own exact-commit hosted run. The earlier overlap
candidate's pass does not verify these new bytes. Until that run is recorded,
the new methods are authored regressions, not passing or failing product evidence.
No iOS reaction execution, Android device, physical radio, screenshot, TalkBack,
signing, release or whole-WP acceptance is claimed.

The initial successor `8b1c496307ede4740319d07144001f1b69b31145` failed
[hosted compilation](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41602626530):
the App attempted to call the service module's internal summary helper. App tests
did not execute, so this is not passing or failing product regression evidence.
The corrected successor extracts the existing persistence helper's
`reconcileSummary` inside `ReactionService` and exposes only that operation,
preserving its existing logging/null/cancellation behavior and avoiding an
internal parser API visibility change or duplicated summary implementation.

## Exact successor result

Corrected Kotlin candidate `0ee2a57a900d622e1e1768a2c2c27b076720bf59`
passed its own
[Android candidate CI run](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41604460960).
The authoritative Android job reports `:app:testDebugUnitTest`: 1,886 discovered,
run and passed; zero failures, errors or skips. Its method inventory includes
all 30 `HistoryReactionReplayFailureTest` methods; both that suite and the prior
nine-method `HistoryReactionReplayTest` report `END|SUCCESS`. The declared build
and other selected jobs completed successfully. This is actual successor
execution, not inherited credit from the previous history-overlap pass.

The final evidence-only commit does not change those tested Kotlin bytes.
Hosted results/logs remain authoritative: no second CI-result bundle was
downloaded, committed, uploaded, hashed or revalidated.

At final inspection, main advanced to
`99479711bfdb7c621765f09d671068ca8b8318ce` by merged #529. Its delta from the
tested base contains only `BleRecoveryIntegrationTest.kt` and WP-303's BLE
evidence. The affected replay production files, trusted capability policy,
manifest and inspected frozen Swift inputs are unchanged. No main integration
or whole-current-base verification is claimed by this candidate's run.
Exact current-base review/merge remains the coordinator's separate action.
No PR was opened, no historical reservation or local execution hold was
released, and no whole-WP or device/hardware acceptance is claimed.

## Current-main reviewable composition

The subsequent coordinator assignment continues this same bounded #406 concern,
not the other reaction/actions work or the nonactivated channel-history feature.
Live main is `99479711bfdb7c621765f09d671068ca8b8318ce`; its merge base with
published `446ba8da0eaade9da5d3606c1d802b4f74548696` is
`796a872a3565a66c74f782b738a9ecb3c273fb25`. The ten-behind/seven-ahead comparison
does not mean ten intervening product changes: the exact main tree delta is only
the merged BLE integration test/evidence described above. An ordinary merge,
`2395d20ec0f7335494ce39cbd7b259d657973476`, adds exactly those two paths and
preserves all five replay Kotlin blobs, including the original nine and 30 tests.
There is no rebase, force push, cherry-pick or adoption of another producer.

Fresh trusted `reserve.py admit` calls passed for all three production files
with `modify-related-consumer` / `reviewed-support-consumer`, both App test files
with `add-or-update-related-test` / `reviewed-support-test`, this evidence with
`record-evidence` / `wp-owned`, and the deviation with `modify` / `wp-owned`.
The scoped claim again refused
`Existing WP lease is bound to different source/base/manifest/backend`.
Historical WP-309 attempt `c2707711-5b8b-4bfb-8d51-ada7caaf12a4`, revision 5,
was not adopted, evolved, released or recreated.

Exact producer reconciliation uses published immutable Git/server diffs and
specific seam messages, not session names or assumed handoff:

- Messaging producer `aa4b9d95-ca8d-4023-bb04-049e969e6d6c`, #530 head
  `0d67cfa6e2c8009b46621f29f15f01a97ae0a84e`, changes App draft revisions,
  pending-message creation/committed recovery, session-pinned send/enqueue and
  resend guards. It does not change `prepareTimelineMessages`, reaction queues,
  or the history fetch methods. Its identical `PersistenceStoreError` import
  is a deterministic textual overlap, not shared replay-state ownership.
- Persistence producer `b5a401f1-8ec2-46e2-96b8-325468cd9761`, published head
  `6fd2f90411c140b62f2de8c4c20a0a78a9c32ee1`, has no path intersection with
  this slice. `ChannelHistoryPersisting` is explicitly separate from
  `MessagePersisting` and nonactivated. Its proposed reads require initiating
  occupant proof, reject `UNBOUND_HISTORY` instead of silently filtering history,
  and confer no wire authority. This replay slice changes none of those APIs,
  existing timeline queries, `MessageDTO`, pending-send origin, or unbound UX.
- Both actual producers received the exact replay symbols and pending-object
  distinction for any unpushed semantic collision. At composition time their
  responses were pending. The published evidence establishes disjoint edits,
  not global custody clearance or release of either producer's work; a newly
  reported actual collision still requires serialization. No other worktree
  was inspected or modified.

The ordinary merge imports neither #530 nor #535/#537, grants no dependency
unlock, and does not treat query storage or a committed message as completed
dispatch. Current input preflight, manifest validation, traceability, semantic
source check and whitespace check passed. The retained local execution owner
is untouched; hosted PR-triggered checks and independent exact-bound review
remain separate requirements.

Historical run
[41604460960](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41604460960)
was `workflow_dispatch`, attempt 1, for parent
`0ee2a57a900d622e1e1768a2c2c27b076720bf59`, not a pass for evidence-only
`446ba8da0` or this current-main composition. Subsequent PR metadata and its
authenticated bounded scope must identify their own exact base/head/run.

## PR-triggered result and coupled live-summary race

Ready PR #540, head `0e923491842e62364ccba5da625f0c71b8748075` on current
base `99479711bfdb7c621765f09d671068ca8b8318ce`, passed normal
`pull_request` run
[41610686548](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41610686548),
attempt 1. Its authoritative Android job `125831590386` reports App 1,889,
services 2,801 and chats 443 discovered/run/passed, zero failures/errors/skips.
All nine history and 30 failure methods appear in that job's actual inventory;
both suites end successfully. Controller policy, declared build and `android-ci`
passed. The two Swift oracle jobs were scope-skipped, not iOS reaction evidence.
Authenticated bounded scope comment `6599922628` staged as data only with digest
`fe4ce77a8282a60cf7bd1b0d24860bb6e258e0ab1fb3136b48785132375c9edc`.
This is not an independent-review verdict or successor acceptance.

Inline review `4452915365` identifies a real race in that candidate's new
duplicate-summary branch: history fetches Bob's row and suspends; live sync saves
Carol and writes count two; history resumes and writes its captured count one,
then acknowledges Bob's pending entry. Later history has no pending entry to
repair that stale summary. The underlying save/fetch/write helper was already
non-atomic, but exposing duplicate-only reconciliation adds this concrete
interleaving; the correction does not dismiss it as unrelated or fake success.

The bounded correction serializes both complete operations through the same
session-owned `ReactionService` mutex. Normal persistence holds it across save,
fetch and summary update; duplicate reconciliation holds it across fetch and
summary update. A private helper avoids nested acquisition. The live sync's
existing `SyncReactionAdapter` already delegates to this exact service instance;
no sync producer, query/origin API, event route or runtime ownership is changed.

Six additional methods in `HistoryReactionReplayFailureTest` use actual Room,
the mounted App history consumer and that real live persistence adapter. Both
DM and channel schedules gate the first actual fetched snapshot, then start the
other operation. History-first requires live persistence to wait before its save;
live-first requires duplicate retry to wait before its fetch. The controls use
undispatched coroutine entry and explicit operation signals, not timing sleeps.
Two cases cancel the waiting duplicate operation, require its pending Bob object
to remain queued, then release the live writer and explicitly retry. Final stored
rows retain Bob/Carol identities and the store/mounted history summary is two
after a real refresh, with later refresh idempotent.

These six are adapter-boundary concurrency controls, not a claim of new radio
polling/device evidence. Their pre-fix failure has not been executed locally
under the retained hard lock. The original 30 controls remain intact; the
successor requires new exact-head PR execution and independent review, not credit
from the passing `0e9234918` run.

## Replacement-session persistence boundary

The same-generation successor `f827e788d37032282c00fd8d7dfea24fb0a437c6`
passed normal PR run
[41614514548](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41614514548),
attempt 1: App 1,895, services 2,801 and chats 443 discovered/run/passed, zero
failures/errors/skips, with all 36 failure/concurrency and nine history methods.
This historical pass does not cover the replacement-session correction below.

Independent review and actual inline finding `4453399854` identify the missing
lifetime boundary: a screen-owned history coroutine can keep the old service
after the connection graph is torn down. A replacement service previously got
its own mutex, so old history could still overwrite a replacement writer's newer
summary and acknowledge its pending entry. Per-instance operation serialization
is necessary but not sufficient.

The process-owned `RadioSessionContainerFactory` now supplies the same mutex to
each `RadioSessionContainer.create` and its `ReactionService`. That argument is
required at graph creation, preventing an accidental per-generation fallback.
Only the persistence boundary survives; message caches, pending queues, session
jobs and radio services still belong to their original connection. No Room,
query/occupant-proof, dispatch-origin, teardown or messaging-queue implementation
is modified. Standalone service tests keep their own default mutex.

Six additional methods use the actual runtime manager to disconnect and reconnect
the same radio, asserting a new service and greater generation plus completed
old teardown while history remains suspended in the screen-owned operation.
History-first holds the old fetched snapshot while the replacement live adapter
must wait before save. Live-first holds old history inside its existence read,
reconnects, then holds the replacement writer's fetched snapshot before releasing
old history to wait for the shared boundary. Both scopes also cancel that old
waiter and inspect exact pending restoration. Final real Room rows and mounted
history retain both identities and count two through a further refresh. The
original 36 failure/concurrency methods and nine history methods remain present.
These are real runtime-generation/Room interleavings through the sync persistence
adapter, not physical radio or full live-polling evidence. A pre-fix execution of
these six controls has not occurred under the retained local hold.

Trusted `reviewed-support-consumer` admission passed for both newly touched
container files with `modify-related-consumer`. The scoped claim again refused
the unchanged historical binding mismatch; no reservation was cleared/recreated.
The actual #543 integration producer explicitly reports no unpublished
factory/create/reaction-service construction intent and only its four disjoint
published paths. The actual #530 producer's limited no-conflicting replay
helper/import disposition is published as #540 comment `6600038072`; neither
report grants global custody. The persistence owner received the exact new
synchronization seam; no persistence implementation is adopted here.

Same-host preflight and whitespace checks passed. The supported local launcher
again refused `gradle-execution held`; retained owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is untouched. The successor
requires fresh exact-head normal PR CI, authenticated scope and independent
review. No predecessor's passing run is claimed for its new code.

The first reconnect candidate `7649173eae19d042cb520538edc68fa6e82333ff`
ran normally in
[41620449043](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41620449043).
Compilation succeeded and 1,901 App methods executed; exactly the three new DM
reconnect controls failed, while the original 45 and the new channel controls
passed. The real full-contact sync correctly pruned the test's local-only Alice
because the fake replacement radio reported an empty contact list, cascading
the target's reactions before the contender began persistence. This is not a
passing successor or evidence that the mutex failed.

The corrected fixture reports Alice through the existing protocol fake on both
connections and uses the actual synced contact identity. It additionally asserts
contact, target and committed Bob retention immediately after reconnect, before
starting the replacement writer. No production pruning behavior, store
operation, expected count or identity assertion is bypassed or weakened.
This correction still requires its own normal exact-head execution.

## Atomic semantic idempotence

Corrected reconnect head `642bf92bd666bf6cf0cb909dceda43267841a3b8` passed normal
PR run [41623838754](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41623838754),
attempt 1, authoritative Android job `125876184419`: App 1,901, services 2,801
and chats 443 discovered/run/passed, zero failures/errors/skips. All 42
failure/concurrency and nine history methods occur in the real inventory and
both suites end successfully. This is not acceptance of the additional
semantic-idempotence correction.

The independent reviewer and latest actual Copilot overview identify another
tightly coupled gap: App and SyncCoordinator check semantic existence before
the shared mutex. Both can read false, then serialize distinct UUID inserts for
the same radio/message/sender/emoji because Room's upsert is identity-based and
its semantic index is nonunique. The resulting doubled count survives refresh.
The outside-check seam partly predates this repair; restored replay and its
required persistent idempotence make it inseparable from this concern. The
earlier Bob-versus-Carol controls did not start with absent Bob versus live Bob.

The service now checks existence again inside the shared persistence operation,
using the unchanged store matcher. Only an absent semantic reaction is saved;
both paths still rebuild and persist the summary before returning success.
Existing first-writer identity and metadata are retained, and check errors and
cancellation cannot acknowledge replay. The actual persistence owner b5 reports
no current/intended service/App/Sync guard, saveReaction, matcher or summary edit
and no semantic collision on this exact boundary. No Room/DAO/schema, query,
origin-proof or SyncCoordinator production edit is needed; no historical rows
are dropped, merged or replaced.

Eight new App/Room methods start with zero reaction rows, gate the actual history
and actual polling-wired SyncCoordinator existence reads at false, then release
either winner into persistence while holding its real fetched snapshot. They
exercise both scopes/orderings and real connection replacement. The poller
receives protocol frames through the existing fake radio, not a copied duplicate
guard or DTO-only dedup substitute. The loser must recheck after the winner
releases the boundary; the original row, count one, stored summary and mounted
history remain identical after another refresh. Two positive controls use the
same sender with a different emoji and require two rows; the earlier Bob/Carol
controls retain the distinct-sender case.

Four further controls inject final-guard failure/cancellation for each scope.
The original outer existence controls move their injected call from two to three
solely to account for Bob's new inside-lock read: Bob must still fully complete
before Carol fails. Final-guard controls interrupt call four, Carol's locked
read. All previous queue, partial-commit, metadata, cancellation and concurrency
assertions remain; no original method is removed. Total affected inventory is
56 failure/concurrency plus nine history methods.

These are source candidates until their own exact normal PR execution and
independent review. The previous 51-method pass is not successor evidence;
the local execution hold and source pin remain unchanged.

Independent source review of initial idempotence candidate `73cae8cd5` found a
test-only timestamp oracle mismatch before any successor pass was claimed.
Frozen Swift and existing App history create the persisted ReactionDTO with
its default creation time, not the pending queue's `receivedAt`. The new race
test must therefore retain and compare the actual first committed DTO, not
expect its date to equal the fixture's queue date. Production timestamp mapping
is deliberately unchanged. Distinct-emoji counts are checked independently of
tie ordering when DTO creation dates share store precision; the exact resulting
summary must still agree between Room, mounted history and subsequent refresh.
Existing pending-object restoration tests continue to compare original queue
arrival metadata exactly.
