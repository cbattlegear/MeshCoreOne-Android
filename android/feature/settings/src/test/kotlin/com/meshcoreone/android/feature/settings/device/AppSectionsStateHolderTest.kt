// AndroidOnly: WP-317 Behavior tests for notifications, stale-node cleanup, diagnostics, device info, the advanced page and navigation rules.
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.l10n.RetainedUiStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.support.FakeBattery
import com.meshcoreone.android.feature.settings.device.support.FakeCatalog
import com.meshcoreone.android.feature.settings.device.support.FakeChatPrefs
import com.meshcoreone.android.feature.settings.device.support.FakeConnection
import com.meshcoreone.android.feature.settings.device.support.FakeDiagnostics
import com.meshcoreone.android.feature.settings.device.support.FakeIdentity
import com.meshcoreone.android.feature.settings.device.support.FakeNotificationPermission
import com.meshcoreone.android.feature.settings.device.support.FakeNotificationPreferences
import com.meshcoreone.android.feature.settings.device.support.FakeSettingsPort
import com.meshcoreone.android.feature.settings.device.support.FakeStale
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import com.meshcoreone.android.feature.settings.device.support.device
import com.meshcoreone.android.feature.settings.device.support.notificationPreferences
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class NotificationSettingsStateHolderTest {
    private val scheduler = TestScheduler()
    private val prefs = FakeNotificationPreferences(notificationPreferences())
    private val permission = FakeNotificationPermission(NotificationAuthorization.NOT_DETERMINED)
    private var stored = StoredDiscoveryChoices(false, false, false)
    private val holder = NotificationSettingsStateHolder(scheduler.environment(), prefs, permission) { stored }
    private val state get() = holder.state.value

    private fun started(): NotificationSettingsStateHolder { holder.start(); scheduler.runCurrent(); return holder }

    @Test
    fun `toggles are hidden until the system permission is granted`() {
        started()
        assertFalse(state.showsToggles)
        holder.requestAuthorization(); scheduler.runCurrent()
        assertEquals(NotificationAuthorization.AUTHORIZED, state.authorization)
        assertTrue(state.showsToggles)
    }

    @Test
    fun `a refusal or a failed request both read as denied`() {
        permission.grant = false
        started()
        holder.requestAuthorization(); scheduler.runCurrent()
        assertEquals(NotificationAuthorization.DENIED, state.authorization)
        permission.requestError = IllegalStateException("x")
        holder.requestAuthorization(); scheduler.runCurrent()
        assertEquals(NotificationAuthorization.DENIED, state.authorization)
    }

    @Test
    fun `returning from system settings refreshes the status`() {
        started()
        permission.status = NotificationAuthorization.AUTHORIZED
        holder.onResume(); scheduler.runCurrent()
        assertTrue(state.showsToggles)
    }

    @Test
    fun `each toggle persists its own field`() {
        permission.status = NotificationAuthorization.AUTHORIZED
        started()
        holder.onContactMessages(false); holder.onChannelMessages(false); holder.onRoomMessages(false)
        holder.onReactions(false); holder.onLowBattery(false)
        scheduler.runCurrent()
        val last = prefs.flow.value
        assertFalse(last.contactMessagesEnabled || last.channelMessagesEnabled || last.roomMessagesEnabled || last.reactionNotificationsEnabled || last.lowBatteryEnabled)
        assertEquals(5, prefs.updates.size)
    }

    @Test
    fun `first activation of discovery turns all children on and later activations keep the user's choices`() {
        permission.status = NotificationAuthorization.AUTHORIZED
        started()
        holder.onNewContactDiscovered(true); scheduler.runCurrent()
        with(prefs.flow.value) { assertTrue(newContactDiscoveredEnabled && discoveryContactEnabled && discoveryRepeaterEnabled && discoveryRoomEnabled) }
        assertTrue(state.showsDiscoveryChildren)
        holder.onDiscoveryRepeater(false); holder.onNewContactDiscovered(false); scheduler.runCurrent()
        stored = StoredDiscoveryChoices(contact = true, repeater = true, room = true)
        holder.onNewContactDiscovered(true); scheduler.runCurrent()
        with(prefs.flow.value) { assertTrue(newContactDiscoveredEnabled); assertFalse(discoveryRepeaterEnabled) }
        assertFalse(NotificationSettingsState(preferences = notificationPreferences()).showsDiscoveryChildren)
    }
}

class StaleNodeCleanupStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device())
    private val port = FakeStale()
    private val holder = StaleNodeCleanupStateHolder(scheduler.environment(), connection, port)
    private val state get() = holder.state.value

    @Test
    fun `starts enabled only when a threshold is stored and the footer follows the state`() {
        holder.start(); scheduler.runCurrent()
        assertFalse(state.isEnabled)
        assertEquals(StaleCleanupFooter.DISABLED, state.footer)
        holder.onEnabledToggled(true)
        assertEquals(StaleCleanupFooter.SELECT_THRESHOLD, state.footer)
        holder.onThresholdSelected(14)
        assertEquals(StaleCleanupFooter.ENABLED, state.footer)
        connection.stateFlow.value = DeviceConnectionState.DISCONNECTED
        scheduler.runCurrent()
        assertEquals(StaleCleanupFooter.DISCONNECTED, state.footer)
    }

    @Test
    fun `a stored threshold starts the section on`() {
        port.days.value = 30
        val on = StaleNodeCleanupStateHolder(scheduler.environment(), connection, port)
        on.start(); scheduler.runCurrent()
        assertTrue(on.state.value.isEnabled)
    }

    @Test
    fun `picking a threshold while connected forces a cleanup and disconnected does not`() {
        holder.start(); scheduler.runCurrent()
        holder.onEnabledToggled(true)
        holder.onThresholdSelected(7)
        assertEquals(listOf(true), port.cleanups)
        holder.onThresholdSelected(0)
        assertEquals(listOf(true), port.cleanups)
        connection.stateFlow.value = DeviceConnectionState.CONNECTED
        scheduler.runCurrent()
        holder.onThresholdSelected(90)
        assertEquals(listOf(true), port.cleanups)
    }

    @Test
    fun `switching off resets the threshold and only the listed thresholds are accepted`() {
        holder.start(); scheduler.runCurrent()
        holder.onEnabledToggled(true); holder.onThresholdSelected(30)
        holder.onEnabledToggled(false)
        assertEquals(0, port.days.value)
        try { holder.onThresholdSelected(5); error("accepted") } catch (expected: IllegalArgumentException) { }
        assertEquals(listOf(0, 7, 14, 30, 90), StaleNodeCleanupStateHolder.THRESHOLD_CHOICES)
    }

    @Test
    fun `the last-run line needs a threshold and a previous run`() {
        port.last.value = Instant.parse("2024-01-01T00:00:00Z")
        port.days.value = 7
        holder.start(); scheduler.runCurrent()
        assertTrue(state.showsLastRun)
        holder.onEnabledToggled(false)
        assertFalse(state.showsLastRun)
    }
}

class DiagnosticsStateHolderTest {
    private val scheduler = TestScheduler()
    private val port = FakeDiagnostics()
    private val holder = DiagnosticsStateHolder(scheduler.environment(), port)
    private val state get() = holder.state.value

    @Test
    fun `export hands over the file once and a null result reports the export failure`() {
        holder.exportLogs()
        assertTrue(state.isExporting)
        scheduler.runCurrent()
        assertEquals("content://logs/1", state.exportedFile)
        assertFalse(state.isExporting)
        holder.onExportShared()
        assertNull(state.exportedFile)
        port.file = null
        holder.exportLogs(); scheduler.runCurrent()
        assertEquals(UiText.Resource(AppSettingsStrings.diagnosticsErrorExportFailed), state.errorMessage)
    }

