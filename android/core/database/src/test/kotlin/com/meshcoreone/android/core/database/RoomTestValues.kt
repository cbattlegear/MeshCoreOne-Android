// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/ChannelDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/DeviceDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/RemoteNodeSessionDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/RoomMessageDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Actual Room/DAO test inputs, not a pretend PersistenceStore implementation.
package com.meshcoreone.android.core.database

import androidx.room.withTransaction
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OriginalCase(val id: String)

internal val AT: Instant = Instant.ofEpochSecond(1_700_000_000, 123_456_700)
internal val RADIO_A: UUID = UUID.fromString("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
internal val RADIO_B: UUID = UUID.fromString("BBBBBBBB-CCCC-DDDD-EEEE-FFFFFFFFFFFF")
internal val KEY: Bytes = Bytes(ByteArray(32) { 0xAB.toByte() })
internal fun device(radioId: UUID = RADIO_A, id: UUID = UUID.randomUUID()): DeviceDTO =
    DeviceDTO(id, RadioId(radioId), KEY, "TestDevice", 9u, "v1.13.0", "TestMfg", "01 Jan 2025", lastConnected = AT)
internal fun contact(radioId: UUID = RADIO_A, id: UUID = UUID.randomUUID()): ContactDTO =
    ContactDTO(id, RadioId(radioId), KEY, "TestContact", typeRawValue = 1u, lastHeardTimestamp = null)
internal fun channel(radioId: UUID = RADIO_A, index: UByte = 0u): ChannelDTO =
    ChannelDTO(radioId = RadioId(radioId), index = index, name = "General")
internal fun message(
    radioId: UUID = RADIO_A, contactID: UUID? = UUID.randomUUID(), index: UByte? = null,
    status: MessageStatus = MessageStatus.FAILED, direction: MessageDirection = MessageDirection.OUTGOING,
    id: UUID = UUID.randomUUID(), text: String = "Test message",
): MessageDTO = MessageDTO(
    id, RadioId(radioId), contactID, index, text, 1_700_000_000u, AT, status = status, direction = direction,
)
internal fun session(radioId: UUID = RADIO_A): RemoteNodeSessionDTO =
    RemoteNodeSessionDTO(radioId = RadioId(radioId), publicKey = KEY, name = "TestNode", role = RemoteNodeRole.ROOM_SERVER)
internal fun roomMessage(sessionID: UUID, self: Boolean = true, status: MessageStatus = MessageStatus.FAILED): RoomMessageDTO =
    RoomMessageDTO(sessionID = sessionID, authorKeyPrefix = Bytes.of(0xAB, 0xCD, 0xEF, 1), text = "Hello from the room",
        timestamp = 1_700_000_000u, createdAt = AT, isFromSelf = self, statusRawValue = status.rawValue)

internal suspend fun MeshCoreDatabase.failureKeys(radioId: UUID): FailedSendConversationKeys = withTransaction {
    FailedSendConversationKeys(
        messages().failedContactIDs(radioId).snapshotSet(),
        messages().failedChannelIDs(radioId).snapshotSet(),
        roomMessages().failedSessionIDs(radioId).snapshotSet(),
    )
}
