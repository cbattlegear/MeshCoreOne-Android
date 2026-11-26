// AndroidOnly: WP-206 Real app-graph link switching and recovery compared with current upstream.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.app.navigation.AppSettingsContent
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment

class ConnectionLinkSwitchIntegrationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun liveHandoffKeepsTheRadioAndConnectionPage() = runTest {
        withRig { h, feature, target ->
            val originalRadio = assertNotNull(h.appState.connectedDevice).radioId
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
            val navigation = h.container.navigation
            navigation.navigateToSetting(SettingsDetail.CONNECTION)
            val originalEntry = navigation.state.value.stacks.getValue(AppTab.SETTINGS).last()
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { radio -> configureRadio(radio); radio.connectGate = gate }

            feature.switchConnectionLink(TransportType.WIFI, bluetooth)
            h.eventually("Wi-Fi handoff started") {
                h.links.links.size == 2 && feature.linkSwitch.value?.target == TransportType.WIFI
            }
            assertEquals(TransportType.BLUETOOTH, feature.linkSwitch.value?.checked)
            assertEquals(originalEntry, navigation.state.value.stacks.getValue(AppTab.SETTINGS).last())
            gate.complete(Unit)
            h.eventually("Wi-Fi handoff completed") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.WIFI && feature.linkSwitch.value == null
            }
            assertEquals(originalRadio, h.appState.connectedDevice?.radioId)
            assertEquals(target.deviceId, h.appState.connectedDevice?.id)
            assertEquals(originalEntry, navigation.state.value.stacks.getValue(AppTab.SETTINGS).last())