    @Test
    fun `an export exception also reports the export failure and a second tap while exporting is ignored`() {
        port.exportError = IllegalStateException("io")
        holder.exportLogs(); holder.exportLogs()
        scheduler.runCurrent()
        assertEquals(UiText.Resource(AppSettingsStrings.diagnosticsErrorExportFailed), state.errorMessage)
    }

    @Test
    fun `clearing logs needs the confirmation first`() {
        holder.requestClearLogs()
        assertTrue(state.showingClearLogsAlert)
        assertEquals(0, port.clears)
        holder.dismissClearLogsAlert()
        scheduler.runCurrent()
        assertEquals(0, port.clears)
        holder.requestClearLogs()
        holder.confirmClearLogs(); scheduler.runCurrent()
        assertEquals(1, port.clears)
        assertFalse(state.showingClearLogsAlert)
    }

    @Test
    fun `a clear failure shows the described error`() {
        port.clearError = IllegalStateException("locked")
        holder.confirmClearLogs(); scheduler.runCurrent()
        assertEquals(UiText.Verbatim("locked"), state.errorMessage)
    }
}

class DeviceInfoStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device(firmwareVersionString = ""))
    private val identity = FakeIdentity()
    private val battery = FakeBattery()
    private val holder = DeviceInfoStateHolder(scheduler.environment(), connection, { identity }, battery)
    private val state get() = holder.state.value

    private fun started(): DeviceInfoStateHolder { holder.start(); scheduler.runCurrent(); return holder }

    @Test
    fun `fetches the battery on start and falls back for empty firmware strings`() {
        started()
        assertEquals(1, battery.fetches)
        assertNull(state.firmwareVersionText)
        assertEquals(9, state.firmwareVersionNumber)
        assertEquals("01 Jan 2025", state.buildDate)
        assertEquals("TestMfg", state.manufacturer)
        connection.deviceFlow.value = device(firmwareVersionString = "v1.13.0").copy(buildDate = "", manufacturerName = "")
        scheduler.runCurrent()
        assertEquals("v1.13.0", state.firmwareVersionText)
        assertNull(state.buildDate); assertNull(state.manufacturer)
    }

    @Test
    fun `storage and voltage presentation`() {
        started()
        battery.flow.value = DeviceBatterySnapshot(3.846, 80, usedStorageKB = 512, totalStorageKB = 1024)
        scheduler.runCurrent()
        assertEquals("3.85 V", state.voltageText)
        assertEquals(512L * 1024, state.storageUsedBytes)
        assertEquals(0.5, state.storageUsageRatio)
        assertEquals(StorageUsageBand.NORMAL, state.storageBand)
        for ((used, band) in mapOf(699 to StorageUsageBand.NORMAL, 700 to StorageUsageBand.WARNING, 899 to StorageUsageBand.WARNING, 900 to StorageUsageBand.CRITICAL)) {
            battery.flow.value = DeviceBatterySnapshot(4.0, 50, used, 1000); scheduler.runCurrent()
            assertEquals(band, state.storageBand, "$used")
        }
        battery.flow.value = DeviceBatterySnapshot(4.0, 50, null, null); scheduler.runCurrent()
        assertEquals(0.0, state.storageUsageRatio)
    }

    @Test
    fun `a failing battery fetch is quiet`() {
        battery.fetchError = IllegalStateException("x")
        started()
        assertNull(state.errorMessage)
        scheduler.run { holder.refreshBattery() }
        assertEquals(2, battery.fetches)
    }

    @Test
    fun `the name edit starts from the current name, caps at 31 UTF-8 bytes without splitting a character, and saves trimmed`() {
        started()
        holder.beginEditName()
        assertTrue(state.isEditingName)
        assertEquals("TestDevice", state.nodeName)
        holder.onNodeNameChanged("é".repeat(20))
        assertEquals("é".repeat(15), state.nodeName)
        holder.onNodeNameChanged("  ​ Node \n")
        holder.saveNodeName(); scheduler.runCurrent()
        assertEquals(listOf("Node"), identity.names)
        assertFalse(state.isSaving)
        assertFalse(state.isEditingName)
    }

    @Test
    fun `an empty name or a missing service saves nothing and cancel keeps the name`() {
        started()
        holder.beginEditName(); holder.onNodeNameChanged("   ")
        holder.saveNodeName(); scheduler.runCurrent()
        assertTrue(identity.names.isEmpty())
        holder.beginEditName(); holder.cancelEditName()
        assertFalse(state.isEditingName)
        val orphan = DeviceInfoStateHolder(scheduler.environment(), connection, { null }, battery)
        orphan.start(); scheduler.runCurrent()
        orphan.beginEditName(); orphan.saveNodeName(); scheduler.runCurrent()
        assertTrue(identity.names.isEmpty())
    }

    @Test
    fun `name save failures route to retry or error and cancellation propagates`() {
        started()
        holder.beginEditName(); holder.onNodeNameChanged("A")
        identity.error = SettingsServiceException(SettingsServiceError.SendFailed)
        holder.saveNodeName(); scheduler.runCurrent()
        assertTrue(holder.retryAlertState.value.isPresented)
        identity.error = SettingsServiceException(SettingsServiceError.InvalidResponse)
        holder.retry.retry(); scheduler.runCurrent()
        assertTrue(state.errorMessage != null)
        identity.error = null
        holder.dismissError()
        holder.retry.cancel()
        assertTrue(state.nameEditEnabled)
    }
}

class AdvancedSettingsStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device(firmwareVersion = 11u, firmwareVersionString = "v1.14.0"))
    private val service = FakeSettingsPort()
    private val holder = AdvancedSettingsStateHolder(scheduler.environment(), connection) { service }

    @Test
    fun `refreshes self info, auto-add config and default flood scope for capable firmware`() {
        holder.start(); scheduler.runCurrent()
        assertEquals(listOf("getSelfInfo", "refreshAutoAddConfig", "getDefaultFloodScope"), service.calls)
    }

    @Test
    fun `older firmware skips the capability reads and a held sync gate skips all reads`() {
        connection.deviceFlow.value = device(firmwareVersion = 9u, firmwareVersionString = "v1.11.0")
        connection.startupFlow.value = false
        holder.start(); scheduler.runCurrent()
        assertTrue(service.calls.isEmpty())
        connection.startupFlow.value = true
        scheduler.runCurrent()
        assertEquals(listOf("getSelfInfo"), service.calls)
    }

    @Test
    fun `read failures are ignored`() {
        service.failNext("getSelfInfo", IllegalStateException("x"))
        holder.start(); scheduler.runCurrent()
        assertTrue(service.calls.contains("getDefaultFloodScope"))
    }

    @Test
    fun `the page dismisses when the radio disappears`() {
        holder.start(); scheduler.runCurrent()
        assertEquals(0, scheduler.dismissCount)
        connection.deviceFlow.value = null
        scheduler.runCurrent()
        assertEquals(1, scheduler.dismissCount)
    }

    @Test
    fun `starting with no radio does not dismiss until one has been seen`() {
        connection.deviceFlow.value = null
        holder.start(); scheduler.runCurrent()
        assertEquals(0, scheduler.dismissCount)
        connection.deviceFlow.value = device()
        scheduler.runCurrent()
        connection.deviceFlow.value = null
        scheduler.runCurrent()
        assertEquals(1, scheduler.dismissCount)
    }

    @Test
    fun `cancellation of a read is not swallowed`() {
        service.failNext("getSelfInfo", CancellationException("c"))
        var thrown: Throwable? = null
        scheduler.scope.launch { try { holder.refreshDeviceSettings() } catch (e: CancellationException) { thrown = e } }
        scheduler.runCurrent()
        assertTrue(thrown is CancellationException)
    }
}

