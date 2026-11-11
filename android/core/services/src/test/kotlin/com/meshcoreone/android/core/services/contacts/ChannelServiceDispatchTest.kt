// AndroidOnly: WP-209 Production channel service read/save and mutation ordering with the dispatch fence.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelDispatchAdmission
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
class ChannelServiceDispatchTest {
    private class Fixture {
        val gate = ChannelIdentityMutex()
        val fence = ChannelDispatchFence(gate)
        val store = ChannelsInMemoryStore(gate)
        val radio = RadioId(UUID(0, 1))
        val epoch = ProcessEpoch(UUID(0, 2))
        val session = ChannelsMockSession()
        val token = SessionToken(epoch, Generation(1), radio)
        val admission = ChannelDispatchAdmission(token, session)
        val lease = fence.openSession(token, session, admission)
        val original = ChannelInfo(0u, "old", channelsSecret(1))
        val effects = mutableListOf<String>()

        init { session.setStubbedChannels(mapOf(0.toUByte() to original)) }

        fun service(
            connection: ChannelSessionOps = session, persistence: ChannelPersisting = store,
        ) = ChannelService(
            connection, persistence, null, ChannelsRecordingClock(),
            dispatchFence = fence, dispatchLease = lease,
        )

        suspend fun reconcile(pipeline: Boolean = false) =
            service().syncChannels(radio, 1u, pipeline)

        suspend fun send(expected: UUID?, currentLease: ChannelDispatchFence.Lease = lease) =
            fence.dispatch(currentLease, 0u, expected, store::fetchChannelOccupantID) { effects += "send" }
    }

