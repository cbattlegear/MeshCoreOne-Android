# WP-201 isolated neutral persistence event

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift specification: `db14559b39d32322b06477c6ae676112f583db50`.
Exact landed base: `35abcc1276082995eed216bf28bff04d77e176c8`,
tree `91150b99594daa3a8cb965d84e0370005a14c124`.
Coordinator assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_Hn50kDoxrExQX6pktWil7Ubp`.

## Scope and actual admission

This is the already-drafted additive `MessageStatusEvent.PersistenceChanged(key:
EntityKey)` declaration, isolated onto landed main rather than importing any
retained Direct ACK/channel contract ancestry. The sole production change is
KDoc plus that case in `MessagingContracts.kt`, base blob
`e729cf219da63d95f824dbb9e5ddaa59fdfec8f2`.
13a confirmed exclusive serialization of this declaration; b0 owns the App
counterpart, dispatcher and chat consumer. No App, service, repository, database,
gate, reaction-cache or retained authority files are changed by this delta.

Authenticated REST reads verified the exact base and trusted inputs before
source-derived path/operation admission:

| Trusted input | Blob |
| --- | --- |
| `docs/android/port-manifest.json` | `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` |
| `docs/android/automation-policy.json` | `e56438d64d2309c964353066f26e1427f17f55d8` |
| `tools/android-port/controller/capabilities.py` | `1001f43112d9bff9c9985929a203b5166c41559e` |

| Path | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/MessagingContracts.kt` | `wp-owned / modify` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/MessagePersistenceChangedContractTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/neutral-persistence-event-isolated.md` | `wp-owned / create` |

The manifest assigns domain/evidence descendants to WP-201. The separate test
matches both the policy support glob and the trusted Kotlin test-path regex.
Only directly related new assertions are added; no original assertions are
removed or weakened. No controller/reservation transaction or generated
admission receipt is claimed. Authorization, policy, reservations and execution
locks remain unchanged. No held-work import or other worktree inspection occurs.

## Contract and native adaptation

`PersistenceChanged` is a radio-scoped **hint to refetch actual persisted state**,
including absence, deletion or a replacement at the same key. `EntityKey` is
`(radioId, id)`; equal message UUIDs on different radios are distinct invalidations.
The key deliberately does not assert an incarnation or status. A replacement
with the same key is indistinguishable from the original in this hint.

It is not a SENT, Resent, FAILED, mark-seen, delivery, indexing, retry, pending
cleanup, mutation, occupant-currentness or dispatch instruction. Neither the
event nor subsequent readback supplies authority to mutate a replacement row.
Consumers fence the current radio/session, neutrally refetch, and display the
actual result without replaying captured status or invoking failed-send effects.
The separately assigned real consumers must prove those side-effect exclusions.

13a owns modern channel publication after an actual typed Applied or exact
AlreadyApplied completion/failure/remap/cleanup result. The contract does not
manufacture those results. Historical replay or stale identity is not authority
to emit ID-only status actions onto today's row. Existing direct-message
`StatusResolved`, `Resent`, `Retrying`, `RoutingChanged` and `Failed` declarations
and semantics are preserved byte-for-byte; this is not a global event migration.

This is an approved Android-only neutral notification adaptation, not a new
Swift case or original-case parity claim. The pinned source headers and all
other declarations remain unchanged.

## Validation and limits

Five new value controls cover full key retention, cross-radio separation,
same-key replacement ambiguity, existing direct status/RTT and other existing
direct payloads. These are value/shape assertions, not App/Room/radio proof.
They remain **uncompiled and unexecuted**.

Source-only validation compares candidate production text with the exact base
after removing just the six-line additive case/KDoc. It must match exactly.
It checks five authored test annotations, ASCII, final newlines and absence of
trailing whitespace, then verifies the remote commit parent/tree/ref and exactly
three changed paths. These checks do not prove Kotlin exhaustiveness, runtime
consumer behavior or test discovery.

Actual source-only results:

- `gh api repos/cbattlegear/MeshCoreOne-Android/git/commits/35abcc1276082995eed216bf28bff04d77e176c8`:
  passed; exact base/tree verified.
- `gh api repos/cbattlegear/MeshCoreOne-Android/contents/<path>?ref=35abcc1276082995eed216bf28bff04d77e176c8`:
  passed for the production contract, manifest, policy, capability engine, plan,
  local-check guide, module build declaration and `RepositoryValues.kt`.
- PowerShell ordinal comparison of normalized base/candidate text after removing
  only the exact additive KDoc/case: passed. Test annotation count: five.
  ASCII, trailing-whitespace and final-newline checks: passed for all three files.

`docs/android/ci/local-checks.md` at the exact base was read. Retained execution
owner `local-check:f516a7e271f444dd9cb2efcc8b995a16` remains held; no local Gradle,
compiler, readiness installation, controller, validator, local Git/object command
or lock recovery runs. No hosted result, passing tests or merged prerequisites
are claimed. Actual hosted compilation/tests and b0's radio-fenced neutral
consumer controls remain integration obligations. No PR, dispatch or merge is
requested or performed.
