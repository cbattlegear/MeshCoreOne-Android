// PortedFrom: MC1Services/Sources/MC1Services/Errors/BLEError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.contracts.domain.errors.BleFault
import com.meshcoreone.android.core.contracts.domain.errors.BleFaultBondState
import com.meshcoreone.android.core.contracts.domain.errors.BleFaultOperation
import com.meshcoreone.android.core.contracts.domain.errors.BleFaultProperty
import com.meshcoreone.android.core.contracts.domain.errors.BleFaultRecovery
import com.meshcoreone.android.core.contracts.domain.errors.BleFaultStatusDomain
import com.meshcoreone.android.core.contracts.domain.errors.BleTransportFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier

sealed interface BleError {
    data object BluetoothUnavailable : BleError
    data object BluetoothUnauthorized : BleError
    data object BluetoothPoweredOff : BleError
    data object DeviceNotFound : BleError
    data class ConnectionFailed(val reason: String) : BleError
    data object ConnectionTimeout : BleError
    data object NotConnected : BleError
    data object CharacteristicNotFound : BleError
    data class WriteError(val reason: String) : BleError
    data object InvalidResponse : BleError
    data object OperationTimeout : BleError
    data object AuthenticationFailed : BleError
    data class PairingFailed(val reason: String) : BleError
    data object DeviceConnectedToOtherApp : BleError

    data object ServiceNotFound : BleError
    data object DescriptorNotFound : BleError
    data class CharacteristicPropertyMissing(val property: GattProperty) : BleError
    data class BondRequired(val state: BondState) : BleError
    data class GattRejected(val operation: GattOperationKind, val status: Int?) : BleError
    data class GattOperationInProgress(val operation: GattOperationKind) : BleError
    data class PlatformApiUnavailable(val minimum: Int, val actual: Int) : BleError
    data class MtuTooSmall(val actual: Int, val minimum: Int) : BleError
    data class InvalidMtu(val actual: Int) : BleError
    data class FrameTooLarge(val actual: Int, val maximum: Int) : BleError
    data class FirmwareCapabilityUnverified(val actual: Int, val bootstrapMaximum: Int) : BleError
    data object FirmwareCapabilitiesAlreadyVerified : BleError
    data class StaleGeneration(val expected: Long, val actual: Long) : BleError
    data object MultipleReceivers : BleError
    data object NotificationDeliveryFailed : BleError
    data class RssiReadFailed(val status: Int) : BleError
    data class CleanupFailed(val stage: String) : BleError
    data class AbortedOperation(val operation: GattOperationKind) : BleError
}

enum class BleRecovery {
    EnableBluetooth, GrantBluetoothConnect, SelectDevice, PairInSystem,
    RetryWithNewConnection, VerifyFirmwareCapabilities, IncreaseMtu,
    ReduceFrame, UseSingleReceiver, HostConfiguration,
}

enum class GattStatusDomain { Att, ConnectionState, PlatformStart, RadioMeasurement }

