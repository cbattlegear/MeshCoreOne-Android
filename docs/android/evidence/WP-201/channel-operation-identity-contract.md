# WP-201 canonical channel operation and identity primitives

Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_XOe76WlPLsFPecYdMJx1Xkcd`.
Independent source base: `0d10b838a181cd3bd34af1a0200435149dbc6bb0`,
`cbattlegear/MeshCoreOne-Android`. No schema/factory/service activation.
Read-only Swift specification remains `db14559b39d32322b06477c6ae676112f583db50`;
these synchronization primitives are Android-only.

## Custody and exact inputs

The coordinator assigned this new canonical contract to WP-201. WP-209 owner
3008 supplied the bounded required signatures and serialized its contract paths
to this owner. Storage owner f805 confirmed there is no corresponding gate
factory/guard primitive in its provisional schema-v2 candidate. Schema v3 is
provisional and not accepted or composed by this delta.

Only these explicitly identified independent extracts were inspected:

| Input | Exact blob / attribution |
| --- | --- |
| cc5f064c3c137c228a678f282f30fd50924e5394, ChannelIdentityMutation.kt | bf864645fb07c1f00f9e5b19b6cd45aa1d0b52e6; initial process gate, WP-201 |
| 728fe2eb4990dfd05a0a3f137d0ffba0e26f7e95, same file | 6134e29734d3be25d87b60f987f81aa52e908c93; explicit scoped writer, WP-201 |
| 8e23d5908d615eb45d11de41eea7f9b1a6cd953f, same file | 97570407f2f11041fd98ab3f459271c910f9ceb7; Job-owned non-reentrancy, WP-201 |
| 75318bf4c9646afc2d7a68dfeb0a46f872975b79, ChannelDispatchAdmission.kt | 0f48e14d86a742f7cac981841b4bedc99d34fe3b; connection admission, WP-201 |
| 75318bf4c9646afc2d7a68dfeb0a46f872975b79, ChannelDispatchAdmissionTest.kt | 3e50e1eb9543314d60775ba36dd43660aef79671; six original admission controls |

These are content provenance, NOT an ancestry import or acceptance of those
commits' other changes/parents. No held eca8/v4 source, storage implementation,
runtime graph or prohibited checkout was accessed. The clean source-only
publication must have the single base above as parent, not historical lineage.

## Exact semantics

`ChannelIdentityMutation` has five abstract suspend methods:
`withChannelOperation`, `withChannelIdentityMutation`,
`withChannelIdentityRestore`, `withChannelOperationScope`, and
`requireChannelOperationScope`. `ChannelIdentityWriteScope` exposes only
`withChannelIdentityMutation`. There are no default throwing/fake implementations.

`ChannelIdentityMutex.PROCESS` is the canonical shared gate for cooperating
process participants. Independent instances are supported for isolated tests;
using independent instances in separate production facade contexts does NOT
provide process-wide exclusion. This change wires no factory or consumer.

Lock order is operation -> identity -> consumer Room transaction:

- Ordinary writes and restore acquire BOTH operation and identity.
- A radio operation callback owns operation across its actual work. Its private
  issued scope enters identity only; it must not enter an ordinary writer.
- Scoped consumers validate the gate's scope before use, then use the scoped
  identity method and the actual storage transaction. No transaction is created
  by the contracts module.
- Scope authentication checks the issuer's private implementation, exact gate,
  active callback and same ACTIVE coroutine Job; object/DTO equality, a public
  caller implementation and inherited context are not authority.
- A child Job cannot borrow the scope. Mutex owner keys reject same-Job recursive
  operation or identity acquisition rather than deadlocking. Scope expires in
  finally on return, exception or cancellation; locks release normally.

Relative to 8e23, the ordinary identity entry is intentionally corrected to take
both locks (its old comment promised both but its implementation took identity
only), methods become abstract and active-Job checks are explicit before and
after acquisition. No silent compatibility shim preserves the unsafe nesting.
WP-209 specifically identified its old dispatch pattern
`withChannelOperation { withChannelIdentityMutation { ... } }` as incompatible;
future clean adaptation MUST use
`withChannelOperationScope { scope -> scope.withChannelIdentityMutation { ... } }`.
Ordinary storage and restore must not invoke an ordinary writer recursively.

The implementation never uses NonCancellable to mint/use authority and rejects
a direct NonCancellable Job. A foreign `withContext(NonCancellable)` cannot borrow
an existing scope. Public coroutine APIs do not generally authenticate an
arbitrary wrapper Job's ancestral cancellation policy; this gate does NOT claim
to detect all NonCancellable ancestry. A separately started operation is not
proof of an active connection. The real lifecycle owner must check/revoke its
connection lease. No cancellation-policy heuristic substitutes for that check.

## Connection/radio admission is separate

`ChannelDispatchAdmission` is the exact 753 source content: immutable SessionToken
(process epoch, generation, radio ID) plus actual connection object reference,
CAS acquire/revoke/count and idempotent permit completion. All six original
test bodies/assertions are retained; only package/import placement changes to
the owning contracts module instead of a model-module test dependency.

It is a publicly constructible accounting primitive, NOT a stand-alone opaque
connection, slot or storage proof. A consumer must authenticate that it is the
CURRENT admission issued by the real lifecycle owner, then validate its exact
token and connection reference. Equal token values are permitted for an actual
same-session admission; equal-but-distinct connection objects are not.

Revocation rejects new admissions but does not cancel already admitted work,
fence physical transport buffers or revoke a scope held by an independent gate.
The lifecycle/fence owns actual monotonic generation, retirement/quarantine,
teardown and database/radio binding checks. It must compose gate-scope checks
with its real session/connection/radio lease, not assume either primitive proves
the other's invariants. No such issuer/factory exists in this source-only delta.

Raw DAO/SQL and other handles/processes still require database-level mutation
fencing. Process exclusion neither rotates durable occupant epochs nor proves
ordered receive origin. Initial/migration/DTO state remains unresolved; exact
actual ChannelInfo producer resolution and schema-v3 rules remain other owners'
unaccepted work. Historical authentic A/A-after-B remains required; no active-slot
comparison or positive receive issuer is added.

## Controls and admission

Eight new gate controls cover same-owner scoped success/order, ordinary/restore
waiting for operation, forged/wrong-gate rejection, child/foreign NonCancellable
scope use, normal/exception expiry, recursive ordinary/scoped entry, cancelled
owner expiry/release, and cancelled waiter release. Six preserved admission
controls cover token dimensions/reference identity, pre-revocation, in-flight
completion, concurrent idempotent completion, revoke/acquire race and overflow.
These are authored source controls, NOT executed or claimed passing.

Trusted inputs were independently read at base0d10:
manifest `e81605857f5aa7b893dcd63224fb27a45d7b0f3a`,
policy `e56438d64d2309c964353066f26e1427f17f55d8`,
capability engine `1001f43112d9bff9c9985929a203b5166c41559e`.
The two domain sources and this evidence descendant admit `wp-owned/create`.
The two core/contracts test descendants admit
`reviewed-support-test/add-or-update-related-test`; they test only these sources.
No authorization/policy/ledger edit or executed validator receipt is claimed.

Existing protocol `api(libs.kotlinx.coroutines.core)` supplies coroutine APIs to
contracts; existing JVM convention supplies Kotlin/JUnit5 test support. No new
dependency/build-graph change. Local-check owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is retained; no local Gradle,
compiler, controller, readiness recovery or alternative worktree execution.
Exact-head hosted compilation/test discovery remains required. Source checks
and immutable publication checks are not a successful build or runtime proof.
No Room migration/schema, product adapter, PR, merge or activation is included.
