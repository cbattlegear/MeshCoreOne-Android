// PortedFrom: MC1Tests/Views/Chats/MessageActionAvailabilityTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Formatters/MessagePathFormatterTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MessageActionModelsTest {
    @Test
    fun `availability preserves source routing and sender rules`() {
        assertTrue(MessageActionAvailability.forMessage(message(routeType = RouteType.FLOOD)).canViewPath)
        assertFalse(MessageActionAvailability.forMessage(message(pathNodes = Bytes.EMPTY, routeType = RouteType.FLOOD)).canViewPath)
        assertFalse(MessageActionAvailability.forMessage(message(pathNodes = null, routeType = RouteType.FLOOD)).canViewPath)
        assertFalse(MessageActionAvailability.forMessage(message(routeType = RouteType.DIRECT)).canViewPath)
        assertFalse(
            MessageActionAvailability.forMessage(
                message(direction = MessageDirection.OUTGOING, routeType = RouteType.FLOOD),
            ).canViewPath,
        )
        assertTrue(
            MessageActionAvailability.forMessage(
                message(channelIndex = 0u, routeType = RouteType.DIRECT),
            ).canViewPath,
        )
    }

    @Test
    fun `path detail visibility includes incoming extras and outgoing repeats`() {
        assertTrue(MessageActionAvailability.forMessage(message(routeType = RouteType.FLOOD)).showsPathDetail)
        assertTrue(
            MessageActionAvailability.forMessage(
                message(pathNodes = Bytes.EMPTY, routeType = RouteType.FLOOD, heardRepeats = 1),
            ).showsPathDetail,
        )
        assertTrue(
            MessageActionAvailability.forMessage(
                message(pathNodes = null, routeType = RouteType.FLOOD, heardRepeats = 1),
            ).showsPathDetail,
        )
        assertTrue(
            MessageActionAvailability.forMessage(
                message(direction = MessageDirection.OUTGOING, heardRepeats = 2),
            ).canShowRepeatDetails,
        )
        assertFalse(
            MessageActionAvailability.forMessage(
                message(direction = MessageDirection.OUTGOING, heardRepeats = 0),
            ).showsPathDetail,
        )
    }

    @Test
    fun `route type and legacy path length preserve direct and flood semantics`() {
        assertTrue(message(routeType = RouteType.FLOOD).isFloodRouted)
        assertTrue(message(routeType = RouteType.TC_FLOOD).isFloodRouted)
        assertFalse(message(routeType = RouteType.DIRECT).isFloodRouted)
        assertFalse(message(routeType = RouteType.TC_DIRECT).isFloodRouted)
        assertTrue(message(channelIndex = 0u, routeType = null).isFloodRouted)
        assertTrue(message(pathLength = 0xFFu, routeType = null).isDirectRouted)
        assertTrue(message(pathLength = 2u, routeType = null).isFloodRouted)
        assertTrue(message(routeType = RouteType.DIRECT).isDirectRouted)
        assertTrue(message(routeType = RouteType.TC_DIRECT).isDirectRouted)
    }

    @Test
    fun `channel sender actions require an incoming resolved sender name`() {
        val named = MessageActionAvailability.forMessage(message(channelIndex = 0u))
        assertTrue(named.canSendDirectMessage)
        assertTrue(named.canBlockSender)
        assertFalse(
            MessageActionAvailability.forMessage(
                message(direction = MessageDirection.OUTGOING, channelIndex = 0u),
            ).canSendDirectMessage,
        )
        assertFalse(MessageActionAvailability.forMessage(message(channelIndex = null)).canSendDirectMessage)
        val prefixOnly = message(channelIndex = 0u, senderKeyPrefix = Bytes.of(0xAA), senderNodeName = null)
        assertFalse(MessageActionAvailability.forMessage(prefixOnly).canSendDirectMessage)
        assertFalse(MessageActionAvailability.forMessage(prefixOnly).canBlockSender)
    }

    @Test
    fun `formatter preserves direct flood hash modes and actual path bytes`() {
        assertEquals("Flood", MessagePathFormatter.format(message(pathLength = 0u, pathNodes = null), "Direct", "Flood"))
        assertEquals("Direct", MessagePathFormatter.format(message(pathLength = 0xFFu, pathNodes = null), "Direct", "Flood"))
        assertEquals("Direct", MessagePathFormatter.format(message(pathLength = 1u, pathNodes = Bytes.of(0xFF)), "Direct", "Flood"))
        assertEquals("A3", MessagePathFormatter.format(message(pathLength = 1u, pathNodes = Bytes.of(0xA3)), "Direct", "Flood"))
        assertEquals("A3,7F,42", MessagePathFormatter.format(message(pathLength = 3u, pathNodes = Bytes.of(0xA3, 0x7F, 0x42)), "Direct", "Flood"))
        assertEquals("00,A3", MessagePathFormatter.format(message(pathLength = 2u, pathNodes = Bytes.of(0, 0xA3)), "Direct", "Flood"))
        assertEquals("Flood", MessagePathFormatter.format(message(pathLength = 3u, pathNodes = null), "Direct", "Flood"))
        assertEquals("A3,7F,42", MessagePathFormatter.format(message(pathLength = 5u, pathNodes = Bytes.of(0xA3, 0x7F, 0x42)), "Direct", "Flood"))
        assertEquals("A1B2,C3D4", MessagePathFormatter.format(message(pathLength = 0x42u, pathNodes = Bytes.of(0xA1, 0xB2, 0xC3, 0xD4)), "Direct", "Flood"))
        assertEquals("010203,040506", MessagePathFormatter.format(message(pathLength = 0x82u, pathNodes = Bytes.of(1, 2, 3, 4, 5, 6)), "Direct", "Flood"))
        assertEquals("A3,7F,42,B2", MessagePathFormatter.format(message(pathLength = 4u, pathNodes = Bytes.of(0xA3, 0x7F, 0x42, 0xB2)), "Direct", "Flood"))
        assertEquals("A3,7F\u2026C1,D0", MessagePathFormatter.format(message(pathLength = 6u, pathNodes = Bytes.of(0xA3, 0x7F, 0x42, 0xB2, 0xC1, 0xD0)), "Direct", "Flood"))
    }

    @Test
    fun `delivery feedback is textual for every status`() {
        assertEquals(DeliveryFeedback.SENDING, DeliveryFeedback.from(message(status = MessageStatus.PENDING)))
        assertEquals(DeliveryFeedback.SENDING, DeliveryFeedback.from(message(status = MessageStatus.SENDING)))
        assertEquals(DeliveryFeedback.SENDING, DeliveryFeedback.from(message(status = MessageStatus.SENT)))
        assertEquals(DeliveryFeedback.SENT, DeliveryFeedback.from(message(channelIndex = 0u, status = MessageStatus.SENT)))
        assertEquals(DeliveryFeedback.DELIVERED, DeliveryFeedback.from(message(status = MessageStatus.DELIVERED)))
        assertEquals(DeliveryFeedback.FAILED, DeliveryFeedback.from(message(status = MessageStatus.FAILED)))
        assertEquals(DeliveryFeedback.RETRYING, DeliveryFeedback.from(message(status = MessageStatus.RETRYING)))
    }

    @Test
    fun `reaction summary parser retains order and skips malformed entries`() {
        assertEquals(
            listOf(ReactionSummaryEntry("👍", 3), ReactionSummaryEntry("❤️", 1)),
            parseReactionSummary("👍:3,bad,❤️:1,😂:nope,:2,🔥:2:1"),
        )
        assertTrue(parseReactionSummary(null).isEmpty())
        assertTrue(parseReactionSummary("").isEmpty())
    }

    @Test
    fun `signal quality preserves source thresholds`() {
        assertEquals(MessageSignalQuality.UNKNOWN, MessageSignalQuality.of(null))
        assertEquals(MessageSignalQuality.EXCELLENT, MessageSignalQuality.of(6.1))
        assertEquals(MessageSignalQuality.GOOD, MessageSignalQuality.of(6.0))
        assertEquals(MessageSignalQuality.FAIR, MessageSignalQuality.of(0.0))
        assertEquals(MessageSignalQuality.POOR, MessageSignalQuality.of(-6.0))
        assertEquals(MessageSignalQuality.POOR, MessageSignalQuality.of(Double.NaN))
    }
}
