// PortedFrom: MC1Services/Sources/MC1Services/Services/ChannelService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
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
import com.meshcoreone.android.core.protocol.session.ChannelWriteCancellationDisposition
import com.meshcoreone.android.core.protocol.session.ChannelWriteDispatch
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    private val channelDispatchFence: ChannelDispatchFence,
    private val channelDispatchLease: ChannelDispatchFence.Lease,
) : ChannelServiceProtocol {
    init {
        require(channelDispatchLease.owner === channelDispatchFence &&
            channelDispatchLease.connection === session
        ) { "Channel service requires the connection's active dispatch lease" }
    }

    private data class ChannelSyncCompletion(
        val result: ChannelSyncResult,
        val channels: SnapshotList<ChannelDTO>? = null,
        val changedSlots: Set<UByte> = emptySet(),
    )

    private data class ChannelMutationCompletion(
        val changedSlots: Set<UByte>,
        val channels: SnapshotList<ChannelDTO>,
    )

    private suspend fun <T> withScopedRadioOperation(
        radioId: RadioId,
        invalidateIndices: Collection<UByte>,
        requireAvailable: Boolean = true,
        operation: suspend (ChannelPersisting, ChannelIdentityWriteScope) -> T,
    ): T {
        if (radioId != channelDispatchLease.token.radioId) throw ChannelServiceError.NotConnected()
        try {
            return dataStore.withChannelOperation { scoped, scope ->
                channelDispatchFence.withScopedMutation(channelDispatchLease, scope) {
                    if (requireAvailable) ensureMutationAvailable()
                    if (invalidateIndices.isNotEmpty()) {
                        channelDispatchFence.forgetReconciled(channelDispatchLease, scope, invalidateIndices)
                        knownSlots[radioId] = knownSlots[radioId].orEmpty() - invalidateIndices.toSet()
                    }
                    operation(scoped, scope)
                }
            }
        } catch (failure: ChannelDispatchFailure.UnsettledDispatch) {
            throw ChannelMutationError.ReconciliationRequired(failure)
        }
    }

    private val logger = Logger.getLogger("com.mc1.ChannelService")
    private val lock = Any()
    private val knownSlots = mutableMapOf<RadioId, Set<UByte>>()
    private var uncertainWrite: Throwable? = null
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
            val completion = withScopedRadioOperation(radioId, (0 until maxChannels.toInt()).map { it.toUByte() }) { scoped, scope ->
                if (usePipelinedRead) {
                    syncChannelsPipelined(radioId, maxChannels, scoped, scope)
                } else {
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
                    finalizeChannelSync(radioId, maxChannels, pass, pipelined = false, scoped = scoped, scope = scope)
                }
            }
            publishSyncCompletion(completion)
            return completion.result
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
        radioId: RadioId,
        maxChannels: UByte,
        scoped: ChannelPersisting,
        scope: ChannelIdentityWriteScope,
    ): ChannelSyncCompletion {
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
        return finalizeChannelSync(radioId, maxChannels, pass, pipelined = true, scoped = scoped, scope = scope)
    }

    /** Reads one slot into [pass]; returns the next consecutive-failure count. */
    private suspend fun readSlot(pass: ChannelReadPass, index: UByte, consecutive: Int, failureLabel: String): Int =
        try {
            val info = fetchChannelOnConnection(index)
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
        radioId: RadioId,
        maxChannels: UByte,
        pass: ChannelReadPass,
        pipelined: Boolean,
        scoped: ChannelPersisting,
        scope: ChannelIdentityWriteScope,
    ): ChannelSyncCompletion {
        val priorChannels = fetchChannelsOrNull(scoped, radioId)
        if (priorChannels == null) {
            logger.warning("Pre-sync channel snapshot failed for radio ${radioId.canonicalString}; skipping draft clear for any slots this prune vacates")
        }
        val occupiedBeforeSync = priorChannels.orEmpty().map { it.index }.toSet()
        val channels = try {
            scoped.batchSaveChannels(radioId, pass.configured.snapshot(), pass.unconfiguredIndices.snapshot(), maxChannels)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.severe("Channel batch persist failed: ${error.describe()}")
            val failures = pass.configured.map {
                ChannelSyncError(it.index, ChannelSyncErrorType.DatabaseError, "Batch persist failed: ${error.describe()}")
            }
            return ChannelSyncCompletion(ChannelSyncResult(0, (pass.syncErrors + failures).snapshot()))
        }
        channelDispatchFence.recordReconciled(channelDispatchLease, scope, pass.observedIndices)
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
        knownSlots[radioId] = pass.observedIndices
        return ChannelSyncCompletion(
            ChannelSyncResult(pass.configured.size.toLong(), pass.syncErrors.snapshot()),
            channels,
            changedSlots,
        )
    }

    private suspend fun publishSyncCompletion(completion: ChannelSyncCompletion) {
        if (completion.changedSlots.isNotEmpty()) {
            currentHandler()?.invoke(channelDispatchLease.token.radioId, completion.changedSlots.snapshotSet())
        }
        completion.channels?.let { decryptionCache?.updateChannels(it) }
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
            val completion = withScopedRadioOperation(radioId, indices) { scoped, scope ->
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
                if (pass.configured.isEmpty()) {
                    ChannelSyncCompletion(ChannelSyncResult(0, pass.syncErrors.snapshot()))
                } else {
                    val priorChannels = fetchChannelsOrNull(scoped, radioId).orEmpty()
                    val allChannels = try {
                        scoped.batchSaveChannels(radioId, pass.configured.snapshot(), SnapshotList.empty(), null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logger.severe("Retry batch persist failed: ${error.describe()}")
                        val failures = pass.configured.map {
                            ChannelSyncError(it.index, ChannelSyncErrorType.DatabaseError, "Retry persist failed: ${error.describe()}")
                        }
                        return@withScopedRadioOperation ChannelSyncCompletion(
                            ChannelSyncResult(0, (pass.syncErrors + failures).snapshot()),
                        )
                    }
                    channelDispatchFence.recordReconciled(channelDispatchLease, scope, pass.configured.map { it.index })
                    knownSlots[radioId] = knownSlots[radioId].orEmpty() + pass.configured.map { it.index }
                    val changedSlots = secretChangedIndices(priorChannels, pass.configured)
                    ChannelSyncCompletion(
                        ChannelSyncResult(pass.configured.size.toLong(), pass.syncErrors.snapshot()),
                        allChannels,
                        changedSlots,
                    )
                }
            }
            publishSyncCompletion(completion)
            return completion.result
        } finally {
            synchronized(lock) {
                isSyncing = false
                syncAccounting?.complete(Unit)
            }
        }
    }

    private suspend fun retrySlot(pass: ChannelReadPass, index: UByte, consecutive: Int): Int =
        try {
            val info = fetchChannelOnConnection(index)
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
    suspend fun fetchChannel(index: UByte): ChannelInfo? =
        try {
            channelDispatchFence.withMutation(channelDispatchLease) {
                ensureMutationAvailable()
                fetchChannelOnConnection(index)
            }
        } catch (failure: ChannelDispatchFailure.UnsettledDispatch) {
            throw ChannelMutationError.ReconciliationRequired(failure)
        }

    private suspend fun fetchChannelOnConnection(index: UByte): ChannelInfo? {
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
        radioId: RadioId,
        maxChannels: UByte,
        name: String,
        secret: Bytes,
        onCommitted: (EntityKey, UByte) -> Unit = { _, _ -> },
    ): ChannelDTO {
        if (!validateSecret(secret)) throw ChannelServiceError.SecretHashingFailed()
        return allocateChannel(radioId, maxChannels, name, secret, reuseName = false, onCommitted = onCommitted)
    }

    suspend fun joinHashtagChannel(
        radioId: RadioId,
        maxChannels: UByte,
        name: String,
        onCommitted: (EntityKey, UByte) -> Unit = { _, _ -> },
    ): ChannelDTO = allocateChannel(
        radioId, maxChannels, name, hashSecret(name), reuseName = true, onCommitted = onCommitted,
    )

    private suspend fun allocateChannel(
        radioId: RadioId,
        maxChannels: UByte,
        name: String,
        secret: Bytes,
        reuseName: Boolean,
        onCommitted: (EntityKey, UByte) -> Unit,
    ): ChannelDTO {
        var committed: PersistedWrite? = null
        try {
            val needsSync = withScopedRadioOperation(radioId, emptyList()) { scoped, _ ->
                val known = knownSlots[radioId].orEmpty()
                val existing = reuseName && scoped.fetchChannels(radioId).any {
                    it.index in known && it.name.equals(name, ignoreCase = true)
                }
                !existing && (1 until maxChannels.toInt()).any { it.toUByte() !in known }
            }
            if (needsSync) syncChannels(radioId, maxChannels, usePipelinedRead = false)

            val completion = withScopedRadioOperation(radioId, emptyList()) { scoped, scope ->
                val known = knownSlots[radioId].orEmpty()
                val channels = scoped.fetchChannels(radioId)
                if (reuseName) {
                    channels.firstOrNull { it.index in known && it.name.equals(name, ignoreCase = true) }
                        ?.let { return@withScopedRadioOperation AllocationCompletion(it, null) }
                }
                val unresolved = (1 until maxChannels.toInt()).map(Int::toUByte).filterNot { it in known }
                if (unresolved.isNotEmpty()) throw ChannelMutationError.SyncIncomplete(unresolved.snapshot())
                val used = channels.map { it.index }.toSet()
                val index = (1 until maxChannels.toInt()).map(Int::toUByte).firstOrNull { it !in used }
                    ?: throw ChannelMutationError.NoAvailableSlots()
                channelDispatchFence.forgetReconciled(channelDispatchLease, scope, listOf(index))
                knownSlots[radioId] = known - index
                val persisted = writeChannelLocked(scoped, scope, radioId, index, name, secret) {
                    committed = it
                }
                val channel = scoped.fetchChannel(radioId, index) ?: throw ChannelServiceError.ChannelNotFound()
                AllocationCompletion(
                    channel,
                    ChannelMutationCompletion(
                        if (persisted.occupantChanged) setOf(index) else emptySet(),
                        scoped.fetchChannels(radioId),
                    ),
                )
            }
            completion.mutation?.let { publishMutationCompletion(radioId, it) }
            return completion.channel
        } finally {
            committed?.let { onCommitted(it.key, it.index) }
        }
    }

    private data class PersistedWrite(val key: EntityKey, val index: UByte, val occupantChanged: Boolean)

    private data class AllocationCompletion(val channel: ChannelDTO, val mutation: ChannelMutationCompletion?)

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
        val completion = withScopedRadioOperation(radioId, listOf(index)) { scoped, scope ->
            val persisted = writeChannelLocked(scoped, scope, radioId, index, truncatedName, secret)
            ChannelMutationCompletion(
                changedSlots = if (persisted.occupantChanged) setOf(index) else emptySet(),
                channels = scoped.fetchChannels(radioId),
            )
        }
        publishMutationCompletion(radioId, completion)
    }

    private suspend fun writeChannelLocked(
        scoped: ChannelPersisting,
        scope: ChannelIdentityWriteScope,
        radioId: RadioId,
        index: UByte,
        name: String,
        secret: Bytes,
        onPersisted: (PersistedWrite) -> Unit = {},
    ): PersistedWrite {
        val truncatedName = name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES)
        scoped.beginChannelMutation(radioId, index)
        val prior = scoped.fetchChannel(radioId, index)
        val savedId = retainUncertainWrite(
            send = { dispatch -> session.setChannel(index, truncatedName, secret, dispatch) },
            reconcile = { scoped.saveChannel(radioId, ChannelInfo(index, truncatedName, secret)) },
        )
        val persisted = PersistedWrite(EntityKey(radioId, savedId), index, prior != null && prior.secret != secret)
        onPersisted(persisted)
        channelDispatchFence.recordReconciled(channelDispatchLease, scope, listOf(index))
        knownSlots[radioId] = knownSlots[radioId].orEmpty() + index
        return persisted
    }

    /** Clears a channel by writing an empty name and zero secret, then deletes it locally. */
    suspend fun clearChannel(radioId: RadioId, index: UByte) {
        val completion = withScopedRadioOperation(radioId, listOf(index)) { scoped, scope ->
            // Read the row first: after the clear write the empty-named channel may not be found.
            scoped.beginChannelMutation(radioId, index)
            val channelToDelete = scoped.fetchChannel(radioId, index)
            retainUncertainWrite(
                send = { dispatch ->
                    session.setChannel(index, "", Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE)), dispatch)
                },
                reconcile = {
            // deleteChannel wipes the slot's messages with the row; with no row, wipe them directly.
            if (channelToDelete != null) scoped.deleteChannel(EntityKey(channelToDelete.radioId, channelToDelete.id))
            else scoped.deleteMessagesForChannel(radioId, index)
                },
            )
            channelDispatchFence.recordReconciled(channelDispatchLease, scope, listOf(index))
            knownSlots[radioId] = knownSlots[radioId].orEmpty() + index
            ChannelMutationCompletion(setOf(index), scoped.fetchChannels(radioId))
        }
        publishMutationCompletion(radioId, completion)
    }

    /** Clears a channel's messages, last-message date and unread counters, keeping the channel. */
    suspend fun clearChannelMessages(radioId: RadioId, channelIndex: UByte) {
        withScopedRadioOperation(radioId, emptyList(), requireAvailable = false) { scoped, _ ->
            scoped.deleteMessagesForChannel(radioId, channelIndex)
            val channel = scoped.fetchChannel(radioId, channelIndex) ?: return@withScopedRadioOperation
            val key = EntityKey(channel.radioId, channel.id)
            scoped.updateChannelLastMessage(key, null)
            scoped.clearChannelUnreadCount(key)
            scoped.clearChannelUnreadMentionCount(key)
        }
    }

    private suspend fun ensureMutationAvailable() {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { if (requiresFreshSession) throw ChannelServiceError.NotConnected() }
        uncertainWrite?.let { throw ChannelMutationError.ReconciliationRequired(it) }
    }

    private suspend fun <T> retainUncertainWrite(
        send: suspend (ChannelWriteDispatch) -> Unit,
        reconcile: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val dispatch = ChannelWriteDispatch()
        var acknowledged = false
        try {
            send(dispatch)
            acknowledged = true
            return reconcile()
        } catch (failure: Exception) {
            val definitelyUnsent = !acknowledged && failure is CancellationException &&
                dispatch.cancellationDisposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT
            if (!definitelyUnsent && (acknowledged || failure !is MeshCoreException.DeviceError)) {
                uncertainWrite = failure
                channelDispatchFence.quarantine(channelDispatchLease, failure)
                logger.warning("Channel write outcome requires a fresh session and reconciliation: ${failure.describe()}")
            }
            when (failure) {
                is CancellationException -> throw failure
                is MeshCoreException -> throw ChannelServiceError.SessionError(failure)
                else -> throw failure
            }
        }
    }

    private suspend fun publishMutationCompletion(radioId: RadioId, completion: ChannelMutationCompletion) {
        if (completion.changedSlots.isNotEmpty()) currentHandler()?.invoke(radioId, completion.changedSlots.snapshotSet())
        decryptionCache?.updateChannels(completion.channels)
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

    private suspend fun fetchChannelsOrNull(store: ChannelPersisting, radioId: RadioId): List<ChannelDTO>? =
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
        val observedIndices: Set<UByte> get() = (configured.map { it.index } + unconfiguredIndices).toSet()

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