            h.links.configureRadio = { configureRadio(it) }
            feature.switchConnectionLink(TransportType.BLUETOOTH, bluetooth)
            h.eventually("Bluetooth handoff completed") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.BLUETOOTH && feature.linkSwitch.value == null
            }
            assertEquals(originalRadio, h.appState.connectedDevice?.radioId)
            assertEquals(originalEntry, navigation.state.value.stacks.getValue(AppTab.SETTINGS).last())
        }
    }

    @Test
    fun handoffUsesTheSelectedRadioWhenAnotherSavedDeviceSharesItsPublicKey() = runTest {
        withRig(duplicateFirst = true) { h, feature, _ ->
            val original = assertNotNull(h.appState.connectedDevice)
            val other = assertNotNull(store.fetchDevice(original.publicKey))
            assertNotEquals(original.id, other.id)
            val otherWithWiFi = other.copy(
                connectionMethods = listOf(ConnectionMethod.WiFi("other.test", 5002u)).snapshot(),
            )
            store.saveDevice(otherWithWiFi)
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(original))

            feature.switchConnectionLink(TransportType.WIFI, bluetooth)
            awaitSwitch(h, "selected radio Wi-Fi handoff") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.WIFI && feature.linkSwitch.value == null
            }
            assertEquals(original.id, h.appState.connectedDevice?.id)
            assertEquals(original.radioId, h.appState.connectedDevice?.radioId)
            assertEquals(otherWithWiFi.nodeName, store.fetchDevice(other.id)?.nodeName)
            assertEquals(otherWithWiFi.connectionMethods, store.fetchDevice(other.id)?.connectionMethods)
        }
    }

    @Test
    fun savedWifiSelectionUsesTheChosenRadioEvenWhenPublicKeysAreDuplicated() = runTest {
        withRig(duplicateFirst = true) { h, feature, _ ->
            val selected = assertNotNull(h.appState.connectedDevice)
            val other = assertNotNull(store.fetchDevice(selected.publicKey))
            assertNotEquals(selected.id, other.id)

            feature.connectSavedDevice(selected).join()
            awaitSwitch(h, "selected saved Wi-Fi radio") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.WIFI
            }
            assertEquals(selected.id, h.appState.connectedDevice?.id)
            assertEquals(selected.radioId, h.appState.connectedDevice?.radioId)
            assertEquals(other, store.fetchDevice(other.id))

            val otherWithWiFi = other.copy(
                connectionMethods = listOf(ConnectionMethod.WiFi("other.test", 5002u)).snapshot(),
            )
            store.saveDevice(otherWithWiFi)
            feature.connectSavedDevice(otherWithWiFi).join()
            awaitSwitch(h, "different saved Wi-Fi radio") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.appState.connectedDevice?.id == other.id
            }
            assertEquals(other.radioId, h.appState.connectedDevice?.radioId)
            assertNotNull(store.fetchDevice(selected.id))
        }
    }

    @Test
    fun savedWifiSelectionRejectsTheWrongPeerAndCanReconnectAfterUserDisconnect() = runTest {
        withRig { h, feature, _ ->
            val selected = assertNotNull(h.appState.connectedDevice)
            h.manager.disconnect()
            h.links.configureRadio = { radio -> configureRadio(radio); radio.publicKey = key(8) }
            feature.connectSavedDevice(selected).join()
            assertEquals(DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
            assertTrue(h.appState.connectionUI.showingConnectionFailedAlert)
            assertEquals(selected.publicKey, store.fetchDevice(selected.id)?.publicKey)
            assertNull(store.fetchDevice(key(8)))

            h.appState.connectionUI.clearPairingFailure()
            h.links.configureRadio = { configureRadio(it) }
            feature.connectSavedDevice(selected).join()
            awaitSwitch(h, "saved Wi-Fi reconnect") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.appState.connectedDevice?.id == selected.id
            }
            assertEquals(selected.radioId, h.appState.connectedDevice?.radioId)
        }
    }

    @Test
    fun newerUserDisconnectCancelsAQueuedSavedWifiSelection() = runTest {
        withRig { h, feature, _ ->
            val selected = assertNotNull(h.appState.connectedDevice)
            val connections = h.links.links.size

            val selection = feature.connectSavedDevice(selected)
            h.manager.disconnect()
            selection.join()
            h.settle()

            assertTrue(selection.isCancelled)
            assertEquals(connections, h.links.links.size)
            assertEquals(DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
            assertEquals(ConnectionIntent.UserDisconnected, h.manager.snapshot.value.intent)
        }
    }

    @Test
    fun failedBluetoothMigrationRetainsTheCommittedDeviceIdForManualRetry() = runTest {
        val failHydration = AtomicBoolean(false)
        val faultStore = object : PersistenceStoreProtocol by store,
            DirectAckAuthority by store,
            DirectPendingCompletionAuthority by store,
            DirectQueueAuthority by store,
            DirectMessageCreationAuthority by store,
            ChannelHistoryPersisting by store,
            ChannelDraftRecoveryPersisting by store,
            QueuedChannelTerminalPersisting by store,
            QueuedChannelReadinessPersisting by store,
            InlineChannelAttemptPersisting by store,
            ChannelSubmissionPersisting by store {
            override suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO> {
                if (failHydration.get()) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                return store.fetchPendingSends(radioId)
            }
        }
        withRig(startOverWifi = true, persistenceOverride = faultStore) { h, feature, target ->
            val original = assertNotNull(h.appState.connectedDevice)
            assertNotEquals(target.deviceId, original.id)
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(original))
            failHydration.set(true)
            feature.switchConnectionLink(TransportType.BLUETOOTH, bluetooth)
            h.eventually("Bluetooth migration failed after activation", virtualBudgetMillis = 10_000, virtualStepMillis = 10) {
                h.manager.connectionState == DeviceConnectionState.DISCONNECTED &&
                    feature.linkSwitch.value?.target == null &&
                    h.appState.connectionUI.showingConnectionFailedAlert
            }
            assertNull(store.fetchDevice(original.id))
            assertEquals(original.radioId, store.fetchDevice(target.deviceId)?.radioId)
            assertEquals(target.deviceId, feature.linkSwitch.value?.deviceId)

            failHydration.set(false)
            h.appState.connectionUI.clearPairingFailure()
            feature.switchConnectionLink(TransportType.BLUETOOTH, null)
            awaitSwitch(h, "manual retry after endpoint migration") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.BLUETOOTH && feature.linkSwitch.value == null
            }
            assertEquals(target.deviceId, h.appState.connectedDevice?.id)
            assertEquals(original.radioId, h.appState.connectedDevice?.radioId)
        }
    }

    @Test
    fun differentWifiPeerFailsAndLeavesTheOriginalBluetoothRouteRecoverable() = runTest {
        withRig { h, feature, _ ->
            val original = assertNotNull(h.appState.connectedDevice)
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(original))
            h.links.configureRadio = { radio -> configureRadio(radio); radio.publicKey = key(8) }

            feature.switchConnectionLink(TransportType.WIFI, bluetooth)
            awaitSwitch(h, "mismatched Wi-Fi peer rejected") {
                h.manager.connectionState == DeviceConnectionState.DISCONNECTED &&
                    feature.linkSwitch.value?.target == null &&
                    h.appState.connectionUI.showingConnectionFailedAlert
            }
            assertEquals(original.radioId, store.fetchDevice(original.id)?.radioId)
            assertNull(store.fetchDevice(key(8)))

            h.appState.connectionUI.clearPairingFailure()
            h.links.configureRadio = { configureRadio(it) }
            feature.switchConnectionLink(TransportType.BLUETOOTH, null)
            awaitSwitch(h, "original Bluetooth route restored") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.BLUETOOTH && feature.linkSwitch.value == null
            }
            assertEquals(original.radioId, h.appState.connectedDevice?.radioId)
        }
    }

    @Test
    fun failedWifiHandoffKeepsCapturedBluetoothRouteForRetry() = runTest {
        withRig { h, feature, _ ->
            val originalRadio = assertNotNull(h.appState.connectedDevice).radioId
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
            h.container.navigation.navigateToSetting(SettingsDetail.CONNECTION)
            h.links.configureRadio = { radio ->
                configureRadio(radio)
                radio.connectFailure = IOException("Wi-Fi unavailable")
            }

            feature.switchConnectionLink(TransportType.WIFI, bluetooth)
            h.eventually("failed handoff is recoverable") {
                h.manager.connectionState == DeviceConnectionState.DISCONNECTED &&
                    feature.linkSwitch.value?.target == null &&
                    h.appState.connectionUI.showingConnectionFailedAlert
            }
            val held = assertNotNull(feature.linkSwitch.value)
            assertEquals(bluetooth, held.bluetoothDeviceId)
            assertEquals(originalRadio, held.radioId)
            assertEquals(
                NavigationDestination.Setting(SettingsDetail.CONNECTION),
                h.container.navigation.state.value.stacks.getValue(AppTab.SETTINGS).last().destination,
            )
            h.appState.connectionUI.clearPairingFailure()
            h.links.configureRadio = { configureRadio(it) }
            feature.switchConnectionLink(TransportType.BLUETOOTH, null)
            h.eventually("captured Bluetooth retry completed") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.BLUETOOTH && feature.linkSwitch.value == null
            }
            assertEquals(originalRadio, h.appState.connectedDevice?.radioId)
        }
    }

    @Test
    fun supersededAttemptDoesNotLeaveSwitchingLatched() = runTest {
        withRig { h, feature, target ->
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
            val gate = CompletableDeferred<Unit>()
            h.links.configureRadio = { radio -> configureRadio(radio); radio.connectGate = gate }
            feature.switchConnectionLink(TransportType.WIFI, bluetooth)
            h.eventually("Wi-Fi handoff started") { h.links.links.size == 2 }

            h.links.configureRadio = { configureRadio(it) }
            val successor = backgroundScope.async {
                h.manager.connect(target, forceFullSync = false, forceReconnect = true)
            }
            h.eventually("same-radio successor completed") {
                successor.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.BLUETOOTH && feature.linkSwitch.value == null
            }
            successor.await()
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
        }
    }

    @Test
    fun delayedBluetoothLookupCannotReplaceANewerConnectionToTheSameRadio() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockLookup = false
        val delegate = SyntheticEndpoints()
        val endpoints = object : KnownEndpointStore {
            override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? {
                if (blockLookup) {
                    entered.complete(Unit)
                    release.await()
                }
                return delegate.endpoint(deviceId)
            }
            override suspend fun remember(endpoint: BluetoothEndpoint) = delegate.remember(endpoint)
            override suspend fun forget(deviceId: UUID) = delegate.forget(deviceId)
        }
        try {
            withRig(knownEndpoints = endpoints) { h, feature, _ ->
                val bluetooth = assertNotNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
                feature.switchConnectionLink(TransportType.WIFI, bluetooth)
                awaitSwitch(h, "first Wi-Fi handoff") {
                    h.manager.connectionState == DeviceConnectionState.READY &&
                        h.container.currentTransport == TransportType.WIFI && feature.linkSwitch.value == null
                }
                blockLookup = true
                feature.switchConnectionLink(TransportType.BLUETOOTH, bluetooth)
                awaitSwitch(h, "Bluetooth lookup blocked") { entered.isCompleted }

                val successor = backgroundScope.async {
                    h.manager.connect(
                        ConnectionTarget.WiFi("radio.test", 5001u),
                        forceFullSync = false, forceReconnect = true,
                    )
                }
                awaitSwitch(h, "newer Wi-Fi connection") {
                    successor.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                        h.container.currentTransport == TransportType.WIFI
                }
                successor.await()
                val connections = h.links.links.size
                release.complete(Unit)
                awaitSwitch(h, "stale switch settled") { feature.linkSwitch.value?.target == null }

                assertEquals(connections, h.links.links.size)
                assertEquals(TransportType.WIFI, h.container.currentTransport)
                assertNull(feature.linkSwitch.value)
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun supersededBluetoothLookupFailureDoesNotShowAStaleError() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failLookup = false
        val delegate = SyntheticEndpoints()
        val endpoints = object : KnownEndpointStore {
            override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? {
                if (failLookup) {
                    entered.complete(Unit)
                    release.await()
                    throw IOException("stale endpoint lookup")
                }
                return delegate.endpoint(deviceId)
            }
            override suspend fun remember(endpoint: BluetoothEndpoint) = delegate.remember(endpoint)
            override suspend fun forget(deviceId: UUID) = delegate.forget(deviceId)
        }
        try {
            withRig(knownEndpoints = endpoints) { h, feature, _ ->
                val bluetooth = assertNotNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
                feature.switchConnectionLink(TransportType.WIFI, bluetooth)
                awaitSwitch(h, "first Wi-Fi handoff") {
                    h.manager.connectionState == DeviceConnectionState.READY &&
                        h.container.currentTransport == TransportType.WIFI && feature.linkSwitch.value == null
                }
                failLookup = true
                feature.switchConnectionLink(TransportType.BLUETOOTH, bluetooth)
                awaitSwitch(h, "Bluetooth lookup blocked") { entered.isCompleted }

                val successor = backgroundScope.async {
                    h.manager.connect(
                        ConnectionTarget.WiFi("radio.test", 5001u),
                        forceFullSync = false, forceReconnect = true,
                    )
                }
                awaitSwitch(h, "newer Wi-Fi connection") {
                    successor.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                        h.container.currentTransport == TransportType.WIFI
                }
                successor.await()
                release.complete(Unit)
                awaitSwitch(h, "stale lookup settled") { feature.linkSwitch.value == null }
                assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
                assertEquals(TransportType.WIFI, h.container.currentTransport)
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun unassociatedWifiCannotStartEvenFromAStaleAction() = runTest {
        withRig(associated = false) { h, feature, _ ->
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
            feature.switchConnectionLink(TransportType.WIFI, bluetooth)
            h.settle()
            assertEquals(1, h.links.links.size)
            assertNull(feature.linkSwitch.value)
            assertEquals(TransportType.BLUETOOTH, h.container.currentTransport)
        }
    }

    @Test
    fun importedBluetoothHandleIsNotAConnectableAndroidEndpoint() = runTest {
        val noEndpoints = object : KnownEndpointStore {
            override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? = null
            override suspend fun remember(endpoint: BluetoothEndpoint) = Unit
            override suspend fun forget(deviceId: UUID) = Unit
        }
        withRig(knownEndpoints = noEndpoints) { h, feature, _ ->
            assertNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
            feature.switchConnectionLink(TransportType.WIFI, null)
            awaitSwitch(h, "Wi-Fi handoff completed without a local Bluetooth endpoint") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.currentTransport == TransportType.WIFI && feature.linkSwitch.value == null
            }
            assertNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
        }
    }

    @Test
    fun failedSwitchRemainsReachableFromSettingsAndCanRetry() = runTest {
        withRig { h, feature, _ ->
            val bluetooth = assertNotNull(feature.bluetoothEndpoint(h.appState.connectedDevice))
            val navigation = h.container.navigation
            navigation.navigateToSetting(SettingsDetail.CONNECTION)
            h.links.configureRadio = { radio ->
                configureRadio(radio)
                radio.connectFailure = IOException("Wi-Fi unavailable")
            }
            feature.switchConnectionLink(TransportType.WIFI, bluetooth)
            h.eventually("failed handoff is held") {
                h.manager.connectionState == DeviceConnectionState.DISCONNECTED &&
                    feature.linkSwitch.value?.target == null &&
                    h.appState.connectionUI.showingConnectionFailedAlert
            }
            h.appState.connectionUI.clearPairingFailure()
            navigation.back()
            val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            host.windowFocusChanged(true)
            try {
                compose.runOnUiThread {
                    host.get().setContent {
                        val state by navigation.state.collectAsState()
                        val entry = state.stacks.getValue(AppTab.SETTINGS).last()
                        MeshCoreTheme(motionScale = 0f) {
                            AppSettingsContent(feature, entry.destination, navigation, selectionHost = false)
                        }
                    }
                }
                val connection = host.get().getString(S.connectionHeader)
                val connect = host.get().getString(S.deviceConnect)
                val bluetoothName = host.get().getString(S.bluetoothHeader)
                compose.waitUntil(10_000) {
                    h.settle()
                    compose.onAllNodesWithText(connection).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(connect).assertExists()
                compose.onNodeWithText(connection).performClick()
                compose.waitUntil(10_000) {
                    h.settle()
                    compose.onAllNodesWithText(bluetoothName).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(host.get().getString(S.connectionLinkInUse)).assertDoesNotExist()
                h.links.configureRadio = { configureRadio(it) }
                compose.onNodeWithText(bluetoothName).assertIsEnabled().performClick()
                awaitSwitch(h, "captured Bluetooth retry completed from Settings") {
                    h.manager.connectionState == DeviceConnectionState.READY &&
                        h.container.currentTransport == TransportType.BLUETOOTH && feature.linkSwitch.value == null
                }
            } finally {
                host.pause().stop().destroy()
            }
        }
    }

    @Test
    fun bluetoothEndpointLookupFailureIsReportedInSettings() = runTest {
        val failure = IOException("endpoint store unavailable")
        val failingEndpoints = object : KnownEndpointStore {
            override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? = throw failure
            override suspend fun remember(endpoint: BluetoothEndpoint) = Unit
            override suspend fun forget(deviceId: UUID) = Unit
        }
        withRig(knownEndpoints = failingEndpoints) { h, feature, _ ->
            val navigation = h.container.navigation
            navigation.navigateToSetting(SettingsDetail.CONNECTION)
            val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            host.windowFocusChanged(true)
            try {
                compose.runOnUiThread {
                    host.get().setContent {
                        val state by navigation.state.collectAsState()
                        val entry = state.stacks.getValue(AppTab.SETTINGS).last()
                        MeshCoreTheme(motionScale = 0f) {
                            AppSettingsContent(feature, entry.destination, navigation)
                        }
                    }
                }
                compose.waitUntil(10_000) { feature.failure.value === failure }
                assertSame(failure, feature.failure.value)
                compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsDisplayed()
            } finally {
                host.pause().stop().destroy()
            }
        }
    }

    private suspend fun awaitSwitch(h: ContainerHarness, message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var advanced = 0
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $message (state=${h.manager.connectionState})" }
            h.scheduler.runCurrent()
            if (advanced < 1_000) {
                h.scheduler.advanceTimeBy(1)
                advanced++
            }
            yield()
            Thread.sleep(2)
        }
    }

    private suspend fun TestScope.withRig(
        associated: Boolean = true,
        knownEndpoints: KnownEndpointStore = SyntheticEndpoints(),
        duplicateFirst: Boolean = false,
        startOverWifi: Boolean = false,
        persistenceOverride: PersistenceStoreProtocol = store,
        block: suspend (ContainerHarness, AppSettingsFeature, ConnectionTarget.Bluetooth) -> Unit,
    ) {
        val h = ContainerHarness(this, store, knownEndpoints = knownEndpoints, persistenceOverride = persistenceOverride)
        val storage = MeshCoreStorage.get(RuntimeEnvironment.getApplication())
        try {
            val target = h.target()
            if (duplicateFirst) {
                val otherId = UUID.randomUUID()
                store.saveDevice(
                    DeviceDTO(
                        id = otherId, radioId = RadioId(UUID.randomUUID()), publicKey = key(7),
                        nodeName = "Other saved device",
                        connectionMethods = listOf(ConnectionMethod.Bluetooth(otherId)).snapshot(),
                    ),
                )
            }
            val selectedId = if (startOverWifi) UUID.randomUUID() else target.deviceId
            store.saveDevice(
                DeviceDTO(
                    id = selectedId, radioId = RadioId(UUID.randomUUID()), publicKey = key(7),
                    nodeName = "Dual method", connectionMethods = listOf(
                        ConnectionMethod.Bluetooth(target.deviceId),
                        ConnectionMethod.WiFi("radio.test", 5001u),
                    ).snapshot(),
                ),
            )
            h.links.configureRadio = { configureRadio(it, associated) }
            h.manager.connect(if (startOverWifi) ConnectionTarget.WiFi("radio.test", 5001u) else target)
            h.settle()
            h.assertReady()
            val feature = AppSettingsFeature.create(h.container, storage)
            feature.bindRadioWiFi()
            h.eventually("radio Wi-Fi observation") {
                val state = feature.radioWiFi.state.value
                state.support == RadioWiFiSupport.SUPPORTED && state.associated == associated && !state.busy
            }
            block(h, feature, target)
        } finally {
            h.container.close()
            storage.close()
        }
    }

    private fun configureRadio(radio: FirmwareRadio, associated: Boolean = true) {
        radio.firmwareVersion = 14u
        radio.respond = { frame ->
            if (frame[0].toInt() == 0x1f) {
                listOf(Bytes.of(0x12, frame[1].toInt()) + Bytes(ByteArray(48)))
            } else if (frame[0].toInt() == 0x42) {
                val request = frame.slice(1, frame.size).toByteArray().toString(Charsets.UTF_8)
                val tag = request.substring(0, 3)
                val reply = when (request.substring(3)) {
                    "get wifi.enabled" -> "> 1"
                    "get wifi.ssid" -> "> Existing"
                    "get wifi.status" -> if (associated) "> connected" else "> disconnected"
                    "get wifi.ip" -> "> 192.0.2.1"
                    else -> error("Unexpected Wi-Fi command")
                }
                listOf(Bytes.of(0x1d) + Bytes.utf8(tag + reply))
            } else null
        }
    }
}
