# WP-209 local path-response and pending-advert admission

Bounded consumer contribution for [#559](https://github.com/cbattlegear/MeshCoreOne-Android/issues/559).
Owner: `services-porter`, session `d8dc94fa-61ec-4e2f-9dad-3a809582d105`.
Authoring base: merged main `e9b924752f085533a349eda07de4527eba40dc56`.
Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
The original recovery worktree's four drafts are not imported or modified.

## Contract and safety boundary

The dependency is producer-owned `LocalDirectContactPersisting` contract
`8e88775daf3dbe4be2f8bc3230d7f73f13dbc830`. Storage implementations must implement
that separate role; a missing capability throws explicitly instead of falling back
to first-prefix-match or nullable lookup.

`AdvertisementService.materializeLocalDirectContactForPendingAdvert(prefix, radioId)`
returns `LocalDirectContactResolution`: `NotFound`, `Ambiguous`, `Stale`, or
`Unique(contact, rosterRevision)`. Only genuine local absence and exactly one
pending full key admit a radio fetch. After `getContact` suspends, the method checks
the monitor identity, radio, tombstone, pending full-key set and returned full key.
It then keeps the pending gate across the conditional Room insertion, recency and
Discover transaction. Storage failures and cancellation propagate. A competing
local key returned by conditional insertion is combined with the pending key and
remains `Ambiguous`, never a newly attributed sender.

The typed materializer is additive and inactive. The existing nullable
`materializeContactForPendingAdvert` implementation, signature and original tests
are preserved unchanged; it does not call the typed method. Its known ambiguity,
post-fetch race and error-collapse limitations are **not fixed** by this
source-only contribution. Neither method is used by the inactive WP-214 candidate
ingestion seam: typed materialization itself stamps recency and emits ContactUpdated,
which that source-only ingestion experiment must not trigger.

The callback seam is:

```kotlin
suspend fun <T> withPendingAdvertContactOperation(
    radioId: RadioId,
    prefix: Bytes,
    observed: LocalDirectContactResolution,
    block: suspend (LocalDirectContactAdmission) -> T,
): T
```

Lock order is **pending gate, local roster fence, Room**. The gate covers advert
recording, draining, failure/busy requeue, missing-key escalation, deletion and
teardown reset. The callback receives the producer's actual conservative
admission, not a wrapper masking a unique admission after its save can bind.
An absent monitor or different current radio produces `Stale` before a local
candidate can be returned. Pending keys from a replacement radio cannot admit an
old radio's candidate. Distinct pending full keys are combined with current local candidates before
producer admission. Originally `Ambiguous` or `Stale` observations stay unresolved.
The callback must not reenter advert admission, mutate pending/roster/channel
state, or perform long hardware or platform-notification work. Release the scope
before those operations. This is a local cache invariant, **not RF-origin proof**,
deduplication authority, user acknowledgement, or activation of receive routing.

Path responses classify the full same-radio roster, then call
`applyLocalDirectPathIfCurrent` with the exact observation. The producer owns
revision/id/key revalidation and the single path+phone-recency+Discover transaction.
The response's encoded path length is authoritative; unrelated contact fields and
the radio `lastModified` watermark are not reconstructed from an older DTO.
Ambiguity and stale identity are logged distinctly; DB errors are logged as
errors, not `NotFound`. The raw `PathDiscoveryResponse` event denotes arrival,
not successful mutation, and remains delivered on non-cancellation storage
failure as well as no-match/ambiguous/stale outcomes.

## Admission

`C:\Python312\python.exe tools\android-port\local\reserve.py admit --wp WP-209 --path <path> --operation <operation>`
admitted service, service-test, evidence and deviation edits as `wp-owned`.
The two data-test paths use `add-or-update-related-test` and were admitted as
`reviewed-support-test`: directly necessary tests only; preserve existing
cases/assertions; no product/build changes; reconcile real semantic overlap;
trusted exact-candidate review and positive hosted discovery required.

## Authored controls, not execution evidence

`AdvertisementPathIdentityRoomTest` contains 28 actual service/event-monitor and
in-memory Room controls. Its controlled session role is not a physical radio:

- Reversed collision insertion orders, foreign-radio isolation, encoded hash-size
  paths, metadata preservation, radio watermark and monotonic phone recency.
- Held classification followed by deletion, same-key/new-id replacement,
  same-id/new-key replacement, same-id/key delete-reinsert, newer metadata save,
  and insertion of a colliding row; no path/recency/Discover mutation on rejection.
- No local match, explicit read failure, and real SQLite Discover-insert failure
  rolling back path and recency together; raw path event retained.
- Held `getContact` followed by a second colliding advert, different/same-key local
  insertion, pending deletion or monitor replacement; wrong returned full key;
  held conditional insertion followed by a different local key.
- Fetch cancellation, materialization storage failure, same-key versus two-key
  pending/local union, sticky unresolved observations, unresolved message save,
  and cancellation releasing a pending-fenced consumer operation.
- Old-radio admission after replacement and admission after monitor stop both
  remain `Stale`, including when the old radio has a unique persisted row.
- Independent typed success asserts exact key, radio watermark, heard/Discover
  transaction and ContactUpdated; true absence performs no radio fetch or insert.

Existing advertisement service/background tests and
`AdvertisementCoordinatorRoomTest` retain their original legacy materialization
case identities, fixtures and assertions. Only the new typed controls invoke the
inactive six-byte materialization API. The actual path-response controls use the
producer capability and do not claim the legacy materialization bug is repaired.

## Verification and integration status

Actual commands run in the isolated native Windows worktree:

| Command | Result |
|---|---|
| `C:\Python312\python.exe tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute temporary directory>` | Passed; host readiness only. |
| `C:\Python312\python.exe tools\android-port\controller\validate.py` | Passed before the evidence-only update. |
| `C:\Python312\python.exe tools\android-port\portmap.py` | Passed before the evidence-only update; headers do not establish feature acceptance. |
| `git diff --check` | Passed before committing. |

No Gradle/product test discovery or execution is claimed here. The retained shared
execution reservation was not cleared, bypassed, or contended for.

The producer implementation `2fade412e01b720931ca4b15417cbb43e98045a4` starts on
the separately owned #550 graph at `386f94f6285a44c2187351aea0d44e2794041d9f`,
not this clean-main authoring base. A conflicting attempted cherry-pick was
aborted without producer-source resolutions; #550's branch/head is untouched.
The consumer commit must be composed by that producer on its actual prerequisite
graph for exact-head hosted service/data/parity execution and independent source
review. WP-214 owns the inactive typed sync port/caller; WP-302 retains the app
adapter. The additive method split preserves legacy app source compatibility
without changing or registering that adapter. This branch alone is not an
assembled, merge-ready application.

No Android device, physical-radio, cross-platform backup, or receive-activation
acceptance is claimed. Hosted results/logs for the eventual exact candidate are
the reproducible execution authority; no duplicate CI-result bundle is retained.
