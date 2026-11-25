// PortedFrom: MC1Services/Sources/MC1Services/ServiceContainer.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: construct only this WP's connection bundle, not a complete WP-303 SessionServices graph.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.DiscoveredNodePersisting
import com.meshcoreone.android.core.contracts.domain.SessionInputs
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class DeviceSettingsServiceFactory(
    private val devices: DevicePersisting,
    private val contacts: ContactPersisting,
    private val discoveredNodes: DiscoveredNodePersisting?,
    private val clock: SessionClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    fun create(inputs: SessionInputs<MeshCoreSession>): DeviceSettingsServices {
        val context = DeviceSettingsContext(inputs.token, inputs.signals, inputs.scope, dispatcher)
        var constructed = false
        try {
            val result = DeviceSettingsServices(
                context, DeviceService(devices, context), SettingsService(inputs.session, context),
                RegionDiscoveryService(inputs.session, contacts, discoveredNodes, context, clock),
            )
            context.requireCurrent()
            constructed = true
            return result
        } finally {
            if (!constructed) context.cancel()
        }
    }
}

class DeviceSettingsServices internal constructor(
    val context: DeviceSettingsContext,
    val deviceService: DeviceService,
    val settingsService: SettingsService,
    val regionDiscoveryService: RegionDiscoveryService,
) {
    suspend fun close() = context.close()
}
