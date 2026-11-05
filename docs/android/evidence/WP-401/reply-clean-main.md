# WP-401: isolated clean-main REPLY journal prerequisite

Repository: `cbattlegear/MeshCoreOne-Android`.
Immutable clean base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
The commit containing this document binds this extraction's source and controls.
This is an unactivated Android-only prerequisite for #514, not completion of WP-401.

## Extraction and ownership

Only the owned `NotificationReplyStagingStore`, `NotificationReplyStagingStoreTest` and
`NotificationReplyLifecycleTest` were extracted from historical
`f2d8f9a7b4d18df4764f4f2c414c2cfd0d51769b`. The new candidate has current main as its
parent, not the held #515/#495 history. Their branches, receipts, Activity, OPEN store,
payload contracts, platform Intent codec and application composition remain untouched.
The historical documentation was not imported.

Current-main `NotificationPayloadCodec` has Intent encode/decode only, not persistence.
The journal therefore owns a small, independent `ReplyRoutingCodec` within its admitted
source file. Its canonical version-1 formats retain exactly the main direct/channel fields:
`1|direct|radioUUID|contactUUID|messageUUID` and
`1|channel|radioUUID|decimalChannelIndex|messageUUID`.
UUIDs must be canonical, channel indices 0..255 without alternate spellings, and versions,
field counts and kinds must match exactly. Unsupported payloads fail explicitly.
This does not import held display-target/OPEN behavior or invent an occupant/original-key witness.
An independent literal storage vector and channel-boundary cases supplement the extracted controls.

The private routing format is not historical OPEN/Intent JSON. The substrate is not deployed;
no legacy-data compatibility is claimed. If supplied an incompatible/corrupt journal, operations
fail closed and preserve it rather than silently upgrading, replacing or emptying its contents.

The trusted `wp-owned` create admission passed for all three new source/test paths and this
WP-owned evidence document. Automatic reservation bookkeeping with the actual session and
clean immutable base refused the historical lease's different source/base/manifest/backend.
No historical lease was rebound, released, or edited. Isolated admitted source work is advisory;
this is not autonomous-worker/merge admission or permission to clear retained execution ownership.

## Callable staging and recovery contract

Construction requires caller-provided `NotificationReplyLimits(maxRecords, maxRecordBytes,
maxTotalRecordBytes)`. There are no production defaults, eviction, expiry, compaction, deletion
or selected retention policy. Intake bytes account for serialized record metadata, routing
and exact Java String code units, not SQLite page/journal/filesystem overhead.

`initializeNew()` requires external fresh-install proof; ordinary reads/stages never create
missing storage. This substrate does not implement a known-initialized marker or distinguish
total storage loss from first install. Those ingress/bootstrap decisions remain unavailable.

`stage(NotificationReplyPost(1, postUUID, "REPLY"), typedPayload, exactText)` checks structural
identity only. It does not authenticate post provenance or identify a new per-submission send.
It atomically stores raw text, immutable original routing payload, a record identity
`(storeUUID, recordUUID, sequence)` and reserved outgoing-message UUID. No outgoing row is created.
Exact token/payload/text replay returns the same identity and UUID. Differing text or payload
retains a separate **unbound conflict**, never overwrites the first text, and never authorizes
another send or relabels a current radio/contact/channel as the original.

Stage outcomes are `Staged`, `AlreadyStaged`, `ConflictRetained(identity, alreadyRetained)`,
`Rejected(reason)` and `Indeterminate(post, proposedRecordId)`. Rejection is **not retained**,
not handled/success. Invalid input/limits, policy mismatch, record/private-data capacity,
missing/unavailable/corrupt storage are explicit. Ambiguous writes are reconciled against
the exact token/payload/text; unresolved state never creates replacement send authority.

`read()` is side-effect-free with respect to claim/consumption and returns a single-transaction
`Inventory` or `Failed(reason)`. Inventory contains the complete sequence-checked retained
primary/conflict rows within the explicit budget. Both expose stable identity, original post
and exact text; `StagedReply` additionally has immutable payload/outgoing UUID and optional
custody receipt. `UnboundConflict` has optional custody but no route or outgoing UUID.
All entries expose `targetAuthority=UNPROVED`. No text is logged or included in result/error
string representations. RemoteInput whitespace, NUL, combining characters, surrogate pairs
and isolated UTF-16 code units are retained without trim/normalization/replacement.

Recovery consumer policy remains a separate recovered-items list with explicit copy to a
user-selected chat. Reading an entry does not adopt, relabel, send, delete or retire it;
this substrate implements none of those UX actions. Unknown/replaced targets stay unbound.
No synthetic origin/key/revision or six-byte-prefix recipient proof is introduced.

## Conservative durable custody, never send authority

`initializeLifecycle(maxTotalReceiptBytes)` explicitly migrates an existing version-1 journal
to version 2 in one transaction. A separate caller-selected serialized-receipt budget includes
digest bytes, not SQLite disk overhead; a replay with a different policy fails. Migration,
capacity or mutation failures never evict text or replace identities.

