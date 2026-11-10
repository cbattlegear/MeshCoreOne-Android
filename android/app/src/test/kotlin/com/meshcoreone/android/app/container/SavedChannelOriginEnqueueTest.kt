// AndroidOnly: WP-308 Real App Retry/Send Again preserve the saved occupant in the durable submission.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.ChannelMessageCreation
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [31, 37])
class SavedChannelOriginEnqueueTest : RoomProcessTest() {
    @Test fun sendAgainPreservesTheSavedOccupant() = enqueueSaved(retry = false)
    @Test fun retryPreservesTheSavedOccupant() = enqueueSaved(retry = true)

    private fun enqueueSaved(retry: Boolean) = runTest {
        val harness = ContainerHarness(this, store)
        harness.links.configureRadio = { firmware ->
            firmware.respond = { frame ->
                when (frame[0].toInt()) {
                    0x20 -> listOf(Bytes.of(0))
                    3 -> listOf(Bytes.of(1, 6))
                    else -> null
                }
            }
        }
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val graph = assertNotNull(harness.sessions.current)
            val radio = graph.token.radioId
            graph.channelService.setChannelWithSecret(radio, 0u, "Saved origin", Bytes(ByteArray(16) { 5 }))
            val snapshot = assertNotNull(store.fetchChannelOccupantSnapshot(radio, 0u))
            val origin = ChannelHistoryScope.from(snapshot)
            val message = store.createOrReconcileChannelMessage(
                ChannelMessageCreation(UUID.randomUUID(), origin, "Exact saved text \u4e2d\uD83D\uDE80", TextType.PLAIN),
                1000u, Instant.ofEpochSecond(1000),
            )
            store.updateMessageStatus(EntityKey(radio, message.id), MessageStatus.FAILED)
            val conversation = TimelineConversation.Channel(snapshot.channel)
            val feature = harness.container.chatsFeature
            if (retry) feature.timeline(conversation).retryService.retry(message, conversation)
            else feature.sendAgain(message, conversation)
            val pending = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
            assertEquals(message.channelOccupantID, pending.channelOccupantID)
            assertEquals(origin.expectedOccupantID, pending.channelOccupantID)
            assertEquals(message.id, pending.messageID)
            assertEquals(snapshot.channel.index, pending.channelIndex)
            assertEquals(message.text, pending.messageText)
            assertEquals(message.timestamp, pending.messageTimestamp)
            assertEquals(true, pending.isResend)
        } finally {
            harness.container.close()
        }
    }
}
