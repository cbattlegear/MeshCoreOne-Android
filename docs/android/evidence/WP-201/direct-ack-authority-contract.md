# Direct-ACK producer contract repair

## Binding and scope

- Repository: `cbattlegear/MeshCoreOne-Android`; WP-201 contract-only repair.
- Base: `41552c85b659396111cee6b0e0f70c404ac5a8ee`, clean at assignment.
- Source: `db14559b39d32322b06477c6ae676112f583db50`; not advanced.
- Trusted manifest SHA-256:
  `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
- Trusted manifest Git blob: `e81605857f5aa7b893dcd63224fb27a45d7b0f3a`;
  policy Git blob: `b12794157978d6d90ab7508d4e2852fdc2d5e4a4`.
- Actual session: `9d271f61-91db-4576-ab7f-129c1019f33b`.
- Storage coordination: `f8058663-e138-4456-9d4d-6ad11522824c`;
  service coordination: `13a03908-afb8-42bd-9c96-57a27920076e`.

The immutable handoff is the commit containing this document and the two new
Kotlin files. It changes no existing interface, consumer, assertion, DAO, schema,
migration, build configuration or source inventory. It is not completion of the
storage repair or WP-201 as a whole. No held candidate was inspected or imported.

## Actual admission and reservation result

The trusted `origin/HEAD` and `origin/main` both resolved to the base above.
`python tools\android-port\local\reserve.py admit --wp WP-201 --path <path>
--operation <operation>` returned these actual admissions:

| Path | Operation | Capability |
| --- | --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/DirectAckAuthority.kt` | `create` | `wp-owned` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/DirectAckAuthorityTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `docs/android/evidence/WP-201/direct-ack-authority-contract.md` | `create` | `wp-owned` |
| `docs/android/deviations/WP-201.md` | `modify` | `wp-owned` |

The initial WP-201 claim, with the actual session/base and contract/evidence paths,
returned `BLOCKED: Existing WP lease is bound to different source/base/manifest/backend`.
No lease was transferred, released, recreated, inspected for custody or edited.
The current local-authoring rule in `AUTOMATION.md` permits isolated admitted
authoring after recording this historical advisory mismatch. The assignment
identifies this worktree as the sole new contract producer; storage and service
owners retain their distinct implementations. Admission is not merge acceptance.

## Agreed API and behavioral boundary

`DirectAckAuthority` is a separate capability, not a default implementation or a
new parent of `MessagePersisting`. Its opaque lease/attempt markers have no public
identity fields. The storage producer must privately issue and authenticate them.

Activation uses caller-known request identity, monotonic same-epoch generation,
retired-epoch fencing and atomic revocation. Logical begin registers the first
wire request and predicted ACK before dispatch, binding the actual message
incarnation/revision and optional exact pending-row incarnation/revision.
Additional wire requests retain prior codes; actual acceptance adds its code to
that same registered wire identity. Settlement can use a predicted early ACK,
without waiting for the send call to return.

Own failure, atomic ACK settlement, idempotent retry bookkeeping and exact pending
completion validate persisted ownership in the write transaction. Generic
message/queue writes, even same-value writes, invalidate authority across raw
DAOs, retained delegates and other Room handles. Restore entries invalidate even
when import skips the row. The three bookkeeping variants are `RetryStarted`,
`RetryExhausted` and `ResendCountIncrement`; request-ID journaling prevents double
increments after commit-then-throw. Pending completion must not delete a
replacement row; a generic delete affecting zero rows does not mutate a row.

Explicit `SEND`/`RESEND` mode preserves existing resend entry behavior for
DELIVERED/FAILED baselines. Initial status is distinct from this claim's own
phase/delivery receipt. Acceptance and retry bookkeeping do not relabel a terminal
baseline SENT. A matching new resend ACK can settle its tuple; own failure cannot
downgrade preexisting DELIVERED. A fresh logical claim fences its predecessor.

