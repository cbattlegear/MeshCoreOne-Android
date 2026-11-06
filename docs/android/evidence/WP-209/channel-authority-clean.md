# WP-209: independent channel authority successor

Status: incomplete source composition, not compiled or executed, no activation.

The existing session `3008e9f0-6e96-40ab-b810-8feee639494b` is assigned the
independently admitted current-main successor by coordinator assignment
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_JaHYHwlB0G1MZhlztb5AonE2`.
Swift remains read-only at `db14559b39d32322b06477c6ae676112f583db50`.

## Exact source and ownership

- Landed main `0d10b838a181cd3bd34af1a0200435149dbc6bb0` contains the accepted
  #603 reseed repair. Its results do not verify this successor.
- Source base `5ff2c83f0987d4ea3197f53468c245a377d102be` is f805's unaccepted,
  independently authored v2 reaction-storage successor. It preserves all five
  #603 files and leaves every origin unproven; no positive issuer is inferred.
- Canonical WP-201 operation/admission source
  `058da29fdd0c642eb82591c688c1ff8e1d5c4d6b` is composed without held ancestry.
  Its fourteen authored controls are unexecuted here.
- Exact WP-202 occupant storage source
  `b495ced4d54b9b348e2292232b258afe79f6ac75`, sole parent `5ff2c83`, was
  composed disjointly at `70fe86cc8e9a3d5b3e069f0fed435f099a2074fe`.
  Its ten database controls and migration are unexecuted; no schema 3 export
  has been generated or borrowed.
- f805 exclusively owns the new occupant database storage, shared database
  declaration, migrations, exports and database tests. Follow-on version 3 is
  provisional on the exact new inputs, not an accepted/global allocation. No
  historical v3/v4/v5 export or migration is reused.
- This session owns the serialized existing repository/restore consumers and
  related controls. a59 owns canonical operation/admission contracts and has no
  overlapping edit on the minimal read-only epoch interface.

Historical `2b97`/`757` source changes are attributed design evidence only. No
held `eca8`, combined-v4, #550/#561 or old service tree is imported. In particular,
the old row-ID rotation, stale missing-ID secret overwrite, message-origin
trigger dependency and restore callback rewrite are not copied.

## Implemented operation boundary

The process-shared canonical gate wraps ordinary channel repository writes and
device erasure with operation then identity exclusion. The scoped facade owns
the outer operation and uses the private exact-gate, active-Job capability for
inner writes. A scoped facade cannot reacquire its operation; an expired or
foreign-Job capability cannot write. There is no inherited-context permission.

Backup restore acquires the same lanes around the original context transaction.
Its `committedTransaction` and post-commit callback remain unchanged, including
the existing late-cancellation completion semantics. No `NonCancellable` scope
is introduced to acquire authority.

The gate does not validate radio/connection ownership. Canonical connection
admission is separate token/reference accounting; real lifecycle/lease checks
remain mandatory before any internal authoritative reconciliation. Ordinary
`saveChannel(info)` does not implicitly grant a live lease or wire provenance.

Five new actual-Room controls cover scoped metadata persistence, rejection of
ordinary/scoped reentry, foreign/escaped scope refusal, another repository
handle and restore waiting behind a held operation, and cancelled queued
writes and device identity erasure waiting behind the owned operation. Device
creation, deletion, ghost demotion and radio-identity reconciliation participate
as well as explicit data erasure; metadata-only device updates keep their
existing transaction behavior. Their data assertions do not substitute for epoch/restore
proof controls.

Eight additional real-Room controls exercise read-only historical epoch lookup,
known-ID secret invalidation, both enable transitions, exact reseed preservation,
mismatch refusal before row/epoch mutation, restore-wide invalidation and
transaction rollback, duplicate refusal during lookup/radio synchronization,
missing-trigger refusal without repair, and actual file-backed database
close/reopen, ordinary radio-info saves remaining unresolved, and radio erase /
recreation retaining an unresolved tombstone without reusing the old epoch.
Both single and batch synchronization refuse duplicate slots before mutation.
Restore-first ordering also holds the next operation outside until its actual
transaction commits invalidation; the earlier held-operation control covers the
opposite ordering. Neither control substitutes a fake protocol send for dispatch.
Positive persisted rows in these controls are explicit DAO storage
fixtures. They are **not** lifecycle-issued authority or evidence of live
reconciliation, original wire provenance, or simulator end-to-end resolution.

The public delegate returns an opaque persisted `String` value only for a
resolved, enabled, unique exact row binding with an existing radio device,
within one Room read transaction. Missing/unresolved/stale bindings return null;
duplicates and missing/incompatible triggers throw reported typed `InvalidData`.
Channel and participating device writes verify the exact trigger definitions
before their mutation. Restore invalidates every epoch in its existing Room
transaction before import writes, preserving rollback and original post-commit
semantics.

The accepted reseed controls retain every original assertion. The actual
empty-store import now also checks the persisted unresolved/null-binding record
and exact epoch preservation across three fixed-DTO replays. An additional
actual `AppBackupService.importBackup` control starts from an explicit resolved
DAO storage fixture, injects a pre-commit failure and checks complete row/epoch,
failed-message and pending-text preservation, then commits the same import and
checks durable invalidation and three subsequent no-op replays. This remains
storage/import evidence, not a live positive-authority issuer.
Another actual import control cancels immediately before commit and checks
epoch rollback, then cancels the caller after commit and checks successful
committed-result delivery, persisted unresolved invalidation, and a subsequent
writer acquiring the released operation/identity lanes. It does not interpret
post-commit cancellation as rollback.

## Preservation and pending composition

The #603 missing-ID matching-slot fallback remains a true no-op preserving the
whole row. Mismatch and ambiguity refuse before mutation. Known-ID row identity
and actual-radio replacement/cascade behavior remain unchanged. The separate
opaque persisted epoch invalidates independently of the stable row ID;
unresolved authority must be witnessed as persisted state, not inferred only
from a null public lookup.

Missing work: the producer's real schema export and composed execution;
guarded reconciliation bound to the actual WP-207 manager/session lease, without
inventing an issuer; adapt the real simulator reconnect controls to the selected
opaque-epoch contract, preserving customized metadata and history/pending.
Database producer controls cover raw-writer, ABA and migration storage semantics,
but do not prove all-writer dispatch exclusion. Any runtime activation remains
excluded. #601 stays open until actual composed acceptance is reviewed/executed.

## Checks and limitations

Trusted named `modify-related-consumer` and `add-or-update-related-test`
admissions passed for the repository/helper/control paths; canonical contract
composition uses `reconcile-current-base`. A historical advisory claim's
different binding was retained unchanged, not impersonated or reset. Trusted
ADR 006 and controller rendering explicitly distinguish that bookkeeping
mismatch from current assignment/admission/live-overlap authority.

`git diff --check`, `python tools\android-port\portmap.py` and
`python tools\android-port\controller\validate.py` passed during initial helper
composition. No Kotlin compilation or JUnit execution occurred. The retained
local execution owner was not changed or bypassed. Hosted results belong only
to their exact candidate and are not copied into a second result bundle.

The independent `5ff2c83` hosted run `42151470903` failed seven producer-owned
reaction-storage controls; it is not acceptance for this successor. Exact
producer repair `7f3e696bff86c3d1c4d43d36464cae646064018b` was composed
disjointly, retaining `SCHEMA_VERSION` rather than regressing to a schema-2
expectation. It fixes canonical UUID SQL fixture bindings, optional legacy
export indices and failure diagnostics only; remaining producer failures and
fresh execution are still pending. No result or schema export is transferred.
