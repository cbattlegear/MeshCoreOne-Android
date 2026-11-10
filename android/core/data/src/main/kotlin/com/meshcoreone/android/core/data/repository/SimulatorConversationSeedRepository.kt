// AndroidOnly: WP-202 Same-transaction simulator conversation contents, never generic message replay.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.ChannelEntity
import com.meshcoreone.android.core.database.ContactEntity
import com.meshcoreone.android.core.database.MessageEntity
import com.meshcoreone.android.core.database.MessageRepeatEntity
import com.meshcoreone.android.core.database.ReactionEntity
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One family per caller-owned transaction; earlier committed parents are rechecked, never rolled back here. */
internal suspend fun RepositoryTransaction.seedDeferredSimulatorConversation(
    plan: SimulatorConversationIdentityPlan,
    familyIndex: Int,
    channels: ChannelRepository,
): SimulatorConversationSeedResult {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val job = coroutine[Job]
    if (job == null || !database.inTransaction() || familyIndex !in plan.families.indices) {
        throw PersistenceStoreException(PersistenceStoreError.InvalidData)
    }
    val parents = mutableListOf<SimulatorParentReadback>()
    val future = plan.families.drop(familyIndex + 1).map { snapshotFutureSimulatorParent(it.parent) }
    for (prior in plan.families.take(familyIndex)) {
        coroutine.ensureActive()
        when (val parent = prior.parent) {
            is SimulatorConversationParent.Direct -> {
                val prepared = prepareSimulatorContact(parent.contact)
                val actual = prepared.existing
                    ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                parents += SimulatorParentReadback.Direct(parent, actual)
            }
            is SimulatorConversationParent.Channel -> {
                val transaction = this
                val prepared = with(channels) { transaction.prepareSimulatorChannel(parent.channel) }
                if (prepared !is ChannelRepository.SimulatorChannelSeedPlan.Existing) {
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                parents += SimulatorParentReadback.Channel(
                    parent, with(channels) { transaction.applySimulatorChannel(prepared) },
                )
            }
        }
    }
    var created: PreparedSimulatorConversationContents? = null
    val family = plan.families[familyIndex]
    val result = run {
        val parent = family.parent
        when (parent) {
            is SimulatorConversationParent.Direct -> {
                val prepared = prepareSimulatorContact(parent.contact)
                val contents = if (prepared.existing == null) {
                    val built = buildDeferredSimulatorFamily(family)
                    requireDeferredDeduplication(parent.radioId.value, built.messages.mapNotNull { it.deduplicationKey }, 0)
                    prepareSimulatorConversationContents(built)
                } else null
                requireSimulatorReadback(currentCoroutineContext()[Job] === job && database.inTransaction())
                val applied = applySimulatorContact(prepared)
                parents += SimulatorParentReadback.Direct(parent, applied.parent)
                if (applied.mutated) save()
                if (contents == null) {
                    SimulatorConversationSeedResult.PreservedExisting(
                        SimulatorConversationParent.Direct(applied.parent.toDTO()),
                    )
                } else {
                    created = contents
                    val actualResult = SimulatorConversationSeedResult.Created(
                        SimulatorConversationParent.Direct(applied.parent.toDTO()),
                        applySimulatorConversationContents(contents),
                    )
                    save()
                    actualResult
                }
            }
            is SimulatorConversationParent.Channel -> {
                val transaction = this
                val prepared = with(channels) { transaction.prepareSimulatorChannel(parent.channel) }
                val contents = if (prepared is ChannelRepository.SimulatorChannelSeedPlan.Existing) null else {
                    val built = buildDeferredSimulatorFamily(family)
                    requireDeferredDeduplication(parent.radioId.value, built.messages.mapNotNull { it.deduplicationKey }, 0)
                    prepareSimulatorConversationContents(built)
                }
                requireSimulatorReadback(currentCoroutineContext()[Job] === job && database.inTransaction())
                val applied = with(channels) { transaction.applySimulatorChannel(prepared) }
                parents += SimulatorParentReadback.Channel(parent, applied)
                if (contents == null) {
                    SimulatorConversationSeedResult.PreservedExisting(
                        SimulatorConversationParent.Channel(applied.toDTO()),
                    )
                } else {
                    created = contents
                    val actualResult = SimulatorConversationSeedResult.Created(
                        SimulatorConversationParent.Channel(applied.toDTO()),
                        applySimulatorConversationContents(contents),
                    )
                    save()
                    actualResult
                }
            }
        }
    }
    // The context normally flushes RX after this block; finish that DML before the final guards.
    if (saves && pendingRx.isNotEmpty()) {
        persistPendingRx()
        pendingRx.clear()
    }
    requireSimulatorReadback(currentCoroutineContext()[Job] === job && database.inTransaction())
    created?.let {
        readbackSimulatorConversationContents(it)
        requireDeferredDeduplication(it.radioId, it.messages.mapNotNull { message -> message.deduplicationKey }, 1)
    }
    for (parent in parents) verifySimulatorParentReadback(parent)
    for (baseline in future) {
        requireSimulatorReadback(snapshotFutureSimulatorParent(baseline.parent) == baseline)
    }
    currentCoroutineContext().ensureActive()
    return result
}

private suspend fun buildDeferredSimulatorFamily(family: SimulatorConversationFamily): SimulatorConversationSeed {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    val result = try {
        family.build()
    } catch (cause: Exception) {
        try {
            caller.ensureActive()
        } catch (cancelled: CancellationException) {
            if (cancelled !== cause) cancelled.addSuppressed(cause)
            throw cancelled
        }
        throw cause
    }
    caller.ensureActive()
    return result
}

private data class FutureSimulatorParent(
    val parent: SimulatorConversationParent,
    val rows: List<List<SimulatorSqlValue>>,
)

private sealed interface SimulatorSqlValue {
    object Null : SimulatorSqlValue
    data class Integer(val value: Long) : SimulatorSqlValue
    data class Real(val bits: Long) : SimulatorSqlValue
    data class Text(val value: String) : SimulatorSqlValue
    data class Blob(val bytes: Bytes) : SimulatorSqlValue
}

internal const val SIMULATOR_FUTURE_CONTACT_QUERY =
    "SELECT * FROM contacts WHERE radioId >= upper(?) AND radioId <= lower(?) AND radioId = ? COLLATE NOCASE AND " +
        "(id = ? COLLATE NOCASE OR publicKey = ?) ORDER BY radioId, id"
internal const val SIMULATOR_FUTURE_CHANNEL_QUERY =
    "SELECT * FROM channels WHERE radioId >= upper(?) AND radioId <= lower(?) AND radioId = ? COLLATE NOCASE AND " +
        "(id = ? COLLATE NOCASE OR `index` = ?) ORDER BY radioId, id"

/** Snapshot only: do not admit or decode a future family before its selected transaction. */
private suspend fun RepositoryTransaction.snapshotFutureSimulatorParent(
    parent: SimulatorConversationParent,
): FutureSimulatorParent = when (parent) {
    is SimulatorConversationParent.Direct -> FutureSimulatorParent(parent, snapshotSimulatorParentRows(
        SIMULATOR_FUTURE_CONTACT_QUERY,
        arrayOf(
            parent.contact.radioId.value.toString(), parent.contact.radioId.value.toString(),
            parent.contact.radioId.value.toString(), parent.contact.id.toString(), parent.contact.publicKey.toByteArray(),
        ),
    ))
    is SimulatorConversationParent.Channel -> FutureSimulatorParent(parent, snapshotSimulatorParentRows(
        SIMULATOR_FUTURE_CHANNEL_QUERY,
        arrayOf(
            parent.channel.radioId.value.toString(), parent.channel.radioId.value.toString(),
            parent.channel.radioId.value.toString(), parent.channel.id.toString(), parent.channel.index.toLong(),
        ),
    ))
}

private fun RepositoryTransaction.snapshotSimulatorParentRows(
    query: String,
    arguments: Array<Any>,
): List<List<SimulatorSqlValue>> = database.openHelper.writableDatabase.query(query, arguments).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
            when (val type = cursor.getType(column)) {
                android.database.Cursor.FIELD_TYPE_NULL -> SimulatorSqlValue.Null
                android.database.Cursor.FIELD_TYPE_INTEGER -> SimulatorSqlValue.Integer(cursor.getLong(column))
                android.database.Cursor.FIELD_TYPE_FLOAT -> SimulatorSqlValue.Real(cursor.getDouble(column).toRawBits())
                android.database.Cursor.FIELD_TYPE_STRING -> SimulatorSqlValue.Text(cursor.getString(column))
                android.database.Cursor.FIELD_TYPE_BLOB -> SimulatorSqlValue.Blob(Bytes(cursor.getBlob(column)))
                else -> error("Unsupported SQLite column type: $type")
            }
        })
    }
}

