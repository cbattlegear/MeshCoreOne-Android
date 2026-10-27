// PortedFrom: MC1Tests/Formatters/LocationReportFormatTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import com.meshcoreone.android.feature.remotenodes.history.LocationReportFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp")
class RemoteNodeHistoryRecencyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var previousLocale: Locale
    private lateinit var previousZone: TimeZone
    private val now = Instant.ofEpochSecond(1_000_000)
    private val key = Bytes(ByteArray(32) { 1 })
    private val radio = RadioId(UUID(0, 1))
    private val report = NodeStatusSnapshotDTO(
        id = UUID(0, 2),
        nodePublicKey = key,
        timestamp = now.minusSeconds(2 * 3600),
        latitude = 37.784712,
        longitude = -122.401233,
        altitude = 0.0,
    )
    private val clock = object : RemoteNodesClock {
        override val now: Instant get() = this@RemoteNodeHistoryRecencyTest.now
        override val elapsed = Duration.ZERO
        override suspend fun sleep(duration: Duration) = error("No clock sleeps expected in history rendering")
    }

    @Before fun openWindow() {
        previousLocale = Locale.getDefault()
        previousZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After fun closeWindow() {
        activity.pause().stop().destroy()
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousZone)
    }

    @Test fun pastReportRendersNonemptyRelativeTextThroughAndroidIcu() {
        val text = LocationReportFormat.relativeTime(report.timestamp, now, ZoneId.of("UTC")).format(Locale.US)
        assertTrue(text.isNotEmpty())
        assertEquals(twoHoursAgoAndroidText(), text)
    }

    @Test fun mountedHistoryShowsRecencyAndAbsoluteDetailAndStillOpensTheSelectedReport() {
        var snapshotReads = 0
        val store = object : RemoteNodeHistoryStore {
            override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> {
                assertEquals(key, nodePublicKey)
                snapshotReads++
                return listOf(report).snapshot()
            }
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? = null
            override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> = emptyList<ContactDTO>().snapshot()
            override suspend fun fetchDiscoveredNodes(radioId: RadioId): SnapshotList<DiscoveredNodeDTO> =
                emptyList<DiscoveredNodeDTO>().snapshot()
        }
        val fixtureMap: RemoteNodesMapSurface = { _, _, _, _, _, _, modifier -> Box(modifier) }
        activity.get().setContent {
            MaterialTheme {
                RemoteNodeHistoryRoute(key, radio, false, store, Modifier, fixtureMap, clock)
            }
        }
        val row = compose.onNodeWithTag("remote-history-report:${report.id}")
        row.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val expectedRecency = twoHoursAgoAndroidText()
        assertTrue(expectedRecency.isNotEmpty())
        compose.onNodeWithTag("remote-history-recency:${report.id}", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals(expectedRecency)
        row.assertTextContains("37.7847, -122.4012", substring = true)
        row.assertTextContains("0 ft", substring = true)
        val absoluteTime = LocationReportFormat.absoluteTime(report.timestamp, Locale.US, ZoneId.of("UTC"))
        assertTrue(absoluteTime.isNotEmpty())
        row.assertTextContains(absoluteTime, substring = true)
        compose.runOnIdle { assertEquals(1, snapshotReads) }
        row.performClick()
        compose.onNodeWithTag("remote-history-full-map").assertIsDisplayed()
        compose.onNode(
            hasText(absoluteTime, substring = true) and hasAnyAncestor(hasTestTag("remote-history-full-map")),
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
            .assertTextContains("37.7847, -122.4012", substring = true)
            .assertTextContains("0 ft", substring = true)
    }

    private fun twoHoursAgoAndroidText(): String = RelativeDateTimeFormatter.getInstance(
        ULocale.US, null, RelativeDateTimeFormatter.Style.NARROW, DisplayContext.CAPITALIZATION_NONE,
    ).format(2.0, RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.HOURS)
}
