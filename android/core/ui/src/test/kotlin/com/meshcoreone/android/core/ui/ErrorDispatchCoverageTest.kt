// PortedFrom: MC1Tests/Extensions/ErrorDispatchCoverageTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Copy coverage is asserted independently; missing concrete producer dispatch remains an acceptance blocker.
package com.meshcoreone.android.core.ui

import java.io.File
import kotlin.test.*
import org.junit.Test
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.DeviceServiceError
import com.meshcoreone.android.core.contracts.domain.errors.DeviceServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingError
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.TimeoutError
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.services.sync.SyncCoordinatorError
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import com.meshcoreone.android.core.services.remote.NodeConfigServiceError
import com.meshcoreone.android.core.services.remote.RemoteNodeError
import com.meshcoreone.android.core.services.remote.RoomServerError
import kotlin.time.Duration.Companion.seconds
import com.meshcoreone.android.core.datastore.KeyGenerationFailure
import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS")
class ErrorDispatchCoverageTest : SourceCaseProof() {
    @OriginalCase("ErrorDispatchCoverageTests::Every LocalizedError enum in MC1Services and MeshCore has a dispatch arm or is allowlisted()")
    @Test fun everyOriginalLocalizedErrorHasCopyAccountingOrTheOriginalControlFlowAllowlist() = prove {
        val root = File(requireNotNull(System.getProperty("repositoryDirectory")))
        val patterns = listOf(
            Regex("(?m)^\\s*(?:public\\s+)?enum\\s+(\\w+)\\s*:[^{]*\\bLocalizedError\\b"),
            Regex("(?m)^\\s*extension\\s+(\\w+)\\s*:[^{]*\\bLocalizedError\\b"),
        )
        val discovered = linkedSetOf<String>()
        for (directory in listOf("MC1Services/Sources", "MeshCore/Sources")) {
            val files = File(root, directory).walkTopDown().filter { it.isFile && it.extension == "swift" }.toList()
            assertTrue(files.isNotEmpty())
            for (file in files) for (pattern in patterns) {
                discovered += pattern.findAll(file.readText()).map { it.groupValues[1] }.toList()
            }
        }
        val allowlist = setOf("PairingError", "DevicePairingError")
        assertTrue(allowlist.all { it in discovered })
        val removedBilling = setOf("StoreServiceError")
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val mapper = UiErrorMapper()
        val actualBindings = mapOf(
            "MeshCoreError" to (MeshCoreException.Timeout() to L.errorMeshCoreTimeout),
            "AppBackupError" to (AppBackupException(AppBackupError.InvalidFile) to S.settingsBackupErrorInvalidFile),
            "WiFiTransportError" to (WiFiTransportException(WiFiTransportError.InvalidHost) to L.errorWifiInvalidHost),
            "PersistenceStoreError" to (PersistenceStoreException(PersistenceStoreError.ContactNotFound) to L.errorPersistenceContactNotFound),
            "KeyGenerationError" to (KeyGenerationFailure.ReservedPrefix() to L.errorKeyGenerationReservedPrefix),
            "DeviceServiceError" to (DeviceServiceException(DeviceServiceError.DeviceNotFound) to L.errorDeviceServiceDeviceNotFound),
            "SettingsServiceError" to (SettingsServiceException(SettingsServiceError.NotConnected) to L.errorSettingsNotConnected),
            "MessageServiceError" to (MessageServiceException(MessageServiceError.NotConnected) to L.errorMessageServiceNotConnected),
            "MessagePollingError" to (MessagePollingException(MessagePollingError.NotConnected) to L.errorMessagePollingNotConnected),
            "ChatSendQueueServiceError" to (ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected) to L.errorChatSendQueueNotConnected),
            "BLEError" to (BleTransportException(BleError.NotConnected) to L.errorBleNotConnected),
            "ConnectionError" to (ConnectionError.NotConnected() to L.errorConnectionNotConnected),
            "ContactServiceError" to (ContactServiceError.ContactTableFull() to L.errorContactServiceContactTableFull),
            "ChannelServiceError" to (ChannelServiceError.NotConnected() to L.errorChannelServiceNotConnected),
            "AdvertisementError" to (AdvertisementError.NotConnected() to L.errorAdvertisementNotConnected),
            "RemoteNodeError" to (RemoteNodeError.PermissionDenied() to L.errorRemoteNodePermissionDenied),
            "RoomServerError" to (RoomServerError.PermissionDenied() to L.errorRoomServerPermissionDenied),
            "BinaryProtocolError" to (BinaryProtocolError.Timeout() to L.errorBinaryProtocolTimeout),
            "AccessorySetupKitError" to (CompanionSetupError.SessionNotActive() to L.errorAccessorySetupSessionNotActive),
            "SyncCoordinatorError" to (SyncCoordinatorError.AlreadySyncing() to L.errorSyncCoordinatorAlreadySyncing),
        )
        for ((source, pair) in actualBindings) {
            assertTrue(source in discovered)
            assertEquals(resources.getString(pair.second), mapper.message(pair.first).resolve(resources))
        }
        val timeout = TimeoutError("sync", 5.seconds)
        assertEquals(resources.getString(L.errorTimeoutOperationTimedOut), mapper.message(timeout).resolve(resources))
        assertSame(timeout, mapper.present(timeout).originalFailure)
        val config = NodeConfigServiceError.InvalidChannelSecret(2, 30)
        assertEquals(S.configImportErrorInvalidChannelSecret(resources, 2, 30), mapper.message(config).resolve(resources))
        assertTrue("NodeConfigServiceError" in discovered)
        val nativeMissing = discovered - actualBindings.keys - allowlist - removedBilling -
            setOf("ProtocolError", "KeychainError", "NodeConfigServiceError")
        assertEquals(emptySet(), nativeMissing, "Every original producer must execute through a real native dispatch")
        assertFalse(nativeMissing.any { it in setOf("MessageServiceError", "MessagePollingError", "ChatSendQueueServiceError",
            "BLEError", "ConnectionError", "TimeoutError") })
        assertTrue(discovered.isNotEmpty())
    }
}
