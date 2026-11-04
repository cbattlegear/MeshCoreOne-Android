# WP-209: unresolved radio-execution proof for #517

Assignment: remaining recoverable uncertainty for
[issue #517](https://github.com/cbattlegear/MeshCoreOne-Android/issues/517).
Worktree branch: `cbattlegear-miniature-sniffle`.
Source reference remains `db14559b39d32322b06477c6ae676112f583db50`.
Implementation prerequisite:
`b544c7ab1c405fa681e8fac1b1ad03edcee27006`, the
[#553](https://github.com/cbattlegear/MeshCoreOne-Android/pull/553)
definitely-unsent Add/Remove accounting series, integrated without rewriting.
Its ordinary pre-send refusal successors `d0c544b9a2e9e2bb0d392fa49df4b3e3d2e9a9b8`
and `15f8eace6b22652b72af47de4f7f865528f031b2` are also integrated, at merge
`df75dad`. The owner's final ordering, completion-callback ownership and typed
test-fixture correction, `5bb53b017204424621d27ad3aedb2f7d16cdd089`, is integrated
forward too, followed by its test-owner supervision correction
`522f9a28c1ed9c7a0cca7511059bbaaa7b342eb8`. These preserve genuinely
possibly-written outcomes and do not add a radio-execution barrier.
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

The concrete platform implementations do not strengthen these contracts:
`AndroidGattFacade.onCharacteristicWrite` completes `GattReply.Written` from
the GATT callback; it does not parse a contact-command result. `BleTransport`
serializes GATT operations and closes its native GATT owner on disconnect.
`WiFiTransport.send` writes/flushes a framed packet to a socket;
`disconnect` closes that socket and joins its host generation. Neither receives
a receipt that the firmware has drained its command queue or retired another
BLE/TCP input. Byte ordering within one connection is not a documented
cross-connection radio-execution guarantee.

The supported `CommandCode`/`ResponseCode` inventory has contact writes,
point reads and contact streams, but no such operation. `SelfInfo` has a public
key, not a boot/ingress incarnation or execution watermark;
`DeviceCapabilities` advertises no recovery-fence capability. `REBOOT` is a
write-only send in `MeshCoreSession`, not a confirmed-reset receipt, and an
automatic reset would introduce product behavior outside this repair.

The five inspected Swift transport/contact/model/code inputs match their
manifest-pinned Git blob IDs. The source commit object is not present in this
checkout, so a `git diff` against that commit was unavailable; comparison used
the manifest's individual pinned blob IDs instead. No source drift was found.

## Required integration contract

The exact unsupported operation is: **obtain a causally authoritative contact
snapshot after retiring every potentially sent write from earlier connections
to this radio, when those writes' terminal outcomes are lost**.

Before enabling general recovery, establish an actual source-backed guarantee
that a recovery receipt for a verified radio orders **all** earlier potentially
sent contact commands before the snapshot, including retired connections and
transports. It must also prevent older host exchanges from dispatching after
the receipt. Possible implementations require a documented firmware execution
barrier/ordering contract or an equivalent verifiable radio incarnation; a new
Android flag or a successful `APP_START` response is not that guarantee.

The smallest required protocol addition, if firmware cannot already provide
that guarantee from reviewed source, is an explicitly negotiated
**retire-old-ingress-and-snapshot** operation. This is a proposed requirement,
not an existing command or an assigned opcode. Its firmware implementation must
drain or irrevocably reject earlier contact work across old BLE/TCP ingress
owners, reject later delivery from those retired owners, and return a complete
snapshot associated with the requesting nonce and verified radio identity.
A firmware incarnation/execution token must make a replaced/restarted radio's
receipt distinguishable where nonce plus live-ingress identity is insufficient.
Merely adding a nonce to `GET_CONTACTS`, advertising a capability bit, or
returning a generic `OK` without those execution semantics would not fix the
counterexample. No firmware source implementing such semantics is among the
approved specification inputs.

With that producer contract established, the Android continuation still needs:
an epoch-bound recovery permit; complete, unique-key, identity/generation-bound
snapshot validation; one guarded Room apply/prune transaction preserving newer
local metadata; durable application before conditional marker clearing; and
idempotent retries when cancellation or marker persistence fails. A late
positive or negative terminal reply can settle a retained exchange, but it does
not recover every durable UNKNOWN marker after process replacement.

## Safe user-visible behavior without the missing operation

These are implementable bounded alternatives, not successful reconciliation
and not additional product changes in this candidate:

- Explain that the write outcome is unknown for the affected radio and that
  the last local list is cached; retain navigation, local data and other radios.
  Keep contact mutation admission blocked for that radio. A retry or dismiss
  action must not acknowledge the retired confirmation or clear its marker.
- Keep manual refresh's current truthful failure and spinner cleanup; expose
  a specific localized unsupported-reconciliation explanation instead of
  implying that reconnecting or repeated refresh will necessarily repair it.
- If offering an observational radio read, display it as an unverified preview
  with no upsert, pruning, marker clearing or success/navigation side effect.
  It cannot be the existing success-shaped refresh result.
- A known terminal ACK/rejection plus completed host/local tail can support a
  separate, narrower recovery permit after durable radio/epoch-bound settlement
  is recorded. That would improve late-ACK/local-save-failure UX, but must not
  be advertised as recovery of a persisted UNKNOWN marker without provenance.

No firmware behavior, automatic reboot/reset, device acceptance run or user
confirmation is invented here to replace the missing guarantee. #517 remains
open work; #553's safety improvement can be evaluated independently.

## Verification

The new method is an executed contract-level counterexample at the exact
candidate identified below. It is not a successful recovery control, Room
recovery test or mounted-Compose test. No successful recovery, device or
hardware result is claimed.

The supported local attempt
`python tools/android-port/local/check.py --worktree --stages preflight`
refused working-tree overlays with
`Working-tree overlays are not supported by the installed merge verifier`.
It did not execute product tests. The host's default Python is 3.12.3, not the
pinned 3.12.4, and no same-host `ANDROID_CI_STATE` is configured. No toolchain or
runtime was installed, no execution owner was cleared, and no direct Gradle
bypass was used. Exact-head hosted validation is the supported alternative;
its original job log, not a second result bundle, is authoritative.

The committed-source attempt
`python tools/android-port/local/check.py --commit HEAD --stages preflight`
also refused before tests with `Missing installed execution-interlock runtime;
reviewed installation required`. This is a local verification limitation, not
the reason general recovery is unsupported.

The first hosted diagnostic,
[41863587941](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41863587941),
was requested for exact source candidate
`dbd7c3601adf5f170b37d3f3b3e8b744b6ff9c58`.
[41865873570](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41865873570)
was requested for `6e473d56ab798ca9bba1243b1c549158b64f3375`.
Both diagnostics were superseded and cancellation was requested when the
producer published further corrections. Neither is claimed as a passing test
result. The final prerequisite integration requires validation of its own
exact candidate.

Candidate `8b7213637e1ae8946d85c9f468011f58156cc7c6`,
[41866872988](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41866872988),
executed controller/policy checks successfully. Its Android job
`126703448994` discovered 4,926 protocol tests and failed two inherited
`ChannelWriteDispatchTest` dynamic cases with their injected
`IllegalStateException`s. `:core:services:compileTestKotlin` completed, but
`:core:services:test` did not execute, so this run does not verify the new
counterexample. The producer's `522f9a2` supervises the test-owned serializer
children while retaining the identity, disposition and completion assertions;
the correction is integrated rather than duplicated here. This source
correction still needs its own exact-candidate hosted result.

### Executed counterexample

Exact source candidate `5960ce2ee8728bcfcbbf3439eb4ef46410a025eb`,
[run 41868989694, Android job 126711775868](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41868989694/job/126711775868),
passed the Linux Android build. The original hosted log records
`core/services`: 2,890 discovered, 2,890 run, 2,890 passed, zero failures,
errors or skips, and explicitly lists
`completeIdentifiedReplacementSnapshotsDoNotProveAnOldRadioWriteCannotStillApply()`
among the executed methods. Thus the counterexample, including manual-refresh
refusal and the later old-command mutation, actually executed successfully.
The hosted controller/policy job also passed.

At the last inspection the broader workflow was not complete: the Swift codec
oracle was cancelled and the SwiftData oracle was queued. Neither is claimed
as passing, and no full-workflow or merge verification is claimed. Those
unrelated oracle outcomes do not replace or negate the completed Android
job's actual method-discovery evidence. This final evidence update changes
documentation only; the verified Kotlin bytes remain those of `5960ce2`.
No second CI-result bundle was retained or revalidated.
