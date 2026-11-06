# WP-201 save-issued row receipt and conditional incoming RX harvest

Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_0jpIzLm0tBYXoigYAOa1QKuO`.
This bounded inactive semantic successor has the sole parent
`10db89a73b7fa46e8f5c843a59eb2b6384d6ab6d`,
tree `12c9a99601499b0b7ee3b10dcda6b4a26a669395`. The original c8f, 8fd, d2ee and 10db commits
remain immutable. Their approved reaction dependency remains
`67ec8bffe3d1eb66e1c896b9fbdebf7fc790b136`. Only the existing contract, its
shape controls and this evidence change; no cache-selection4c405, shared derivation
e6f, held consumer ancestry or production storage implementation is imported.
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
unset-field representation. The earlier Existing upsert expectation is corrected
below; wire/DTO consistency and storage-only receipts cannot authorize overwriting
an unrelated existing incarnation.

## Separate row and receive provenance

SavedMessageRow retains actual committed DTO, private storage-issued
SavedMessageRowReceipt and optional exact reaction capture. Receipt binds original
save, incarnation and reaction-identity revision IN THE SAME SAVE TX for new
inserts, including InsertedUnproven. Public receipt markers are
not accepted by storage. It is not a receive, duplicate, original-insert or
reaction proof; no save-then-read, current-ID or DTO-equality receipt recovery.

RowBoundChannelIngress preserves the original67ec result vocabulary. Inserted
requires the exact captured message/capture; InsertedUnproven and Existing cannot
gain reaction capture; Rejected carries no row. Existing remains a compatibility
value shape, not a permitted outcome from the current save role.

The concrete 9ef storage review exposed a distinct safety gap: by-ID lookup then
unconditional upsert could overwrite an unrelated same-radio/same-UUID row before
RX authentication. Canonical wire derivation does not establish overwrite authority.
Under the current API, storage MUST reject every existing key with
Rejected(CONFLICT), before ANY DML, without a receipt. Identical DTO, matching wire,
content key, retry and public Proven marker are not exceptions. Leave the existing
row and durable identity unchanged; never return its untouched predecessor as
Existing or fall back to generic upsert.

The coordinator explicitly approved this restriction under the same assignment.
A future positive Existing outcome requires separately reviewed genuine expected-
incarnation authority. No such input, token, issuer or positive implementation is
introduced here. The old upsert expectation is not retained as an acceptance
requirement for this role. Generic/OFF behavior remains unchanged.
Commit-before-throw may lose a new-insert return; no journal, safe redrive or
recovered receipt is promised.

For this new-row/no-issuer role, initial heardRepeats MUST equal zero. Positive
and negative nonzero values require Rejected(CONFLICT) before DML and no receipt;
otherwise a private baseline would preserve a caller-preseeded unproven count.
The coordinator approved this coupled requirement. The public request constructor
remains unchanged and retains exact input; storage performs explicit rejection,
not silent sanitization. Zero is permitted by this requirement, not proof of
origin or a promise to accept other invalid input.

The separately approved current-repeat restriction checks radio + logical new
message UUID in the SAME original-save transaction. Any current repeat row requires
Rejected(CONFLICT) before ANY DML and no receipt, including orphan rows with a null
parent foreign key. Logical messageID, not parent linkage, controls this lookup.
Never adopt, mutate or delete those rows. The current no-issuer role has no
authorized repeat writes; successful original save establishes an empty current-
repeat baseline.

A second independent gap was reported by 12e/f805: a consumer content-key lookup
outside the save transaction cannot exclude another distinct-UUID/same-key insert
before save. No cross-context single-ingress invariant was established. Under the
coordinator-approved extension, authenticate the canonical derived deduplication
key, then check ANY same-radio/key row in the SAME save transaction as the UUID
check and insert. A collision, including a different UUID, requires
Rejected(CONFLICT) before ANY DML and no receipt. Never equate matching content
with original RX provenance, adopt the observed row, or recapture its lineage.
This is transaction-local conservative refusal, NOT global uniqueness: the
existing index is nonunique, and unrelated later generic writes are not fenced
by this rule. Transaction/serialization errors propagate without guessed success,
implicit retry, or inferred receipt. No new unique index or schema is introduced.

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
   In this inactive no-issuer role, compare actual pathNodes, pathLength, routeType,
   regionScope, regionScopeMatches, heardRepeats and SNR to the private original-save
   snapshot in that SAME transaction, before candidate-level issues or empty completion.
   Any mismatch requires row-level Blocked(Stale), exact original request, ZERO DML
   and no successor. Benign read flags and unrelated local metadata remain permitted;
   this is not whole-DTO equality.
   Also require no current repeat rows for radio/logical messageID in that SAME
   transaction, including nullable-parent orphans, before candidate-level issues or
   empty completion. Any such row requires the same row-level Blocked(Stale), exact
   request, ZERO DML and no successor, without adopting, mutating or deleting it.
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

The 9b97 source review identified that reaction-identity triggers do not watch
path/route/region/count fields. Legacy path/count writes can therefore leave that
revision unchanged, and the previous empty-harvest snapshot could return those
unproven current values. The coordinator approved the explicit snapshot check
above. It is current-state drift detection only: not ABA or same-value write-history
detection, not a fence against later generic writes, and not receive-origin proof.
The separately approved repeat-row check detects currently present rows only;
it does not detect insert/delete ABA or historical repeat activity, and it does not
fence later generic/UI/event operations. No trigger/schema mutation
or generic/OFF change is introduced. Future positive enrichment needs separately
authenticated writes and explicit same-transaction successor semantics; it cannot
silently disable or reset this baseline.

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
admit `wp-owned/modify`; directly related contracts test admits
`reviewed-support-test/add-or-update-related-test`. No policy/authorization/
reservation edit or executed validator receipt is claimed.

Eleven authored shape controls cover exact original wire/save fields, rejecting
pre-enrichment, committed Unproven/unsupported Existing value shape, existing-key
CONFLICT with no receipt, distinct-ID/content-key collision refusal shape without
row adoption, row-level Stale retaining the exact batch without observed-row
substitution, repeat-contamination refusal shapes without row adoption, each
original earlier/equal-time RX snapshot, blocked
exact custody and issue bounds. Existing value assertions are retained solely for
compatibility shape, not producer acceptance. The new conflict shape test is not
a fake authority, an existing-row lookup or proof of zero database writes.
Test receipt marker is value-shape only, never submitted to storage; no fake
Proven issuer or positive RX/Room evidence. No compiler/test run occurred.
All existing67ec tests/assertions and operation058 source remain untouched.

Storage owns actual different-wire and identical-DTO existing-key zero-DML controls
with row/identity preservation, plus same-transaction distinct-ID/content-key
collision controls, radio isolation, and deterministic competing-context insertion
where the actual harness permits. Source shape controls do not establish SQLite
writer/snapshot behavior, global uniqueness, or exclusion of later generic DML.
Storage also owns actual snapshot-drift controls for every listed field, precedence
over candidate issues, zero DML/no successor, and benign-read preservation. Shape
controls cannot establish actual SQL behavior or state-transition coverage.
Initial-count shape coverage retains negative, zero and positive input without
constructor sanitization; actual before-DML rejection and valid zero insertion
remain storage-owned controls, not simulated authority assertions.
Repeat-table controls must use actual current rows, including nullable-parent
orphans, same logical UUID on another radio, save/harvest refusal precedence,
unchanged count/identity and unchanged repeat rows. Actual generic repeat upsert
can leave message count/identity unchanged; App actions read repeats by
radio/messageID. These consumers are not changed or fenced by this contract.
Service consumers must retain Existing/Rejected as
non-redrivable unresolved custody, not notify/index an observed row as the incoming
occurrence. No runtime composition, full contract implementation, positive issuer
or active feature/device acceptance is supplied by this correction.

Publication checks cover exact sole 10db parent, only these three changed paths,
ASCII/LF and no changes to other originals. Local execution hold
`local-check:f516a7e271f444dd9cb2efcc8b995a16` remains; no local Gradle/controller/
readiness recovery or new session/worktree. No schema allocation, producer/
factory activation, PR, merge, deployed firmware or hardware verification.