class SettingsNavigationTest {
    @Test
    fun `device pages need a device and the app pages never do`() {
        assertEquals(setOf(SettingsDetail.DEVICE_INFO, SettingsDetail.RADIO, SettingsDetail.LOCATION, SettingsDetail.CONNECTION, SettingsDetail.ADVANCED),
            SettingsDetail.entries.filter { it.requiresDevice }.toSet())
        assertEquals(9, SettingsListPresentation.detailsFor(hasDevice = false).size) // 8 ported pages + the AndroidOnly Prizm Observer page
        assertEquals(SettingsDetail.entries, SettingsListPresentation.detailsFor(hasDevice = true))
    }

    @Test
    fun `a device-only selection clears when the radio goes away`() {
        assertNull(SettingsListPresentation.selectionAfterDeviceChange(SettingsDetail.RADIO, hasDevice = false))
        assertEquals(SettingsDetail.MAPS, SettingsListPresentation.selectionAfterDeviceChange(SettingsDetail.MAPS, hasDevice = false))
        assertEquals(SettingsDetail.RADIO, SettingsListPresentation.selectionAfterDeviceChange(SettingsDetail.RADIO, hasDevice = true))
        assertNull(SettingsListPresentation.selectionAfterDeviceChange(null, hasDevice = false))
    }

    @Test
    fun `radio row names the matching preset, repeat presets by frequency, or custom`() {
        val catalog = FakeCatalog()
        assertEquals(RadioDetailText.Preset("USA/Canada (915)"), SettingsListPresentation.radioDetailText(device(frequency = 915_000u), null, catalog))
        assertEquals(RadioDetailText.Custom, SettingsListPresentation.radioDetailText(device(frequency = 902_000u), null, catalog))
        assertEquals(RadioDetailText.Preset("Repeat 1"), SettingsListPresentation.radioDetailText(device(frequency = 910_525u, clientRepeat = true), null, catalog))
        assertEquals(RadioDetailText.Custom, SettingsListPresentation.radioDetailText(device(frequency = 915_000u, clientRepeat = true), null, catalog))
    }

    @Test
    fun `location and connection row text`() {
        assertEquals(UiText.Resource(AppSettingsStrings.locationSharingPublicly), SettingsListPresentation.locationDetailText(device(advertLocationPolicy = 2u)))
        assertEquals(UiText.Resource(AppSettingsStrings.locationNotSharing), SettingsListPresentation.locationDetailText(device()))
        assertEquals(UiText.Resource(RetainedUiStrings.wifiHeader), SettingsListPresentation.connectionTitle(TransportType.WIFI))
        assertEquals(UiText.Resource(AppSettingsStrings.bluetoothHeader), SettingsListPresentation.connectionTitle(TransportType.BLUETOOTH))
        assertEquals(UiText.Resource(AppSettingsStrings.bluetoothHeader), SettingsListPresentation.connectionTitle(null))
    }

    @Test
    fun `the offline-map link is an external destination resolved by the app layer`() {
        val opened = mutableListOf<SettingsExternalDestination>()
        val navigator = SettingsNavigator { opened += it }
        navigator.open(SettingsExternalDestination.OfflineMapSettings)
        assertEquals(listOf<SettingsExternalDestination>(SettingsExternalDestination.OfflineMapSettings), opened)
    }
}

class ChatSettingsStateHolderTest {
    @Test
    fun `reads and writes through the preference port`() {
        val port = FakeChatPrefs()
        val holder = ChatSettingsStateHolder(port)
        assertFalse(holder.isOn(ChatPreference.SHOW_INCOMING_PATH))
        holder.set(ChatPreference.SHOW_INCOMING_PATH, true)
        assertTrue(holder.isOn(ChatPreference.SHOW_INCOMING_PATH))
        assertFalse(holder.isOn(ChatPreference.REPLY_WITH_QUOTE))
        assertEquals(true, holder.values.value[ChatPreference.SHOW_INCOMING_PATH])
    }
}
