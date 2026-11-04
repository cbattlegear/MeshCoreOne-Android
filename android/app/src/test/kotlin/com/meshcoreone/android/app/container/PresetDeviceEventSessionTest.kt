// AndroidOnly: WP-305 The real settings event collector forwards the originating token for preset updates only.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.device.RadioPresets
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PresetDeviceEventSessionTest : RoomProcessTest() {
    @Test fun currentPresetEventCarriesItsOriginalToken() = runTest {
        collectPreset { it }
    }

    @Test fun retiredPresetEventCannotUseAReplacementToken() = runTest {
        collectPreset { it.copy(generation = Generation(it.generation.value + 1)) }
    }

    @Test fun disconnectedPresetEventIsRejected() = runTest {
        collectPreset { null }
    }

    private suspend fun TestScope.collectPreset(replace: (SessionToken) -> SessionToken?) {
        val h = ContainerHarness(this, store)
        try {
            h.links.configureRadio = { radio ->
                radio.respond = { command ->
                    if (command[0].toInt() == 0x0b) listOf(Bytes.of(0)) else null
                }
            }
            h.connect()
            val session = assertNotNull(h.sessions.current)
            val token = session.token
            val port = FakeConnectionPort().also {
                it.connectionState = h.manager.connectionState
                it.connectedDevice = h.manager.connectedDevice
                it.presetToken = token
                it.beforePresetUpdate = { it.presetToken = replace(token) }
            }
            val state = appStateOf(backgroundScope, port, session = { session })
            state.wireServicesIfConnected()
            try {
                val preset = RadioPresets.all.first().copy(
                    frequencyMHz = 915.0, bandwidthKHz = 250.0,
                    spreadingFactor = 10u, codingRate = 5u, pathHashSize = null,
                )
                session.settingsService.applyRadioPresetVerified(preset)
                h.eventually("preset event reaches token-bound port") { port.presetTokens.isNotEmpty() }
                assertEquals(listOf(token), port.presetTokens)
                assertEquals(if (port.presetToken == token) 1 else 0,
                    port.calls.count { it == "updateDeviceInfo" })
            } finally { state.tearDownAppStateSessionState() }
        } finally { h.container.close() }
    }

    @Test fun nonPresetDeviceEventKeepsItsExistingConsumer() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val session = assertNotNull(h.sessions.current)
            val port = FakeConnectionPort().also {
                it.connectionState = h.manager.connectionState
                it.connectedDevice = h.manager.connectedDevice
            }
            val state = appStateOf(backgroundScope, port, session = { session })
            state.wireServicesIfConnected()
            try {
                session.settingsService.refreshDeviceInfo()
                h.eventually("non-preset event reaches existing port") { "updateDeviceInfo" in port.calls }
                assertTrue(port.presetTokens.isEmpty())
                assertEquals(1, port.calls.count { it == "updateDeviceInfo" })
            } finally { state.tearDownAppStateSessionState() }
        } finally { h.container.close() }
    }
}
