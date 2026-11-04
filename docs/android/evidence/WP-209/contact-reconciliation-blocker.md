# WP-209: unresolved radio-execution proof for #517

Assignment: remaining recoverable uncertainty for
[issue #517](https://github.com/cbattlegear/MeshCoreOne-Android/issues/517).
Worktree branch: `cbattlegear-miniature-sniffle`.
Source reference remains `db14559b39d32322b06477c6ae676112f583db50`.
Implementation prerequisite:
`b544c7ab1c405fa681e8fac1b1ad03edcee27006`, the
[#553](https://github.com/cbattlegear/MeshCoreOne-Android/pull/553)
definitely-unsent Add/Remove accounting series, integrated without rewriting.
This investigation does not change that series's production behavior or the
older recovery session's retained drafts.

## Result

The requested general recovery is **not implemented**. There is a concrete
missing protocol contract, independent of device acceptance or reservations:
the available API cannot prove that a radio command with a lost terminal reply
will not execute after a replacement session has fetched a complete snapshot.
Enabling refresh to prune or clear uncertainty on that evidence would violate
the assignment's explicit safety requirement.

The new deterministic control
`completeIdentifiedReplacementSnapshotsDoNotProveAnOldRadioWriteCannotStillApply`
uses the existing real `MeshCoreSession`, wire parser, virtual clock and held
radio control. It does not substitute a fake contact session:

1. Send `UPDATE_CONTACT`; hold the radio's terminal result.
2. Let the session's actual timeout expire, retain the recovery marker and await
   `stop()`, including host-generation cleanup.
3. Start another session over a replacement transport view of the same radio;
   verify its reported self public key equals the original session's.
4. Fetch two full streams directly through the protocol role. Both have
   `contactsStart(0)`, `contactsEnd`, zero rows and completed protocol progress.
5. Recreate the preference role without the old in-memory dispatch provenance.
   The production manual-refresh entry point still refuses recovery, releases
   its manual-sync claim and keeps the marker.
6. Apply the previously sent command on the held radio without delivering its
   lost ACK. Radio state now differs from both completed snapshots; no new
   contact write was sent, and no local continuation saved the old write.

This is a contract-level counterexample using a deterministic radio control,
not evidence that a specific physical firmware delays writes this way. It
demonstrates behavior permitted by the current transport/session interfaces.
The existing held-radio control already intentionally retains its pending
radio-side queue across link teardown; this change does not add that behavior.

## Why existing mechanisms do not supply the proof

- `RequestContext` retains a sent exchange after UI cancellation to consume its
  reply. That protects response ownership while the exchange exists, not the
  outcome of a command whose reply never arrives.
- `SessionCore.endGeneration` cancels/joins host jobs and disconnects the
  transport. It has no radio-execution receipt. An in-memory finished job or
  replacement generation therefore cannot authorize durable recovery.
- `MeshTransport` promises connection lifecycle and byte transmission, not a
  cross-connection or BLE/WiFi radio-command execution fence. The pinned Swift
  transport documentation makes the same distinction. BLE's acknowledged GATT
  write is not a contact-command `OK`.
- `SessionCore.checkCorrelation` refuses ambiguous replies within a generation;
  starting a fresh generation removes host reply ambiguity, not unknown
  radio-side work.
- `SessionContactOperations.fetchContacts` can verify a terminal stream and
  total, but `ContactFetchResult` contains neither an originating command ID,
  radio incarnation nor execution watermark. The new control demonstrates why
  even repeated equal-count, correct-public-key snapshots cannot fill that gap.
- `ContactWriteRecovery` deliberately rejects persisted markers loaded without
  dispatch provenance. Its internal `reconciled` CAS is not a recovery permit.
- The existing UI already reaches `ContactsStateHolder.syncContacts` ->
  `AppNodesFeature` -> `ContactService.syncContactsForRefresh`; changing that
  route cannot create a missing producer guarantee.
- Room's `batchSaveContacts` and subsequent service pruning are separate
  operations. They also need a dedicated atomic recovery application contract,
  preserving newer frames/local metadata and other radios. Making them atomic
  would not solve the earlier missing radio-execution proof.

## Required integration contract

Before enabling general recovery, establish an actual source-backed guarantee
that a recovery receipt for a verified radio orders **all** earlier potentially
sent contact commands before the snapshot, including retired connections and
transports. It must also prevent older host exchanges from dispatching after
the receipt. Possible implementations require a documented firmware execution
barrier/ordering contract or an equivalent verifiable radio incarnation; a new
Android flag or a successful `APP_START` response is not that guarantee.

With that producer contract established, the Android continuation still needs:
an epoch-bound recovery permit; complete, unique-key, identity/generation-bound
snapshot validation; one guarded Room apply/prune transaction preserving newer
local metadata; durable application before conditional marker clearing; and
idempotent retries when cancellation or marker persistence fails. A late
positive or negative terminal reply can settle a retained exchange, but it does
not recover every durable UNKNOWN marker after process replacement.

No firmware behavior, automatic reboot/reset, device acceptance run or user
confirmation is invented here to replace the missing guarantee. #517 remains
open work; #553's safety improvement can be evaluated independently.

## Verification

The new method is authored source evidence until its exact-candidate hosted
test run completes. It is not a passing recovery control, Room recovery test
or mounted-Compose test. No successful recovery, device or hardware result is
claimed.

The supported local attempt
`python tools/android-port/local/check.py --worktree --stages preflight`
refused working-tree overlays with
`Working-tree overlays are not supported by the installed merge verifier`.
It did not execute product tests. The host's default Python is 3.12.3, not the
pinned 3.12.4, and no same-host `ANDROID_CI_STATE` is configured. No toolchain or
runtime was installed, no execution owner was cleared, and no direct Gradle
bypass was used. Exact-head hosted validation is the supported alternative;
its original job log, not a second result bundle, is authoritative.
