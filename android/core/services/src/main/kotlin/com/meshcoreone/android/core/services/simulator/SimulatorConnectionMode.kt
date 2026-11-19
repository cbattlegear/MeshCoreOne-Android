// PortedFrom: MC1Services/Sources/MC1Services/Simulator/SimulatorConnectionMode.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.DeviceDTO
import java.time.Clock
import java.time.Instant
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connection mode for the simulator and demo mode on a device. Provides mock data and a simulated connection
 * without requiring real hardware: nothing here opens a radio or network link.
 *
 * The Swift type is `@MainActor`; here `isConnected` is lock-confined and never held across a suspension,
 * and seed passes are serialized by a coroutine [Mutex] so two concurrent connects cannot interleave the
 * check-then-insert RX-log and snapshot steps. [clock] supplies the single `now` each seed pass is built
 * from and the simulated device's `lastConnected`.
 */
class SimulatorConnectionMode(
    private val clock: Clock = Clock.systemUTC(),
    private val connectDelay: Duration = DEFAULT_CONNECT_DELAY,
) {
    private val lock = Any()
    private var connected = false
    private val seedMutex = Mutex()

    /** Whether the simulator is "connected". */
    val isConnected: Boolean get() = synchronized(lock) { connected }

    /** The simulated device while connected. */
    val device: DeviceDTO? get() = if (isConnected) MockDataProvider.simulatorDevice(clock.instant()) else null

    /**
     * Simulates connecting to the simulator device after a brief delay. Swift's `try? await Task.sleep`
     * swallows cancellation and connects anyway; here cancellation propagates and leaves the mode
     * disconnected.
     */
    suspend fun connect() {
        logger.info("Simulator: connecting to mock device")
        delay(connectDelay)
        synchronized(lock) { connected = true }
        logger.info("Simulator: connected")
    }

    /** Simulates disconnecting. */
    suspend fun disconnect() {
        logger.info("Simulator: disconnecting")
        synchronized(lock) { connected = false }
    }

    /**
     * Seeds the data store with mock data. Reference stores replay messages before their dependent rows;
     * the native store can atomically create complete conversations or preserve compatible existing ones.
     * Seed IDs are fixture identities, not radio authority. Store failures and cancellation propagate.
     */
    suspend fun seedDataStore(dataStore: SimulatorSeedStore) {
        seedMutex.withLock { seed(dataStore, clock.instant()) }
    }

    private suspend fun seed(dataStore: SimulatorSeedStore, now: Instant) {
        dataStore.saveDevice(MockDataProvider.simulatorDevice(now))

        val plan by lazy { simulatorDeferredFixturePlan(now) }
        val result = dataStore.seedConversations({ plan }) {
            replayReference(dataStore, now)
        }
        when (result) {
            SimulatorFixtureSeedResult.ReferenceReplay -> Unit
            is SimulatorFixtureSeedResult.Durable -> check(
                result.created >= 0 && result.preservedExisting >= 0 &&
                    result.created.toLong() + result.preservedExisting.toLong() == plan.families.size.toLong(),
            ) { "Simulator seed result does not account for every conversation" }
        }

        seedRxLogEntries(dataStore, now)
        seedNodeStatusSnapshots(dataStore, now)

        when (result) {
            SimulatorFixtureSeedResult.ReferenceReplay ->
                logger.info("Simulator: replayed fixture contacts and channels")
            is SimulatorFixtureSeedResult.Durable -> logger.info(
                "Simulator: created ${result.created} complete fixture conversations; " +
                    "preserved ${result.preservedExisting} existing conversations, which may be incomplete",
            )
        }
    }

    private suspend fun replayReference(
        dataStore: SimulatorSeedStore,
        now: Instant,
    ) {
        val radioId = MockDataProvider.simulatorRadioId
        val contacts = MockDataProvider.contacts(now)
        for (contact in contacts) dataStore.seedContact(contact)
        val channels = MockDataProvider.channels(now)
        for (channel in channels) dataStore.saveChannel(channel)

        for (contact in contacts) {
            for (message in MockDataProvider.messages(contact.id, now)) dataStore.saveMessage(message)
        }
        for (channel in channels) {
            for (message in MockDataProvider.channelMessages(channel.index, now)) dataStore.saveMessage(message)
        }

        // Link previews render offline from the message-owned columns, which saveMessage drops.
        for (seed in MockDataProvider.linkPreviewSeeds) {
            dataStore.updateMessageLinkPreview(
                EntityKey(radioId, seed.messageID), seed.url, seed.title, seed.imageData, iconData = null, fetched = true,
            )
        }

        // Reaction rows feed the reactor-detail list; the summary drives the badge.
        for (reacted in MockDataProvider.reactedMessages) {
            for (reaction in MockDataProvider.reactions(reacted.messageID, now)) dataStore.saveReaction(reaction)
            dataStore.updateMessageReactionSummary(EntityKey(radioId, reacted.messageID), reacted.summary)
        }

        for (messageID in MockDataProvider.messagesWithRepeats) {
            for (repeatRow in MockDataProvider.messageRepeats(messageID, now)) dataStore.saveMessageRepeat(radioId, repeatRow)
        }
    }

    /** Delegates fixture identity decisions to the store's simulator-specific RX boundary. */
    private suspend fun seedRxLogEntries(dataStore: SimulatorSeedStore, now: Instant) {
        dataStore.seedRxLogEntries(MockDataProvider.simulatorRadioId) { MockDataProvider.rxLogEntries(now) }
    }

    /**
     * Seeds a node's GPS track once so the location History list and map have content to render. Skipped when
     * the node already has a snapshot, so the now-relative timestamps aren't restacked into a duplicate track on
     * every reconnect. Snapshot rows are keyed by node key plus timestamp, so re-seeding would otherwise append.
     */
    private suspend fun seedNodeStatusSnapshots(dataStore: SimulatorSeedStore, now: Instant) {
        val nodeKey = MockDataProvider.locationHistoryNodePublicKey
        if (dataStore.fetchLatestNodeStatusSnapshot(nodeKey) != null) return
        val existingKeys = dataStore.existingNodeStatusSnapshotKeys()
        dataStore.batchInsertNodeStatusSnapshots(MockDataProvider.nodeStatusSnapshots(now), existingKeys)
    }

    companion object {
        /** Swift's brief connect delay. */
        val DEFAULT_CONNECT_DELAY: Duration = 200.milliseconds

        private val logger: Logger = Logger.getLogger("com.mc1.SimulatorConnectionMode")
    }
}
