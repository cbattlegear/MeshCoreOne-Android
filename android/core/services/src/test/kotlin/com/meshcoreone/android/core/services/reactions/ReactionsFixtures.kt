// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Shared WP-216 test fixtures: Swift `MessageDTO.testChannelMessage`, the heard-repeat echo builders and clocks. */
internal object ReactionsFixtures {
    const val TEST_NODE_NAME = "TestNode"
    private const val TEST_TIMEOUT_MILLIS = 10_000L

    fun nowSeconds(): UInt = Instant.now().epochSecond.toUInt()

    fun newRadioId(): RadioId = RadioId(UUID.randomUUID())

    /** Runs a suspending test body with a generous timeout so a broken await fails instead of hanging. */
    fun <T> blocking(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(TEST_TIMEOUT_MILLIS) { block() } }

    /** Swift `MessageDTO.testChannelMessage` (defaults: outgoing, sent, no path). */
    fun testChannelMessage(
        id: UUID = UUID.randomUUID(),
        radioId: RadioId = newRadioId(),
        channelIndex: UByte = 0u,
        text: String = "Test channel message",
        timestamp: UInt = nowSeconds(),
        createdAt: Instant = Instant.now(),
        direction: MessageDirection = MessageDirection.OUTGOING,
        status: MessageStatus = MessageStatus.SENT,
        pathLength: UByte = 0u,
        senderNodeName: String? = null,
        heardRepeats: Long = 0,
    ): MessageDTO = MessageDTO(
        id = id,
        radioId = radioId,
        contactID = null,
        channelIndex = channelIndex,
        text = text,
        timestamp = timestamp,
        createdAt = createdAt,
        direction = direction,
        status = status,
        pathLength = pathLength,
        senderNodeName = senderNodeName,
        heardRepeats = heardRepeats,
    )

    /**
     * Swift `HeardRepeatsServiceTests.makeEcho`: a decrypted channel-message echo whose decoded text carries the
     * `"NodeName: body"` format and a matching sender timestamp.
     */
    fun makeEcho(
        radioId: RadioId,
        channelIndex: UByte,
        senderTimestamp: UInt,
        body: String,
        senderName: String = TEST_NODE_NAME,
        id: UUID = UUID.randomUUID(),
        pathNodes: List<Int> = listOf(0x42),
        pathLength: UByte = 1u,
        receivedAt: Instant = Instant.now(),
        payloadType: PayloadType = PayloadType.GROUP_TEXT,
        decryptStatus: DecryptStatus = DecryptStatus.SUCCESS,
        decodedText: String? = "$senderName: $body",
    ): RxLogEntryDTO = RxLogEntryDTO(
        id = id,
        radioId = radioId,
        receivedAt = receivedAt,
        snr = 8.0,
        rssi = -70,
        routeType = RouteType.FLOOD,
        payloadType = payloadType,
        payloadVersion = 0u,
        transportCode = null,
        pathLength = pathLength,
        pathNodes = Bytes.of(*pathNodes.toIntArray()),
        packetPayload = Bytes.of(0x01, 0x02, 0x03),
        rawPayload = Bytes.of(0x01),
        packetHash = "test",
        channelIndex = channelIndex,
        channelName = "Test",
        decryptStatus = decryptStatus,
        senderTimestamp = senderTimestamp,
        payloadTypeBits = 5u,
        decodedText = decodedText,
    )

    /** Swift `HeardRepeatsServiceTests.incomingChannelMessage`. */
    fun incomingChannelMessage(
        id: UUID = UUID.randomUUID(),
        radioId: RadioId,
        channelIndex: UByte,
        text: String,
        senderName: String,
        wireTimestamp: UInt,
        pathNodes: Bytes?,
        pathLength: UByte,
        timestampCorrected: Boolean = false,
        receiveTime: Instant = Instant.now(),
    ): MessageDTO = testChannelMessage(
        id = id,
        radioId = radioId,
        channelIndex = channelIndex,
        text = text,
        timestamp = if (timestampCorrected) receiveTime.epochSecond.toUInt() else wireTimestamp,
        createdAt = receiveTime,
        direction = MessageDirection.INCOMING,
        pathLength = pathLength,
        senderNodeName = senderName,
    ).copy(
        pathNodes = pathNodes,
        timestampCorrected = timestampCorrected,
        senderTimestamp = if (timestampCorrected) wireTimestamp else null,
        deduplicationKey = DeduplicationKey.contentBased(null, channelIndex, senderName, wireTimestamp, text),
    )

    fun bytes(vararg values: Int): Bytes = Bytes.of(*values)
}

/** A clock that advances by [step] on every read, so successive index operations get strictly later instants. */
internal class ReactionsTickingClock(
    start: Instant = Instant.ofEpochSecond(1_700_000_000),
    private val step: Duration = Duration.ofMillis(1),
) : Clock() {
    private val nanos = AtomicLong(start.epochSecond * NANOS_PER_SECOND + start.nano)

    override fun instant(): Instant {
        val value = nanos.getAndAdd(step.toNanos())
        return Instant.ofEpochSecond(value / NANOS_PER_SECOND, value % NANOS_PER_SECOND)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
