// PortedFrom: MC1/Views/Tools/NodeDiscovery/NodeDiscoveryViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.discovery

import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.event.DiscoverResponse
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.tools.trace.SourceCollation
import com.meshcoreone.android.feature.tools.trace.TraceDiagnostics
import com.meshcoreone.android.feature.tools.trace.TraceTimeSource
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NodeDiscoveryState(
    val results: List<NodeDiscoveryResult> = emptyList(),
    val isScanning: Boolean = false,
    val errorMessage: String? = null,
    val filter: NodeDiscoveryFilter = NodeDiscoveryFilter.REPEATERS,
    val sortOrder: NodeDiscoverySortOrder = NodeDiscoverySortOrder.SNR,
    val scanStartHapticTrigger: Long = 0,
    val scanSuccessHapticTrigger: Long = 0,
    val scanEmptyHapticTrigger: Long = 0,
    val addedPublicKeys: Set<Bytes> = emptySet(),
    val addingPublicKey: Bytes? = null,
    val addSuccessHapticTrigger: Long = 0,
    val addErrorHapticTrigger: Long = 0,
) {
    fun isAdded(publicKey: Bytes): Boolean = publicKey in addedPublicKeys

    /** Results of the selected filter: strongest SNR first, or by name (case-insensitive). */
    fun sortedResults(locale: Locale = Locale.getDefault()): List<NodeDiscoveryResult> {
        val filtered = results.filter { it.scanFilter == filter }
        return when (sortOrder) {
            NodeDiscoverySortOrder.SNR -> filtered.sortedByDescending { it.snr }
            NodeDiscoverySortOrder.NAME -> {
                val names = SourceCollation.localizedCaseInsensitive(locale)
                filtered.sortedWith { left, right -> names.compare(left.name, right.name) }
            }
        }
    }
}

/**
 * Node discovery scan and add (source `NodeDiscoveryViewModel`). Call from the scope's thread.
 * A scan listens for 15 s of [time]; its timeout cancels the scan, which then finishes normally.
 */