`claim(identity, callerRetainedClaimUUID, ownerUUID)` returns `NewlyClaimed` only for the first
atomic UNCLAIMED -> CLAIMED_POSSIBLE_SEND transition. Exact replays only return `Observed`;
other owners are `Fenced`, across separate handles and store recreation. A claim UUID cannot
be reused for another record. Conflicts are UNBOUND and cannot be claimed. A custody receipt
binds identity, revision, state, claim, owner and optional typed uncertainty; it is not a
radio receipt, authenticated ownership witness or permission to dispatch.

`settleIndeterminate(exactExpectedReceipt, reason)` is the only settlement, comparing the exact
claim/owner/revision before persisting revision 2 and uncertainty. Replaying an identical
settlement observes it; stale or differing settlements cannot overwrite it. There is no
expiry, takeover, release, reset-to-unsent, automatic resend, accepted/definitely-unsent or
notification-retirement API. An abandoned or cancellation-hidden claim remains a possible send.
Recovery can conservatively mark it INDETERMINATE, not infer dispatch never happened.

Both consumer and dispatch owners confirm no actual durable terminal dispatch-evidence API exists.
Merged `NotificationActionReceipt` is process-memory only. Transient protocol outcomes, missing
ACKs, FAILED/PENDING rows, fresh UUIDs and current full keys do not fill that gap. Definitive
settlement requires separately admitted durable evidence bound to original action, outgoing ID,
claim/attempt and actual dispatch owner's trustworthy terminal proof. Actual target/mutation/RF
authority, firmware prefix ambiguity (#559), channel provenance/fence (#557/#550), private ingress,
external bootstrap policy, recovery UX and retention remain integration prerequisites.

Every intake/custody mutation validates retained row bytes and complete metadata inside the same
SQLite transaction before reporting success. Integrity digests bind store identity; schema,
sequence gaps, integer narrowing, ignored/partial writes, missing custody and tampered policy
fail closed. Hashes detect corruption, not an attacker able to rewrite the database and digests.
The internal lifecycle test observer loses/cancels a return after an actual commit; it is not
a dispatch witness or runtime registration.

## Verification status and independent discovery handoff

The old [f2 hosted result](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41889202784)
is **historical only**. It does not verify this clean-main extraction or its independent codec.
No new candidate has been pushed, no hosted run dispatched and no PR opened before independent review.

Native Windows pinned Python is 3.12.4. The documented `ci.py preflight --local --state
$env:ANDROID_CI_STATE --output <fresh-absolute-output-directory>` refused when the existing
JDK's `java -version` returned exit 1. This is host-readiness refusal, not failed product tests.
The generated JVM crash file was removed; no runtime installation, SDK/license changes or local
Gradle execution occurred. The retained execution owner and lock remain untouched.

`python tools\android-port\controller\validate.py` passed.
`python tools\android-port\portmap.py` passed.
`git diff --check` passed.
These structural checks do not prove Kotlin compilation or SQLite execution.

The existing, unchanged notification Gradle module maps
`src/androidTest/kotlin/com/meshcoreone/android/platform/notifications/messaging`
into `test`; it already declares Robolectric and coroutine/test dependencies. The declared module
task is `:platform:notifications:testDebugUnitTest`, not connected device instrumentation.
Expected authored cases: 28 intake methods and 16 lifecycle methods, each configured with
`@Config(sdk = [31, 37])`. The expected 56+32 execution count is **not an execution claim**.
The discovery owner supplies the exact admitted current-candidate executable selectors and
positive per-class discovery controls; no build/discovery paths are duplicated here.

Required real-SQLite controls include multi-handle duplicate/conflicting intake and custody,
exact text and routing vectors, byte/record budgets, reopen and missing-storage behavior,
transaction ABORT and IGNORE, schema/row/policy corruption, stale receipt/claim fencing,
no resend after restart, ambiguous committed returns and cancellation after commit.
Only a subsequent exact-head job/log with positive actual class discovery can verify this candidate.
No duplicate CI-result bundle is committed or retained.

## Review correction: literal SQLite internal prefix

The immutable initial extraction `e050e9a9b9561caf53d61b95c749320c944c1a77` is retained
as an ancestor, not amended. Coordinator review found that SQL `LIKE 'sqlite_%'` treats
the underscore as a wildcard and hides valid foreign `sqliteX...` objects. Both schema
queries now use `GLOB 'sqlite_*'`, whose underscore is literal, preserving SQLite's
reserved internal prefix and the exact Android metadata-table exception.

Real SQLite controls now cover a version-zero foreign `sqliteX_user_data` table and
`sqliteX_foreign_view` view, plus the foreign table in initialized v1 and v2 journals.
They assert corruption/refusal, unchanged schema version, retained foreign content,
no new rows, and restoration of the original journal identity/text after removing only
the injected table. A positive control retains actual `sqlite_sequence` and
`android_metadata` exclusions through bootstrap, lifecycle initialization and reopen.
These controls are authored, not locally executed; no Gradle or retained-lock change occurred.
