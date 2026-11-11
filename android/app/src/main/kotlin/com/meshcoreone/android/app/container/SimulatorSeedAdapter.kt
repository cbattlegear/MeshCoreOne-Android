// AndroidOnly: WP-217 Adapts the real process Room store to the simulator's seed contract.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.data.repository.SimulatorConversationFamily
import com.meshcoreone.android.core.data.repository.SimulatorConversationIdentityPlan
import com.meshcoreone.android.core.data.repository.SimulatorConversationParent
import com.meshcoreone.android.core.data.repository.SimulatorConversationSeed
import com.meshcoreone.android.core.data.repository.SimulatorConversationSeedResult
import com.meshcoreone.android.core.data.repository.SimulatorMessageIdentity
import com.meshcoreone.android.core.data.repository.SimulatorReactionIdentity
import com.meshcoreone.android.core.data.repository.SimulatorReactionSummary
import com.meshcoreone.android.core.data.repository.SimulatorRepeatIdentity
import com.meshcoreone.android.core.data.repository.SimulatorRxSeedOutcome
import com.meshcoreone.android.core.data.repository.SimulatorRxSeedPresence
import com.meshcoreone.android.core.data.repository.SimulatorRxSeedResult
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.simulator.NodeStatusSnapshotInsertResult
import com.meshcoreone.android.core.services.simulator.NodeStatusSnapshotKey
import com.meshcoreone.android.core.services.simulator.SimulatorDeferredFixturePlan
import com.meshcoreone.android.core.services.simulator.SimulatorFixtureConversation
import com.meshcoreone.android.core.services.simulator.SimulatorFixtureParent
import com.meshcoreone.android.core.services.simulator.SimulatorFixtureSeedResult
import com.meshcoreone.android.core.services.simulator.SimulatorSeedStore
import java.time.DateTimeException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class RoomSimulatorSeedStore(private val store: RoomPersistenceStore) : SimulatorSeedStore {
    override suspend fun saveDevice(dto: DeviceDTO) { store.insertDeviceIfAbsent(dto) }
    override suspend fun fetchContact(key: EntityKey): ContactDTO? = store.fetchContact(key)
    override suspend fun saveContact(dto: ContactDTO) = store.saveContact(dto)
    override suspend fun seedContact(dto: ContactDTO) = store.saveSimulatorContact(dto)

    override suspend fun seedConversations(
        buildPlan: () -> SimulatorDeferredFixturePlan,
        replayReference: suspend () -> Unit,
    ): SimulatorFixtureSeedResult {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val plan = try {
            buildPlan().toRoomSimulatorIdentityPlan()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            try {
                caller.ensureActive()
            } catch (cancellation: CancellationException) {
                if (cancellation !== failure) cancellation.addSuppressed(failure)
                throw cancellation
            }
            throw failure
        }
        caller.ensureActive()
        var created = 0
        var preserved = 0
        for (index in plan.families.indices) {
            caller.ensureActive()
            when (store.seedSimulatorConversation(plan, index)) {
                is SimulatorConversationSeedResult.Created -> created = Math.incrementExact(created)
                is SimulatorConversationSeedResult.PreservedExisting -> preserved = Math.incrementExact(preserved)
            }
        }
        return SimulatorFixtureSeedResult.Durable(created, preserved)
    }

    override suspend fun saveChannel(dto: ChannelDTO) = store.saveSimulatorChannel(dto)
    override suspend fun saveMessage(dto: MessageDTO) = store.saveMessage(dto)
    override suspend fun updateMessageLinkPreview(
        key: EntityKey, url: String?, title: String?, imageData: Bytes?, iconData: Bytes?, fetched: Boolean,
    ) = store.updateMessageLinkPreview(key, url, title, imageData, iconData, fetched)
    override suspend fun saveReaction(dto: ReactionDTO) = store.saveReaction(dto)
    override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?) =
        store.updateMessageReactionSummary(message, summary)
    override suspend fun saveMessageRepeat(radioId: RadioId, dto: MessageRepeatDTO) =
        store.saveMessageRepeat(radioId, dto)
    override suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO> =
        store.fetchRxLogEntries(radioId, limit)
    override suspend fun saveRxLogEntry(dto: RxLogEntryDTO) = store.saveRxLogEntry(dto)
    override suspend fun seedRxLogEntries(radioId: RadioId, buildEntries: () -> SnapshotList<RxLogEntryDTO>) =
        seedRoomSimulatorRxEntries(radioId, buildEntries, store::seedSimulatorRxBatchIfAbsent)

    override suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO? =
        store.fetchLatestNodeStatusSnapshot(nodePublicKey)
    override suspend fun existingNodeStatusSnapshotKeys(): SnapshotSet<NodeStatusSnapshotKey> =
        store.existingNodeStatusSnapshotKeys().map { (key, milliseconds) ->
            NodeStatusSnapshotKey(key, milliseconds)
        }.snapshotSet()
    override suspend fun batchInsertNodeStatusSnapshots(
        dtos: SnapshotList<NodeStatusSnapshotDTO>, existingKeys: SnapshotSet<NodeStatusSnapshotKey>,
    ): NodeStatusSnapshotInsertResult {
        val (inserted, skipped) = store.batchInsertNodeStatusSnapshots(
            dtos, existingKeys.mapTo(hashSetOf()) { it.nodePublicKey to it.milliseconds }, skipNodesWithHistory = true,
        )
        return NodeStatusSnapshotInsertResult(inserted, skipped)
    }
}

