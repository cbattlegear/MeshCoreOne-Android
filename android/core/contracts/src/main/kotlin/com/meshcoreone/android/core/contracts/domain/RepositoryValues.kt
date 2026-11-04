// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/MessagePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/ContactPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/RxLogPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/NodeSnapshotPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionParser.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import java.util.UUID

data class EntityKey(val radioId: RadioId, val id: UUID)
data class MessageWindow(val messages: SnapshotList<MessageDTO>, val hasMore: Boolean)
data class ChannelQuery(val radioId: RadioId, val channelIndex: UByte, val id: UUID)
data class ContactIdentity(val id: UUID, val publicKey: Bytes)
data class ContactSaveResult(val id: UUID, val isNew: Boolean)
enum class ContactAvatarUpdateResult { UPDATED, CONTACT_NOT_FOUND, IDENTITY_MISMATCH }
data class DiscoveredNodeSaveResult(val node: DiscoveredNodeDTO, val isNew: Boolean)
data class ParsedReaction(val emoji: String, val targetSender: String, val messageHash: String)
data class NeighborBaseline(val previous: NodeStatusSnapshotDTO?, val seenPrefixes: SnapshotSet<Bytes>)
data class UnreadCounts(val contacts: Long, val channels: Long, val rooms: Long)
data class RxLogRegionUpdate(val id: UUID, val regionScope: String?, val regionScopeMatches: SnapshotList<String>)
data class RxLogDecryptionUpdate(val id: UUID, val channelIndex: UByte?, val channelName: String?, val senderTimestamp: UInt?)
data class ChannelRegionUpdate(
    val channelIndex: UByte, val senderTimestamp: UInt, val regionScope: String?, val regionScopeMatches: SnapshotList<String>,
)
data class DirectRegionUpdate(
    val senderPrefixByte: UByte, val senderTimestamp: UInt, val regionScope: String?, val regionScopeMatches: SnapshotList<String>,
)

sealed interface PersistenceStoreError {
    data object DeviceNotFound : PersistenceStoreError
    data object ContactNotFound : PersistenceStoreError
    data object MessageNotFound : PersistenceStoreError
    data object ChannelNotFound : PersistenceStoreError
    data object RemoteNodeSessionNotFound : PersistenceStoreError
    data class SaveFailed(val reason: String) : PersistenceStoreError
    data class FetchFailed(val reason: String) : PersistenceStoreError
    data object InvalidData : PersistenceStoreError
}

class PersistenceStoreException(val error: PersistenceStoreError, cause: Throwable? = null) :
    Exception(error.javaClass.simpleName, cause)

// The Swift store intentionally shares these process logs and the URL cache across connections.
object DebugLogRetention {
    val window: Duration = Duration.ofDays(7)
    const val MAX_ENTRIES = 50_000L
    val pruneInterval: Duration = Duration.ofHours(1)
}

object RxLogRetention {
    const val KEEP_COUNT = 1000L
    const val PRUNE_THRESHOLD = 100L
    const val BATCH_SIZE = 20L
    val flushInterval: Duration = Duration.ofSeconds(1)
}
