// PortedFrom: MC1/Views/RemoteNodes/NodeStatusViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeStatusMetrics
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.ContactOcvPort
import com.meshcoreone.android.feature.remotenodes.dependencies.NodeSnapshotPort
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.telemetry.typeName
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Shared logic behind the repeater, room and chat-node telemetry screens (Swift `NodeStatusViewModel`):
 * retry machinery, status/telemetry handling with snapshot capture, neighbor enrichment, OCV settings
 * and history. Service providers are read at call time; a null provider result is a disconnected radio.
 */
class NodeStatusStateHolder(
    private val clock: RemoteNodesClock,
    faults: RemoteNodeFaultClassifier,
) {
    private val _state = MutableStateFlow(NodeStatusState())
    val state: StateFlow<NodeStatusState> = _state.asStateFlow()

    val retry = TransientRetryRunner(clock, faults)

    @Volatile private var contactOcvProvider: () -> ContactOcvPort? = { null }
    @Volatile private var nodeSnapshotProvider: () -> NodeSnapshotPort? = { null }
    @Volatile private var contactId: UUID? = null

    private val nodeSnapshots: NodeSnapshotPort? get() = nodeSnapshotProvider()

    fun configure(contactOcv: () -> ContactOcvPort?, nodeSnapshots: () -> NodeSnapshotPort?) {
        contactOcvProvider = contactOcv
        nodeSnapshotProvider = nodeSnapshots
    }

    /** Login-free telemetry for chat nodes (Swift `configureForDirectTelemetry`). */
    fun configureForDirectTelemetry(publicKey: Bytes) = _state.update { it.copy(directPublicKey = publicKey) }

    fun matchesSession(publicKeyPrefix: Bytes): Boolean = _state.value.matchesSession(publicKeyPrefix)

    // Swift settable vars the views and tests assign.
    fun setSession(session: RemoteNodeSessionDTO?) = _state.update { it.copy(session = session) }

    /** Swift `if helper.session == nil { helper.session = session }`. */
    fun adoptSessionIfAbsent(session: RemoteNodeSessionDTO) =
        _state.update { if (it.session == null) it.copy(session = session) else it }

    fun setStatusExpanded(expanded: Boolean) = _state.update { it.copy(statusExpanded = expanded) }
    fun setTelemetryExpanded(expanded: Boolean) = _state.update { it.copy(telemetryExpanded = expanded) }
    fun setBatteryCurveExpanded(expanded: Boolean) = _state.update { it.copy(isBatteryCurveExpanded = expanded) }
    fun setSelectedOCVPreset(preset: OCVPreset) = _state.update { it.copy(selectedOCVPreset = preset) }
    fun setOcvValues(values: List<Long>) = _state.update { it.copy(ocvValues = values.toList()) }
    fun setLoadingStatus(loading: Boolean) = _state.update { it.copy(isLoadingStatus = loading) }
    fun setLoadingTelemetry(loading: Boolean) = _state.update { it.copy(isLoadingTelemetry = loading) }
    fun setStatusSectionError(error: RemoteNodesText?) = _state.update { it.copy(statusSectionError = error) }
    fun setTelemetrySectionError(error: RemoteNodesText?) = _state.update { it.copy(telemetrySectionError = error) }

    /** Runs a status-section request through the shared retry budget. */
    suspend fun <T> runStatusSectionRequest(operation: suspend (Duration) -> T, onSuccess: suspend (T) -> Unit) =
        retry.runRetryingSectionRequest(
            "status", ::setLoadingStatus, ::setStatusSectionError, operation = operation, onSuccess = onSuccess,
        )

    /** Runs a telemetry-section request through the shared retry budget. */
    suspend fun <T> runTelemetrySectionRequest(
        timeoutMessage: RemoteNodesText = TransientRetryRunner.REQUEST_TIMED_OUT,
        operation: suspend (Duration) -> T,
        onSuccess: suspend (T) -> Unit,
    ) = retry.runRetryingSectionRequest(
        "telemetry", ::setLoadingTelemetry, ::setTelemetrySectionError, timeoutMessage, operation, onSuccess,
    )

    // MARK: - Status

    /**
     * Applies a status response for this session and captures a snapshot with the role-specific fields
     * (rooms pass null for the repeater metrics, repeaters null for the room counters).
     */
    suspend fun handleStatusResponse(
        response: StatusResponse,
        rxAirtimeSeconds: UInt? = null,
        receiveErrors: UInt? = null,
        postedCount: UShort? = null,
        postPushCount: UShort? = null,
    ) {
        val session = _state.value.session ?: return
        if (response.publicKeyPrefix != session.publicKeyPrefix) return
        _state.update {
            it.copy(status = response, statusLoaded = true, isLoadingStatus = false, statusSectionError = null)
        }
        val service = nodeSnapshots ?: return
        val previous = service.previousStatusSnapshot(session.publicKey, clock.now)
        _state.update { it.copy(previousStatusSnapshot = previous) }
        val metrics = NodeStatusMetrics.fromStatus(response, rxAirtimeSeconds, receiveErrors, postedCount, postPushCount)
        service.recordSnapshot(session.publicKey, status = metrics)
    }

    /**
     * Captures neighbor data onto the current in-window snapshot (or a new one). The baseline is read
     * before persisting so the delta and "New" badge reflect history, not this capture.
     */
    suspend fun enrichNeighbors(entries: List<NeighborSnapshotEntry>) {
        val service = nodeSnapshots ?: return
        val publicKey = _state.value.effectivePublicKey ?: return
        val baseline = service.neighborBaseline(publicKey)
        _state.update {
            it.copy(previousNeighborSnapshot = baseline.previous, seenNeighborPrefixes = baseline.seenPrefixes.toSet())
        }
        service.recordSnapshot(publicKey, neighbors = entries.snapshot())
    }

    // MARK: - Telemetry

    /** Applies a telemetry response for this node and captures its numeric readings and location. */
    suspend fun handleTelemetryResponse(response: TelemetryResponse) {
        val expectedPrefix = _state.value.effectivePublicKeyPrefix ?: return
        if (response.publicKeyPrefix != expectedPrefix) return
        val points = response.dataPoints.filter { it.channel.toInt() != 0 }
        val updated = _state.updateAndGet {
            it.copy(
                telemetry = response, cachedDataPoints = points, isLoadingTelemetry = false,
                telemetryLoaded = true, telemetrySectionError = null,
            )
        }
        val entries = points.mapNotNull(::telemetryEntry)
        val location = updated.currentLocationFix
        if (entries.isEmpty() && location == null) return
        val service = nodeSnapshots ?: return
        val publicKey = updated.effectivePublicKey ?: return
        service.recordSnapshot(publicKey, telemetry = entries.takeIf { it.isNotEmpty() }?.snapshot(), location = location)
    }

    // MARK: - History

    suspend fun fetchHistory(): List<NodeStatusSnapshotDTO> {
        val service = nodeSnapshots ?: return emptyList()
        val publicKey = _state.value.effectivePublicKey ?: return emptyList()
        return service.fetchSnapshots(publicKey)
    }

    // MARK: - OCV Settings

    /** Loads the contact's OCV curve once; a missing contact leaves the defaults. */
    suspend fun loadOCVSettings(publicKey: Bytes, radioId: RadioId) {
        if (contactId != null) return
        val contacts = contactOcvProvider() ?: return
        try {
            val contact = contacts.getContact(radioId, publicKey) ?: return
            contactId = contact.id
            val (preset, values) = resolveOcv(contact.ocvPreset, contact.customOCVArrayString)
            _state.update { it.copy(selectedOCVPreset = preset, ocvValues = values) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(ocvError = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusOcvLoadFailed)) }
        }
    }

    suspend fun saveOCVSettings(preset: OCVPreset, values: List<Long>) {
        val contacts = contactOcvProvider()
        val id = contactId
        if (contacts == null || id == null) {
            _state.update { it.copy(ocvError = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusOcvSaveNoContact)) }
            return
        }
        _state.update { it.copy(ocvError = null) }
        try {
            if (preset == OCVPreset.CUSTOM) {
                contacts.updateContactOCVSettings(id, OCVPreset.CUSTOM.rawValue, OcvCustomCurve.format(values))
            } else {
                contacts.updateContactOCVSettings(id, preset.rawValue, null)
            }
            _state.update { it.copy(selectedOCVPreset = preset, ocvValues = values.toList()) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val text = RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_ocvsavefailed, RemoteNodesText.Failure(error))
            _state.update { it.copy(ocvError = text) }
        }
    }

    companion object {
        /** Swift's telemetry snapshot entry: float and integer readings only. */
        internal fun telemetryEntry(point: LPPDataPoint): TelemetrySnapshotEntry? {
            val value = when (val reading = point.value) {
                is LPPValue.Float -> reading.value
                is LPPValue.Integer -> reading.value.toDouble()
                else -> return null
            }
            return TelemetrySnapshotEntry(point.channel.toLong(), point.typeName, value)
        }

        /** The preset and curve a stored contact selects (custom only with a full 11-point string). */
        internal fun resolveOcv(presetName: String?, customString: String?): Pair<OCVPreset, List<Long>> {
            if (presetName != null) {
                if (presetName == OCVPreset.CUSTOM.rawValue && customString != null) {
                    val parsed = OcvCustomCurve.parse(customString)
                    if (parsed.size == OcvCustomCurve.POINT_COUNT) return OCVPreset.CUSTOM to parsed
                }
                OCVPreset.fromRawValue(presetName)?.let { return it to it.ocvArray }
            }
            return OCVPreset.LI_ION to OCVPreset.LI_ION.ocvArray
        }
    }
}
