// PortedFrom: MC1/Extensions/Errors/Error+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/BLEError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/ConnectionError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/TimeoutError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/Errors/NodeConfigServiceError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Neutral payload rendering never replaces the real producer throwable or its cause.
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S

internal fun sourceFaultPresentation(
    fault: SourceServiceFault,
    underlying: (Throwable) -> UiText,
): UiErrorMapping {
    val copy = when (fault) {
        is RuntimeTimeoutFault -> ErrorCopy.static("TimeoutError", "timeout")
        is ConnectionFault -> when (fault) {
            is ConnectionFault.ConnectionFailed -> ErrorCopy.connectionFailed(fault.reason)
            ConnectionFault.DeviceNotFound -> ErrorCopy.static("ConnectionError", "deviceNotFound")
            ConnectionFault.NotConnected -> ErrorCopy.static("ConnectionError", "notConnected")
            is ConnectionFault.InitializationFailed -> ErrorCopy.initializationFailed(fault.reason)
            is ConnectionFault.UnsupportedCapability, ConnectionFault.ForeignPhysicalOwner,
            ConnectionFault.RetainedPhysicalLink, ConnectionFault.InvalidIdentity,
            ConnectionFault.FactoryOwnershipViolation -> UiText.Resource(L.commonErrorFailedToLoad)
        }
        is BleTransportFault -> bleFaultCopy(fault.error)
        is ContactServiceFault -> when (fault) {
            ContactServiceFault.NotConnected -> ErrorCopy.static("ContactServiceError", "notConnected")
            ContactServiceFault.SendFailed -> ErrorCopy.static("ContactServiceError", "sendFailed")
            ContactServiceFault.InvalidResponse -> ErrorCopy.static("ContactServiceError", "invalidResponse")
            ContactServiceFault.SyncInterrupted -> ErrorCopy.static("ContactServiceError", "syncInterrupted")
            ContactServiceFault.ContactNotFound -> ErrorCopy.static("ContactServiceError", "contactNotFound")
            ContactServiceFault.ContactTableFull -> ErrorCopy.static("ContactServiceError", "contactTableFull")
            ContactServiceFault.ShareContactUnavailable -> ErrorCopy.static("ContactServiceError", "shareContactUnavailable")
            is ContactServiceFault.SessionError -> underlying(fault.error)
        }
        is ChannelServiceFault -> when (fault) {
            ChannelServiceFault.NotConnected -> ErrorCopy.static("ChannelServiceError", "notConnected")
            ChannelServiceFault.ChannelNotFound -> ErrorCopy.static("ChannelServiceError", "channelNotFound")
            ChannelServiceFault.InvalidChannelIndex -> ErrorCopy.static("ChannelServiceError", "invalidChannelIndex")
            ChannelServiceFault.SecretHashingFailed -> ErrorCopy.static("ChannelServiceError", "secretHashingFailed")
            is ChannelServiceFault.SaveFailed -> ErrorCopy.channelSaveFailed(fault.reason)
            is ChannelServiceFault.SendFailed -> ErrorCopy.channelSendFailed(fault.reason)
            is ChannelServiceFault.SessionError -> underlying(fault.error)
            ChannelServiceFault.SyncAlreadyInProgress -> ErrorCopy.static("ChannelServiceError", "syncAlreadyInProgress")
            is ChannelServiceFault.CircuitBreakerOpen -> ErrorCopy.circuitBreakerOpen(fault.consecutiveFailures.toLong())
        }
        is AdvertisementFault -> when (fault) {
            AdvertisementFault.NotConnected -> ErrorCopy.static("AdvertisementError", "notConnected")
            AdvertisementFault.SendFailed -> ErrorCopy.static("AdvertisementError", "sendFailed")
            AdvertisementFault.InvalidResponse -> ErrorCopy.static("AdvertisementError", "invalidResponse")
            is AdvertisementFault.SessionError -> underlying(fault.error)
        }
        is RemoteNodeFault -> when (fault) {
            RemoteNodeFault.NotConnected -> ErrorCopy.static("RemoteNodeError", "notConnected")
            is RemoteNodeFault.LoginFailed -> ErrorCopy.static("RemoteNodeError", "loginFailed")
            is RemoteNodeFault.SendFailed -> ErrorCopy.static("RemoteNodeError", "sendFailed")
            RemoteNodeFault.InvalidResponse -> ErrorCopy.static("RemoteNodeError", "invalidResponse")
            RemoteNodeFault.PermissionDenied -> ErrorCopy.static("RemoteNodeError", "permissionDenied")
            RemoteNodeFault.Timeout -> ErrorCopy.static("RemoteNodeError", "timeout")
            RemoteNodeFault.SessionNotFound -> ErrorCopy.static("RemoteNodeError", "sessionNotFound")
            RemoteNodeFault.PasswordNotFound -> ErrorCopy.static("RemoteNodeError", "passwordNotFound")
            RemoteNodeFault.FloodRouted -> ErrorCopy.static("RemoteNodeError", "floodRouted")
            RemoteNodeFault.PathDiscoveryFailed -> ErrorCopy.static("RemoteNodeError", "pathDiscoveryFailed")
            RemoteNodeFault.ContactNotFound -> ErrorCopy.static("RemoteNodeError", "contactNotFound")
            RemoteNodeFault.RadioContactsFull -> ErrorCopy.static("RemoteNodeError", "radioContactsFull")
            RemoteNodeFault.Cancelled -> ErrorCopy.static("RemoteNodeError", "cancelled")
            RemoteNodeFault.InvalidSensorSetting -> UiText.Resource(com.meshcoreone.android.core.l10n.R.string.sensor_settings_invalid)
            RemoteNodeFault.SensorSettingRejected -> UiText.Resource(com.meshcoreone.android.core.l10n.R.string.sensor_settings_rejected)
            RemoteNodeFault.SensorOutcomeUnknown -> UiText.Resource(com.meshcoreone.android.core.l10n.R.string.sensor_settings_uncertain)
            is RemoteNodeFault.SessionError -> underlying(fault.error)
        }
        is RoomServerFault -> when (fault) {
            RoomServerFault.NotConnected -> ErrorCopy.static("RoomServerError", "notConnected")
            RoomServerFault.SessionNotFound -> ErrorCopy.static("RoomServerError", "sessionNotFound")
            is RoomServerFault.SendFailed -> ErrorCopy.static("RoomServerError", "sendFailed")
            RoomServerFault.PermissionDenied -> ErrorCopy.static("RoomServerError", "permissionDenied")
            RoomServerFault.InvalidResponse -> ErrorCopy.static("RoomServerError", "invalidResponse")
            is RoomServerFault.SessionError -> underlying(fault.error)
        }
        is BinaryProtocolFault -> when (fault) {
            BinaryProtocolFault.NotConnected -> ErrorCopy.static("BinaryProtocolError", "notConnected")
            BinaryProtocolFault.SendFailed -> ErrorCopy.static("BinaryProtocolError", "sendFailed")
            BinaryProtocolFault.Timeout -> ErrorCopy.static("BinaryProtocolError", "timeout")
            BinaryProtocolFault.InvalidResponse -> ErrorCopy.static("BinaryProtocolError", "invalidResponse")
            is BinaryProtocolFault.SessionError -> underlying(fault.error)
        }
        is NodeConfigServiceFault -> nodeConfigFaultCopy(fault)
        is CompanionSetupFault -> when (fault) {
            CompanionSetupFault.SessionNotActive -> ErrorCopy.static("AccessorySetupKitError", "sessionNotActive")
            CompanionSetupFault.SessionInvalidated -> ErrorCopy.static("AccessorySetupKitError", "sessionInvalidated")
            CompanionSetupFault.PickerDismissed -> ErrorCopy.static("AccessorySetupKitError", "pickerDismissed")
            CompanionSetupFault.UserCancelled -> ErrorCopy.static("RemoteNodeError", "cancelled")
            CompanionSetupFault.PickerRestricted -> ErrorCopy.static("AccessorySetupKitError", "pickerRestricted")
            CompanionSetupFault.PickerAlreadyActive -> ErrorCopy.static("AccessorySetupKitError", "pickerAlreadyActive")
            is CompanionSetupFault.PairingFailed -> ErrorCopy.accessoryPairingFailed(fault.reason)
            CompanionSetupFault.NoBluetoothIdentifier -> ErrorCopy.static("AccessorySetupKitError", "noBluetoothIdentifier")
            CompanionSetupFault.DiscoveryTimeout -> ErrorCopy.static("AccessorySetupKitError", "discoveryTimeout")
            CompanionSetupFault.ConnectionFailed -> ErrorCopy.static("AccessorySetupKitError", "connectionFailed")
        }
        is SyncFault -> when (fault) {
            SyncFault.NotConnected -> ErrorCopy.static("SyncCoordinatorError", "notConnected")
            is SyncFault.SyncFailed -> ErrorCopy.syncFailed(fault.reason)
            SyncFault.AlreadySyncing -> ErrorCopy.static("SyncCoordinatorError", "alreadySyncing")
        }
    }
    val recovery = when (fault) {
        is RuntimeTimeoutFault -> UiRecovery.RETRY
        ConnectionFault.NotConnected -> UiRecovery.CONNECT
        is BleTransportFault -> when (fault.recovery) {
            BleFaultRecovery.EnableBluetooth -> UiRecovery.ENABLE_BLUETOOTH
            BleFaultRecovery.GrantBluetoothConnect -> UiRecovery.GRANT_BLUETOOTH
            BleFaultRecovery.SelectDevice, BleFaultRecovery.RetryWithNewConnection -> UiRecovery.CONNECT
            BleFaultRecovery.ReduceFrame -> UiRecovery.REDUCE_PAYLOAD
            BleFaultRecovery.PairInSystem, BleFaultRecovery.VerifyFirmwareCapabilities,
            BleFaultRecovery.IncreaseMtu, BleFaultRecovery.UseSingleReceiver,
            BleFaultRecovery.HostConfiguration -> UiRecovery.INSPECT_FAILURE
        }
        is RemoteNodeFault -> if (fault.isRetryable) UiRecovery.RETRY else UiRecovery.INSPECT_FAILURE
        is NodeConfigServiceFault -> UiRecovery.FIX_INPUT
        else -> UiRecovery.INSPECT_FAILURE
    }
    return UiErrorMapping(copy, recovery, sourceFault = fault)
}

