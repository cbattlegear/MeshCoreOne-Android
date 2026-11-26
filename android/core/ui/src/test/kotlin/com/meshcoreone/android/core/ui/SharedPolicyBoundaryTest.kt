// AndroidOnly: WP-304 Source validation/identity/cancellation/consumer boundaries beyond the original families.
package com.meshcoreone.android.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
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
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiReceiveException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS")
class SharedPolicyBoundaryTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test fun regionAsciiByteLimitWhitespacePrivateAndDuplicateRulesMatchTheSource() {
        assertEquals(RegionValidationError.Empty, RegionNameValidator.validate(" \t ", emptyList()))
        assertEquals(RegionValidationError.InvalidCharacters, RegionNameValidator.validate("region\n", emptyList()))
        for (separator in listOf('\u2028', '\u2029')) {
            assertEquals(RegionValidationError.InvalidCharacters, RegionNameValidator.validate("region$separator", emptyList()))
            assertEquals("region$separator", RegionNameValidator.normalized("region$separator"))
        }
        assertEquals("region", RegionNameValidator.normalized("\u00a0\tregion\u2003"))
        assertEquals(RegionValidationError.InvalidCharacters, RegionNameValidator.validate("\u4e2d\u6587", emptyList()))
        assertEquals(RegionValidationError.InvalidCharacters, RegionNameValidator.validate("\$private", emptyList()))
        assertTrue("\$private".isPrivateRegion)
        assertFalse("private\$".isPrivateRegion)
        val maximum = ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES
        assertNull(RegionNameValidator.validate("A".repeat(maximum), emptyList()))
        assertEquals(RegionValidationError.TooLong(maximum), RegionNameValidator.validate("A".repeat(maximum + 1), emptyList()))
        assertEquals(RegionValidationError.Duplicate, RegionNameValidator.validate(" region ", listOf("region")))
        assertNull(RegionNameValidator.validate("Region", listOf("region")))
        assertEquals("region", RegionNameValidator.normalized(" region "))
        assertNull(regionValidationCopy(RegionValidationError.Empty))
        assertEquals(resources.getString(com.meshcoreone.android.core.l10n.generated.AppChatsStrings.chatsChannelInfoRegionInvalidName),
            assertNotNull(regionValidationCopy(RegionValidationError.InvalidCharacters)).resolve(resources))
    }

    @Test fun regionSearchUsesNumericLocaleOrderingAndDiscoverySelectionIsStableNotIndexBased() {
        assertEquals(listOf("region2", "region10"), filteredRegions(listOf("region10", "region2"), "", java.util.Locale.US))
        assertEquals(listOf("home", "Home"), filteredRegions(listOf("Home", "home"), "", java.util.Locale.US))
        assertEquals(filteredRegions(listOf("Home", "home"), "", java.util.Locale.US),
            filteredRegions(listOf("home", "Home"), "", java.util.Locale.US))
        assertEquals(listOf("M\u00fcnchen"), filteredRegions(listOf("M\u00fcnchen", "Berlin"), "mun", java.util.Locale.US))
        val discovery = RegionDiscoveryState.from(listOf("z", "a", "z"))
        assertEquals(listOf("a", "z", "z"), discovery.sortedRegions)
        assertEquals(listOf("a", "z"), discovery.selectedInDisplayOrder)
        assertEquals(listOf("a"), discovery.toggled("z").selectedInDisplayOrder)
        assertTrue(RegionManagementState(com.meshcoreone.android.core.model.SnapshotList(List(15) { "$it" })).searchEnabled)
    }

    @Test fun discoveredFrameRetainsUnknownRawTypePathBytesAndDoesNotReplaceRadioIdentity() {
        val radio = RadioId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
        val source = DiscoveredNodeDTO(UUID.randomUUID(), radio, Bytes(ByteArray(32) { 0x42 }),
            "Node", 255u, Instant.EPOCH, 10u, 1.0, 2.0, 1u, Bytes(byteArrayOf(0x7f)), 3, 9u)
        val frame: ContactFrame = source.makeContactFrame(Clock.fixed(Instant.ofEpochSecond(99), ZoneOffset.UTC))
        assertEquals(255u.toUByte(), frame.typeRawValue); assertEquals(0u.toUByte(), frame.flags)
        assertEquals(source.publicKey, frame.publicKey); assertEquals(source.outPath, frame.outPath)
        assertEquals(99u, frame.lastModified); assertEquals(radio, source.radioId)
        assertFailsWith<IllegalArgumentException> { source.makeContactFrame(Clock.fixed(Instant.ofEpochSecond(-1), ZoneOffset.UTC)) }
    }

    @Test fun emojiGraphemesCombiningNamesAndRootCaseMappingDoNotSplitIdentityText() {
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDCBB", avatarInitials("Ada \uD83D\uDC69\u200D\uD83D\uDCBB Lovelace"))
        assertEquals("AL", avatarInitials("Ada Lovelace"))
        assertEquals("E\u0301M", avatarInitials("e\u0301 mesh"))
        assertEquals("\u4e2d", avatarInitials("\u4e2d\u6587"))
        assertEquals("1", avatarInitials("123"))
        assertEquals("", avatarInitials(""))
        assertEquals("0A", 10u.toUByte().hexString); assertEquals("FF", 255u.toUByte().hexString)
    }

    @Test fun initialExpandedLoadingIsRequestedOnceOutsideCompositionAndRadioActionsRequireReady() {
        var loads = 0
        val policy = InitialSectionLoad({ ExpandableSectionState(true, false, false, false) }, { loads++ })
        policy.onEnter(); policy.onEnter()
        assertEquals(1, loads)
        for (state in DeviceConnectionState.entries) assertEquals(state == DeviceConnectionState.READY, radioActionEnabled(state))
        assertFalse(radioActionEnabled(DeviceConnectionState.READY, true))
        assertEquals(Duration.ofSeconds(7), RadioCommandTimeout.delete)
        assertFalse(shouldRevealNavigationTitle(150.0, 150.0))
        assertTrue(shouldRevealNavigationTitle(150.1, 150.0))
    }

    @Test fun typedFaultsAndOriginalCausesSurvivePresentationWithoutCancellationOrUnknownRetryFallbacks() {
        val reported = mutableListOf<Throwable>()
        val mapper = UiErrorMapper(reporter = UiErrorReporter(reported::add))
        val original = MeshCoreException.DataTooLarge(184, 200)
        val presented = mapper.present(original)
        assertSame(original, presented.originalFailure); assertEquals(UiRecovery.REDUCE_PAYLOAD, presented.content.recovery)
        assertTrue(presented.content.message.resolve(resources).contains("200"))
        assertTrue(presented.content.message.resolve(resources).contains("184"))
        assertSame(original, reported.single())
        val cancellation = CancellationException("synthetic cancellation")
        assertFailsWith<CancellationException> { mapper.present(cancellation) }
        assertEquals(1, reported.size)
        val storage = StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
        assertEquals(UiRecovery.RESTORE_SECURE_DATA, mapper.present(storage).content.recovery)
        assertSame(storage, reported.last())
        val unknown = IllegalStateException("unknown synthetic failure")
        assertEquals(UiRecovery.INSPECT_FAILURE, mapper.present(unknown).content.recovery)
    }

    @Test fun injectedTypedAdaptersUseRealThrowableTypesAndCyclicCausesRemainBounded() {
        val a = Exception("a")
        val b = Exception("b")
        a.initCause(b); b.initCause(a)
        val adapter = TypedUiErrorAdapter(Exception::class.java) { error, underlying ->
            UiErrorMapping(error.cause?.let { ErrorCopy.queuePersistFailed(underlying(it)) }
                ?: UiText.Verbatim(requireNotNull(error.message)), UiRecovery.INSPECT_FAILURE)
        }
        val mapper = UiErrorMapper(listOf(adapter), UiErrorReporter {})
        val text = mapper.message(a).resolve(resources)
        assertTrue(text.contains(resources.getString(AppLocalizableStrings.commonErrorFailedToLoad)))
    }

    @Test fun allSevenRealBackupFaultsRetainLongPayloadsAndCauseRecursionWithoutClaimingRestoreEvidence() {
        val mapper = UiErrorMapper(reporter = UiErrorReporter {})
        val errors = listOf(
            AppBackupError.InvalidFile, AppBackupError.FileTooLarge(Long.MAX_VALUE, 50L * 1_048_576),
            AppBackupError.DecompressedTooLarge(512L * 1_048_576),
            AppBackupError.UnsupportedVersion(Long.MAX_VALUE, 1), AppBackupError.CorruptedManifest,
            AppBackupError.ExportFailed(MeshCoreException.Timeout()), AppBackupError.ImportFailed(MeshCoreException.NotConnected()),
        )
        for (error in errors) {
            val failure = AppBackupException(error)
            assertTrue(mapper.message(failure).resolve(resources).isNotEmpty())
            assertSame(failure, mapper.present(failure).originalFailure)
        }
        assertTrue(mapper.message(AppBackupException(AppBackupError.UnsupportedVersion(Long.MAX_VALUE, 1)))
            .resolve(resources).contains(Long.MAX_VALUE.toString()))
    }

    @Test fun wifiInputAndDisconnectedFailuresUseActionableRecoveryAndReceiveFailureHidesProtocolDiagnostics() {
        val mapper = UiErrorMapper(reporter = UiErrorReporter {})
        val cases = listOf(
            WiFiTransportError.InvalidHost to UiRecovery.FIX_INPUT,
            WiFiTransportError.InvalidPort to UiRecovery.FIX_INPUT,
            WiFiTransportError.NotConfigured to UiRecovery.FIX_INPUT,
            WiFiTransportError.NotConnected to UiRecovery.CONNECT,
            WiFiTransportError.ConnectionTimeout to UiRecovery.RETRY,
            WiFiTransportError.SendTimeout to UiRecovery.RETRY,
            WiFiTransportError.ConnectionFailed("source reason") to UiRecovery.RETRY,
            WiFiTransportError.SendFailed("source reason") to UiRecovery.RETRY,
        )
        for ((fault, recovery) in cases) {
            val failure = WiFiTransportException(fault)
            val presented = mapper.present(failure)
            assertSame(failure, presented.originalFailure)
            assertEquals(recovery, presented.content.recovery)
            assertEquals(mapper.wifiError(fault).resolve(resources), presented.content.message.resolve(resources))
        }
        val cause = IOException("native-private-diagnostic")
        val receive = WiFiReceiveException(cause)
        val presented = mapper.present(receive)
        assertSame(receive, presented.originalFailure)
        assertSame(cause, presented.originalFailure.cause)
        assertEquals(UiRecovery.CONNECT, presented.content.recovery)
        assertEquals(resources.getString(AppLocalizableStrings.errorMeshCoreConnectionLostNoDetail),
            presented.content.message.resolve(resources))
        assertFalse(presented.content.message.resolve(resources).contains("wifi.receive_failed"))
        assertFalse(presented.content.message.resolve(resources).contains("native-private-diagnostic"))
        assertEquals("Connection to device was lost.", MeshCoreException.ConnectionLost(Exception()).sourceEnglishDescription())
    }

    @Test fun nativeBackupSizePresentationKeepsTheFrozenIntegerMibPolicyRatherThanInventingDecimalCopy() {
        val copy = UiErrorMapper(reporter = UiErrorReporter {})
            .message(AppBackupException(AppBackupError.FileTooLarge(10_500_000, 10 * 1_048_576L)))
        val formatted = assertIs<UiText.Format>(copy)
        assertEquals(2, formatted.arguments.size)
        assertEquals(10L, assertIs<UiFormatArgument.Integer>(formatted.arguments[0]).value)
        assertEquals(10L, assertIs<UiFormatArgument.Integer>(formatted.arguments[1]).value)
    }

    @Test fun actualMessagingFaultFamiliesKeepTheirPayloadsCausesAndNestedCentralDelegation() {
        val mapper = UiErrorMapper(reporter = UiErrorReporter {})
        val protocol = MeshCoreException.Timeout()
        val messages = listOf(
            MessageServiceError.NotConnected to AppLocalizableStrings.errorMessageServiceNotConnected,
            MessageServiceError.ContactNotFound to AppLocalizableStrings.errorMessageServiceContactNotFound,
            MessageServiceError.ChannelNotFound to AppLocalizableStrings.errorMessageServiceChannelNotFound,
            MessageServiceError.SendFailed("private-message-reason") to AppLocalizableStrings.errorMessageServiceSendFailed,
            MessageServiceError.InvalidRecipient to AppLocalizableStrings.errorMessageServiceInvalidRecipient,
            MessageServiceError.MessageTooLong to AppLocalizableStrings.errorMessageServiceMessageTooLong,
            MessageServiceError.SessionError(protocol) to AppLocalizableStrings.errorMeshCoreTimeout,
        )
        for ((fault, resource) in messages) {
            val cause = if (fault is MessageServiceError.SessionError) protocol else IOException("private-native-cause")
            val failure = MessageServiceException(fault, cause)
            val presented = mapper.present(failure)
            assertSame(fault, failure.error)
            assertSame(failure, presented.originalFailure)
            assertSame(cause, presented.originalFailure.cause)
            assertEquals(resources.getString(resource), presented.content.message.resolve(resources))
            assertFalse(presented.content.message.resolve(resources).contains("private-message-reason"))
            assertFalse(presented.content.message.resolve(resources).contains("private-native-cause"))
            if (fault is MessageServiceError.SendFailed) {
                assertEquals("Send failed: private-message-reason", failure.sourceEnglishDescription())
            }
        }
        for ((fault, resource) in listOf(
            MessagePollingError.NotConnected to AppLocalizableStrings.errorMessagePollingNotConnected,
            MessagePollingError.PollingFailed to AppLocalizableStrings.errorMessagePollingPollingFailed,
            MessagePollingError.SessionError(protocol) to AppLocalizableStrings.errorMeshCoreTimeout,
        )) {
            val failure = MessagePollingException(fault)
            assertEquals(resources.getString(resource), mapper.message(failure).resolve(resources))
            if (fault is MessagePollingError.SessionError) assertSame(protocol, failure.cause)
        }
        val message = MessageServiceException(MessageServiceError.SessionError(protocol))
        val queue = ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(message))
        assertSame(message, queue.cause)
        assertSame(protocol, message.cause)
        assertEquals(AppLocalizableStrings.errorChatSendQueuePersistFailed(resources,
            resources.getString(AppLocalizableStrings.errorMeshCoreTimeout)), mapper.message(queue).resolve(resources))
        val storage = StorageFailure(StorageProblem.DeviceLocked, StorageOperation.WRITE)
        val failedPersistence = ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(storage))
        assertSame(storage, failedPersistence.cause)
        assertEquals(AppLocalizableStrings.errorChatSendQueuePersistFailed(resources,
            mapper.message(storage).resolve(resources)), mapper.message(failedPersistence).resolve(resources))
        val cancelled = ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(CancellationException("cancelled write")))
        assertFailsWith<CancellationException> { mapper.present(cancelled) }
    }

    @Test fun allEightFrozenDeviceSettingsCasesDispatchAndOnlySourceEligibleFaultsRecommendRetry() {
        val mapper = UiErrorMapper(reporter = UiErrorReporter {})
        val deviceFailures = listOf(
            DeviceServiceException(DeviceServiceError.DeviceNotFound),
            DeviceServiceException(DeviceServiceError.PersistenceFailed("native synthetic reason")),
        )
        val settings = listOf(
            SettingsServiceError.NotConnected, SettingsServiceError.SendFailed,
            SettingsServiceError.InvalidResponse, SettingsServiceError.SessionError(MeshCoreException.Timeout()),
            SettingsServiceError.VerificationFailed("expected", "actual"),
            SettingsServiceError.DeviceGPSVerificationFailed(true, false),
        )
        for (failure in deviceFailures) {
            assertSame(failure, mapper.present(failure).originalFailure)
            assertTrue(mapper.message(failure).resolve(resources).isNotEmpty())
            assertEquals(UiRecovery.INSPECT_FAILURE, mapper.present(failure).content.recovery)
        }
        for ((index, fault) in settings.withIndex()) {
            val failure = SettingsServiceException(fault)
            assertSame(failure, mapper.present(failure).originalFailure)
            assertTrue(mapper.message(failure).resolve(resources).isNotEmpty())
            assertEquals(index in setOf(0, 1, 3), failure.isRetryable)
            assertEquals(if (failure.isRetryable) UiRecovery.RETRY else UiRecovery.INSPECT_FAILURE,
                mapper.present(failure).content.recovery)
        }
        val radioRejected = SettingsServiceException(SettingsServiceError.RadioCLIRejected("Error: unsupported"))
        assertEquals("Error: unsupported", mapper.message(radioRejected).resolve(resources))
        assertFalse(radioRejected.isRetryable)
        val noRetry = SettingsServiceException(SettingsServiceError.SessionError(MeshCoreException.FeatureDisabled()))
        assertFalse(noRetry.isRetryable)
        assertEquals(UiRecovery.INSPECT_FAILURE, mapper.present(noRetry).content.recovery)
        val wrappedCancellation = SettingsServiceException(SettingsServiceError.SessionError(
            MeshCoreException.ConnectionLost(CancellationException("synthetic cancelled operation"))))
        assertFailsWith<CancellationException> { mapper.message(wrappedCancellation) }
    }
}
