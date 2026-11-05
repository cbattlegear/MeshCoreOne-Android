// PortedFrom: MC1Tests/Protocol/LPPDataPointDisplayTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Formatters/LocationReportFormatTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Mounted resource-locale changes must reach real telemetry, history report and chart consumers.
package com.meshcoreone.android.feature.remotenodes.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.lpp.LPPEncoder
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import com.meshcoreone.android.feature.remotenodes.history.ChartDataPoint
import com.meshcoreone.android.feature.remotenodes.history.NeighborChart
import com.meshcoreone.android.feature.remotenodes.status.FakeFaults
import com.meshcoreone.android.feature.remotenodes.status.NodeStatusStateHolder
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class RemoteLocaleMeasurementComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var previousLocale: Locale
    private val tag = mutableStateOf("en-US")
    private val key = Bytes(ByteArray(32) { 1 })
    private val now = Instant.parse("2024-07-13T12:00:00Z")
    private val clock = object : RemoteNodesClock {
        override val now: Instant get() = this@RemoteLocaleMeasurementComposeTest.now
        override val elapsed = Duration.ZERO
        override suspend fun sleep(duration: Duration) = error("Rendering must not sleep")
    }

    @Before fun openWindow() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After fun closeWindow() {
        try {
            activity.pause().stop().destroy()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Composable private fun ResourceLocale(content: @Composable () -> Unit) {
        val configuration = remember(tag.value) {
            Configuration(activity.get().resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag.value)))
            }
        }
        val context = remember(configuration) { activity.get().createConfigurationContext(configuration) }
        CompositionLocalProvider(
            LocalConfiguration provides configuration,
            LocalContext provides context,
            LocalResources provides context.resources,
            LocalDensity provides Density(1f, 2f),
        ) {
            MeshCoreTheme(motionScale = 0f, content = content)
        }
    }

    private fun changeLocale(value: String) {
        compose.runOnIdle { tag.value = value }
        assertEquals("The process locale stays US while only app resources change", Locale.US, Locale.getDefault())
    }

    @Test fun decodedTelemetryTracksResourceLocaleWithoutChangingStoredSIValues() {
        val holder = NodeStatusStateHolder(clock, FakeFaults)
        holder.configureForDirectTelemetry(key)
        holder.setTelemetryExpanded(true)
        val encoder = LPPEncoder().apply {
            addTemperature(1u, 25.5)
            addBarometer(2u, 1013.2)
            add(3u, LPPSensorType.ALTITUDE, LPPValue.Float(42.0))
            addVoltage(4u, 3.85)
        }
        runBlocking { holder.handleTelemetryResponse(TelemetryResponse(key.prefix(6), null, encoder.encode())) }
        val originalPoints = holder.state.value.cachedDataPoints
        assertEquals(4, originalPoints.size)
        activity.get().setContent {
            ResourceLocale {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteTelemetrySection(holder, false, { error("Locale changes must not request telemetry") }, {})
                }
            }
        }
        compose.onNodeWithText("77.9 \u00b0F").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("138 ft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3.850 V").performScrollTo().assertIsDisplayed()
        changeLocale("de-DE")
        for (value in listOf("25,5 \u00b0C", "1.013,2 hPa", "42,0 m", "3,850 V")) {
            compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("77.9 \u00b0F").assertDoesNotExist()
        changeLocale("en-US-u-ms-metric")
        compose.onNodeWithText("25.5 \u00b0C").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("42.0 m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("138 ft").assertDoesNotExist()
        assertEquals(originalPoints, holder.state.value.cachedDataPoints)
    }

    @Test fun historyReportsAndChartReadoutsUseTheSameResourceLocaleAndUnits() {
        val reports = listOf(
            NodeStatusSnapshotDTO(
                nodePublicKey = key, timestamp = now.minusSeconds(10_800),
                telemetryEntries = listOf(TelemetrySnapshotEntry(1, "Temperature", 20.5)).snapshot(),
            ),
            NodeStatusSnapshotDTO(
                nodePublicKey = key, timestamp = now.minusSeconds(7_200),
                latitude = 37.784712, longitude = -122.401233, altitude = 42.0,
                telemetryEntries = listOf(TelemetrySnapshotEntry(1, "Temperature", 25.5)).snapshot(),
            ),
        ).snapshot()
        var snapshotReads = 0
        var contactReads = 0
        var contactsReads = 0
        var discoveredReads = 0
        var failSnapshots = false
        val store = object : RemoteNodeHistoryStore {
            override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> {
                snapshotReads++
                if (failSnapshots) throw IllegalStateException("Offline history fixture")
                return reports
            }
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                contactReads++
                return null
            }
            override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> {
                contactsReads++
                return SnapshotList.empty()
            }
            override suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO> {
                discoveredReads++
                return SnapshotList.empty()
            }
        }
        fun text(id: Int) = activity.get().createConfigurationContext(
            Configuration(activity.get().resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag.value)))
            },
        ).getString(id)
        fun assertReads(expected: Int) {
            compose.runOnIdle {
                assertEquals(expected, snapshotReads)
                assertEquals(expected, contactReads)
                assertEquals(expected, contactsReads)
                assertEquals(expected, discoveredReads)
            }
        }
        fun installHistoryRoute() {
            activity.get().setContent {
                ResourceLocale {
                    RemoteNodeHistoryRoute(
                        key, RadioId(UUID(0, 1)), false, store, Modifier, { _, _, _, _, _, _, _ -> }, clock,
                    )
                }
            }
        }
        installHistoryRoute()
        compose.onNode(hasText(": 77.9 \u00b0F", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(L.remoteNodesHistoryAll)).performScrollTo().performClick().assertIsSelected()
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(slider).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNode(hasText(": 68.9 \u00b0F", substring = true)).performScrollTo().assertIsDisplayed()
        assertReads(1)
        compose.onNode(hasText(" \u00b7 37.7847, -122.4012 \u00b7 138 ft", substring = true))
            .performScrollTo().assertIsDisplayed()
        changeLocale("de-DE")
        compose.onNodeWithText(text(L.remoteNodesHistoryAll)).performScrollTo().assertIsSelected()
        compose.onNode(hasText(": 20,5 \u00b0C", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(" \u00b7 37.7847, -122.4012 \u00b7 42 m", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(": 77.9 \u00b0F", substring = true)).assertDoesNotExist()
        compose.onNode(hasText(" \u00b7 138 ft", substring = true)).assertDoesNotExist()
        assertReads(1)
        compose.runOnIdle { failSnapshots = true }
        compose.onNodeWithText(text(L.remoteNodesStatusRefresh)).performScrollTo().performClick()
        compose.onNode(hasText(": 20,5 \u00b0C", substring = true)).performScrollTo().assertIsDisplayed()
        assertReads(2)
        compose.onNode(hasTestTag("remote-error")).performScrollTo().assertIsDisplayed()
        changeLocale("en-US-u-ms-metric")
        compose.onNodeWithText(text(L.remoteNodesHistoryAll)).performScrollTo().assertIsSelected()
        compose.onNode(hasText(": 20.5 \u00b0C", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(" \u00b7 37.7847, -122.4012 \u00b7 42 m", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasTestTag("remote-error")).performScrollTo().assertIsDisplayed()
        assertReads(2)
        val beforeRecreation = activity.get()
        activity.recreate()
        assertTrue("The host must be recreated", beforeRecreation !== activity.get())
        installHistoryRoute()
        compose.waitForIdle()
        compose.onNodeWithText(text(L.remoteNodesHistoryAll)).performScrollTo().assertIsSelected()
        compose.onNode(hasText(": 20.5 \u00b0C", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(": 25.5 \u00b0C", substring = true)).assertDoesNotExist()
        compose.onNode(hasTestTag("remote-error")).performScrollTo().assertIsDisplayed()
        assertReads(2)
    }

    @Test fun neighborSelectionSurvivesAResolvedTitleChangeForTheSamePrefix() {
        val prefix = Bytes.of(0x01, 0x02)
        val points = listOf(
            ChartDataPoint(UUID(0, 1), now.minusSeconds(7_200), 4.0),
            ChartDataPoint(UUID(0, 2), now.minusSeconds(3_600), 9.0),
        )
        val neighbor = NeighborChart(prefix, "Alpha", points)
        val stableIdentity = "neighbor:0102"
        val chart = mutableStateOf(neighbor.chart.copy(selectionIdentity = stableIdentity))
        activity.get().setContent {
            ResourceLocale {
                Column(Modifier.verticalScroll(rememberScrollState())) { RemoteMetricChart(chart.value) }
            }
        }
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(slider and hasContentDescription("Alpha.", substring = true))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNode(hasText("Alpha: 4.0 dB", substring = true)).assertIsDisplayed()
        compose.runOnIdle {
            chart.value = neighbor.copy(name = "Beta").chart.copy(selectionIdentity = stableIdentity)
        }
        compose.onNode(hasText("Beta: 4.0 dB", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Beta: 9.0 dB", substring = true)).assertDoesNotExist()
    }

    @Test fun sameTimestampChartsKeepTheirOwnSelectionWhenLocaleReordersThemAndResetForAnotherNode() {
        val node = mutableStateOf(key)
        var snapshotReads = 0
        val store = object : RemoteNodeHistoryStore {
            override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> {
                snapshotReads++
                return listOf(
                    NodeStatusSnapshotDTO(
                        nodePublicKey = nodePublicKey, timestamp = now.minusSeconds(10_800),
                        telemetryEntries = listOf(
                            TelemetrySnapshotEntry(1, "Humidity", 40.5),
                            TelemetrySnapshotEntry(1, "Power", 10.5),
                        ).snapshot(),
                    ),
                    NodeStatusSnapshotDTO(
                        nodePublicKey = nodePublicKey, timestamp = now.minusSeconds(7_200),
                        telemetryEntries = listOf(
                            TelemetrySnapshotEntry(1, "Humidity", 60.5),
                            TelemetrySnapshotEntry(1, "Power", 20.5),
                        ).snapshot(),
                    ),
                ).snapshot()
            }
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? = null
            override suspend fun fetchContacts(radioId: RadioId) = SnapshotList.empty<ContactDTO>()
            override suspend fun fetchDiscoveredNodes(radioId: RadioId) = SnapshotList.empty<DiscoveredNodeDTO>()
        }
        activity.get().setContent {
            ResourceLocale {
                RemoteNodeHistoryRoute(
                    node.value, RadioId(UUID(0, 1)), false, store, Modifier, { _, _, _, _, _, _, _ -> }, clock,
                )
            }
        }
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        compose.onNode(slider and hasContentDescription("Humidity.", substring = true))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNode(hasText(": 40.5 %", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(": 20.5 W", substring = true)).performScrollTo().assertIsDisplayed()
        val humidityBefore = compose.onNodeWithText("Humidity").fetchSemanticsNode().positionInRoot.y
        val powerBefore = compose.onNodeWithText("Power").fetchSemanticsNode().positionInRoot.y
        assertTrue("English orders Humidity before Power", humidityBefore < powerBefore)
        compose.runOnIdle { assertEquals(1, snapshotReads) }

        changeLocale("de-DE")
        compose.onNode(hasText(": 40,5 %", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(": 20,5 W", substring = true)).performScrollTo().assertIsDisplayed()
        val humidityAfter = compose.onNodeWithText("Luftfeuchtigkeit").fetchSemanticsNode().positionInRoot.y
        val powerAfter = compose.onNodeWithText("Leistung").fetchSemanticsNode().positionInRoot.y
        assertTrue("German orders Leistung before Luftfeuchtigkeit", powerAfter < humidityAfter)
        compose.runOnIdle { assertEquals(1, snapshotReads) }

        compose.runOnIdle { node.value = Bytes(ByteArray(32) { 2 }) }
        compose.onNode(hasText(": 60,5 %", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(": 20,5 W", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(": 40,5 %", substring = true)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, snapshotReads) }
    }
}
