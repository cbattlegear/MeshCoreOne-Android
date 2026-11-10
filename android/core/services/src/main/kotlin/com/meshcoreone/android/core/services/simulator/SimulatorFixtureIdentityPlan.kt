// AndroidOnly: WP-217 Immutable simulator fixture identities for deferred conversation construction.
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal val simulatorFixtureDeduplicationKey: String? = null

data class SimulatorFixtureMessageIdentity(
    val id: UUID,
    val deduplicationKey: String?,
    val replyToID: UUID? = null,
)

data class SimulatorFixtureReactionIdentity(
    val id: UUID,
    val messageID: UUID,
    val senderName: String,
    val emoji: String,
)

data class SimulatorFixtureRepeatIdentity(val id: UUID, val messageID: UUID, val rxLogEntryID: UUID? = null)

/** Metadata is available without building children; storage calls the pure builder only for an absent parent. */
interface SimulatorDeferredFixtureFamily {
    val parent: SimulatorFixtureParent
    val messages: SnapshotList<SimulatorFixtureMessageIdentity>
    val reactions: SnapshotList<SimulatorFixtureReactionIdentity>
    val repeats: SnapshotList<SimulatorFixtureRepeatIdentity>
    val previewTargets: SnapshotList<UUID>
    val summaries: SnapshotList<ReactedMessage>
    val repeatTargets: SnapshotList<UUID>

    fun build(): SimulatorFixtureConversation
}

interface SimulatorDeferredFixturePlan {
    val families: SnapshotList<SimulatorDeferredFixtureFamily>
}

internal class SimulatorFixtureFamily(
    override val parent: SimulatorFixtureParent,
    messages: Iterable<SimulatorFixtureMessageIdentity>,
    reactions: Iterable<SimulatorFixtureReactionIdentity>,
    repeats: Iterable<SimulatorFixtureRepeatIdentity>,
    previewTargets: Iterable<UUID>,
    summaries: Iterable<ReactedMessage>,
    repeatTargets: Iterable<UUID>,
    private val buildContents: () -> SimulatorFixtureConversation,
) : SimulatorDeferredFixtureFamily {
    override val messages = messages.snapshot()
    override val reactions = reactions.snapshot()
    override val repeats = repeats.snapshot()
    override val previewTargets = previewTargets.snapshot()
    override val summaries = summaries.snapshot()
    override val repeatTargets = repeatTargets.snapshot()

    override fun build(): SimulatorFixtureConversation {
        val result = buildContents()
        requireFixture(result.parent == parent, "Fixture builder changed its parent")
        requireFixture(
            result.messages.map { SimulatorFixtureMessageIdentity(it.id, it.deduplicationKey, it.replyToID) } == messages,
            "Fixture builder changed its message identities",
        )
        requireFixture(result.messages.all { it.belongsTo(parent) }, "Fixture message has the wrong parent")
        requireFixture(
            result.reactions.map {
                SimulatorFixtureReactionIdentity(it.id, it.messageID, it.senderName, it.emoji)
            } == reactions,
            "Fixture builder changed its reaction identities",
        )
        requireFixture(
            result.reactions.all {
                it.radioId == parent.radioId &&
                    it.contactID == parent.contactID &&
                    it.channelIndex == parent.channelIndex &&
                    it.messageHash == it.messageID.canonicalString().take(8) &&
                    it.rawText == it.emoji
            },
            "Fixture reaction has the wrong parent",
        )
        requireFixture(
            result.repeats.map { SimulatorFixtureRepeatIdentity(it.id, it.messageID, it.rxLogEntryID) } == repeats,
            "Fixture builder changed its repeat identities",
        )
        requireFixture(
            result.messages.filter { it.linkPreviewFetched }.map { it.id } == previewTargets,
            "Fixture builder changed its preview targets",
        )
        requireFixture(
            result.messages.mapNotNull { message ->
                message.reactionSummary?.let { ReactedMessage(message.id, it) }
            } == summaries,
            "Fixture builder changed its reaction summaries",
        )
        return result
    }
}

