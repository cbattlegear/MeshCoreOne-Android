// PortedFrom: MC1Services/Sources/MC1Services/Services/DeviceService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Errors/SettingsServiceError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native assertions: typed neutral producer metadata and cause/retry behavior for later localized consumers.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.DeviceServiceError
import com.meshcoreone.android.core.contracts.domain.errors.DeviceServiceException
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.TestFactory

class DeviceSettingsFaultTest {
    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("device fault producer retains both source cases and persistence cause") {
            val cause = IllegalStateException("disk failure")
            val error = DeviceServiceException(DeviceServiceError.PersistenceFailed("reason"), cause)
            assertEquals("reason", (error.error as DeviceServiceError.PersistenceFailed).reason)
            assertSame(cause, error.cause)
            assertEquals("Failed to save device settings: reason", error.message)
            assertEquals("Device not found", DeviceServiceException(DeviceServiceError.DeviceNotFound).message)
        },
        nativeCase("settings retry classification is exactly the source three conditions") {
            assertTrue(SettingsServiceError.NotConnected.isRetryable)
            assertTrue(SettingsServiceError.SendFailed.isRetryable)
            assertTrue(SettingsServiceError.SessionError(MeshCoreException.Timeout()).isRetryable)
            assertFalse(SettingsServiceError.InvalidResponse.isRetryable)
            assertFalse(SettingsServiceError.SessionError(MeshCoreException.NotConnected()).isRetryable)
            assertFalse(SettingsServiceError.SessionError(MeshCoreException.DeviceError(3u)).isRetryable)
            assertFalse(SettingsServiceError.VerificationFailed("a", "b").isRetryable)
            assertFalse(SettingsServiceError.DeviceGPSVerificationFailed(true, false).isRetryable)
        },
        nativeCase("settings verification and session faults retain exact payloads and cause") {
            val cause = MeshCoreException.InvalidResponse("expected", "actual")
            val session = SettingsServiceException(SettingsServiceError.SessionError(cause))
            assertSame(cause, session.cause)
            assertSame(cause, (session.error as SettingsServiceError.SessionError).error)
            val verification = SettingsServiceError.VerificationFailed("bitmask=1, maxHops=3", "bitmask=2, maxHops=4")
            val error = SettingsServiceException(verification)
            assertSame(verification, error.error)
            assertEquals(
                "Setting was not saved. Expected 'bitmask=1, maxHops=3' but device reports 'bitmask=2, maxHops=4'.",
                error.message,
            )
        },
        nativeCase("GPS fault producer retains every expected and actual boolean combination") {
            for (expected in listOf(false, true)) for (actual in listOf(false, true)) {
                val fault = SettingsServiceError.DeviceGPSVerificationFailed(expected, actual)
                assertEquals(expected, fault.expectedEnabled)
                assertEquals(actual, fault.actualEnabled)
                assertFalse(fault.isRetryable)
            }
        },
    )
}