The actual pinned `PersistenceStore+Messages.swift` and current-main
`MessageDaos.kt` both preserve retry counters in `clearRetryingToSent`; this is
not the counter-reset behavior reported from a consumer fake. No fake-store
semantics or original assertions were adopted or changed here.

Typed outcomes distinguish applied, exact already-applied history,
already-delivered, invalid handle, stale lease, invalidated, missing, ineligible
and conflicting operations. Immutable receipts retain wire bindings, bookkeeping
and pending-completion operation identities, committed message snapshots and the
historical delivered tuple separately from current permission. Historical delivery
does not describe a replacement/current row or restore mutation authority.
Inspection never issues a handle. Real storage exceptions and cancellation escape;
neither is converted into fake success or an invented uncertain receipt.

## Validation and limits

Executed with Python **3.12.4** on native Windows, without Gradle:

| Command | Actual outcome |
| --- | --- |
| `python tools\android-port\controller\validate.py` | Passed: 1,866 reference files, 64 WPs, 178 dependency edges; source/ownership/DAG validation |
| `python tools\android-port\controller\verification_config.py --check` | Passed; feature verification remains unconfigured, not dependency/human acceptance |
| `python tools\android-port\portmap.py` | Passed: 2,035 Kotlin dispositions, including both new files as WP-201 Android-only; headers are not feature acceptance |
| `git diff --cached --check` | Passed: no whitespace errors in the staged handoff |
| `git diff --quiet db14559b39d32322b06477c6ae676112f583db50 -- MC1Services\Sources\MC1Services\Services\MessageService+ACK.swift MC1Services\Sources\MC1Services\Services\MessageService+SendDM.swift MC1Services\Tests\MC1ServicesTests\Services\MessageServiceACKTests.swift` | Passed: these inspected ACK inputs match the source pin |

Seven new test methods assert receipt-value semantics: unsigned predicted/actual
codes, immutable wire/operation histories, exact RTT distinctions, historical
delivery versus replacement/current authority, resend baseline versus own delivery,
pending/operation identities and typed outcome payloads. These are supplemental
contract tests, not original-case credit or a storage implementation oracle.