class NodeDiscoveryStateHolder(
    private val scope: CoroutineScope,
    private val time: TraceTimeSource,
    private val strings: NodeDiscoveryStrings,
    private val diagnostics: TraceDiagnostics,
) {
    private val mutableState = MutableStateFlow(NodeDiscoveryState())
    val state: StateFlow<NodeDiscoveryState> = mutableState.asStateFlow()
    private val current: NodeDiscoveryState get() = mutableState.value

    private var deps: NodeDiscoveryFeatureDependencies? = null
    private var scanJob: Job? = null
    private var timeoutJob: Job? = null
    private var scanGeneration = 0L
    private var namesByKey: Map<Bytes, String> = emptyMap()

    fun configure(dependencies: NodeDiscoveryFeatureDependencies) {
        deps = dependencies
    }

    fun setFilter(filter: NodeDiscoveryFilter) = mutableState.update { it.copy(filter = filter) }

    fun setSortOrder(order: NodeDiscoverySortOrder) = mutableState.update { it.copy(sortOrder = order) }

    fun scan() {
        val session = deps?.session()
        if (session == null) {
            mutableState.update { it.copy(errorMessage = strings.notConnectedDescription(strings.filterTitle(it.filter))) }
            return
        }
        val radioId = deps?.radioId() ?: return
        stopScan()
        mutableState.update { state ->
            state.copy(
                results = state.results.filter { it.scanFilter != state.filter },
                errorMessage = null,
                isScanning = true,
                scanStartHapticTrigger = state.scanStartHapticTrigger + 1,
            )
        }
        scanGeneration += 1
        val generation = scanGeneration
        scanJob = scope.launch { runScan(session, radioId, generation) }
    }

    private suspend fun runScan(session: NodeDiscoverySession, radioId: RadioId, generation: Long) {
        var timeout: Job? = null
        try {
            loadNameResolutionData(radioId)
            val tag = session.sendNodeDiscoverRequest(current.filter.filterValue, false)
            val tagData = littleEndian(tag)
            timeout = scope.launch {
                time.sleep(SCAN_DURATION)
                if (generation == scanGeneration) scanJob?.cancel()
            }
            if (generation == scanGeneration) timeoutJob = timeout
            session.events().collect { event ->
                if (event is MeshEvent.DiscoverResponse && event.response.tag == tagData) appendOrUpdateResult(event.response)
            }
        } catch (error: CancellationException) {
            // The timeout's cancellation is the normal end of a scan.
            if (generation == scanGeneration) finishScan()
            throw error
        } catch (error: Exception) {
            diagnostics.failure("nodeDiscovery", error)
            if (generation == scanGeneration) {
                mutableState.update { it.copy(errorMessage = strings.userFacingMessage(error)) }
            }
        } finally {
            timeout?.cancel()
        }
        if (generation == scanGeneration) finishScan()
    }

    /** Stops listening; a superseded scan never finishes over its successor. */
    fun stopScan() {
        timeoutJob?.cancel()
        timeoutJob = null
        scanGeneration += 1
        scanJob?.cancel()
        scanJob = null
        if (current.isScanning) finishScan()
    }

    fun addNode(result: NodeDiscoveryResult) {
        val adder = deps?.contactAdder() ?: return
        val radioId = deps?.radioId() ?: return
        mutableState.update { it.copy(addingPublicKey = result.publicKey) }
        scope.launch {
            try {
                adder.addOrUpdateContact(radioId, contactFrame(result))
                mutableState.update {
                    // Bytes is Iterable<UByte>, so `+` would add its bytes; add the key itself.
                    it.copy(addedPublicKeys = it.addedPublicKeys.plusElement(result.publicKey), addSuccessHapticTrigger = it.addSuccessHapticTrigger + 1)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = addFailureMessage(error)
                mutableState.update { it.copy(errorMessage = message, addErrorHapticTrigger = it.addErrorHapticTrigger + 1) }
            } finally {
                mutableState.update { it.copy(addingPublicKey = null) }
            }
        }
    }

    private fun addFailureMessage(error: Exception): String {
        val dependencies = deps
        if (dependencies?.classifyAddFailure(error) != AddContactFailure.CONTACT_TABLE_FULL) {
            return strings.userFacingMessage(error)
        }
        val maxContacts = dependencies.maxContacts()
        return if (maxContacts != null) strings.nodeListFull(maxContacts.toInt()) else strings.nodeListFullSimple
    }

    /** Discovered names first, then contacts override; contact keys count as already added. */
    private suspend fun loadNameResolutionData(radioId: RadioId) {
        val directory = deps?.directory() ?: return
        try {
            val nodes = directory.fetchDiscoveredNodes(radioId)
            val names = LinkedHashMap<Bytes, String>()
            for (node in nodes) names.putIfAbsent(node.publicKey, node.name)
            namesByKey = names.toMap()
            val contacts = directory.fetchContacts(radioId)
            for (contact in contacts) names[contact.publicKey] = contact.name
            namesByKey = names
            val contactKeys: Set<Bytes> = contacts.map { contact -> contact.publicKey }.toSet()
            mutableState.update { state -> state.copy(addedPublicKeys = contactKeys) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("loadNameResolutionData", error)
        }
    }

    private fun resolveName(publicKey: Bytes): String =
        namesByKey[publicKey] ?: "${strings.unknownNode} (${publicKey.prefix(4).uppercaseHexString()})"

    private fun appendOrUpdateResult(response: DiscoverResponse) {
        val filter = current.filter
        val result = NodeDiscoveryResult(
            resolveName(response.publicKey), response.publicKey, response.nodeType, response.snr,
            response.snrIn, response.rssi, filter, time.now(),
        )
        mutableState.update { state ->
            val index = state.results.indexOfFirst { it.publicKey == response.publicKey && it.scanFilter == filter }
            val results = if (index >= 0) {
                state.results.mapIndexed { i, existing -> if (i == index) result else existing }
            } else {
                state.results + result
            }
            state.copy(results = results)
        }
    }

    private fun finishScan() = mutableState.update { state ->
        if (state.results.any { it.scanFilter == state.filter }) {
            state.copy(isScanning = false, scanSuccessHapticTrigger = state.scanSuccessHapticTrigger + 1)
        } else {
            state.copy(isScanning = false, scanEmptyHapticTrigger = state.scanEmptyHapticTrigger + 1)
        }
    }

    companion object {
        val SCAN_DURATION = 15.seconds

        /** Adds a discovered node as a flood-routed contact with no location or advert time. */
        fun contactFrame(result: NodeDiscoveryResult): ContactFrame = ContactFrame(
            publicKey = result.publicKey,
            type = ContactType.fromRawValue(result.nodeType) ?: ContactType.REPEATER,
            flags = 0u,
            outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL,
            outPath = Bytes.EMPTY,
            name = result.name,
            lastAdvertTimestamp = 0u,
            latitude = 0.0,
            longitude = 0.0,
            lastModified = 0u,
        )

        fun littleEndian(tag: UInt): Bytes = Bytes(ByteArray(4) { index -> (tag shr (8 * index)).toByte() })
    }
}
