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
