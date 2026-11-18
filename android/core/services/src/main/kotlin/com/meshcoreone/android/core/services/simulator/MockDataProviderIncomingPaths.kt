// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+IncomingPaths.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.encodePathLen
import java.time.Instant
import java.util.UUID

/** Stored path on the multi-path incoming messages. Later routes are `messageRepeats(messageID, now)`. */
internal val MockDataProvider.incomingFirstPath: Bytes
    get() = Bytes.of(northRidgeRepeaterSeed.toInt(), twinPeaksRepeaterSeed.toInt())

internal const val INCOMING_EXTRA_PATH_COUNT = 3

internal const val ALICE_MULTI_PATH_AGE_SECONDS = -40L
internal const val PUBLIC_MULTI_PATH_AGE_SECONDS = -20L

internal fun MockDataProvider.aliceMultiPathMessage(
    identity: SimulatorFixtureMessageIdentity,
    now: Instant,
    senderKey: Bytes,
): MessageDTO =
    MockMessageFactory.message(
        identity = identity,
        createdAt = now.addingInterval(ALICE_MULTI_PATH_AGE_SECONDS),
        text = "Still on the trail. This one came in a few different ways.",
        direction = MessageDirection.INCOMING,
        contactID = aliceChenID,
        pathLength = encodePathLen(hashSize = 1, hopCount = 2),
        snr = 9.1,
        pathNodes = incomingFirstPath,
        senderKeyPrefix = senderKey,
        isRead = false,
        heardRepeats = INCOMING_EXTRA_PATH_COUNT.toLong(),
        routeType = RouteType.FLOOD,
    )

internal fun MockDataProvider.publicMultiPathMessage(identity: SimulatorFixtureMessageIdentity, now: Instant): MessageDTO =
    MockMessageFactory.message(
        identity = identity,
        createdAt = now.addingInterval(PUBLIC_MULTI_PATH_AGE_SECONDS),
        text = "Net check from the ridge. Hearing a few routes into the city.",
        direction = MessageDirection.INCOMING,
        channelIndex = publicChannelIndex,
        pathLength = encodePathLen(hashSize = 1, hopCount = 2),
        snr = 8.4,
        pathNodes = incomingFirstPath,
        senderNodeName = "Alice Chen",
        isRead = false,
        heardRepeats = INCOMING_EXTRA_PATH_COUNT.toLong(),
        routeType = RouteType.FLOOD,
    )

/** Later hearings of the same flood. Each route has its own hop list and SNR. */
internal fun MockDataProvider.incomingPathRepeats(messageID: UUID, receivedAt: Instant): SnapshotList<MessageRepeatDTO> {
    if (messageID != aliceMultiPathMessageID && messageID != publicMultiPathMessageID) return SnapshotList.empty()
    val identities = fixtureRepeatIdentities(messageID)
    val routes = incomingExtraRoutes()
    requireFixture(identities.size == routes.size, "Fixture repeat identities and routes disagree")
    return identities.zip(routes) { identity, route ->
        MessageRepeatDTO(
            id = identity.id,
            messageID = identity.messageID,
            receivedAt = receivedAt.addingInterval(route.offsetSeconds),
            pathNodes = route.pathNodes,
            pathLength = encodePathLen(hashSize = 1, hopCount = route.hopCount),
            snr = route.snr,
            rssi = route.rssi,
            rxLogEntryID = identity.rxLogEntryID,
        )
    }.snapshot()
}

private data class ExtraRoute(
    val offsetSeconds: Long,
    val pathNodes: Bytes,
    val hopCount: Int,
    val snr: Double,
    val rssi: Long,
)

private fun MockDataProvider.incomingExtraRoutes(): List<ExtraRoute> {
    val northRidge = northRidgeRepeaterSeed.toInt()
    val twinPeaks = twinPeaksRepeaterSeed.toInt()
    val oakland = oaklandRepeaterSeed.toInt()
    return listOf(
        ExtraRoute(offsetSeconds = 4, pathNodes = Bytes.of(oakland), hopCount = 1, snr = 5.4, rssi = -104),
        ExtraRoute(offsetSeconds = 12, pathNodes = Bytes.of(twinPeaks, oakland), hopCount = 2, snr = 3.1, rssi = -111),
        ExtraRoute(
            offsetSeconds = 28, pathNodes = Bytes.of(northRidge, twinPeaks, oakland), hopCount = 3, snr = 1.6, rssi = -116,
        ),
    )
}
