# Bounded channel-dispatch lifecycle candidate

WP-207 repair; pinned reference `db14559b39d32322b06477c6ae676112f583db50`.
Original worktree base `796a872a3`; independent diagnostic foundation
`8148c0f43bae5fc3f9d5ceea44007513861c41b6` is unaccepted, not green evidence.
Candidate commits and exact hosted execution are reported by their actual Git
identities and canonical workflow run, not a second retained CI-result bundle.

## Custody and admission

Before editing, the live #495 producer `a85352c6-96c7-44eb-806d-de7ad856974d`
explicitly serialized prospective runtime channel-revocation hunks to this stream,
then attributed the narrow factory registry-publication guard and graph lease
binding/early-revocation hunks here. The narrow additions retain the
manager -> registry -> graph -> app ordering. Separately committed #495 manager
lifecycle-stamp changes still require composition; its AppContainer/AppState/
navigation drafts are not imported, edited or published.

Current #529 producer `b33644ad-3a9a-4a37-af40-9a50b34ced2a` confirmed its work is
tests/evidence only and retains no overlapping production hunks. The merged #475
producer `be9fa0fd-35b2-4e78-bffc-917adc273f29` explicitly confirmed no unpublished
graph/factory hunks. Neither idle state nor old PR metadata was used as transfer.

Trusted `wp-owned` admits runtime source modification and new lifecycle test
creation. `app-build-launcher` / `add-direct-support-wiring` admits the actual App
factory/graph and integration-test paths. Initial claim against the historical
WP-207 reservation refused its different base/manifest binding; that reservation,
actual identity, authorization and history remain unchanged. Supported attributed
`reserve.py transfer` metadata for the runtime manager reports advisory,
`file_edit_blocked=false`, retaining both originating and actual producer identities.
No release/recreate, private ledger or execution-owner rewrite occurred.

A proposed WP-207 contracts path was rejected. WP-201's actual identity producer
authored the separately admitted shared contract instead. Only exact independent
diffs were composed: `75318bf4c` (atomic contract), `f9e7e47b4`, `3d1411a96`,
`bca1dbc07` (fence primitives), and `0b26607fb` (migration fixture and scoped
orphan-intent repair). Evidence-only cherry-pick context conflicts retain both
producers' sections. No held WP-405 source or #495/#529 work is included.

## Implemented behavior

Each real owner binds a single required handle to its actual SessionToken and
MeshCoreSession before invoking the service factory. A per-owner monotonic marker
also rejects binding after an early callback, before the token even exists.
FactoryOwnership requires the same handle, rejects stale registration/return, and
revokes before cleanup. Manager detach, logical/physical close, retained reconnect,
reconnection failure, shutdown and real loss callbacks revoke admission before
waiting. Correlation diagnostics revoke synchronously, before asynchronously
waiting for already-admitted channel-query accounting.

If recovery/loss revokes an owner before a factory exists, binding waits
cancellably outside the manager lock for its already-queued retirement. It does
not invent a new factory failure/retry-budget charge or allow a transient READY
graph. The prior best-effort auto-add native regression preserves diagnostic,
fallback, retry-budget and fresh-reconnect checks, with explicit no-graph/no-token
assertions while recovery is paused.

Factory publication is a restricted state-only commit: registered service identity
is checked outside the boundary, actual manager owner/token/session/handle inside,
then registry -> graph state is committed. No observer, teardown, suspend point,
fence lock, operation/identity lane, Room or I/O belongs in that boundary. Registry
allocation tracking is distinct from live publication, so a rejected late factory
can still be torn down exactly once without negative outstanding counts. Graph
early teardown revokes its same handle before later publication can succeed.

## Authored discriminators

Eight runtime dynamic cases use the actual ConnectionManager and MeshCoreSession:
exact binding; noncooperative detached-before-factory-publication; retained
rebuild/admitted accounting; stale owner callbacks; synchronous link-loss
revocation; paused correlation recovery with retained accounting; early factory
close; reconnection failure plus shutdown while service teardown is held.

Four App tests compose the real process graph and Room store. They exercise a
fresh actual GET_CHANNEL plus scoped save/reconciliation followed by an actual
fenced protocol send and retired refusal; manager revocation from another thread
while the Room operation lane is held; closed-graph registry-publication refusal;
and stale callbacks after a successor graph publishes. These direct fence tests
are deliberately not represented as concrete message/channel service integration.

## Verification and remaining gates

Native Windows readiness preflight passed using Python 3.12.4, the inherited
same-host ANDROID_CI_STATE, and a fresh absolute output directory.
`python .\tools\android-port\controller\validate.py` passed.
`python .\tools\android-port\portmap.py` passed.
`git diff --check` passed.

No local Gradle/JVM/WSL product verification was attempted: retained
`gradle-execution` owner `local-check:f516a7e271f444dd9cb2efcc8b995a16` has no
cleanup proof and is unchanged. Authored Kotlin tests are unverified until the
exact-candidate hosted job discovers and executes them without failures/skips.

First hosted run [41601419363](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41601419363)
at exact `f09fa61703c8db35a417e0b06627ad7a688be64e` failed the prerequisite
legacy migration fixture's optional `indices` JSON read. Available reports
contained 918 discovered tests, 917 passed and one failed, zero errors/skips;
mandatory services/runtime/App reports were missing. Controller/policy and the
Swift codec oracle passed, not lifecycle acceptance. The WP-201 producer supplied
isolated `b8303ba7c` and `e1f4c05ce` fixture-only corrections, composed unchanged.
New runtime test scheduling/expected-failure setup was corrected separately;
no test was removed and no assertion was weakened.

Second hosted run [41602795069](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41602795069)
at exact `41c4b20b6dc7b7cb071f415f07f3105411ab0dae` passed all 5,985 available
discovered tests, but failed the new runtime test's WiFi fixture port literal:
`Int` was supplied where the actual target requires `UShort`. Runtime, App and
other mandatory reports were absent; evidence was incomplete. The new fixtures
were corrected to unsigned typed port literals, without widening the API or
casting away type safety. App scheduling assertions now require actual READY,
not the earlier SYNCING-or-READY operational state.

The identity producer's standalone `c590e7e1c` correction is composed unchanged:
DTO saves retain existing and pending history while occupant epochs still rotate;
authoritative replacement remains separately discriminated. Existing reopen
assertions use the actual schema version. This is not a schema-3/journal import
and has no Kotlin execution credit until a fresh exact-candidate run.

Full production ChannelService wiring is blocked by its owner's frozen WP-405
dispatch/UUID-receipt/uncertainty cohort; MessageService wiring remains its current
producer's work. No interface-only or fake-only result completes that integration.
The in-memory admission handle is not an exact-owner retirement/absence receipt
or a crash-safe transition ticket. Durable route supersession and transition
publication ordering remain separate, unimplemented prerequisites for #495.
Physical noncooperative late-write drain, actual Android/radio behavior, signing,
release and merge acceptance are not established. Revocation/accounting never
clears uncertainty; no quarantine-reset or success-shaped fallback is introduced.
