# WP-209 truthful contact-write recovery observation

Continuation of [#517](https://github.com/cbattlegear/MeshCoreOne-Android/issues/517)
for [#495](https://github.com/cbattlegear/MeshCoreOne-Android/pull/495).
Owner: services-porter, session `d8dc94fa-61ec-4e2f-9dad-3a809582d105`.
Pinned Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
Authoring parent: `30ef44447d143f0555aded3865412e823a78027f`.
The four pre-existing worktree drafts are excluded. The independent
[#553](https://github.com/cbattlegear/MeshCoreOne-Android/pull/553) acknowledged-write
safety/settlement producer retains its own changes.

## Contract

`ContactService.contactWriteRecoveryState(RadioId): ContactWriteRecoveryState`
is a synchronous, radio-scoped **observation**, not an import result, an admission
lease, a generation receipt or a reconciliation method. Public types are in
`com.meshcoreone.android.core.services.contacts`.

| State/reason | Meaning for presentation |
|---|---|
| `Clear` | No recovery obligation currently recorded; an exchange can still be in progress. Never navigate or acknowledge from this observation. |
| `Required / RADIO_OUTCOME_UNCERTAIN` | The radio may have applied the dispatched change. Cancellation did not undo it. |
| `Required / RADIO_ACCEPTED_RECONCILIATION_REQUIRED` | The radio accepted a change. The observation cannot authorize settlement of the cached state. This is not UNKNOWN. |
| `Required / OPERATION_OUTCOME_UNAVAILABLE` | An obligation has no associated dispatch receipt; do not infer acceptance or rollback. |
| `Required / STORED_MARKER_WITHOUT_PROVENANCE` | A true flag exists without the original process's exchange evidence. |

`ContactWriteMarkerRecording.RETRY_PENDING` remains visible after a failed
true-marker set, even if the preference's in-memory bool became true.
`SET_SUCCEEDED` means only that the injected preference role reported success;
`LOADED_FLAG_UNVERIFIED` does not independently prove durable recording.
Observation never sets preferences, retries a failed write, creates or clears an
epoch, dispatches a command, or changes write/sync admission. Preference read
failures propagate rather than masquerading as `Clear`. Existing guarded-access
retry behavior and `needsContactWriteReconciliation` are unchanged.

Cached contacts remain readable. Other radios retain independent admission.
The UI owner must map the typed reason into localized explanatory/recovery
presentation and retain its cancellation/no-stale-navigation behavior. Suggested
meaning, not a new unlocalized runtime string: **The contact change may already
be on this radio. Cached contacts are available, but further contact changes are
paused until the final state can be verified. Reconnecting does not confirm or
undo the change.** For known acceptance, say the radio accepted the change rather
than calling its outcome unknown. A failed marker write also needs explicit
storage/retry explanation; observing state is not that retry.

## Why no automatic recovery is added

Read-only research inspected public `meshcore-dev/MeshCore` commit
`81dbae592ffad40b4212bc0b37e82b38da08f4bc`, not a claim about a tested/deployed
radio or an advancement of the pinned Swift specification:

- [`MultiSerialInterface.h:160-197`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/src/helpers/MultiSerialInterface.h#L160-L197)
  broadcasts replies to enabled ingresses and reads the first available frame in
  interface registration order. A response on one ingress is not an all-ingress
  queue-drain receipt.
- [`MyMesh.cpp:1055-1095`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp#L1055-L1095)
  returns firmware/build information and stable self identity, not a fresh
  boot/incarnation nonce or acknowledged global command barrier.
- [`MyMesh.cpp:1238-1257`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp#L1238-L1257)
  starts a contact iterator; it does not purge or fence pending ingress writes.
  [`:2389-2408`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp#L2389-L2408)
  processes an available command ahead of each iterator step.
- [`MyMesh.cpp:1328-1357`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp#L1328-L1357)
  mutates the contact then emits an untagged OK and schedules lazy storage.
  [`:1512-1516`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp#L1512-L1516)
  reboots without a returned incarnation receipt.
- [`SerialBLEInterface.cpp:104-127`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/src/helpers/esp32/SerialBLEInterface.cpp#L104-L127)
  enqueues RX frames separately from disconnect notification;
  [`:195-220`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/src/helpers/esp32/SerialBLEInterface.cpp#L195-L220)
  dequeues a frame before processing the disconnect transition/buffer reset.
- [`companion_protocol.md`](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/docs/companion_protocol.md)
  recommends one command at a time and response matching by type; it does not
  document an authenticated cross-ingress execution barrier.

These observations do not prove every supported firmware variant lacks another
guarantee. A dedicated firmware investigation owns that wider question. They do
prevent treating a reconnect, battery/time response, repeated complete snapshot,
stable public key or app-side coroutine join as the missing proof.

The remaining prerequisite is a positively correlated, radio-incarnation-bound
execution/drain guarantee for all ingresses that can still apply the old write,
followed by a complete authoritative snapshot with current-generation authority
held across transactional persistence and conditional recovery-epoch clearing.
Persisted markers without prior-process provenance need that guarantee too.
No firmware changes or reboot behavior are added here. No reconciliation/clear
API is exposed without this authority.

## Admission and verification

`reserve.py admit --wp WP-209 --path <path> --operation modify` admitted all five
source/test/evidence paths through `wp-owned`; pinned-source, declared-verification,
one-WP/branch/PR and evidence-provenance invariants apply.
The six authored `ContactWriteRecoveryStateTests` controls use actual
`MeshCoreSession` and controlled raw frames for service observations, with an
explicit in-memory persistence/preferences role. They are not Room, Android
disk/process-death, radio or device acceptance evidence.

- Native Windows/Python 3.12.4 readiness:
  `python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute temporary directory>`
  passed before editing.
- An initial `check.py --worktree --affected-base <origin/main>` invocation was
  refused before execution because affected verification requires an immutable
  commit candidate. This is not a test failure or test discovery.
- Exact immutable candidate execution and static validation are recorded in the
  successor handoff/PR; no unexecuted controls are reported as passing here.
