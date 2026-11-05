# WP-401: externally bound installation inspection and atomic intake

Repository: `cbattlegear/MeshCoreOne-Android`.
Clean-main base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
Source reference remains `db14559b39d32322b06477c6ae676112f583db50`.
This nonactivated successor retains the source-reviewed `12b86bbbd` journal and controls.
Its exact source is bound by the commit containing this document.
The earlier exact-composition diagnostic `72abb4f55516d3b01b311c70c689b8848727369c`
and [run 41964293724](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41964293724)
remain separate; their eventual result cannot verify this later successor.

## Truthful installation boundary

The producer and receiver owners confirm there is **no currently implemented fresh-install
provisioning witness or durable issued-action registry**. Missing database, missing marker,
package first-install time and data-directory absence cannot distinguish initial provisioning
from restore, app-data clear or lost/corrupt state. A local marker sharing the same loss domain
does not solve this ambiguity. No new witness, automatic bootstrap or silent reinitialization
is introduced here. Existing `initializeNew()` remains an explicit unactivated setup primitive
requiring external fresh-install proof; it must not be called by ordinary startup/receiver code.

`inspectInstallation(expectedStoreId: UUID?)` opens/validates existing SQLite state only:

| Result | Meaning |
| --- | --- |
| UnprovisionedOrLost | No journal and no external expectation; **not fresh proof** |
| MissingInitializedStorage(expected) | Expected installation journal is absent; do not recreate |
| UnboundExisting(observed) | Valid journal exists but no independent expectation was supplied |
| IdentityMismatch(expected, observed) | A different valid journal exists; do not adopt/rebind |
| Ready(store, schemaVersion, retainedRecords) | Existing complete journal matches supplied expectation |
| Failed(reason) | Invalid limits, policy mismatch, corrupt or unavailable storage; no success fallback |

The expected UUID must be independently retained by an admitted provisioning/issuance owner.
Reading `UnboundExisting` and copying its observed UUID into `expectedStoreId` is not
authentication, installation provenance or production bootstrap authorization. Fixtures do
that only as explicit test setup. This API makes the unknown state visible rather than
pretending to prove what the platform state cannot prove.

## Atomic storage admission and read-only recovery

`admit(expectedStoreId, post, issuedPayload, exactText)` checks the expected identity **inside
the same SQLite transaction** that reconciles or retains reply bytes. A prior Ready inspection
is not sufficient; a valid replacement journal between inspection and admission is rejected.
Post, routing, exact UTF-16 text, record identity and reserved outgoing UUID retain the existing
one-logical-action/token semantics. Duplicate input returns the same IDs; differing text/route
becomes an unbound recoverable conflict, never another send or replacement-channel draft.

The result is the existing typed `StageResult`: Staged/AlreadyStaged/ConflictRetained mean
retained journal bytes, Rejected(reason) is **NOT RETAINED**, and Indeterminate means acceptance
cannot be established. Capacity, private-data, missing/corrupt/unavailable storage and identity
mismatch never report success, remove a prior record or create a missing database.

Commit-error reconciliation also checks the expected installation identity. Even an actual
replacement journal containing the identical post/payload/text cannot satisfy a lost return
from another installation. A still-matching retained row can reconcile to AlreadyStaged;
missing/unreadable/mismatched storage stays Indeterminate. No new identity or automatic resend
is created. The internal test-only post-admission observer can lose a return after a real commit;
it is not a production ingress witness.

`read(expectedStoreId)` checks identity in its inventory transaction and otherwise preserves
the complete side-effect-free read contract. It never adopts a replacement journal, claims a
row, settles a receipt, sends, copies, deletes, relabels, evicts or retires a notification.
All target authority remains UNPROVED. Old unbound `read()`/`stage()` remain for the existing
unactivated substrate/tests; they are not substitutes for production installation binding.

The caller still supplies all intake and custody budgets explicitly. There is no selected
production default, retention policy, eviction/TTL/delete, unbounded-memory fallback or
success-shaped full-queue response. A caller must visibly preserve/report a rejected or
indeterminate submission and cannot retire it as durably accepted.

## Missing authenticated issuance protocol

This is **storage binding only**, not the complete receiver admission protocol.
Current-main notification posting reuses id/action PendingIntent identity with UPDATE_CURRENT;
reissue can replace base extras. A post UUID is correlation, not durable issuance provenance.
RemoteInput mutability and a nonexported receiver do not independently supply unique durable
post/action identity or original RF recipient authority.

Before any real PendingIntent is exposed, an admitted producer must durably register a unique
issued-action token to its original typed payload and genuinely bound installation identity.
The receiver must resolve that registry rather than accept inbound payload extras; raw text
must be retained before process-queue acceptance or notification retirement. Registry lookup
and reply admission need a reviewed atomic durability/reservation protocol, including duplicate,
conflicting, stale/missing and replaced installation cases. A separate lookup followed by
unbound staging is not that protocol.

The journal and producer owners are coordinating exact storage ownership for that registry;
this commit does not claim it exists. No registry, trusted ingress, human-authentication,
external bootstrap proof, target/key/RF witness or terminal dispatch evidence is fabricated.
Actual first provisioning remains blocked until its real primitive is supplied/admitted.
No runtime registration, receiver, dispatcher, recovered UI or held #515/#495 file is changed.

## Controls and status

Real-SQLite API31/37 Robolectric controls are authored for absent/unbound/matching/mismatched
installation state; v1/v2 reopen; exact UTF-16 duplicate/conflicting admission; full-capacity
rejection without eviction; independent concurrent handles; replacement after inspection;
loss after initialization; corrupt/empty/unavailable state; policy mismatch; transaction
rollback; committed-but-lost return; missing store after commit; and a valid replacement
with identical bytes during ambiguous-commit reconciliation.

The host's known pagefile/JVM readiness problem and retained local execution owner remain
untouched. No local Gradle, lock repair, runtime deployment, active-ref update or new hosted
dispatch occurred for this successor. Existing source/ownership validators and authored method
counts are not Kotlin compilation or executable SQLite evidence. The discovery owner must
compose the exact successor with its guard and positively observe every committed named
method; previous historical or diagnostic results cannot be reused for these later bytes.
