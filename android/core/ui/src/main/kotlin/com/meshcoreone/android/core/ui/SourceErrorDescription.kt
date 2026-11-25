// PortedFrom: MC1Services/Sources/MC1Services/Errors/MeshCoreError+LocalizedError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageServiceError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Developer-facing frozen English fallback is separate from localized UI copy.
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.AdvertisementFault
import com.meshcoreone.android.core.contracts.domain.errors.BinaryProtocolFault
import com.meshcoreone.android.core.contracts.domain.errors.ChannelServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.ContactServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.RemoteNodeFault
import com.meshcoreone.android.core.contracts.domain.errors.RoomServerFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier

fun <T> T.sourceEnglishDescription(): String where T : Throwable, T : SourceServiceFaultCarrier {
    val session = when (val fault = sourceServiceFault) {
        is ContactServiceFault.SessionError -> fault.error
        is ChannelServiceFault.SessionError -> fault.error
        is AdvertisementFault.SessionError -> fault.error
        is RemoteNodeFault.SessionError -> fault.error
        is RoomServerFault.SessionError -> fault.error
        is BinaryProtocolFault.SessionError -> fault.error
        else -> null
    }
    return session?.sourceEnglishDescription() ?: requireNotNull(message) {
        "Source fault producer must retain its diagnostic constructor"
    }
}

fun MessageServiceException.sourceEnglishDescription(): String = when (val fault = error) {
    MessageServiceError.NotConnected -> "Not connected to device."
    MessageServiceError.ContactNotFound -> "Contact not found."
    MessageServiceError.ChannelNotFound -> "Channel not found."
    is MessageServiceError.SendFailed -> "Send failed: ${fault.reason}"
    MessageServiceError.InvalidRecipient -> "Cannot send messages to this recipient."
    MessageServiceError.MessageTooLong -> "Message exceeds the maximum allowed length."
    is MessageServiceError.SessionError -> fault.underlying.sourceEnglishDescription()
}

fun MeshCoreException.sourceEnglishDescription(): String = when (this) {
    is MeshCoreException.Timeout -> "The operation timed out. Please try again."
    is MeshCoreException.DeviceError -> when (code.toInt()) {
        1 -> "Command not supported by device firmware."
        2 -> "Item not found on device."
        3 -> "Device storage is full."
        4 -> "Device is in an invalid state for this operation."
        5 -> "Device file system error."
        6 -> "Invalid parameter sent to device."
        else -> "Device error (code ${code.toInt()})."
    }
    is MeshCoreException.ParseError -> "Failed to parse device response: $reason"
    is MeshCoreException.NotConnected -> "Not connected to device."
    is MeshCoreException.CommandFailed -> "Command failed: $reason"
    is MeshCoreException.InvalidResponse -> "Unexpected response from device (expected $expected, got $got)."
    is MeshCoreException.ContactNotFound -> "Contact not found on device."
    is MeshCoreException.DataTooLarge -> "Data too large ($actualSize bytes, maximum is $maxSize)."
    is MeshCoreException.SigningFailed -> "Signing failed: $reason"
    is MeshCoreException.InvalidInput -> "Invalid input: $reason"
    is MeshCoreException.Unknown -> "An unknown error occurred: $reason"
    is MeshCoreException.BluetoothUnavailable -> "Bluetooth is not available on this device."
    is MeshCoreException.BluetoothUnauthorized -> "Bluetooth permission is required. Please enable it in Settings."
    is MeshCoreException.BluetoothPoweredOff -> "Bluetooth is turned off. Please enable Bluetooth to connect."
    is MeshCoreException.ConnectionLost -> {
        val description = cause?.let {
            if (it is MeshCoreException) it.sourceEnglishDescription() else it.localizedMessage?.takeIf(String::isNotBlank)
        }
        description?.let { "Connection to device was lost: $it" } ?: "Connection to device was lost."
    }
    is MeshCoreException.SessionNotStarted -> "Session has not been started."
    is MeshCoreException.FeatureDisabled -> "This feature is disabled on the device."
}
