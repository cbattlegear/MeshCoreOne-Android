// PortedFrom: MC1/Extensions/Errors/AccessorySetupKitError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/AdvertisementError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/BLEError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/BinaryProtocolError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/ChannelServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/ChatSendQueueServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/ConnectionError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/ContactServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/DeviceServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/KeyGenerationError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/KeychainError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/MeshCoreError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/MessagePollingError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/MessageServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/NodeConfigServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/PersistenceStoreError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/ProtocolError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/RemoteNodeError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/RoomServerError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/SettingsServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/SyncCoordinatorError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/TimeoutError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/WiFiTransportError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Copy policies only. Producer error declarations, retry policy and service implementations are not recreated here.
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.model.snapshotSet

data class ErrorCopyKey(val sourceType: String, val sourceCase: String)

object ErrorCopy {
    val staticCopies: SnapshotMap<ErrorCopyKey, Int> = linkedMapOf(
        ErrorCopyKey("AccessorySetupKitError", "sessionNotActive") to L.errorAccessorySetupSessionNotActive,
        ErrorCopyKey("AccessorySetupKitError", "sessionInvalidated") to L.errorAccessorySetupSessionInvalidated,
        ErrorCopyKey("AccessorySetupKitError", "pickerDismissed") to L.errorAccessorySetupPickerDismissed,
        ErrorCopyKey("AccessorySetupKitError", "pickerRestricted") to L.errorAccessorySetupPickerRestricted,
        ErrorCopyKey("AccessorySetupKitError", "pickerAlreadyActive") to L.errorAccessorySetupPickerAlreadyActive,
        ErrorCopyKey("AccessorySetupKitError", "noBluetoothIdentifier") to L.errorAccessorySetupNoBluetoothIdentifier,
        ErrorCopyKey("AccessorySetupKitError", "discoveryTimeout") to L.errorAccessorySetupDiscoveryTimeout,
        ErrorCopyKey("AccessorySetupKitError", "connectionFailed") to L.errorAccessorySetupConnectionFailed,
        ErrorCopyKey("AdvertisementError", "notConnected") to L.errorAdvertisementNotConnected,
        ErrorCopyKey("AdvertisementError", "sendFailed") to L.errorAdvertisementSendFailed,
        ErrorCopyKey("AdvertisementError", "invalidResponse") to L.errorAdvertisementInvalidResponse,
        ErrorCopyKey("BLEError", "bluetoothUnavailable") to L.errorBleBluetoothUnavailable,
        ErrorCopyKey("BLEError", "bluetoothUnauthorized") to L.errorBleBluetoothUnauthorized,
        ErrorCopyKey("BLEError", "bluetoothPoweredOff") to L.errorBleBluetoothPoweredOff,
        ErrorCopyKey("BLEError", "deviceNotFound") to L.errorBleDeviceNotFound,
        ErrorCopyKey("BLEError", "connectionTimeout") to L.errorBleConnectionTimeout,
        ErrorCopyKey("BLEError", "notConnected") to L.errorBleNotConnected,
        ErrorCopyKey("BLEError", "characteristicNotFound") to L.errorBleCharacteristicNotFound,
        ErrorCopyKey("BLEError", "invalidResponse") to L.errorBleInvalidResponse,
        ErrorCopyKey("BLEError", "operationTimeout") to L.errorBleOperationTimeout,
        ErrorCopyKey("BLEError", "authenticationFailed") to L.errorBleAuthenticationFailed,
        ErrorCopyKey("BLEError", "deviceConnectedToOtherApp") to L.errorBleDeviceConnectedToOtherApp,
        ErrorCopyKey("BinaryProtocolError", "notConnected") to L.errorBinaryProtocolNotConnected,
        ErrorCopyKey("BinaryProtocolError", "sendFailed") to L.errorBinaryProtocolSendFailed,
        ErrorCopyKey("BinaryProtocolError", "timeout") to L.errorBinaryProtocolTimeout,
        ErrorCopyKey("BinaryProtocolError", "invalidResponse") to L.errorBinaryProtocolInvalidResponse,
        ErrorCopyKey("ChannelServiceError", "notConnected") to L.errorChannelServiceNotConnected,
        ErrorCopyKey("ChannelServiceError", "channelNotFound") to L.errorChannelServiceChannelNotFound,
        ErrorCopyKey("ChannelServiceError", "invalidChannelIndex") to L.errorChannelServiceInvalidChannelIndex,
        ErrorCopyKey("ChannelServiceError", "secretHashingFailed") to L.errorChannelServiceSecretHashingFailed,
        ErrorCopyKey("ChannelServiceError", "syncAlreadyInProgress") to L.errorChannelServiceSyncAlreadyInProgress,
        ErrorCopyKey("ChatSendQueueServiceError", "notConnected") to L.errorChatSendQueueNotConnected,
        ErrorCopyKey("ConnectionError", "deviceNotFound") to L.errorConnectionDeviceNotFound,
        ErrorCopyKey("ConnectionError", "notConnected") to L.errorConnectionNotConnected,
        ErrorCopyKey("ContactServiceError", "notConnected") to L.errorContactServiceNotConnected,
        ErrorCopyKey("ContactServiceError", "sendFailed") to L.errorContactServiceSendFailed,
        ErrorCopyKey("ContactServiceError", "invalidResponse") to L.errorContactServiceInvalidResponse,
        ErrorCopyKey("ContactServiceError", "syncInterrupted") to L.errorContactServiceSyncInterrupted,
        ErrorCopyKey("ContactServiceError", "contactNotFound") to L.errorContactServiceContactNotFound,
        ErrorCopyKey("ContactServiceError", "contactTableFull") to L.errorContactServiceContactTableFull,
        ErrorCopyKey("ContactServiceError", "shareContactUnavailable") to L.errorContactServiceShareContactUnavailable,
        ErrorCopyKey("DeviceServiceError", "deviceNotFound") to L.errorDeviceServiceDeviceNotFound,
        ErrorCopyKey("KeyGenerationError", "maxAttemptsExceeded") to L.errorKeyGenerationMaxAttemptsExceeded,
        ErrorCopyKey("KeyGenerationError", "reservedPrefix") to L.errorKeyGenerationReservedPrefix,
        ErrorCopyKey("KeyGenerationError", "randomGenerationFailed") to L.errorKeyGenerationRandomGenerationFailed,
        ErrorCopyKey("KeyGenerationError", "invalidKey") to L.errorKeyGenerationInvalidKey,
        ErrorCopyKey("KeychainError", "encodingFailed") to L.errorKeychainEncodingFailed,
        ErrorCopyKey("MeshCoreError", "timeout") to L.errorMeshCoreTimeout,
        ErrorCopyKey("MeshCoreError", "notConnected") to L.errorMeshCoreNotConnected,
        ErrorCopyKey("MeshCoreError", "contactNotFound") to L.errorMeshCoreContactNotFound,
        ErrorCopyKey("MeshCoreError", "bluetoothUnavailable") to L.errorMeshCoreBluetoothUnavailable,
        ErrorCopyKey("MeshCoreError", "bluetoothUnauthorized") to L.errorMeshCoreBluetoothUnauthorized,
        ErrorCopyKey("MeshCoreError", "bluetoothPoweredOff") to L.errorMeshCoreBluetoothPoweredOff,
        ErrorCopyKey("MeshCoreError", "sessionNotStarted") to L.errorMeshCoreSessionNotStarted,
        ErrorCopyKey("MeshCoreError", "featureDisabled") to L.errorMeshCoreFeatureDisabled,
        ErrorCopyKey("MessagePollingError", "notConnected") to L.errorMessagePollingNotConnected,
        ErrorCopyKey("MessagePollingError", "pollingFailed") to L.errorMessagePollingPollingFailed,
        ErrorCopyKey("MessageServiceError", "notConnected") to L.errorMessageServiceNotConnected,
        ErrorCopyKey("MessageServiceError", "contactNotFound") to L.errorMessageServiceContactNotFound,
        ErrorCopyKey("MessageServiceError", "channelNotFound") to L.errorMessageServiceChannelNotFound,
        ErrorCopyKey("MessageServiceError", "sendFailed") to L.errorMessageServiceSendFailed,
        ErrorCopyKey("MessageServiceError", "invalidRecipient") to L.errorMessageServiceInvalidRecipient,
        ErrorCopyKey("MessageServiceError", "messageTooLong") to L.errorMessageServiceMessageTooLong,
        ErrorCopyKey("PersistenceStoreError", "deviceNotFound") to L.errorPersistenceDeviceNotFound,
        ErrorCopyKey("PersistenceStoreError", "contactNotFound") to L.errorPersistenceContactNotFound,
        ErrorCopyKey("PersistenceStoreError", "messageNotFound") to L.errorPersistenceMessageNotFound,
        ErrorCopyKey("PersistenceStoreError", "channelNotFound") to L.errorPersistenceChannelNotFound,
        ErrorCopyKey("PersistenceStoreError", "remoteNodeSessionNotFound") to L.errorPersistenceRemoteNodeSessionNotFound,
        ErrorCopyKey("PersistenceStoreError", "invalidData") to L.errorPersistenceInvalidData,
        ErrorCopyKey("ProtocolError", "unsupportedCommand") to L.errorDeviceUnsupportedCommand,
        ErrorCopyKey("ProtocolError", "notFound") to L.errorDeviceNotFound,
        ErrorCopyKey("ProtocolError", "tableFull") to L.errorDeviceStorageFull,
        ErrorCopyKey("ProtocolError", "badState") to L.errorDeviceInvalidState,
        ErrorCopyKey("ProtocolError", "fileIOError") to L.errorDeviceFileSystem,
        ErrorCopyKey("ProtocolError", "illegalArgument") to L.errorDeviceInvalidParameter,
        ErrorCopyKey("RemoteNodeError", "notConnected") to L.errorRemoteNodeNotConnected,
        ErrorCopyKey("RemoteNodeError", "loginFailed") to L.errorRemoteNodeLoginFailed,
        ErrorCopyKey("RemoteNodeError", "sendFailed") to L.errorRemoteNodeSendFailed,
        ErrorCopyKey("RemoteNodeError", "invalidResponse") to L.errorRemoteNodeInvalidResponse,
        ErrorCopyKey("RemoteNodeError", "permissionDenied") to L.errorRemoteNodePermissionDenied,
        ErrorCopyKey("RemoteNodeError", "timeout") to L.errorRemoteNodeTimeout,
        ErrorCopyKey("RemoteNodeError", "sessionNotFound") to L.errorRemoteNodeSessionNotFound,
        ErrorCopyKey("RemoteNodeError", "passwordNotFound") to L.errorRemoteNodePasswordNotFound,
        ErrorCopyKey("RemoteNodeError", "floodRouted") to L.errorRemoteNodeFloodRouted,
        ErrorCopyKey("RemoteNodeError", "pathDiscoveryFailed") to L.errorRemoteNodePathDiscoveryFailed,
        ErrorCopyKey("RemoteNodeError", "contactNotFound") to L.errorRemoteNodeContactNotFound,
        ErrorCopyKey("RemoteNodeError", "radioContactsFull") to L.errorRemoteNodeRadioContactsFull,
        ErrorCopyKey("RemoteNodeError", "cancelled") to L.errorRemoteNodeCancelled,
        ErrorCopyKey("RoomServerError", "notConnected") to L.errorRoomServerNotConnected,
        ErrorCopyKey("RoomServerError", "sessionNotFound") to L.errorRoomServerSessionNotFound,
        ErrorCopyKey("RoomServerError", "sendFailed") to L.errorRoomServerSendFailed,
        ErrorCopyKey("RoomServerError", "permissionDenied") to L.errorRoomServerPermissionDenied,
        ErrorCopyKey("RoomServerError", "invalidResponse") to L.errorRoomServerInvalidResponse,
        ErrorCopyKey("SettingsServiceError", "notConnected") to L.errorSettingsNotConnected,
        ErrorCopyKey("SettingsServiceError", "sendFailed") to L.errorSettingsSendFailed,
        ErrorCopyKey("SettingsServiceError", "invalidResponse") to L.errorSettingsInvalidResponse,
        ErrorCopyKey("SyncCoordinatorError", "notConnected") to L.errorSyncCoordinatorNotConnected,
        ErrorCopyKey("SyncCoordinatorError", "alreadySyncing") to L.errorSyncCoordinatorAlreadySyncing,
        ErrorCopyKey("TimeoutError", "timeout") to L.errorTimeoutOperationTimedOut,
        ErrorCopyKey("WiFiTransportError", "connectionTimeout") to L.errorWifiConnectionTimeout,
        ErrorCopyKey("WiFiTransportError", "notConnected") to L.errorWifiNotConnected,
        ErrorCopyKey("WiFiTransportError", "sendTimeout") to L.errorWifiSendTimeout,
        ErrorCopyKey("WiFiTransportError", "invalidHost") to L.errorWifiInvalidHost,
        ErrorCopyKey("WiFiTransportError", "invalidPort") to L.errorWifiInvalidPort,
        ErrorCopyKey("WiFiTransportError", "notConfigured") to L.errorWifiNotConfigured,
    ).snapshotMap()

