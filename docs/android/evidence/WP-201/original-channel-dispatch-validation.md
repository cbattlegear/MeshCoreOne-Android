# WP-201 original channel dispatch validation contract

Repository: `cbattlegear/MeshCoreOne-Android`.
Pinned Swift specification: `db14559b39d32322b06477c6ae676112f583db50`.
Independent exact-main base: `35abcc1276082995eed216bf28bff04d77e176c8`,
tree `91150b99594daa3a8cb965d84e0370005a14c124`.
Assignment:
`assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_BNQOfp8vNVw5Tlm2Lh3G0vSV`.

## Independent bounded scope

Three additions only: opaque original admission plus callback-scoped validator
interface and result types, six value-shape controls, this evidence. No existing
main source is modified. No original prepare factory, implementation, default
validator, schema, DAO, service, generic guard or acceptance receipt is supplied.
This contract cannot activate dispatch. No held ACK/channel/failure/completion
type or implementation is imported, inspected or treated as a dependency.

The separately approved reaction source
`67a22f7f9e75db2b8188ed890e04cc3b25d62a02` has sole parent35ab. Its three files
remain unchanged and separately attributable WP-201 dependency input for f805.
It is not this delta's parent or a dispatch producer. Reaction-specific identity
with benign-update lineage cannot authenticate full message/pending dispatch.

## Trusted path and operation admission

Serial REST source reads reverified exact main and these trusted blobs:

| Input | Blob |
| --- | --- |
| `docs/android/port-manifest.json` | `e81605857f5aa7b893dcd63224fb27a45d7b0f3a` |
| `docs/android/automation-policy.json` | `e56438d64d2309c964353066f26e1427f17f55d8` |
| `tools/android-port/controller/capabilities.py` | `1001f43112d9bff9c9985929a203b5166c41559e` |

| Addition | Capability / operation |
| --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/OriginalChannelDispatchAuthority.kt` | `wp-owned / create` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/domain/OriginalChannelDispatchAuthorityTest.kt` | `reviewed-support-test / add-or-update-related-test` |
| `docs/android/evidence/WP-201/original-channel-dispatch-validation.md` | `wp-owned / create` |

Manifest WP-201 domain/evidence descendants and policy support-test operation,
plus the controller Kotlin-test regex, admit these exact operations. Only related
new tests are added; no original assertions or public Boolean/channel contracts
change. No controller transaction, generated reservation receipt or executed
ownership validator is claimed. Authorization, policy, reservations and execution
locks are not edited. Source-derived path admission is not a schema assignment.

## Agreed API and responsibilities

```kotlin
interface OriginalChannelDispatchAdmission
enum class OriginalChannelDispatchRejection {
    INVALID_ADMISSION, NOT_ELIGIBLE, CONFLICT,
}
sealed interface OriginalChannelDispatchResult<out T> {
    data class Executed<T>(val value: T) : OriginalChannelDispatchResult<T>
    data object Stale : OriginalChannelDispatchResult<Nothing>
    data class Rejected(
        val reason: OriginalChannelDispatchRejection,
    ) : OriginalChannelDispatchResult<Nothing>
}
interface OriginalChannelDispatchAuthority {
    suspend fun <T> withValidatedOriginalChannelDispatch(
        admission: OriginalChannelDispatchAdmission,
        block: suspend () -> T,
    ): OriginalChannelDispatchResult<T>
}
```

3008 agreed callback-only shape and owns separate
`RepositoryMutationGuard.PROCESS.withDispatch` / `withMutation` source and all
participating RoomRepositoryContext mutation-path adoption. No overlapping
authority file is owned there. The proposed guard has no Job transition, and a
private inherited active-context marker detects illegal nested dispatch/mutation
without granting authority. A read does not reacquire that lane.

13a agreed actual service-wrapper fit. The service holds original prepared
immutable MessageDTO and exact optional PendingSendDTO, immutable initiating
SessionToken and actual session object; a later reread or held-attempt adapter
cannot manufacture the token/snapshot pair. The service sets RAM acceptance only
after its real session send returns successfully INSIDE the callback.

