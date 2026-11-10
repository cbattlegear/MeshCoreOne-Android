# WP-313 TS-06 live neighbor resolver correspondence

Bounded #408 follow-up in `cbattlegear/MeshCoreOne-Android`, based on merged
main `f0c4d4b993472fc11d33e19713635395ea1b1515` (#588).
Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_Cl9BYo89MruHymRbupjc6hyl`.
Actual session: `fc2dfe2e-baca-42ab-87a5-353e93ac6d74`;
branch: `wp-313-live-neighbor-resolver`. No previous branch was rewritten.

## Frozen inputs and gap

The pin remains `db14559b39d32322b06477c6ae676112f583db50`.
Local Swift inputs match manifest blobs:

- `MC1Tests/Utilities/RepeaterResolverTests.swift`:
  `29fc72ea9bd864c3b5b2bb2c767fb8f942b40bca`.
- `MC1/Utilities/RepeaterResolver.swift`:
  `8c2f3217b69b745118174eef148c5e835e528431`.

The original ten neighbor-name families already have coverage in
`feature/tools/trace/RepeaterResolverTest`, but that tests a different
feature-local implementation. The actual remote-node status consumer calls
`feature/remotenodes/resolver/NeighborNameResolver` through `NeighborRows`.
Existing remote-node map/status tests cover some general resolution states,
not the complete ten original families and five argument rows.

## Supplemental correspondence

Every method below is in `NeighborNameResolverParityTest` and carries the exact
original case ID in `@OriginalCase`. Original tools mappings/tests remain intact;
this is additional consumer evidence, not a transfer of WP-314 ownership.

| Original family (RepeaterResolverTests) | Live test |
| --- | --- |
| uses discovered node when contact is absent | `discoveredNameReachesLiveRowsWithoutContact` |
| unique short discovered prefix is exact | `uniqueShortDiscoveredPrefixIsExactInLiveRows` |
| full prefix contact match is exact | `fullContactPrefixIsExactInLiveRows` |
| prefers contacts over discovered nodes | `savedNameWinsOverNewerAdvertForSameKey` |
| cross-source short prefix ambiguity is fallback | `differentKeysAcrossSourcesAreFallbackWithoutReplacingSavedName` |
| short discovered prefixes disambiguated by recency | `newerDiscoveredNameWinsRegardlessOfInputOrder` |
| ambiguous short discovered prefix is fallback | `collidingDiscoveredKeysStayFallbackInLiveRows` |
| key display floors at 2, caps at 3 | `originalFiveDeviceHashSizesReachLiveKeyText` |
| fallback name formats two or three bytes | `originalTwoAndThreeByteFallbackNamesReachLiveRows` |
| fallback name clamps max and short prefixes | `originalOversizedShortAndEmptyFallbackInputsReachLiveRows` |

Original keys, names, advert timestamps (10/100/200), heard times (1000/2000)
and prefix widths are preserved. Random UUID/current-time fixture fields become
deterministic identities/times, with no change to the original ordering cases.
The discovered-name oracle is literal `Ridge Repeater`; saved contact wins over
the newer advert as `Saved Repeater`. Distinct keys across sources are FALLBACK;
two records for the same key are EXACT. The recency winner is literal
`Newer Repeater`, tested with both input orders to reject first/last-item shortcuts.

The five source arguments remain `(null,2), (1,2), (2,2), (3,3), (99,3)`.
Hex expectations are independently literal `DEAD`, `DEADBE`, `AB` and empty
string, never computed using a candidate formatter. The seven resolution
families exercise `resolve`, `resolveName`, `resolveLocated`, and both actual
current/disappeared `NeighborRows` consumers. Width/fallback families exercise
live current key text and disappeared hex titles while preserving the source
distinction that unresolved current names use the localized Unknown resource.
This does not repeat TS-39 map assertions or change the mounted #588 repair.

## Execution and ownership boundaries

Trusted `reserve.py admit` accepts the new feature test/evidence paths as
`wp-owned`. The old merged #588 intent was reconciled using its exact
head/merge receipt, without releasing/recreating reservations. The same app
session's advisory evolution rejects its new branch because the historical
receipt binds the old branch; that bookkeeping limitation is reported rather
than impersonating another identity or editing ledger rows. Assignment permits
isolated capability-admitted edits; no actual shared execution lock is changed.

No local Gradle execution is requested. The documented host readiness boundary
from #588 remains: absent same-host Android state and unpinned Python 3.12.3.
Verification belongs to supported exact-candidate hosted jobs and logs, not a
second retained CI-result bundle. No physical-device/radio acceptance is claimed.
The #519 map and #586 contacts holds are unchanged.
