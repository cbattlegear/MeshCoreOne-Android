# Incarnation-bound channel failure producer

This is source-only preparation for the channel fallback race, not executed Room,
service, migration, schema-generation or product acceptance evidence.

## Exact prerequisites and ownership

- Storage predecessor: `4f7e747d85fd1a6d4bac29d9977511602b53c189`.
- Additive canonical role: `39250d808b9997e0a87db421251f548fa9bc93f5`,
  `ChannelFailureAuthority.kt` blob `5897785d7a50a5d9b3648357e60a5cbb87348d52`.
- Service race controls were frozen independently in the `a624` -> `d510` ->
  `9e11a8ba4f4365ede250b720680071265bf07e45` chain. This producer does not edit
  their integration file or claim their execution.
- The coordinator allocated schema v4. The storage owner is the sole combined
  registration/migration producer for the DB-wide UNKNOWN state and these channel
  tables. There is no independent competing migration or handwritten v4 export.
  Genuine v3 blob `e7bf0f62999896bca4d36a2a636096d962b12638` stays immutable.

The isolated implementation consists of `ChannelSendStorage.kt`,
`ChannelSendRepository.kt`, `ChannelCreationDigest.kt`, and
`ChannelSendRepositoryTest.kt`. Shared database registration, restore invalidation,
opaque lease bridging and process-store delegation are serialized through the
storage owner, not independently edited by this lane.
The isolated freeze is a reviewable source delta on the storage predecessor,
not a standalone executable candidate: it requires the exact canonical role and
the storage owner's combined v4 wiring. No missing capability is defaulted or
stubbed to make that intermediate tree appear buildable.

## Intended guarantees and boundaries

Creation inserts a new outgoing pending channel row and captures its actual
persisted message incarnation/revision in the same transaction. It never upserts
an existing key. A length-prefixed, complete DTO digest binds creation replay,
including nullable fields and binary payloads; it is not a physical-history
certificate or permission to reconstruct the message.

Preparation reads the actual channel row and any exact channel pending row,
checks their relationship, applies the optional timestamp, and captures their
persisted identities atomically. Preparation is not proof of an earlier envelope,
preflight read, or generic write. Consumers must not recapture after invalidation.

The channel role delegates activation/revocation to the same process-owned durable
session issuer as direct ACK authority. It does not activate a second competing
lease. A new process cannot interpret the old opaque handle.

Failure checks the active lease, message incarnation/revision, and any pending
incarnation/revision inside the mutation transaction. The SQL also checks current
channel kind, but that predicate is not the original-incarnation proof. An own
FAILED write advances only its expected message revision and retains its grant
for later exact cleanup. A conditional PENDING remap journals a versioned successor
for the same row incarnations; the old handle cannot start a new operation.
Generic writes, including same-value writes, invalidate old revisions.

Terminal cleanup deletes only the captured pending ID with its exact incarnation
and revision, in the same transaction as FAILED and the operation tombstone.
Sibling pending rows and other radio partitions are not deletion targets. Cleanup
retires the grant; both receipt DTOs are null. A deletion error must roll back the
status, identity advancement and journal together.

Receipts store nonpayload capture/operation identity, not message/pending history.
Both DTOs are returned only after current exact-binding proof in that transaction.
Exact replay of an already committed failure is historical information even after
replacement or lease retirement; it performs no second write or deletion.
Restore invalidates all channel captures in its transaction. No generic DM intent,
legacy envelope, matching DTO or empty journal is imported as a channel grant.

A receipt is not authority for a later message-ID-only Failed event or mark-seen
side effect. Service/UI consumers still require neutral persistence invalidation
or independently identity-fenced observation. A postcommit replacement can occur
before publication. This producer does not change generic MessageDao SQL, generic
SENT/timestamp/success deletion paths, or claim those other boundaries are repaired.

## Authored verification and remaining gates

Sixteen real Room controls are authored, all **UNEXECUTED** under the local
execution hold:

1. Insert-only creation and immutable complete-argument replay/conflicts.
2. Direct/incoming/terminal/wrong-radio creation rejection without row creation.
3. Paused failure followed by same-key replacement DM and pending row.
4. Equal-looking channel delete/reinsert ABA rejection.
5. Generic same-value message and pending writes invalidate the grant.
6. Genuine channel failure with exact cleanup, sibling and radio isolation.
7. Own failure -> conditional PENDING successor -> exact terminal cleanup.
8. Own failure -> later exact cleanup without key recapture.
9. Missing/wrong-kind pending rejection before any timestamp mutation.
10. Malformed persisted binding produces typed InvalidData, not permission.
11. Historical postcommit replay cannot delete a replacement.
12. Real SQLite cleanup failure rolls back the status/revision/journal.
13. Actual-row preparation/timestamp replay preserves pending attempt count.
14. Invalid opaque handle, delivered preservation and revoked lease rejection.
15. Restore invalidation retains nonpayload operation tombstones.
16. Actual file/process reopen retains the commit but rejects the old handle.

No Kotlin compilation, test runner, Gradle task, local readiness installation,
device execution or hosted dispatch was performed in this lane. Source/blob/tree
identity checks are not executable evidence. The original storage-only
`42084764103` failure is unchanged and supplies no result for these sources.

Before execution, the exact shared v4 producer and channel consumer must be
independently reviewed, the supported hosted KSP must generate the real v4 schema,
and an exact-candidate diagnostic must be separately authorized. No WP completion,
dependency unlock, integration safety or product confirmation availability is
established by this source-only freeze.