internal class SimulatorFixtureIdentityPlan private constructor(
    override val families: SnapshotList<SimulatorFixtureFamily>,
) : SimulatorDeferredFixturePlan {
    companion object {
        fun validate(families: Iterable<SimulatorFixtureFamily>): SimulatorFixtureIdentityPlan {
            val entries = families.snapshot()
            val contacts = entries.mapNotNull { (it.parent as? SimulatorFixtureParent.Direct)?.contact }
            val channels = entries.mapNotNull { (it.parent as? SimulatorFixtureParent.Channel)?.channel }
            requireUnique(contacts.map { it.radioId to it.id }, "Duplicate fixture contact ID")
            requireUnique(contacts.map { it.radioId to it.publicKey }, "Duplicate fixture contact key")
            requireUnique(channels.map { it.radioId to it.id }, "Duplicate fixture channel ID")
            requireUnique(channels.map { it.radioId to it.index }, "Duplicate fixture channel slot")
            requireUnique(
                entries.flatMap { family -> family.messages.map { family.parent.radioId to it.id } },
                "Duplicate fixture message ID",
            )
            requireUnique(
                entries.flatMap { family ->
                    family.messages.mapNotNull { message ->
                        message.deduplicationKey?.let { family.parent.radioId to normalized(it) }
                    }
                },
                "Duplicate fixture message deduplication key",
            )
            requireUnique(
                entries.flatMap { family -> family.reactions.map { family.parent.radioId to it.id } },
                "Duplicate fixture reaction ID",
            )
            requireUnique(
                entries.flatMap { family ->
                    family.reactions.map {
                        Triple(family.parent.radioId, it.messageID, normalized("${it.senderName}-${it.emoji}"))
                    }
                },
                "Duplicate fixture reaction identity",
            )
            requireUnique(
                entries.flatMap { family -> family.repeats.map { family.parent.radioId to it.id } },
                "Duplicate fixture repeat ID",
            )
            for (family in entries) {
                val messageIDs = family.messages.map { it.id }.toSet()
                requireFixture(
                    family.messages.all { it.replyToID == null || it.replyToID in messageIDs },
                    "Orphan fixture reply",
                )
                requireFixture(family.reactions.all { it.messageID in messageIDs }, "Orphan fixture reaction")
                requireFixture(family.repeats.all { it.messageID in messageIDs }, "Orphan fixture repeat")
                requireFixture(family.repeats.all { it.rxLogEntryID == null }, "Unplanned fixture RX-log reference")
                requireUnique(family.previewTargets, "Duplicate fixture preview target")
                val summaryTargets = family.summaries.map { it.messageID }
                requireUnique(summaryTargets, "Duplicate fixture summary target")
                requireUnique(family.repeatTargets, "Duplicate fixture repeat target")
                requireFixture(family.previewTargets.all { it in messageIDs }, "Orphan fixture preview")
                requireFixture(summaryTargets.all { it in messageIDs }, "Orphan fixture summary")
                requireFixture(family.repeatTargets.all { it in messageIDs }, "Orphan fixture repeat target")
                requireFixture(
                    family.reactions.map { it.messageID }.toSet() == summaryTargets.toSet(),
                    "Fixture reaction summaries and rows disagree",
                )
                requireFixture(
                    family.repeats.map { it.messageID }.toSet() == family.repeatTargets.toSet(),
                    "Fixture repeat targets and rows disagree",
                )
                for (summary in family.summaries) {
                    val counts = family.reactions.filter { it.messageID == summary.messageID }
                        .groupBy { it.emoji }
                        .entries.joinToString(",") { (emoji, rows) -> "$emoji:${rows.size}" }
                    requireFixture(summary.summary == counts, "Fixture reaction summary counts disagree")
                }
            }
            return SimulatorFixtureIdentityPlan(entries)
        }
    }
}

internal val SimulatorFixtureParent.radioId: RadioId
    get() = when (this) {
        is SimulatorFixtureParent.Direct -> contact.radioId
        is SimulatorFixtureParent.Channel -> channel.radioId
    }

internal val SimulatorFixtureParent.contactID: UUID?
    get() = (this as? SimulatorFixtureParent.Direct)?.contact?.id