    val sourceFamilies: SnapshotSet<String> = (staticCopies.keys.map { it.sourceType } +
        listOf("AppBackupError", "NodeConfigServiceError")).snapshotSet()

    fun static(sourceType: String, sourceCase: String): UiText =
        UiText.Resource(requireNotNull(staticCopies[ErrorCopyKey(sourceType, sourceCase)]) {
            "No reviewed error copy for $sourceType.$sourceCase"
        })

    fun accessoryPairingFailed(reason: String): UiText =
        generatedText("Error.AccessorySetup.pairingFailed") { L.errorAccessorySetupPairingFailed(it, reason) }
    fun bleConnectionFailed(reason: String): UiText =
        generatedText("Error.Ble.connectionFailed") { L.errorBleConnectionFailed(it, reason) }
    fun bleWriteError(reason: String): UiText =
        generatedText("Error.Ble.writeError") { L.errorBleWriteError(it, reason) }
    fun blePairingFailed(reason: String): UiText =
        generatedText("Error.Ble.pairingFailed") { L.errorBlePairingFailed(it, reason) }
    fun connectionFailed(reason: String): UiText =
        generatedText("Error.Connection.connectionFailed") { L.errorConnectionConnectionFailed(it, reason) }
    fun initializationFailed(reason: String): UiText =
        generatedText("Error.Connection.initializationFailed") { L.errorConnectionInitializationFailed(it, reason) }
    fun channelSaveFailed(reason: String): UiText =
        generatedText("Error.ChannelService.saveFailed") { L.errorChannelServiceSaveFailed(it, reason) }
    fun channelSendFailed(reason: String): UiText =
        generatedText("Error.ChannelService.sendFailed") { L.errorChannelServiceSendFailed(it, reason) }
    fun circuitBreakerOpen(count: Long): UiText =
        generatedText("Error.ChannelService.circuitBreakerOpen") { L.errorChannelServiceCircuitBreakerOpen(it, count) }
    fun queuePersistFailed(underlying: UiText): UiText =
        generatedText("Error.ChatSendQueue.persistFailed") { L.errorChatSendQueuePersistFailed(it, underlying.resolve(it)) }
    fun persistenceSaveFailed(reason: String): UiText =
        generatedText("Error.Persistence.saveFailed") { L.errorPersistenceSaveFailed(it, reason) }
    fun persistenceFetchFailed(reason: String): UiText =
        generatedText("Error.Persistence.fetchFailed") { L.errorPersistenceFetchFailed(it, reason) }
    fun devicePersistenceFailed(reason: String): UiText =
        generatedText("Error.DeviceService.persistenceFailed") { L.errorDeviceServicePersistenceFailed(it, reason) }
    fun settingsVerificationFailed(expected: String, actual: String): UiText =
        generatedText("Error.Settings.verificationFailed") { L.errorSettingsVerificationFailed(it, expected, actual) }
    fun gpsVerificationFailed(expectedEnabled: Boolean): UiText =
        UiText.Resource(if (expectedEnabled) L.errorSettingsGpsNotSavedExpectedOn else L.errorSettingsGpsNotSavedExpectedOff)
    fun syncFailed(reason: String): UiText =
        generatedText("Error.SyncCoordinator.syncFailed") { L.errorSyncCoordinatorSyncFailed(it, reason) }
    fun wifiConnectionFailed(reason: String): UiText =
        generatedText("Error.Wifi.connectionFailed") { L.errorWifiConnectionFailed(it, reason) }
    fun wifiSendFailed(reason: String): UiText =
        generatedText("Error.Wifi.sendFailed") { L.errorWifiSendFailed(it, reason) }
    fun meshParseError(reason: String): UiText =
        generatedText("Error.MeshCore.parseError") { L.errorMeshCoreParseError(it, reason) }
    fun meshCommandFailed(reason: String): UiText =
        generatedText("Error.MeshCore.commandFailed") { L.errorMeshCoreCommandFailed(it, reason) }
    fun meshInvalidResponse(expected: String, got: String): UiText =
        generatedText("Error.MeshCore.invalidResponse") { L.errorMeshCoreInvalidResponse(it, expected, got) }
    fun meshDataTooLarge(actual: Long, maximum: Long): UiText =
        generatedText("Error.MeshCore.dataTooLarge") { L.errorMeshCoreDataTooLarge(it, actual, maximum) }
    fun meshSigningFailed(reason: String): UiText =
        generatedText("Error.MeshCore.signingFailed") { L.errorMeshCoreSigningFailed(it, reason) }
    fun meshInvalidInput(reason: String): UiText =
        generatedText("Error.MeshCore.invalidInput") { L.errorMeshCoreInvalidInput(it, reason) }
    fun meshUnknown(reason: String): UiText =
        generatedText("Error.MeshCore.unknown") { L.errorMeshCoreUnknown(it, reason) }
    fun meshConnectionLost(underlying: UiText?): UiText = underlying?.let { text ->
        generatedText("Error.MeshCore.connectionLost") { L.errorMeshCoreConnectionLost(it, text.resolve(it)) }
    } ?: UiText.Resource(L.errorMeshCoreConnectionLostNoDetail)
    fun unknownDevice(code: UByte): UiText =
        generatedText("Error.Device.unknown") { L.errorDeviceUnknown(it, code.toLong()) }
    fun keychainStorageFailed(status: Long): UiText =
        generatedText("Error.Keychain.storageFailed") { L.errorKeychainStorageFailed(it, status) }
    fun keychainRetrievalFailed(status: Long): UiText =
        generatedText("Error.Keychain.retrievalFailed") { L.errorKeychainRetrievalFailed(it, status) }
    fun keychainDeletionFailed(status: Long): UiText =
        generatedText("Error.Keychain.deletionFailed") { L.errorKeychainDeletionFailed(it, status) }
    fun invalidChannelSecret(index: Long, hexLength: Long): UiText =
        generatedText("ConfigImport.Error.invalidChannelSecret") { S.configImportErrorInvalidChannelSecret(it, index, hexLength) }
    fun invalidContactPublicKey(name: String): UiText =
        generatedText("ConfigImport.Error.invalidContactPublicKey") { S.configImportErrorInvalidContactPublicKey(it, name) }
    fun invalidPathHashMode(name: String, mode: UByte): UiText =
        generatedText("ConfigImport.Error.invalidPathHashMode") { S.configImportErrorInvalidPathHashMode(it, name, mode.toLong()) }
    fun invalidPrivateKey(hexLength: Long): UiText =
        generatedText("ConfigImport.Error.invalidPrivateKey") {
            S.configImportErrorInvalidPrivateKey(it, hexLength,
                com.meshcoreone.android.core.model.ProtocolLimits.PRIVATE_KEY_SIZE.toLong() * 2)
        }
    fun radioOutOfRange(fieldLabel: UiText): UiText =
        generatedText("ConfigImport.Error.radioOutOfRange") { S.configImportErrorRadioOutOfRange(it, fieldLabel.resolve(it)) }
    fun noAvailableChannelSlot(name: String): UiText =
        generatedText("ConfigImport.Error.noAvailableChannelSlot") { S.configImportErrorNoAvailableChannelSlot(it, name) }
    fun positionInvalid(fieldLabel: UiText): UiText =
        generatedText("ConfigImport.Error.positionInvalid") { S.configImportErrorPositionInvalid(it, fieldLabel.resolve(it)) }
    fun contactCoordinateInvalid(name: String, fieldLabel: UiText): UiText =
        generatedText("ConfigImport.Error.contactCoordinateInvalid") { S.configImportErrorContactCoordinateInvalid(it, name, fieldLabel.resolve(it)) }
    fun invalidOutPath(name: String): UiText =
        generatedText("ConfigImport.Error.invalidOutPath") { S.configImportErrorInvalidOutPath(it, name) }
    fun contactCapacityExceeded(needed: Long, available: Long): UiText = formattedText(
        R.string.l10n_app_settings_configimport_error_contactcapacityexceeded,
        UiFormatArgument.Integer(needed), UiFormatArgument.Integer(available),
    )
}
