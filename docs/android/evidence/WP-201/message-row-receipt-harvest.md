# WP-201 save-issued row receipt and conditional incoming RX harvest

Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_0jpIzLm0tBYXoigYAOa1QKuO`.
Parent dependency: approved reaction contract
`67ec8bffe3d1eb66e1c896b9fbdebf7fc790b136`,
tree `9c6a3684a0bf5290c73d419ddb17c1503d563c23`.
This is a SEPARATE three-path additive delta, not cache-selection4c405 or held
consumer ancestry. Actual currentmain checked0d10 lacks67ec declarations.
Swift specification stays `db14559b39d32322b06477c6ae676112f583db50`.

## Custody and scope

The coordinator assigned WP-201 the domain seam because WP-202 can modify its
repository but has no trusted create capability for a new domain contract.
f805 owns actual saved-row/harvest storage implementation; 12e owns Sync inputs,
custody and events; 13a assessed independent RX provenance. No owner's existing
candidate, schema or runtime graph is modified here.

Literal channel-only signatures were coordinated with f805/12e:

`MessageHarvestAuthority.saveIncomingChannelMessageWithRowReceipt(
request: ChannelMessageRowSaveRequest): RowBoundChannelIngress`

`MessageHarvestAuthority.harvestIncomingPaths(
request: IncomingPathHarvestRequest): IncomingPathHarvestResult`

No generic save, DM/sent echo policy or defaults are added. Existing67ec variants
and generic interfaces remain unchanged. f805 explicitly confirmed MessageDTO
unset-field representation and Existing's storage-only receipt semantics.

## Separate row and receive provenance

SavedMessageRow retains actual committed DTO, private storage-issued
SavedMessageRowReceipt and optional exact reaction capture. Receipt binds original
save, incarnation and reaction-identity revision IN THE SAME SAVE TX, including
InsertedUnproven and actual Existing upsert successor. Public receipt markers are
not accepted by storage. It is not a receive, duplicate, original-insert or
reaction proof; no save-then-read, current-ID or DTO-equality receipt recovery.

RowBoundChannelIngress preserves the exact original67ec outcome. Inserted
requires the exact captured message/capture; InsertedUnproven and Existing cannot
gain reaction capture; Rejected carries no row. Existing's committed same-ID
upsert can issue its own new storage-only receipt, not certify a prior insertion/
occurrence or safe retry. Commit-before-throw may lose the return; no journal
or recovered receipt is promised.

Pre-save content correlation/current-key decoding is not proof: the prior
consumer copied RX path/route/region before save. The new save request explicitly
requires those RX-derived fields unset and rejects enriched input before DML,
without silently stripping it. Independently received wire pathLength and SNR
must remain exact. Caller retains/defer RX candidates for conditional enrichment;
generic/OFF behavior is not blanket changed by this optional inactive role.
Body/time correction remains the original decoding policy, not a new codec.

Each OriginalRxCandidate carries its own exact RxLogEntryDTO and either Unproven
reason or a future private authenticated implementation of the Proven marker.
No issuer exists: neither public markers, key/hash/content equality, a row
receipt, current session/token nor raw RX first-match association prove origin.
Future issuance needs the exact raw receive occurrence/session/radio/index and
authoritative original channel-selection/occupant association. The verified
firmware81dbae lacks that join and an all-buffer ingress barrier; 13a's
evidence-only027d60ae2b3be1f7278a3965d20ea5426ba04b6e independently agrees.

## Atomic conditional harvest and outcome

Request retains the exact SavedMessageRow plus immutable candidates in stable
receivedAt order. Earlier legitimate RX entries are not excluded because they
precede save; equal timestamps retain input order. In one write TX:

1. Authenticate own original row receipt/incarnation/revision and actual row.
2. Authenticate EACH original candidate and its same-origin association; verify
   GROUP_TEXT/SUCCESS decoded sender/body/slot/original reactionTimestamp
   (senderTimestamp when corrected, not corrected stored timestamp) and canonical
   dedup key. No current-key guess or stale same-ID read.
3. If any candidate/row is unproved/stale/unresolved/foreign-issued, return
   Blocked with ZERO DML and exact whole-batch custody, not partial filter/miss.
4. Only with genuine proof, adopt earliest path when canonical path is NULL.
   Atomically adopt that SAME candidate's pathNodes, RX pathLength, routeType,
   regionScope and regionScopeMatches while preserving wire message SNR.
   No candidate retains wire pathLength/SNR and null/unset route/region.
   Empty path is already known; adoption itself adds no repeat/count. Further
   canonical-equal paths do nothing. Dedup additional paths globally by radio/
   RX ID and per-message path, then atomically insert each actual repeat and
   checked count. Return actual final DTO/successor receipt/capture plus adoption
   ordinal and each inserted repeat/count-after fact, with no later ID refetch.

Completed includes only actual post-commit facts; events occur afterward.
Empty candidates may complete after actual row validation in the same TX,
claiming no RX origin/enrichment. Current all-Unproven storage can implement only
such validated empty no-op or explicit blocked nonempty work; fake Proven markers
are rejected, not a positive test seam.

Own benign path/repeat/count writes must preserve original authentic reaction
lineage or return explicit SAME-TX successor capture on actual returned message.
Do not silently skip future legitimate indexing/replay or remint by public ID.
No positive capture is manufactured for Unproven/Existing rows. Earliest route/
region metadata requires that same independently authenticated candidate, not a
separate current-key content lookup. The old nodes/length-only DAO cannot fulfill
the future positive adoption semantics without admitted implementation changes.

Exceptions and cancellation propagate, possibly after commit. Caller retains
exact request, receipts, candidate occurrences and original cause as UNKNOWN/
non-redrivable custody. A known committed save followed by failed harvest is
known-committed incomplete work, NOT save-UNKNOWN or inferred rollback. No retry,
reselection, journal recovery, dropped candidate or speculative success.

## Admission and evidence limits

Trusted manifest `e81605857f5aa7b893dcd63224fb27a45d7b0f3a`,
policy `e56438d64d2309c964353066f26e1427f17f55d8`,
engine `1001f43112d9bff9c9985929a203b5166c41559e` were checked against actualmain0d10
and match the approved dependency's admission policy. Domain/evidence descendants
admit `wp-owned/create`; directly related contracts test admits
`reviewed-support-test/add-or-update-related-test`. No policy/authorization/
reservation edit or executed validator receipt is claimed.

Six authored shape controls cover exact original wire/save fields, rejecting
pre-enrichment, committed Unproven/Existing storage-only shape, each original
earlier/equal-time RX snapshot, blocked exact custody and issue bounds.
Test receipt marker is value-shape only, never submitted to storage; no fake
Proven issuer or positive RX/Room evidence. No compiler/test run occurred.
All existing67ec tests/assertions and operation058 source remain untouched.

Publication checks cover exact sole dependency parent, three additive paths,
ASCII/LF and no changes to originals. Local execution hold
`local-check:f516a7e271f444dd9cb2efcc8b995a16` remains; no local Gradle/controller/
readiness recovery or new session/worktree. No schema allocation, producer/
factory activation, PR, merge, deployed firmware or hardware verification.
