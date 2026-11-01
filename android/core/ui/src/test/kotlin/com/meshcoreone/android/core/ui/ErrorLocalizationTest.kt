// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ErrorLocalizationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Pending producer annotations distinguish asserted copy policies from unavailable concrete service-error wiring.
package com.meshcoreone.android.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import com.meshcoreone.android.core.services.remote.RemoteNodeError
import com.meshcoreone.android.core.services.remote.RoomServerError
import com.meshcoreone.android.core.services.sync.SyncCoordinatorError
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS")
class ErrorLocalizationTest : SourceCaseProof() {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val mapper = UiErrorMapper()
    private fun copy(type: String, case: String) = ErrorCopy.static(type, case).resolve(resources)

    @OriginalCase("ErrorLocalizationTests::MeshCoreError.timeout produces human-readable description()")
    @Test fun timeout() = prove {
        assertEquals("The operation timed out. Please try again.", MeshCoreException.Timeout().sourceEnglishDescription())
    }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError.deviceError maps known firmware codes(code : UInt8 , expected : String)", 6)
    @Test fun knownCodes() = prove(6) {
        val expected = listOf("Command not supported by device firmware.", "Item not found on device.",
            "Device storage is full.", "Device is in an invalid state for this operation.",
            "Device file system error.", "Invalid parameter sent to device.")
        for ((index, description) in expected.withIndex()) {
            assertEquals(description, MeshCoreException.DeviceError((index + 1).toUByte()).sourceEnglishDescription())
        }
    }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError.deviceError falls back for unknown codes()")
    @Test fun unknownCode() = prove { assertEquals("Device error (code 10).", MeshCoreException.DeviceError(10u).sourceEnglishDescription()) }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError.deviceError handles code zero()")
    @Test fun zeroCode() = prove { assertEquals("Device error (code 0).", MeshCoreException.DeviceError(0u).sourceEnglishDescription()) }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError bluetooth errors produce readable descriptions()")
    @Test fun bluetooth() = prove {
        assertEquals("Bluetooth is not available on this device.", MeshCoreException.BluetoothUnavailable().sourceEnglishDescription())
        assertEquals("Bluetooth permission is required. Please enable it in Settings.", MeshCoreException.BluetoothUnauthorized().sourceEnglishDescription())
        assertEquals("Bluetooth is turned off. Please enable Bluetooth to connect.", MeshCoreException.BluetoothPoweredOff().sourceEnglishDescription())
    }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError.connectionLost includes underlying error when present()")
    @Test fun underlyingConnection() = prove {
        val description = MeshCoreException.ConnectionLost(IllegalStateException("link dropped")).sourceEnglishDescription()
        assertTrue(description.contains("Connection to device was lost")); assertTrue(description.contains("link dropped"))
    }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError.connectionLost without underlying error()")
    @Test fun connectionWithoutCause() = prove {
        assertEquals("Connection to device was lost.", MeshCoreException.ConnectionLost().sourceEnglishDescription())
    }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError.featureDisabled produces readable description()")
    @Test fun disabled() = prove {
        assertEquals("This feature is disabled on the device.", MeshCoreException.FeatureDisabled().sourceEnglishDescription())
    }
    @OriginalCase("ErrorLocalizationTests::MeshCoreError.sessionNotStarted produces readable description()")
    @Test fun sessionNotStarted() = prove {
        assertEquals("Session has not been started.", MeshCoreException.SessionNotStarted().sourceEnglishDescription())
    }
    @OriginalCase("ErrorLocalizationTests::ProtocolError cases produce non-empty, readable descriptions(protocolError : ProtocolError)", 6)
    @Test fun protocolDescriptions() = prove(6) {
        for (error in ErrorCode.entries) {
            val description = mapper.protocolError(error).resolve(resources)
            assertTrue(description.isNotEmpty()); assertFalse(description.contains("ProtocolError"))
        }
    }
    @NativeAdaptation("native-typed-description-accessor")
    @OriginalCase("ErrorLocalizationTests::MessageServiceError.sessionError passes through MeshCoreError description()")
    @Test fun messageConcreteDelegationKeepsTheOriginalProtocolCauseAndLocalizedCopy() = prove {
        val underlying = MeshCoreException.DeviceError(3u)
        val failure = MessageServiceException(MessageServiceError.SessionError(underlying))
        assertSame(underlying, failure.cause)
        assertEquals("Device storage is full.", failure.sourceEnglishDescription())
        assertEquals(mapper.deviceError(3u).resolve(resources), mapper.message(failure).resolve(resources))
        assertFalse(mapper.message(failure).resolve(resources).contains("SessionError"))
    }
    @NativeAdaptation("native-typed-description-accessor")
    @OriginalCase("ErrorLocalizationTests::ChannelServiceError.sessionError passes through MeshCoreError description()")
    @Test fun actualChannelSessionKeepsItsCauseAndCentralDescription() = prove {
        val cause = MeshCoreException.DeviceError(3u)
        val failure = ChannelServiceError.SessionError(cause)
        assertSame(cause, failure.cause)
        assertSame(cause, failure.error)
        assertEquals("Device storage is full.", failure.sourceEnglishDescription())
        assertEquals(mapper.message(cause).resolve(resources), mapper.message(failure).resolve(resources))
    }
    @OriginalCase("ErrorLocalizationTests::SettingsServiceError.sessionError passes through without prefix()")
    @Test fun settingsConcreteDelegation() = prove {
        val underlying = MeshCoreException.NotConnected()
        val failure = SettingsServiceException(SettingsServiceError.SessionError(underlying))
        assertSame(underlying, failure.cause)
        val description = mapper.message(failure).resolve(resources)
        assertEquals("Not connected to device.", description)
        assertEquals("Not connected to device.", underlying.sourceEnglishDescription())
        assertFalse(description.contains("Session error:"))
    }
    @OriginalCase("ErrorLocalizationTests::SettingsServiceError.deviceGPSVerificationFailed is human-readable()")
    @Test fun gpsConcreteDescription() = prove {
        val fault = SettingsServiceError.DeviceGPSVerificationFailed(false, true)
        val failure = SettingsServiceException(fault)
        assertEquals("Device GPS setting was not saved. Expected 'Off' but device reports 'On'.", failure.localizedMessage)
        val copy = mapper.message(failure).resolve(resources)
        assertEquals(resources.getString(L.errorSettingsGpsNotSavedExpectedOff), copy)
        assertFalse(fault.expectedEnabled); assertTrue(fault.actualEnabled)
        assertTrue(copy.isNotEmpty()); assertFalse(copy.contains("SettingsServiceError"))
    }
    @NativeAdaptation("native-typed-description-accessor")
    @OriginalCase("ErrorLocalizationTests::RemoteNodeError.sessionError passes through without prefix()")
    @Test fun actualRemoteSessionKeepsItsCauseAndDescriptionWithoutPrefix() = prove {
        val cause = MeshCoreException.BluetoothPoweredOff()
        val failure = RemoteNodeError.SessionError(cause)
        assertSame(cause, failure.cause)
        val description = failure.sourceEnglishDescription()
        assertEquals("Bluetooth is turned off. Please enable Bluetooth to connect.", description)
        assertFalse(description.contains("Session error:"))
        assertEquals(mapper.message(cause).resolve(resources), mapper.message(failure).resolve(resources))
    }
    @OriginalCase("ErrorLocalizationTests::AdvertisementError.notConnected produces readable description()")
    @Test fun actualAdvertisementNotConnectedHasReadableLocalizedCopy() = prove {
        val failure = AdvertisementError.NotConnected()
        assertEquals("Not connected to device.", mapper.message(failure).resolve(resources))
        assertEquals("Not connected to device.", failure.sourceEnglishDescription())
        assertSame(failure, mapper.present(failure).originalFailure)
    }
    @OriginalCase("ErrorLocalizationTests::RoomServerError.permissionDenied produces readable description()")
    @Test fun actualRoomPermissionFailureHasReadableLocalizedCopy() = prove {
        val failure = RoomServerError.PermissionDenied()
        assertEquals("Permission denied.", mapper.message(failure).resolve(resources))
        assertEquals("Permission denied.", failure.sourceEnglishDescription())
        assertSame(failure, mapper.present(failure).originalFailure)
    }
    @OriginalCase("ErrorLocalizationTests::BinaryProtocolError.timeout produces readable description()")
    @Test fun actualBinaryTimeoutHasReadableLocalizedCopy() = prove {
        val failure = BinaryProtocolError.Timeout()
        assertEquals("Request timed out.", mapper.message(failure).resolve(resources))
        assertEquals("Request timed out.", failure.sourceEnglishDescription())
        assertSame(failure, mapper.present(failure).originalFailure)
    }
    @OriginalCase("ErrorLocalizationTests::SyncCoordinatorError.alreadySyncing produces readable description()")
    @Test fun actualSyncAlreadyRunningHasReadableProducerDescriptionAndLocalizedCopy() = prove {
        val failure = SyncCoordinatorError.AlreadySyncing()
        assertEquals("A sync is already in progress.", failure.message)
        assertEquals(resources.getString(L.errorSyncCoordinatorAlreadySyncing), mapper.message(failure).resolve(resources))
        assertSame(failure, mapper.present(failure).originalFailure)
    }
    @OriginalCase("ErrorLocalizationTests::PersistenceStoreError.contactNotFound produces readable description()")
    @Test fun persistenceDescription() = prove {
        val failure = PersistenceStoreException(PersistenceStoreError.ContactNotFound)
        assertEquals("Contact not found.", mapper.message(failure).resolve(resources))
        assertEquals("ContactNotFound", failure.message)
        assertSame(failure, mapper.present(failure).originalFailure)
    }
}
