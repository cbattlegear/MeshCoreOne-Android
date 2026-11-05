// PortedFrom: MC1/Views/Tools/TracePath/TracePathViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Trace path builder and runner (source `TracePathViewModel`). A plain state holder: state is an
 * immutable [TracePathState] in a [StateFlow]; background work runs in the injected [scope] and
 * waits on [time]. Call every member from the scope's thread (the source was `@MainActor`).
 * Batch execution lives in [TraceBatchRunner]; the hop-picker members match WP-311's
 * `HopPickerSource` shape (uncapped, so never full).
 */
class TracePathStateHolder(
    internal val scope: CoroutineScope,
    internal val time: TraceTimeSource,
    internal val strings: TracePathStrings,
    recentHopsStorage: RecentHopsStorage,
    internal val diagnostics: TraceDiagnostics,
    internal val tagSource: () -> UInt = { Random.nextInt().toUInt() },
) {
    private val mutableState = MutableStateFlow(TracePathState())
    val state: StateFlow<TracePathState> = mutableState.asStateFlow()
    internal val current: TracePathState get() = mutableState.value

    private val recents = RecentHopsStore(recentHopsStorage)
    private var currentRadioId: RadioId? = null
    private var pathSelectionRevision = 0L
    private val hashModeActionObservers = linkedSetOf<() -> Unit>()
    internal var deps: TracePathFeatureDependencies = TracePathFeatureDependencies.Disconnected
        private set

    var errorAutoClearDelay: Duration = 4.seconds
    private var errorAutoClearJob: Job? = null
    private var traceEventsJob: Job? = null

    // Trace correlation, confined to the scope's thread.
    internal var pendingTag: UInt? = null
    internal var pendingDeviceId: RadioId? = null
    internal var pendingPathHash: Bytes? = null
    internal var traceStartTime: java.time.Instant? = null
    internal var traceJob: Job? = null
    private val batch = TraceBatchRunner(this)

    /** Path_sz code for this trace: the override, else the radio's configured `pathHashMode`. */
    val effectiveTraceMode: UByte get() = current.traceHashMode ?: deps.connectedDevice()?.pathHashMode ?: 0u

    /** Bytes per hop (1, 2 or 4): power-of-two trace encoding, unlike routing's 1/2/3. */
    val hashSize: Int get() = TraceHashModes.hashSize(effectiveTraceMode)

    /** Each provider is read live at its point of use. */
    fun configure(dependencies: TracePathFeatureDependencies) {
        deps = dependencies
    }

    internal fun observeHashModeActions(onAction: () -> Unit): () -> Unit {
        hashModeActionObservers += onAction
        return { hashModeActionObservers -= onAction }
    }

    private fun notifyHashModeAction() {
        hashModeActionObservers.toList().forEach { it() }
    }

    internal fun update(transform: (TracePathState) -> TracePathState) = mutableState.update(transform)

    // MARK: - Listening

    /** Subscribes to the current graph's trace responses; a no-op while disconnected. Re-invoke per graph. */
    fun startListening() {
        traceEventsJob?.cancel()
        val events = deps.traceResponses() ?: return
        traceEventsJob = scope.launch {
            try {
                events.collect { handleTraceResponse(it.traceInfo, it.radioId) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.failure("traceResponses", error)
            }
        }
    }

    fun stopListening() {
        traceEventsJob?.cancel()
        traceEventsJob = null
    }

    // MARK: - Errors

    fun setError(message: String) {
        errorAutoClearJob?.cancel()
        update { it.copy(errorMessage = message, errorHapticTrigger = it.errorHapticTrigger + 1) }
        errorAutoClearJob = scope.launch {
            time.sleep(errorAutoClearDelay)
            update { it.copy(errorMessage = null) }
        }
    }

    fun clearError() {
        errorAutoClearJob?.cancel()
        errorAutoClearJob = null
        update { it.copy(errorMessage = null) }
    }

    // MARK: - Resolution and loading

    /** Best matching node name for [hashBytes], contacts first. */
    fun resolveHashToName(hashBytes: Bytes): String? = resolver().resolve(hashBytes)?.resolvableName

    internal fun resolver(snapshot: TracePathState = current) = TraceNodeResolver(snapshot, deps.bestAvailableLocation())

    suspend fun loadContacts(radioId: RadioId) {
        currentRadioId = radioId
        update { it.copy(recentPublicKeys = recents.load(radioId)) }
        if (deps.connectedDevice()?.supportsTraceHashSizeOverride != true) update { it.copy(traceHashMode = null) }
        val directory = deps.nodeDirectory() ?: return
        try {
            val contacts = directory.fetchContacts(radioId)
            update { state ->
                state.copy(
                    availableRepeaters = contacts.filter { it.type == ContactType.REPEATER },
                    availableRooms = contacts.filter { it.type == ContactType.ROOM },
                )
            }
            val nodes = directory.fetchDiscoveredNodes(radioId)
            update { state -> state.copy(discoveredRepeaters = nodes.filter { it.nodeType == ContactType.REPEATER }) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("loadContacts", error)
            update { it.copy(availableRepeaters = emptyList(), availableRooms = emptyList(), discoveredRepeaters = emptyList()) }
        }
    }

    // MARK: - Path editing

    private fun invalidatePathExecution() {
        pathSelectionRevision += 1
        batch.cancelBatchTrace()
    }

    fun addNode(node: RepeaterResolvable) {
        clearError()
        val hop = TracePathEditing.hopFor(node, hashSize)
        invalidatePathExecution()
        update { it.copy(outboundPath = it.outboundPath + hop, activeSavedPath = null, result = null, batchPresentationActive = false) }
    }

    /** Uniform-width rebuild for a per-trace override; the flags byte declares one size for the path. */
    fun setTraceHashMode(mode: UByte) {
        notifyHashModeAction()
        applyTraceHashMode(mode)
    }

    private fun applyTraceHashMode(mode: UByte) {
        invalidatePathExecution()
        clearError()
        update { it.copy(traceHashMode = mode) }
        val size = hashSize
        pendingPathHash = null
        update {
            it.copy(outboundPath = TracePathEditing.rehash(it.outboundPath, size), activeSavedPath = null, result = null, batchPresentationActive = false)
        }
    }

    /** Adds every resolvable code; shares [HopCodeParser] with the bulk-add preview. */
    fun addRepeatersFromCodes(input: String): CodeInputResult {
        var result = CodeInputResult()
        val appended = ArrayList<TracePathHop>()
        for (entry in classifyCodes(input)) {
            when (val status = entry.status) {
                is HopCodeStatus.WillAdd -> {
                    appended += status.hop
                    status.hop.publicKey?.let(::recordRecent)
                    result = result.copy(added = result.added + entry.code)
                }
                HopCodeStatus.AlreadyInPath -> result = result.copy(alreadyInPath = result.alreadyInPath + entry.code)
                HopCodeStatus.NotFound -> result = result.copy(notFound = result.notFound + entry.code)
                HopCodeStatus.InvalidFormat -> result = result.copy(invalidFormat = result.invalidFormat + entry.code)
                // Trace paths are uncapped, so the parser never yields PathFull here.
                HopCodeStatus.PathFull -> Unit
            }
        }
        update { it.copy(outboundPath = it.outboundPath + appended) }
        if (result.added.isNotEmpty()) {
            invalidatePathExecution()
            update { it.copy(activeSavedPath = null, result = null, batchPresentationActive = false) }
            clearError()
        }
        return result
    }

    fun removeRepeater(index: Int) {
        clearError()
        if (index !in current.outboundPath.indices) return
        invalidatePathExecution()
        update {
            it.copy(outboundPath = it.outboundPath.filterIndexed { i, _ -> i != index }, activeSavedPath = null, result = null, batchPresentationActive = false)
        }
    }

    fun moveRepeater(fromOffsets: Set<Int>, toOffset: Int) {
        clearError()
        invalidatePathExecution()
        update {
            it.copy(outboundPath = TracePathEditing.move(it.outboundPath, fromOffsets, toOffset), activeSavedPath = null, result = null, batchPresentationActive = false)
        }
    }

    fun copyPathToClipboard(clipboard: TraceClipboard) = clipboard.copy(current.fullPathString)

    /** "Tower", "Tower → Ridge", "Tower → ... → Ridge", or "Path <hex>" when nothing resolved. */
    fun generatePathName(): String = TracePathEditing.pathName(current.outboundPath, current.fullPathString, strings)

    fun setAutoReturnPath(enabled: Boolean) {
        if (current.autoReturnPath == enabled) return
        invalidatePathExecution()
        clearError()
        update {
            it.copy(autoReturnPath = enabled, activeSavedPath = null, result = null, batchPresentationActive = false)
        }
    }

    fun clearPath() {
        invalidatePathExecution()
        notifyHashModeAction()
        clearError()
        pendingPathHash = null
        update { it.copy(activeSavedPath = null, outboundPath = emptyList(), result = null, traceHashMode = null, batchPresentationActive = false) }
    }

    // MARK: - Saved paths

    /** Restores a saved path at its saved width; a mirrored palindrome turns auto-return on. */
    fun loadSavedPath(savedPath: SavedTracePathDTO) {
        invalidatePathExecution()
        notifyHashModeAction()
        pendingPathHash = null
        update { it.copy(outboundPath = emptyList(), result = null, batchPresentationActive = false) }
        if (savedPath.pathBytes.isEmpty) return
        val size = savedPath.hashSize.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val mode = if (deps.connectedDevice()?.supportsTraceHashSizeOverride == true) {
            java.lang.Long.numberOfTrailingZeros(savedPath.hashSize).toUByte()
        } else {
            null
        }
        val hops = SavedPathCodec.hopHashes(savedPath.pathBytes, size)
        val mirroredCount = SavedPathCodec.autoReturnOutboundCount(hops)
        val outboundCount = mirroredCount ?: hops.size
        val resolver = resolver()
        val outbound = hops.take(outboundCount).map { hash ->
            val match = resolver.resolve(hash)
            TracePathHop(hash, match?.publicKey, match?.resolvableName)
        }
        update {
            it.copy(traceHashMode = mode, autoReturnPath = mirroredCount != null, outboundPath = outbound, activeSavedPath = savedPath)
        }
    }

    fun handleSavedPathDeleted(id: UUID) {
        if (current.activeSavedPath?.id != id) return
        pathSelectionRevision += 1
        update { it.copy(activeSavedPath = null) }
    }

    /** Saves the current (or batch's first successful) result; batch mode appends the other runs. */
    suspend fun savePath(name: String): Boolean {
        val radioId = deps.connectedDevice()?.radioId ?: return false
        val store = deps.savedPaths() ?: return false
        val snapshot = current
        val revision = pathSelectionRevision
        val size = hashSize.toLong()
        if (snapshot.batchEnabled && snapshot.completedResults.isNotEmpty()) {
            val firstSuccess = snapshot.successfulResults.firstOrNull() ?: return false
            return persistBatch(store, radioId, name, firstSuccess, snapshot.completedResults, size, revision)
        }
        val result = snapshot.result?.takeIf { it.success } ?: return false
        return try {
            val saved = store.createSavedTracePath(radioId, name, result.tracedPathBytes, size, run(result, time.now()))
            if (pathSelectionRevision == revision && deps.connectedDevice()?.radioId == radioId) {
                update { it.copy(activeSavedPath = saved) }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("savePath", error)
            false
        }
    }

    private suspend fun persistBatch(
        store: TracePathPersisting,
        radioId: RadioId,
        name: String,
        firstSuccess: TraceResult,
        results: List<TraceResult>,
        size: Long,
        revision: Long,
    ): Boolean = try {
        val saved = store.createSavedTracePath(radioId, name, firstSuccess.tracedPathBytes, size, run(firstSuccess, time.now()))
        val key = EntityKey(radioId, saved.id)
        results.forEachIndexed { index, batchResult ->
            if (batchResult.id != firstSuccess.id) {
                store.appendTracePathRun(key, run(batchResult, time.now().plusSeconds(index.toLong())))
            }
        }
        store.fetchSavedTracePath(key)?.let { updated ->
            if (pathSelectionRevision == revision && deps.connectedDevice()?.radioId == radioId) {
                update { it.copy(activeSavedPath = updated) }
            }
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        diagnostics.failure("savePath(batch)", error)
        false
    }

    private fun run(result: TraceResult, date: java.time.Instant) =
        TracePathRunDTO(UUID.randomUUID(), date, result.success, result.durationMs, intermediateSnr(result.hops))

    internal fun intermediateSnr(hops: List<TraceHop>): SnapshotList<Double> =
        hops.filter { !it.isStartNode && !it.isEndNode }.map { it.snr }.snapshot()

    /** Most recently run saved path with exactly the current wire bytes. */
    internal suspend fun findMatchingSavedPath(): SavedTracePathDTO? {
        val radioId = deps.connectedDevice()?.radioId ?: return null
        val store = deps.savedPaths() ?: return null
        val pathBytes = current.fullPathData
        if (pathBytes.isEmpty) return null
        return try {
            store.fetchSavedTracePaths(radioId)
                .filter { it.pathBytes == pathBytes }
                .maxByOrNull { path -> path.runs.maxOfOrNull { it.date } ?: java.time.Instant.MIN }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("findMatchingSavedPath", error)
            null
        }
    }

    /** Appends a run to the saved path in the background and refreshes the active reference. */
    internal fun appendRunInBackground(savedPath: SavedTracePathDTO, run: TracePathRunDTO) {
        val store = deps.savedPaths() ?: return
        val revision = pathSelectionRevision
        scope.launch { appendRun(store, savedPath, run, revision) }
    }

    internal suspend fun appendRun(
        store: TracePathPersisting,
        savedPath: SavedTracePathDTO,
        run: TracePathRunDTO,
        revision: Long = pathSelectionRevision,
    ) {
        val key = EntityKey(savedPath.radioId, savedPath.id)
        try {
            store.appendTracePathRun(key, run)
            store.fetchSavedTracePath(key)?.let { updated ->
                update {
                    val active = it.activeSavedPath
                    if (pathSelectionRevision == revision && active != null && active.id == key.id && active.radioId == key.radioId) {
                        it.copy(activeSavedPath = updated)
                    } else it
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("appendTracePathRun", error)
        }
    }

    internal fun failedRun(): TracePathRunDTO =
        TracePathRunDTO(UUID.randomUUID(), time.now(), false, 0, SnapshotList.empty())

    // MARK: - Execution

    /** Sends one trace; the response or the firmware-derived timeout completes it. */
    suspend fun runTrace() = batch.runTrace()

    /** Runs [TracePathState.batchSize] traces in sequence (or one trace when batch mode is off). */
    suspend fun runBatchTrace() = batch.runBatchTrace()

    fun cancelBatchTrace() = batch.cancelBatchTrace()

    fun clearBatchState() = update { it.copy(currentTraceIndex = 0, completedResults = emptyList(), batchPresentationActive = false) }

    /** Turning batch mode off clears batch progress and results (the source's `didSet`). */
    fun setBatchEnabled(enabled: Boolean) {
        update { it.copy(batchEnabled = enabled) }
        if (!enabled) clearBatchState()
    }

    fun setBatchSize(size: Int) = update { it.copy(batchSize = size) }

    /** Correlates by tag (and radio when both sides know it), builds hops and records saved-path runs. */
    fun handleTraceResponse(traceInfo: TraceInfo, radioId: RadioId?) = batch.handleTraceResponse(traceInfo, radioId)

    // MARK: - Hop picker source

    val currentHopCount: Int get() = current.outboundPath.size
    val hopLimit: Int? get() = null
    val isPathFull: Boolean get() = false

    fun appendHop(node: RepeaterResolvable) {
        addNode(node)
        recordRecent(node.publicKey)
    }

    fun addCodes(input: String): CodeInputResult = addRepeatersFromCodes(input)

    fun classifyCodes(input: String): List<HopCodeClassification> {
        val resolver = resolver()
        return HopCodeParser.classify(input, hashSize, current.outboundPath.map { it.hashBytes }.toSet(), null) { hash ->
            resolver.resolve(hash)?.let { ResolvedHopCode(it.publicKey, it.resolvableName) }
        }
    }

    /** Accepts a supported uniform-width paste; rebuilds the path only when its width changes. */
    fun adoptHashSize(forPastedCodes: String) {
        if (!supportsTraceHashOverride) return
        val mode = TraceHashModes.inferredTraceHashMode(forPastedCodes) ?: return
        notifyHashModeAction()
        applySupportedTraceHashMode(mode, preserveOverride = false)
    }

    internal fun restoreTraceHashMode(mode: UByte, expectedRadioId: RadioId? = null): Boolean {
        val device = deps.connectedDevice() ?: return false
        if (expectedRadioId != null && device.radioId != expectedRadioId) return false
        if (device.supportsTraceHashSizeOverride) {
            applySupportedTraceHashMode(
                mode,
                preserveOverride = true,
                effectiveMode = current.traceHashMode ?: device.pathHashMode,
            )
        }
        return true
    }

    private val supportsTraceHashOverride: Boolean
        get() = deps.connectedDevice()?.supportsTraceHashSizeOverride == true

    private fun applySupportedTraceHashMode(
        mode: UByte,
        preserveOverride: Boolean,
        effectiveMode: UByte = effectiveTraceMode,
    ) {
        if (mode != effectiveMode) {
            applyTraceHashMode(mode)
        } else if (preserveOverride && current.traceHashMode != mode) {
            update { it.copy(traceHashMode = mode) }
        }
    }

    fun recordRecent(publicKey: Bytes) {
        val radioId = currentRadioId ?: return
        val updated = recents.record(publicKey, current.recentPublicKeys, radioId)
        update { it.copy(recentPublicKeys = updated) }
    }

    // MARK: - Test seams (the source's #if DEBUG helpers and settable vars)

    internal fun setPendingTagForTesting(tag: UInt) {
        pendingTag = tag
    }

    internal fun setPendingDeviceIdForTesting(radioId: RadioId?) {
        pendingDeviceId = radioId
    }

    internal fun setPendingPathHashForTesting(pathHash: Bytes?) {
        pendingPathHash = pathHash
    }

    internal fun setContactsForTesting(contacts: List<ContactDTO>) = update { state ->
        state.copy(
            availableRepeaters = contacts.filter { it.type == ContactType.REPEATER },
            availableRooms = contacts.filter { it.type == ContactType.ROOM },
        )
    }

    internal fun editStateForTesting(transform: (TracePathState) -> TracePathState) = update(transform)
}
