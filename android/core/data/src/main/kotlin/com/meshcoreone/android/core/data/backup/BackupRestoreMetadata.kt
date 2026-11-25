// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupHelpers.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupImport.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.data.repository.RepositoryDeduplicationKey
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.math.RoundingMode
import java.time.Instant
import java.text.Normalizer
import java.util.UUID

internal data class PublicKeyIdentity(val radioId: RadioId, val publicKey: Bytes)
internal data class ChannelSlot(val radioId: RadioId, val index: UByte)
internal data class ParentIdentity(val radioId: RadioId, val id: UUID)
internal data class TraceIdentity(val radioId: RadioId, val path: Bytes, val hashSize: Long)
internal data class ReactionIdentity(val parent: ParentIdentity, val canonicalSuffix: String)
internal data class DirectBackupIdentity(
    val senderPrefix: Bytes?, val timestamp: UInt?, val type: TextType, val text: String, val unprovedID: UUID?,
)
internal data class MessageBackupKey(val sourceKey: String, val direct: DirectBackupIdentity?)
internal data class RoomMessageBackupKey(
    val parent: ParentIdentity, val sourceKey: String, val author: Bytes, val timestamp: UInt, val text: String,
)

internal fun sourceStringKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
internal fun reactionIdentity(parent: ParentIdentity, senderName: String, emoji: String): ReactionIdentity =
    ReactionIdentity(parent, sourceStringKey("$senderName-$emoji"))

internal fun messageBackupKey(dto: MessageDTO): MessageBackupKey {
    val key = if (dto.direction == MessageDirection.OUTGOING) "out-${dto.id.canonicalString()}"
    else sourceStringKey("${dto.radioId.canonicalString}-${dto.deduplicationKey ?: RepositoryDeduplicationKey.contentBased(
        dto.contactID, dto.channelIndex, dto.senderNodeName, dto.timestamp, dto.text,
    )}")
    val direct = if (dto.direction == MessageDirection.INCOMING && dto.channelIndex == null) {
        val timestamp = dto.senderTimestamp ?: dto.timestamp.takeUnless { dto.timestampCorrected }
        val unprovedID = dto.id.takeIf {
            timestamp == null || (dto.senderKeyPrefix == null && dto.contactID == null)
        }
        DirectBackupIdentity(dto.senderKeyPrefix, timestamp, dto.textType, dto.text, unprovedID)
    } else null
    return MessageBackupKey(key, direct)
}

internal fun roomMessageBackupKey(parent: ParentIdentity, dto: RoomMessageDTO): RoomMessageBackupKey =
    RoomMessageBackupKey(parent, sourceStringKey(dto.deduplicationKey), dto.authorKeyPrefix, dto.timestamp, dto.text)

internal fun rewriteDirectKey(key: String?, from: UUID, to: UUID): String? =
    rewriteLeadingKey(key, "dm-${from.canonicalString()}-", "dm-${to.canonicalString()}-")
internal fun rewriteChannelKey(key: String?, from: UByte, to: UByte): String? =
    rewriteLeadingKey(key, "ch-$from-", "ch-$to-")
private fun rewriteLeadingKey(key: String?, from: String, to: String): String? =
    if (key?.startsWith(from) == true) to + key.substring(from.length) else key

internal fun snapshotBackupKey(dto: NodeStatusSnapshotDTO): Pair<Bytes, Long> =
    dto.nodePublicKey to dto.timestamp.unixSeconds().movePointRight(3).setScale(0, RoundingMode.DOWN).longValueExact()

internal fun ownedSnapshotBackupKey(dto: NodeStatusSnapshotDTO): Pair<RadioId?, Pair<Bytes, Long>> =
    dto.selfRadioID to snapshotBackupKey(dto)

internal fun maxDate(local: StoredInstant?, imported: Instant?): StoredInstant? =
    if (imported != null && (local == null || imported > local.toInstant())) StoredInstant.from(imported) else local

internal fun mergeContact(local: ContactEntity, dto: ContactDTO, now: Instant): ContactEntity = local.copy(
    nickname = local.nickname ?: dto.nickname, isBlocked = local.isBlocked || dto.isBlocked,
    isMuted = local.isMuted || dto.isMuted, isFavorite = local.isFavorite || dto.isFavorite,
    isPinned = local.isPinned || dto.isPinned,
    lastMessageDate = maxDate(local.lastMessageDate, dto.lastMessageDate),
    unreadCount = maxOf(local.unreadCount, dto.unreadCount), unreadMentionCount = maxOf(local.unreadMentionCount, dto.unreadMentionCount),
    ocvPreset = local.ocvPreset ?: dto.ocvPreset, customOCVArrayString = local.customOCVArrayString ?: dto.customOCVArrayString,
    avatarImageData = local.avatarImageData ?: dto.avatarImageData,
    lastHeardTimestamp = maxOf(local.lastHeardTimestamp, dto.lastHeardTimestamp?.let { RoomPersistenceStore.clampedPhoneClockTimestamp(it, now) }?.toLong() ?: 0),
)

internal fun mergeChannel(local: ChannelEntity, dto: ChannelDTO): ChannelEntity {
    val localDTO = local.toDTO()
    val adoptScope = localDTO.floodScope == ChannelFloodScope.Inherit && dto.floodScope != ChannelFloodScope.Inherit
    val scope = if (adoptScope) ChannelFloodScopeStorage.decompose(dto.floodScope) else null
    return local.copy(
        lastMessageDate = maxDate(local.lastMessageDate, dto.lastMessageDate),
        unreadCount = maxOf(local.unreadCount, dto.unreadCount), unreadMentionCount = maxOf(local.unreadMentionCount, dto.unreadMentionCount),
        notificationLevelRawValue = if (localDTO.notificationLevel == NotificationLevel.ALL && dto.notificationLevel != NotificationLevel.ALL)
            dto.notificationLevel.rawValue else local.notificationLevelRawValue,
        isFavorite = local.isFavorite || dto.isFavorite,
        floodScopeModeRawValue = scope?.modeRawValue ?: local.floodScopeModeRawValue,
        regionScope = if (scope != null) scope.regionName else local.regionScope,
    )
}

internal fun mergeSession(local: RemoteNodeSessionEntity, dto: RemoteNodeSessionDTO): RemoteNodeSessionEntity = local.copy(
    unreadCount = maxOf(local.unreadCount, dto.unreadCount),
    notificationLevelRawValue = if (local.toDTO().notificationLevel == NotificationLevel.ALL && dto.notificationLevel != NotificationLevel.ALL)
        dto.notificationLevel.rawValue else local.notificationLevelRawValue,
    isFavorite = local.isFavorite || dto.isFavorite, lastSyncTimestamp = maxOf(local.lastSyncTimestamp, dto.lastSyncTimestamp.toLong()),
    lastMessageDate = maxDate(local.lastMessageDate, dto.lastMessageDate),
)
