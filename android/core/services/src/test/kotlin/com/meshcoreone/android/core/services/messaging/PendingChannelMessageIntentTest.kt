// AndroidOnly: WP-208 Real-service channel creation identity, cancellation and session-ownership controls.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class PendingChannelMessageIntentTest {
    private val origin = ChannelHistoryScope(RADIO, 0u, UUID.randomUUID())

    @TestFactory fun creationBoundaries() = listOf(
        native("channelIntentReturnsCanonicalPersistedRowAndCarriesOriginalScopeIntoOwnedChild") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                role.canonical = { it.copy(timestamp = it.timestamp + 1u, status = MessageStatus.SENT) }
                val result = withContext(intent + receipt) {
                    service.createPendingChannelMessage("original", 0u, RADIO)
                }
                assertEquals(intent.creation, role.requests.single().creation)
                assertEquals(intent.timestamp, role.requests.single().timestamp)
                assertEquals(intent.createdAt, role.requests.single().createdAt)
                assertEquals(intent.timestamp + 1u, result.timestamp)
                assertEquals(MessageStatus.SENT, result.status)
                assertEquals(PendingMessageCreation.Snapshot(PendingMessageCreation.State.COMMITTED, result), receipt.snapshot)
                assertEquals(result, h.stored(result.id))
                assertEquals(listOf(h.token), role.activations)
                assertTrue(h.sends(com.meshcoreone.android.core.protocol.model.CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            }
            assertTrue(role.revoked)
        },
        native("channelIntentExplicitReconciliationReusesAllOriginalArgumentsAndReturnsCurrentCanonicalRow") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val first = withContext(intent + PendingMessageCreation()) {
                    service.createPendingChannelMessage("original", 0u, RADIO)
                }
                role.reconciled = true
                role.canonical = { it.copy(status = MessageStatus.SENT, sendCount = 3) }
                val receipt = PendingMessageCreation()
                val current = withContext(intent + receipt) {
                    service.createPendingChannelMessage("original", 0u, RADIO)
                }
                assertEquals(first.id, current.id)
                assertEquals(role.requests[0], role.requests[1])
                assertEquals(1, h.store.messages.size)
                assertEquals(3L, current.sendCount)
                assertEquals(current, receipt.snapshot.message)
                assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
            }
        },
        native("channelIntentMismatchNeverInvokesProducerOrGenericSave") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                assertFailsWith<PersistenceStoreException> {
                    withContext(intent + receipt) { service.createPendingChannelMessage("newer typing", 0u, RADIO) }
                }
                assertTrue(role.requests.isEmpty())
                assertTrue(role.activations.isEmpty())
                assertTrue(h.store.messages.isEmpty())
                assertEquals(PendingMessageCreation.State.NOT_STARTED, receipt.snapshot.state)
            }
        },
        native("channelIntentDefaultCaptureDoesNotResampleOccupantAfterSuspendingValidation") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            val channelA = ChannelDTO(origin.expectedOccupantID, RADIO, 0u, "A")
            val channelB = channelA.copy(id = UUID.randomUUID(), name = "B")
            h.store.channels[EntityKey(RADIO, channelA.id)] = channelA
            h.store.before = { operation ->
                if (operation == "fetchDevice") {
                    h.store.channels.clear()
                    h.store.channels[EntityKey(RADIO, channelB.id)] = channelB
                }
            }
            role.before = {
                assertEquals(channelB.id, h.store.channels.values.single().id)
                assertEquals(origin, role.requests.single().creation.origin)
                role.rejection = DirectAckRejection.CONFLICT
            }
            usingProducer(h, role) { service ->
                assertFailsWith<PersistenceStoreException> {
                    service.createPendingChannelMessage("original", 0u, RADIO)
                }
                assertEquals(origin, role.requests.single().creation.origin)
                assertTrue(h.store.messages.isEmpty())
            }
        },
        native("channelIntentReturnedReplacementOriginNeverBecomesCommittedReceipt") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            role.returnedOrigin = origin.copy(expectedOccupantID = UUID.randomUUID())
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                assertFailsWith<PersistenceStoreException> {
                    withContext(intent + receipt) { service.createPendingChannelMessage("original", 0u, RADIO) }
                }
                assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                assertEquals(intent.creation.messageID, receipt.snapshot.message?.id)
                assertEquals(origin, intent.creation.origin)
            }
        },
        native("explicitChannelIntentWithoutCapabilityDoesNotFallBackToGenericSave") {
            val h = Harness(this); h.start()
            usingProducer(h, null) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                assertFailsWith<PersistenceStoreException> {
                    withContext(intent) { service.createPendingChannelMessage("original", 0u, RADIO) }
                }
                assertTrue(h.store.messages.isEmpty())
                assertFalse("saveMessage" in h.store.calls)
            }
        },
        native("channelIntentCancellationBeforeCommitIsUnknownAndNeverReplaysTheInsert") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            val entered = CompletableDeferred<Unit>()
            role.before = { entered.complete(Unit); awaitCancellation() }
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                val call = backgroundScope.async(intent + receipt) {
                    service.createPendingChannelMessage("original", 0u, RADIO)
                }
                entered.await()
                call.cancelAndJoin()
                assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                assertEquals(intent.creation.messageID, receipt.snapshot.message?.id)
                assertEquals(1, role.requests.size)
                assertTrue(h.store.messages.isEmpty())
            }
        },
        native("channelIntentCommitBeforeCancelledReturnRetainsOriginalIdentityWithoutInventingCommitProof") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            val committed = CompletableDeferred<Unit>()
            role.after = { committed.complete(Unit); awaitCancellation() }
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                val call = backgroundScope.async(intent + receipt) {
                    service.createPendingChannelMessage("original", 0u, RADIO)
                }
                committed.await()
                call.cancelAndJoin()
                assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                assertEquals(intent.creation.messageID, receipt.snapshot.message?.id)
                assertEquals(origin, intent.creation.origin)
                assertEquals(1, role.requests.size)
                assertEquals(1, h.store.messages.size)
                assertFalse("fetchMessage" in h.store.calls, "DTO equality is not original creation proof")
            }
        },
        native("channelIntentKnownCommitIsPublishedBeforeReplacementSessionOwnershipCheck") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            role.after = { h.signals.set(token(generation = 2), DeviceConnectionState.READY) }
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException> {
                    withContext(intent + receipt) { service.createPendingChannelMessage("original", 0u, RADIO) }
                }
                assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
                assertEquals(h.store.messages.values.single(), receipt.snapshot.message)
                assertEquals(origin, role.requests.single().creation.origin)
            }
        },
        native("channelIntentRejectionIsExplicitAndDoesNotSaveOrCertifyRollback") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            role.rejection = DirectAckRejection.CONFLICT
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                assertFailsWith<PersistenceStoreException> {
                    withContext(intent + receipt) { service.createPendingChannelMessage("original", 0u, RADIO) }
                }
                assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                assertTrue(h.store.messages.isEmpty())
                assertEquals(1, role.requests.size)
            }
        },
        native("channelIntentTeardownJoinsOriginalCreateBeforeRevokingItsLease") {
            val h = Harness(this); h.start()
            val role = CreationRole(h.store)
            val entered = CompletableDeferred<Unit>()
            var createFinished = false
            role.before = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { createFinished = true }
            }
            role.onRevoke = { assertTrue(createFinished) }
            usingProducer(h, role) { service ->
                val intent = service.preparePendingChannelMessage("original", origin)
                val receipt = PendingMessageCreation()
                val call = backgroundScope.async(intent + receipt) {
                    service.createPendingChannelMessage("original", 0u, RADIO)
                }
                entered.await()
                service.close()
                assertFailsWith<CancellationException> { call.await() }
                assertTrue(role.revoked)
                assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
                assertTrue(h.store.messages.isEmpty())
            }
        },
    )

    private suspend fun usingProducer(
        h: Harness,
        role: CreationRole?,
        block: suspend (MessageService) -> Unit,
    ) {
        val service = MessageService(h.token, SELF, h.session, h.store, h.signals, h.scope,
            clock = h.clock, reporter = MessagingIssueReporter { h.diagnostics += it }, channelFailureAuthority = role)
        try { block(service) } finally { service.close() }
    }

    private data class Request(val creation: ChannelMessageCreation, val timestamp: UInt, val createdAt: Instant)

    private class CreationRole(private val store: RecordingStore) : ChannelFailureAuthority by rejectingRole() {
        private val lease = object : DirectAckLease {}
        val activations = mutableListOf<SessionToken>()
        val requests = mutableListOf<Request>()
        var revoked = false
        var reconciled = false
        var rejection: DirectAckRejection? = null
        var returnedOrigin: ChannelHistoryScope? = null
        var canonical: (MessageDTO) -> MessageDTO = { it }
        var before: suspend () -> Unit = {}
        var after: suspend () -> Unit = {}
        var onRevoke: () -> Unit = {}

        override suspend fun activateChannelFailureSession(token: SessionToken, requestId: UUID): DirectAckActivationResult {
            activations += token
            return DirectAckActivationResult.Activated(lease)
        }

        override suspend fun createOrReconcileChannelMessage(
            lease: DirectAckLease,
            creation: ChannelMessageCreation,
            timestamp: UInt,
            createdAt: Instant,
        ): ChannelMessageCreationResult {
            assertSame(this.lease, lease)
            requests += Request(creation, timestamp, createdAt)
            before()
            rejection?.let { return ChannelMessageCreationResult.Rejected(it) }
            val message = canonical(MessageDTO(
                creation.messageID, creation.origin.radioId, channelIndex = creation.origin.channelIndex,
                text = creation.text, timestamp = timestamp, createdAt = createdAt, textType = creation.textType,
            ))
            store.messages[EntityKey(message.radioId, message.id)] = message
            after()
            val receipt = ChannelMessageCreationReceipt(returnedOrigin ?: creation.origin, message)
            return if (reconciled) ChannelMessageCreationResult.Reconciled(null, receipt)
                else ChannelMessageCreationResult.Created(object : ChannelFailureAttempt {}, receipt)
        }

        override suspend fun revokeChannelFailureSession(lease: DirectAckLease): DirectAckRevocationResult {
            assertSame(this.lease, lease)
            onRevoke()
            revoked = true
            return DirectAckRevocationResult.Revoked
        }
    }

    private companion object {
        fun rejectingRole(): ChannelFailureAuthority = ChannelFailureAuthority::class.java.cast(
            Proxy.newProxyInstance(ChannelFailureAuthority::class.java.classLoader, arrayOf(ChannelFailureAuthority::class.java)) {
                    _, method, _ -> error("Unexpected channel producer operation: ${method.name}")
            },
        )
    }
}
