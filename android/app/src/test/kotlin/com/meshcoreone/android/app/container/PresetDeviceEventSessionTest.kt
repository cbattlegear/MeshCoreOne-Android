// AndroidOnly: WP-305 The real settings event collector keeps every device mutation bound to its originating session.
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
    private suspend fun ContainerHarness.connect() {
        manager.connect(target())
        settle()
        assertReady()
    }

    @Test fun currentPresetEventCarriesItsOriginalToken() = runTest {
        collectPreset { it }
    }

    @Test fun retiredPresetEventCannotUseAReplacementToken() = runTest {
        collectPreset { it.copy(generation = Generation(it.generation.value + 1)) }
    }

    @Test fun disconnectedPresetEventIsRejected() = runTest {
        collectPreset { null }
    }

    @Test fun presetWithPathHashCarriesItsOriginalTokenToBothMutations() = runTest {
        collectPreset(pathHashSize = 2) { it }
    }

    @Test fun retiredPresetPathHashCannotUseReplacementGeneration() = runTest {
        collectPreset(pathHashSize = 2) { it.copy(generation = Generation(it.generation.value + 1)) }
    }

    private suspend fun TestScope.collectPreset(pathHashSize: Long? = null, replace: (SessionToken) -> SessionToken?) {
        val h = ContainerHarness(this, store)
        try {
            h.links.configureRadio = { radio ->
                var mode = 0
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x0b -> listOf(Bytes.of(0))
                        0x3d -> {
                            mode = command[2].toInt()
                            listOf(Bytes.of(0))
                        }
                        0x16 -> if (pathHashSize == null) null else listOf(
                            Bytes.of(13, 10, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                                Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                                Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0, mode),
                        )
                        else -> null
                    }
                }
            }
            h.connect()
            val session = assertNotNull(h.sessions.current)
            val token = session.token
            val port = FakeConnectionPort().also {
                it.connectionState = h.manager.connectionState
                it.connectedDevice = h.manager.connectedDevice
                it.currentToken = token
                it.beforeSessionUpdate = { it.currentToken = replace(token) }
            }
            val state = appStateOf(backgroundScope, port, session = { session })
            state.wireServicesIfConnected()
            try {
                val preset = RadioPresets.all.first().copy(
                    frequencyMHz = 915.0, bandwidthKHz = 250.0,
                    spreadingFactor = 10u, codingRate = 5u, pathHashSize = pathHashSize,
                )
                session.settingsService.applyRadioPresetVerified(preset)
                val count = if (pathHashSize == null) 1 else 2
                h.eventually("preset events reach token-bound port") { port.sessionTokens.size == count }
                assertEquals(List(count) { token }, port.sessionTokens)
                assertEquals(if (port.currentToken == token) 1 else 0,
                    port.calls.count { it == "updateDeviceInfo" })
                if (pathHashSize != null) {
                    assertEquals(if (port.currentToken == token) 1 else 0, port.calls.count { it == "pathHash:1" })
                }
            } finally { state.tearDownAppStateSessionState() }
        } finally { h.container.close() }
    }

    @Test fun currentNonPresetDeviceEventCarriesItsOriginalToken() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val session = assertNotNull(h.sessions.current)
            val port = FakeConnectionPort().also {
                it.connectionState = h.manager.connectionState
                it.connectedDevice = h.manager.connectedDevice
                it.currentToken = session.token
            }
            val state = appStateOf(backgroundScope, port, session = { session })
            state.wireServicesIfConnected()
            try {
                session.settingsService.refreshDeviceInfo()
                h.eventually("non-preset event reaches token-bound port") { port.sessionTokens.size == 1 }
                assertEquals(listOf(session.token), port.sessionTokens)
                assertEquals(1, port.calls.count { it == "updateDeviceInfo" })
            } finally { state.tearDownAppStateSessionState() }
        } finally { h.container.close() }
    }

    @Test fun retiredNonPresetDeviceEventCannotUpdateReplacementSession() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val session = assertNotNull(h.sessions.current)
            val port = FakeConnectionPort().also {
                it.connectionState = h.manager.connectionState
                it.connectedDevice = h.manager.connectedDevice
                it.currentToken = session.token
                it.beforeSessionUpdate = {
                    it.currentToken = session.token.copy(generation = Generation(session.token.generation.value + 1))
                }
            }
            val state = appStateOf(backgroundScope, port, session = { session })
            state.wireServicesIfConnected()
            try {
                session.settingsService.refreshDeviceInfo()
                h.eventually("retired non-preset event reaches token guard") { port.sessionTokens.size == 1 }
                assertEquals(listOf(session.token), port.sessionTokens)
                assertTrue("updateDeviceInfo" !in port.calls)
            } finally { state.tearDownAppStateSessionState() }
        } finally { h.container.close() }
    }

    @Test fun retiredRepeatEventsCannotUpdateReplacementSession() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.links.configureRadio = { radio ->
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x0b, 0x3d -> listOf(Bytes.of(0))
                        else -> null
                    }
                }
            }
            h.connect()
            val session = assertNotNull(h.sessions.current)
            val port = FakeConnectionPort().also {
                it.connectionState = h.manager.connectionState
                it.connectedDevice = h.manager.connectedDevice
                it.currentToken = session.token
                it.beforeSessionUpdate = {
                    it.currentToken = session.token.copy(generation = Generation(session.token.generation.value + 1))
                }
            }
            val state = appStateOf(backgroundScope, port, session = { session })
            state.wireServicesIfConnected()
            try {
                session.settingsService.setRadioParamsVerified(915_000u, 250_000u, 10u, 5u, clientRepeat = false)
                h.eventually("repeat and device events reach token guards") { port.sessionTokens.size == 2 }
                assertEquals(List(2) { session.token }, port.sessionTokens)
                assertTrue("clientRepeat:false" !in port.calls)
                assertTrue("updateDeviceInfo" !in port.calls)
            } finally { state.tearDownAppStateSessionState() }
        } finally { h.container.close() }
    }
}