private fun bleFaultCopy(fault: BleFault): UiText = when (fault) {
    BleFault.BluetoothUnavailable -> ErrorCopy.static("BLEError", "bluetoothUnavailable")
    BleFault.BluetoothUnauthorized -> ErrorCopy.static("BLEError", "bluetoothUnauthorized")
    BleFault.BluetoothPoweredOff -> ErrorCopy.static("BLEError", "bluetoothPoweredOff")
    BleFault.DeviceNotFound -> ErrorCopy.static("BLEError", "deviceNotFound")
    is BleFault.ConnectionFailed -> ErrorCopy.bleConnectionFailed(fault.reason)
    BleFault.ConnectionTimeout -> ErrorCopy.static("BLEError", "connectionTimeout")
    BleFault.NotConnected -> ErrorCopy.static("BLEError", "notConnected")
    BleFault.CharacteristicNotFound -> ErrorCopy.static("BLEError", "characteristicNotFound")
    is BleFault.WriteError -> ErrorCopy.bleWriteError(fault.reason)
    BleFault.InvalidResponse -> ErrorCopy.static("BLEError", "invalidResponse")
    BleFault.OperationTimeout -> ErrorCopy.static("BLEError", "operationTimeout")
    BleFault.AuthenticationFailed -> ErrorCopy.static("BLEError", "authenticationFailed")
    is BleFault.PairingFailed -> ErrorCopy.blePairingFailed(fault.reason)
    BleFault.DeviceConnectedToOtherApp -> ErrorCopy.static("BLEError", "deviceConnectedToOtherApp")
    BleFault.ServiceNotFound, BleFault.DescriptorNotFound,
    is BleFault.CharacteristicPropertyMissing -> ErrorCopy.static("BLEError", "characteristicNotFound")
    is BleFault.BondRequired -> ErrorCopy.static("BLEError", "authenticationFailed")
    is BleFault.PlatformApiUnavailable -> ErrorCopy.static("BLEError", "bluetoothUnavailable")
    is BleFault.GattRejected, is BleFault.GattOperationInProgress, is BleFault.MtuTooSmall,
    is BleFault.InvalidMtu, is BleFault.FrameTooLarge, is BleFault.FirmwareCapabilityUnverified,
    BleFault.FirmwareCapabilitiesAlreadyVerified, is BleFault.StaleGeneration,
    BleFault.MultipleReceivers, BleFault.NotificationDeliveryFailed, is BleFault.RssiReadFailed,
    is BleFault.CleanupFailed, is BleFault.AbortedOperation -> UiText.Resource(L.commonErrorFailedToLoad)
}

