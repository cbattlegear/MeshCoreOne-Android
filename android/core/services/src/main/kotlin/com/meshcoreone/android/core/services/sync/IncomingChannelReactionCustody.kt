// AndroidOnly: WP-214 Process custody before native channel reaction prerequisite reads and proven journal handoff.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ReactionQueuePersisting
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import java.lang.ref.WeakReference
import java.util.UUID

class IncomingChannelReactionCapacityException :
    IllegalStateException("Incoming channel reaction receive capacity exhausted (100)")

/** Callbacks run only at the journal write boundary and on a proven commit or unchanged preimage. */
interface IncomingChannelReactionTransfer {
    fun preparing()
    fun resolved(committed: Boolean)
}

internal data class ReceivedChannelReaction(
    val message: ChannelMessage,
    val channel: ChannelDTO?,
    val reaction: SyncIncomingChannelReaction,
    val workerID: UUID? = null,
)

/** The original delivered object is an occurrence witness, not decryption or channel-origin proof. */
class IncomingChannelReactionCustody(journal: ReactionQueuePersisting) {
    private enum class Phase { PENDING, WORKING, TRANSFERRING }
    private class Entry(
        val receive: ReceivedChannelReaction,
        var phase: Phase = Phase.PENDING,
        var workerID: UUID? = null,
    )
    private data class Completed(val radioId: RadioId, val source: WeakReference<ChannelMessage>)
    private val lock = Any()
    private val entries = linkedMapOf<UUID, Entry>()
    private val completed = mutableListOf<Completed>()

    init {
        journal.addRadioDeletionListener { deletion ->
            synchronized(lock) {
                entries.entries.removeAll { it.value.receive.reaction.radioId == deletion.radioId }
                completed.removeAll { it.radioId == deletion.radioId || it.source.get() == null }
            }
        }
    }

    internal fun retain(receive: ReceivedChannelReaction) = synchronized(lock) {
        val radio = receive.reaction.radioId
        completed.removeAll { it.source.get() == null }
        if (completed.any { it.radioId == radio && it.source.get() === receive.message }) return@synchronized
        if (entries.values.any {
                it.receive.reaction.radioId == radio && it.receive.message === receive.message
            }
        ) return@synchronized
        if (entries.size >= 100) throw IncomingChannelReactionCapacityException()
        entries[receive.reaction.occurrenceID] = Entry(receive)
    }

    internal fun claim(radioId: RadioId): ReceivedChannelReaction? = synchronized(lock) {
        val first = entries.values.firstOrNull { it.receive.reaction.radioId == radioId } ?: return@synchronized null
        if (first.phase != Phase.PENDING) return@synchronized null
        first.phase = Phase.WORKING
        val worker = UUID.randomUUID()
        first.workerID = worker
        first.receive.copy(workerID = worker)
    }

    internal fun release(receive: ReceivedChannelReaction): Boolean = synchronized(lock) {
        val entry = entries[receive.reaction.occurrenceID] ?: return@synchronized false
        if (entry.workerID == receive.workerID && entry.phase == Phase.WORKING) {
            entry.phase = Phase.PENDING
            entry.workerID = null
        }
        true
    }

    internal fun complete(receive: ReceivedChannelReaction) = synchronized(lock) {
        val entry = entries[receive.reaction.occurrenceID]
        if (entry != null && receive.workerID != null && entry.workerID == receive.workerID) {
            entries.remove(receive.reaction.occurrenceID)
            completed += Completed(receive.reaction.radioId, WeakReference(receive.message))
        }
    }

    internal fun transfer(receive: ReceivedChannelReaction) = object : IncomingChannelReactionTransfer {
        override fun preparing() = synchronized(lock) {
            val entry = checkNotNull(entries[receive.reaction.occurrenceID]) { "Received reaction custody was removed" }
            check(receive.workerID != null && entry.workerID == receive.workerID && entry.phase == Phase.WORKING)
            entry.phase = Phase.TRANSFERRING
        }

        override fun resolved(committed: Boolean) {
            synchronized(lock) {
                if (committed) complete(receive) else {
                    entries[receive.reaction.occurrenceID]?.let {
                        if (it.workerID == receive.workerID && it.phase == Phase.TRANSFERRING) {
                            it.phase = Phase.PENDING
                            it.workerID = null
                        }
                    }
                }
            }
        }
    }
}