internal val SimulatorFixtureParent.channelIndex: UByte?
    get() = (this as? SimulatorFixtureParent.Channel)?.channel?.index

private fun MessageDTO.belongsTo(parent: SimulatorFixtureParent): Boolean =
    radioId == parent.radioId && contactID == parent.contactID && channelIndex == parent.channelIndex

private fun <T> requireUnique(values: List<T>, message: String) {
    requireFixture(values.size == values.toSet().size, message)
}

private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

internal fun requireFixture(condition: Boolean, message: String) {
    if (!condition) invalidFixture(message)
}

internal fun invalidFixture(message: String): Nothing =
    throw PersistenceStoreException(PersistenceStoreError.InvalidData, IllegalArgumentException(message))

private fun fixtureMessage(id: UUID, replyToID: UUID? = null): SimulatorFixtureMessageIdentity =
    SimulatorFixtureMessageIdentity(id, simulatorFixtureDeduplicationKey, replyToID)

internal fun MockDataProvider.fixtureDirectMessageIdentities(contactID: UUID): SnapshotList<SimulatorFixtureMessageIdentity> =
    when (contactID) {
    aliceChenID -> SnapshotList.of(
        fixtureMessage(uuid("10000000-0000-0000-0000-000000000001")),
        fixtureMessage(aliceReactedMessageID),
        fixtureMessage(uuid("10000000-0000-0000-0000-000000000003"), replyToID = aliceReactedMessageID),
        fixtureMessage(uuid("10000000-0000-0000-0000-000000000009")),
        fixtureMessage(uuid("10000000-0000-0000-0000-00000000000C")),
        fixtureMessage(aliceLinkPreviewMessageID),
        fixtureMessage(uuid("10000000-0000-0000-0000-00000000000B")),
        fixtureMessage(uuid("10000000-0000-0000-0000-0000000000FF")),
        fixtureMessage(aliceMultiPathMessageID),
    )
    bobMartinezID -> SnapshotList.of(
        fixtureMessage(uuid("20000000-0000-0000-0000-000000000001")),
        fixtureMessage(uuid("20000000-0000-0000-0000-000000000002")),
        fixtureMessage(uuid("20000000-0000-0000-0000-000000000003")),
        fixtureMessage(uuid("20000000-0000-0000-0000-000000000004")),
        fixtureMessage(uuid("20000000-0000-0000-0000-000000000005")),
        fixtureMessage(uuid("20000000-0000-0000-0000-000000000006")),
    )
    frankWilsonID -> SnapshotList.of(
        fixtureMessage(uuid("60000000-0000-0000-0000-000000000001")),
        fixtureMessage(frankRepeatMessageID),
        fixtureMessage(uuid("60000000-0000-0000-0000-000000000003")),
        fixtureMessage(frankFloodUniqueMessageID),
        fixtureMessage(frankFloodAmbiguousMessageID),
    )
    hannahLeeID -> SnapshotList.of(
        fixtureMessage(uuid("80000000-0000-0000-0000-000000000001")),
        fixtureMessage(uuid("80000000-0000-0000-0000-000000000002")),
        fixtureMessage(uuid("80000000-0000-0000-0000-000000000003")),
    )
    else -> SnapshotList.empty()
}

internal fun MockDataProvider.fixtureChannelMessageIdentities(index: UByte): SnapshotList<SimulatorFixtureMessageIdentity> =
    when (index) {
    publicChannelIndex -> SnapshotList.of(
        fixtureMessage(uuid("C0000000-0000-0000-0000-000000000001")),
        fixtureMessage(uuid("C0000000-0000-0000-0000-000000000002")),
        fixtureMessage(uuid("C0000000-0000-0000-0000-000000000003")),
        fixtureMessage(uuid("C0000000-0000-0000-0000-000000000004")),
        fixtureMessage(publicAmbiguousRegionMessageID),
        fixtureMessage(publicMultiPathMessageID),
    )
    bayAreaChannelIndex -> SnapshotList.of(
        fixtureMessage(uuid("C1000000-0000-0000-0000-000000000001")),
        fixtureMessage(bayAreaReactedMessageID),
        fixtureMessage(bayAreaMentionMessageID),
    )
    trailCrewChannelIndex -> SnapshotList.of(
        fixtureMessage(uuid("C2000000-0000-0000-0000-000000000001")),
        fixtureMessage(uuid("C2000000-0000-0000-0000-000000000002")),
    )
    meshHQChannelIndex -> (0 until MESH_HQ_TOTAL_MESSAGES).map {
        fixtureMessage(uuid("C3000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012X", it)))
    }.snapshot()
    else -> SnapshotList.empty()
}

