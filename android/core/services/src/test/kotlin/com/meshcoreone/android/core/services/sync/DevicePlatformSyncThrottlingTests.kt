// PortedFrom: MC1Services/Tests/MC1ServicesTests/DevicePlatformSyncThrottlingTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.TransportType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Original DevicePlatformChannelSyncConfigTests (file DevicePlatformSyncThrottlingTests.swift). */
class DevicePlatformSyncThrottlingTests {
    private val suite = "DevicePlatformChannelSyncConfigTests"
    private fun case(name: String, body: suspend SyncTestScope.() -> Unit) = syncCase(suite, name, body)

    private fun SyncTestScope.config(platform: DevicePlatform, transport: TransportType): ChannelSyncConfig {
        val (controller, host) = retryController()
        host.detectedPlatform = platform
        return controller.currentChannelSyncConfig(newRadio(), transport)
    }

    @TestFactory
    fun cases(): List<DynamicTest> = listOf(
        pureCase(suite, "ESP32 has 30s channel sync skip window") { assertEquals(30.seconds, DevicePlatform.ESP32.channelSyncSkipWindow) },
        pureCase(suite, "nRF52 has zero channel sync skip window") { assertEquals(Duration.ZERO, DevicePlatform.NRF52.channelSyncSkipWindow) },
        pureCase(suite, "Unknown has zero channel sync skip window") { assertEquals(Duration.ZERO, DevicePlatform.UNKNOWN.channelSyncSkipWindow) },
        case("WiFi uses ESP32 channel sync cooldown") {
            val (controller, host) = retryController()
            val radioId = newRadio()
            val attemptedAt = clock.now()
            host.detectedPlatform = DevicePlatform.ESP32
            host.lastAttemptedChannelSync = radioId to attemptedAt
            val config = controller.currentChannelSyncConfig(radioId, TransportType.WIFI)
            assertEquals(30.seconds, config.channelSyncSkipWindow)
            assertEquals(attemptedAt, config.lastAttemptedChannelSync)
        },
        case("nRF52 over BLE enables pipelined channel reads") {
            assertTrue(config(DevicePlatform.NRF52, TransportType.BLUETOOTH).usePipelinedChannelRead)
        },
        case("nRF52 over WiFi does not pipeline channel reads") {
            assertFalse(config(DevicePlatform.NRF52, TransportType.WIFI).usePipelinedChannelRead)
        },
        case("ESP32 over BLE does not pipeline channel reads") {
            assertFalse(config(DevicePlatform.ESP32, TransportType.BLUETOOTH).usePipelinedChannelRead)
        },
        case("ESP32 over WiFi enables pipelined channel reads") {
            assertTrue(config(DevicePlatform.ESP32, TransportType.WIFI).usePipelinedChannelRead)
        },
        case("WiFi connect resolves a recognized ESP32 model to ESP32") {
            val (controller, host) = retryController()
            controller.detectAndStorePlatform("Heltec V3", TransportType.WIFI)
            assertEquals(DevicePlatform.ESP32, host.detectedPlatform)
        },
        case("WiFi connect resolves an unrecognized model to ESP32 (WiFi implies ESP32-class)") {
            val (controller, host) = retryController()
            controller.detectAndStorePlatform("Totally Unknown Radio 9000", TransportType.WIFI)
            assertEquals(DevicePlatform.ESP32, host.detectedPlatform)
            val config = controller.currentChannelSyncConfig(newRadio(), TransportType.WIFI)
            assertEquals(30.seconds, config.channelSyncSkipWindow)
            assertTrue(config.usePipelinedChannelRead)
        },
        case("BLE connect leaves an unrecognized model as unknown (no WiFi coalesce)") {
            val (controller, host) = retryController()
            controller.detectAndStorePlatform("Totally Unknown Radio 9000", TransportType.BLUETOOTH)
            assertEquals(DevicePlatform.UNKNOWN, host.detectedPlatform)
        },
        case("BLE connect resolves an nRF52 model to nRF52") {
            val (controller, host) = retryController()
            controller.detectAndStorePlatform("T1000-E", TransportType.BLUETOOTH)
            assertEquals(DevicePlatform.NRF52, host.detectedPlatform)
        },
        case("Heartbeat pauses while syncing") {
            val (controller, host) = retryController()
            host.connectionState = DeviceConnectionState.SYNCING
            assertTrue(controller.shouldPauseWiFiHeartbeatProbe)
        },
    )
}
