// AndroidOnly: WP-208 Capture new channel intent origin before persistence without adopting or sending old rows.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.CommandCode
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.TestFactory

class ChannelPendingCreationTest {
    @TestFactory fun capturedOrigin() = listOf(
        native("new pending channel creation persists and returns its captured occupant without enqueue or wire work") {
            val h = Harness(this)
            val snapshot = h.store.seedChannelCreation()
            val receipt = PendingMessageCreation()
            val message = withContext(receipt) {
                h.service.createPendingChannelMessage("original", snapshot.channel.index, RADIO)
            }
            assertEquals(snapshot.occupantID, message.channelOccupantID)
            assertEquals(RADIO, message.radioId)
            assertEquals(snapshot.channel.index, message.channelIndex)
            assertEquals(MessageStatus.PENDING, message.status)
            assertEquals(message, h.stored(message.id))
            assertEquals(PendingMessageCreation.Snapshot(PendingMessageCreation.State.COMMITTED, message), receipt.snapshot)
            assertEquals(1, h.store.calls.count { it == "createOrReconcileChannelMessage" })
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(h.sends(CommandCode.GET_CHANNEL).isEmpty())
            h.close()
        },
        native("an unresolved slot rejects new pending channel creation without inventing authority") {
            val h = Harness(this)
            val receipt = PendingMessageCreation()
            val error = assertFailsWith<MessageServiceException> {
                withContext(receipt) { h.service.createPendingChannelMessage("original", 0u, RADIO) }
            }
            assertEquals(MessageServiceError.ChannelNotFound, error.error)
            assertEquals(PendingMessageCreation.State.NOT_STARTED, receipt.snapshot.state)
            assertTrue(h.store.messages.isEmpty())
            assertTrue(h.store.pending.isEmpty())
            assertFalse("createOrReconcileChannelMessage" in h.store.calls)
            h.close()
        },
        native("origin-aware creation returns the actual persisted metadata and records that exact row") {
            val h = Harness(this)
            val snapshot = h.store.seedChannelCreation()
            val store = object : PersistenceStoreProtocol by h.store, ChannelHistoryPersisting by h.store {
                override suspend fun createOrReconcileChannelMessage(
                    creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant,
                ): MessageDTO {
                    val saved = h.store.capturedChannelMessage(creation, timestamp + 1u, createdAt)
                    h.store.saveMessage(saved)
                    return saved
                }
            }
            val service = MessageService(h.token, SELF, h.session, store, h.signals, h.scope, clock = h.clock)
            try {
                val receipt = PendingMessageCreation()
                val message = withContext(receipt) { service.createPendingChannelMessage("original", 0u, RADIO) }
                assertEquals(snapshot.occupantID, message.channelOccupantID)
                assertEquals(h.store.messages.values.single(), message)
                assertEquals(BASE_TIME.epochSecond.toUInt() + 1u, message.timestamp)
                assertEquals(message, receipt.snapshot.message)
                assertEquals(PendingMessageCreation.State.COMMITTED, receipt.snapshot.state)
            } finally { service.close(); h.close() }
        },
        native("snapshot storage failure is not converted into an absent channel or an unbound draft") {
            val h = Harness(this)
            h.store.seedChannelCreation()
            val failure = storageFailure()
            h.store.faults["fetchChannelOccupantSnapshot"] = failure
            assertSame(failure, assertFailsWith<PersistenceStoreException> {
                h.service.createPendingChannelMessage("original", 0u, RADIO)
            })
            assertTrue(h.store.messages.isEmpty())
            assertFalse("createOrReconcileChannelMessage" in h.store.calls)
            h.close()
        },
        native("occupant replacement before the creation transaction rejects the captured intent instead of rebinding") {
            val h = Harness(this)
            val original = h.store.seedChannelCreation()
            val replacement = UUID.randomUUID()
            h.store.before = { operation ->
                if (operation == "createOrReconcileChannelMessage") h.store.channelOccupants[RADIO to 0u] = replacement
            }
            val receipt = PendingMessageCreation()
            val failure = assertFailsWith<PersistenceStoreException> {
                withContext(receipt) { h.service.createPendingChannelMessage("original", 0u, RADIO) }
            }
            assertEquals(ChannelHistoryConflict.STALE_OCCUPANT, assertIs<ChannelHistoryRejected>(failure.cause).reason)
            assertEquals(PendingMessageCreation.State.UNKNOWN, receipt.snapshot.state)
            assertEquals(original.occupantID, assertNotNull(receipt.snapshot.message).channelOccupantID)
            assertEquals(replacement, h.store.channelOccupants[RADIO to 0u])
            assertTrue(h.store.messages.isEmpty())
            assertTrue(h.store.pending.isEmpty())
            assertTrue(h.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            h.store.before = null
            h.close()
        },
    )
}
