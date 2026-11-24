// PortedFrom: MC1/Views/Chats/ChatConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/BlockedSenderMessageDeletionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.feature.chats.actions.BlockedSenderHistoryDeletionException
import com.meshcoreone.android.feature.chats.actions.BlockedContactCLIRevocationException
import com.meshcoreone.android.feature.chats.actions.MessageActionsFailure
import com.meshcoreone.android.feature.chats.actions.MessageActionsStateHolder
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineEvent
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedSenderStoreFlowTest : RoomProcessTest() {
    private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val other = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val at = Instant.ofEpochSecond(1_700_000_000)

    private fun channel(sender: String?, index: UByte = 0u) = MessageDTO(
        radioId = radio, channelIndex = index, text = "message from $sender", timestamp = 1_700_000_000u,
        createdAt = at, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        senderNodeName = sender,
    )

    private fun contact(seed: Int) = ContactDTO(
        UUID.randomUUID(), radio, Bytes(ByteArray(32) { seed.toByte() }), "Selected $seed", typeRawValue = 1u,
        lastHeardTimestamp = 0u,
    )

    // SourceCase: BlockedSenderMessageDeletionTests::Deletes all channel messages from a named sender()
    // SourceCase: BlockedSenderMessageDeletionTests::Does not delete DMs from the same sender()
    // SourceCase: BlockedSenderMessageDeletionTests::Scopes deletion to the specified device()
    // SourceCase: BlockedSenderMessageDeletionTests::Preserves messages with nil senderNodeName()
    // SourceCase: BlockedSenderMessageDeletionTests::Deletes reactions associated with deleted channel messages()
    // SourceCase: PersistenceStoreTests::deleteChannelMessages(fromSender:) cascades PendingSends and spares other senders()
    @Test fun confirmedActionDeletesNamedHistoryThroughTheActualAppAdapterAndRoomStore() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val spam = channel("Spammer")
            val secondSlot = channel("Spammer", 1u)
            val legitimate = channel("Legit")
            val unnamed = channel(null)
            val dm = spam.copy(id = UUID.randomUUID(), channelIndex = null, contactID = UUID.randomUUID())
            val foreign = spam.copy(radioId = other)
            for (row in listOf(spam, secondSlot, legitimate, unnamed, dm, foreign)) store.saveMessage(row)
            for (row in listOf(spam, secondSlot, legitimate, foreign)) {
                store.saveReaction(ReactionDTO(messageID = row.id, emoji = "\uD83D\uDC4D", senderName = "Reactor",
                    messageHash = "AABB1122", rawText = ":thumbsup:", receivedAt = at,
                    channelIndex = row.channelIndex, radioId = row.radioId))
                store.upsertPendingSend(PendingSendDTO.fromEnvelope(
                    ChannelMessageEnvelope(row.id, requireNotNull(row.channelIndex), false,
                        row.text, row.timestamp, row.senderNodeName), row.radioId, enqueuedAt = at,
                ))
            }
            val holder = MessageActionsStateHolder(h.container.chatsFeature.actions(radio), backgroundScope)
            holder.blockSender(spam, emptySet())
            runCurrent()
            assertEquals("Spammer", holder.state.value.blockedSender)
            assertNull(holder.state.value.failure)
            assertEquals(listOf("Spammer"), store.fetchBlockedChannelSenders(radio).map { it.name })
            assertTrue(store.fetchBlockedChannelSenders(other).isEmpty())
            for (row in listOf(spam, secondSlot)) {
                val key = EntityKey(row.radioId, row.id)
                assertNull(store.fetchMessage(key))
                assertTrue(store.fetchReactions(key).isEmpty())
                assertFalse(store.hasPendingSend(key))
            }
            for (row in listOf(legitimate, unnamed, dm, foreign)) {
                assertEquals(row, store.fetchMessage(EntityKey(row.radioId, row.id)))
            }
            for (row in listOf(legitimate, foreign)) {
                val key = EntityKey(row.radioId, row.id)
                assertEquals(1, store.fetchReactions(key).size)
                assertTrue(store.hasPendingSend(key))
            }
        } finally { h.container.close() }
    }

    // SourceCase: BlockedSenderMessageDeletionTests::No-op when sender has no messages()
    @Test fun blockingAnAbsentSenderPreservesOtherHistoryAndNilSenderDoesNothing() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val kept = channel("Legit")
            store.saveMessage(kept)
            val holder = MessageActionsStateHolder(h.container.chatsFeature.actions(radio), backgroundScope)
            holder.blockSender(channel(null), emptySet())
            runCurrent()
            assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
            assertNull(holder.state.value.blockedSender)
            holder.blockSender(channel("Ghost"), emptySet())
            runCurrent()
            assertEquals("Ghost", holder.state.value.blockedSender)
            assertEquals(kept, store.fetchMessage(EntityKey(radio, kept.id)))
        } finally { h.container.close() }
    }

    @Test fun historyDeletionRegistersImmediatelyAndInvalidatesOnlyTheMatchingRadio() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val conversation = TimelineConversation.Channel(ChannelDTO(radioId = radio, index = 0u, name = "Test"))
            val feature = h.container.chatsFeature
            runCurrent()
            val stream = h.appState.messageEventStream
            val before = stream.subscriberCount
            val next = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                feature.timeline(conversation).events.first()
            }
            assertEquals(before + 1, stream.subscriberCount)
            stream.send(MessageEvent.ChannelHistoryDeleted(other))
            runCurrent()
            assertFalse(next.isCompleted)
            feature.actions(radio).blockChannelSender(radio, "Spammer")
            assertEquals(TimelineEvent.HistoryChanged(conversation.id), next.await())
            assertEquals(before, stream.subscriberCount)
        } finally { h.container.close() }
    }

    @Test fun failedHistoricalDeletionIsVisibleAndDoesNotReportTheActionAsCompleted() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val spam = channel("Spammer")
            store.saveMessage(spam)
            val selected = contact(1)
            val unselected = contact(2)
            val foreign = selected.copy(radioId = other)
            for (row in listOf(selected, unselected, foreign)) store.saveContact(row)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_block_delete AFTER DELETE ON messages " +
                    "BEGIN SELECT RAISE(FAIL, 'controlled block deletion failure'); END",
            )
            try {
                val holder = MessageActionsStateHolder(h.container.chatsFeature.actions(radio), backgroundScope)
                holder.blockSender(spam, setOf(selected.id))
                runCurrent()
                assertEquals(MessageActionsFailure.BlockFailed, holder.state.value.failure)
                assertNull(holder.state.value.blockedSender)
                assertEquals(spam, store.fetchMessage(EntityKey(radio, spam.id)))
                assertEquals(listOf("Spammer"), store.fetchBlockedChannelSenders(radio).map { it.name })
                assertEquals(selected.copy(isBlocked = true), store.fetchContact(EntityKey(radio, selected.id)))
                assertEquals(unselected, store.fetchContact(EntityKey(radio, unselected.id)))
                assertEquals(foreign, store.fetchContact(EntityKey(other, foreign.id)))
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_block_delete")
            }
        } finally { h.container.close() }
    }

    @Test fun failedSenderBlockDoesNotBlockSelectedContactsOrDeleteHistory() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val spam = channel("Spammer")
            val selected = contact(1)
            store.saveMessage(spam)
            store.saveContact(selected)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_sender_block AFTER INSERT ON blocked_channel_senders " +
                    "BEGIN SELECT RAISE(FAIL, 'controlled sender block failure'); END",
            )
            try {
                val holder = MessageActionsStateHolder(h.container.chatsFeature.actions(radio), backgroundScope)
                holder.blockSender(spam, setOf(selected.id))
                runCurrent()
                assertEquals(MessageActionsFailure.BlockFailed, holder.state.value.failure)
                assertNull(holder.state.value.blockedSender)
                assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())
                assertEquals(selected, store.fetchContact(EntityKey(radio, selected.id)))
                assertEquals(spam, store.fetchMessage(EntityKey(radio, spam.id)))
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_sender_block")
            }
        } finally { h.container.close() }
    }

    @Test fun aMissingSelectedContactDoesNotPreventBlockingTheRemainingRealContacts() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val spam = channel("Spammer")
            val selected = contact(1)
            val foreign = selected.copy(radioId = other)
            store.saveMessage(spam)
            store.saveContact(selected)
            store.saveContact(foreign)
            val holder = MessageActionsStateHolder(h.container.chatsFeature.actions(radio), backgroundScope)
            holder.blockSender(spam, linkedSetOf(UUID.randomUUID(), selected.id))
            runCurrent()
            assertEquals(MessageActionsFailure.BlockFailed, holder.state.value.failure)
            assertNull(holder.state.value.blockedSender)
            assertEquals(selected.copy(isBlocked = true), store.fetchContact(EntityKey(radio, selected.id)))
            assertEquals(foreign, store.fetchContact(EntityKey(other, foreign.id)))
            assertNull(store.fetchMessage(EntityKey(radio, spam.id)))
        } finally { h.container.close() }
    }

    @Test fun mixedContactFailureRetainsCLIWarningAndRejectedRevocationCanRetry() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            val contact = contactFrame(key(20), "Spammer")
            radio.knownContacts += contact.slice(0, 34) + Bytes.of(0xb5) + contact.slice(35, contact.size)
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) listOf(Bytes.of(1, 6)) else null
            }
        }
        try {
            h.manager.connect(h.target())
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val currentRadio = assertNotNull(h.appState.currentRadioId)
            val selected = assertNotNull(store.fetchContact(currentRadio, key(20)))
            val actions = h.container.chatsFeature.actions(currentRadio)
            val result = backgroundScope.async {
                assertFailsWith<BlockedContactCLIRevocationException> {
                    actions.blockContacts(linkedSetOf(UUID.randomUUID(), selected.id))
                }
            }
            h.eventually("partial block failure") { result.isCompleted }
            assertTrue(result.await().otherFailures)
            assertTrue(assertNotNull(store.fetchContact(EntityKey(currentRadio, selected.id))).isBlocked)
            val radio = h.links.radios.single()
            val before = radio.sent.count { it[0].toInt() and 0xff == 0x09 }
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    radio.knownContacts[0] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                    listOf(Bytes.of(0))
                } else null
            }
            val retry = backgroundScope.async { actions.blockContacts(setOf(selected.id)) }
            h.eventually("rejected block retry") { retry.isCompleted }
            retry.await()
            assertEquals(before + 1, radio.sent.count { it[0].toInt() and 0xff == 0x09 })
            assertEquals(0xa5u.toUByte(), store.fetchContact(EntityKey(currentRadio, selected.id))?.flags)
            assertFalse(assertNotNull(h.sessions.current).contactService.needsRemoteCLIWriteReconciliation(currentRadio))
        } finally { h.container.close() }
    }

    @Test fun failedRadioReadLeavesRevocationUnsentAndRetryable() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            val contact = contactFrame(key(20), "Spammer")
            radio.knownContacts += contact.slice(0, 34) + Bytes.of(0xb5) + contact.slice(35, contact.size)
        }
        try {
            h.manager.connect(h.target())
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val currentRadio = assertNotNull(h.appState.currentRadioId)
            val selected = assertNotNull(store.fetchContact(currentRadio, key(20)))
            val radio = h.links.radios.single()
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x1e) listOf(Bytes.of(1, 6)) else null
            }
            val actions = h.container.chatsFeature.actions(currentRadio)
            val failed = backgroundScope.async {
                assertFailsWith<BlockedContactCLIRevocationException> { actions.blockContacts(setOf(selected.id)) }
            }
            h.eventually("contact read rejection") { failed.isCompleted }
            failed.await()
            assertTrue(assertNotNull(store.fetchContact(EntityKey(currentRadio, selected.id))).isBlocked)
            assertTrue(assertNotNull(h.sessions.current).contactService.needsRemoteCLIWriteReconciliation(currentRadio))
            val before = radio.sent.count { it[0].toInt() and 0xff == 0x09 }
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    radio.knownContacts[0] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                    listOf(Bytes.of(0))
                } else null
            }
            val retry = backgroundScope.async { actions.blockContacts(setOf(selected.id)) }
            h.eventually("unsent block retry") { retry.isCompleted }
            retry.await()
            assertEquals(before + 1, radio.sent.count { it[0].toInt() and 0xff == 0x09 })
            assertEquals(0xa5u.toUByte(), store.fetchContact(EntityKey(currentRadio, selected.id))?.flags)
            assertFalse(assertNotNull(h.sessions.current).contactService.needsRemoteCLIWriteReconciliation(currentRadio))
        } finally { h.container.close() }
    }

    @Test fun disconnectedContactBlockStaysLocalUntilTheOwningRadioReconnects() = runTest {
        val flags = MemoryFlags()
        val h = ContainerHarness(this, store, contactPreferences = flags)
        val target = h.target()
        h.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            val contact = contactFrame(key(20), "Spammer")
            radio.knownContacts += contact.slice(0, 34) + Bytes.of(0xb5) + contact.slice(35, contact.size)
            val otherContact = contactFrame(key(21), "Spammer")
            radio.knownContacts += otherContact.slice(0, 34) + Bytes.of(0xa5) + otherContact.slice(35, otherContact.size)
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    radio.knownContacts[0] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                    listOf(Bytes.of(0))
                } else null
            }
        }
        try {
            h.manager.connect(target)
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val currentRadio = assertNotNull(h.appState.currentRadioId)
            val selected = assertNotNull(store.fetchContact(currentRadio, key(20)))
            val unaffected = assertNotNull(store.fetchContact(currentRadio, key(21)))
            val oldRadio = h.links.radios.single()
            h.manager.disconnect()
            h.eventually("disconnected") { h.manager.connectionState == DeviceConnectionState.DISCONNECTED }
            val holder = MessageActionsStateHolder(h.container.chatsFeature.actions(currentRadio), backgroundScope)
            holder.blockSender(channel("Spammer").copy(radioId = currentRadio), setOf(selected.id, unaffected.id))
            h.eventually("local block warning") { holder.state.value.remoteCLIBlockUncertain }
            assertEquals(setOf(selected.id), holder.state.value.remoteCLIBlockFailures)
            assertNull(holder.state.value.blockedSender)
            assertTrue(assertNotNull(store.fetchContact(EntityKey(currentRadio, selected.id))).isBlocked)
            assertTrue(assertNotNull(store.fetchContact(EntityKey(currentRadio, unaffected.id))).isBlocked)
            assertEquals(0xb5u.toUByte(), store.fetchContact(EntityKey(currentRadio, selected.id))?.flags)
            assertTrue(flags.bool("remoteCLIRevocationFailed.${currentRadio.value}.${selected.publicKey.hexString}"))
            assertFalse(flags.bool("remoteCLIWriteReconciliation.${currentRadio.value}.${selected.publicKey.hexString}"))
            assertFalse(flags.bool("remoteCLIRevocationFailed.${currentRadio.value}.${unaffected.publicKey.hexString}"))
            assertTrue(oldRadio.sent.none { it[0].toInt() and 0xff == 0x09 })
            h.manager.connect(target)
            h.eventually("reconnected and reconciled") { h.manager.connectionState == DeviceConnectionState.READY }
            h.eventually("warning cleared") { !holder.state.value.remoteCLIBlockUncertain }
            val reconnected = h.links.radios.last()
            assertTrue(reconnected.sent.any { it[0].toInt() and 0xff == 0x09 })
            assertEquals(0xa5u.toUByte(), store.fetchContact(EntityKey(currentRadio, selected.id))?.flags)
            assertFalse(flags.bool("remoteCLIRevocationFailed.${currentRadio.value}.${selected.publicKey.hexString}"))
        } finally { h.container.close() }
    }

    @Test fun committedBlockUpdatesTheLiveIngestionFilterEvenWhenHistoricalDeletionFails() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.eventually("ready blocked-sender graph") { h.manager.connectionState == DeviceConnectionState.READY }
            val currentRadio = assertNotNull(h.appState.currentRadioId)
            val coordinator = assertNotNull(h.sessions.current).syncCoordinator
            val spam = channel("Spammer").copy(radioId = currentRadio)
            store.saveMessage(spam)
            assertFalse(coordinator.isBlockedSender("Spammer"))
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_live_block_delete AFTER DELETE ON messages " +
                    "BEGIN SELECT RAISE(FAIL, 'controlled live block deletion failure'); END",
            )
            try {
                val failure = assertFailsWith<BlockedSenderHistoryDeletionException> {
                    h.container.chatsFeature.actions(currentRadio).blockChannelSender(currentRadio, "Spammer")
                }
                assertIs<PersistenceStoreException>(failure.cause)
                assertEquals(listOf("Spammer"), store.fetchBlockedChannelSenders(currentRadio).map { it.name })
                assertTrue(coordinator.isBlockedSender("Spammer"))
                assertFalse(coordinator.isBlockedSender("Legit"))
                assertEquals(spam, store.fetchMessage(EntityKey(currentRadio, spam.id)))
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_live_block_delete")
            }
        } finally { h.container.close() }
    }
}
