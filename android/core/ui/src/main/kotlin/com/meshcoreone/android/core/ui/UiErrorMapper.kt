// PortedFrom: MC1/Extensions/Errors/Error+UserFacingMessage.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: actual neutral/protocol/store faults and injected typed adapters, never class-name guesses.
package com.meshcoreone.android.core.ui

import android.content.res.Resources
import android.util.Log
import com.meshcoreone.android.core.contracts.domain.ConnectionIssue
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
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.datastore.KeyGenerationFailure
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem
import com.meshcoreone.android.core.l10n.R as L10nR
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.core.model.CommittedBackupPreferenceFailure
import com.meshcoreone.android.core.model.CommittedBackupReceipt
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiReceiveException
import com.meshcoreone.android.core.ui.R as UiR
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class UiRecovery {
    RETRY, CONNECT, ENABLE_BLUETOOTH, GRANT_BLUETOOTH, FIX_INPUT, REDUCE_PAYLOAD, RECOVER_STORAGE,
    UNLOCK_DEVICE, RESTORE_SECURE_DATA, COMPLETE_BACKUP_PREFERENCES, INSPECT_FAILURE,
}
data class UiErrorState(
    val id: UUID, val code: String, val message: UiText, val recovery: UiRecovery,
    val committedBackupReceipt: CommittedBackupReceipt? = null,
    val storageIssue: UiStorageIssue? = null,
    val sourceFault: SourceServiceFault? = null,
)
data class UiStorageIssue(val operation: StorageOperation, val problem: StorageProblem)
data class PresentedUiError(val originalFailure: Throwable, val content: UiErrorState)
data class UiErrorMapping(
    val message: UiText,
    val recovery: UiRecovery,
    val committedBackupReceipt: CommittedBackupReceipt? = null,
    val sourceFault: SourceServiceFault? = null,
)

fun interface UiErrorReporter { fun report(failure: Throwable) }

object AndroidUiErrorReporter : UiErrorReporter {
    override fun report(failure: Throwable) {
        val code = if (failure is StorageFailure) {
            "${failure.javaClass.name}:${failure.operation}:${failure.problem.javaClass.simpleName}"
        } else failure.javaClass.name
        Log.e("MeshCoreOne.UI", code)
    }
}

interface UiErrorAdapter {
    fun presentation(error: Throwable, underlyingCopy: (Throwable) -> UiText): UiErrorMapping?
}

class TypedUiErrorAdapter<T : Throwable>(
    private val type: Class<T>,
    private val render: (T, (Throwable) -> UiText) -> UiErrorMapping,
) : UiErrorAdapter {
    override fun presentation(error: Throwable, underlyingCopy: (Throwable) -> UiText): UiErrorMapping? =
        if (type.isInstance(error)) render(type.cast(error), underlyingCopy) else null
}

