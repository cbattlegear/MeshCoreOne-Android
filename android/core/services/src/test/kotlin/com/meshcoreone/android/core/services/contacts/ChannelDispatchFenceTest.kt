// AndroidOnly: WP-209 Deterministic shared-lane schedules; not physical-radio or complete graph acceptance.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ChannelDispatchAdmission
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelDispatchFenceTest {
    private class Fixture {
        val gate = ChannelIdentityMutex()
        val fence = ChannelDispatchFence(gate)
        val radio = RadioId(UUID(0, 1))
        val epoch = ProcessEpoch(UUID(0, 2))
        val connection = Any()
        val admission = ChannelDispatchAdmission(token(1), connection)
        val lease = fence.openSession(token(1), connection, admission)
        var occupant: UUID? = UUID(0, 3)
        val intended = occupant
        val effects = mutableListOf<String>()

        fun token(generation: Long, radioId: RadioId = radio) = SessionToken(epoch, Generation(generation), radioId)

        fun open(token: SessionToken, connection: Any): ChannelDispatchFence.Lease =
            fence.openSession(token, connection,
                if (token == admission.token && connection === admission.connectionIdentity) admission
                else ChannelDispatchAdmission(token, connection))

        suspend fun send(lease: ChannelDispatchFence.Lease = this.lease) =
            fence.dispatch(lease, 7u, intended, { radioId, index ->
                assertEquals(radio, radioId)
                assertEquals(7.toUByte(), index)
                occupant
            }) { effects += "send" }

        suspend fun replace() = fence.withMutation(lease) {
            gate.withChannelIdentityMutation {
                occupant = UUID(0, 4)
                effects += "replace"
            }
        }
    }

    private suspend fun preparedFixture(): Fixture = Fixture().also { f ->
        f.gate.withChannelOperationScope { scope ->
            scope.withChannelIdentityMutation { assertEquals(f.intended, f.occupant) }
            f.fence.recordReconciled(f.lease, scope, listOf(7u))
        }
    }

    @Test
    fun bindingRequiresOriginalRadioTokenAndExactConnectionBeforeAuthorityRead() = runTest {
        val f = preparedFixture()
        var reads = 0
        val binding = f.fence.bind(f.lease) { _, _ -> reads++; f.occupant }
        val origin = ChannelHistoryScope(f.radio, 7u, requireNotNull(f.intended))
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            binding.dispatch(f.token(1), Any(), origin) { error("Wrong connection") }
        }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            binding.dispatch(f.token(2), f.connection, origin) { error("Wrong generation") }
        }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            binding.dispatch(f.token(1), f.connection, origin.copy(radioId = RadioId(UUID(0, 9)))) {
                error("Wrong radio")
            }
        }
        assertEquals(0, reads)
    }

    @Test
    fun boundWireAndSynchronousAcceptanceFinishBeforeReplacementAndReturnTheirResult() = runTest {
        val f = preparedFixture()
        val binding = f.fence.bind(f.lease) { _, _ -> f.occupant }
        val origin = ChannelHistoryScope(f.radio, 7u, requireNotNull(f.intended))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val send = async {
            binding.dispatch(f.token(1), f.connection, origin) {
                entered.complete(Unit)
                release.await()
                f.effects += "wire returned"
                f.effects += "acceptance noted"
                123u to "accepted"
            }
        }
        entered.await()
        val replacement = launch { f.replace() }
        runCurrent()
        assertFalse(replacement.isCompleted)
        release.complete(Unit)
        assertEquals(123u to "accepted", send.await())
        replacement.join()
        assertEquals(listOf("wire returned", "acceptance noted", "replace"), f.effects)
    }

    @Test
    fun bindingRechecksOriginalOccupantOnEveryRetryAndRejectsUnresolvedAuthority() = runTest {
        val f = preparedFixture()
        val binding = f.fence.bind(f.lease) { _, _ -> f.occupant }
        val origin = ChannelHistoryScope(f.radio, 7u, requireNotNull(f.intended))
        binding.dispatch(f.token(1), f.connection, origin) { f.effects += "first attempt" }
        f.replace()
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> {
            binding.dispatch(f.token(1), f.connection, origin) { error("Never rebind old text") }
        }
        f.fence.withMutation(f.lease) {
            f.gate.withChannelIdentityMutation { f.occupant = null }
        }
        assertFailsWith<ChannelDispatchFailure.UnknownOccupant> {
            binding.dispatch(f.token(1), f.connection, origin) { error("Unresolved radio authority") }
        }
        assertEquals(listOf("first attempt", "replace"), f.effects)
    }

    @Test
    fun cancelledBindingWaiterCannotBecomeFreshWireOperation() = runTest {
        val f = preparedFixture()
        val binding = f.fence.bind(f.lease) { _, _ -> f.occupant }
        val origin = ChannelHistoryScope(f.radio, 7u, requireNotNull(f.intended))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutation = launch {
            f.fence.withMutation(f.lease) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val send = launch {
            binding.dispatch(f.token(1), f.connection, origin) { f.effects += "must not send" }
        }
        runCurrent()
        send.cancel()
        send.join()
        release.complete(Unit)
        mutation.join()
        assertTrue(f.effects.isEmpty())
        assertEquals(0L, f.admission.inFlightCount)
    }

    @Test
    fun leaseRequiresExactManagerAdmissionAndCannotReplaceItsHandle() = runTest {
        val f = preparedFixture()
        val substituted = ChannelDispatchAdmission(f.token(1), f.connection)
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            f.fence.openSession(f.token(1), f.connection, substituted)
        }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            f.fence.openSession(f.token(2), f.connection, f.admission)
        }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            f.fence.openSession(f.token(1), Any(), f.admission)
        }
        assertEquals(0L, substituted.inFlightCount)
        assertEquals(0L, f.admission.inFlightCount)
        f.admission.revoke()
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.open(f.token(1), f.connection) }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.send() }
    }

    @Test
    fun managerRevocationDuringAuthorityReadPreventsAtomicWireAdmission() = runTest {
        val f = preparedFixture()
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ ->
                f.admission.revoke()
                f.occupant
            }) { error("Revoked during authority read") }
        }
        assertEquals(0L, f.admission.inFlightCount)
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun admittedDispatchRetainsPermitAfterRevocationAndCancellationUntilActualReturn() = runTest {
        val f = preparedFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sending = launch {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ -> f.occupant }) {
                assertEquals(1L, f.admission.inFlightCount)
                entered.complete(Unit)
                release.await()
                f.effects += "accepted"
            }
        }
        entered.await()
        f.admission.revoke()
        sending.cancel()
        assertEquals(1L, f.admission.inFlightCount)
        assertTrue(f.admission.isRevoked)
        val successor = f.open(f.token(2), Any())
        val mutation = launch { f.fence.withMutation(successor) { f.effects += "successor" } }
        runCurrent()
        assertFalse(mutation.isCompleted)
        release.complete(Unit)
        sending.join()
        mutation.join()
        assertTrue(sending.isCancelled)
        assertEquals(0L, f.admission.inFlightCount)
        assertEquals(listOf("accepted", "successor"), f.effects)
    }

    @Test
    fun scopedMutationPermitAccountsCompletionButCannotReadmitRevokedLease() = runTest {
        val f = preparedFixture()
        f.gate.withChannelOperationScope { scope ->
            f.fence.withScopedMutation(f.lease, scope) {
                assertEquals(1L, f.admission.inFlightCount)
                f.admission.revoke()
                scope.withChannelIdentityMutation { f.effects += "persist admitted" }
                assertFailsWith<ChannelDispatchFailure.RetiredSession> {
                    f.fence.recordReconciled(f.lease, scope, listOf(7u))
                }
            }
        }
        assertEquals(0L, f.admission.inFlightCount)
        assertEquals(listOf("persist admitted"), f.effects)
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.send() }
    }

    @Test
    fun managerRevokedStaleFactoryDoesNotStrandSuccessorOrResetQuarantine() = runTest {
        val f = preparedFixture()
        f.admission.revoke()
        val successor = f.open(f.token(2), Any())
        assertFalse(successor === f.lease)
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send(successor) }
        val failure = MeshCoreException.Timeout()
        f.fence.quarantine(f.lease, failure)
        assertSame(failure, assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> {
            f.fence.withMutation(successor) { error("Old uncertainty still blocks successor") }
        }.cause)
    }

    @Test
    fun withdrawingObservedSlotsRequiresActiveScopeAndDoesNotAffectOtherSlots() = runTest {
        val f = preparedFixture()
        f.gate.withChannelOperationScope { scope ->
            f.fence.recordReconciled(f.lease, scope, listOf(8u))
            f.fence.forgetReconciled(f.lease, scope, listOf(7u))
        }
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send() }
        f.fence.dispatch(f.lease, 8u, f.intended, { _, _ -> f.occupant }) { f.effects += "other slot" }
        assertEquals(listOf("other slot"), f.effects)
    }

    @Test
    fun cachedEpochCannotAdmitFreshConnectionOrAnUnreadSlot() = runTest {
        val f = Fixture()
        var reads = 0
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ -> reads++; f.occupant }) { error("must not send") }
        }
        assertEquals(0, reads)
        f.gate.withChannelOperationScope { scope ->
            f.fence.recordReconciled(f.lease, scope, listOf(7u))
        }
        f.send()
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> {
            f.fence.dispatch(f.lease, 8u, f.intended, { _, _ -> f.occupant }) { error("unread slot must not send") }
        }
        f.fence.retire(f.lease)
        val successor = f.open(f.token(2), Any())
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send(successor) }
        f.gate.withChannelOperationScope { scope -> f.fence.recordReconciled(successor, scope, listOf(7u)) }
        f.send(successor)
        assertEquals(listOf("send", "send"), f.effects)
    }

    @Test
    fun reconciliationCannotUseForgedOrExpiredCapability() = runTest {
        val f = Fixture()
        val forged = object : ChannelIdentityWriteScope {
            override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T = block()
        }
        assertFailsWith<IllegalStateException> { f.fence.recordReconciled(f.lease, forged, listOf(7u)) }
        lateinit var expired: ChannelIdentityWriteScope
        f.gate.withChannelOperationScope { expired = it }
        assertFailsWith<IllegalStateException> { f.fence.recordReconciled(f.lease, expired, listOf(7u)) }
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send() }
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun heldDispatchPrecedesReplacementAcrossServiceInstances() = runTest {
        val f = preparedFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sameLease = f.open(f.token(1), f.connection)
        assertSame(f.lease, sameLease)
        val send = async {
            f.fence.dispatch(sameLease, 7u, f.intended, { _, _ -> f.occupant }) {
                entered.complete(Unit)
                release.await()
                f.effects += "send"
            }
        }
        entered.await()
        val replace = launch { f.replace() }
        runCurrent()
        assertFalse(replace.isCompleted)
        assertEquals(f.intended, f.occupant)
        release.complete(Unit)
        send.await()
        replace.join()
        assertEquals(listOf("send", "replace"), f.effects)
    }

    @Test
    fun heldReplacementRejectsOldPendingIntentBeforeWire() = runTest {
        val f = preparedFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replace = launch {
            f.fence.withMutation(f.lease) {
                entered.complete(Unit)
                release.await()
                f.gate.withChannelIdentityMutation { f.occupant = UUID(0, 4) }
            }
        }
        entered.await()
        val send = async { assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send() } }
        runCurrent()
        assertFalse(send.isCompleted)
        release.complete(Unit)
        replace.join()
        send.await()
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun cancellationAfterAdmissionDoesNotReleaseForLateDispatch() = runTest {
        val f = preparedFixture()
        val entered = CompletableDeferred<Unit>()
        val lateTransport = CompletableDeferred<Unit>()
        var accepted = false
        val send = launch {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ -> f.occupant }) {
                entered.complete(Unit)
                lateTransport.await()
                f.effects += "send"
                accepted = true
            }
        }
        entered.await()
        send.cancel()
        val replace = launch { f.replace() }
        runCurrent()
        assertFalse(send.isCompleted)
        assertFalse(replace.isCompleted)
        lateTransport.complete(Unit)
        send.join()
        replace.join()
        assertTrue(send.isCancelled)
        assertTrue(accepted, "The producer records acceptance inside the non-cancellable operation")
        assertEquals(listOf("send", "replace"), f.effects)
    }

    @Test
    fun cancellationWhileWaitingForAdmissionNeverDispatches() = runTest {
        val f = preparedFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutation = launch {
            f.fence.withMutation(f.lease) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val send = launch { f.send() }
        runCurrent()
        send.cancel()
        send.join()
        release.complete(Unit)
        mutation.join()
        assertTrue(f.effects.isEmpty())
        f.send()
        assertEquals(listOf("send"), f.effects)
    }

    @Test
    fun retiringDuringIdentityReadPreventsWire() = runTest {
        val f = preparedFixture()
        f.fence.dispatchFailureAfterRetiredRead(f)
        assertTrue(f.effects.isEmpty())
    }

    private suspend fun ChannelDispatchFence.dispatchFailureAfterRetiredRead(f: Fixture) {
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            dispatch(f.lease, 7u, f.intended, { _, _ ->
                retire(f.lease)
                f.occupant
            }) { f.effects += "send" }
        }
    }

    @Test
    fun retirementRejectsQueuedOldConnectionButAdmittedCallDrainsBeforeSuccessor() = runTest {
        val f = preparedFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val send = launch {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ -> f.occupant }) {
                entered.complete(Unit)
                release.await()
                f.effects += "old send"
            }
        }
        entered.await()
        f.fence.retire(f.lease)
        val next = f.open(f.token(2), Any())
        val successor = launch {
            f.fence.withMutation(next) { f.effects += "successor mutation" }
        }
        runCurrent()
        assertFalse(successor.isCompleted)
        release.complete(Unit)
        send.join()
        successor.join()
        assertEquals(listOf("old send", "successor mutation"), f.effects)
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.send() }
    }

    @Test
    fun ambiguousFailurePoisonsRadioAcrossRetirementAndReconnect() = runTest {
        val f = preparedFixture()
        val failure = MeshCoreException.ConnectionLost()
        assertFailsWith<MeshCoreException.ConnectionLost> {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ -> f.occupant }) { throw failure }
        }
        assertSame(failure, assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> {
            f.open(f.token(1), f.connection)
        }.cause)
        assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> { f.replace() }
        assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> { f.send() }
        f.fence.retire(f.lease)
        assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> { f.open(f.token(2), Any()) }
    }

    @Test
    fun sessionCancellationInsideOperationQuarantinesBeforeUnlock() = runTest {
        val f = preparedFixture()
        assertFailsWith<CancellationException> {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ -> f.occupant }) {
                throw CancellationException("session ended without transport drain proof")
            }
        }
        assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> { f.replace() }
    }

    @Test
    fun definiteDeviceRejectionDoesNotQuarantine() = runTest {
        val f = preparedFixture()
        assertFailsWith<MeshCoreException.DeviceError> {
            f.fence.dispatch(f.lease, 7u, f.intended, { _, _ -> f.occupant }) {
                throw MeshCoreException.DeviceError(1u)
            }
        }
        f.replace()
        assertEquals(listOf("replace"), f.effects)
    }

    @Test
    fun producerUncertainMutationQuarantinesOtherInstances() = runTest {
        val f = preparedFixture()
        val failure = MeshCoreException.Timeout()
        f.fence.withMutation(f.lease) { f.fence.quarantine(f.lease, failure) }
        assertSame(failure, assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> {
            f.open(f.token(1), f.connection)
        }.cause)
        assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> { f.send() }
    }

    @Test
    fun sameKeyRenamePreservesEpochButClearDifferentKeyAndAbaDoNot() = runTest {
        val f = preparedFixture()
        f.fence.withMutation(f.lease) {
            f.gate.withChannelIdentityMutation { f.effects += "rename" }
        }
        f.send()
        f.replace()
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send() }
        f.fence.withMutation(f.lease) { f.gate.withChannelIdentityMutation { f.occupant = null } }
        assertFailsWith<ChannelDispatchFailure.UnknownOccupant> { f.send() }
        f.fence.withMutation(f.lease) {
            f.gate.withChannelIdentityMutation { f.occupant = UUID(0, 5) }
        }
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send() }
        assertEquals(listOf("rename", "send", "replace"), f.effects)
    }

    @Test
    fun restoreWaitsForWholeRadioWriteAndPersistInterval() = runTest {
        val f = preparedFixture()
        val radioAccepted = CompletableDeferred<Unit>()
        val persist = CompletableDeferred<Unit>()
        val mutation = launch {
            f.fence.withMutation(f.lease) {
                f.effects += "SET accepted"
                radioAccepted.complete(Unit)
                persist.await()
                f.gate.withChannelIdentityMutation { f.effects += "save" }
            }
        }
        radioAccepted.await()
        val restore = launch {
            f.gate.withChannelIdentityRestore {
                f.occupant = null
                f.effects += "restore"
            }
        }
        runCurrent()
        assertFalse(restore.isCompleted)
        persist.complete(Unit)
        mutation.join()
        restore.join()
        assertEquals(listOf("SET accepted", "save", "restore"), f.effects)
        assertFailsWith<ChannelDispatchFailure.UnknownOccupant> { f.send() }
    }

    @Test
    fun restoreBeforeDispatchRejectsImportedUnknownIntent() = runTest {
        val f = preparedFixture()
        f.gate.withChannelIdentityRestore { f.occupant = UUID(0, 10) }
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send() }
        assertFailsWith<ChannelDispatchFailure.UnknownOccupant> {
            f.fence.dispatch(f.lease, 7u, null, { _, _ -> f.occupant }) { f.effects += "send" }
        }
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun freshRegistryStillRequiresSuppliedPersistedEpoch() = runTest {
        val before = preparedFixture()
        val after = preparedFixture()
        assertEquals(before.intended, after.intended)
        after.send()
        after.occupant = UUID(0, 30)
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { after.send() }
        assertEquals(listOf("send"), after.effects)
    }

    @Test
    fun unknownLegacyIntentNeverSamplesNewOccupantAsItsOwnIdentity() = runTest {
        val f = preparedFixture()
        var reads = 0
        assertFailsWith<ChannelDispatchFailure.UnknownOccupant> {
            f.fence.dispatch(f.lease, 7u, null, { _, _ -> reads++; f.occupant }) { f.effects += "send" }
        }
        assertEquals(0, reads)
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun wrongFenceConnectionTokenOrProcessCannotBorrowLease() = runTest {
        val f = preparedFixture()
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.open(f.token(1), Any()) }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.open(f.token(2), Any()) }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            ChannelDispatchFence(ChannelIdentityMutex()).withMutation(f.lease) { error("must not enter") }
        }
        f.fence.retire(f.lease)
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.open(f.token(1), f.connection) }
        assertFailsWith<ChannelDispatchFailure.RetiredSession> {
            f.open(f.token(2).copy(epoch = ProcessEpoch(UUID(0, 99))), Any())
        }
    }

    @Test
    fun radioQuarantineDoesNotBecomeAnotherRadiosIdentity() = runTest {
        val f = preparedFixture()
        f.fence.quarantine(f.lease, MeshCoreException.Timeout())
        val otherRadio = RadioId(UUID(0, 99))
        val other = f.open(f.token(1, otherRadio), Any())
        f.gate.withChannelOperationScope { scope -> f.fence.recordReconciled(other, scope, listOf(7u)) }
        f.fence.dispatch(other, 7u, UUID(0, 50), { radio, slot ->
            assertEquals(otherRadio, radio)
            assertEquals(7.toUByte(), slot)
            UUID(0, 50)
        }) { f.effects += "other radio" }
        assertEquals(listOf("other radio"), f.effects)
    }

    @Test
    fun storeOwnedScopeValidatesWithoutRecursivelyAcquiringOperationLane() = runTest {
        val f = preparedFixture()
        f.gate.withChannelOperationScope { scope ->
            f.fence.withScopedMutation(f.lease, scope) {
                scope.withChannelIdentityMutation { f.effects += "persist" }
            }
        }
        f.gate.withChannelIdentityRestore { f.effects += "callback after release" }
        assertEquals(listOf("persist", "callback after release"), f.effects)
    }

    @Test
    fun mutationScopeRejectsForgeryWrongGateChildAndExpiredUse() = runTest {
        val f = preparedFixture()
        val forged = object : ChannelIdentityWriteScope {
            override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T = block()
        }
        assertFailsWith<IllegalStateException> {
            f.fence.withScopedMutation(f.lease, forged) { error("must not enter") }
        }
        ChannelIdentityMutex().withChannelOperationScope { wrongGate ->
            assertFailsWith<IllegalStateException> {
                f.fence.withScopedMutation(f.lease, wrongGate) { error("must not enter") }
            }
        }
        lateinit var leaked: ChannelIdentityWriteScope
        f.gate.withChannelOperationScope { scope ->
            leaked = scope
            async {
                assertFailsWith<IllegalStateException> {
                    f.fence.withScopedMutation(f.lease, scope) { error("must not enter") }
                }
            }.await()
        }
        assertFailsWith<IllegalStateException> {
            f.fence.withScopedMutation(f.lease, leaked) { error("must not enter") }
        }
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun failedScopedMutationPreservesFailureAndScopeExpiresBeforePublication() = runTest {
        val f = preparedFixture()
        val failure = IllegalArgumentException("write failed")
        lateinit var leaked: ChannelIdentityWriteScope
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            f.gate.withChannelOperationScope { scope ->
                leaked = scope
                f.fence.withScopedMutation(f.lease, scope) { throw failure }
            }
        })
        assertFailsWith<IllegalStateException> {
            f.fence.withScopedMutation(f.lease, leaked) { error("must not enter") }
        }
        f.gate.withChannelIdentityRestore { f.effects += "outside" }
        assertEquals(listOf("outside"), f.effects)
    }
}
