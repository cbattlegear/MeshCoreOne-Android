# Channel accepted-send bookkeeping source

## Status and source custody

This is an isolated **source-only, non-standalone producer delta** on the corrected channel-failure
source `3e2c36508946d79a544d88320cacdbcdcc6b503e`. It does not repair or replace the immutable
`2916e0460c35753e76c1141c7eba8524b03222b8` / `c77e8a732d49680f78b74973515c1ed8f574e486`
historical freezes. The malformed original repository blob `45b71761acad7818ac8e728243286e810c92a6d2`
must not be substituted for the corrected input `21163a92eb341a8fe3306ac7b9906f1cb92ae76d`.

The additive contract is the exact `ChannelSendCompletionAuthority.kt` blob
`2cc76fae312c0c3bc065a1ea513daec3b91034c9` in canonical freeze
`773d434be7700734ee8cd2cb2b46c8f47fa8d416`. Frozen failure contract
`5897785d7a50a5d9b3648357e60a5cbb87348d52` and neutral event contract
`e94ffbe17697e5549fc811a28dccb876b79cf7e3` are unchanged.

The service race sites were read from immutable source `29666b5c90fd39442778960f43e950d682ec88a3`
and the service owner's neutral-only successor is `87372e101717596ecc0320219e67eff20b084a6f`.
The new service controls use the owner's subsequently agreed additive constructor argument
`channelSendCompletionAuthority`, so they require that owner's completion-consumer successor.
They are not represented as compilable against unmodified `29666` or `87372`.

## Producer and migration integration

The same `ChannelSendRepository` implements both optional roles with the existing durable
`DirectAckRepository` lease bridge. There is no second activation, generic persistence fallback,
copied DM grant, payload snapshot, physical dispatch journal or indexing certificate.

`recordChannelSendCompleted` first checks an exact committed-operation replay. A new operation
requires the original opaque capture/version, current issuer and lease, exact message and optional
pending incarnation/revision, captured actual timestamp, and matching pending `isResend` mode.
One transaction binds the caller-acceptance tuple and writes SENT. RESEND additionally performs
the checked send-count increment, heard-repeat reset and bound message's repeat deletion in that
same transaction. SEND preserves counts and repeats. Only own writes advance the expected revision.
Completion advances the capture version; subsequent new failure/remap operations cannot reopen it.
Previously committed failure operations still have their historical read-only replay.

`completeChannelSendPending` accepts the separately issued completion handle after the caller's
required indexing returns. It checks the current original bindings, deletes only the captured
pending ID/incarnation/revision, records the cleanup request and retires the grant. It never uses
`deletePendingSendsForMessage`. Exact cleanup replay is read-only even after replacement. Exact
completion replay preserves its original tuple but reissues no handle or live DTO after invalidation,
cleanup or retirement. Inspection returns no handle. `NotRecorded` is **not** unsent proof and does
not authorize a wire retry or recapture.

The sole v4 integrator must import the complete storage and repository blobs, register
`ChannelSendCompletionEntity` alongside the existing two channel entities, and delegate
`ChannelSendCompletionAuthority` through the **same** `channelSends` instance in
`RoomPersistenceStore`. The existing sole 3-to-4 migration already calls
`ChannelSendStorage.createTables`; that hook now additionally creates:

```sql
CREATE TABLE IF NOT EXISTS channel_send_completions (
  radioId TEXT NOT NULL,captureRequestId TEXT NOT NULL,completionRequestId TEXT NOT NULL,
  mode TEXT NOT NULL,acceptedTimestamp INTEGER NOT NULL,
  inputVersion INTEGER NOT NULL,outputVersion INTEGER NOT NULL,pendingCompletionRequestId TEXT,
  PRIMARY KEY(radioId,captureRequestId)
)
```

The existing restore invalidation of `channel_send_attempts.current` also retires these handles;
completion tombstones remain, so no additional destructive restore hook is needed. The failure
operation enum/readback contains no new completion kinds. The new entity changes the actual v4
input, and therefore requires its own genuine Room/KSP export after combined integration. This lane
edits neither database registration, shared store, direct-ACK producer nor migration owner files.
Genuine v3 is immutable. No schema v4 export has been manufactured here.

## Authored real-Room controls

`ChannelSendCompletionRepositoryTest` adds **19 test methods**, using the existing real Room fixture
whose exact combined support blob is `0a0b8f10820078bd422c699d067bf7362af46f35`. That fixture installs
`DirectAckStorage.callback`; it does not install a test-made UNKNOWN exemption. The controls cover
SEND/RESEND positives, checked counters and repeat deletion, immutable replay and inspection,
timestamp/mode conflicts, held direct/equal-channel replacement, no-op generic DML, exact cleanup
with a pre-existing sibling, replacement/sibling pending invalidation, post-commit replacement,
completion/cleanup journal rollback using actual SQLite abort triggers, overflow, stale/foreign
handles, DELIVERED preservation, remap versions, other-radio isolation, restore, malformed stored
bindings, file/store reopen with old-issuer rejection, and prior failure-operation replay after completion.

`ChannelSuccessReplacementRoomTest` adds **12 test methods** using a separate actual Room/native
SQLite fixture and real `MeshCoreSession` over `MockTransport`. Accepted-send barriers execute only
after the delegated real channel call returns; physical packet counts are asserted. It covers inline
and pending accepted-send replacement by same-key direct/channel rows, own resend counter/repeat
preservation, held indexing and held cleanup replacement envelopes, post-acceptance completion failure
followed by recreation/retry, completed-index cache recreation/retry, original success and exact
pending cleanup, and a real post-commit thrown-return replay with the same opaque attempt/request.
Completion faults decorate the actual optional authority, not a bypassed generic SENT method.

## Verification and limits

All **31 new test methods are authored, uncompiled and unexecuted**. These are source inventory
counts, not runner discovery, assertion results or passing evidence. No local Kotlin, Gradle, Android,
WSL or device execution was performed; no workflow was dispatched. The old ID-only generic SENT,
resend and broad-cleanup paths are expected to violate the replacement assertions, but no runtime
failure or successful repair is claimed from authorship. The adapted service controls require the
unfrozen-at-authorship completion-consumer successor.

Independent source review, the sole owner's combined registration/store/migration integration,
genuine schema generation and explicitly authorized hosted execution remain prerequisites. The
external reaction indexer's RAM cache/pending effects are not made atomic or certified by this
database seam. The caller must retain the original capture/completion identity across uncertainty;
lost RAM acceptance cannot be reconstructed as definitely unsent. This is not parity acceptance,
WP completion, a dependency unlock, deployment readiness or release authority.
