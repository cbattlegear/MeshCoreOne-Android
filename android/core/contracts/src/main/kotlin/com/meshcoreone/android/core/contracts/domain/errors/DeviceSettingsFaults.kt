// PortedFrom: MC1Services/Sources/MC1Services/Services/DeviceService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Errors/SettingsServiceError.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.contracts.domain.errors

import com.meshcoreone.android.core.protocol.config.MeshCoreException

sealed interface DeviceServiceError {
    data object DeviceNotFound : DeviceServiceError
    data class PersistenceFailed(val reason: String) : DeviceServiceError
}

class DeviceServiceException(val error: DeviceServiceError, cause: Throwable? = null) :
    Exception(
        when (error) {
            DeviceServiceError.DeviceNotFound -> "Device not found"
            is DeviceServiceError.PersistenceFailed -> "Failed to save device settings: ${error.reason}"
        },
        cause,
    )

sealed interface SettingsServiceError {
    data object NotConnected : SettingsServiceError
    data object SendFailed : SettingsServiceError
    data object InvalidResponse : SettingsServiceError
    data class RadioCLIRejected(val reason: String) : SettingsServiceError
    data class SessionError(val error: MeshCoreException) : SettingsServiceError
    data class VerificationFailed(val expected: String, val actual: String) : SettingsServiceError
    data class DeviceGPSVerificationFailed(
        val expectedEnabled: Boolean,
        val actualEnabled: Boolean,
    ) : SettingsServiceError

    val isRetryable: Boolean
        get() = when (this) {
            NotConnected, SendFailed -> true
            is SessionError -> error is MeshCoreException.Timeout
            InvalidResponse, is RadioCLIRejected, is VerificationFailed, is DeviceGPSVerificationFailed -> false
        }
}

class SettingsServiceException(
    val error: SettingsServiceError,
    cause: Throwable? = when (error) {
        is SettingsServiceError.SessionError -> error.error
        else -> null
    },
) : Exception(
    when (error) {
        SettingsServiceError.NotConnected -> "Device not connected"
        SettingsServiceError.SendFailed -> "Failed to send command"
        SettingsServiceError.InvalidResponse -> "Invalid response from device"
        is SettingsServiceError.RadioCLIRejected -> error.reason
        is SettingsServiceError.SessionError -> error.error.message
        is SettingsServiceError.VerificationFailed ->
            "Setting was not saved. Expected '${error.expected}' but device reports '${error.actual}'."
        is SettingsServiceError.DeviceGPSVerificationFailed ->
            "Device GPS setting was not saved. Expected '${if (error.expectedEnabled) "On" else "Off"}' " +
                "but device reports '${if (error.actualEnabled) "On" else "Off"}'."
    },
    cause,
) {
    val isRetryable: Boolean get() = error.isRetryable
}
