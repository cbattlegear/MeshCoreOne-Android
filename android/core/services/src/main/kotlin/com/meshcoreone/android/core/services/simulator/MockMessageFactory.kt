// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockMessageFactory.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID

/**
 * Builds a [MessageDTO] with demo defaults so each seed message specifies only what differs from a plain,
 * read, single-hop message. `timestamp` derives from `createdAt`; a clock-corrected message instead passes
 * its own `senderTimestamp`.
 */
internal object MockMessageFactory {
    @Suppress("LongParameterList")
    fun message(
        identity: SimulatorFixtureMessageIdentity,
        createdAt: Instant,
        text: String,
        direction: MessageDirection,
        status: MessageStatus = MessageStatus.DELIVERED,
        contactID: UUID? = null,
        channelIndex: UByte? = null,
        textType: TextType = TextType.PLAIN,
        ackCode: UInt? = null,
        pathLength: UByte = 1u,
        snr: Double? = null,
        pathNodes: Bytes? = null,
        senderKeyPrefix: Bytes? = null,
        senderNodeName: String? = null,
        isRead: Boolean = true,
        roundTripTime: UInt? = null,
        heardRepeats: Long = 0,
        retryAttempt: Long = 0,
        maxRetryAttempts: Long = 3,
        containsSelfMention: Boolean = false,
        mentionSeen: Boolean = false,
        timestampCorrected: Boolean = false,
        senderTimestamp: UInt? = null,
        routeType: RouteType? = null,
        regionScope: String? = null,
        regionScopeMatches: List<String> = emptyList(),
    ): MessageDTO = MessageDTO(
        id = identity.id,
        radioId = MockDataProvider.simulatorRadioId,
        contactID = contactID,
        channelIndex = channelIndex,
        text = text,
        timestamp = swiftUInt32Seconds(createdAt),
        createdAt = createdAt,
        sortDate = createdAt,
        direction = direction,
        status = status,
        textType = textType,
        ackCode = ackCode,
        pathLength = pathLength,
        snr = snr,
        pathNodes = pathNodes,
        senderKeyPrefix = senderKeyPrefix,
        senderNodeName = senderNodeName,
        isRead = isRead,
        replyToID = identity.replyToID,
        roundTripTime = roundTripTime,
        heardRepeats = heardRepeats,
        sendCount = 1,
        retryAttempt = retryAttempt,
        maxRetryAttempts = maxRetryAttempts,
        deduplicationKey = identity.deduplicationKey,
        containsSelfMention = containsSelfMention,
        mentionSeen = mentionSeen,
        timestampCorrected = timestampCorrected,
        senderTimestamp = senderTimestamp,
        routeType = routeType,
        regionScope = regionScope,
        regionScopeMatches = regionScopeMatches.snapshot(),
    )
}