private suspend fun RepositoryTransaction.requireDeferredDeduplication(radio: UUID, keys: List<String>, expected: Int) {
    if (keys.isEmpty()) return
    val stored = database.messages().simulatorDeduplicationKeys(radio)
        .map { Normalizer.normalize(it, Normalizer.Form.NFC) }
    val valid = keys.all { key ->
        stored.count { it == Normalizer.normalize(key, Normalizer.Form.NFC) } == expected
    }
    if (expected == 0) {
        if (!valid) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
    } else {
        requireSimulatorReadback(valid)
    }
}

private sealed interface SimulatorParentReadback {
    data class Direct(val requested: SimulatorConversationParent.Direct, val actual: ContactEntity) : SimulatorParentReadback
    data class Channel(val requested: SimulatorConversationParent.Channel, val actual: ChannelEntity) : SimulatorParentReadback
}

private suspend fun RepositoryTransaction.verifySimulatorParentReadback(parent: SimulatorParentReadback) {
    when (parent) {
        is SimulatorParentReadback.Direct -> {
            val row = parent.actual
            requireSimulatorReadback(
                database.contacts().byId(row.radioId, row.id) == row &&
                    database.contacts().forPublicKey(row.radioId, row.publicKey).singleOrNull() == row &&
                    database.contacts().countSimulatorIdentity(row.radioId, row.id) == 1L &&
                    database.contacts().simulatorPublicKeyCohort(row.radioId, row.publicKey).singleOrNull() == row &&
                    (row.id == parent.requested.contact.id ||
                        database.contacts().countSimulatorIdentity(row.radioId, parent.requested.contact.id) == 0L),
            )
        }
        is SimulatorParentReadback.Channel -> {
            val row = parent.actual
            requireSimulatorReadback(
                database.channels().byId(row.radioId, row.id) == row &&
                    database.channels().forIndex(row.radioId, row.index).singleOrNull() == row &&
                    database.channels().simulatorIdentityCohort(row.radioId, row.id).singleOrNull() == row &&
                    database.channels().simulatorSlotCohort(row.radioId, row.index).singleOrNull() == row &&
                    (row.id == parent.requested.channel.id ||
                        database.channels().simulatorIdentityCohort(row.radioId, parent.requested.channel.id).isEmpty()),
            )
        }
    }
}

