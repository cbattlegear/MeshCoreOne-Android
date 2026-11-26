// PortedFrom: MC1Services/Sources/MC1Services/Services/AdvertisementEvent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Advertisement and discovery notifications broadcast by [AdvertisementService].
 *
 * Subscribe via [AdvertisementService.events]. The stream is multicast: every subscriber receives
 * every event, so coexisting consumers (sync coordination for discovery notifications, app state for
 * version bumps and deletion cleanup, connection UI for the storage-full flag, path-discovery views)
 * never steal each other's events.
 */
sealed interface AdvertisementEvent {
    /** A contact or discovered node was created or updated; observers should reload contact lists. */
    data object ContactUpdated : AdvertisementEvent

    /** Adopted orphan DMs created or updated a conversation row; observers must refresh the conversation list. */
    data object ConversationsChanged : AdvertisementEvent

    /**
     * Orphan DMs were linked to these contacts by a delta round. A DM that arrived before its sender's
     * contact existed had no contact to notify at receipt; the notification owner posts the banner and
     * refreshes the badge for each contact when adoption resolves the sender.
     */
    data class OrphanDirectMessagesAdopted(val contactIDs: SnapshotList<UUID>) : AdvertisementEvent

    /** A new contact was discovered via advertisement. */
    data class NewContactDiscovered(val name: String, val contactID: UUID, val contactType: ContactType) :
        AdvertisementEvent

    /** The device's node storage full state changed (true = full, false = has space). */
    data class NodeStorageFullChanged(val isFull: Boolean) : AdvertisementEvent

    /** Overwrite-oldest deletions. Observers drop notifications and refresh the badge. */
    data class ContactDeletedCleanup(val contactIDs: SnapshotList<UUID>) : AdvertisementEvent

    /** A path discovery response arrived for a contact. */
    data class PathDiscoveryResponse(val path: PathInfo) : AdvertisementEvent

    /** A trace response arrived; `traceInfo.tag` correlates it with the trace that requested it. */
    data class TraceResponse(val traceInfo: TraceInfo, val radioId: RadioId) : AdvertisementEvent

    /**
     * The RX log reported reception of a trace packet, carrying the SNR the local radio measured and,
     * when present, the far end's measured SNR.
     */
    data class TraceSnrObserved(val tag: UInt, val localSnr: Double, val remoteSnr: Double?, val radioId: RadioId) :
        AdvertisementEvent
}

/**
 * Multicast fan-out for [AdvertisementEvent]. Producers yield synchronously; registration happens when
 * [subscribe] is called (not when the flow is collected), so events yielded after the call are never
 * dropped. [finish] ends every subscriber and makes later subscriptions empty.
 */
internal class AdvertisementEventBroadcaster {
    private val lock = Any()
    private val subscribers = LinkedHashMap<Long, Channel<AdvertisementEvent>>()
    private var nextId = 0L
    private var finished = false

    fun subscribe(): Flow<AdvertisementEvent> {
        val channel = Channel<AdvertisementEvent>(Channel.UNLIMITED)
        val id = synchronized(lock) {
            val assigned = nextId++
            if (finished) channel.close() else subscribers[assigned] = channel
            assigned
        }
        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                synchronized(lock) { subscribers.remove(id) }
                channel.cancel()
            }
        }
    }

    fun yield(event: AdvertisementEvent) {
        synchronized(lock) {
            val closed = subscribers.filterValues { it.trySend(event).isClosed }.keys
            closed.forEach(subscribers::remove)
        }
    }

    fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
            subscribers.values.forEach { it.close() }
            subscribers.clear()
        }
    }
}
