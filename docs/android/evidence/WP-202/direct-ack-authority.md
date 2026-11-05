# Direct ACK storage authority

## Candidate boundary

This is an independently authored WP-202 repair based on published main
`41552c85b659396111cee6b0e0f70c404ac5a8ee`, not completion of the macro WP.
The pinned Swift specification remains
`db14559b39d32322b06477c6ae676112f583db50`.
The WP-201 additive contract is the exact patch from
`93125597154c8ae28041f6781c5fb851d2b6188b`, integrated as `cc5901f30`.
No held channel candidate, historical direct-ACK candidate, consumer patch,
schema export or foundation ancestry was imported.

The active channel owner confirmed there is no live main-bound schema-version
allocation/migration reservation. Main's only published schema was v1.
Trusted `persistence-schemas` admits the independently authored v1-to-v2
migration; `wp-owned` admits the repository, lifecycle, tests and evidence.
Schema-coupled tests use `reviewed-support-test`. Domain contract authorship
remains WP-201, not an interpretation of the generic consumer capability.

## Storage design

Room v2 adds persisted message/pending incarnations, message/pending mutation revisions,
session leases, logical claims with last-own-commit snapshots, immutable delivery
snapshots, additive wire-code bindings and exact-operation journals.
Every supported message/pending INSERT, UPDATE and DELETE fires persistent
SQLite triggers, including no-op status writes, DAO upserts, raw SQL replacement
and key changes. A typed operation validates the lease, incarnation, revision
and current grant in its actual Room transaction. Only its expected revision
advance can renew its own grant; nested extra mutations fail and roll back.
This is not a Boolean affected-row or matching-DTO attribution rule.

The raw-write boundary is message/pending DML on the managed database.
Arbitrary DDL, dropping/replacing authority tables/triggers, or manufacturing
authority records is not a supported application write interface. The producer
verifies canonical trigger definitions inside each transaction and fails
explicitly when they are missing/changed. New production databases use
`DirectAckStorage.callback`; existing v1 files use the non-destructive migration.
Custom Room builders must install that callback. A builder without fencing
cannot silently obtain the capability.

The separately authored WP-201 no-dispatch contract
`80e8ebc33ac2730c2e45845333425284c7e4f77a` is integrated as `994388ac4`.
Its independent capture/completion role persists exact pending/message snapshots
and lease/incarnation/revision bindings after all legitimate finalization writes.
Actual outgoing DM rows are required; normal delivered cleanup excludes resend,
and zero-attempt metadata must be exactly zero. Completion deletes only the
captured pending incarnation, journals the operation and does not create or
renew any ACK grant. Generic pending deletion still invalidates prior ACK claims.

Opaque handles additionally bind the issuing repository context. Durable lease
history orders same-epoch generations and retires earlier epochs; activation
request replay cannot replace a successor. New handles cannot adopt old issued
handles. Process-store close revokes its leases, independently of radio teardown.
Backup restore invalidates grants in its commit transaction even when the
importer would skip every message. Failed restore rolls invalidation back.

Logical claims precede dispatch. Predicted and returned codes from all wires on
the same live claim remain eligible. Only typed retry/status/send-count and exact
pending completion operations preserve the prevalidated grant. Generic retry
or queue mutation invalidates it. Explicit resends preserve terminal baselines;
acceptance never uses a temporary FAILED-to-SENT relabel. Matching delivery is
one transaction and includes its immutable committed DTO/code/RTT receipt.
Historical exact-operation replay never renews authority or deletes a replacement.

## Verification state

Source-only preparation passed:

- `C:\Python312\python.exe .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $output`
  with the existing same-host state and a fresh absolute TEMP output directory:
  exit 0, pinned Python 3.12.4. The documented verification-venv path was absent;
  the available pinned interpreter executed the successful preflight.
- `python tools/android-port/controller/validate.py`: exit 0.
- `python tools/android-port/portmap.py`: exit 0.
- `git diff --check`: exit 0.

The new source contains 19 repository and four database test methods, including
actual Room migration from the checked-in v1 export, same-value independent
FAILED controls before/after own commit, cancellation before/after commit,
explicit rollback failure, nested mutation revision fencing, typed retry codes,
terminal resend, queue replacement, raw/DAO writes, separate contexts/handles,
file reopen, restore and revocation. These are authored assertions, not discovered
or passing tests. No Room/KSP/Gradle execution or generated v2 schema is claimed
at this source-only stage.

Own advisory reservation enrollment was attempted using the original assignment
prose, session `f8058663-e138-4456-9d4d-6ad11522824c` and base `41552c85`.
The supported command refused: `Existing WP lease is bound to different
source/base/manifest/backend`. No historical receipt was inspected, transferred,
released or rewritten. Exact `add-migration` and
`update-directly-required-entity-or-dao` admissions pass for this repair; a
generic `create` probe on the database path correctly refuses. The coordinator
reviewed the refusal against trusted main `docs/android/AUTOMATION.md` and
confirmed this is advisory historical binding mismatch, not a pre-edit lock or
live semantic collision. Assigned, capability-admitted isolated authoring
continues after recording the actual attempt. No receipt is claimed as acquired;
no owner/authorization/binding/ledger mutation or held-work release occurred.

Source review after the first diagnostic dispatch added a conflicting-pending
insert guard: raw `INSERT OR REPLACE` remapping a pending key to a different
message must fence both old and new messages, including an inline ACK claim
with no captured pending ID. This is a conservative DML-attempt fence for that
conflicting remap; ignored conflicting inserts may also invalidate the old
claim, never grant permission or change message content. Its two-message control
is a successor assertion, not a result of the earlier exact-head run.

Local Gradle remains blocked by retained execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` and missing canonical cleanup proof.
There is no bypass or ownership release. A separate admitted tooling producer
is preparing first-export-only hosted schema recovery; only genuine KSP output
may become the v2 schema. Hosted exact-head results/logs, not a second result
bundle, will be authoritative.

The concrete service/repeater regression and original successful ACK families
remain the consumer owner's work against this producer. Old Boolean-wrapper
tests are not evidence of typed producer calls. No durable ACK ingress,
restart recovery, device/radio, whole-WP, release, signing or merge acceptance
is claimed.
