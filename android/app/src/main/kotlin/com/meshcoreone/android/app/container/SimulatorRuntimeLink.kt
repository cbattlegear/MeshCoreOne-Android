// AndroidOnly: WP-217 Owns one real simulator mode and placeholder transport per connection generation.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.runtime.LinkCallbacks
import com.meshcoreone.android.core.runtime.RuntimeSimulatorLink
import com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode
import com.meshcoreone.android.core.services.simulator.SimulatorMockTransport
import com.meshcoreone.android.core.services.simulator.SimulatorSeedStore
import java.time.Instant
import java.util.UUID

class SimulatorRuntimeLink(
    private val store: SimulatorSeedStore,
    private val finishSeed: suspend () -> Unit,
    private val mode: SimulatorConnectionMode = SimulatorConnectionMode(),
) : RuntimeSimulatorLink {
    private val mock = SimulatorMockTransport()
    override val type: TransportType = TransportType.SIMULATOR
    override val transport: MeshTransport = object : MeshTransport by mock {
        override suspend fun disconnect() {
            try { mock.disconnect() } finally { mode.disconnect() }
        }
    }

    override suspend fun connectSimulator(): DeviceDTO {
        mode.connect()
        return checkNotNull(mode.device) { "Simulator did not connect" }
    }

    override suspend fun seedSimulator() {
        check(mode.isConnected) { "Simulator is not connected" }
        mode.seedDataStore(store)
        finishSeed()
    }

    override fun register(callbacks: LinkCallbacks): AutoCloseable = AutoCloseable {}
    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) =
        error("Simulator has no physical radio capabilities")
    override suspend fun setSessionLive(token: SessionToken?) = Unit
    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) =
        error("Simulator has no Bluetooth bond")
    override suspend fun clearBondVerification(deviceId: UUID) = error("Simulator has no Bluetooth bond")
    override suspend fun mayRefreshBond(deviceId: UUID): Boolean = false
}