private fun nodeConfigFaultCopy(fault: NodeConfigServiceFault): UiText = when (fault) {
    is NodeConfigServiceFault.InvalidChannelSecret -> ErrorCopy.invalidChannelSecret(fault.index.toLong(), fault.hexLength.toLong())
    is NodeConfigServiceFault.InvalidContactPublicKey -> ErrorCopy.invalidContactPublicKey(fault.name)
    is NodeConfigServiceFault.InvalidPathHashMode -> ErrorCopy.invalidPathHashMode(fault.name, fault.mode)
    is NodeConfigServiceFault.InvalidPrivateKey -> ErrorCopy.invalidPrivateKey(fault.hexLength.toLong())
    is NodeConfigServiceFault.InvalidRadioSettings -> ErrorCopy.radioOutOfRange(UiText.Resource(when (fault.field) {
        NodeConfigRadioField.FREQUENCY -> S.configImportFieldFrequency
        NodeConfigRadioField.BANDWIDTH -> S.configImportFieldBandwidth
        NodeConfigRadioField.SPREADING_FACTOR -> S.configImportFieldSpreadingFactor
        NodeConfigRadioField.CODING_RATE -> S.configImportFieldCodingRate
        NodeConfigRadioField.TX_POWER -> S.configImportFieldTxPower
    }))
    is NodeConfigServiceFault.NoAvailableChannelSlot -> ErrorCopy.noAvailableChannelSlot(fault.name)
    is NodeConfigServiceFault.InvalidCoordinate -> when (val field = fault.field) {
        NodeConfigCoordinateField.PositionLatitude -> ErrorCopy.positionInvalid(UiText.Resource(S.configImportFieldLatitude))
        NodeConfigCoordinateField.PositionLongitude -> ErrorCopy.positionInvalid(UiText.Resource(S.configImportFieldLongitude))
        is NodeConfigCoordinateField.ContactLatitude ->
            ErrorCopy.contactCoordinateInvalid(field.name, UiText.Resource(S.configImportFieldLatitude))
        is NodeConfigCoordinateField.ContactLongitude ->
            ErrorCopy.contactCoordinateInvalid(field.name, UiText.Resource(S.configImportFieldLongitude))
    }
    is NodeConfigServiceFault.InvalidOutPath -> ErrorCopy.invalidOutPath(fault.name)
    is NodeConfigServiceFault.ContactCapacityExceeded -> ErrorCopy.contactCapacityExceeded(fault.needed.toLong(), fault.available.toLong())
}
