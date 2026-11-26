// AndroidOnly: WP-211 Real-session radio Wi-Fi presentation regressions compared with current upstream.
package com.meshcoreone.android.app.container

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.navigation.RadioWiFiDialogs
import com.meshcoreone.android.app.navigation.RadioWiFiEditor
import com.meshcoreone.android.app.navigation.RadioWiFiSettings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioWiFiControllerTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
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
    fun recoveredObservationClearsItsErrorButNotAnUnacknowledgedWriteFailure() = runTest {
        val f = fixture()
        try {
            f.transport = TransportType.BLUETOOTH
            f.radio.statusFailures = 1
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            assertNotNull(f.controller.state.value.error)
            assertNull(f.controller.state.value.associated)

            f.controller.refresh()
            runCurrent()
            assertNull(f.controller.state.value.error)
            assertEquals(true, f.controller.state.value.associated)

            f.radio.rejectDisable = true
            f.controller.requestEnabled(false)
            runCurrent()
            val writeFailure = assertNotNull(f.controller.state.value.error)
            assertEquals(true, f.controller.state.value.associated)
            f.controller.refresh()
            runCurrent()
            assertEquals(writeFailure, f.controller.state.value.error)
            f.controller.dismissError()
            assertNull(f.controller.state.value.error)
        } finally {
            f.close()
        }
    }

    @Test
    fun queuedRefreshClearsAnEarlierObservationFailure() = runTest {
        val f = fixture()
        try {
            f.radio.statusFailures = 1
            f.controller.bind(f.service, supportsCli = true)
            f.controller.refresh()
            runCurrent()
            assertEquals(2, f.radio.commands.count { it == "get wifi.status" })
            assertEquals(true, f.controller.state.value.associated)
            assertNull(f.controller.state.value.error)
        } finally {
            f.close()
        }
    }

    @Test
    fun confirmedRebootUpdatesAReadyControllerAfterItsInitialRefresh() = runTest {
        val f = fixture()
        try {
            val state = f.service.state
            state.settle(assertNotNull(state.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
            val oldContext = UUID.randomUUID()
            val before = RadioWiFiResetObservation(1000u, Duration.ZERO, Duration.ZERO)
            val candidate = assertNotNull(state.prepareExpectedReboot(oldContext, observation = before))
            state.completeRebootSubmission(candidate, succeeded = true)
            state.noteLinkEnded(oldContext)

            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            assertTrue(f.controller.state.value.pendingReboot)
            assertFalse(f.controller.state.value.busy)
            val after = RadioWiFiResetObservation(1u, 1.seconds, 1.seconds)
            assertTrue(state.clearExpectedReboot(candidate, f.service.contextId, assertNotNull(state.stableRevision()), after))
            runCurrent()
            assertFalse(f.controller.state.value.pendingReboot)
            assertFalse(f.controller.canReboot())
        } finally {
            f.close()
        }
    }

    @Test
    fun pendingRebootPublicationWaitsForAnInProgressBinding() = runTest {
        val f = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val binder = Executors.newSingleThreadExecutor()
        try {
            val controller = RadioWiFiController(
                controllerScope, { f.service },
                {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    false
                },
                { TransportType.WIFI },
            )
            controller.bind(f.service, supportsCli = false)
            val subscribed = CountDownLatch(1)
            controllerScope.launch { subscribed.countDown() }
            assertTrue(subscribed.await(5, TimeUnit.SECONDS))

            val binding = binder.submit { controller.bind(f.service, supportsCli = true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val state = f.service.state
            state.settle(assertNotNull(state.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
            val delivered = CountDownLatch(1)
            controllerScope.launch { delivered.countDown() }
            assertFalse(delivered.await(500, TimeUnit.MILLISECONDS))
            assertFalse(controller.state.value.pendingReboot)

            release.countDown()
            binding.get(5, TimeUnit.SECONDS)
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state.value.pendingReboot)
        } finally {
            release.countDown()
            binder.shutdownNow()
            controllerScope.cancel()
            dispatcher.close()
            f.close()
        }
    }

    @Test
    fun retiredRadioPendingChangesCannotUpdateTheNewControllerOwner() = runTest {
        val f = fixture()
        try {
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            val replacement = RadioWiFiService(f.session, f.context, RadioWiFiState(f.service.state.radioId), f.serviceClock)
            f.current = replacement
            f.controller.bind(replacement, supportsCli = true)
            runCurrent()

            val old = f.service.state
            old.settle(assertNotNull(old.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
            runCurrent()
            assertFalse(f.controller.state.value.pendingReboot)
            val current = replacement.state
            current.settle(assertNotNull(current.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
            runCurrent()
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
    fun mountedEditorCancelsAndConfirmsRealWifiWritesAtDoubleFontScale() = runTest {
        val f = fixture()
        try {
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            withEditorHost(f, fontScale = 2f) { activity ->
                val network = activity.getString(S.radioWiFiNetworkName)
                val password = activity.getString(S.radioWiFiPassword)
                val save = activity.getString(L.commonSave)
                val cancel = activity.getString(L.commonCancel)
                val warning = activity.getString(S.radioWiFiAlertWifiTransportMessage)
                fun ssidField() = compose.onNode(hasContentDescription(network) and hasSetTextAction())
                fun passwordField() = compose.onNode(hasContentDescription(password) and hasSetTextAction())
                fun warningButton(text: String) = compose.onNode(
                    hasText(text) and hasAnyAncestor(isDialog() and hasAnyDescendant(hasText(warning))),
                )
                fun openEditor() {
                    compose.onNodeWithText(network).performScrollTo().performClick()
                    runCurrent()
                    compose.waitForIdle()
                }

                openEditor()
                ssidField().performScrollTo().assertIsDisplayed().assertTextContains("Old")
                passwordField().performScrollTo().assertIsDisplayed()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                ssidField().performScrollTo().performTextReplacement("Cancelled")
                passwordField().performScrollTo().performTextReplacement("discarded")
                compose.onNodeWithText(cancel).performScrollTo().assertIsDisplayed().performClick()
                runCurrent()
                assertNull(f.controller.state.value.editor)
                assertFalse(f.radio.commands.any { it.startsWith("set ") })

                openEditor()
                ssidField().performScrollTo().performTextReplacement("Back")
                compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
                runCurrent()
                assertNull(f.controller.state.value.editor)
                assertFalse(f.radio.commands.any { it.startsWith("set ") })

                openEditor()
                ssidField().performScrollTo().performTextReplacement("New")
                passwordField().performScrollTo().performTextReplacement("new-secret")
                compose.onNodeWithText(save).performScrollTo().assertIsEnabled().performClick()
                compose.onNodeWithText(warning).assertIsDisplayed()
                assertFalse(f.radio.commands.any { it.startsWith("set ") })
                warningButton(cancel).performClick()
                assertNotNull(f.controller.state.value.editor)
                assertFalse(f.radio.commands.any { it.startsWith("set ") })
                compose.onNodeWithText(save).performScrollTo().performClick()
                compose.onNodeWithText(warning).assertIsDisplayed()
                warningButton(save).performClick()
                runCurrent()
                assertEquals(
                    listOf("set wifi.ssid New", "set wifi.pwd new-secret"),
                    f.radio.commands.filter { it.startsWith("set ") },
                )
                assertNull(f.controller.state.value.editor)
                assertTrue(f.controller.state.value.pendingReboot)
            }
        } finally {
            f.close()
        }
    }

    @Test
    fun mountedEditorKeepsPartialFailureAndRetriesTheRealWrite() = runTest {
        val f = fixture()
        try {
            f.transport = TransportType.BLUETOOTH
            f.controller.bind(f.service, supportsCli = true)
            runCurrent()
            withEditorHost(f) { activity ->
                val network = activity.getString(S.radioWiFiNetworkName)
                val password = activity.getString(S.radioWiFiPassword)
                val save = activity.getString(L.commonSave)
                fun ssidField() = compose.onNode(hasContentDescription(network) and hasSetTextAction())
                fun passwordField() = compose.onNode(hasContentDescription(password) and hasSetTextAction())

                compose.onNodeWithText(network).performScrollTo().performClick()
                runCurrent()
                ssidField().performScrollTo().performTextReplacement("New")
                passwordField().performScrollTo().performTextReplacement("new-secret")
                f.radio.rejectPassword = true
                compose.onNodeWithText(save).performScrollTo().performClick()
                runCurrent()
                assertNotNull(f.controller.state.value.error)
                assertNotNull(f.controller.state.value.editor)
                assertTrue(f.controller.state.value.pendingReboot)
                compose.onNodeWithText(activity.getString(L.commonOk)).assertIsDisplayed().performClick()
                ssidField().performScrollTo().assertTextContains("New")
                passwordField().performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                f.radio.rejectPassword = false
                compose.onNodeWithText(save).performScrollTo().assertIsEnabled().performClick()
                runCurrent()
                assertNull(f.controller.state.value.error)
                assertNull(f.controller.state.value.editor)
                assertEquals(
                    listOf("set wifi.ssid New", "set wifi.pwd new-secret", "set wifi.ssid New", "set wifi.pwd new-secret"),
                    f.radio.commands.filter { it.startsWith("set ") },
                )
            }
        } finally {
            f.close()
        }
    }

    private inline fun withEditorHost(f: Fixture, fontScale: Float = 1f, block: (ComponentActivity) -> Unit) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        val resolver = host.get().contentResolver
        val animatorScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            compose.runOnUiThread {
                host.get().setContent {
                    val state by f.controller.state.collectAsState()
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        MeshCoreTheme(motionScale = 0f) {
                            Box(Modifier.fillMaxSize()) {
                                if (state.editor != null) RadioWiFiEditor(f.controller, state)
                                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    RadioWiFiSettings(f.controller, state)
                                }
                                RadioWiFiDialogs(f.controller, state, f.transport)
                            }
                        }
                    }
                }
            }
            block(host.get())
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, animatorScale)
            host.pause().stop().destroy()
        }
    }

    private class Radio(private val delegate: MockTransport = MockTransport()) : MeshTransport by delegate {
        val commands = mutableListOf<String>()
        var enabled = true
        var ssid = "Old"
        var password = "secret"
        var rejectPassword = false
        var rejectDisable = false
        var statusFailures = 0
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
                command == "get wifi.status" -> if (statusFailures > 0) { statusFailures--; "ERR" } else "> connected"
                command == "get wifi.ip" -> "> 192.0.2.1"
                command == "get wifi.pwd" -> "> $password"
                command.startsWith("set wifi.ssid ") -> { ssid = command.removePrefix("set wifi.ssid "); "OK" }
                command.startsWith("set wifi.pwd ") -> if (rejectPassword) "ERR" else {
                    password = command.removePrefix("set wifi.pwd "); "OK"
                }
                command == "set wifi.enabled 0" -> if (rejectDisable) "ERR" else { enabled = false; "OK" }
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
        val serviceClock: SessionClock,
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
        return Fixture(radio, session, context, service, clock).also { fixture ->
            fixture.controller = RadioWiFiController(
                backgroundScope, { fixture.current }, { fixture.current != null }, { fixture.transport },
            )
        }
    }
}