f805 has no objection to the shape and explicitly excludes dispatch identity
and issuance from its independent reaction-only v2 producer/migration.

The required interval is:

1. Caller holds original occupant fence and live connection/identity ownership.
2. Authority acquires process participating-writer dispatch lane.
3. Same-store context.read checks authenticated ORIGINAL message incarnation AND
   expected full-mutation revision, original wire tuple, original known occupant,
   initiating session and exact optional pending identity/revision/payload/count.
4. Release Room read transaction and per-store mutex, retaining mutation lane.
5. Invoke exact original wire once for this call and synchronous RAM-only note.
6. Release lane; only afterward perform separately authorized completion/events.

Stale includes a deleted/missing or changed original row; Rejected covers invalid
issuer/handle and ineligible/conflicting relationship. Neither invokes callback.
No Current, replacement snapshot or transferable validation grant escapes.
Executed(Unit) means callback returned, not manufactured acceptance or delivery.
Callback exceptions/cancellation propagate unchanged, including ambiguous
post-wire failure. No Uncertain receipt is fabricated from exception catching.
No automatic retry, consumption ledger or one-send-across-repeated-calls promise
exists. Original attempt/acceptance/fence logic separately prevents duplicate wire.

## Actual main inputs and missing producer

Main `ConnectionContracts.kt` blob `458ff91dbd9480d273092190bf363b9ee8c06188`
has SessionToken(epoch,generation,radioId), not admission. `SendEnvelopes.kt`
blob `d68c55365a74cdae73c3caff03979362731bd952` contains original channel
envelope and PendingSendDTO inputs. Main `MessageService.kt`
blob `14568ac582098c3fe98bbd2c0d0d3f78ea5365f5` uses key-based outgoing
bookkeeping; it is not an issuer or validator.

Main `RoomRepositoryContext.kt` blob
`85d5593ac6f45783acbefc5efeabb58a24f111d4` has per-context mutex/Room
transactions, not the shared guard spanning real transport. A context-only RAM
lease does not exclude other Room handles, raw DAO/SQL or another process.
The process guard protects participating writers only, not those bypasses.

BLOCKED activation prerequisites, not supplied by this declaration:

1. Independently assigned original-prepare producer and persisted full-mutation
   message plus optional pending incarnation/revision, minting actual token and
   immutable original input together. No key/equal-DTO/successor regrant.
2. Explicit original no-pending/public versus queued eligibility, competing
   pending policy, actual session binding and issuer authentication. These must
   be defined by that producer, not inferred from absence or a public interface.
3. Actual participating-writer guard adoption including relevant writes, restore,
   close, flush/commit lifecycle and explicit illegal-reentry rejection. Bypasses
   must be excluded before claiming validation-to-wire exclusion.
4. Actual original dispatch, cancellation, stale/missing, same-value mutation,
   delete/recreate, restore, pending count/payload, session-retirement, reentry,
   release-order and ambiguous wire-return acceptance evidence.

## Validation and limits

Source-only checks cover ASCII, final newline/trailing whitespace, six test
declarations, one abstract suspend callback method, absence of held-type
dependencies, exact three additions and sole35ab parent on remote publication.
These are source/diff checks, not Kotlin compilation or behavioral test results.

Six authored result/marker shape controls are UNCOMPILED/UNEXECUTED. They exercise
retained callback result, nullable value, stale covariance, typed rejection,
distinct opaque test markers and covariant result observation. Test markers
are NOT accepted production issuers. They prove no transaction, authentication,
coroutine, mutation-exclusion or radio behavior.

Local-check execution owner `local-check:f516a7e271f444dd9cb2efcc8b995a16`
remains retained. The host-specific guide was read; no local Gradle/compiler,
controller/readiness recovery, Git/object processing, alternate workspace,
held implementation import, schema/DAO change, PR or merge is performed.
Hosted compilation/test discovery and independently assigned producer/consumer
behavior remain unverified. No passing CI bundle or execution receipt is claimed.