**Compilation and test execution were not run.** The retained local execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` was not bypassed or recovered.
No readiness result, discovered/executed Kotlin test count, hosted check, generated
Room export, migration, hardware or iOS execution is claimed. Seven methods authored
does not mean seven tests discovered/passed. The receiving owners must compile and
exercise the actual database/service/queue interleavings against this exact handoff.
There is no durable-ingress promise for an ACK held only in RAM during storage
failure followed by process death.

## Additive no-dispatch queue completion follow-up

Follow-up parent: `93125597154c8ae28041f6781c5fb851d2b6188b`. During
consumer integration, the normal already-DELIVERED queued SEND fast path and the
legal zero-attempt budget were found to return without beginning an ACK claim.
The existing claim-bound completion must not be bypassed with fake RESEND
authority, generic deletion or broadened SEND eligibility.

Storage/service owners agreed a separate optional
`DirectPendingCompletionAuthority`: capture an exact persisted pending/message
incarnation/revision, conditionally complete only that capture, and inspect its
immutable completion history. `AlreadyDeliveredSend` verifies actual outgoing DM,
DELIVERED and a non-resend DM pending row. `ZeroAttempts` carries the exact
configured maximum as metadata; the producer admits only zero, never interprets
it as delivery or acceptance. Both require matching actual direct rows.

Capture follows the initial pending bump and **all** legitimate no-dispatch
finalization/routing/message bookkeeping. Pinned Swift and current-base Kotlin
skip status writes when tracking is absent, but prior tracking may run
`clearRetryingToSent`; capture must follow either branch. No subsequent generic
message/pending mutation is exempt from invalidation. Completion changes only the
exact pending row, creates no wire/ACK grant and never regrants invalid authority.
Exact request replay exposes historical completion without deleting replacement
rows. Lease/issuer, true message and pending incarnations/revisions, immutable
snapshots and durable operation identities remain storage obligations, not DTO
equality or in-memory leases.

Trusted `reserve.py admit` returned `wp-owned/create` for the new domain
`DirectPendingCompletionAuthority.kt`, `reviewed-support-test/
add-or-update-related-test` for its new corresponding test, and `wp-owned/modify`
for this evidence. The historical claim mismatch above remains unchanged; no
reservation was mutated. No existing signature, assertion or channel behavior
was changed. Four additional methods test only immutable payloads, exact
zero-budget metadata, capture versus completion and historical replacement
distinctions. They do not prove database enforcement or execute a deletion.

The same source-only manifest, verification-configuration, provenance and staged
diff checks were rerun successfully for this follow-up; provenance now accounts
for 2,037 Kotlin files. Kotlin compilation and all eleven newly authored methods
across both handoffs remain **unexecuted** under the same retained execution hold.
This follow-up contains no schema, migration, DAO, repository or service changes.

## Storage dispatch mode clarification

Clarification parent: `80e8ebc33ac2730c2e45845333425284c7e4f77a`.
Pinned `MessageService+SendDM.swift:207` invokes `onMessageCreated` before the
retry loop at line 214, without a terminal-row dispatch gate. A callback can have
already committed DELIVERED before a real first wire attempt; the existing
consumer positives still require actual wire failures to propagate and preserve
the delivered baseline.

Storage and service owners confirmed that `DirectAckMode.RESEND` is explicit
**storage dispatch admission**, not the product `isResend` flag. It can admit a
real new logical wire dispatch against that terminal baseline, binding its actual
incarnation/revision and new wire identity while fencing its predecessor. It does
not imply a sendCount increment or a Resent event. It never revives a grant
invalidated after begin, regresses DELIVERED, or creates authority for no-wire
cleanup. The independent pending-completion role remains required for that case.
This only clarifies existing mode semantics; no signature, test assertion,
consumer, schema or runtime behavior is changed here.

Trusted `wp-owned/modify` admissions were rechecked for the contract and this
evidence. Source-only manifest, verification-configuration, provenance and staged
diff checks passed again. Compilation and all Kotlin tests remain unexecuted in
this worktree under the retained hold; external diagnostic results are not
credited as this clarification's acceptance.

The storage owner reported exact hosted run `41958103719`, candidate `fade600f`,
failing `:core:contracts:compileTestKotlin` at `DirectAckAuthorityTest.kt:133`
because `assertNotEquals` could not infer its common type for two singleton result
variants. This successor explicitly supplies `DirectAckRevocationResult` as the
type argument, preserving both operands and the inequality assertion. Trusted
`reviewed-support-test/add-or-update-related-test` admission was rechecked.
The failing run did not execute Room tests and supplied no schema artifact.
This surgical source repair is not a successful compiler rerun; hosted
verification of the integrated successor remains required.

## P1 issued intent and payload-erasure successor

Parent: `35780232cdd4190889297e0497c8b94d61ac6db5`. Coordinator-assigned
consumer review identified a real gap: a generic fetch could cache payload A,
then a competing upsert or identical-DTO delete/recreate could install B before
key-only begin. Begin granting current B would not authenticate dispatching A.
Post-begin DTO comparison cannot prove the earlier incarnation.

Storage/service owners agreed two-phase issuance. `captureDirectAckIntent`
atomically captures the true message and optional pending incarnations/revisions
and their immutable snapshots under the lease. Complete legitimate timestamp,
preflight and preparation writes first; this capture is the **first authoritative
wire selection**, not validation of an older DTO. Derive wire text/timestamp and
contact ID/radio from its issued snapshot. Resolve contact using those captured
identities and retain existing consumer identity checks; no new contact-revision
enforcement is claimed.

The only begin signature is now
`beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)`. It consumes
and revalidates the opaque issued intent, retaining the same logical request ID.
Text-changing writes, same-value updates, identical-DTO ABA and pending replacement
between capture and begin must reject, not bind new current rows. Replay never
recaptures changed state. `inspectDirectAckIntent` returns historical evidence
without handles. The unsafe key-only begin overload is removed, with no default
implementation or equality fallback; receiving concrete implementations and
consumers must explicitly update.

The coupled privacy finding concerned full snapshots surviving supported
deletion/redaction. Historical `DirectAckReceipt.message`,
`DirectAckDelivery.message`, intent message/pending and no-dispatch message/pending
are nullable: null means **erased payload**, never a fabricated success DTO.
Intent retains optional `pendingSendId` so inline absence differs from erased
pending data. No-dispatch receipts retain required `pendingSendId` independently
of payload, along with completion request identity.

Message deletion/key replacement or payload-bearing message-field updates/
redaction atomically purge message and pending DTO snapshots across persisted
authority histories; pending deletion/replacement/update purges intent and
no-dispatch pending payload, including on own completion. Identity/code/RTT/
operation tombstones remain, with non-CURRENT permission and no handle reissue.
Own pending completion retains its commit ID and message snapshot unless the
message was erased, but never retains the deleted pending DTO snapshot.
The ACK claim never stores pending payload; a previously begun attempt remains
independent, so typed own pending completion can preserve its validated late-ACK
grant. Own status/ACK/retry/count changes do not redact message payload.
Fresh CURRENT captures require actual nonnull message and any expected pending
payload. Historical erased commit does not authorize dispatch, describe a
replacement row, or justify fallback readback/success.

Trusted `wp-owned/modify` admissions cover both domain contract surfaces and this
evidence; both related test surfaces retain actual
`reviewed-support-test/add-or-update-related-test` admission. Existing assertions
are preserved (the new nullable no-dispatch message gets an explicit nonnull
assertion before its original field assertions). Three additional value tests
cover issued inputs versus later replacement and erased identity/tuple retention.
These are not actual-Room race or privacy tests: the storage owner must exercise
text-changing and identical-DTO/pending ABA at before-begin barriers, supported
deletion/redaction and raw persisted snapshot removal, while service tests prove
wire inputs derive only from the issued snapshot and original positives survive.

The source-only manifest, verification-configuration and staged diff checks
passed for this successor. The full provenance command hit a host `MemoryError`
while reading an unchanged scope amendment; its JSON consumer consequently failed.
This is not a passing provenance result or a Kotlin test failure. A standalone
retry without the concurrent JSON-consumer process remained running without output
after its 120-second initial wait and was explicitly stopped; no passing result
is credited. Provenance validation remains unverified for this successor.
Kotlin compilation and all fourteen then-authored
methods across the handoffs remain unexecuted in this worktree under the retained
execution hold. No integrated hosted result, migration, erasure enforcement or
consumer parity acceptance is claimed here.

## Lease-retirement erasure follow-up

The storage producer reports commit
`0182ef8354e6e42adf6246d5eee14311a942285a` atop its producer
`4f20d23482a8b0bcd5b9124b778b4d8db270c7c5` and integrated contract
`46dd00d6a`. It adds a canonical SQLite active-to-inactive lease trigger that
erases all snapshots for explicit revoke, successor activation and process close,
while retaining stable radio/lease and claim identities, phase, ACK code, RTT and
operation journals. The owner reports corresponding actual Room tests cover all
three retirement paths and verify the ordinary product rows remain unchanged.

Those commits and test sources are not in this contract worktree and have not
been executed or independently validated here. The exact follow-up source/status
is producer-reported integration context, not this branch's contents or passing
test evidence. This contract makes the corresponding behavior explicit:
retired-lease receipts may have null payload snapshots and `STALE_LEASE` even
when the ordinary message row still exists. This contract follow-up adds two
more value-only assertions; sixteen methods are now authored but none has been
executed here. No public API or generated Room
schema bytes are changed by that reported storage follow-up; export remains a
separate pending validation gate.
