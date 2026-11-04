// AndroidOnly: WP-313 Mounted history recency regression with a deterministic clock and local map surface; not device acceptance.
package com.meshcoreone.android.feature.remotenodes.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import com.meshcoreone.android.feature.remotenodes.history.LocationReportRowText
import com.meshcoreone.android.feature.remotenodes.support.bytes
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
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
class RemoteHistoryRecencyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var previousLocale: Locale

    @Before fun openWindow() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }
    @After fun closeWindow() {
        try {
            controller.pause().stop().destroy()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private val now = Instant.parse("2024-07-13T12:00:00Z")
    private val report = NodeStatusSnapshotDTO(
        nodePublicKey = bytes(32, 1),
        timestamp = now.minusSeconds(7_200),
        latitude = 37.784712,
        longitude = -122.401233,
        altitude = 0.0,
    )
    private val clock = object : RemoteNodesClock {
        override val now: Instant get() = this@RemoteHistoryRecencyTest.now
        override val elapsed = Duration.ZERO
        override suspend fun sleep(duration: Duration) = error("History rendering must not sleep")
    }
    private val store = object : RemoteNodeHistoryStore {
        override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?) = SnapshotList.of(report)
        override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? = null
        override suspend fun fetchContacts(radioId: RadioId) = SnapshotList.empty<ContactDTO>()
        override suspend fun fetchDiscoveredNodes(radioId: RadioId) = SnapshotList.empty<DiscoveredNodeDTO>()
    }
    private val mapSurface: RemoteNodesMapSurface = { state, _, _, _, _, select, modifier ->
        TextButton(onClick = { select(state.points.single()) }, modifier = modifier) { Text("Select fixture report") }
    }

    private fun mount() {
        controller.get().setContent {
            RemoteNodeHistoryRoute(
                report.nodePublicKey, RadioId(UUID(0, 1)), false, store, Modifier, mapSurface, clock,
            )
        }
        compose.waitForIdle()
    }

    private fun detail(): String {
        val locale = Locale.getDefault()
        return LocationReportRowText.detailLine(report, locale, ZoneId.systemDefault(), MeasurementSystem.of(locale))
    }

    private val headline: String get() = if (Build.VERSION.SDK_INT == 31) "2 hr. ago" else "2h ago"

    @Test fun listHeadlineAndDetailsOpenTheSameReportWithRecencyOnTheFullMap() {
        mount()
        compose.onNodeWithText(detail()).performScrollTo()
            .assertTextContains(headline)
            .performClick()
        val fullMap = hasAnyAncestor(hasTestTag("remote-history-full-map"))
        compose.onNode(hasText(headline) and fullMap).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(detail()) and fullMap).performScrollTo().assertIsDisplayed()
    }

    @Test fun selectingInlineMapReportShowsRecencyWithoutRemovingAbsoluteDetails() {
        mount()
        compose.onNodeWithText("Select fixture report").performScrollTo().performClick()
        compose.onNode(hasText(headline) and !hasClickAction()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(detail()) and !hasClickAction()).performScrollTo().assertIsDisplayed()
    }
}
