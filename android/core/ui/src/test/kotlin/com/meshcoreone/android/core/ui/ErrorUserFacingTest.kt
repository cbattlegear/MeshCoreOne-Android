// PortedFrom: MC1Tests/Extensions/ErrorUserFacingMessageTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Actual producers execute through UiErrorMapper; original credit requires paired producer evidence.
package com.meshcoreone.android.core.ui

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
import com.meshcoreone.android.core.contracts.domain.errors.BleFault
import com.meshcoreone.android.core.contracts.domain.errors.BleTransportFault
import com.meshcoreone.android.core.contracts.domain.errors.ConnectionFault
import com.meshcoreone.android.core.contracts.domain.errors.RuntimeTimeoutFault
import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.datastore.KeyGenerationFailure
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.TimeoutError
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.services.sync.SyncCoordinatorError
import com.meshcoreone.android.core.contracts.domain.errors.CompanionSetupFault
import com.meshcoreone.android.core.contracts.domain.errors.SyncFault
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import com.meshcoreone.android.core.services.remote.NodeConfigServiceError
import com.meshcoreone.android.core.services.remote.RemoteNodeError
import com.meshcoreone.android.core.services.remote.RoomServerError
import com.meshcoreone.android.core.contracts.domain.errors.ChannelServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.NodeConfigServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.RemoteNodeFault
import com.meshcoreone.android.core.contracts.domain.errors.RoomServerFault
import kotlin.time.Duration.Companion.seconds
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS")
class ErrorUserFacingTest : SourceCaseProof() {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val mapper = UiErrorMapper()
    private fun message(error: Throwable) = mapper.message(error).resolve(resources)
    private fun copy(type: String, case: String) = ErrorCopy.static(type, case).resolve(resources)

