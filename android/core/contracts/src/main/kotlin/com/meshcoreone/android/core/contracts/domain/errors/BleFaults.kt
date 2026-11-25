// PortedFrom: MC1Services/Sources/MC1Services/Errors/BLEError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native GATT metadata is a lossless projection, not a new transport or recovery policy.
package com.meshcoreone.android.core.contracts.domain.errors

data class BleTransportFault(
    val error: BleFault,
    val operation: BleFaultOperation?,
    val status: Int?,
    val statusDomain: BleFaultStatusDomain?,
    val recovery: BleFaultRecovery,
) : SourceServiceFault

sealed interface BleFault {
    data object BluetoothUnavailable : BleFault
    data object BluetoothUnauthorized : BleFault
    data object BluetoothPoweredOff : BleFault
    data object DeviceNotFound : BleFault
    data class ConnectionFailed(val reason: String) : BleFault
    data object ConnectionTimeout : BleFault
    data object NotConnected : BleFault
    data object CharacteristicNotFound : BleFault
    data class WriteError(val reason: String) : BleFault
    data object InvalidResponse : BleFault
    data object OperationTimeout : BleFault
    data object AuthenticationFailed : BleFault
    data class PairingFailed(val reason: String) : BleFault
    data object DeviceConnectedToOtherApp : BleFault
    data object ServiceNotFound : BleFault
    data object DescriptorNotFound : BleFault
    data class CharacteristicPropertyMissing(val property: BleFaultProperty) : BleFault
    data class BondRequired(val state: BleFaultBondState) : BleFault
    data class GattRejected(val operation: BleFaultOperation, val status: Int?) : BleFault
    data class GattOperationInProgress(val operation: BleFaultOperation) : BleFault
    data class PlatformApiUnavailable(val minimum: Int, val actual: Int) : BleFault
    data class MtuTooSmall(val actual: Int, val minimum: Int) : BleFault
    data class InvalidMtu(val actual: Int) : BleFault
    data class FrameTooLarge(val actual: Int, val maximum: Int) : BleFault
    data class FirmwareCapabilityUnverified(val actual: Int, val bootstrapMaximum: Int) : BleFault
    data object FirmwareCapabilitiesAlreadyVerified : BleFault
    data class StaleGeneration(val expected: Long, val actual: Long) : BleFault
    data object MultipleReceivers : BleFault
    data object NotificationDeliveryFailed : BleFault
    data class RssiReadFailed(val status: Int) : BleFault
    data class CleanupFailed(val stage: String) : BleFault
    data class AbortedOperation(val operation: BleFaultOperation) : BleFault
}

enum class BleFaultProperty { Write, WriteWithoutResponse, Notify }
enum class BleFaultOperation { Connect, DiscoverServices, Mtu, Subscribe, Write, Rssi }
enum class BleFaultBondState { None, Bonding, Bonded }
enum class BleFaultStatusDomain { Att, ConnectionState, PlatformStart, RadioMeasurement }
enum class BleFaultRecovery {
    EnableBluetooth, GrantBluetoothConnect, SelectDevice, PairInSystem,
    RetryWithNewConnection, VerifyFirmwareCapabilities, IncreaseMtu,
    ReduceFrame, UseSingleReceiver, HostConfiguration,
}
