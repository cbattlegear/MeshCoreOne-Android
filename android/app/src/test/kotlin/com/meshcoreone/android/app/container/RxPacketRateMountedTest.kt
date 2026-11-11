// AndroidOnly: WP-402 Real radio frames -> session RX ingestion -> Room/service adapter -> mounted Tools rate.
package com.meshcoreone.android.app.container

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsScreen
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeed
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h1000dp-mdpi")
class RxPacketRateMountedTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun physicalArrivalsReachVisibleRateWithoutCountingHistoryAndReconnectStartsFresh() = runTest {
        val fixture = RxIngestionFixture(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val mounted = mutableStateOf(true)
        val collectors = AtomicInteger()
        try {
            host.windowFocusChanged(true)
            fixture.connect()
            val packet = fixture.packet(Bytes.of(1, 2, 3), type = 15)
            fixture.ingest(packet)
            fixture.ingest(packet)
            val original = fixture.harness.container.createToolsDiagnosticsDependencies()
            val dependencies = original.copy(rxLog = original.rxLog.copy(rxLogService = {
                original.rxLog.rxLogService()?.let { feed ->
                    object : RxLogFeed by feed {
                        override fun packetRates(): Flow<Long> = flow {
                            collectors.incrementAndGet()
                            try { emitAll(feed.packetRates()) }
                            finally { collectors.decrementAndGet() }
                        }
                    }
                }
            }))
            host.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        if (mounted.value) ToolsDiagnosticsScreen(dependencies, ToolSelection.RX_LOG)
                    }
                }
            }
            fun awaitRate(rate: Long) {
                compose.waitUntil(5_000) {
                    fixture.harness.scheduler.runCurrent()
                    compose.onAllNodesWithText("$rate packets per minute").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("$rate packets per minute").assertIsDisplayed()
                assertEquals(1, collectors.get(), "Only one visible rate collector")
            }
            awaitRate(2)
            compose.onNodeWithText(host.get().getString(AppToolsStrings.toolsRxLogFilterDirectOnly))
                .performScrollTo().performClick()
            compose.onNodeWithText("2 packets per minute").assertIsDisplayed()
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.waitUntil(5_000) { collectors.get() == 0 }
            fixture.ingest(packet)
            compose.runOnIdle { mounted.value = true }
            awaitRate(3)

            host.pause().stop()
            compose.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idle() }
            compose.waitUntil(5_000) { collectors.get() == 0 }
            fixture.ingest(packet)
            host.restart().start().resume().visible()
            host.windowFocusChanged(true)
            awaitRate(4)
            assertEquals(4, store.fetchRxLogEntries(fixture.radio).size)

            val oldService = fixture.live.rxLogService
            fixture.harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            fixture.harness.settle()
            compose.waitForIdle()
            compose.waitUntil(5_000) {
                fixture.harness.scheduler.runCurrent()
                collectors.get() == 0
            }
            compose.onNodeWithText("4 packets per minute").assertDoesNotExist()
            assertEquals(0L, oldService.packetRates().first())
            fixture.connect()
            assertTrue(oldService !== fixture.live.rxLogService)
            awaitRate(0)
            assertEquals(4, store.fetchRxLogEntries(fixture.radio).size, "History survives, rate does not")
            fixture.ingest(packet)
            awaitRate(1)
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            host.pause().stop().destroy()
            fixture.harness.container.close()
        }
    }
}
