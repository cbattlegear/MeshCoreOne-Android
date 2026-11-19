# ADR 004: frozen Swift pin exception for two fixed-sleep advertisement tests

**Status:** Proposed. This needs the maintainer's explicit authorization, which merging it gives, per ADR 003.

## Context

ADR 003 keeps the Swift reference pinned at `db14559b39d32322b06477c6ae676112f583db50`. It allows a test-only edit only with explicit maintainer authorization and an ADR. After the correlation-test race, the only other `SPM Package Tests` failures in this repo's CI history are two `MC1Services` tests. Each failed once, on branches that change no Swift:

| Test (`AdvertisementServiceTests.swift`) | Run | Failure |
| --- | --- | --- |
| `pathUpdate cancel after save does not cascade-delete messages` | 37365772725 (#40) | `#require(await store.fetchContact(...))` → `nil` |
| `setDeltaSyncHandler nil then reinstall still syncs pending keys` | #25 branch, 2026-10-05 18:18 | `#expect(await service.pendingAdvertKeys.contains(key))` failed |

Both tests inject an event through `MockMeshCoreSession.yieldEvent`, then sleep a fixed 40 ms and assume the service actor has already processed it.

- **Cascade-delete test.** The 0x8F handler records the key for rollback, then looks up the local row to delete it. The test holds the delta-sync handler, so the row doesn't exist yet and the lookup finds nothing. If a slow runner hasn't handled 0x8F within 40 ms, the test releases the handler first. The handler re-saves the contact and its message, then the late 0x8F deletes that row and cascades the message. That's exactly the observed `fetchContact → nil`.
- **Reinstall test.** If the 0x80 isn't processed within 40 ms, `pendingAdvertKeys` doesn't contain the key yet.

These are scheduling assumptions in the tests, not defects in `AdvertisementService`.

## Decision

The fixed sleeps are replaced with waits on the service's own recorded state, using the file's existing `waitUntil` helper (10 s timeout, 10 ms poll):

- **Cascade-delete test.** It releases the handler only once `contactsDeletedDuringSync` contains the key **and** `pendingDeletedKeys` no longer does. At that point, the foreground 0x8F path has dequeued the key and is already in its row lookup, before the handler can save. `#expect(deleteHandled)` makes a regression fail clearly.
- **Reinstall test.** It waits for `pendingAdvertKeys` to contain the key, then asserts the handler wasn't called, since no handler is installed.

No assertion is removed or weakened, and no production Swift source changes. The existing `#expect(await service.pendingAdvertKeys.contains(key))` is now the `waitUntil` condition plus `#expect(recorded)`. The general read-only policy from ADR 003 is unchanged.

## Evidence

- Root cause comes from reading `AdvertisementService.handleContactDeletedEvent` (the rollback key insert, the `pendingDeletedKeys` dequeue in the foreground, then `fetchContact`/`deleteContact`) and `recordPendingAdvertKey`, against the two CI failure messages.
- **Not run locally.** `MC1Services` uses SwiftData `@Model` macros, which need a full Xcode toolchain; the contributor's machine has only the Command Line Tools. CI's `SPM Package Tests` job on this PR is the execution evidence.
- `python tools/android-port/controller/validate.py` passes with the new amendment entry, and the controller test suites pass. (The controller was retired by [ADR-007](007-maintainer-directed-process-teardown.md); `python tools/android-port/portmap.py` now performs the amendment validation.)