internal fun MockDataProvider.fixtureReactionIdentities(messageID: UUID): SnapshotList<SimulatorFixtureReactionIdentity> =
    when (messageID) {
    aliceReactedMessageID -> SnapshotList.of(
        SimulatorFixtureReactionIdentity(uuid("A0000000-0000-0000-0000-000000000001"), messageID, "You", "👍"),
        SimulatorFixtureReactionIdentity(uuid("A0000000-0000-0000-0000-000000000002"), messageID, "Bob Martinez", "👍"),
        SimulatorFixtureReactionIdentity(uuid("A0000000-0000-0000-0000-000000000003"), messageID, "Alice Chen", "❤️"),
    )
    bayAreaReactedMessageID -> SnapshotList.of(
        SimulatorFixtureReactionIdentity(uuid("A1000000-0000-0000-0000-000000000001"), messageID, "Alice Chen", "🎉"),
        SimulatorFixtureReactionIdentity(uuid("A1000000-0000-0000-0000-000000000002"), messageID, "Bob Martinez", "🎉"),
    )
    bayAreaMentionMessageID -> SnapshotList.of(
        SimulatorFixtureReactionIdentity(uuid("A1000000-0000-0000-0000-000000000003"), messageID, "Sim", "👍"),
    )
    else -> SnapshotList.empty()
}

internal fun MockDataProvider.fixtureRepeatIdentities(messageID: UUID): SnapshotList<SimulatorFixtureRepeatIdentity> =
    when (messageID) {
        frankRepeatMessageID -> SnapshotList.of(
            SimulatorFixtureRepeatIdentity(uuid("B0000000-0000-0000-0000-000000000001"), messageID),
            SimulatorFixtureRepeatIdentity(uuid("B0000000-0000-0000-0000-000000000002"), messageID),
            SimulatorFixtureRepeatIdentity(uuid("B0000000-0000-0000-0000-000000000003"), messageID),
        )
        aliceMultiPathMessageID -> SnapshotList.of(
            SimulatorFixtureRepeatIdentity(uuid("B1000000-0000-0000-0000-000000000001"), messageID),
            SimulatorFixtureRepeatIdentity(uuid("B1000000-0000-0000-0000-000000000002"), messageID),
            SimulatorFixtureRepeatIdentity(uuid("B1000000-0000-0000-0000-000000000003"), messageID),
        )
        publicMultiPathMessageID -> SnapshotList.of(
            SimulatorFixtureRepeatIdentity(uuid("B2000000-0000-0000-0000-000000000001"), messageID),
            SimulatorFixtureRepeatIdentity(uuid("B2000000-0000-0000-0000-000000000002"), messageID),
            SimulatorFixtureRepeatIdentity(uuid("B2000000-0000-0000-0000-000000000003"), messageID),
        )
        else -> SnapshotList.empty()
    }

internal val MockDataProvider.fixturePreviewTargets: SnapshotList<UUID>
    get() = SnapshotList.of(aliceLinkPreviewMessageID)

fun simulatorDeferredFixturePlan(now: Instant): SimulatorDeferredFixturePlan = simulatorFixtureIdentityPlan(now)

