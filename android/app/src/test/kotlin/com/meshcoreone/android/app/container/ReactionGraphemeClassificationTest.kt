// AndroidOnly: WP-216 Real Room, mounted sync and App reaction-send classification at the GB11 boundary.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.reactions.isHiddenOutgoingReaction
import com.meshcoreone.android.core.services.rendering.HiddenOutgoingReactionPredicate
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionGraphemeClassificationTest : RoomProcessTest() {
    private val doubledJoiner = "\uD83D\uDC4D\u200D\u200D\uD83D\uDC4D"
    private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
    private val peerKey = key(3)
    private val stamp = 1_704_067_200u
    private val targetName = "Node\uD83D\uDD25"

    @Test fun incomingChannelDoubledJoinerPersistsAnOrdinaryMessageInsteadOfAReaction() = runTest {
        withMounted(this) { assertIncoming(it, direct = false, doubledJoiner, accepted = false) }
    }

    @Test fun incomingDirectDoubledJoinerPersistsAnOrdinaryMessageInsteadOfAReaction() = runTest {
        withMounted(this) { assertIncoming(it, direct = true, doubledJoiner, accepted = false) }
    }

    @Test fun incomingFamilyReactionPreservesTheEmojiInItsTargetSenderName() = runTest {
        withMounted(this) { assertIncoming(it, direct = false, family, accepted = true) }
    }

    @Test fun incomingDirectSkinToneStillUpdatesItsStoredTargetWithoutAnOrdinaryMessage() = runTest {
        withMounted(this) { assertIncoming(it, direct = true, "\uD83D\uDC4D\uD83C\uDFFD", accepted = true) }
    }

    @Test fun appChannelSendPreservesDoubledJoinerWireAndKeepsItsOrdinaryMessageVisible() = runTest {
        withMounted(this) { assertOutgoing(it, direct = false, doubledJoiner, accepted = false) }
    }

    @Test fun appDirectSendPreservesDoubledJoinerWireAndKeepsItsOrdinaryMessageVisible() = runTest {
        withMounted(this) { assertOutgoing(it, direct = true, doubledJoiner, accepted = false) }
    }

    @Test fun appChannelFamilySendStillHasReactionClassificationAndVisibility() = runTest {
        withMounted(this) { assertOutgoing(it, direct = false, family, accepted = true) }
    }

    @Test fun appDirectFlagSendStillHasReactionClassificationAndVisibility() = runTest {
        withMounted(this) { assertOutgoing(it, direct = true, "\uD83C\uDDFA\uD83C\uDDF8", accepted = true) }
    }

    @Test fun concurrentChannelReactionTapsShareTheInFlightSend() = runTest {
        withMounted(this) { assertConcurrentOutgoing(it, direct = false) }
    }

    @Test fun concurrentDirectReactionTapsShareTheInFlightSend() = runTest {
        withMounted(this) { assertConcurrentOutgoing(it, direct = true) }
    }

    @Test fun acceptedChannelReactionRetriesLocalBookkeepingWithoutAnotherRadioWrite() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) { fixture ->
            val target = target(fixture, direct = false)
            val conversation = TimelineConversation.Channel(fixture.channel)
            val feature = fixture.harness.container.chatsFeature
            val displayed = assertNotNull(feature.mountedTimeline(conversation).data.fetchMessage(target.id))
            val radio = fixture.harness.links.radios.last()
            val before = radio.sent.count { it[0].toInt() == 0x03 }
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER outgoing_summary_fault BEFORE UPDATE OF reactionSummary ON messages " +
                    "BEGIN SELECT RAISE(ABORT, 'outgoing summary fault'); END",
            )
            try {
                feature.react(displayed, "\uD83D\uDC4D", feature.captureMessageActionConversation(conversation))
                fixture.harness.eventually("accepted reaction retains failed bookkeeping") {
                    runBlocking {
                        radio.sent.count { it[0].toInt() == 0x03 } == before + 1 &&
                            store.fetchReactions(EntityKey(target.radioId, target.id)).size == 1 &&
                            fixture.harness.messagingDiagnostics.isNotEmpty()
                    }
                }
                assertNull(assertNotNull(store.fetchMessage(EntityKey(target.radioId, target.id))).reactionSummary)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER outgoing_summary_fault")
            }
            fixture.live.chatSendQueueService.transportDidOpen()
            fixture.harness.eventually("accepted reaction summary recovers") {
                runBlocking {
                    store.fetchMessage(EntityKey(target.radioId, target.id))?.reactionSummary == "\uD83D\uDC4D:1" &&
                        store.fetchPendingSends(target.radioId).isEmpty()
                }
            }
            assertEquals(before + 1, radio.sent.count { it[0].toInt() == 0x03 })
        }
    }

    @Test fun acceptedDirectReactionRetriesLocalBookkeepingWithoutAnotherRadioWrite() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) { fixture -> assertDirectBookkeepingRecovery(fixture, false) }
    }

    @Test fun acceptedDirectReactionDoesNotAttachToRewrittenTargetDuringRecovery() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) { fixture -> assertDirectBookkeepingRecovery(fixture, true) }
    }

    private suspend fun assertDirectBookkeepingRecovery(fixture: Mounted, rewrite: Boolean) {
        val target = target(fixture, direct = true)
        val conversation = TimelineConversation.Direct(fixture.contact)
        val feature = fixture.harness.container.chatsFeature
        val displayed = assertNotNull(feature.mountedTimeline(conversation).data.fetchMessage(target.id))
        val radio = fixture.harness.links.radios.last()
        val before = radio.sent.count { it[0].toInt() == 0x02 }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER outgoing_direct_summary_fault BEFORE UPDATE OF reactionSummary ON messages " +
                "WHEN OLD.text = 'Hello' " +
                "BEGIN SELECT RAISE(ABORT, 'outgoing direct summary fault'); END",
        )
        try {
            feature.react(displayed, "\uD83D\uDC4D", feature.captureMessageActionConversation(conversation))
            fixture.harness.eventually("accepted direct reaction retains failed bookkeeping") {
                runBlocking {
                    radio.sent.count { it[0].toInt() == 0x02 } == before + 1 &&
                        store.fetchReactions(EntityKey(target.radioId, target.id)).size == 1 &&
                        fixture.harness.messagingDiagnostics.isNotEmpty()
                }
            }
            assertNull(assertNotNull(store.fetchMessage(EntityKey(target.radioId, target.id))).reactionSummary)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER outgoing_direct_summary_fault")
        }
        if (rewrite) store.saveMessage(target.copy(text = "Replacement"))
        fixture.live.chatSendQueueService.transportDidOpen()
        fixture.harness.eventually("accepted direct reaction summary recovers") {
            runBlocking {
                if (rewrite) {
                    store.fetchReactions(EntityKey(target.radioId, target.id)).isEmpty()
                } else store.fetchMessage(EntityKey(target.radioId, target.id))?.reactionSummary == "\uD83D\uDC4D:1"
            }
        }
        assertEquals(if (rewrite) 0 else 1, store.fetchReactions(EntityKey(target.radioId, target.id)).size)
        if (rewrite) {
            assertNull(assertNotNull(store.fetchMessage(EntityKey(target.radioId, target.id))).reactionSummary)
        }
        assertEquals(before + 1, radio.sent.count { it[0].toInt() == 0x02 })
    }

    @Test fun queuedDirectReactionDoesNotSendAfterTargetDeletion() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) { assertTargetChangeBeforeDispatch(it, direct = true, delete = true) }
    }

    @Test fun queuedDirectReactionDoesNotSendAfterTargetReplacement() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) { assertTargetChangeBeforeDispatch(it, direct = true, delete = false) }
    }

    @Test fun directRetirementStorageFailureParksAndRecoversWithoutSendingTheReaction() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) {
            assertTargetChangeBeforeDispatch(it, direct = true, delete = true, failRetirement = true)
        }
    }

    @Test fun queuedChannelReactionDoesNotSendAfterTargetDeletion() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) { assertTargetChangeBeforeDispatch(it, direct = false, delete = true) }
    }

    @Test fun queuedChannelReactionDoesNotSendAfterTargetReplacement() = runTest {
        withMounted(this, allowMessagingDiagnostics = true) { assertTargetChangeBeforeDispatch(it, direct = false, delete = false) }
    }

    @Test fun generatedUnicodePropertiesReuseTheExistingApplicationAssetNotice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notice = context.assets.open("licenses/Unicode-License.txt").use { it.readBytes() }
        assertTrue(notice.toString(Charsets.UTF_8).startsWith("UNICODE LICENSE V3"))
        assertEquals(
            "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96",
            MessageDigest.getInstance("SHA-256").digest(notice)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
    }

    private data class Mounted(
        val harness: ContainerHarness,
        val live: RadioSessionContainer,
        val contact: ContactDTO,
        val channel: ChannelDTO,
    )

    private suspend fun withMounted(
        test: TestScope, allowMessagingDiagnostics: Boolean = false, body: suspend (Mounted) -> Unit,
    ) {
        val clock = object : SyncClock {
            override fun now() = EPOCH.plusSeconds(2)
            override fun elapsed() = test.testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val harness = ContainerHarness(test, store, syncClock = clock)
        val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x1f -> if (command[1] == 0.toUByte()) {
                        listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) + secret)
                    } else null
                    0x02 -> sentAndDelivered(command, key(7))
                    0x03 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        try {
            assertEquals(EPOCH.epochSecond.toUInt(), stamp)
            harness.manager.connect(harness.target())
            harness.eventually("grapheme classification session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            assertTrue(live.messagePollingService.hasMessageHandlersWired)
            val monitor = harness.appState.batteryMonitor
            harness.eventually("real battery bootstrap completed before reaction assertions") {
                monitor.deviceBattery?.level == 3700L && !monitor.isBootstrapActive && monitor.isRefreshLoopActive
            }
            // stop clears its job handles; retain the child so cancellation is joined, not merely requested.
            val jobs = assertNotNull(test.backgroundScope.coroutineContext[Job]).children
                .filter { it.isActive }.toList()
            monitor.stop()
            val cancelled = jobs.filter { it.isCancelled }
            assertEquals(1, cancelled.size, "Stopping the settled monitor cancels its one refresh child")
            cancelled.single().join()
            assertTrue(cancelled.single().isCompleted)
            assertFalse(monitor.isBootstrapActive)
            assertFalse(monitor.isRefreshLoopActive)
            live.messagePollingService.stopAutoFetch()
            assertFalse(live.messagePollingService.isAutoFetching)
            assertTrue(live.messagePollingService.hasMessageHandlersWired)
            live.channelService.syncChannels(live.token.radioId, 1u, usePipelinedRead = false)
            val channel = store.fetchChannels(live.token.radioId).single()
            assertEquals(secret, channel.secret)
            val peer = ContactDTO(radioId = live.token.radioId, publicKey = peerKey, name = "Bob", typeRawValue = 1u, lastHeardTimestamp = null)
            store.saveContact(peer)
            body(Mounted(harness, live, peer, channel))
            assertFalse(monitor.isBootstrapActive)
            assertFalse(monitor.isRefreshLoopActive)
            assertFalse(live.messagePollingService.isAutoFetching)
            assertTrue(live.reportedGaps.isEmpty())
            if (!allowMessagingDiagnostics) {
                assertTrue(harness.messagingDiagnostics.isEmpty(), harness.messagingDiagnostics.toString())
            }
        } finally {
            harness.container.close()
        }
    }

    private suspend fun messages(fixture: Mounted, direct: Boolean): List<MessageDTO> =
        if (direct) store.fetchMessages(EntityKey(fixture.contact.radioId, fixture.contact.id))
        else store.fetchMessages(fixture.channel.radioId, fixture.channel.index)

    private suspend fun target(fixture: Mounted, direct: Boolean): MessageDTO {
        poll(fixture, direct, if (direct) "Hello" else "$targetName: Hello", stamp)
        val target = messages(fixture, direct).single()
        assertEquals("Hello", target.text)
        assertEquals(stamp, target.reactionTimestamp)
        assertFalse(target.timestampCorrected)
        if (!direct) assertEquals(targetName, target.senderNodeName)
        return target
    }

    private suspend fun assertTargetChangeBeforeDispatch(
        fixture: Mounted, direct: Boolean, delete: Boolean, failRetirement: Boolean = false,
    ) {
        val target = target(fixture, direct)
        val conversation = if (direct) TimelineConversation.Direct(fixture.contact)
            else TimelineConversation.Channel(fixture.channel)
        val feature = fixture.harness.container.chatsFeature
        val displayed = assertNotNull(feature.mountedTimeline(conversation).data.fetchMessage(target.id))
        val radio = fixture.harness.links.radios.last()
        val code = if (direct) 0x02 else 0x03
        val before = radio.sent.count { it[0].toInt() == code }
        val originalResponse = radio.respond
        val nextMessage = if (direct) {
            fixture.live.messageService.createPendingMessage("Next message", fixture.contact, TextType.PLAIN, null)
        } else {
            fixture.live.messageService.createPendingChannelMessage("Next message", fixture.channel)
        }
        radio.respond = { frame -> if (frame[0].toInt() == code) emptyList() else originalResponse(frame) }
        suspend fun enqueueOrdinary(message: MessageDTO) {
            if (direct) {
                fixture.live.chatSendQueueService.enqueueDM(DirectMessageEnvelope(message.id, fixture.contact.id))
            } else {
                fixture.live.chatSendQueueService.enqueueChannel(ChannelMessageEnvelope(
                    message.id, fixture.channel.index, false, message.text, message.timestamp, "TestNode",
                    message.channelOccupantID,
                ))
            }
        }
        try {
            val blocker = if (direct) {
                fixture.live.messageService.createPendingMessage("Queue blocker", fixture.contact, TextType.PLAIN, null)
            } else {
                fixture.live.messageService.createPendingChannelMessage("Queue blocker", fixture.channel)
            }
            enqueueOrdinary(blocker)
            fixture.harness.eventually("ordinary send holds the queue") {
                radio.sent.count { it[0].toInt() == code } == before + 1
            }
            val blocked = radio.sent.last { it[0].toInt() == code }
            feature.react(displayed, "\uD83D\uDC4D", feature.captureMessageActionConversation(conversation))
            val reaction = store.fetchPendingSends(target.radioId).single { it.messageID != blocker.id }
            val events = fixture.harness.appState.messageEventStream.events()
            if (delete) store.deleteMessage(EntityKey(target.radioId, target.id))
            else store.saveMessage(target.copy(text = "Replacement"))
            enqueueOrdinary(nextMessage)
            if (failRetirement) {
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reaction_retirement_fault BEFORE DELETE ON pending_sends " +
                        "WHEN OLD.messageID = '${reaction.messageID}' COLLATE NOCASE " +
                        "BEGIN SELECT RAISE(ABORT, 'reaction retirement fault'); END",
                )
            }
            radio.respond = originalResponse
            val reply = if (direct) sentAndDelivered(blocked, key(7)) else listOf(Bytes.of(0))
            reply.forEach(radio::push)
            if (failRetirement) {
                fixture.harness.eventually("failed retirement remains parked") {
                    fixture.harness.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                        it.operation == "park.${reaction.messageID}"
                    }
                }
                assertEquals(before + 1, radio.sent.count { it[0].toInt() == code })
                assertTrue(store.fetchPendingSends(target.radioId).any { it.id == reaction.id })
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reaction_retirement_fault")
                fixture.live.chatSendQueueService.transportDidOpen()
            }
            fixture.harness.eventually("later ordinary message passes retired reaction") {
                radio.sent.count { it[0].toInt() == code } == before + 2
            }
            val next = radio.sent.last { it[0].toInt() == code }
            assertEquals("Next message", next.toByteArray().copyOfRange(if (direct) 13 else 7, next.size).toString(Charsets.UTF_8))
            assertEquals(before + 2, radio.sent.count { it[0].toInt() == code })
            assertTrue(store.fetchReactions(EntityKey(target.radioId, target.id)).isEmpty())
            val failedReaction = assertNotNull(store.fetchMessage(EntityKey(target.radioId, reaction.messageID)))
            assertEquals(MessageStatus.FAILED, failedReaction.status)
            assertFalse(failedReaction.isHiddenOutgoingReaction(isDM = direct))
            assertTrue(store.isOutgoingReaction(EntityKey(target.radioId, reaction.messageID)))
            withTimeout(5_000) { events.first { it == MessageEvent.MessageFailed(reaction.messageID) } }
            if (delete) assertNull(store.fetchMessage(EntityKey(target.radioId, target.id)))
            else assertEquals("Replacement", assertNotNull(store.fetchMessage(EntityKey(target.radioId, target.id))).text)
        } finally {
            radio.respond = originalResponse
            if (failRetirement) {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reaction_retirement_fault")
            }
        }
    }

    private fun wire(emoji: String, direct: Boolean): String =
        if (direct) "$emoji\nb45pc4ek" else "@[$targetName]$emoji\nb45pc4ek"

    private suspend fun assertIncoming(fixture: Mounted, direct: Boolean, emoji: String, accepted: Boolean) {
        val target = target(fixture, direct)
        val wire = wire(emoji, direct)
        poll(fixture, direct, if (direct) wire else "Bob: $wire", stamp + 1u)
        val saved = messages(fixture, direct)
        val targetKey = EntityKey(target.radioId, target.id)
        val reactions = store.fetchReactions(targetKey)
        if (accepted) {
            assertEquals(listOf(target.id), saved.map { it.id })
            val reaction = reactions.single()
            assertEquals(emoji, reaction.emoji)
            assertEquals("b45pc4ek", reaction.messageHash)
            assertEquals(wire, reaction.rawText)
            assertEquals("Bob", reaction.senderName)
            assertEquals(target.id, reaction.messageID)
            assertEquals(target.radioId, reaction.radioId)
            assertEquals(if (direct) fixture.contact.id else null, reaction.contactID)
            assertEquals(if (direct) null else fixture.channel.index, reaction.channelIndex)
            assertEquals("$emoji:1", assertNotNull(store.fetchMessage(targetKey)).reactionSummary)
        } else {
            assertEquals(2, saved.size)
            val ordinary = saved.single { it.id != target.id }
            assertEquals(wire, ordinary.text)
            assertEquals(MessageDirection.INCOMING, ordinary.direction)
            assertEquals(MessageStatus.DELIVERED, ordinary.status)
            assertEquals(if (direct) fixture.contact.id else null, ordinary.contactID)
            assertEquals(if (direct) null else fixture.channel.index, ordinary.channelIndex)
            if (!direct) assertEquals("Bob", ordinary.senderNodeName)
            assertTrue(reactions.isEmpty())
            assertTrue(store.fetchReactions(EntityKey(ordinary.radioId, ordinary.id)).isEmpty())
            assertNull(assertNotNull(store.fetchMessage(targetKey)).reactionSummary)
        }
    }

    private suspend fun assertOutgoing(fixture: Mounted, direct: Boolean, emoji: String, accepted: Boolean) {
        val target = target(fixture, direct)
        val conversation = if (direct) TimelineConversation.Direct(fixture.contact)
            else TimelineConversation.Channel(fixture.channel)
        val radio = fixture.harness.links.radios.last()
        val feature = fixture.harness.container.chatsFeature
        val displayed = assertNotNull(feature.mountedTimeline(conversation).data.fetchMessage(target.id))
        val beforeSend = radio.sent.size
        feature.react(displayed, emoji, feature.captureMessageActionConversation(conversation))
        val expectedStatus = if (direct) MessageStatus.DELIVERED else MessageStatus.SENT
        fixture.harness.eventually("actual App reaction-send committed status") {
            runBlocking {
                messages(fixture, direct).singleOrNull { it.direction == MessageDirection.OUTGOING }?.status == expectedStatus &&
                    (!accepted || store.fetchMessage(EntityKey(target.radioId, target.id))?.reactionSummary == "$emoji:1")
            }
        }
        val saved = messages(fixture, direct)
        assertEquals(2, saved.size)
        val outgoing = saved.single { it.direction == MessageDirection.OUTGOING }
        val expectedWire = wire(emoji, direct)
        assertEquals(expectedWire, outgoing.text)
        assertEquals(target.radioId, outgoing.radioId)
        assertEquals(if (direct) fixture.contact.id else null, outgoing.contactID)
        assertEquals(if (direct) null else fixture.channel.index, outgoing.channelIndex)
        val commandCode = if (direct) 0x02 else 0x03
        val commands = radio.sent.drop(beforeSend).filter { it[0].toInt() == commandCode || !direct && it[0].toInt() == 0x36 }
        assertEquals(if (direct) listOf(0x02) else listOf(0x36, 0x03), commands.map { it[0].toInt() })
        val frame = commands.last()
        assertEquals(commandCode, frame[0].toInt())
        assertEquals(expectedWire, frame.toByteArray().copyOfRange(if (direct) 13 else 7, frame.size).toString(Charsets.UTF_8))
        val classifier = fixture.harness.container.chatsFeature.listFor(target.radioId).reactions
        assertEquals(accepted, if (direct) classifier.isDirectReaction(outgoing.text) else classifier.isChannelReaction(outgoing.text))
        assertEquals(accepted, outgoing.isHiddenOutgoingReaction(isDM = direct))
        val rendering = HiddenOutgoingReactionPredicate.SourceWireFormat
        assertEquals(accepted, rendering.isHiddenOutgoingReaction(outgoing, isDM = direct))
        assertFalse(outgoing.copy(status = MessageStatus.FAILED).isHiddenOutgoingReaction(isDM = direct))
        assertFalse(rendering.isHiddenOutgoingReaction(outgoing.copy(status = MessageStatus.FAILED), isDM = direct))
        val targetReactions = store.fetchReactions(EntityKey(target.radioId, target.id))
        if (accepted) {
            assertEquals(listOf(emoji), targetReactions.map { it.emoji })
            assertEquals("$emoji:1", assertNotNull(store.fetchMessage(EntityKey(target.radioId, target.id))).reactionSummary)
        } else assertTrue(targetReactions.isEmpty())
    }

    private suspend fun TestScope.assertConcurrentOutgoing(fixture: Mounted, direct: Boolean) {
        val target = target(fixture, direct)
        val conversation = if (direct) TimelineConversation.Direct(fixture.contact)
            else TimelineConversation.Channel(fixture.channel)
        val feature = fixture.harness.container.chatsFeature
        val displayed = assertNotNull(feature.mountedTimeline(conversation).data.fetchMessage(target.id))
        val mounted = feature.captureMessageActionConversation(conversation)
        val radio = fixture.harness.links.radios.last()
        val originalResponse = radio.respond
        val code = if (direct) 0x02 else 0x03
        radio.respond = { frame ->
            if (frame[0].toInt() == code) emptyList() else originalResponse(frame)
        }
        val before = radio.sent.count { it[0].toInt() == code }
        val first = async { feature.react(displayed, "\uD83D\uDC4D", mounted) }
        fixture.harness.eventually("first reaction reaches the radio") {
            radio.sent.count { it[0].toInt() == code } == before + 1
        }
        val duplicate = async { feature.react(displayed, "\uD83D\uDC4D", mounted) }
        fixture.harness.eventually("duplicate reaction admission completes") { duplicate.isCompleted }
        duplicate.await()
        assertEquals(before + 1, radio.sent.count { it[0].toInt() == code })
        val command = radio.sent.last { it[0].toInt() == code }
        val reply = if (direct) sentAndDelivered(command, key(7)) else listOf(Bytes.of(0))
        reply.forEach(radio::push)
        fixture.harness.eventually("reaction bookkeeping completes") {
            runBlocking { store.fetchMessage(EntityKey(target.radioId, target.id))?.reactionSummary == "\uD83D\uDC4D:1" }
        }
        first.await()
        assertEquals(before + 1, radio.sent.count { it[0].toInt() == code })

        radio.respond = originalResponse
        feature.react(displayed, "\uD83D\uDC4D", mounted)
        testScheduler.runCurrent()
        assertEquals(before + 1, radio.sent.count { it[0].toInt() == code })
        feature.react(displayed, "\uD83D\uDD25", mounted)
        fixture.harness.eventually("different emoji reaches the radio") {
            radio.sent.count { it[0].toInt() == code } == before + 2
        }
        assertEquals(before + 2, radio.sent.count { it[0].toInt() == code })
        poll(fixture, direct, if (direct) "Another" else "$targetName: Another", stamp + 10u)
        val other = messages(fixture, direct).single { it.text == "Another" }
        val otherDisplayed = assertNotNull(feature.mountedTimeline(conversation).data.fetchMessage(other.id))
        feature.react(otherDisplayed, "\uD83D\uDC4D", mounted)
        fixture.harness.eventually("different target reaches the radio") {
            radio.sent.count { it[0].toInt() == code } == before + 3
        }
        fixture.harness.eventually("different target has its own summary") {
            runBlocking { store.fetchMessage(EntityKey(other.radioId, other.id))?.reactionSummary == "\uD83D\uDC4D:1" }
        }
    }

    private suspend fun poll(fixture: Mounted, direct: Boolean, text: String, timestamp: UInt) {
        val radio = fixture.harness.links.radios.last()
        val previous = radio.respond
        val frame = if (direct) {
            Bytes.of(0x10, 20, 0, 0) + peerKey.prefix(6) + Bytes.of(0, 0) + le32(timestamp.toLong()) + Bytes.utf8(text)
        } else {
            Bytes.of(0x11, 20, 0, 0, 0, 0, 0) + le32(timestamp.toLong()) + Bytes.utf8(text)
        }
        val frames = ConcurrentLinkedQueue(listOf(frame))
        val beforePoll = radio.sent.size
        radio.respond = { command ->
            if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a)) else previous(command)
        }
        try {
            assertEquals(1L, fixture.live.messagePollingService.pollAllMessages())
            assertEquals(listOf(Bytes.of(0x0a), Bytes.of(0x0a)), radio.sent.drop(beforePoll))
        } finally {
            radio.respond = previous
        }
    }
}