    @OriginalCase("ErrorUserFacingMessageTests::mesh core error dispatches to concrete mapping()")
    @Test fun mesh() = prove { assertEquals(resources.getString(L.errorMeshCoreTimeout), message(MeshCoreException.Timeout())) }
    @OriginalCase("ErrorUserFacingMessageTests::protocol error dispatches to concrete mapping()")
    @Test fun protocol() = prove {
        assertEquals("Device storage is full.", mapper.protocolError(ErrorCode.TABLE_FULL).resolve(resources))
    }
    @OriginalCase("ErrorUserFacingMessageTests::timeout error dispatches to concrete mapping()")
    @Test fun actualRuntimeTimeoutHidesTheOperationNameAndPreservesItsDuration() = prove {
        val failure = TimeoutError("sync", 5.seconds)
        val presented = mapper.present(failure)
        assertEquals(resources.getString(L.errorTimeoutOperationTimedOut), presented.content.message.resolve(resources))
        assertFalse(presented.content.message.resolve(resources).contains("sync"))
        assertSame(failure, presented.originalFailure)
        assertEquals(RuntimeTimeoutFault("sync", 5.seconds), presented.content.sourceFault)
        assertEquals(UiRecovery.RETRY, presented.content.recovery)
    }
    @OriginalCase("ErrorUserFacingMessageTests::app backup error dispatches to concrete mapping()")
    @Test fun backup() = prove {
        assertEquals(resources.getString(S.settingsBackupErrorInvalidFile), message(AppBackupException(AppBackupError.InvalidFile)))
    }
    @OriginalCase("ErrorUserFacingMessageTests::ble error dispatches to concrete mapping()")
    @Test fun actualBleFailureDispatchesWithTheOriginalPayloadAndCause() = prove {
        val fault = BleError.ConnectionFailed("peripheral unreachable")
        val cause = IllegalStateException("synthetic original cause")
        val failure = BleTransportException(fault, cause = cause)
        val presented = mapper.present(failure)
        assertEquals(L.errorBleConnectionFailed(resources, fault.reason), presented.content.message.resolve(resources))
        assertSame(failure, presented.originalFailure)
        assertSame(cause, presented.originalFailure.cause)
        assertSame(fault, failure.error)
        assertEquals(BleFault.ConnectionFailed(fault.reason),
            assertIs<BleTransportFault>(presented.content.sourceFault).error)
    }
    @OriginalCase("ErrorUserFacingMessageTests::connection error dispatches to concrete mapping()")
    @Test fun actualRuntimeConnectionFailureKeepsItsReasonAndCause() = prove {
        val cause = IllegalStateException("synthetic original cause")
        val failure = ConnectionError.InitializationFailed("handshake rejected", cause)
        val presented = mapper.present(failure)
        assertEquals(L.errorConnectionInitializationFailed(resources, failure.reason),
            presented.content.message.resolve(resources))
        assertSame(failure, presented.originalFailure)
        assertSame(cause, presented.originalFailure.cause)
        assertEquals(ConnectionFault.InitializationFailed(failure.reason), presented.content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::wifi transport error dispatches to concrete mapping()")
    @Test fun wifi() = prove {
        assertEquals(resources.getString(L.errorWifiInvalidHost), message(WiFiTransportException(WiFiTransportError.InvalidHost)))
    }
    @OriginalCase("ErrorUserFacingMessageTests::accessory setup kit error dispatches to concrete mapping()")
    @Test fun actualCompanionPairingFailureRetainsItsRawReasonAndLocalizedCopy() = prove {
        val failure = CompanionSetupError.PairingFailed("user declined")
        assertEquals(L.errorAccessorySetupPairingFailed(resources, "user declined"),
            message(failure))
        assertSame(failure, mapper.present(failure).originalFailure)
        assertEquals(CompanionSetupFault.PairingFailed(failure.reason), mapper.present(failure).content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::contact service error dispatches to concrete mapping()")
    @Test fun actualContactTableFailureUsesItsProducerProjection() = prove {
        val failure = ContactServiceError.ContactTableFull()
        assertEquals(resources.getString(L.errorContactServiceContactTableFull), message(failure))
        assertSame(failure, mapper.present(failure).originalFailure)
        assertEquals(failure.sourceServiceFault, mapper.present(failure).content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::message service error dispatches to concrete mapping()")
    @Test fun actualSendFaultSuppressesItsRawReasonWithoutLosingThePayload() = prove {
        val fault = MessageServiceError.SendFailed("queue rejected")
        val failure = MessageServiceException(fault)
        val text = message(failure)
        assertEquals(resources.getString(L.errorMessageServiceSendFailed), text); assertFalse(text.contains("queue rejected"))
        assertSame(fault, failure.error); assertEquals("queue rejected", fault.reason)
        assertSame(failure, mapper.present(failure).originalFailure)
    }
    @OriginalCase("ErrorUserFacingMessageTests::channel service error dispatches to concrete mapping()")
    @Test fun actualChannelBreakerFailureRetainsItsCount() = prove {
        val failure = ChannelServiceError.CircuitBreakerOpen(3)
        val presented = mapper.present(failure)
        assertEquals(L.errorChannelServiceCircuitBreakerOpen(resources, 3), presented.content.message.resolve(resources))
        assertSame(failure, presented.originalFailure)
        assertEquals(ChannelServiceFault.CircuitBreakerOpen(3), presented.content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::chat send queue service error dispatches to concrete mapping()")
    @Test fun queueConcreteDispatch() = prove {
        assertEquals(resources.getString(L.errorChatSendQueueNotConnected),
            message(ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)))
    }
    @OriginalCase("ErrorUserFacingMessageTests::message polling error dispatches to concrete mapping()")
    @Test fun pollingConcreteDispatch() = prove {
        assertEquals(resources.getString(L.errorMessagePollingPollingFailed),
            message(MessagePollingException(MessagePollingError.PollingFailed)))
    }
    @OriginalCase("ErrorUserFacingMessageTests::advertisement error dispatches to concrete mapping()")
    @Test fun actualAdvertisementFailureDispatchesThroughItsRealCarrier() = prove {
        val failure = AdvertisementError.SendFailed()
        assertEquals(resources.getString(L.errorAdvertisementSendFailed), message(failure))
        assertSame(failure, mapper.present(failure).originalFailure)
        assertEquals(failure.sourceServiceFault, mapper.present(failure).content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::remote node error dispatches to concrete mapping()")
    @Test fun actualRemoteLoginFailureSuppressesCopyNotItsReason() = prove {
        val failure = RemoteNodeError.LoginFailed("authentication failed")
        val text = message(failure)
        assertEquals(resources.getString(L.errorRemoteNodeLoginFailed), text); assertFalse(text.contains("authentication failed"))
        assertEquals("authentication failed", failure.reason)
        assertEquals(RemoteNodeFault.LoginFailed(failure.reason), mapper.present(failure).content.sourceFault)
        assertSame(failure, mapper.present(failure).originalFailure)
        assertFalse(failure.isRetryable)
    }
    @OriginalCase("ErrorUserFacingMessageTests::room server error dispatches to concrete mapping()")
    @Test fun actualRoomSessionFailureDispatchesThroughItsRealCarrier() = prove {
        val failure = RoomServerError.SessionNotFound()
        assertEquals(resources.getString(L.errorRoomServerSessionNotFound), message(failure))
        assertSame(failure, mapper.present(failure).originalFailure)
        assertEquals(failure.sourceServiceFault, mapper.present(failure).content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::room server send failed drops raw reason()")
    @Test fun actualRoomSendFailureSuppressesCopyNotItsRawReason() = prove {
        val failure = RoomServerError.SendFailed("Retry already in progress")
        val text = message(failure)
        assertEquals(resources.getString(L.errorRoomServerSendFailed), text); assertFalse(text.contains("Retry already in progress"))
        assertEquals("Retry already in progress", failure.reason)
        assertEquals(RoomServerFault.SendFailed(failure.reason), mapper.present(failure).content.sourceFault)
        assertSame(failure, mapper.present(failure).originalFailure)
    }
    @OriginalCase("ErrorUserFacingMessageTests::binary protocol error dispatches to concrete mapping()")
    @Test fun actualBinaryTimeoutDispatchesThroughItsRealCarrier() = prove {
        val failure = BinaryProtocolError.Timeout()
        assertEquals(resources.getString(L.errorBinaryProtocolTimeout), message(failure))
        assertSame(failure, mapper.present(failure).originalFailure)
        assertEquals(failure.sourceServiceFault, mapper.present(failure).content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::persistence store error dispatches to concrete mapping()")
    @Test fun persistence() = prove {
        assertEquals(L.errorPersistenceSaveFailed(resources, "store unavailable"),
            message(PersistenceStoreException(PersistenceStoreError.SaveFailed("store unavailable"))))
    }
    @OriginalCase("ErrorUserFacingMessageTests::sync coordinator error dispatches to concrete mapping()")
    @Test fun actualSyncAlreadyRunningUsesItsOriginalProducer() = prove {
        val failure = SyncCoordinatorError.AlreadySyncing()
        assertEquals(resources.getString(L.errorSyncCoordinatorAlreadySyncing), message(failure))
        assertSame(failure, mapper.present(failure).originalFailure)
        assertSame(SyncFault.AlreadySyncing, mapper.present(failure).content.sourceFault)
    }
    @OriginalCase("ErrorUserFacingMessageTests::device service error dispatches to concrete mapping()")
    @Test fun deviceConcreteDispatch() = prove {
        val cause = IllegalStateException("synthetic write cause")
        val failure = DeviceServiceException(DeviceServiceError.PersistenceFailed("write rejected"), cause)
        assertEquals(L.errorDeviceServicePersistenceFailed(resources, "write rejected"),
            message(failure))
        assertSame(cause, mapper.present(failure).originalFailure.cause)
    }
    @OriginalCase("ErrorUserFacingMessageTests::settings service error dispatches to concrete mapping()")
    @Test fun settingsConcreteDispatch() = prove {
        val fault = SettingsServiceError.VerificationFailed("915.5", "868.0")
        val failure = SettingsServiceException(fault)
        assertEquals(L.errorSettingsVerificationFailed(resources, "915.5", "868.0"),
            message(failure))
        assertEquals("915.5", fault.expected); assertEquals("868.0", fault.actual)
    }
    @NativeAdaptation("native-storage-family-without-Apple-status")
    @OriginalCase("ErrorUserFacingMessageTests::keychain error dispatches to concrete mapping()")
    @Test fun actualNativeStorageFaultDispatchesWithoutManufacturedAppleStatus() = prove {
        val failure = StorageFailure(StorageProblem.SecretAlreadyExists, StorageOperation.WRITE)
        assertEquals("Could not save local data.\nThe saved item already exists.", message(failure))
        assertEquals(NativeStorageErrorFamily.STORAGE, failure.nativeFamily)
        assertSame(failure, mapper.present(failure).originalFailure)
        assertEquals(UiStorageIssue(StorageOperation.WRITE, StorageProblem.SecretAlreadyExists), mapper.present(failure).content.storageIssue)
    }
    @OriginalCase("ErrorUserFacingMessageTests::key generation error dispatches to concrete mapping()")
    @Test fun keyGeneration() = prove {
        assertEquals(resources.getString(L.errorKeyGenerationReservedPrefix), message(KeyGenerationFailure.ReservedPrefix()))
    }
    @OriginalCase("ErrorUserFacingMessageTests::node config service error dispatches to concrete mapping()")
    @Test fun actualConfigFailureRetainsIndexAndHexLengthThroughItsProducer() = prove {
        val failure = NodeConfigServiceError.InvalidChannelSecret(2, 30)
        assertEquals(S.configImportErrorInvalidChannelSecret(resources, 2, 30), message(failure))
        assertEquals(NodeConfigServiceFault.InvalidChannelSecret(2, 30), mapper.present(failure).content.sourceFault)
        assertSame(failure, mapper.present(failure).originalFailure)
    }
    @NativeAdaptation("removed-billing")
    @OriginalCase("ErrorUserFacingMessageTests::store service error dispatches to concrete mapping()")
    @Test fun billingHasNoNativeFaultEntitlementOrUiBranch() = prove {
        assertFalse("StoreServiceError" in ErrorCopy.sourceFamilies)
        assertFalse(ErrorCopy.staticCopies.keys.any { it.sourceType == "StoreServiceError" })
    }
    @OriginalCase("ErrorUserFacingMessageTests::device GPS verification failed picks boolean variant key()")
    @Test fun gpsConcreteBooleanVariants() = prove {
        val expectedOn = SettingsServiceError.DeviceGPSVerificationFailed(true, false)
        val expectedOff = SettingsServiceError.DeviceGPSVerificationFailed(false, true)
        assertEquals(resources.getString(L.errorSettingsGpsNotSavedExpectedOn), message(SettingsServiceException(expectedOn)))
        assertEquals(resources.getString(L.errorSettingsGpsNotSavedExpectedOff), message(SettingsServiceException(expectedOff)))
        assertFalse(expectedOn.actualEnabled); assertTrue(expectedOff.actualEnabled)
    }
    @OriginalCase("ErrorUserFacingMessageTests::unmapped error falls back to localized description()")
    @Test fun unmapped() = prove { assertEquals("Something went wrong", message(IllegalStateException("Something went wrong"))) }
    @OriginalCase("ErrorUserFacingMessageTests::session error delegates to central mesh core mapping()")
    @Test fun allNineActualSessionWrappersDelegateToTheOriginalCentralMapping() = prove {
        val bindings = listOf(
            ContactServiceError.SessionError(MeshCoreException.Timeout()) to L.errorMeshCoreTimeout,
            MessageServiceException(MessageServiceError.SessionError(MeshCoreException.NotConnected())) to L.errorMeshCoreNotConnected,
            ChannelServiceError.SessionError(MeshCoreException.SessionNotStarted()) to L.errorMeshCoreSessionNotStarted,
            MessagePollingException(MessagePollingError.SessionError(MeshCoreException.BluetoothPoweredOff())) to L.errorMeshCoreBluetoothPoweredOff,
            AdvertisementError.SessionError(MeshCoreException.FeatureDisabled()) to L.errorMeshCoreFeatureDisabled,
            RemoteNodeError.SessionError(MeshCoreException.Timeout()) to L.errorMeshCoreTimeout,
            RoomServerError.SessionError(MeshCoreException.NotConnected()) to L.errorMeshCoreNotConnected,
            BinaryProtocolError.SessionError(MeshCoreException.SessionNotStarted()) to L.errorMeshCoreSessionNotStarted,
            SettingsServiceException(SettingsServiceError.SessionError(MeshCoreException.Timeout())) to L.errorMeshCoreTimeout,
        )
        assertEquals(9, bindings.size)
        for ((failure, expected) in bindings) {
            assertEquals(resources.getString(expected), message(failure))
            assertSame(failure, mapper.present(failure).originalFailure)
            assertNotNull(failure.cause)
            assertEquals(mapper.message(requireNotNull(failure.cause)).resolve(resources), message(failure))
        }
    }
    @OriginalCase("ErrorUserFacingMessageTests::persist failed recurses into underlying error()")
    @Test fun actualQueuePersistFailureRecursesThroughTheOriginalMessageFault() = prove {
        val underlying = MessageServiceException(MessageServiceError.NotConnected)
        val failure = ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(underlying))
        assertSame(underlying, failure.cause)
        assertEquals(L.errorChatSendQueuePersistFailed(resources, resources.getString(L.errorMessageServiceNotConnected)),
            message(failure))
    }
    @OriginalCase("ErrorUserFacingMessageTests::interpolated cases carry associated values()")
    @Test fun interpolation() = prove {
        assertEquals(L.errorMeshCoreParseError(resources, "bad frame"), message(MeshCoreException.ParseError("bad frame")))
        assertEquals(L.errorMeshCoreDataTooLarge(resources, 200, 184), message(MeshCoreException.DataTooLarge(184, 200)))
    }
    @OriginalCase("ErrorUserFacingMessageTests::device error maps known codes through protocol error()")
    @Test fun knownDeviceCodes() = prove {
        for (code in ErrorCode.entries) assertEquals(mapper.protocolError(code).resolve(resources),
            message(MeshCoreException.DeviceError(code.rawValue)))
    }
    @OriginalCase("ErrorUserFacingMessageTests::device error falls back for unknown code()")
    @Test fun unknownDeviceCode() = prove {
        assertEquals(L.errorDeviceUnknown(resources, 0x42), message(MeshCoreException.DeviceError(0x42u)))
    }
    @OriginalCase("ErrorUserFacingMessageTests::connection lost recurses into underlying error()")
    @Test fun lostConnection() = prove {
        assertEquals(L.errorMeshCoreConnectionLost(resources, resources.getString(L.errorMeshCoreBluetoothPoweredOff)),
            message(MeshCoreException.ConnectionLost(MeshCoreException.BluetoothPoweredOff())))
        assertEquals(resources.getString(L.errorMeshCoreConnectionLostNoDetail), message(MeshCoreException.ConnectionLost()))
    }
}
