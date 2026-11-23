// AndroidOnly: WP-403 Real session and Room producers for widget metrics.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.BatteryServices
import com.meshcoreone.android.app.state.WidgetBatteryReading
import com.meshcoreone.android.app.freshWidgetBattery
import com.meshcoreone.android.app.widgetRadioStatus
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.platform.widgets.WidgetConnectionView
import com.meshcoreone.android.platform.widgets.WidgetRadioStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRadioStatusTest : RoomProcessTest() {
    @Test fun widgetReceivesPhysicalArrivalsAndPersistedUnreadAndDropsRetiredMetrics() = runTest {
        val epoch = Instant.parse("2026-01-01T00:00:00Z")
        val wall = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
            override fun instant() = epoch.plusMillis(testScheduler.currentTime)
        }
        var radioKey = 7
        val fixture = RxIngestionFixture(
            this, store, identity = { key(radioKey) }, sessionClock = SystemSessionClock(wall), batteryMillivolts = 4200,
        )
        var view: WidgetConnectionView? = null
        val observing = backgroundScope.launch { fixture.harness.container.widgetRadioStatus().collect { view = it } }
        try {
            fixture.connect()
            val token = fixture.live.token
            val channel = checkNotNull(store.fetchChannel(fixture.radio, 0u))
            val key = EntityKey(fixture.radio, channel.id)
            fixture.harness.eventually("initial widget metrics", virtualBudgetMillis = 30_000, virtualStepMillis = 250) {
                view?.metrics?.packetsPerMinute == 0L && view?.metrics?.unread == 0L && view?.metrics?.batteryPercent == 100L
            }
            val packet = fixture.packet(Bytes.of(1, 2, 3), type = 15)
            fixture.ingest(packet)
            store.incrementChannelUnreadCount(key)
            advanceTimeBy(15_000)
            fixture.harness.eventually("one live packet and one persisted unread", virtualBudgetMillis = 20_000, virtualStepMillis = 100) {
                view?.metrics?.packetsPerMinute == 1L && view?.metrics?.unread == 1L
            }
            store.clearChannelUnreadCount(key)
            advanceTimeBy(15_000)
            fixture.harness.eventually("persisted unread cleared", virtualBudgetMillis = 20_000, virtualStepMillis = 100) {
                view?.metrics?.unread == 0L
            }
            assertEquals(1L, view?.metrics?.packetsPerMinute)
            advanceTimeBy(60_000)
            fixture.harness.eventually("trailing minute decayed") { view?.metrics?.packetsPerMinute == 0L }
            assertEquals(1, store.fetchRxLogEntries(fixture.radio).size)

            fixture.ingest(packet)
            advanceTimeBy(15_000)
            fixture.harness.eventually("packet before disconnect") { view?.metrics?.packetsPerMinute == 1L }
            fixture.harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            fixture.harness.eventually("disconnected widget") { view?.status == WidgetRadioStatus.DISCONNECTED }
            assertNull(view?.metrics)
            fixture.connect()
            assertEquals(token.radioId, fixture.live.token.radioId)
            assertNotEquals(token, fixture.live.token)
            fixture.harness.eventually("fresh RX rate on same radio") { view?.metrics?.packetsPerMinute == 0L }

            store.incrementChannelUnreadCount(key)
            fixture.harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            fixture.harness.eventually("same radio retired") { view?.status == WidgetRadioStatus.DISCONNECTED }
            radioKey++
            fixture.connect()
            assertNotEquals(token.radioId, fixture.live.token.radioId)
            fixture.harness.eventually("new radio has its own persisted count", virtualBudgetMillis = 20_000, virtualStepMillis = 100) {
                view?.metrics?.unread == 0L
            }
        } finally {
            observing.cancel()
            fixture.harness.container.close()
        }
    }

    @Test fun batteryExpiresAndOnlyTheExactSessionSourceCanPublishIt() = runTest {
        fun source() = object : BatteryServices {
            override suspend fun getBattery() = BatteryInfo(3900)
            override suspend fun postLowBatteryNotification(deviceName: String, batteryPercentage: Long) = Unit
        }
        val primary = source()
        val previous = source()
        val epoch = Instant.parse("2026-01-01T00:00:00Z")
        val readings = MutableStateFlow<WidgetBatteryReading?>(WidgetBatteryReading(previous, 80, epoch))
        val output = mutableListOf<Long?>()
        backgroundScope.launch {
            freshWidgetBattery(readings, primary) { epoch.plusMillis(testScheduler.currentTime) }
                .collect { output += it }
        }
        runCurrent()
        assertEquals(listOf<Long?>(null), output)
        readings.value = WidgetBatteryReading(primary, 0, epoch.plusSeconds(1))
        runCurrent()
        assertEquals(listOf<Long?>(null), output, "Future timestamp is not fresh")
        readings.value = WidgetBatteryReading(primary, 0, epoch)
        runCurrent()
        assertEquals(listOf(null, 0L), output, "A genuine zero is not unknown")
        advanceTimeBy(900_000)
        runCurrent()
        assertEquals(listOf(null, 0L, null), output)
    }
}
