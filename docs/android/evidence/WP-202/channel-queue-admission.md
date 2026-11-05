# Cross-generation channel admission source

## Scope and immutable inputs

This is a separate **source-only, non-standalone successor** of
`0619a9d59663e0625eed5e21f1021252d9501db1`, not an amendment to that freeze or to strict-only
`940a899216c1a5d33fc6b98d39f4b880b0f402ff`. The coordinator authorized producer-local stable
pending-lifecycle definitions and atomic pre-bump admission, preserving Direct ACK's existing
per-update mutation fencing and f805's sole ownership of shared registration/migration/store wiring.

The additive contract is `ChannelQueueAdmissionAuthority.kt`. The initial
`716066432f2b4d891d3e41af11651083fa69323a` / `06d50da5eae1b5bf3ee2157577fb1dca5c5c58fc`
freeze was insufficient for truthful originally unbound acceptance. This source requires its
nullable-original-pending successor `331cf5c1440cc17d3c52525c17a2ef7b4186b271`, exact API
`f9273686487a5c9d44343a743583f5627b153b15` (initial draft `4a6803aa90c61d7fab5862debad007526669a1e2`
plus explicit no-handle checks before replay). Existing failure
`5897785d7a50a5d9b3648357e60a5cbb87348d52`, completion
`2cc76fae312c0c3bc065a1ea513daec3b91034c9` and neutral event
`e94ffbe17697e5549fc811a28dccb876b79cf7e3` contracts are not changed here.

The relevant consumer input was exact queue blob `2c5f57198a99a8ee49a6337095e1bc4106ed8398`
in `52ce453e14f7fe70bb2a98d5b1645e0cf0b67f12`; service public-invocation successor
`1ad589b7a43b9553637fc64cdd280eb4e907f25f` does not itself close this boundary.
Those initial consumers bump pending attempts before preparing the channel claim. The service owner
subsequently froze atomic-admission consumer `641b9dd60aabff1e3a4210ab79a98b0d0d21a183`,
which also needs its unbound-public reporting follow-up; this source does not certify either
consumer. The actual existing
Direct ACK pending UPDATE trigger replaces its mutation incarnation, so a completion check only
after that bump can miss already-accepted persisted work.

## Identity and admission

`ChannelPendingLifecycleEntity` records a distinct live pending birth. Dedicated
`channel_pending_lifecycle_*` INSERT/DELETE/UPDATE triggers create a fresh 64-character lower-hex
birth on insertion, erase it on deletion, and preserve it on an UPDATE only while radio, pending ID
and message relationship are unchanged. Retargeting those identities creates a fresh birth.
An actual DELETE + INSERT or INSERT OR REPLACE is new work; an ordinary same-value Room UPSERT
is still the old lifecycle. The direct-ACK trigger map and its unconditional per-UPDATE pending
mutation identity/revision invalidation are unchanged.

New channel captures persist `pendingLifecycleIncarnation` at issue. The opaque attempt also holds
the original actual message incarnation, stable pending birth, pending relationship, timestamp and
mode. It does not reconstruct these from IDs or later DTOs.

`admitQueuedChannelSend` validates the real active shared lease and outgoing channel row, selects
the actual first pending row in existing repository sequence order, and checks committed same-work
history **before any bump or timestamp write**. A committed match uses actual message incarnation
and pending birth, not status, mutation revision, request ID or payload equality. It returns
`PreviouslyCompleted` without a grant. This survives ordinary lease replacement and generic
message/pending mutation, but is not transferred to a genuinely new pending birth or message
incarnation. Historical receipts across a different lease have no live DTOs.

Relevant known completion history lacking the captured birth returns
`Uncertain(UNPROVEN_COMPLETION_LINEAGE)`. No historical capture is backfilled from a live row.
This is not database-wide quarantine: unrelated message incarnations are admitted, and proven own
pending deletion retires that historical work. A completed cleanup does not forbid a later new
pending row merely because its IDs match.

If no committed or retained acceptance fence matches, one Room transaction performs the existing
checked `(attemptCount ?: 0) + 1` on only the selected pending row, verifies the regenerated mutation
identity and unchanged lifecycle, optionally applies a fresh timestamp only to a first-attempt
RESEND, and captures the resulting exact mutation identities/revisions. SEND and later RESEND
preserve the actual timestamp. No maximum-attempt policy is introduced.

The durable `QUEUE_ADMISSION` origin distinguishes this operation from old creation/preparation.
Replay binds the original lease, request, key and raw fresh timestamp even when unused. Only the
original still-current version-zero grant returns `AlreadyReady`, with no new bump or timestamp.
Remap successors, invalidated grants and replacements cannot replay into renewed authority.
The existing failure-prepare method also checks acceptance before timestamp/capture or exact
pending-bound replay. A public null-pending preparation, including replay, atomically refuses any
actual pending already present for that message, with no manufactured pending binding. This is
necessary even before prior acceptance exists. Queued work uses atomic admission instead.
Genuinely no-pending public SEND/RESEND behavior remains available; this is not blanket public
message-ID deduplication.

## Ordinary process-owner handoff, not process-death proof

The same `ChannelSendRepository` implements all three channel roles. Its non-suspending
`noteAcceptedChannelAttempt` authenticates the opaque original metadata and records a real
caller-reported successful return immediately, without database/network IO, launch or lease renewal.
The registry is keyed by original capture privately bound to actual message incarnation and pending
birth. It is not a message-ID success cache.

