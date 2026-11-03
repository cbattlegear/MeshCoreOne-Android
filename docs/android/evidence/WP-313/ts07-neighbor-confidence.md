# TS-07: neighbor fallback confidence in status rows

Source-only follow-up on parent
`d0200d4a00cd1de904d0783c200885eeee31e1fc` (tree
`ec7610502f2a2367147c1e1644b3a381910dce8a`), compared with main
`796a872a3565a66c74f782b738a9ecb3c273fb25`.
The Swift reference is `db14559b39d32322b06477c6ae676112f583db50`.

## Gap and adaptation

The pinned `NeighborRow` and `DisappearedNeighborRow` show a tappable
`FallbackMatchIndicatorView` only for a fallback match. The existing native
`NeighborRows` models already retain `matchKind`, but the actual
`RemoteNodeStatusContent` consumed neither field. No resolver or map-contract
change was needed.

Both status-row branches now expose the existing localized possible-match
title as a visible button only for `FALLBACK`. Its accessibility name includes
the resolved neighbor name and the source's possible-match label; its state
description supplies the localized explanation. The button opens an
explanation dialog using the source title and explanation, with 48dp minimum
targets. This visible Material button/dialog is an Android adaptation of the
source icon/popover, not a claim of device or TalkBack verification. Exact and
unresolved matches do not receive a fallback indicator. Neighbor values,
ordering, resolver selection and map behavior are unchanged.

The pinned disappeared-neighbor row also shows its previous SNR. The existing
native status consumer omits that value even though `NeighborRows.disappeared`
provides `snrText`; this pre-existing, separate presentation gap remains
unresolved by the bounded TS-07 confidence repair.

## Mounted regression prepared, not executed

`RemoteNeighborConfidenceComposeTest` mounts the real status consumer and real
status holders with a synthetic neighbor response and snapshot baseline. Its
two methods simulate 100% and 200% font scales with Compose `LocalDensity`,
each with both current and disappeared fallback, exact and unresolved rows.
They assert the two fallback identities,
localized explanation semantics and dialog, button role and 48dp targets,
font scale and unclipped label layout, absence of false indicators, retained
new badges and not-seen rows, and current SNR/last-seen text. The existing
class configuration specifies SDK 31 and 37.

These are authored assertions, not discovered or passing results. The retained
local Gradle execution hold remains in force. Compilation, both methods on
SDK31/37, hosted candidate checks, physical device and TalkBack are **NOT RUN**.
The simulated scales are not a system-font or device-setting check. No whole-WP
or source-family acceptance is claimed.

On 2026-11-03, the supported reservation status reported a hard
`gradle-execution` lock for candidate
`3c5297acdc083df21ec08afd7654aeaaa103ee59`. Its recorded Windows PID was not
present when checked; that alone is not cleanup proof or permission to release
the lock. No lock release was attempted.

The 2026-11-03 combined source-light checks ran against the retained checkout:
`python -B .\tools\android-port\portmap.py --repo .` exited 0 with 1,932
traceability rows, and `python -B .\tools\android-port\controller\validate.py
--repo .` exited 0 with 64 WPs, 1,866 reference files and 178 dependency edges.
The current-main-matching trusted validator entrypoints also exited 0 against
that checkout and its older manifest. These counts are not tests or
current-main candidate verification.

## Ownership and coordination

The trusted public capability interface admitted `modify` / `wp-owned` for
the status consumer, this feature test and this evidence file. The prior
claim attempt returned `Existing WP lease is bound to different
source/base/manifest/backend`; current `AUTOMATION.md` treats that historical
mismatch alone as advisory, not a local-authoring veto. The earlier intake
receipt and issue comments that called it a pre-edit custody blocker are
superseded; no ledger row was released, recreated or edited.

WP-312 PR #519 at `9007983f9a2b13199cdd4acbc53da896e9981001` changes the
neighbor-map branch of the same consumer, not the current/disappeared status-row
branch. The disjoint boundary and correction were communicated at
https://github.com/cbattlegear/MeshCoreOne-Android/issues/407#issuecomment-6598300473.
No acknowledgment is claimed and no WP-312 map code or map test was edited.
TS-01/25 history and the local TS-39 `DEAD`/`DEADBE` supplement remain intact.

For the four TS-07/TS-26 Kotlin paths only, a temporary isolated Git index was
loaded with `git read-tree` and the code-only patch checked with
`git apply --cached --check`. Both commands exited 0 against current main
`796a872a3565a66c74f782b738a9ecb3c273fb25` and WP-312 head
`9007983f9a2b13199cdd4acbc53da896e9981001`. Recorded HEAD, current-main ref,
real staging state, worktree status and the four Kotlin byte hashes were
unchanged; WP notes and TS-01/TS-39 were not included. This proves textual
applicability only, not an integrated or tested candidate.

The retained branch predates current main by 412 commits and includes the
unpublished TS-39 supplement. A current-base, exact-candidate hosted run cannot
be inferred from its previous results; source reconciliation and scoped
verification remain necessary without discarding or duplicating that work.
