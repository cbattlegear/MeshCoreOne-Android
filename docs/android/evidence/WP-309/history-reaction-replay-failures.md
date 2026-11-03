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

On retry, a committed reaction row is preserved. Its summary is rebuilt from the
real stored reactions with `ReactionParser.buildSummary`, written successfully,
and then returned to the actual timeline. New rows still use the existing
`persistReactionAndUpdateSummary`; no second parser or persistence store is added.

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