/** The facade supplies its already-held writer/transaction, including the channel lane when applicable. */
internal suspend fun RepositoryTransaction.seedSimulatorConversation(
    seed: SimulatorConversationSeed,
    channels: ChannelRepository,
): SimulatorConversationSeedResult {
    val transaction = this
    return when (val parent = seed.parent) {
        is SimulatorConversationParent.Direct -> {
            val plan = prepareSimulatorContact(parent.contact)
            if (plan.existing != null) {
                val applied = applySimulatorContact(plan)
                if (applied.mutated) save()
                SimulatorConversationSeedResult.PreservedExisting(
                    SimulatorConversationParent.Direct(applied.parent.toDTO()),
                )
            } else {
                val contents = prepareSimulatorConversationContents(seed)
                val applied = applySimulatorContact(plan)
                val messages = applySimulatorConversationContents(contents)
                requireSimulatorReadback(
                    database.contacts().byId(applied.parent.radioId, applied.parent.id) == applied.parent &&
                        database.contacts().forPublicKey(applied.parent.radioId, applied.parent.publicKey)
                            .singleOrNull() == applied.parent &&
                        database.contacts().countSimulatorIdentity(applied.parent.radioId, applied.parent.id) == 1L &&
                        database.contacts().simulatorPublicKeyCohort(applied.parent.radioId, applied.parent.publicKey)
                            .singleOrNull() == applied.parent,
                )
                save()
                SimulatorConversationSeedResult.Created(
                    SimulatorConversationParent.Direct(applied.parent.toDTO()), messages,
                )
            }
        }
        is SimulatorConversationParent.Channel -> {
            val plan = with(channels) { transaction.prepareSimulatorChannel(parent.channel) }
            if (plan is ChannelRepository.SimulatorChannelSeedPlan.Existing) {
                val actual = with(channels) { transaction.applySimulatorChannel(plan) }
                SimulatorConversationSeedResult.PreservedExisting(SimulatorConversationParent.Channel(actual.toDTO()))
            } else {
                val contents = prepareSimulatorConversationContents(seed)
                val actual = with(channels) { transaction.applySimulatorChannel(plan) }
                val messages = applySimulatorConversationContents(contents)
                requireSimulatorReadback(
                    database.channels().byId(actual.radioId, actual.id) == actual &&
                        database.channels().forIndex(actual.radioId, actual.index).singleOrNull() == actual &&
                        database.channels().simulatorIdentityCohort(actual.radioId, actual.id).singleOrNull() == actual &&
                        database.channels().simulatorSlotCohort(actual.radioId, actual.index).singleOrNull() == actual,
                )
                save()
                SimulatorConversationSeedResult.Created(SimulatorConversationParent.Channel(actual.toDTO()), messages)
            }
        }
    }
}