class UiErrorMapper(
    adapters: List<UiErrorAdapter> = emptyList(),
    private val reporter: UiErrorReporter = AndroidUiErrorReporter,
) {
    private val adapters = adapters.toList()

    fun present(error: Throwable, id: UUID = UUID.randomUUID()): PresentedUiError {
        val mapped = mapping(error, Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()))
        reporter.report(error)
        val code = if (error is StorageFailure) {
            "${error.javaClass.name}:${error.operation}:${error.problem.javaClass.simpleName}"
        } else error.javaClass.name
        val storage = when (error) {
            is StorageFailure -> error
            is CommittedBackupPreferenceFailure -> error.preferenceFailure as? StorageFailure
            else -> null
        }
        return PresentedUiError(error, UiErrorState(id, code, mapped.message, mapped.recovery, mapped.committedBackupReceipt,
            storage?.let { UiStorageIssue(it.operation, it.problem) }, mapped.sourceFault))
    }

    fun message(error: Throwable): UiText =
        message(error, Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()))

    private fun message(error: Throwable, visited: MutableSet<Throwable>): UiText = mapping(error, visited).message

    private fun mapping(error: Throwable, visited: MutableSet<Throwable>): UiErrorMapping {
        if (error is CancellationException) throw error
        if (!visited.add(error)) {
            reporter.report(error)
            return UiErrorMapping(UiText.Resource(L.commonErrorFailedToLoad), UiRecovery.INSPECT_FAILURE)
        }
        if (error is CommittedBackupPreferenceFailure) {
            val preferenceCopy = message(error.preferenceFailure, visited)
            return UiErrorMapping(generatedText("WP-304.Backup.RowsCommittedPreferencesIncomplete") {
                it.getString(UiR.string.ui_backup_preferences_incomplete) + "\n" + preferenceCopy.resolve(it)
            }, UiRecovery.COMPLETE_BACKUP_PREFERENCES, error.committedReceipt)
        }
        adapters.firstNotNullOfOrNull { adapter -> adapter.presentation(error) { message(it, visited) } }?.let { return it }
        if (error is StorageFailure) return storageErrorPresentation(error)
        if (error is SourceServiceFaultCarrier) {
            return sourceFaultPresentation(error.sourceServiceFault) { message(it, visited) }
        }
        val copy = when (error) {
            is MeshCoreException -> when (error) {
                is MeshCoreException.Timeout -> ErrorCopy.static("MeshCoreError", "timeout")
                is MeshCoreException.DeviceError -> deviceError(error.code)
                is MeshCoreException.ParseError -> ErrorCopy.meshParseError(error.reason)
                is MeshCoreException.NotConnected -> ErrorCopy.static("MeshCoreError", "notConnected")
                is MeshCoreException.CommandFailed -> ErrorCopy.meshCommandFailed(error.reason)
                is MeshCoreException.InvalidResponse -> ErrorCopy.meshInvalidResponse(error.expected, error.got)
                is MeshCoreException.ContactNotFound -> ErrorCopy.static("MeshCoreError", "contactNotFound")
                is MeshCoreException.DataTooLarge -> ErrorCopy.meshDataTooLarge(error.actualSize, error.maxSize)
                is MeshCoreException.SigningFailed -> ErrorCopy.meshSigningFailed(error.reason)
                is MeshCoreException.InvalidInput -> ErrorCopy.meshInvalidInput(error.reason)
                is MeshCoreException.Unknown -> ErrorCopy.meshUnknown(error.reason)
                is MeshCoreException.BluetoothUnavailable -> ErrorCopy.static("MeshCoreError", "bluetoothUnavailable")
                is MeshCoreException.BluetoothUnauthorized -> ErrorCopy.static("MeshCoreError", "bluetoothUnauthorized")
                is MeshCoreException.BluetoothPoweredOff -> ErrorCopy.static("MeshCoreError", "bluetoothPoweredOff")
                is MeshCoreException.ConnectionLost -> ErrorCopy.meshConnectionLost(error.cause?.let { message(it, visited) })
                is MeshCoreException.SessionNotStarted -> ErrorCopy.static("MeshCoreError", "sessionNotStarted")
                is MeshCoreException.FeatureDisabled -> ErrorCopy.static("MeshCoreError", "featureDisabled")
            }
            is MeshTransportError -> when (error) {
                MeshTransportError.NotConnected -> ErrorCopy.static("MeshCoreError", "notConnected")
                is MeshTransportError.ConnectionFailed -> ErrorCopy.wifiConnectionFailed(error.reason)
                is MeshTransportError.SendFailed -> ErrorCopy.wifiSendFailed(error.reason)
                MeshTransportError.DeviceNotFound -> ErrorCopy.static("BLEError", "deviceNotFound")
                MeshTransportError.ServiceNotFound, MeshTransportError.CharacteristicNotFound ->
                    ErrorCopy.static("BLEError", "characteristicNotFound")
            }
            is WiFiTransportException -> wifiError(error.error)
            is WiFiReceiveException -> ErrorCopy.meshConnectionLost(null)
            is PersistenceStoreException -> persistenceError(error.error)
            is MessageServiceException -> when (val fault = error.error) {
                MessageServiceError.NotConnected -> ErrorCopy.static("MessageServiceError", "notConnected")
                MessageServiceError.ContactNotFound -> ErrorCopy.static("MessageServiceError", "contactNotFound")
                MessageServiceError.ChannelNotFound -> ErrorCopy.static("MessageServiceError", "channelNotFound")
                is MessageServiceError.SendFailed -> ErrorCopy.static("MessageServiceError", "sendFailed")
                MessageServiceError.InvalidRecipient -> ErrorCopy.static("MessageServiceError", "invalidRecipient")
                MessageServiceError.AmbiguousRecipient -> UiText.Resource(L10nR.string.chats_ambiguous_direct_recipient)
                MessageServiceError.MessageTooLong -> ErrorCopy.static("MessageServiceError", "messageTooLong")
                is MessageServiceError.SessionError -> message(fault.underlying, visited)
            }
            is MessagePollingException -> when (val fault = error.error) {
                MessagePollingError.NotConnected -> ErrorCopy.static("MessagePollingError", "notConnected")
                MessagePollingError.PollingFailed -> ErrorCopy.static("MessagePollingError", "pollingFailed")
                is MessagePollingError.SessionError -> message(fault.underlying, visited)
            }
            is ChatSendQueueServiceException -> when (val fault = error.error) {
                ChatSendQueueServiceError.NotConnected -> ErrorCopy.static("ChatSendQueueServiceError", "notConnected")
                is ChatSendQueueServiceError.PersistFailed -> ErrorCopy.queuePersistFailed(message(fault.underlying, visited))
            }
            is DeviceServiceException -> when (val fault = error.error) {
                DeviceServiceError.DeviceNotFound -> ErrorCopy.static("DeviceServiceError", "deviceNotFound")
                is DeviceServiceError.PersistenceFailed -> ErrorCopy.devicePersistenceFailed(fault.reason)
            }
            is SettingsServiceException -> when (val fault = error.error) {
                SettingsServiceError.NotConnected -> ErrorCopy.static("SettingsServiceError", "notConnected")
                SettingsServiceError.SendFailed -> ErrorCopy.static("SettingsServiceError", "sendFailed")
                SettingsServiceError.InvalidResponse -> ErrorCopy.static("SettingsServiceError", "invalidResponse")
                is SettingsServiceError.SessionError -> message(fault.error, visited)
                is SettingsServiceError.VerificationFailed -> ErrorCopy.settingsVerificationFailed(fault.expected, fault.actual)
                is SettingsServiceError.DeviceGPSVerificationFailed -> ErrorCopy.gpsVerificationFailed(fault.expectedEnabled)
            }
            is AppBackupException -> backupError(error.error, visited)
            is KeyGenerationFailure -> when (error) {
                is KeyGenerationFailure.MaxAttemptsExceeded -> ErrorCopy.static("KeyGenerationError", "maxAttemptsExceeded")
                is KeyGenerationFailure.ReservedPrefix -> ErrorCopy.static("KeyGenerationError", "reservedPrefix")
                is KeyGenerationFailure.RandomGenerationFailed -> ErrorCopy.static("KeyGenerationError", "randomGenerationFailed")
                is KeyGenerationFailure.InvalidKey -> ErrorCopy.static("KeyGenerationError", "invalidKey")
                is KeyGenerationFailure.InvalidPrefix -> UiText.Resource(S.regenerateIdentityPrefixFooter)
            }
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }?.let(UiText::Verbatim)
                ?: UiText.Resource(L.commonErrorFailedToLoad)
        }
        return UiErrorMapping(copy, recovery(error))
    }

    fun deviceError(code: UByte): UiText = ErrorCode.fromRawValue(code)?.let(::protocolError) ?: ErrorCopy.unknownDevice(code)

    fun protocolError(error: ErrorCode): UiText = ErrorCopy.static("ProtocolError", when (error) {
        ErrorCode.UNSUPPORTED_COMMAND -> "unsupportedCommand"
        ErrorCode.NOT_FOUND -> "notFound"
        ErrorCode.TABLE_FULL -> "tableFull"
        ErrorCode.BAD_STATE -> "badState"
        ErrorCode.FILE_IO_ERROR -> "fileIOError"
        ErrorCode.ILLEGAL_ARGUMENT -> "illegalArgument"
    })

    fun wifiError(error: WiFiTransportError): UiText = when (error) {
        is WiFiTransportError.ConnectionFailed -> ErrorCopy.wifiConnectionFailed(error.reason)
        WiFiTransportError.ConnectionTimeout -> ErrorCopy.static("WiFiTransportError", "connectionTimeout")
        WiFiTransportError.NotConnected -> ErrorCopy.static("WiFiTransportError", "notConnected")
        is WiFiTransportError.SendFailed -> ErrorCopy.wifiSendFailed(error.reason)
        WiFiTransportError.SendTimeout -> ErrorCopy.static("WiFiTransportError", "sendTimeout")
        WiFiTransportError.InvalidHost -> ErrorCopy.static("WiFiTransportError", "invalidHost")
        WiFiTransportError.InvalidPort -> ErrorCopy.static("WiFiTransportError", "invalidPort")
        WiFiTransportError.NotConfigured -> ErrorCopy.static("WiFiTransportError", "notConfigured")
    }

    fun persistenceError(error: PersistenceStoreError): UiText = when (error) {
        PersistenceStoreError.DeviceNotFound -> ErrorCopy.static("PersistenceStoreError", "deviceNotFound")
        PersistenceStoreError.ContactNotFound -> ErrorCopy.static("PersistenceStoreError", "contactNotFound")
        PersistenceStoreError.MessageNotFound -> ErrorCopy.static("PersistenceStoreError", "messageNotFound")
        PersistenceStoreError.ChannelNotFound -> ErrorCopy.static("PersistenceStoreError", "channelNotFound")
        PersistenceStoreError.RemoteNodeSessionNotFound -> ErrorCopy.static("PersistenceStoreError", "remoteNodeSessionNotFound")
        is PersistenceStoreError.SaveFailed -> ErrorCopy.persistenceSaveFailed(error.reason)
        is PersistenceStoreError.FetchFailed -> ErrorCopy.persistenceFetchFailed(error.reason)
        PersistenceStoreError.InvalidData -> ErrorCopy.static("PersistenceStoreError", "invalidData")
    }

    private fun backupError(error: AppBackupError, visited: MutableSet<Throwable>): UiText = when (error) {
        AppBackupError.InvalidFile -> UiText.Resource(S.settingsBackupErrorInvalidFile)
        is AppBackupError.FileTooLarge -> formattedText(
            L10nR.string.l10n_app_settings_settings_backup_error_file_too_large,
            UiFormatArgument.Integer(error.actualBytes / 1_048_576), UiFormatArgument.Integer(error.maxBytes / 1_048_576),
        )
        is AppBackupError.DecompressedTooLarge -> formattedText(
            L10nR.string.l10n_app_settings_settings_backup_error_decompressed_too_large,
            UiFormatArgument.Integer(error.maxBytes / 1_048_576),
        )
        is AppBackupError.UnsupportedVersion -> formattedText(
            L10nR.string.l10n_app_settings_settings_backup_error_unsupported_version,
            UiFormatArgument.Integer(error.found), UiFormatArgument.Integer(error.maxSupported),
        )
        AppBackupError.CorruptedManifest -> UiText.Resource(S.settingsBackupErrorCorruptedManifest)
        is AppBackupError.ExportFailed -> {
            val underlying = message(error.underlying, visited)
            generatedText("Settings.Backup.Error.exportFailed") { S.settingsBackupErrorExportFailed(it, underlying.resolve(it)) }
        }
        is AppBackupError.ImportFailed -> {
            val underlying = message(error.underlying, visited)
            generatedText("Settings.Backup.Error.importFailed") { S.settingsBackupErrorImportFailed(it, underlying.resolve(it)) }
        }
    }

    fun connectionIssue(issue: ConnectionIssue): UiText = when (issue) {
        is ConnectionIssue.PermissionDenied -> when (issue.capability) {
            com.meshcoreone.android.core.contracts.domain.Capability.BLUETOOTH_CONNECT,
            com.meshcoreone.android.core.contracts.domain.Capability.BLUETOOTH_SCAN ->
                ErrorCopy.static("BLEError", "bluetoothUnauthorized")
            else -> UiText.Resource(L.commonErrorFailedToLoad)
        }
        is ConnectionIssue.Unsupported -> UiText.Resource(L.commonErrorFailedToLoad)
        ConnectionIssue.BluetoothOff -> ErrorCopy.static("BLEError", "bluetoothPoweredOff")
        is ConnectionIssue.Transport -> message(issue.error)
        is ConnectionIssue.Protocol -> message(issue.error)
        is ConnectionIssue.Storage -> message(issue.error)
        is ConnectionIssue.Lifecycle -> message(issue.cause)
    }

    private fun recovery(error: Throwable): UiRecovery = when (error) {
        is SettingsServiceException -> if (error.isRetryable) UiRecovery.RETRY else UiRecovery.INSPECT_FAILURE
        is MeshCoreException.NotConnected -> UiRecovery.CONNECT
        is MeshCoreException.BluetoothPoweredOff -> UiRecovery.ENABLE_BLUETOOTH
        is MeshCoreException.BluetoothUnauthorized -> UiRecovery.GRANT_BLUETOOTH
        is MeshCoreException.InvalidInput, is KeyGenerationFailure.InvalidKey,
        is KeyGenerationFailure.InvalidPrefix, is KeyGenerationFailure.ReservedPrefix -> UiRecovery.FIX_INPUT
        is MeshCoreException.DataTooLarge -> UiRecovery.REDUCE_PAYLOAD
        is StorageFailure -> UiRecovery.RECOVER_STORAGE
        is WiFiTransportException -> when (error.error) {
            WiFiTransportError.InvalidHost, WiFiTransportError.InvalidPort,
            WiFiTransportError.NotConfigured -> UiRecovery.FIX_INPUT
            WiFiTransportError.NotConnected -> UiRecovery.CONNECT
            is WiFiTransportError.ConnectionFailed, WiFiTransportError.ConnectionTimeout,
            is WiFiTransportError.SendFailed, WiFiTransportError.SendTimeout -> UiRecovery.RETRY
        }
        is WiFiReceiveException -> UiRecovery.CONNECT
        is MeshCoreException.Timeout -> UiRecovery.RETRY
        else -> UiRecovery.INSPECT_FAILURE
    }
}
