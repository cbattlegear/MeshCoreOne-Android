// PortedFrom: MC1Services/Sources/MC1Services/Services/ChannelService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.sha256
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Channel (group) management: CRUD, secret hashing and device sync.
 *
 * The source actor is reentrant, so only its two state fields are confined here (under [lock]);
 * suspension points interleave with other calls exactly as actor awaits do. Sync exclusivity is
 * the source `isSyncing` flag, checked and set atomically.
 */
class ChannelService(
    private val session: ChannelSessionOps,
    private val dataStore: ChannelPersisting,
    private val decryptionCache: ChannelDecryptionCache?,
    private val clock: ChannelServiceClock = ChannelServiceClock.SYSTEM,
    private val random: Random = Random.Default,
    private val transportClassifier: ChannelTransportFailureClassifier = ChannelTransportFailureClassifier.NONE,
) : ChannelServiceProtocol {
    private val logger = Logger.getLogger("com.mc1.ChannelService")
    private val lock = Any()
    private var isSyncing = false
    private var syncAccounting: CompletableDeferred<Unit>? = null
    private var requiresFreshSession = false
    private var retirementFailure: MeshCoreException.ConnectionLost? = null
    private var slotOccupantChangedHandler: SlotOccupantChangedHandler? = null

    /** Whether an RX log (decryption cache) consumer was injected at construction. */
    val hasRxLogServiceWired: Boolean get() = decryptionCache != null

    /** Let the connection owner preserve a completed partial drain before ending this generation. */
    suspend fun awaitSyncAccounting(failure: MeshCoreException.ConnectionLost? = null) {
        val accounting = synchronized(lock) {
            requiresFreshSession = true
            if (failure != null) retirementFailure = failure
            syncAccounting
        }
        accounting?.await()
    }

    // region Sync

    override suspend fun syncChannels(radioId: RadioId, maxChannels: UByte, usePipelinedRead: Boolean): ChannelSyncResult {
        synchronized(lock) {
            if (requiresFreshSession) throw ChannelServiceError.NotConnected()
            if (isSyncing) {
                logger.warning("Channel sync already in progress, rejecting concurrent request")
                throw ChannelServiceError.SyncAlreadyInProgress()
            }
            isSyncing = true
            syncAccounting = CompletableDeferred()
        }
        try {
            if (usePipelinedRead) return syncChannelsPipelined(radioId, maxChannels)
            val pass = ChannelReadPass()
            var consecutiveTimeouts = 0
            val limit = maxChannels.toInt()
            for (slot in 0 until limit) {
                if (consecutiveTimeouts >= SYNC_CIRCUIT_BREAKER_THRESHOLD) {
                    logger.severe("Circuit breaker open: $consecutiveTimeouts consecutive timeouts, aborting sync")
                    for (remaining in slot until limit) pass.skip(remaining.toUByte(), "Skipped due to circuit breaker")
                    break
                }
                consecutiveTimeouts = readSlot(pass, slot.toUByte(), consecutiveTimeouts, "Failed to sync channel")
            }
            // Indices skipped by the circuit breaker land in neither list, so they are untouched.
            return finalizeChannelSync(radioId, maxChannels, pass, pipelined = false)
        } finally {
            synchronized(lock) {
                isSyncing = false
                syncAccounting?.complete(Unit)
            }
        }
    }

    /**
     * A partial drain commits only observed slots. Unanswered requests require a new physical
     * connection; the runtime owns teardown, reconnect and the next generation's full channel read.
     */
    private suspend fun syncChannelsPipelined(radioId: RadioId, maxChannels: UByte): ChannelSyncResult {
        val pass = ChannelReadPass()
        // A hard send failure throws here, aborting with nothing persisted.
        val fetched = session.getChannels((0 until maxChannels.toInt()).map { it.toUByte() })
        for (info in fetched.received) {
            if (isChannelConfigured(info.name, info.secret)) pass.addConfigured(info, info.index)
            else pass.unconfiguredIndices += info.index
        }
        if (fetched.missing.isNotEmpty()) synchronized(lock) { requiresFreshSession = true }
        for (index in fetched.missing) {
            pass.syncErrors += ChannelSyncError(
                index, ChannelSyncErrorType.TransportError, "Fresh physical connection required before reading channel ${index.toInt()}",
            )
        }
        return finalizeChannelSync(radioId, maxChannels, pass, pipelined = true)
    }

    /** Reads one slot into [pass]; returns the next consecutive-failure count. */
    private suspend fun readSlot(pass: ChannelReadPass, index: UByte, consecutive: Int, failureLabel: String): Int =
        try {
            val info = fetchChannel(index)
            if (info != null) pass.addConfigured(info, index) else pass.unconfiguredIndices += index
            0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val syncError = classifyError(error, index)
            logger.warning("$failureLabel ${index.toInt()}: ${syncError.description}")
            pass.syncErrors += syncError
            nextConsecutiveFailureCount(syncError, consecutive)
        }

    /**
     * Persists a completed read pass in one transaction and reports the result. Shared by the
     * serial and pipelined paths so their classification-to-persist tail cannot drift.
     */
    private suspend fun finalizeChannelSync(
        radioId: RadioId, maxChannels: UByte, pass: ChannelReadPass, pipelined: Boolean,
    ): ChannelSyncResult {
        val priorChannels = fetchChannelsOrNull(radioId)
        if (priorChannels == null) {
            logger.warning("Pre-sync channel snapshot failed for radio ${radioId.canonicalString}; skipping draft clear for any slots this prune vacates")
        }
        val occupiedBeforeSync = priorChannels.orEmpty().map { it.index }.toSet()
        val channels = try {
            dataStore.batchSaveChannels(radioId, pass.configured.snapshot(), pass.unconfiguredIndices.snapshot(), maxChannels)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.severe("Channel batch persist failed: ${error.describe()}")
            val failures = pass.configured.map {
                ChannelSyncError(it.index, ChannelSyncErrorType.DatabaseError, "Batch persist failed: ${error.describe()}")
            }
            return ChannelSyncResult(0, (pass.syncErrors + failures).snapshot())
        }
        val label = if (pipelined) "Channel sync diagnostics (pipelined)" else "Channel sync diagnostics"
        logger.info(
            "$label: synced=${pass.configured.size}, unconfigured=${pass.unconfiguredIndices.size}, " +
                "emptyNameWithSecret=${pass.emptyNameWithSecretIndices.size}, errors=${pass.syncErrors.size}",
        )
        if (pass.emptyNameWithSecretIndices.isNotEmpty()) {
            logger.warning("Channel sync detected empty-name channels with non-zero secrets at indices: ${pass.emptyNameWithSecretIndices}")
        }
        val vacatedSlots = occupiedBeforeSync - channels.map { it.index }.toSet()
        val changedSlots = vacatedSlots + secretChangedIndices(priorChannels.orEmpty(), pass.configured)
        if (changedSlots.isNotEmpty()) currentHandler()?.invoke(radioId, changedSlots.snapshotSet())
        decryptionCache?.updateChannels(channels)
        return ChannelSyncResult(pass.configured.size.toLong(), pass.syncErrors.snapshot())
    }

    /** Retries only the channels that previously failed. Never deletes or prunes slots. */
    override suspend fun retryFailedChannels(radioId: RadioId, indices: SnapshotList<UByte>): ChannelSyncResult {
        synchronized(lock) {
            if (requiresFreshSession) throw ChannelServiceError.NotConnected()
            if (isSyncing) throw ChannelServiceError.SyncAlreadyInProgress()
            if (indices.isEmpty()) return ChannelSyncResult(0, SnapshotList.empty())
            isSyncing = true
            syncAccounting = CompletableDeferred()
        }
        try {
            logger.info("Retrying ${indices.size} failed channels: $indices")
            // Brief delay before retry to allow transient issues to resolve.
            clock.sleep(RETRY_DELAY_MS.milliseconds)
            val pass = ChannelReadPass()
            var consecutiveTimeouts = 0
            for (index in indices) {
                if (consecutiveTimeouts >= RETRY_CIRCUIT_BREAKER_THRESHOLD) {
                    logger.warning("Retry circuit breaker open: $consecutiveTimeouts consecutive timeouts, stopping retry")
                    for (remaining in indices.dropWhile { it != index }) pass.skip(remaining, "Skipped due to retry circuit breaker")
                    break
                }
                consecutiveTimeouts = retrySlot(pass, index, consecutiveTimeouts)
            }
            // Nothing recovered: skip the persist round-trip and the handler notification.
            if (pass.configured.isEmpty()) return ChannelSyncResult(0, pass.syncErrors.snapshot())
            val priorChannels = fetchChannelsOrNull(radioId).orEmpty()
            val allChannels = try {
                dataStore.batchSaveChannels(radioId, pass.configured.snapshot(), SnapshotList.empty(), null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.severe("Retry batch persist failed: ${error.describe()}")
                val failures = pass.configured.map {
                    ChannelSyncError(it.index, ChannelSyncErrorType.DatabaseError, "Retry persist failed: ${error.describe()}")
                }
                return ChannelSyncResult(0, (pass.syncErrors + failures).snapshot())
            }
            val changedSlots = secretChangedIndices(priorChannels, pass.configured)
            if (changedSlots.isNotEmpty()) currentHandler()?.invoke(radioId, changedSlots.snapshotSet())
            decryptionCache?.updateChannels(allChannels)
            return ChannelSyncResult(pass.configured.size.toLong(), pass.syncErrors.snapshot())
        } finally {
            synchronized(lock) {
                isSyncing = false
                syncAccounting?.complete(Unit)
            }
        }
    }

    private suspend fun retrySlot(pass: ChannelReadPass, index: UByte, consecutive: Int): Int =
        try {
            val info = fetchChannel(index)
            if (info != null) {
                pass.configured += info
                logger.info("Retry succeeded for channel ${index.toInt()}")
            }
            0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val syncError = classifyError(error, index)
            logger.warning("Retry failed for channel ${index.toInt()}: ${syncError.description}")
            pass.syncErrors += syncError
            nextConsecutiveFailureCount(syncError, consecutive)
        }

    /**
     * Fetches one channel with exponential-backoff retry on timeouts (500, 1000 ms with
     * +/-100 ms jitter). Returns null when the slot is unconfigured or the device reports it
     * not found.
     */
    suspend fun fetchChannel(index: UByte): ChannelInfo? {
        var lastError: MeshCoreException = MeshCoreException.Timeout()
        for (attempt in 1..FETCH_MAX_ATTEMPTS) {
            synchronized(lock) {
                if (requiresFreshSession) {
                    // Preserve the admitted query's correlation failure without another protocol call.
                    if (attempt > 1) retirementFailure?.let { throw ChannelServiceError.SessionError(it) }
                    throw ChannelServiceError.NotConnected()
                }
            }
            try {
                val info = session.getChannel(index)
                if (info.index != index) {
                    logger.severe("Channel index mismatch: requested ${index.toInt()}, received ${info.index.toInt()}")
                    throw ChannelServiceError.InvalidChannelIndex()
                }
                if (!isChannelConfigured(info.name, info.secret)) return null
                if (info.name.isEmpty()) {
                    logger.warning("Channel ${index.toInt()} has empty name with non-zero secret; treating as configured")
                }
                return ChannelInfo(info.index, info.name, info.secret)
            } catch (error: MeshCoreException) {
                if (error is MeshCoreException.DeviceError && error.code == ErrorCode.NOT_FOUND.rawValue) return null
                if (error is MeshCoreException.Timeout) {
                    lastError = error
                    if (attempt < FETCH_MAX_ATTEMPTS) {
                        val delayMs = FETCH_BASE_DELAY_MS * (1 shl (attempt - 1)) +
                            random.nextInt(-FETCH_JITTER_MS, FETCH_JITTER_MS + 1)
                        logger.info("Channel ${index.toInt()} fetch timeout, retry $attempt/$FETCH_MAX_ATTEMPTS in ${delayMs}ms")
                        clock.sleep(delayMs.milliseconds)
                        continue
                    }
                }
                throw ChannelServiceError.SessionError(error)
            }
        }
        throw ChannelServiceError.SessionError(lastError)
    }

    // endregion

    // region Writes

    /** Sets (creates or updates) a channel on the device, hashing [passphrase] into its secret. */
    suspend fun setChannel(radioId: RadioId, index: UByte, name: String, passphrase: String) =
        writeChannel(radioId, index, name, hashSecret(passphrase))

    /** Sets a channel with a pre-computed 16-byte secret. */
    suspend fun setChannelWithSecret(radioId: RadioId, index: UByte, name: String, secret: Bytes) {
        if (!validateSecret(secret)) throw ChannelServiceError.SecretHashingFailed()
        writeChannel(radioId, index, name, secret)
    }

    /** Writes to the radio, mirrors locally, and notifies when an existing occupant's secret changed. */
    private suspend fun writeChannel(radioId: RadioId, index: UByte, name: String, secret: Bytes) {
        val truncatedName = name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES)
        val prior = dataStore.fetchChannel(radioId, index)
        try {
            session.setChannel(index, truncatedName, secret)
            dataStore.saveChannel(radioId, ChannelInfo(index, truncatedName, secret))
            if (prior != null && prior.secret != secret) currentHandler()?.invoke(radioId, setOf(index).snapshotSet())
            decryptionCache?.updateChannels(dataStore.fetchChannels(radioId))
        } catch (error: MeshCoreException) {
            throw ChannelServiceError.SessionError(error)
        }
    }

    /** Clears a channel by writing an empty name and zero secret, then deletes it locally. */
    suspend fun clearChannel(radioId: RadioId, index: UByte) {
        // Read the row first: after the clear write the empty-named channel may not be found.
        val channelToDelete = dataStore.fetchChannel(radioId, index)
        try {
            session.setChannel(index, "", Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE)))
        } catch (error: MeshCoreException) {
            throw ChannelServiceError.SessionError(error)
        }
        // deleteChannel wipes the slot's messages with the row; with no row, wipe them directly.
        if (channelToDelete != null) dataStore.deleteChannel(EntityKey(channelToDelete.radioId, channelToDelete.id))
        else dataStore.deleteMessagesForChannel(radioId, index)
        currentHandler()?.invoke(radioId, setOf(index).snapshotSet())
        decryptionCache?.updateChannels(dataStore.fetchChannels(radioId))
    }

    /** Clears a channel's messages, last-message date and unread counters, keeping the channel. */
    suspend fun clearChannelMessages(radioId: RadioId, channelIndex: UByte) {
        dataStore.deleteMessagesForChannel(radioId, channelIndex)
        val channel = dataStore.fetchChannel(radioId, channelIndex) ?: return
        val key = EntityKey(channel.radioId, channel.id)
        dataStore.updateChannelLastMessage(key, null)
        dataStore.clearChannelUnreadCount(key)
        dataStore.clearChannelUnreadMentionCount(key)
    }

    // endregion

    // region Local reads and public channel

    suspend fun getChannels(radioId: RadioId): SnapshotList<ChannelDTO> = dataStore.fetchChannels(radioId)

    suspend fun getChannel(radioId: RadioId, index: UByte): ChannelDTO? = dataStore.fetchChannel(radioId, index)

    /** Channels that have messages (for the chat list). */
    suspend fun getActiveChannels(radioId: RadioId): SnapshotList<ChannelDTO> =
        dataStore.fetchChannels(radioId).filter { it.lastMessageDate != null }.snapshot()

    /** Creates or resets the public channel (slot 0) with the well-known key. */
    suspend fun setupPublicChannel(radioId: RadioId) =
        setChannelWithSecret(radioId, 0u, PUBLIC_CHANNEL_NAME, PUBLIC_CHANNEL_SECRET)

    suspend fun hasPublicChannel(radioId: RadioId): Boolean = dataStore.fetchChannel(radioId, 0u) != null

    // endregion

    // region Handlers and helpers

    /** Sets the callback for slots whose occupant changed so per-slot UI state can be dropped. */
    fun setSlotOccupantChangedHandler(handler: SlotOccupantChangedHandler) {
        synchronized(lock) { slotOccupantChangedHandler = handler }
    }

    private fun currentHandler(): SlotOccupantChangedHandler? = synchronized(lock) { slotOccupantChangedHandler }

    private suspend fun fetchChannelsOrNull(radioId: RadioId): List<ChannelDTO>? =
        try {
            dataStore.fetchChannels(radioId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }

    /** Source `classifyError(_:forIndex:)`. */
    private fun classifyError(error: Throwable, index: UByte): ChannelSyncError {
        if (error is ChannelServiceError) return classifyServiceError(error, index)
        classifyTransportFailure(error)?.let { (type, description) -> return ChannelSyncError(index, type, description) }
        transportClassifier.classify(error)?.let { return ChannelSyncError(index, it, error.describe()) }
        return ChannelSyncError(index, ChannelSyncErrorType.Unknown, error.describe())
    }

    private fun classifyServiceError(error: ChannelServiceError, index: UByte): ChannelSyncError = when (error) {
        is ChannelServiceError.CircuitBreakerOpen -> ChannelSyncError(index, ChannelSyncErrorType.CircuitBreaker, error.describe())
        is ChannelServiceError.SessionError -> when (val mesh = error.error) {
            is MeshCoreException.Timeout -> ChannelSyncError(index, ChannelSyncErrorType.Timeout, "Request timed out")
            is MeshCoreException.DeviceError -> ChannelSyncError(index, ChannelSyncErrorType.DeviceError(mesh.code), mesh.describe())
            else -> ChannelSyncError(index, ChannelSyncErrorType.Unknown, mesh.describe())
        }
        is ChannelServiceError.SaveFailed -> ChannelSyncError(index, ChannelSyncErrorType.DatabaseError, "Save failed: ${error.reason}")
        else -> ChannelSyncError(index, ChannelSyncErrorType.Unknown, error.describe())
    }

    private fun nextConsecutiveFailureCount(error: ChannelSyncError, currentCount: Int): Int =
        if (error.countsTowardCircuitBreaker) currentCount + 1 else 0

    /** Per-pass accumulators, confined to the single coroutine running the pass. */
    private class ChannelReadPass {
        val syncErrors = mutableListOf<ChannelSyncError>()
        val configured = mutableListOf<ChannelInfo>()
        val unconfiguredIndices = mutableListOf<UByte>()
        val emptyNameWithSecretIndices = mutableListOf<UByte>()

        fun addConfigured(info: ChannelInfo, index: UByte) {
            configured += info
            if (info.name.isEmpty()) emptyNameWithSecretIndices += index
        }

        fun skip(index: UByte, description: String) {
            syncErrors += ChannelSyncError(index, ChannelSyncErrorType.CircuitBreaker, description)
        }
    }

    // endregion

    companion object {
        private const val SYNC_CIRCUIT_BREAKER_THRESHOLD = 3
        private const val RETRY_CIRCUIT_BREAKER_THRESHOLD = 2
        private const val RETRY_DELAY_MS = 500
        private const val FETCH_MAX_ATTEMPTS = 3
        private const val FETCH_BASE_DELAY_MS = 500
        private const val FETCH_JITTER_MS = 100
        private const val PUBLIC_CHANNEL_NAME = "Public"
        private val PUBLIC_CHANNEL_SECRET = Bytes.of(
            0x8B, 0x33, 0x87, 0xE9, 0xC5, 0xCD, 0xEA, 0x6A,
            0xC9, 0xE5, 0xED, 0xBA, 0xA1, 0x15, 0xCD, 0x72,
        )

        /** First 16 bytes of SHA-256 over the UTF-8 passphrase; an empty passphrase is all zeros. */
        fun hashSecret(passphrase: String): Bytes {
            if (passphrase.isEmpty()) return Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE))
            return sha256(Bytes.utf8(passphrase)).prefix(ProtocolLimits.CHANNEL_SECRET_SIZE)
        }

        fun validateSecret(secret: Bytes): Boolean = secret.size == ProtocolLimits.CHANNEL_SECRET_SIZE

        /** A slot is unconfigured only when the name is empty and the secret is all zeros. */
        fun isChannelConfigured(name: String, secret: Bytes): Boolean =
            name.isNotEmpty() || !secret.all { it == 0.toUByte() }

        /**
         * Builds a shareable `meshcore://channel/add` URI. Always includes `name` and `secret`;
         * adds `region_scope` only for a non-empty [ChannelFloodScope.Region].
         */
        fun exportChannelURI(name: String, secret: Bytes, floodScope: ChannelFloodScope = ChannelFloodScope.Inherit): String =
            ChannelURIEncoding.channelAddURI(name, secret, floodScope)

        /** Source `secretChangedIndices`: slots with no prior row are first sightings, not changes. */
        internal fun secretChangedIndices(prior: List<ChannelDTO>, configured: List<ChannelInfo>): Set<UByte> {
            val priorSecrets = LinkedHashMap<UByte, Bytes>()
            prior.forEach { priorSecrets.putIfAbsent(it.index, it.secret) }
            return configured.mapNotNull { info ->
                val priorSecret = priorSecrets[info.index]
                if (priorSecret == null || priorSecret == info.secret) null else info.index
            }.toSet()
        }
    }
}