    @Test
    fun cachedOccupantAndReadyDoNotReplaceAnActualReadAndSave() = runTest {
        val f = Fixture()
        f.store.saveChannel(f.radio, f.original)
        val old = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send(old) }
        assertEquals(1, f.reconcile().channelsSynced)
        f.send(old)
        assertEquals(listOf("send"), f.effects)
    }

    @Test
    fun serialReadThroughSaveExcludesDispatchAndRestore() = runTest {
        val f = Fixture()
        f.reconcile()
        val occupant = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.session.script(0u, { reading.complete(Unit); release.await(); f.original })
        val sync = async { f.reconcile() }
        reading.await()
        val sending = launch { f.send(occupant) }
        val restoring = launch { f.gate.withChannelIdentityRestore { f.effects += "restore" } }
        runCurrent()
        assertFalse(sending.isCompleted)
        assertFalse(restoring.isCompleted)
        release.complete(Unit)
        sync.await()
        sending.join()
        restoring.join()
        assertEquals(listOf("send", "restore"), f.effects)
    }

    @Test
    fun pipelinedSaveMustReturnBeforeSlotCanDispatch() = runTest {
        val f = Fixture()
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persistence = object : ChannelPersisting by f.store {
            override suspend fun <T> withChannelOperation(
                block: suspend (ChannelPersisting, ChannelIdentityWriteScope) -> T,
            ): T = f.store.withChannelOperation { scoped, scope ->
                val held = object : ChannelPersisting by scoped {
                    override suspend fun batchSaveChannels(
                        radioId: RadioId, configured: SnapshotList<ChannelInfo>,
                        unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
                    ) = scoped.batchSaveChannels(radioId, configured, unconfiguredIndices, pruneBeyond).also {
                        saving.complete(Unit)
                        release.await()
                    }
                }
                block(held, scope)
            }
        }
        val sync = async { f.service(persistence = persistence).syncChannels(f.radio, 1u, true) }
        saving.await()
        val occupant = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        val send = launch { f.send(occupant) }
        runCurrent()
        assertFalse(send.isCompleted)
        release.complete(Unit)
        assertEquals(1, sync.await().channelsSynced)
        send.join()
        assertEquals(listOf("send"), f.effects)
    }

    @Test
    fun failedSaveWithdrawsPriorReconciliationAndRetryReestablishesIt() = runTest {
        val f = Fixture()
        f.reconcile()
        val occupant = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        f.store.batchSaveFailure = IllegalStateException("save failed")
        assertEquals(0, f.reconcile(true).channelsSynced)
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send(occupant) }
        f.store.batchSaveFailure = null
        assertEquals(1, f.service().retryFailedChannels(f.radio, SnapshotList.of(0u)).channelsSynced)
        f.send(occupant)
    }

    @Test
    fun sameSecretRenamePreservesOriginWhileReplacementAndAbaDoNot() = runTest {
        val f = Fixture()
        f.reconcile()
        val old = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        f.service().setChannelWithSecret(f.radio, 0u, "renamed", f.original.secret)
        assertEquals(old, f.store.fetchChannelOccupantID(f.radio, 0u))
        f.send(old)
        f.service().setChannelWithSecret(f.radio, 0u, "other", channelsSecret(2))
        assertNotEquals(old, f.store.fetchChannelOccupantID(f.radio, 0u))
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send(old) }
        f.service().setChannelWithSecret(f.radio, 0u, "old", f.original.secret)
        assertNotEquals(old, f.store.fetchChannelOccupantID(f.radio, 0u))
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send(old) }
    }

    @Test
    fun heldDispatchPrecedesReplacementAcrossServiceInstances() = runTest {
        val f = Fixture()
        f.reconcile()
        val old = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val send = launch {
            f.fence.dispatch(f.lease, 0u, old, f.store::fetchChannelOccupantID) {
                entered.complete(Unit)
                release.await()
                f.effects += "send"
            }
        }
        entered.await()
        val replacing = launch {
            f.service().setChannelWithSecret(f.radio, 0u, "replacement", channelsSecret(2))
            f.effects += "replace"
        }
        runCurrent()
        assertTrue(f.session.recordedSetChannels.isEmpty())
        release.complete(Unit)
        send.join()
        replacing.join()
        assertEquals(listOf("send", "replace"), f.effects)
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send(old) }
    }

    @Test
    fun heldReplacementPreventsOldIntentFromDispatchingAfterItsAck() = runTest {
        val f = Fixture()
        f.reconcile()
        val old = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.session.beforeSetChannel = { entered.complete(Unit); release.await() }
        val replacing = launch { f.service().setChannelWithSecret(f.radio, 0u, "new", channelsSecret(2)) }
        entered.await()
        assertNull(f.store.fetchChannelOccupantID(f.radio, 0u), "Unresolved before the radio write can finish")
        val sending = async { assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send(old) } }
        runCurrent()
        assertFalse(sending.isCompleted)
        assertTrue(f.effects.isEmpty())
        release.complete(Unit)
        replacing.join()
        sending.await()
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun clearWithdrawsProofAndRecreationDoesNotAdoptTheOldOccupant() = runTest {
        val f = Fixture()
        f.reconcile()
        val old = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        f.service().clearChannel(f.radio, 0u)
        assertNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send(old) }
        f.service().setChannelWithSecret(f.radio, 0u, "old", f.original.secret)
        assertNotEquals(old, f.store.fetchChannelOccupantID(f.radio, 0u))
        assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { f.send(old) }
    }

    @Test
    fun uncertainMutationRetainsOriginalCancellationAndQuarantinesReconnect() = runTest {
        val f = Fixture()
        f.reconcile()
        val cancelled = CancellationException("radio write cancelled")
        f.session.stubbedSetChannelError = cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> {
            f.service().setChannelWithSecret(f.radio, 0u, "new", channelsSecret(2))
        })
        assertNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        f.fence.retire(f.lease)
        val next = f.token.copy(generation = Generation(2))
        val connection = Any()
        val failure = assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> {
            f.fence.openSession(next, connection, ChannelDispatchAdmission(next, connection))
        }
        assertSame(cancelled, failure.cause)
    }

    @Test
    fun revokedReadCannotAdmitOldOrFreshGenerationAndFreshConnectionMustReadAgain() = runTest {
        val f = Fixture()
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.session.script(0u, { reading.complete(Unit); release.await(); f.original })
        val sync = async { f.reconcile() }
        reading.await()
        f.admission.revoke()
        release.complete(Unit)
        assertEquals(1, sync.await().channelsSynced, "Retirement retains completed partial-read accounting")
        val old = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.send(old) }
        val next = f.token.copy(generation = Generation(2))
        val nextSession = ChannelsMockSession().also { it.setStubbedChannels(mapOf(0.toUByte() to f.original)) }
        val lease = f.fence.openSession(next, nextSession, ChannelDispatchAdmission(next, nextSession))
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send(old, lease) }
        ChannelService(
            nextSession, f.store, null, dispatchFence = f.fence, dispatchLease = lease,
        ).syncChannels(f.radio, 1u, false)
        f.send(old, lease)
    }

    @Test
    fun acknowledgedAndSavedMutationRetiringBeforeReconciliationDoesNotQuarantineSuccessor() = runTest {
        val f = Fixture()
        f.reconcile()
        f.session.beforeSetChannel = { f.admission.revoke() }
        f.service().setChannelWithSecret(f.radio, 0u, "new", channelsSecret(2))
        val next = f.token.copy(generation = Generation(2))
        val nextSession = ChannelsMockSession()
        val lease = f.fence.openSession(next, nextSession, ChannelDispatchAdmission(next, nextSession))
        val occupant = assertNotNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        assertFailsWith<ChannelDispatchFailure.RetiredSession> { f.send(occupant) }
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> { f.send(occupant, lease) }
    }

    @Test
    fun definiteDeviceRejectionWithdrawsReconciliationWithoutQuarantiningReconnect() = runTest {
        val f = Fixture()
        f.reconcile()
        val rejected = MeshCoreException.DeviceError(2u)
        f.session.stubbedSetChannelError = rejected
        val failure = assertFailsWith<ChannelServiceError.SessionError> {
            f.service().setChannelWithSecret(f.radio, 0u, "new", channelsSecret(2))
        }
        assertSame(rejected, failure.cause)
        assertNull(f.store.fetchChannelOccupantID(f.radio, 0u))
        f.fence.retire(f.lease)
        val next = f.token.copy(generation = Generation(2))
        val nextSession = ChannelsMockSession()
        val lease = f.fence.openSession(next, nextSession, ChannelDispatchAdmission(next, nextSession))
        assertFailsWith<ChannelDispatchFailure.UnreconciledSession> {
            f.send(UUID(0, 100), lease)
        }
    }

    @Test
    fun callbacksRunOutsideTheOperationAndCanReenterPersistence() = runTest {
        val f = Fixture()
        f.reconcile()
        val service = f.service()
        service.setSlotOccupantChangedHandler { _, _ ->
            f.store.withChannelOperation { _, _ -> f.effects += "callback" }
        }
        service.setChannelWithSecret(f.radio, 0u, "new", channelsSecret(2))
        assertEquals(listOf("callback"), f.effects)
    }
}