internal fun simulatorFixtureIdentityPlan(now: Instant): SimulatorFixtureIdentityPlan {
    val provider = MockDataProvider
    val parents: List<SimulatorFixtureParent> = provider.contacts(now).map { SimulatorFixtureParent.Direct(it) } +
        provider.channels(now).map { SimulatorFixtureParent.Channel(it) }
    val messagesByParent = parents.map { parent ->
        when (parent) {
            is SimulatorFixtureParent.Direct -> provider.fixtureDirectMessageIdentities(parent.contact.id)
            is SimulatorFixtureParent.Channel -> provider.fixtureChannelMessageIdentities(parent.channel.index)
        }
    }
    val allIDs = messagesByParent.flatten().map { it.id }.toSet()
    val previews = provider.fixturePreviewTargets
    val summaries = provider.reactedMessages
    val summaryIDs = summaries.map { it.messageID }
    val repeated = provider.messagesWithRepeats
    requireUnique(previews, "Duplicate fixture preview target")
    requireUnique(summaryIDs, "Duplicate fixture summary target")
    requireUnique(repeated, "Duplicate fixture repeat target")
    requireFixture(previews.all { it in allIDs }, "Orphan fixture preview")
    requireFixture(summaryIDs.all { it in allIDs }, "Orphan fixture summary")
    requireFixture(repeated.all { it in allIDs }, "Orphan fixture repeat")
    requireFixture(summaryIDs.all { provider.fixtureReactionIdentities(it).isNotEmpty() }, "Missing fixture reactions")
    requireFixture(repeated.all { provider.fixtureRepeatIdentities(it).isNotEmpty() }, "Missing fixture repeats")

    val families = parents.zip(messagesByParent) { parent, identities ->
        val ids = identities.map { it.id }
        val idSet = ids.toSet()
        val familyPreviews = ids.filter { it in previews }
        val familySummaries = ids.mapNotNull { id -> summaries.singleOrNull { it.messageID == id } }
        val familyRepeatTargets = ids.filter { it in repeated }
        val reactions = ids.flatMap { messageID ->
            if (messageID !in summaryIDs) emptyList() else provider.fixtureReactionIdentities(messageID)
        }
        val repeats = ids.flatMap { messageID ->
            if (messageID !in repeated) emptyList() else provider.fixtureRepeatIdentities(messageID)
        }
        SimulatorFixtureFamily(
            parent = parent,
            messages = identities,
            reactions = reactions,
            repeats = repeats,
            previewTargets = familyPreviews,
            summaries = familySummaries,
            repeatTargets = familyRepeatTargets,
        ) { buildSimulatorFixtureFamily(parent, now, idSet, familyPreviews.isNotEmpty()) }
    }
    return SimulatorFixtureIdentityPlan.validate(families)
}

private fun buildSimulatorFixtureFamily(
    parent: SimulatorFixtureParent,
    now: Instant,
    messageIDs: Set<UUID>,
    hasPreviews: Boolean,
): SimulatorFixtureConversation {
    val provider = MockDataProvider
    val raw = when (parent) {
        is SimulatorFixtureParent.Direct -> provider.messages(parent.contact.id, now)
        is SimulatorFixtureParent.Channel -> provider.channelMessages(parent.channel.index, now)
    }
    val previews = if (hasPreviews) provider.linkPreviewSeeds.associateBy { it.messageID } else emptyMap()
    val summaries = provider.reactedMessages.associateBy { it.messageID }
    val repeated = provider.messagesWithRepeats.toSet()
    val messages = raw.map { message ->
        val preview = previews[message.id]
        message.copy(
            linkPreviewURL = preview?.url ?: message.linkPreviewURL,
            linkPreviewTitle = preview?.title ?: message.linkPreviewTitle,
            linkPreviewImageData = preview?.imageData ?: message.linkPreviewImageData,
            linkPreviewIconData = if (preview != null) null else message.linkPreviewIconData,
            linkPreviewFetched = preview != null || message.linkPreviewFetched,
            reactionSummary = summaries[message.id]?.summary ?: message.reactionSummary,
        )
    }
    return SimulatorFixtureConversation(
        parent = parent,
        messages = messages.snapshot(),
        reactions = messages.flatMap { message ->
            if (message.id in summaries && message.id in messageIDs) provider.reactions(message.id, now) else emptyList()
        }.snapshot(),
        repeats = messages.flatMap { message ->
            if (message.id in repeated && message.id in messageIDs) provider.messageRepeats(message.id, now) else emptyList()
        }.snapshot(),
    )
}