class BleTransportException(
    val error: BleError,
    val operation: GattOperationKind? = null,
    val status: Int? = null,
    cause: Throwable? = null,
    val statusDomain: GattStatusDomain? = null,
) : Exception("ble.${error.javaClass.simpleName}", cause), SourceServiceFaultCarrier {
    val recovery: BleRecovery
        get() = when (error) {
            BleError.BluetoothUnavailable, is BleError.PlatformApiUnavailable -> BleRecovery.HostConfiguration
            BleError.BluetoothUnauthorized -> BleRecovery.GrantBluetoothConnect
            BleError.BluetoothPoweredOff -> BleRecovery.EnableBluetooth
            BleError.DeviceNotFound -> BleRecovery.SelectDevice
            BleError.AuthenticationFailed, is BleError.PairingFailed, is BleError.BondRequired -> BleRecovery.PairInSystem
            is BleError.FirmwareCapabilityUnverified -> BleRecovery.VerifyFirmwareCapabilities
            is BleError.MtuTooSmall -> BleRecovery.IncreaseMtu
            is BleError.FrameTooLarge -> BleRecovery.ReduceFrame
            BleError.MultipleReceivers -> BleRecovery.UseSingleReceiver
            else -> BleRecovery.RetryWithNewConnection
        }

    override val sourceServiceFault: BleTransportFault
        get() = BleTransportFault(
            error = when (val fault = error) {
                BleError.BluetoothUnavailable -> BleFault.BluetoothUnavailable
                BleError.BluetoothUnauthorized -> BleFault.BluetoothUnauthorized
                BleError.BluetoothPoweredOff -> BleFault.BluetoothPoweredOff
                BleError.DeviceNotFound -> BleFault.DeviceNotFound
                is BleError.ConnectionFailed -> BleFault.ConnectionFailed(fault.reason)
                BleError.ConnectionTimeout -> BleFault.ConnectionTimeout
                BleError.NotConnected -> BleFault.NotConnected
                BleError.CharacteristicNotFound -> BleFault.CharacteristicNotFound
                is BleError.WriteError -> BleFault.WriteError(fault.reason)
                BleError.InvalidResponse -> BleFault.InvalidResponse
                BleError.OperationTimeout -> BleFault.OperationTimeout
                BleError.AuthenticationFailed -> BleFault.AuthenticationFailed
                is BleError.PairingFailed -> BleFault.PairingFailed(fault.reason)
                BleError.DeviceConnectedToOtherApp -> BleFault.DeviceConnectedToOtherApp
                BleError.ServiceNotFound -> BleFault.ServiceNotFound
                BleError.DescriptorNotFound -> BleFault.DescriptorNotFound
                is BleError.CharacteristicPropertyMissing -> BleFault.CharacteristicPropertyMissing(
                    when (fault.property) {
                        GattProperty.Write -> BleFaultProperty.Write
                        GattProperty.WriteWithoutResponse -> BleFaultProperty.WriteWithoutResponse
                        GattProperty.Notify -> BleFaultProperty.Notify
                    },
                )
                is BleError.BondRequired -> BleFault.BondRequired(when (fault.state) {
                    BondState.None -> BleFaultBondState.None
                    BondState.Bonding -> BleFaultBondState.Bonding
                    BondState.Bonded -> BleFaultBondState.Bonded
                })
                is BleError.GattRejected -> BleFault.GattRejected(fault.operation.toFault(), fault.status)
                is BleError.GattOperationInProgress -> BleFault.GattOperationInProgress(fault.operation.toFault())
                is BleError.PlatformApiUnavailable -> BleFault.PlatformApiUnavailable(fault.minimum, fault.actual)
                is BleError.MtuTooSmall -> BleFault.MtuTooSmall(fault.actual, fault.minimum)
                is BleError.InvalidMtu -> BleFault.InvalidMtu(fault.actual)
                is BleError.FrameTooLarge -> BleFault.FrameTooLarge(fault.actual, fault.maximum)
                is BleError.FirmwareCapabilityUnverified ->
                    BleFault.FirmwareCapabilityUnverified(fault.actual, fault.bootstrapMaximum)
                BleError.FirmwareCapabilitiesAlreadyVerified -> BleFault.FirmwareCapabilitiesAlreadyVerified
                is BleError.StaleGeneration -> BleFault.StaleGeneration(fault.expected, fault.actual)
                BleError.MultipleReceivers -> BleFault.MultipleReceivers
                BleError.NotificationDeliveryFailed -> BleFault.NotificationDeliveryFailed
                is BleError.RssiReadFailed -> BleFault.RssiReadFailed(fault.status)
                is BleError.CleanupFailed -> BleFault.CleanupFailed(fault.stage)
                is BleError.AbortedOperation -> BleFault.AbortedOperation(fault.operation.toFault())
            },
            operation = operation?.toFault(),
            status = status,
            statusDomain = statusDomain?.let { when (it) {
                GattStatusDomain.Att -> BleFaultStatusDomain.Att
                GattStatusDomain.ConnectionState -> BleFaultStatusDomain.ConnectionState
                GattStatusDomain.PlatformStart -> BleFaultStatusDomain.PlatformStart
                GattStatusDomain.RadioMeasurement -> BleFaultStatusDomain.RadioMeasurement
            } },
            recovery = when (recovery) {
                BleRecovery.EnableBluetooth -> BleFaultRecovery.EnableBluetooth
                BleRecovery.GrantBluetoothConnect -> BleFaultRecovery.GrantBluetoothConnect
                BleRecovery.SelectDevice -> BleFaultRecovery.SelectDevice
                BleRecovery.PairInSystem -> BleFaultRecovery.PairInSystem
                BleRecovery.RetryWithNewConnection -> BleFaultRecovery.RetryWithNewConnection
                BleRecovery.VerifyFirmwareCapabilities -> BleFaultRecovery.VerifyFirmwareCapabilities
                BleRecovery.IncreaseMtu -> BleFaultRecovery.IncreaseMtu
                BleRecovery.ReduceFrame -> BleFaultRecovery.ReduceFrame
                BleRecovery.UseSingleReceiver -> BleFaultRecovery.UseSingleReceiver
                BleRecovery.HostConfiguration -> BleFaultRecovery.HostConfiguration
            },
        )
}

private fun GattOperationKind.toFault(): BleFaultOperation = when (this) {
    GattOperationKind.Connect -> BleFaultOperation.Connect
    GattOperationKind.DiscoverServices -> BleFaultOperation.DiscoverServices
    GattOperationKind.Mtu -> BleFaultOperation.Mtu
    GattOperationKind.Subscribe -> BleFaultOperation.Subscribe
    GattOperationKind.Write -> BleFaultOperation.Write
    GattOperationKind.Rssi -> BleFaultOperation.Rssi
}

internal fun gattFailure(kind: GattOperationKind, status: Int): BleTransportException {
    val error = when {
        status in setOf(5, 8, 12, 15) -> BleError.AuthenticationFailed
        kind == GattOperationKind.Write -> BleError.WriteError("gatt.status.$status")
        else -> BleError.ConnectionFailed("gatt.status.$status")
    }
    return BleTransportException(error, kind, status, statusDomain = GattStatusDomain.Att)
}

internal fun connectionStateFailure(kind: GattOperationKind, status: Int): BleTransportException {
    val error = when (status) {
        5 -> BleError.AuthenticationFailed
        8, 147 -> BleError.ConnectionTimeout
        else -> BleError.ConnectionFailed("gatt.status.$status")
    }
    return BleTransportException(error, kind, status, statusDomain = GattStatusDomain.ConnectionState)
}