private data class PreparedSimulatorConversationContents(
    val transaction: RepositoryTransaction,
    val radioId: UUID,
    val contactID: UUID?,
    val channelIndex: Long?,
    val messages: List<MessageEntity>,
    val reactions: List<ReactionEntity>,
    val repeats: List<MessageRepeatEntity>,
)

/** Call only for an absent, identity-checked parent, before applying that parent's insertion. */
private suspend fun RepositoryTransaction.prepareSimulatorConversationContents(
    seed: SimulatorConversationSeed,
): PreparedSimulatorConversationContents {
    val radio = when (val parent = seed.parent) {
        is SimulatorConversationParent.Direct -> parent.contact.radioId
        is SimulatorConversationParent.Channel -> parent.channel.radioId
    }
    val contactID = (seed.parent as? SimulatorConversationParent.Direct)?.contact?.id
    val channelIndex = (seed.parent as? SimulatorConversationParent.Channel)?.channel?.index
    fun requireFixture(condition: Boolean) {
        if (!condition) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
    }
    requireFixture(seed.messages.map { it.id }.distinct().size == seed.messages.size)
    requireFixture(seed.reactions.map { it.id }.distinct().size == seed.reactions.size)
    requireFixture(seed.repeats.map { it.id }.distinct().size == seed.repeats.size)
    val messageIDs = seed.messages.mapTo(hashSetOf()) { it.id }
    for (message in seed.messages) {
        requireFixture(message.radioId == radio && message.contactID == contactID && message.channelIndex == channelIndex)
        requireFixture(database.messages().countSimulatorIdentity(radio.value, message.id) == 0L)
        message.deduplicationKey?.let {
            requireFixture(database.messages().simulatorDeduplicationCohort(radio.value, it).isEmpty())
        }
        requireFixture(!database.reactions().hasSimulatorMessage(radio.value, message.id))
        requireFixture(!database.repeats().hasForMessage(radio.value, message.id))
        requireFixture(!database.pendingSends().hasForMessage(radio.value, message.id))
    }
    requireFixture(seed.messages.mapNotNull { it.deduplicationKey }.let { it.distinct().size == it.size })
    val existingMessageCount = database.messages().countSimulatorConversation(radio.value, contactID, channelIndex?.toLong())
    requireFixture(existingMessageCount == 0L)
    for (reaction in seed.reactions) {
        requireFixture(
            reaction.radioId == radio && reaction.messageID in messageIDs &&
                reaction.contactID == contactID && reaction.channelIndex == channelIndex,
        )
        requireFixture(database.reactions().countSimulatorIdentity(radio.value, reaction.id) == 0L)
    }
    for (repeat in seed.repeats) {
        requireFixture(repeat.messageID in messageIDs)
        requireFixture(database.repeats().countSimulatorIdentity(radio.value, repeat.id) == 0L)
    }
    requireFixture(!database.reactions().hasForConversation(radio.value, contactID, channelIndex?.toLong()))
    requireFixture(!database.pendingSends().hasForConversation(radio.value, contactID, channelIndex?.toLong()))
    currentCoroutineContext().ensureActive()
    return PreparedSimulatorConversationContents(
        this,
        radio.value,
        contactID,
        channelIndex?.toLong(),
        seed.messages.map { message ->
            message.toEntity().copy(
                linkPreviewImageData = message.linkPreviewImageData,
                linkPreviewIconData = message.linkPreviewIconData,
                linkPreviewFetched = message.linkPreviewFetched,
            )
        },
        seed.reactions.map { it.toEntity() },
        seed.repeats.map { it.toEntity(radio, it.messageID) },
    )
}

