// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/DeviceDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/ContactDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/ChannelDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageRepeatDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/BlockedChannelSenderDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/ReactionDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/RemoteNodeSessionDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/RoomMessageDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/NodeStatusSnapshotDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/SavedTracePathDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.SelfInfo
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.DynamicTest

internal val AT: Instant = Instant.ofEpochSecond(1_700_000_000, 123_456_700)
internal val RADIO = RadioId(UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
internal val KEY = Bytes(ByteArray(32) { 0xAB.toByte() })

internal fun testDevice(id: UUID = UUID.randomUUID(), radioId: RadioId = RadioId(id)): DeviceDTO = DeviceDTO(
    id, radioId, Bytes(ByteArray(32) { 1 }), "TestDevice", 9u, "v1.13.0", "TestMfg", "01 Jan 2025",
    lastConnected = AT, isActive = true,
)
internal fun testContact(): ContactDTO =
    ContactDTO(radioId = RADIO, publicKey = KEY, name = "TestContact", typeRawValue = 1u, lastHeardTimestamp = null)
internal fun testChannel(scope: ChannelFloodScope = ChannelFloodScope.Inherit): ChannelDTO =
    ChannelDTO(radioId = RADIO, index = 1u, name = "General").withFloodScope(scope)
internal fun testMessage(): MessageDTO =
    MessageDTO(radioId = RADIO, contactID = UUID.randomUUID(), text = "Test message", timestamp = 1_700_000_000u, createdAt = AT)
internal fun testRepeat(): MessageRepeatDTO = MessageRepeatDTO(
    messageID = UUID.randomUUID(), receivedAt = AT, pathNodes = Bytes.of(0x31), snr = 8.5, rssi = -90,
)
internal fun testBlocked(): BlockedChannelSenderDTO = BlockedChannelSenderDTO(name = "SpammerNode", radioId = RADIO, dateBlocked = AT)
internal fun testReaction(): ReactionDTO = ReactionDTO(
    messageID = UUID.randomUUID(), emoji = "\uD83D\uDC4D", senderName = "TestSender", messageHash = "a1b2c3d4",
    rawText = "+m:a1b2c3d4:\uD83D\uDC4D", receivedAt = AT, channelIndex = 0u, radioId = RADIO,
)
internal fun testSession(): RemoteNodeSessionDTO = RemoteNodeSessionDTO(
    radioId = RADIO, publicKey = Bytes(ByteArray(32) { 0xCC.toByte() }), name = "TestNode", role = RemoteNodeRole.ROOM_SERVER,
)
internal fun testRoomMessage(): RoomMessageDTO = RoomMessageDTO(
    sessionID = UUID.randomUUID(), authorKeyPrefix = Bytes.of(0xAB, 0xCD, 0xEF, 1), authorName = "TestAuthor",
    text = "Hello from the room", timestamp = 1_700_000_000u, createdAt = AT,
)
internal fun testSnapshot(): NodeStatusSnapshotDTO = NodeStatusSnapshotDTO(
    timestamp = AT, nodePublicKey = Bytes(ByteArray(32) { 0xDD.toByte() }),
    batteryMillivolts = 3800u, lastSNR = 9.0, lastRSSI = -85, noiseFloor = -110, uptimeSeconds = 3600u,
)
internal fun testRun(): TracePathRunDTO = TracePathRunDTO(UUID.randomUUID(), AT, true, 250, SnapshotList.of(8.5, 7.0))
internal fun testPath(): SavedTracePathDTO = SavedTracePathDTO(
    UUID.randomUUID(), RADIO, "Test Path", Bytes.of(0x31, 0xA7), 1, AT, SnapshotList.empty(),
)
internal fun testSelfInfo(): SelfInfo = SelfInfo(
    1u, 20, 30, KEY, 37.7749, -122.4194, 2u, 1u, 0u, 0u, 2u, false, 906.875, 250.0, 11u, 8u, "UpdatedNode",
)
internal fun sourceCases(suite: String, vararg cases: Pair<String, () -> Unit>): List<DynamicTest> =
    cases.map { (name, assertions) -> DynamicTest.dynamicTest("$suite::$name()", assertions) }
