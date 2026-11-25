// AndroidOnly: WP-211 Real-session radio Wi-Fi presentation regressions compared with current upstream.
package com.meshcoreone.android.app.container

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.R
import com.meshcoreone.android.app.navigation.RadioWiFiDialogs
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.device.DeviceSettingsContext
import com.meshcoreone.android.core.services.device.RadioWiFiService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioWiFiControllerTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test
    fun wifiSaveWarnsAndUsesRealGuardedCommands() = runTest {
        val f = fixture()
        try {
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            assertEquals(RadioWiFiSupport.SUPPORTED, f.controller.state.value.support)
            assertEquals("Old", f.controller.state.value.network)
            f.controller.openEditor()
            runCurrent()
            assertEquals("secret", f.controller.state.value.editor?.password)
            f.controller.setSsid("New")
            f.controller.setPassword("new-secret")
            assertTrue(f.controller.canSave())
            f.controller.requestSave()
            assertEquals(RadioWiFiConfirmation.SAVE, f.controller.state.value.confirmation)
            assertFalse(f.radio.commands.any { it.startsWith("set ") })
            f.controller.confirm()
            runCurrent()
            assertEquals(listOf("set wifi.ssid New", "set wifi.pwd new-secret"), f.radio.commands.filter { it.startsWith("set ") })
            assertNull(f.controller.state.value.editor)
            assertTrue(f.controller.state.value.pendingReboot)
            assertEquals("New", f.controller.state.value.network)
        } finally {
            f.close()
        }
    }

    @Test
    fun partialWriteKeepsTheEditorAndErrorWithoutPretendingSuccess() = runTest {
        val f = fixture()
        try {
            f.transport = TransportType.BLUETOOTH
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            f.controller.openEditor()
            runCurrent()
            f.controller.setSsid("New")
            f.controller.setPassword("new-secret")
            f.radio.rejectPassword = true
            f.controller.requestSave()
            runCurrent()
            val state = f.controller.state.value
            assertNotNull(state.error)
            assertNotNull(state.editor)
            assertTrue(state.pendingReboot)
            assertEquals("New", state.network)
            assertTrue(f.controller.canSave())
            f.radio.rejectPassword = false
            f.controller.requestSave()
            runCurrent()
            assertNull(f.controller.state.value.editor)
            assertTrue(f.controller.state.value.pendingReboot)
        } finally {
            f.close()
        }
    }

    @Test
    fun retiredOwnerCannotPublishAnOutstandingCredentialRead() = runTest {
        val f = fixture()
        try {
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            f.radio.passwordGate = gate
            f.controller.openEditor()
            runCurrent()
            assertTrue(f.controller.state.value.busy)
            f.current = null
            f.controller.bind(null, supportsCli = false)
            gate.complete(Unit)
            runCurrent()
            assertNull(f.controller.state.value.editor)
            assertNull(f.controller.state.value.network)
            assertEquals(RadioWiFiSupport.UNKNOWN, f.controller.state.value.support)
        } finally {
            f.close()
        }
    }

    @Test
    fun disablingAndClearingRequireConfirmationAndKeepRebootPending() = runTest {
        val f = fixture()
        try {
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            f.controller.requestEnabled(false)
            assertEquals(RadioWiFiConfirmation.DISABLE, f.controller.state.value.confirmation)
            assertFalse(f.radio.commands.contains("set wifi.enabled 0"))
            f.controller.cancelConfirmation()
            assertTrue(f.radio.enabled)
            f.controller.requestEnabled(false)
            f.controller.confirm()
            runCurrent()
            assertFalse(f.radio.enabled)
            assertTrue(f.controller.state.value.pendingReboot)
            f.controller.requestClear()
            assertEquals(RadioWiFiConfirmation.CLEAR, f.controller.state.value.confirmation)
            assertFalse(f.radio.commands.contains("set wifi.clear"))
            f.controller.confirm()
            runCurrent()
            assertTrue(f.radio.commands.contains("set wifi.clear"))
            assertNull(f.controller.state.value.network)
            assertTrue(f.controller.state.value.pendingReboot)
        } finally {
            f.close()
        }
    }

    @Test
    fun staleConfirmationCannotWriteToRetiredSession() = runTest {
        val f = fixture()
        try {
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            f.controller.requestEnabled(false)
            assertEquals(RadioWiFiConfirmation.DISABLE, f.controller.state.value.confirmation)
            f.current = null
            f.controller.confirm()
            runCurrent()
            assertFalse(f.radio.commands.contains("set wifi.enabled 0"))
        } finally {
            f.close()
        }
    }

    @Test
    fun mountedEditorMasksPasswordAndShowsTheWifiWarning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = RadioWiFiController(scope, { null }, { false }, { TransportType.WIFI })
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        try {
            var state by mutableStateOf(RadioWiFiPresentation())
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        Box(Modifier.fillMaxSize()) { RadioWiFiDialogs(controller, state, TransportType.WIFI) }
                    }
                }
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                state = state.copy(editor = RadioWiFiEditor(UUID.randomUUID(), ssid = "Old", password = "secret"))
            }
            repeat(5) {
                compose.mainClock.advanceTimeBy(100)
                compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idle() }
            }
            compose.onNodeWithText("Old").assertIsDisplayed()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists()
            compose.runOnUiThread { state = state.copy(editor = null, confirmation = RadioWiFiConfirmation.SAVE) }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText(host.get().getString(R.string.radio_wifi_radiowifi_alert_wifitransport_message)).assertIsDisplayed()
        } finally {
            host.pause().stop().destroy()
            scope.cancel()
        }
    }

    private class Radio(private val delegate: MockTransport = MockTransport()) : MeshTransport by delegate {
        val commands = mutableListOf<String>()
        var enabled = true
        var ssid = "Old"
        var password = "secret"
        var rejectPassword = false
        var passwordGate: CompletableDeferred<Unit>? = null

        override suspend fun send(data: Bytes) {
            delegate.send(data)
            if (data[0].toInt() == 1) {
                val zeros = Bytes(ByteArray(32))
                delegate.simulateReceive(
                    Bytes.of(5, 1, 22, 22) + zeros + Bytes(ByteArray(8)) + Bytes.of(0, 0, 0, 0) +
                        Bytes(ByteArray(8)) + Bytes.of(7, 5) + Bytes.utf8("Test"),
                )
                return
            }
            if (data[0].toInt() != 0x42) return
            val raw = data.slice(1, data.size).toByteArray().toString(Charsets.UTF_8)
            val tag = raw.substring(0, 3)
            val command = raw.substring(3)
            commands += command
            if (command == "get wifi.pwd") passwordGate?.await()
            val response = when {
                command == "get wifi.enabled" -> if (enabled) "> 1" else "> 0"
                command == "get wifi.ssid" -> "> $ssid"
                command == "get wifi.status" -> "> connected"
                command == "get wifi.ip" -> "> 192.0.2.1"
                command == "get wifi.pwd" -> "> $password"
                command.startsWith("set wifi.ssid ") -> { ssid = command.removePrefix("set wifi.ssid "); "OK" }
                command.startsWith("set wifi.pwd ") -> if (rejectPassword) "ERR" else {
                    password = command.removePrefix("set wifi.pwd "); "OK"
                }
                command == "set wifi.enabled 0" -> { enabled = false; "OK" }
                command == "set wifi.enabled 1" -> { enabled = true; "OK" }
                command == "set wifi.clear" -> { ssid = ""; password = ""; "OK" }
                else -> error("Unexpected command $command")
            }
            delegate.simulateReceive(Bytes.of(0x1d) + Bytes.utf8(tag + response))
        }
    }

    private class Fixture(
        val radio: Radio,
        val session: MeshCoreSession,
        val context: DeviceSettingsContext,
        val service: RadioWiFiService,
    ) {
        var current: RadioWiFiService? = service
        var transport = TransportType.WIFI
        lateinit var controller: RadioWiFiController
        suspend fun close() { context.close(); session.stop() }
    }

    private suspend fun TestScope.fixture(): Fixture {
        val radioId = RadioId(UUID.randomUUID())
        val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), radioId)
        val signals = object : ConnectionSignals {
            override val snapshot = kotlinx.coroutines.flow.MutableStateFlow(
                ConnectionSnapshot(DeviceConnectionState.READY, ConnectionState.Connected, null,
                    ConnectionIntent.WantsConnection(), token, null),
            )
            override suspend fun subscribeTransitions(): ConnectionSubscription =
                error("Not used by this fixture")
        }
        val clock = object : SessionClock {
            override val now: Duration get() = testScheduler.currentTime.milliseconds
            override val wallClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            override suspend fun sleepFor(duration: Duration) = delay(duration)
        }
        val radio = Radio()
        val session = MeshCoreSession(radio, SessionConfiguration(defaultTimeout = 1.0), clock, backgroundScope.coroutineContext)
        val start = backgroundScope.async { session.start() }
        runCurrent()
        start.await()
        val context = DeviceSettingsContext(token, signals, backgroundScope, StandardTestDispatcher(testScheduler))
        val service = RadioWiFiService(session, context, RadioWiFiState(radioId), clock)
        return Fixture(radio, session, context, service).also { fixture ->
            fixture.controller = RadioWiFiController(
                backgroundScope, { fixture.current }, { fixture.current != null }, { fixture.transport },
            )
        }
    }
}