/** The caller already owns the parent writer and Room transaction; no context or lane is acquired here. */
private suspend fun RepositoryTransaction.applySimulatorConversationContents(
    prepared: PreparedSimulatorConversationContents,
): SnapshotList<MessageDTO> {
    requireSimulatorReadback(prepared.transaction === this)
    for (message in prepared.messages) {
        currentCoroutineContext().ensureActive()
        database.messages().insert(message)
    }
    for (reaction in prepared.reactions) {
        currentCoroutineContext().ensureActive()
        database.reactions().insert(reaction)
    }
    for (repeat in prepared.repeats) {
        currentCoroutineContext().ensureActive()
        database.repeats().insert(repeat)
    }
    return readbackSimulatorConversationContents(prepared)
}

private suspend fun RepositoryTransaction.readbackSimulatorConversationContents(
    prepared: PreparedSimulatorConversationContents,
): SnapshotList<MessageDTO> {
    requireSimulatorReadback(prepared.transaction === this)
    val storedMessages = prepared.messages.map { expected ->
        val actual = database.messages().byId(prepared.radioId, expected.id)
        requireSimulatorReadback(actual == expected)
        requireSimulatorReadback(database.messages().countSimulatorIdentity(prepared.radioId, expected.id) == 1L)
        checkNotNull(actual)
    }
    val storedMessageCount = prepared.contactID?.let {
        database.messages().countForContact(prepared.radioId, it)
    } ?: database.messages().countForChannel(prepared.radioId, checkNotNull(prepared.channelIndex))
    requireSimulatorReadback(storedMessageCount == prepared.messages.size.toLong())
    requireSimulatorReadback(database.messages().countSimulatorConversation(
        prepared.radioId, prepared.contactID, prepared.channelIndex,
    ) == prepared.messages.size.toLong())
    requireSimulatorReadback(!database.pendingSends().hasForConversation(
        prepared.radioId, prepared.contactID, prepared.channelIndex,
    ))
    requireSimulatorReadback(database.reactions().countForConversation(
        prepared.radioId, prepared.contactID, prepared.channelIndex,
    ) == prepared.reactions.size.toLong())
    requireSimulatorReadback(database.reactions().countSimulatorConversation(
        prepared.radioId, prepared.contactID, prepared.channelIndex,
    ) == prepared.reactions.size.toLong())
    for (reaction in prepared.reactions) {
        requireSimulatorReadback(database.reactions().byId(prepared.radioId, reaction.id) == reaction)
        requireSimulatorReadback(database.reactions().countSimulatorIdentity(prepared.radioId, reaction.id) == 1L)
    }
    for (repeat in prepared.repeats) {
        requireSimulatorReadback(database.repeats().byId(prepared.radioId, repeat.id) == repeat)
        requireSimulatorReadback(database.repeats().countSimulatorIdentity(prepared.radioId, repeat.id) == 1L)
    }
    for (message in prepared.messages) {
        message.deduplicationKey?.let {
            requireSimulatorReadback(
                database.messages().simulatorDeduplicationCohort(prepared.radioId, it) == listOf(message),
            )
        }
        requireSimulatorReadback(!database.pendingSends().hasForMessage(prepared.radioId, message.id))
        val reactions = database.reactions().simulatorMessageCohort(prepared.radioId, message.id)
        val expectedReactions = prepared.reactions.filter { it.messageID == message.id }
        requireSimulatorReadback(reactions.size == expectedReactions.size && reactions.toSet() == expectedReactions.toSet())
        requireSimulatorReadback(database.reactions().forMessage(prepared.radioId, message.id, -1).size == reactions.size)
        val repeats = database.repeats().simulatorMessageCohort(prepared.radioId, message.id)
        val expectedRepeats = prepared.repeats.filter { it.messageID == message.id }
        requireSimulatorReadback(repeats.size == expectedRepeats.size && repeats.toSet() == expectedRepeats.toSet())
        requireSimulatorReadback(database.repeats().forMessage(prepared.radioId, message.id).size == repeats.size)
    }
    currentCoroutineContext().ensureActive()
    return storedMessages.map { it.toDTO() }.snapshot()
}

private fun requireSimulatorReadback(condition: Boolean) {
    if (!condition) throw PersistenceStoreException(
        PersistenceStoreError.SaveFailed("seedSimulatorConversation"),
        IllegalStateException("Simulator conversation insert did not persist the expected rows"),
    )
}
