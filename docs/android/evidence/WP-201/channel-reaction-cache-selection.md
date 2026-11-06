# WP-201 occurrence-authenticated cached reaction selection

Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_72V3cUWt1hTqldTqgQDZbvOl`.
Parent dependency: `67ec8bffe3d1eb66e1c896b9fbdebf7fc790b136`,
tree `9c6a3684a0bf5290c73d419ddb17c1503d563c23`, approved WP-201 original-occurrence
contract on independent `67a22f7f9e75db2b8188ed890e04cc3b25d62a02` / main35ab.
Current main checked: `0d10b838a181cd3bd34af1a0200435149dbc6bb0`; it lacks those
reaction dependency declarations, so this bounded delta uses the approved
dependency, not held consumer ancestry or an implicit current-main integration.
Swift specification stays `db14559b39d32322b06477c6ae676112f583db50`.

## Gap and agreed seam

WP-309 reported that ranking cached targets before authenticating the reaction's
original occupant can bind a newer indexed B target and mask legitimate retained
A. Rejecting B at final apply is correct, and does NOT permit falling back to A
after binding. Fix the original selection, not the bound-work rule.

The literal contract was coordinated with 5c (cache/service), 12e (Sync) and
f805 (storage). Several proposals crossed during coordination; the SINGLE
authority-owned selection seam in this file supersedes assessment/filter
alternatives. No held d63 source was inspected/imported; the owner supplied
the minimum existing candidate and ranking semantics. Consumers remain separately
owned and are not changed here.

`ChannelReactionCacheSelectionAuthority.selectChannelReactionCacheTarget(request)`
is a new optional role with NO default implementation. Request carries the
EXACT original occurrence, parsed reaction and immutable bounded cache snapshot.
Each member is Bound(exact captured target, indexedAt, indexOrder) or
Unbound(messageID, original text/timestamp, indexedAt, indexOrder). Bound text/time
derive from captured.message.text/reactionTimestamp. Ordinal means original list
position, not indexOrder or message identity. Input retains ascending UNIQUE
original indexOrder; no clock, reindex, reorder or LRU refresh occurs.

In one consistent read transaction the future issuer must authenticate original
occurrence/wire/parsed reaction, every original handle/incarnation/revision,
origin and cached match-key membership BEFORE ranking. Only genuinely known
foreign origin may be excluded. Any unbound/stale/unproved/foreign-issued/
unresolved input blocks the original selection explicitly, not a miss.
Among authenticated same-origin matches preserve existing format matching and
greatest indexedAt with STRICT greater-than; first equal in input order wins.
Metadata, public markers and identity-row existence alone confer no authority.

Result retains the SAME request:

- Selected(original ordinal) resolves to the exact original Bound member, not
  a DTO/key refetch or a copied replacement.
- NoMatch means authenticated completed original selection, including actual
  empty/all-known-foreign/nonmatching. Unproved empty input is NOT NoMatch.
- Blocked retains the entire original snapshot plus nonempty typed issues:
  Unbound, Stale, Unproven(reason), Unresolved or Rejected(reason). Nullable
  ordinal identifies a batch/occurrence failure; per-member ordinals retain
  original order and cannot be duplicated/out of bounds.

These are public result SHAPES, not an issuer or public proof factory. Consumers
must validate exact request identity and accept only their actual authority's
return. Missing role and Blocked are explicit bounded non-redrivable custody,
never generic authority casts, native/database fallback or fake success.
Exceptions/cancellation propagate while the caller retains exact request/work;
Unresolved describes actual persisted uncertainty, not caught-error fabrication.

Final apply STILL independently authenticates the same reaction occurrence,
original target/predecessor/origin/revision in its SAME WRITE transaction after
suspension. Pre-ranking is not a lease; stale bound work never becomes unbound.
Historical authentic A/A survives slot retirement to B, even across genuinely
authenticated sessions. B cannot match A; no current-slot or equal-event UUID
test replaces original provenance.

## Current producer and acceptance limits

f805's existing `4160e396ce8c07629036f3900a825e6578f8def3` producer is all-Unproven:
no private receive-origin issuer or positive target selection. It can only
return explicit Blocked MISSING_ORIGIN/INVALID_PROOF as appropriate; no new
schema, marker constructor or fake positive adapter is included here.
The receive-provenance investigation independently traced firmware81dbae
slot-only retained queues and absent all-buffer cutover. This contract does not
repair that missing upstream producer.

Six source shape controls preserve exact occurrence/envelope/wire, member order,
ordinal versus indexOrder, unbound selection rejection, whole-batch unproved
custody (including empty snapshot), and malformed issue-set rejection. They use
ONLY Unproven and Unbound inputs: no fake Proven test issuer or authenticated
positive test authority. Ranking/origin/Room/concurrency/hardware positives are
not executed or claimed; they require genuine future producer evidence.
All existing67ec production/tests/assertions remain byte-for-byte unchanged.

## Trusted path admission and verification

Actual trusted inputs were read at currentmain0d10 and match the approved
dependency's policy pins: manifest `e81605857f5aa7b893dcd63224fb27a45d7b0f3a`,
policy `e56438d64d2309c964353066f26e1427f17f55d8`,
capability engine `1001f43112d9bff9c9985929a203b5166c41559e`.
The new domain source and WP-201 evidence descendants admit `wp-owned/create`.
The directly coupled core/contracts test admits
`reviewed-support-test/add-or-update-related-test`; no cases/assertions removed.
No policy/authorization/ledger edit or executed controller/reservation receipt
is asserted. Operation gate058, prior evidence drafts and all held consumer/App/
history/storage work remain untouched by the three-path publication.

Source checks cover exact parent/tree, additive paths, declared types, ASCII/LF,
and absence of changes to approved67ec originals. They are NOT compilation or
hosted test discovery. No local Gradle/compiler/controller/readiness recovery;
retained execution owner `local-check:f516a7e271f444dd9cb2efcc8b995a16` unchanged.
No PR, merge, runtime factory, positive proof, activation or firmware run.
