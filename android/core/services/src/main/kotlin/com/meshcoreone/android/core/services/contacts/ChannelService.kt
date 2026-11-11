// PortedFrom: MC1Services/Sources/MC1Services/Services/ChannelService.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-405 Native adaptation: allocate through acknowledgement/save in the shared channel operation.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
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
import com.meshcoreone.android.core.protocol.session.ChannelWriteDispatch
import com.meshcoreone.android.core.protocol.session.ChannelWriteCancellationDisposition
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Channel (group) management: CRUD, secret hashing and device sync.
 *
 * State is confined under [lock]; sync exclusivity uses the source `isSyncing` flag.
 * Android additionally holds the persistence operation lane across radio reads/writes and
 * authoritative save, excluding occupant replacement, restore and admitted message dispatch.
 */
class ChannelService(
    private val session: ChannelSessionOps,
    private val dataStore: ChannelPersisting,
    private val decryptionCache: ChannelDecryptionCache?,
    private val clock: ChannelServiceClock = ChannelServiceClock.SYSTEM,
    private val random: Random = Random.Default,
    private val transportClassifier: ChannelTransportFailureClassifier = ChannelTransportFailureClassifier.NONE,
    private val dispatchFence: ChannelDispatchFence = ChannelDispatchFence.PROCESS,
    private val dispatchLease: ChannelDispatchFence.Lease? = null,
) : ChannelServiceProtocol {
    init {
        require(dispatchLease == null || dispatchLease.connection === session) {
            "Channel reconciliation requires the actual connection lease"
        }
    }
    private val logger = Logger.getLogger("com.mc1.ChannelService")
    private val lock = Any()
    private val cacheUpdates = Mutex()
    private var uncertainWrite: Throwable? = null
    private val knownSlots = mutableMapOf<RadioId, Set<UByte>>()
    private var isSyncing = false
    private var syncAccounting: CompletableDeferred<Unit>? = null
    private var requiresFreshSession = false
    private var retirementFailure: MeshCoreException.ConnectionLost? = null
    private var slotOccupantChangedHandler: SlotOccupantChangedHandler? = null

    /** Whether an RX log (decryption cache) consumer was injected at construction. */
    val hasRxLogServiceWired: Boolean get() = decryptionCache != null

    /** Let the connection owner preserve a completed partial drain before ending this generation. */
    suspend fun awaitSyncAccounting(failure: MeshCoreException.ConnectionLost? = null) {
        dispatchLease?.let(dispatchFence::retire)
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
            return channelOperation(radioId) { store, scope, completions ->
                knownSlots[radioId] = emptySet()
                dispatchLease?.let {
                    dispatchFence.forgetReconciled(it, scope, (0 until maxChannels.toInt()).map(Int::toUByte))
                }
                if (usePipelinedRead) {
                    return@channelOperation syncChannelsPipelined(radioId, maxChannels, store, scope, completions)
                }
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
                finalizeChannelSync(radioId, maxChannels, pass, pipelined = false, store, scope, completions)
            }
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
    private suspend fun syncChannelsPipelined(
        radioId: RadioId, maxChannels: UByte, store: ChannelPersisting,
        scope: ChannelIdentityWriteScope, completions: MutableList<suspend () -> Unit>,
    ): ChannelSyncResult {
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
        return finalizeChannelSync(radioId, maxChannels, pass, pipelined = true, store, scope, completions)
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
        store: ChannelPersisting, scope: ChannelIdentityWriteScope, completions: MutableList<suspend () -> Unit>,
    ): ChannelSyncResult {
        val priorChannels = fetchChannelsOrNull(radioId, store)
        if (priorChannels == null) {
            logger.warning("Pre-sync channel snapshot failed for radio ${radioId.canonicalString}; skipping draft clear for any slots this prune vacates")
        }
        val occupiedBeforeSync = priorChannels.orEmpty().map { it.index }.toSet()
        val channels = try {
            store.batchSaveChannels(radioId, pass.configured.snapshot(), pass.unconfiguredIndices.snapshot(), maxChannels)
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
        knownSlots[radioId] = (pass.configured.map { it.index } + pass.unconfiguredIndices).toSet()
        recordReconciled(scope, pass.configured.map { info -> info.index })
        completions += {
            if (changedSlots.isNotEmpty()) currentHandler()?.invoke(radioId, changedSlots.snapshotSet())
            refreshDecryptionCache(radioId)
        }
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
            return channelOperation(radioId) { store, scope, completions ->
                knownSlots[radioId] = knownSlots[radioId].orEmpty() - indices.toSet()
                dispatchLease?.let { dispatchFence.forgetReconciled(it, scope, indices) }
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
                if (pass.configured.isEmpty()) return@channelOperation ChannelSyncResult(0, pass.syncErrors.snapshot())
                val priorChannels = fetchChannelsOrNull(radioId, store).orEmpty()
                val allChannels = try {
                    store.batchSaveChannels(radioId, pass.configured.snapshot(), SnapshotList.empty(), null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.severe("Retry batch persist failed: ${error.describe()}")
                    val failures = pass.configured.map {
                        ChannelSyncError(it.index, ChannelSyncErrorType.DatabaseError, "Retry persist failed: ${error.describe()}")
                    }
                    return@channelOperation ChannelSyncResult(0, (pass.syncErrors + failures).snapshot())
                }
                val changedSlots = secretChangedIndices(priorChannels, pass.configured)
                knownSlots[radioId] = knownSlots[radioId].orEmpty() + pass.configured.map { it.index }
                recordReconciled(scope, pass.configured.map { info -> info.index })
                completions += {
                    if (changedSlots.isNotEmpty()) currentHandler()?.invoke(radioId, changedSlots.snapshotSet())
                    refreshDecryptionCache(radioId)
                }
                ChannelSyncResult(pass.configured.size.toLong(), pass.syncErrors.snapshot())
            }
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

    suspend fun createChannelWithSecret(
        radioId: RadioId, maxChannels: UByte, name: String, secret: Bytes,
        regionScope: String? = null,
        onCommitted: (EntityKey, UByte) -> Unit = { _, _ -> },
    ): ChannelDTO {
        if (!validateSecret(secret)) throw ChannelServiceError.SecretHashingFailed()
        return allocateChannel(radioId, maxChannels, name, secret, false, regionScope, onCommitted)
    }

    suspend fun joinHashtagChannel(
        radioId: RadioId, maxChannels: UByte, name: String,
        onCommitted: (EntityKey, UByte) -> Unit = { _, _ -> },
    ): ChannelDTO = allocateChannel(radioId, maxChannels, name, hashSecret(name), true, null, onCommitted)

    private suspend fun allocateChannel(
        radioId: RadioId, maxChannels: UByte, name: String, secret: Bytes, reuseName: Boolean,
        regionScope: String?,
        onCommitted: (EntityKey, UByte) -> Unit,
    ): ChannelDTO {
        var committed: EntityKey? = null
        var committedIndex: UByte? = null
        var primaryFailure: Throwable? = null
        try {
            val needsRead = channelOperation(radioId) { store, _, _ ->
                val known = knownSlots[radioId].orEmpty()
                val existing = reuseName && store.fetchChannels(radioId).any {
                    it.index in known && it.name.equals(name, ignoreCase = true)
                }
                !existing && (1 until maxChannels.toInt()).any { it.toUByte() !in known }
            }
            if (needsRead) syncChannels(radioId, maxChannels, usePipelinedRead = false)
            return channelOperation(radioId) { store, scope, completions ->
                val known = knownSlots[radioId].orEmpty()
                val channels = store.fetchChannels(radioId)
                if (reuseName) channels.firstOrNull {
                    it.index in known && it.name.equals(name, ignoreCase = true)
                }?.let { return@channelOperation it }
                val unresolved = (1 until maxChannels.toInt()).map(Int::toUByte).filterNot { it in known }
                if (unresolved.isNotEmpty()) throw ChannelMutationError.SyncIncomplete(unresolved.snapshot())
                val used = channels.map { it.index }.toSet()
                val index = (1 until maxChannels.toInt()).map(Int::toUByte).firstOrNull { it !in used }
                    ?: throw ChannelMutationError.NoAvailableSlots()
                writeChannelLocked(radioId, index, name, secret, store, scope, completions) { key ->
                    committed = key
                    committedIndex = index
                }
                regionScope?.takeIf(String::isNotEmpty)?.let { region ->
                    // Replacement must not interleave between the saved occupant and its local preference.
                    val key = requireNotNull(committed)
                    try {
                        store.setChannelFloodScope(key, ChannelFloodScope.Region(region))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        logger.log(Level.SEVERE, "Failed to persist channel flood scope after join", failure)
                    }
                }
                store.fetchChannel(radioId, index) ?: throw ChannelServiceError.ChannelNotFound()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            committed?.let { key ->
                try {
                    onCommitted(key, requireNotNull(committedIndex))
                } catch (observerFailure: Throwable) {
                    val primary = primaryFailure
                    if (primary == null) throw observerFailure
                    if (primary !== observerFailure) primary.addSuppressed(observerFailure)
                }
            }
        }
    }

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
        channelOperation(radioId) { store, scope, completions ->
            writeChannelLocked(radioId, index, name, secret, store, scope, completions)
        }
    }

    private suspend fun writeChannelLocked(
        radioId: RadioId, index: UByte, name: String, secret: Bytes,
        store: ChannelPersisting, scope: ChannelIdentityWriteScope,
        completions: MutableList<suspend () -> Unit>, onSaved: (EntityKey) -> Unit = {},
    ) {
        val truncatedName = name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES)
        val prior = store.fetchChannel(radioId, index)
        dispatchLease?.let { dispatchFence.forgetReconciled(it, scope, listOf(index)) }
        store.beginChannelMutation(radioId, index)
        val dispatch = ChannelWriteDispatch()
        var acknowledged = false
        try {
            session.setChannel(index, truncatedName, secret, dispatch)
            acknowledged = true
            val id = store.saveChannel(radioId, ChannelInfo(index, truncatedName, secret))
            onSaved(EntityKey(radioId, id))
        } catch (error: Exception) {
            quarantineUnsettledWrite(dispatch, acknowledged, error)
            if (error is MeshCoreException) throw ChannelServiceError.SessionError(error)
            throw error
        }
        knownSlots[radioId] = knownSlots[radioId].orEmpty() + index
        completions += {
            if (prior != null && prior.secret != secret) currentHandler()?.invoke(radioId, setOf(index).snapshotSet())
            refreshDecryptionCache(radioId)
        }
        if (isChannelConfigured(truncatedName, secret)) recordReconciled(scope, listOf(index))
    }

    private suspend fun refreshDecryptionCache(radioId: RadioId) {
        if (decryptionCache == null) return
        cacheUpdates.withLock { decryptionCache.updateChannels(dataStore.fetchChannels(radioId)) }
    }

    private suspend fun recordReconciled(scope: ChannelIdentityWriteScope, indices: Collection<UByte>) {
        val lease = dispatchLease ?: return
        try {
            dispatchFence.recordReconciled(lease, scope, indices)
        } catch (_: ChannelDispatchFailure.RetiredSession) {
            // A completed read/save still belongs in partial-drain accounting, never in the next lease.
            logger.info("Channel persistence completed after session retirement; dispatch remains revoked")
        }
    }

    private fun quarantineUnsettledWrite(dispatch: ChannelWriteDispatch, acknowledged: Boolean, failure: Exception) {
        val definitelyUnsent = !acknowledged && failure is CancellationException &&
            dispatch.cancellationDisposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT
        val rejected = !acknowledged && failure is MeshCoreException.DeviceError
        if (!definitelyUnsent && !rejected) {
            uncertainWrite = failure
            dispatchLease?.let { dispatchFence.quarantine(it, failure) }
        }
    }

    /**
     * The persistence facade owns the operation lane across radio IO and authoritative save.
     * Observer/cache callbacks run only after exclusion is released, never inside either gate.
     */
    private suspend fun <T> channelOperation(
        radioId: RadioId,
        operation: suspend (ChannelPersisting, ChannelIdentityWriteScope, MutableList<suspend () -> Unit>) -> T,
    ): T {
        val completions = mutableListOf<suspend () -> Unit>()
        var primaryFailure: Throwable? = null
        try {
            return dataStore.withChannelOperation { store, scope ->
                uncertainWrite?.let { throw ChannelMutationError.ReconciliationRequired(it) }
                synchronized(lock) { if (requiresFreshSession) throw ChannelServiceError.NotConnected() }
                val lease = dispatchLease
                if (lease == null) operation(store, scope, completions)
                else {
                    require(lease.token.radioId == radioId) { "Channel operation belongs to a different radio" }
                    dispatchFence.withScopedMutation(lease, scope) { operation(store, scope, completions) }
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var completionFailure: Throwable? = null
            for (completion in completions) {
                try {
                    completion()
                } catch (failure: Throwable) {
                    val primary = primaryFailure ?: completionFailure
                    if (primary == null) completionFailure = failure
                    else if (primary !== failure) primary.addSuppressed(failure)
                }
            }
            completionFailure?.let { throw it }
        }
    }

    /** Clears a channel by writing an empty name and zero secret, then deletes it locally. */
    suspend fun clearChannel(radioId: RadioId, index: UByte) = channelOperation(radioId) { store, scope, completions ->
        val channelToDelete = store.fetchChannel(radioId, index)
        dispatchLease?.let { dispatchFence.forgetReconciled(it, scope, listOf(index)) }
        store.beginChannelMutation(radioId, index)
        val dispatch = ChannelWriteDispatch()
        var acknowledged = false
        try {
            session.setChannel(index, "", Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE)), dispatch)
            acknowledged = true
            if (channelToDelete != null) store.deleteChannel(EntityKey(channelToDelete.radioId, channelToDelete.id))
            else store.deleteMessagesForChannel(radioId, index)
            store.invalidateChannelOccupant(radioId, index)
        } catch (error: Exception) {
            quarantineUnsettledWrite(dispatch, acknowledged, error)
            if (error is MeshCoreException) throw ChannelServiceError.SessionError(error)
            throw error
        }
        knownSlots[radioId] = knownSlots[radioId].orEmpty() + index
        completions += {
            currentHandler()?.invoke(radioId, setOf(index).snapshotSet())
            refreshDecryptionCache(radioId)
        }
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

    private suspend fun fetchChannelsOrNull(radioId: RadioId, store: ChannelPersisting): List<ChannelDTO>? =
        try {
            store.fetchChannels(radioId)
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