internal suspend fun seedRoomSimulatorRxEntries(
    radioId: RadioId,
    buildEntries: () -> SnapshotList<RxLogEntryDTO>,
    seedBatch: suspend (SnapshotList<RxLogEntryDTO>) -> SnapshotList<SimulatorRxSeedOutcome>,
) {
    val caller = currentCoroutineContext()
    val entries = buildRxFixtures(caller, buildEntries)
    if (entries.any { it.radioId != radioId }) {
        throw PersistenceStoreException(
            PersistenceStoreError.InvalidData,
            IllegalArgumentException("Simulator RX fixture radio does not match the seed scope"),
        )
    }
    if (entries.isEmpty() || entries.map { it.id }.distinct().size != entries.size) {
        throw PersistenceStoreException(
            PersistenceStoreError.InvalidData,
            IllegalArgumentException("Simulator RX fixture batch must contain distinct identities"),
        )
    }
    caller.ensureActive()
    val outcomes = seedBatch(entries)
    caller.ensureActive()
    val inserted = outcomes.any { it.decision == SimulatorRxSeedResult.INSERTED }
    val valid = outcomes.size == entries.size && outcomes.map { it.id } == entries.map { it.id } &&
        outcomes.all { outcome ->
            when (outcome.decision) {
                SimulatorRxSeedResult.INSERTED -> outcome.presence == SimulatorRxSeedPresence.STORED
                SimulatorRxSeedResult.PRESERVED_STORED ->
                    outcome.presence == SimulatorRxSeedPresence.STORED ||
                        (inserted && outcome.presence == SimulatorRxSeedPresence.PRUNED)
                SimulatorRxSeedResult.PRESERVED_PENDING ->
                    if (inserted) {
                        outcome.presence == SimulatorRxSeedPresence.STORED ||
                            outcome.presence == SimulatorRxSeedPresence.PRUNED
                    } else {
                        outcome.presence == SimulatorRxSeedPresence.PENDING
                    }
            }
        }
    if (!valid) {
        val reason = "Simulator RX batch returned inconsistent outcomes"
        throw PersistenceStoreException(PersistenceStoreError.SaveFailed(reason), IllegalStateException(reason))
    }
}

private fun buildRxFixtures(
    caller: CoroutineContext,
    buildEntries: () -> SnapshotList<RxLogEntryDTO>,
): SnapshotList<RxLogEntryDTO> {
    caller.ensureActive()
    fun rethrowFixtureFailure(failure: Exception): Nothing {
        try {
            caller.ensureActive()
        } catch (cancellation: CancellationException) {
            if (cancellation !== failure) cancellation.addSuppressed(failure)
            throw cancellation
        }
        throw failure
    }
    val entries = try {
        buildEntries()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: PersistenceStoreException) {
        rethrowFixtureFailure(failure)
    } catch (failure: IllegalArgumentException) {
        rethrowFixtureFailure(failure)
    } catch (failure: IllegalStateException) {
        rethrowFixtureFailure(failure)
    } catch (failure: ArithmeticException) {
        rethrowFixtureFailure(failure)
    } catch (failure: DateTimeException) {
        rethrowFixtureFailure(failure)
    }
    caller.ensureActive()
    return entries
}

internal fun SimulatorDeferredFixturePlan.toRoomSimulatorIdentityPlan(): SimulatorConversationIdentityPlan =
    SimulatorConversationIdentityPlan.validate(
        families.map { family ->
            SimulatorConversationFamily(
                parent = family.parent.toRoomSimulatorParent(),
                messages = family.messages.map {
                    SimulatorMessageIdentity(it.id, it.deduplicationKey, it.replyToID)
                },
                reactions = family.reactions.map {
                    SimulatorReactionIdentity(it.id, it.messageID, it.senderName, it.emoji)
                },
                repeats = family.repeats.map {
                    SimulatorRepeatIdentity(it.id, it.messageID, it.rxLogEntryID)
                },
                previewTargets = family.previewTargets,
                summaries = family.summaries.map { SimulatorReactionSummary(it.messageID, it.summary) },
                repeatTargets = family.repeatTargets,
            ) {
                family.build().toRoomSimulatorSeed()
            }
        },
    )

internal fun SimulatorFixtureConversation.toRoomSimulatorSeed(): SimulatorConversationSeed =
    SimulatorConversationSeed(
        parent = parent.toRoomSimulatorParent(),
        messages = messages,
        reactions = reactions,
        repeats = repeats,
    )

private fun SimulatorFixtureParent.toRoomSimulatorParent(): SimulatorConversationParent = when (this) {
    is SimulatorFixtureParent.Direct -> SimulatorConversationParent.Direct(contact)
    is SimulatorFixtureParent.Channel -> SimulatorConversationParent.Channel(channel)
}
