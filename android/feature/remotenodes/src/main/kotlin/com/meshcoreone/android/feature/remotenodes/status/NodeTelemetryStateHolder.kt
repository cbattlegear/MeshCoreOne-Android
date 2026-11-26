// PortedFrom: MC1/Views/RemoteNodes/NodeTelemetryViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.BinaryTelemetryPort
import com.meshcoreone.android.feature.remotenodes.dependencies.ContactOcvPort
import com.meshcoreone.android.feature.remotenodes.dependencies.NodeSnapshotPort
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import kotlin.coroutines.cancellation.CancellationException

/** Login-free telemetry for a chat node (Swift `NodeTelemetryViewModel`); state lives on [helper]. */
class NodeTelemetryStateHolder(clock: RemoteNodesClock, private val faults: RemoteNodeFaultClassifier) {
    val helper = NodeStatusStateHolder(clock, faults)

    @Volatile private var binaryTelemetryProvider: () -> BinaryTelemetryPort? = { null }
    @Volatile private var publicKey: Bytes? = null

    /** Null providers mirror a disconnected radio; requests then no-op. */
    fun configure(
        binaryTelemetry: () -> BinaryTelemetryPort?,
        contactOcv: () -> ContactOcvPort?,
        nodeSnapshots: () -> NodeSnapshotPort?,
        contact: ContactDTO,
    ) {
        binaryTelemetryProvider = binaryTelemetry
        publicKey = contact.publicKey
        helper.configure(contactOcv, nodeSnapshots)
        helper.configureForDirectTelemetry(contact.publicKey)
    }

    /** A binary session timeout is reported with the telemetry-specific timeout text. */
    suspend fun requestTelemetry() {
        val service = binaryTelemetryProvider() ?: return
        val key = publicKey ?: return
        helper.runTelemetrySectionRequest(
            timeoutMessage = TELEMETRY_TIMED_OUT,
            operation = { _ ->
                try {
                    service.requestTelemetry(key)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (faults.isBinarySessionTimeout(error)) throw RemoteRequestTimeoutException("telemetry")
                    throw error
                }
            },
        ) { helper.handleTelemetryResponse(it) }
    }

    companion object {
        val TELEMETRY_TIMED_OUT: RemoteNodesText =
            RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusTelemetryTimedOut)
    }
}
