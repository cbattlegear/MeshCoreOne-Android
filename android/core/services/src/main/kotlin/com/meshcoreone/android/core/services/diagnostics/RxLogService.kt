// PortedFrom: MC1Services/Sources/MC1Services/Services/RxLogService.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: the Swift actor becomes lock-confined state whose locks never span a suspension,
// matching actor reentrancy; tasks run in an injected CoroutineScope with monitor ownership checks.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.RxLogDecryptionUpdate
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.decodePathLen
import com.meshcoreone.android.core.protocol.parser.RegionScopeKey
import com.meshcoreone.android.core.protocol.session.SessionEventStreaming
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Processes RX log events, decodes channel and direct messages, and persists entries. */
class RxLogService(
    private val session: SessionEventStreaming,
    private val dataStore: RxLogServiceStore,
    private val heardRepeatsService: RxLogRepeatProcessing?,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val diagnostics: RxLogDiagnostics = RxLogDiagnostics.NONE,
    locale: Locale = Locale.getDefault(),
) {
    private val lock = Any()
    private var radioIdValue: RadioId? = null
    private var crypto = RxLogCryptoState.EMPTY
    private var contactNames: Map<Bytes, String> = emptyMap()
    private var eventMonitorJob: Job? = null
    private var regionReprocessJob: Job? = null
    private var monitorEpoch = 0L
    private var isReprocessingChannels = false
    private var isReprocessingDMs = false
    private val packetRate = RxPacketRate(clock)

    private val entryBroadcaster = RxLogStreamBroadcaster<RxLogEntryDTO>()
    internal val regionUpdateBroadcaster = RxLogStreamBroadcaster<SnapshotList<UUID>>()
    private val regions = RxLogRegionReprocessor(
        dataStore, diagnostics, clock, locale, { radioId }, regionUpdateBroadcaster,
    )
    private val decryptor = RxLogEntryDecryptor { failure ->
        log(DebugLogLevel.ERROR, "Crypto provider failure during RX log decryption: ${failure.rxLogErrorDescription()}")
    }

    val radioId: RadioId? get() = synchronized(lock) { radioIdValue }

    /** Whether a heard repeats service was injected at construction. */
    val hasHeardRepeatsServiceWired: Boolean get() = heardRepeatsService != null

    internal val knownRegions: List<String> get() = regions.knownRegions

    internal val isReprocessingRegions: Boolean get() = regions.isReprocessing

    // MARK: - Event Monitoring

    /** Start monitoring for RX log events. Cancels any previous monitor and connect-path reprocess. */
    fun startEventMonitoring(radioId: RadioId) {
        val job = synchronized(lock) {
            radioIdValue = radioId
            eventMonitorJob?.cancel()
            packetRate.reset()
            regionReprocessJob?.cancel()
            regionReprocessJob = null
            val epoch = ++monitorEpoch
            val generation = regions.generation
            scope.launch(start = CoroutineStart.LAZY) { runEventMonitor(radioId, generation, epoch) }
                .also { eventMonitorJob = it }
        }
        job.start()
    }

    private suspend fun runEventMonitor(radioId: RadioId, generation: Long, epoch: Long) {
        // Build known-regions cache (and channel/contact secrets).
        loadSecretsFromDatabase(radioId, generation)
        // Subscribe before reprocess: the session registers synchronously and only buffers for
        // existing subscribers, so awaiting a full reprocess here would drop live RX.
        val events = session.events(EventFilter.rxLogData)
        // Sibling reprocess so collection drains while store work runs.
        launchRegionReprocessJob(epoch)
        events.collect { event ->
            currentCoroutineContext().ensureActive()
            if (event is MeshEvent.RxLogData) process(event.data)
        }
    }

    /** Stores a cancellable handle for connect-path region reprocess, unless a newer monitor owns it. */
    private fun launchRegionReprocessJob(epoch: Long) {
        val job = synchronized(lock) {
            if (epoch != monitorEpoch || eventMonitorJob?.isCancelled != false) return
            regionReprocessJob?.cancel()
            scope.launch(start = CoroutineStart.LAZY) { regions.reprocessRegionEntries() }
                .also { regionReprocessJob = it }
        }
        job.start()
    }

    /** Load channel secrets and contact public keys so decryption works before sync completes. */
    private suspend fun loadSecretsFromDatabase(radioId: RadioId, generation: Long) {
        try {
            val channels = dataStore.fetchChannels(radioId)
            // Channels arrive sorted by slot index; a corrupt store can hold duplicate indices,
            // so keep the first match for a deterministic choice.
            val secrets = LinkedHashMap<UByte, Bytes>()
            val names = LinkedHashMap<UByte, String>()
            channels.forEach { secrets.putIfAbsent(it.index, it.secret); names.putIfAbsent(it.index, it.name) }
            synchronized(lock) { crypto = crypto.withChannels(secrets, names) }
            if (channels.isNotEmpty()) log(DebugLogLevel.INFO, "Loaded ${channels.size} channel secrets from database")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to load channel secrets: ${error.rxLogErrorDescription()}")
        }

        try {
            val publicKeys = dataStore.fetchContactPublicKeysByPrefix(radioId)
            if (publicKeys.isNotEmpty()) {
                val converted = RxLogEntryDecryptor.convertPublicKeysToX25519(publicKeys)
                synchronized(lock) { crypto = crypto.copy(contactPublicKeysByPrefix = converted) }
                log(DebugLogLevel.INFO, "Loaded ${publicKeys.size} contact public key prefixes from database")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to load contact public keys: ${error.rxLogErrorDescription()}")
        }

        // Source `try?`: a failed device read degrades to no known regions.
        val device = try {
            dataStore.fetchDevice(radioId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val loadedRegions = device?.knownRegions ?: emptyList()
        val resolvable = regions.applyLoadedRegions(generation, loadedRegions) ?: return
        if (loadedRegions.isNotEmpty()) {
            log(DebugLogLevel.INFO, "Loaded ${loadedRegions.size} known regions from database ($resolvable resolvable)")
        }
    }

    /** Stop monitoring events. */
    fun stopEventMonitoring() {
        synchronized(lock) {
            eventMonitorJob?.cancel()
            eventMonitorJob = null
            regionReprocessJob?.cancel()
            regionReprocessJob = null
            packetRate.reset()
        }
    }

    /** Live RF arrivals, independent of stored history, UI filters and entry retention. */
    fun packetRates(): Flow<Long> = packetRate.rates()

    /**
     * Returns a fresh multicast stream of newly persisted entries. Registration is synchronous, so
     * entries yielded after this call returns are never dropped.
     */
    fun entryStream(): Flow<RxLogEntryDTO> = entryBroadcaster.subscribe()

    /** Multicast stream of Message IDs whose region fields reprocess changed. */
    fun regionUpdateEvents(): Flow<SnapshotList<UUID>> = regionUpdateBroadcaster.subscribe()

    /** Ends every `entryStream()` / `regionUpdateEvents()` subscriber's collection. */
    fun finishEntryStream() {
        entryBroadcaster.finish()
        regionUpdateBroadcaster.finish()
    }

    // MARK: - Cache updates

    /**
     * Rebuilds the channel cache from a fresh channel list. Duplicate slot indices are a
     * programming error, as in the source's `uniqueKeysWithValues`.
     */
    suspend fun updateChannels(channels: List<ChannelDTO>) {
        require(channels.map { it.index }.toSet().size == channels.size) { "Duplicate channel index in channel list" }
        updateChannels(
            secrets = channels.associate { it.index to it.secret },
            names = channels.associate { it.index to it.name },
        )
    }

    /** Update channel cache; re-processes recent noMatchingKey entries when secrets are provided. */
    suspend fun updateChannels(secrets: Map<UByte, Bytes>, names: Map<UByte, String>) {
        synchronized(lock) { crypto = crypto.withChannels(secrets, names) }
        if (secrets.isNotEmpty()) reprocessNoMatchingKeyEntries()
    }

    /** Update contact names cache (pubkey prefix -> name). */
    fun updateContactNames(names: Map<Bytes, String>) {
        synchronized(lock) { contactNames = LinkedHashMap(names) }
    }

    /**
     * Update the device private key for DM decryption. The exported key is 64 bytes
     * `[expanded_scalar:32][nonce:32]`; DM decryption needs the 32-byte scalar (first half).
     */
    suspend fun updatePrivateKey(key: Bytes?) {
        val scalar = key?.takeIf { it.size >= X25519_SCALAR_SIZE }?.prefix(X25519_SCALAR_SIZE)
        synchronized(lock) { crypto = crypto.copy(myPrivateKey = scalar) }
        if (scalar != null) reprocessDMEntries()
    }

    /** Update contact Ed25519 public keys (converted to X25519) and re-process recent DM entries. */
    suspend fun updateContactPublicKeys(keys: Map<UByte, List<Bytes>>) {
        val converted = RxLogEntryDecryptor.convertPublicKeysToX25519(keys)
        synchronized(lock) { crypto = crypto.copy(contactPublicKeysByPrefix = converted) }
        if (converted.isNotEmpty()) reprocessDMEntries()
    }

    // MARK: - Reprocessing

    /** Re-process recent entries that failed decryption due to missing keys (reentrancy-guarded). */
    private suspend fun reprocessNoMatchingKeyEntries() {
        if (!synchronized(lock) { (!isReprocessingChannels).also { if (it) isReprocessingChannels = true } }) return
        try {
            val radioId = radioId ?: return
            val cutoff = clock.instant().minus(REPROCESS_WINDOW)
            try {
                val entries = dataStore.fetchRecentEntriesByDecryptStatus(radioId, DecryptStatus.NO_MATCHING_KEY, cutoff)
                if (entries.isEmpty()) return
                log(DebugLogLevel.INFO, "Re-processing ${entries.size} noMatchingKey entries")

                val updates = mutableListOf<RxLogDecryptionUpdate>()
                val decryptedEntries = mutableListOf<RxLogEntryDTO>()
                for (entry in entries) {
                    if (!currentCoroutineContext().isActive) break
                    val decrypted = decryptEntry(entry)
                    if (decrypted.decodedText == null) continue
                    updates += RxLogDecryptionUpdate(
                        entry.id, decrypted.channelIndex, decrypted.channelName, decrypted.senderTimestamp,
                    )
                    decryptedEntries += decrypted
                }

                // Batch update database (decodedText is transient, not persisted).
                if (updates.isNotEmpty()) {
                    dataStore.batchUpdateRxLogDecryption(radioId, updates.snapshot())
                    if (heardRepeatsService != null) {
                        for (entry in decryptedEntries) {
                            if (!currentCoroutineContext().isActive) break
                            heardRepeatsService.processForRepeats(entry)
                        }
                    }
                    log(DebugLogLevel.INFO, "Successfully re-processed ${updates.size} entries")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log(DebugLogLevel.ERROR, "Failed to re-process noMatchingKey entries: ${error.rxLogErrorDescription()}")
            }
        } finally {
            synchronized(lock) { isReprocessingChannels = false }
        }
    }

    /** Re-process recent DM entries once contact public keys or the device private key exist. */
    private suspend fun reprocessDMEntries() {
        if (!synchronized(lock) { (!isReprocessingDMs).also { if (it) isReprocessingDMs = true } }) return
        try {
            val (radioId, state) = synchronized(lock) { radioIdValue to crypto }
            val myPrivateKey = state.myPrivateKey
            if (radioId == null || myPrivateKey == null || state.contactPublicKeysByPrefix.isEmpty()) return
            val cutoff = clock.instant().minus(REPROCESS_WINDOW)
            try {
                val entries = dataStore.fetchRecentEntriesByDecryptStatus(radioId, DecryptStatus.DM_NO_MATCHING_KEY, cutoff)
                if (entries.isEmpty()) return
                log(DebugLogLevel.INFO, "Re-processing ${entries.size} DM entries for timestamp extraction")

                val updates = mutableListOf<RxLogDecryptionUpdate>()
                for (entry in entries) {
                    if (!currentCoroutineContext().isActive) break
                    val timestamp = decryptor.extractDMTimestamp(entry, myPrivateKey, state) ?: continue
                    updates += RxLogDecryptionUpdate(entry.id, null, null, timestamp)
                }
                if (updates.isNotEmpty()) {
                    dataStore.batchUpdateRxLogDecryption(radioId, updates.snapshot())
                    log(DebugLogLevel.INFO, "Successfully re-processed ${updates.size} DM entries")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log(DebugLogLevel.ERROR, "Failed to re-process DM entries: ${error.rxLogErrorDescription()}")
            }
        } finally {
            synchronized(lock) { isReprocessingDMs = false }
        }
    }

    // MARK: - Live processing

    /** Process a parsed RX log event. */
    suspend fun process(parsed: ParsedRxLogData) {
        currentCoroutineContext().ensureActive()
        val (radioId, state, names) = synchronized(lock) {
            if (radioIdValue != null && eventMonitorJob?.isCancelled == false) packetRate.record()
            Triple(radioIdValue, crypto, contactNames)
        }
        if (radioId == null) return

        val decoded = decryptor.decodeLive(parsed, state)
        if (decoded.dmFailed) log(DebugLogLevel.DEBUG, "DM decryption failed, marking as dmNoMatchingKey for reprocessing")
        else if (decoded.dmTimestamp != null) log(DebugLogLevel.DEBUG, "Decrypted direct message senderTimestamp: ${decoded.dmTimestamp}")

        // Resolve contact name from sender pubkey prefix (direct messages).
        val fromContactName = parsed.senderPubkeyPrefix?.let { senderPrefix ->
            names.entries.firstOrNull { (storedPrefix, _) ->
                storedPrefix.rxLogStartsWith(senderPrefix) || senderPrefix.rxLogStartsWith(storedPrefix)
            }?.value
        }

        stampInboundHopCount(radioId, parsed)

        val regionFields = regions.resolve(parsed.transportCode, parsed.payloadTypeBits, parsed.packetPayload)
        val dto = RxLogEntryDTO.fromParsed(
            radioId, parsed, receivedAt = clock.instant(),
            channelIndex = decoded.channelIndex, channelName = decoded.channelName,
            decryptStatus = decoded.decryptStatus, fromContactName = fromContactName,
            senderTimestamp = decoded.senderTimestamp, regionScope = regionFields.regionScope,
            regionScopeMatches = regionFields.regionScopeMatches, decodedText = decoded.decodedText,
        )

        try {
            dataStore.saveRxLogEntry(dto)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to save RX log entry: ${error.rxLogErrorDescription()}")
        }

        // Emit to stream consumers (RX log screens, live activity freshness).
        entryBroadcaster.yield(dto)

        // Inline await provides natural backpressure under high RX volume. The source call cannot throw;
        // a failing repeat processor must not end live RX monitoring, so it is logged and skipped.
        try {
            heardRepeatsService?.processForRepeats(dto)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to process heard repeats: ${error.rxLogErrorDescription()}")
        }
    }

    /**
     * The advert payload carries the sender's full pubkey at offset 0 and the advert timestamp at
     * offset 32. Only flood-routed adverts accumulate a hop path; reserved or truncated encodings
     * skip the write rather than stamping a wrong value.
     */
    private suspend fun stampInboundHopCount(radioId: RadioId, parsed: ParsedRxLogData) {
        if (parsed.payloadType != PayloadType.ADVERT || !parsed.routeType.isFlood) return
        val payload = parsed.packetPayload
        if (payload.size < ProtocolLimits.PUBLIC_KEY_SIZE) return
        val inboundHops = decodePathLen(parsed.pathLength)?.hopCount ?: return
        val advertiserPubKey = payload.prefix(ProtocolLimits.PUBLIC_KEY_SIZE)
        val advertTimestamp = if (payload.size >= ProtocolLimits.PUBLIC_KEY_SIZE + ProtocolLimits.ADVERT_TIMESTAMP_SIZE) {
            payload.readUInt32LE(ProtocolLimits.PUBLIC_KEY_SIZE)
        } else {
            null
        }
        try {
            dataStore.setInboundHopCount(radioId, advertiserPubKey, inboundHops.toLong(), advertTimestamp)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to stamp inbound hop count: ${error.rxLogErrorDescription()}")
        }
    }

    /** Load existing entries (newest first, default limit 500), re-decrypting with current secrets. */
    suspend fun loadExistingEntries(): List<RxLogEntryDTO> {
        val radioId = radioId ?: return emptyList()
        return try {
            val entries = dataStore.fetchRxLogEntries(radioId, DEFAULT_FETCH_LIMIT)
            val state = synchronized(lock) { crypto }
            entries.map { decryptor.decryptEntry(it, state) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to load RX log entries: ${error.rxLogErrorDescription()}")
            emptyList()
        }
    }

    // MARK: - Decryption

    /** Re-decrypt a batch of RX log entries against one snapshot of the current secrets. */
    fun decodedEntries(entries: List<RxLogEntryDTO>): List<RxLogEntryDTO> {
        val state = synchronized(lock) { crypto }
        return entries.map { decryptor.decryptEntry(it, state) }
    }

    /**
     * Returns a copy of [entry] with `decodedText` populated when decryption succeeds. A previously
     * decrypted channel entry uses its stored channel index first (fast path).
     */
    fun decryptEntry(entry: RxLogEntryDTO): RxLogEntryDTO =
        decryptor.decryptEntry(entry, synchronized(lock) { crypto })

    /** Clear all entries for the monitored radio. */
    suspend fun clearEntries() {
        val radioId = radioId ?: return
        try {
            dataStore.clearRxLogEntries(radioId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log(DebugLogLevel.ERROR, "Failed to clear RX log entries: ${error.rxLogErrorDescription()}")
        }
    }

    // MARK: - Region resolution (RxLogService+RegionResolution)

    /** Update known regions, rebuild the scope-key cache, and reprocess (also when empty). */
    suspend fun updateKnownRegions(regions: List<String>) = this.regions.updateKnownRegions(regions)

    /** Test seam: replace the scope-key cache (for example two names sharing one key) and reprocess. */
    internal suspend fun replaceScopeKeyCacheAndReprocess(cache: List<RegionScopeKey>) =
        regions.replaceScopeKeyCacheAndReprocess(cache)

    internal suspend fun reprocessRegionEntries() = regions.reprocessRegionEntries()

    private fun log(level: DebugLogLevel, message: String) = diagnostics.log(level, LOG_CATEGORY, message)

    companion object {
        const val LOG_CATEGORY = "RxLogService"
        const val DEFAULT_FETCH_LIMIT = 500L
        val REPROCESS_WINDOW: Duration = Duration.ofSeconds(60)
        private const val X25519_SCALAR_SIZE = 32
    }
}

internal fun Bytes.rxLogStartsWith(prefix: Bytes): Boolean =
    prefix.size <= size && (0 until prefix.size).all { this[it] == prefix[it] }

internal val RouteType.isDirectRoute: Boolean get() = this == RouteType.DIRECT || this == RouteType.TC_DIRECT
