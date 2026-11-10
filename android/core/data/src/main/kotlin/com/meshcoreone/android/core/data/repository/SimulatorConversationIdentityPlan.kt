// AndroidOnly: WP-202 Builder-independent simulator identity claims for deferred transactional seeding.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import java.text.Normalizer
import java.util.UUID

data class SimulatorMessageIdentity(val id: UUID, val deduplicationKey: String?, val replyToID: UUID? = null)

data class SimulatorReactionIdentity(
    val id: UUID,
    val messageID: UUID,
    val senderName: String,
    val emoji: String,
)

data class SimulatorRepeatIdentity(val id: UUID, val messageID: UUID, val rxLogEntryID: UUID? = null)

data class SimulatorReactionSummary(val messageID: UUID, val summary: String)

/** Claims are snapshotted without constructing children, including for families that will be preserved. */
class SimulatorConversationFamily(
    val parent: SimulatorConversationParent,
    messages: Iterable<SimulatorMessageIdentity>,
    reactions: Iterable<SimulatorReactionIdentity>,
    repeats: Iterable<SimulatorRepeatIdentity>,
    previewTargets: Iterable<UUID>,
    summaries: Iterable<SimulatorReactionSummary>,
    repeatTargets: Iterable<UUID>,
    private val buildContents: () -> SimulatorConversationSeed,
) {
    val messages = messages.snapshot()
    val reactions = reactions.snapshot()
    val repeats = repeats.snapshot()
    val previewTargets = previewTargets.snapshot()
    val summaries = summaries.snapshot()
    val repeatTargets = repeatTargets.snapshot()

    internal fun build(): SimulatorConversationSeed {
        val result = buildContents()
        requireSimulatorIdentity(result.parent == parent)
        requireSimulatorIdentity(result.messages.map {
            SimulatorMessageIdentity(it.id, it.deduplicationKey, it.replyToID)
        } == messages)
        requireSimulatorIdentity(result.reactions.map {
            SimulatorReactionIdentity(it.id, it.messageID, it.senderName, it.emoji)
        } == reactions)
        requireSimulatorIdentity(result.repeats.map {
            SimulatorRepeatIdentity(it.id, it.messageID, it.rxLogEntryID)
        } == repeats)
        val contactID = (parent as? SimulatorConversationParent.Direct)?.contact?.id
        val channelIndex = (parent as? SimulatorConversationParent.Channel)?.channel?.index
        requireSimulatorIdentity(result.messages.all {
            it.radioId == parent.radioId && it.contactID == contactID && it.channelIndex == channelIndex
        })
        requireSimulatorIdentity(result.reactions.all {
            it.radioId == parent.radioId && it.contactID == contactID && it.channelIndex == channelIndex
        })
        requireSimulatorIdentity(result.messages.filter { it.linkPreviewFetched }.map { it.id } == previewTargets)
        requireSimulatorIdentity(result.messages.mapNotNull { message ->
            message.reactionSummary?.let { SimulatorReactionSummary(message.id, it) }
        } == summaries)
        return result
    }
}

/** Ordinary immutable fixture declarations, not a database lease or proof of a persisted row. */
class SimulatorConversationIdentityPlan private constructor(val families: SnapshotList<SimulatorConversationFamily>) {
    companion object {
        fun validate(families: Iterable<SimulatorConversationFamily>): SimulatorConversationIdentityPlan {
            val entries = families.snapshot()
            val contacts = entries.mapNotNull { (it.parent as? SimulatorConversationParent.Direct)?.contact }
            val channels = entries.mapNotNull { (it.parent as? SimulatorConversationParent.Channel)?.channel }
            requireUniqueSimulatorIdentities(contacts.map { it.radioId to it.id })
            requireUniqueSimulatorIdentities(contacts.map { it.radioId to it.publicKey })
            requireUniqueSimulatorIdentities(channels.map { it.radioId to it.id })
            requireUniqueSimulatorIdentities(channels.map { it.radioId to it.index })
            requireUniqueSimulatorIdentities(entries.flatMap { family ->
                family.messages.map { family.parent.radioId to it.id }
            })
            requireUniqueSimulatorIdentities(entries.flatMap { family ->
                family.messages.mapNotNull { message ->
                    message.deduplicationKey?.let { family.parent.radioId to normalizedSimulatorIdentity(it) }
                }
            })
            requireUniqueSimulatorIdentities(entries.flatMap { family ->
                family.reactions.map { family.parent.radioId to it.id }
            })
            requireUniqueSimulatorIdentities(entries.flatMap { family ->
                family.reactions.map {
                    Triple(family.parent.radioId, it.messageID, normalizedSimulatorIdentity("${it.senderName}-${it.emoji}"))
                }
            })
            requireUniqueSimulatorIdentities(entries.flatMap { family ->
                family.repeats.map { family.parent.radioId to it.id }
            })
            for (family in entries) {
                val messageIDs = family.messages.mapTo(hashSetOf()) { it.id }
                requireSimulatorIdentity(family.messages.all { it.replyToID == null || it.replyToID in messageIDs })
                requireSimulatorIdentity(family.reactions.all { it.messageID in messageIDs })
                requireSimulatorIdentity(family.repeats.all { it.messageID in messageIDs && it.rxLogEntryID == null })
                requireUniqueSimulatorIdentities(family.previewTargets)
                requireUniqueSimulatorIdentities(family.summaries.map { it.messageID })
                requireUniqueSimulatorIdentities(family.repeatTargets)
                requireSimulatorIdentity(family.previewTargets.all { it in messageIDs })
                requireSimulatorIdentity(family.summaries.all { it.messageID in messageIDs })
                requireSimulatorIdentity(family.repeatTargets.all { it in messageIDs })
                requireSimulatorIdentity(family.reactions.map { it.messageID }.toSet() ==
                    family.summaries.map { it.messageID }.toSet())
                requireSimulatorIdentity(family.repeats.map { it.messageID }.toSet() == family.repeatTargets.toSet())
                for (summary in family.summaries) {
                    val counts = family.reactions.filter { it.messageID == summary.messageID }
                        .groupBy { it.emoji }.entries.joinToString(",") { (emoji, rows) -> "$emoji:${rows.size}" }
                    requireSimulatorIdentity(summary.summary == counts)
                }
            }
            return SimulatorConversationIdentityPlan(entries)
        }
    }
}

internal val SimulatorConversationParent.radioId
    get() = when (this) {
        is SimulatorConversationParent.Direct -> contact.radioId
        is SimulatorConversationParent.Channel -> channel.radioId
    }

private fun normalizedSimulatorIdentity(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

private fun <T> requireUniqueSimulatorIdentities(values: List<T>) {
    requireSimulatorIdentity(values.size == values.toSet().size)
}

private fun requireSimulatorIdentity(condition: Boolean) {
    if (!condition) throw PersistenceStoreException(
        PersistenceStoreError.InvalidData,
        IllegalArgumentException("Simulator fixture identities do not match their declared family"),
    )
}