A genuine issued handle may have a revoked lease or invalidated mutation revision when the report
arrives: acceptance is still retained for **suppression only**. A failed/rejected completion does not
erase it. Same-value DML, service claim retirement and lease revocation cannot clear it. A new
admission matching this retained work returns `Uncertain(RETAINED_ACCEPTANCE)` before DML.
Changed tuple replay is a typed conflict, and another capture cannot overwrite an existing
same-work fence. A first authentic report with incompatible captured timestamp or known pending
mode retains its **reported**, not validated, tuple before returning Rejected(CONFLICT); admission
then returns `Uncertain(REJECTED_ACCEPTANCE_NOTE)`. Exact and changed rejected retries remain
rejected and cannot clear that first fence. An overlapping authentic capture's rejected report
also remains retained rather than disappearing behind another capture. Registration and fence
observation use the same repository monitor. These checks precede `AlreadyReady`, not just bump DML.

A genuine originally unbound opaque attempt registers with its original null pending ID/birth and
actual message incarnation; no later pending lookup supplies invented capture evidence.
Insertion after the public preparation check but before its transport return therefore remains
suppressed across generation replacement. Both relevant unbound committed history and unbound
retained reports return `Uncertain(UNBOUND_ACCEPTANCE_LINEAGE)`, with original null pending ID,
not `PreviouslyCompleted`. A later pending UUID/birth cannot prove separation when no original
birth was captured; a genuinely recreated message incarnation or unrelated message is distinct.
This narrow relevant-history ambiguity is explicit, not a new durable legacy quarantine.

There is no TTL or capacity eviction. Only the exact durable completion's after-commit action
prunes its matching note; rollback or a thrown storage return does not falsely clear suppression.
Process-store disposal explicitly closes and clears the registry after runtime shutdown. The
consumer owner identified the real application-owned Room store, per-session delegation and
old-session join-before-new-connect ordering; the consumer must use that same owner and report
the successful return before cancellation/ownership checks.

This does **not** create a durable pre-wire journal or prove unobserved callback outcomes,
definitely-unsent state, exactly-once RF send, recipient delivery or process-death recovery.
Destroying/reopening the process store loses uncommitted RAM evidence. A missed acceptance report
cannot be manufactured by this producer. External reaction indexing is still not certified atomic
by the database seam.

## Sole-owner integration hooks

f805 must import the complete storage/repository source and:

- Register `ChannelPendingLifecycleEntity` in the same combined v4 database, alongside the completion
  entity from `0619`. The attempt entity adds nullable `pendingLifecycleIncarnation`. The resulting
  combined source inventory is 32 entities, **not an exported schema assertion**.
- Keep the sole 3-to-4 migration's existing `ChannelSendStorage.createTables` hook; it creates the
  lifecycle table and installs triggers for current rows, never historical acceptance bindings.
- In the actual fresh database callback, call `ChannelSendStorage.installPendingLifecycles(db)`
  after existing Direct ACK/prehistory initialization. On open, call
  `ChannelSendStorage.verifyPendingLifecycles(db)` after the existing checks. On-open verification
  must not repair or invent births. Every channel transaction verifies both fencing systems.
- Delegate `ChannelQueueAdmissionAuthority` through the **same** `channelSends` instance as failure
  and completion. Call `channelSends.closeQueueAdmission()` at process-store disposal, never at
  session close or lease revocation.

The source hook defines `channel_pending_lifecycles(radioId TEXT NOT NULL, pendingId TEXT NOT NULL,
messageId TEXT NOT NULL, incarnation TEXT NOT NULL, PRIMARY KEY(radioId,pendingId))`.
No shared database, migration, direct-ACK producer, process-store or service-owner file was edited
by this lane. No v4 schema was fabricated or generated; the eventual combined input needs its own
genuine generation and independent review. Genuine v3 and all historical refs remain immutable.

## Authored controls and actual verification limits

`ChannelQueueAdmissionRepositoryTest` adds **31 real-Room test methods** using the genuine callback
fixture after sole-owner hook integration. They cover committed acceptance then lease retirement,
pre-bump ordering and generic-bump laundering, same-value UPSERT versus same-ID DELETE/INSERT,
message recreation, siblings, pending PK/radio/message retargeting, raw REPLACE, missing/changed
triggers and missing births, actual first-row mode/null-count behavior, exact admission replay,
later resend timestamp preservation, overflow, SQLite abort rollback of identities/count/timestamp,
retained acceptance after invalidation/revocation, conflicting/foreign reports, durable precedence,
unproven historical lineage without backfill, legitimate identity replacement, replay operation and
version separation, process-store disposal, public null-pending refusal followed by queue eligibility,
unbound acceptance before later insertion, committed unbound history with no fabricated pending ID,
sticky first rejected reports, and same-request replay after retained/rejected acceptance.

`ChannelSuccessReplacementRoomTest` retains its existing 12 controls and adds **three** actual
session/Room ordinary-generation controls: accepted completion with failed indexing, genuine
accepted but uncommitted completion outage with process-owner retention, and a legitimate same-ID
new pending lifecycle after retirement. The fixture injects the agreed
`channelQueueAdmissionAuthority = persistence as? ChannelQueueAdmissionAuthority ?: store`.
It requires the service owner's matching atomic-admission and unbound-reporting successor, not
unmodified `1ad589`.

All **34 new methods are authored, uncompiled and unexecuted**. These are source inventory counts,
not runner discovery or passing evidence. No local Kotlin/Gradle/Android/WSL/device test, workflow
dispatch, live check publication, merge or deployment occurred. Independent exact-source review,
sole-owner shared integration, genuine schema generation and a separately authorized exact-candidate
hosted run remain outstanding. This source handoff is neither parity acceptance, WP completion nor
a dependency unlock.
