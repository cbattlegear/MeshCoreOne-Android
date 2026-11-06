# WP-209: independent participating repository mutation lane

Base: `35abcc1276082995eed216bf28bff04d77e176c8`.
Reference: `db14559b39d32322b06477c6ae676112f583db50`.
Tracking: [#601](https://github.com/cbattlegear/MeshCoreOne-Android/issues/601),
with the separate original-admission contract/storage producer prerequisites.
This branch imports no held storage/schema/authority source. The independent
channel reseed candidate remains separate at
`7ce63fbaf329d4eb1f0922343d9acb0754c8b795`.

## Scope and producer boundary

The coordinator assigned an internal data-local guard in the already admitted
`RoomRepositoryContext.kt`, not a new low-level public contract.
`RepositoryMutationGuard.withDispatch(block)` and `withMutation(block)` share
one process singleton mutex. It conservatively serializes participating contexts
even for different databases; it does not depend on Room-handle object equality.
The public consumer seam is the separately owned callback-only original
admission authority, not this implementation mutex.

The lane surrounds every `access(..., writing = true)` operation before the
context mutex: `write`, `guardedWrite`, backup restore, staged RX insertion,
explicit and scheduled RX flush, and close. Existing transaction cancellation,
commit and bookkeeping code is unchanged. Read operations do not acquire the
lane. The original validator must complete its `context.read` first, releasing
the Room transaction and per-context mutex, before invoking actual protocol
dispatch plus synchronous RAM acceptance while the process lane remains held.

Required lock order when composed:

1. Channel occupant operation/identity fence, where applicable.
2. Repository mutation lane.
3. Per-context mutex.
4. Room transaction.

No participating writer may acquire an occupant fence from inside this lane.
The base has no occupant fence to activate here. The dispatch callback must not
invoke another dispatch, generic writer, external publication or lock-acquiring
callback. Typed completion/publication follows release.

An inherited coroutine-context marker explicitly rejects nested lane entry,
including structured children and Room's transaction worker, rather than hanging
on Kotlin's non-reentrant mutex. The marker is rejection-only, never write proof.
Standard Kotlin continuation context propagation adds that marker without
replacing the caller Job or creating a `withContext` child Job. This preserves
the established committed-restore result after late caller cancellation.
Cancellation while waiting does not begin dispatch; exceptions and cancellation
from an entered callback release the mutex. No NonCancellable transition or
earlier exact-Job channel scope is reused by this guard.

## Participating writer trace

The production trace found message/pending mutations through:

- `MessageRepository`: message/reaction/repeat updates, deletes, ACK/status,
  timestamp, read/mention and retry-related changes.
- `PendingSendRepository`: insertion, replacement, sequence/attempt updates,
  deletion and maintenance purges.
- `ContactRepository`: cascades, orphan adoption and guarded contact deletion.
- `ChannelRepository` and `DeviceRepository`: history/channel/radio erase.
- `DiagnosticRepository`: message-region updates.
- `RepositoryCascades`: transaction-local helpers reached by those repositories.
- `BackupMessageRestore`: direct DAO updates inside `withBackupRestore`.

These participate through the context write/guarded/restore boundary. An arbitrary
raw DAO/SQLite writer, misuse of a read callback for DML, another process or
out-of-band physical-radio mutation is **outside** this guarantee. Modern held
producers were not imported, and their eventual composition must use this same
lane rather than a second private mutex.

## Authored controls and limits

`RepositoryMutationGuardRoomTest` has nine source-inventoried real-Room methods:
same-store read while dispatch is held; message/pending writer cancellation;
separate contexts over one Room handle; writer-before-dispatch restore ordering;
guarded deletion/restore/RX/flush/close exclusion; separate handles to one database
file; nested/child rejection; waiting/entered cancellation and release; actual
caller Job preservation plus committed restore cancellation; and Room-worker
reentry with rollback. Multiple scenarios share methods; this is nine methods,
not an inflated execution count.

No Kotlin compilation or JUnit execution occurred. The retained local execution
owner `local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged and not bypassed.
Exact-head hosted execution and independent review remain required.

The guard alone is **not activation** and mints no admission, incarnation,
revision or occupant proof. The canonical original-prepare/schema producer must
issue the authentic persisted original message plus optional exact pending-row
admission. The callback-only authority validates that opaque original inside
this lane before actual wire acceptance; equal IDs/DTOs/keys or a current receipt
are not substitutes. Until that producer and consumer composition are verified,
atomic original-message/pending-versus-wire protection is incomplete.
