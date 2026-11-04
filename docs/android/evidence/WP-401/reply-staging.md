# WP-401: unactivated REPLY staging prerequisite

Repository: `cbattlegear/MeshCoreOne-Android`. Parent candidate:
`0e94fd78cc32816087832ad2b0ba5acb67d2a655` (#515, OPEN-only).
Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
This is Android-only intake infrastructure for #514, not completion of WP-401 or activation.
The commit containing this document binds the implementation bytes; hosted exact-commit
results/logs remain authoritative and are not copied into a second result bundle.

## Contract and native adaptation

`NotificationReplyStagingStore` is a separate private, no-backup SQLite database. Nothing in
the receiver, Activity, dispatcher or application instantiates it. The OPEN store is unchanged
and cannot retain RemoteInput.

- `NotificationReplyPost(version=1, postId: UUID, action="REPLY")` is structurally checked,
  **not authenticated**. Future ingress must authenticate the real private action provenance.
- `stage(post, payload, exactText)` atomically retains the typed direct/channel routing payload,
  lossless original Java String code units, stable record identity and reserved outgoing-message UUID.
  Allocating a UUID does not create an outgoing row, receipt or authority to send.
- An exact token/payload/text replay returns the same record and outgoing UUID. A changed text
  or target produces a separate durable `ConflictRetained`; identical conflicting attempts
  reconcile by full byte equality, not a hash. The first reply never changes.
- `read()` uses one transaction and returns a complete sequence-checked inventory within the
  explicit record budget. `UnboundConflict` exposes its identity, attempted post and raw text,
  **no routing target or outgoing UUID**. Conflict IDs are recovery identities, not new send tokens.
  Conflicting payload bytes remain private comparison evidence, never a draft destination.
- Every recovery entry explicitly has `TargetAuthority.UNPROVED`. Current payloads lack an
  admitted versioned original-key snapshot. Display-target metadata is not authenticated RF
  origin; channel receive origin remains Unproved (#557). No current entity/key lookup occurs.
- Every caller supplies `maxRecords`, `maxRecordBytes`, and `maxTotalRecordBytes`.
  The byte accounting is the exact serialized record header plus payload/text BLOB lengths,
  not a disk/page/journal quota. Limits persist with the journal; mismatches fail explicitly.
  There are no production defaults, selected limits, eviction, expiry, compaction or retention policy.
  Exact replays remain possible when full; rejected attempts are explicitly **not retained**.
- Missing, invalid, unavailable/corrupt, record/private-data capacity and ambiguous-commit
  outcomes are typed. A commit error rereads the same exact token/payload/text; unresolved state
  is `Indeterminate`, never a fresh logical action or claimed retention. No reply text is logged
  or included in result/error string representations. Cancellation can hide a committed return;
  the caller must reconcile the identical input, never mint a replacement token.
- `initializeNew()` requires external fresh-install proof; ordinary stage/read never create
  missing storage. Unknown schema/version and corrupt bytes are preserved, not repaired/deleted.
  SQLite transactions use synchronous FULL; no cross-store or device-fsync guarantee is claimed.

## Ownership and checks

The OPEN producer confirmed sole successor ownership of the new REPLY files; action consumer
and dispatch/receipt owners were coordinated directly. No existing producer candidate, locks,
runtime deployment, shared policy, schema, receiver or consumer file was changed.

Trusted `reserve.py admit --wp WP-401 --path <path> --operation create` passed for the new
messaging production/test files and this WP-owned evidence document (`wp-owned`).
The automatic claim attempt with the actual session
`8f78a704-0aaf-4e32-8db8-73d3b17520bc` and immutable parent refused:
`Existing WP lease is bound to different source/base/manifest/backend`.
Historical ownership was not rebound/released or manually edited. Per the current advisory
policy this does not forbid admitted isolated source edits; live/merge admission is not asserted.

Native Windows pinned Python `3.12.4` was used. The documented suggested verification-venv
path was absent; the existing pinned `python` interpreter successfully ran
`python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh-absolute-temp-directory>`.
This proves host readiness only, not product tests.

`python tools\android-port\controller\validate.py` and
`python tools\android-port\portmap.py` passed before committing the candidate.
`git diff --check` passed.

Authored real SQLite Robolectric cases run on API31 and API37 through the existing notification
module test source mapping. They cover lossless whitespace/NUL/combining/non-BMP/isolated-surrogate
text, stable identities/reopen, fresh-token identical sends, differing text/target conflicts,
concurrent independent handles, both capacity budgets and exact thresholds, replay while full,
transaction-trigger rollback, invalid kind/version/payload/text/limits, policy mismatch,
missing/corrupt/unavailable storage, tampered/gapped rows and unknown schema preservation.
Authorship is not execution evidence.

The immutable local check on `e04dca12eb1e3f0371c6fc1f5922f89794731fdb`:
`python tools\android-port\local\check.py --commit e04dca12eb1e3f0371c6fc1f5922f89794731fdb --affected-base <trusted-origin-default-SHA> --scope module:platform/notifications`
refused `Installed execution runtime is old or mismatched: controller/authority.py`.
No product tests executed locally; the interlock was not bypassed or redeployed.
That candidate was published explicitly unverified and the existing supported
`gh workflow run android-ci.yml --repo cbattlegear/MeshCoreOne-Android --ref cbattlegear-notification-reply-staging`
created run [41878818155](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41878818155).
The run API confirmed its exact head was `e04dca12eb1e3f0371c6fc1f5922f89794731fdb`;
it cannot verify later corrections.

Coordinator source review found a 64-bit SQLite INTEGER narrowing hole in the initial candidate.
Stored action version and `max_records` now undergo explicit Long-to-Int range checks before
conversion. Real SQLite tampering regressions include positive/negative 2^32 aliases and both
Long extrema, retaining the original digest/policy and proving rejection rather than truncation.
A corrected exact successor requires its own hosted result.

## Outstanding integration prerequisites

No claim CAS/revision, settlement, send, durable receipt, recovery presentation, retention decision,
receiver wiring or process-death delivery claim is included. All remain unactivated.

The producer confirms no actual persisted stable contact revision/original-key snapshot contract.
Caller-ID persistence at `eace2cfc9895958f77c56bf05e24e2d1f80bd1c3` does not bind original key
or action identity. `NotificationActionReceipt` is in-memory only, not a durable journal receipt.
Do not treat FAILED status, a fetched current key or a staged outgoing UUID as retry authority.

Actual direct dispatch needs an admitted original snapshot and mutation/ABA-safe guard; even a
local full-key match or local prefix-uniqueness scan is insufficient RF proof. Firmware sends to
the first matching six-byte public-key prefix; local and radio contact ordering may differ.
An authoritative radio contact set/dispatch API or protocol change remains necessary. Channel
dispatch additionally requires the shared occupant/mutation fence (#550); reused ChannelDTO IDs
or display metadata are not incarnation/RF proof. No synthetic revision or identity is introduced.

Future activation must jointly verify original snapshot/provenance, one-send/token durable
claim/receipt/settlement, possible-send indeterminacy, caller-ID create/reconcile, recovery UX for
unbound conflicts and not-retained attempts, external bootstrap/data-loss detection, and an
explicitly selected private-data/retention policy. API31/37 device/process-kill/hardware evidence
has not been produced by this slice.
