// PortedFrom: MC1/Views/RemoteNodes/NodeSettingsViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.cli.CLIResponse
import com.meshcoreone.android.feature.remotenodes.cli.NodeSettingsResponseParser
import com.meshcoreone.android.feature.remotenodes.cli.RemoteCLICommandRewriter
import com.meshcoreone.android.feature.remotenodes.cli.RemoteOperationTimeouts
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Swift's `(UUID, String, Duration) async throws -> String` CLI send closures, keyed by entity. */
typealias RemoteCliSend = suspend (session: EntityKey, command: String, timeout: Duration) -> String

/**
 * Shared logic for the repeater and room settings screens: CLI transport, device info, radio,
 * identity, contact info, security, device actions and late-reply recovery (Swift
 * `NodeSettingsViewModel`). Like the `@MainActor` original it is confined to its caller's
 * (main) dispatcher; suspend functions may interleave at their awaits exactly as Swift's do.
 * Cancellation always propagates (Swift's catch-alls also swallowed `CancellationError`); in-flight
 * flags are reset first.
 */
class NodeSettingsStateHolder(
    private val clock: RemoteNodesClock,
    private val faults: RemoteNodeFaultClassifier,
) {
    private val _state = MutableStateFlow(NodeSettingsState())
    val state: StateFlow<NodeSettingsState> = _state.asStateFlow()

    private var sendCommand: RemoteCliSend? = null
    private var sendRawCommand: RemoteCliSend? = null

    /** Pre-fetches node info: repeaters use binary `requestOwnerInfo`, rooms leave it null. */
    var onPreFetchNodeInfo: (suspend () -> Unit)? = null

    // Late-reply bookkeeping; guarded by [lock] because admin services may invoke the CLI handler
    // off the main thread (Swift hopped to the main actor first).
    private val lock = Any()
    private val unansweredQueries = mutableSetOf<String>()
    private val recentResponses = ArrayDeque<String>()
    private val lateRecoveryAppliers = mutableMapOf<String, (CLIResponse) -> Unit>()

    internal fun update(transform: (NodeSettingsState) -> NodeSettingsState) = _state.update(transform)

    // MARK: - Configuration

    fun configure(session: RemoteNodeSessionDTO, sendCommand: RemoteCliSend, sendRawCommand: RemoteCliSend) {
        update { it.copy(session = session) }
        this.sendCommand = sendCommand
        this.sendRawCommand = sendRawCommand
        registerSharedLateRecovery()
    }

    /** Name / owner info from an external source (binary owner-info pre-fetch). */
    fun setNodeInfo(firmwareVersion: String?, name: String?, ownerInfo: String?) = update { current ->
        var next = current
        if (firmwareVersion != null) next = next.copy(firmwareVersion = firmwareVersion)
        if (name != null) next = next.copy(name = name, originalName = name)
        if (ownerInfo != null) next = next.copy(ownerInfo = ownerInfo, originalOwnerInfo = ownerInfo)
        next
    }

    fun cleanup() {
        sendCommand = null
        sendRawCommand = null
        onPreFetchNodeInfo = null
        synchronized(lock) {
            unansweredQueries.clear()
            recentResponses.clear()
            lateRecoveryAppliers.clear()
        }
    }

    // MARK: - Field edits (Swift bindings)

    fun setName(value: String?) = update { it.copy(name = value) }
    fun setLatitude(value: Double?) = update { it.copy(latitude = value) }
    fun setLongitude(value: Double?) = update { it.copy(longitude = value) }
    fun setLocationFromPicker(latitude: Double, longitude: Double) = update { it.copy(latitude = latitude, longitude = longitude) }
    fun setOwnerInfo(value: String?) = update { it.copy(ownerInfo = value) }
    fun setNewPassword(value: String) = update { it.copy(newPassword = value) }
    fun setConfirmPassword(value: String) = update { it.copy(confirmPassword = value) }
    fun setClockDrift(value: Double?) = update { it.copy(clockDrift = value) }
    fun dismissSuccessAlert() = update { it.copy(showSuccessAlert = false) }

    /** Radio edits mark the section modified, as the Swift pickers' `onChange` does. */
    fun setRadio(frequency: Double?, bandwidth: Double?, spreadingFactor: Long?, codingRate: Long?, modified: Boolean = true) =
        update {
            it.copy(
                frequency = frequency, bandwidth = bandwidth, spreadingFactor = spreadingFactor,
                codingRate = codingRate, radioSettingsModified = modified,
            )
        }

    fun setExpanded(section: NodeSettingsSection, expanded: Boolean) = update {
        when (section) {
            NodeSettingsSection.DEVICE_INFO -> it.copy(isDeviceInfoExpanded = expanded)
            NodeSettingsSection.RADIO -> it.copy(isRadioExpanded = expanded)
            NodeSettingsSection.IDENTITY -> it.copy(isIdentityExpanded = expanded)
            NodeSettingsSection.CONTACT_INFO -> it.copy(isContactInfoExpanded = expanded)
            NodeSettingsSection.SECURITY -> it.copy(isSecurityExpanded = expanded)
        }
    }

    // MARK: - CLI transport

    suspend fun sendAndWait(
        command: String,
        timeout: Duration = RemoteOperationTimeouts.defaultCLITimeout,
        rawMatching: Boolean = false,
    ): String {
        val session = _state.value.session
        val send = if (rawMatching) sendRawCommand else sendCommand
        if (session == null || send == null) throw NodeSettingsNoServiceException()
        try {
            val response = send(EntityKey(session.radioId, session.id), command, timeout)
            synchronized(lock) {
                rememberSeenResponse(response)
                unansweredQueries.remove(command)
            }
            return response
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (faults.isTimeout(error) && CLIResponse.isStructuredQuery(command)) synchronized(lock) { unansweredQueries.add(command) }
            throw error
        }
    }

    /** `error.userFacingMessage`: the feature's own no-service error resolves here, others in the UI. */
    internal fun userFacing(error: Throwable): RemoteNodesText =
        (error as? NodeSettingsNoServiceException)?.text ?: RemoteNodesText.Failure(error)

    // MARK: - Fetch

    suspend fun fetchDeviceInfo() {
        update { it.copy(isLoadingDeviceInfo = true, deviceInfoError = false) }
        try {
            if (_state.value.firmwareVersion == null) onPreFetchNodeInfo?.invoke()
            if (_state.value.firmwareVersion == null) {
                val version = query(CLIResponse.Query.VERSION) { update { it.copy(deviceInfoError = true) } }
                (version as? CLIResponse.Version)?.let { parsed -> update { it.copy(firmwareVersion = parsed.text) } }
            }
            val clockReply = query(CLIResponse.Query.CLOCK) { update { it.copy(deviceInfoError = true) } }
            (clockReply as? CLIResponse.DeviceTime)?.let { applyDeviceTime(it.text) }
        } finally {
            update { it.copy(isLoadingDeviceInfo = false) }
        }
    }

    suspend fun fetchIdentity() {
        update { it.copy(isLoadingIdentity = true, identityError = false) }
        var hadTimeout = false
        try {
            if (_state.value.originalName == null) onPreFetchNodeInfo?.invoke()
            if (_state.value.originalName == null) {
                (query(CLIResponse.Query.NAME) { hadTimeout = true } as? CLIResponse.Name)?.let { parsed ->
                    update { it.copy(name = parsed.text, originalName = parsed.text) }
                }
            }
            (query(CLIResponse.Query.LATITUDE) { hadTimeout = true } as? CLIResponse.Latitude)?.let { parsed ->
                update { it.copy(latitude = parsed.value, originalLatitude = parsed.value) }
            }
            (query(CLIResponse.Query.LONGITUDE) { hadTimeout = true } as? CLIResponse.Longitude)?.let { parsed ->
                update { it.copy(longitude = parsed.value, originalLongitude = parsed.value) }
            }
            if (hadTimeout) update { it.copy(identityError = true) }
        } finally {
            update { it.copy(isLoadingIdentity = false) }
        }
    }

    suspend fun fetchRadioSettings() {
        update { it.copy(isLoadingRadio = true, radioError = false) }
        var hadTimeout = false
        try {
            (query(CLIResponse.Query.RADIO) { hadTimeout = true } as? CLIResponse.Radio)?.let { radio ->
                update {
                    it.copy(
                        frequency = radio.frequency, bandwidth = radio.bandwidth,
                        spreadingFactor = radio.spreadingFactor, codingRate = radio.codingRate,
                    )
                }
            }
            if (hadTimeout) update { it.copy(radioError = true) }
        } finally {
            update { it.copy(isLoadingRadio = false) }
        }
    }

    suspend fun fetchContactInfo() {
        if (_state.value.originalOwnerInfo == null) onPreFetchNodeInfo?.invoke()
        if (_state.value.originalOwnerInfo != null) return
        update { it.copy(isLoadingContactInfo = true, contactInfoError = false) }
        try {
            val reply = query(CLIResponse.Query.OWNER_INFO) { update { it.copy(contactInfoError = true) } }
            (reply as? CLIResponse.OwnerInfo)?.let { parsed ->
                val display = NodeSettingsResponseParser.displayOwnerInfo(parsed.text)
                update { it.copy(ownerInfo = display, originalOwnerInfo = display) }
            }
        } finally {
            update { it.copy(isLoadingContactInfo = false) }
        }
    }

    /**
     * Sends a get-style query and parses it for that query; any failure is logged-and-skipped as in
     * Swift, with [onTimeout] run for `RemoteNodeError.timeout`. Returns null on failure.
     */
    internal suspend fun query(query: String, rawMatching: Boolean = false, onTimeout: () -> Unit): CLIResponse? = try {
        CLIResponse.parse(sendAndWait(query, rawMatching = rawMatching), query)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (faults.isTimeout(error)) onTimeout()
        null
    }

    internal fun applyDeviceTime(raw: String) {
        val text = NodeSettingsResponseParser.clockResponseText(raw) ?: return
        val drift = NodeSettingsResponseParser.clockDrift(text, clock.now)
        update { it.copy(deviceTimeUTC = text, clockDrift = drift) }
    }

    // MARK: - Success flash

    /**
     * Drops the section's applying flag, shows its success indicator for [SUCCESS_FLASH], then clears
     * it. Swift's `try? Task.sleep` ran on after cancellation; here the indicator is cleared and the
     * cancellation propagates.
     */
    suspend fun flashSuccess(setApplying: (Boolean) -> Unit, setSuccess: (Boolean) -> Unit) {
        setApplying(false)
        setSuccess(true)
        try {
            clock.sleep(SUCCESS_FLASH)
        } finally {
            setSuccess(false)
        }
    }

    // MARK: - Apply

    suspend fun applyRadioSettings() {
        val current = _state.value
        val frequency = current.frequency
        val bandwidth = current.bandwidth
        val spreadingFactor = current.spreadingFactor
        val codingRate = current.codingRate
        if (frequency == null || bandwidth == null || spreadingFactor == null || codingRate == null) {
            update { it.copy(errorMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRadioNotLoaded)) }
            return
        }
        if (!frequency.isFinite() || !bandwidth.isFinite() ||
            frequency * 1000 !in com.meshcoreone.android.core.protocol.command.PacketBuilder.FREQUENCY_RANGE_KHZ.let { it.first.toDouble()..it.last.toDouble() } ||
            bandwidth * 1000 !in com.meshcoreone.android.core.protocol.command.PacketBuilder.BANDWIDTH_RANGE_HZ.let { it.first.toDouble()..it.last.toDouble() } ||
            spreadingFactor !in 5L..12L || codingRate !in 5L..8L
        ) {
            update { it.copy(errorMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRadioApplyFailed)) }
            return
        }
        update { it.copy(isApplying = true, errorMessage = null) }
        val command = "set radio ${SwiftDoubleText.describe(frequency)},${SwiftDoubleText.describe(bandwidth)}," +
            "$spreadingFactor,$codingRate"
        runApplying {
            if (CLIResponse.parse(sendAndWait(command)) == CLIResponse.Ok) {
                update {
                    it.copy(
                        radioSettingsModified = false, showSuccessAlert = true,
                        successMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRadioAppliedSuccess),
                    )
                }
            } else {
                update { it.copy(errorMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRadioApplyFailed)) }
            }
        }
        update { it.copy(isApplying = false) }
    }

    suspend fun applyIdentitySettings() {
        val current = _state.value
        val validation = NodeSettingsValidation.validateIdentityFields(current.name, current.latitude, current.longitude)
        update { it.copy(nameError = validation.name, latitudeError = validation.latitude, longitudeError = validation.longitude) }
        if (validation.hasErrors) return
        update { it.copy(isApplying = true, errorMessage = null) }
        val flashed = runApplying {
            // Each step reads the live field values, as Swift's `if let name, ...` does after every await.
            var allSucceeded = true
            val name = _state.value.name
            if (name != null && name != _state.value.originalName) {
                if (isOk(sendAndWait("set name $name"))) update { it.copy(originalName = name) } else allSucceeded = false
            }
            val latitude = _state.value.latitude
            if (latitude != null && latitude != _state.value.originalLatitude) {
                if (isOk(sendAndWait("set lat ${SwiftDoubleText.describe(latitude)}"))) {
                    update { it.copy(originalLatitude = latitude) }
                } else allSucceeded = false
            }
            val longitude = _state.value.longitude
            if (longitude != null && longitude != _state.value.originalLongitude) {
                if (isOk(sendAndWait("set lon ${SwiftDoubleText.describe(longitude)}"))) {
                    update { it.copy(originalLongitude = longitude) }
                } else allSucceeded = false
            }
            if (allSucceeded) {
                flashSuccess({ v -> update { it.copy(isApplying = v) } }, { v -> update { it.copy(identityApplySuccess = v) } })
                true
            } else {
                update { it.copy(errorMessage = someSettingsFailed()) }
                false
            }
        }
        if (flashed != true) update { it.copy(isApplying = false) }
    }

    suspend fun applyContactInfoSettings() {
        update { it.copy(isApplying = true, errorMessage = null) }
        val ownerInfo = _state.value.ownerInfo
        val flashed = runApplying {
            val wire = NodeSettingsResponseParser.wireOwnerInfo(ownerInfo ?: "")
            if (isOk(sendAndWait("set owner.info $wire"))) {
                // Swift assigns the live `ownerInfo` after the await.
                update { it.copy(originalOwnerInfo = it.ownerInfo) }
                flashSuccess({ v -> update { it.copy(isApplying = v) } }, { v -> update { it.copy(contactInfoApplySuccess = v) } })
                true
            } else {
                update { it.copy(errorMessage = someSettingsFailed()) }
                false
            }
        }
        if (flashed != true) update { it.copy(isApplying = false) }
    }

    suspend fun changePassword() {
        val current = _state.value
        if (current.newPassword.isEmpty()) {
            update { it.copy(errorMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsPasswordEmpty)) }
            return
        }
        if (current.newPassword != current.confirmPassword) {
            update { it.copy(errorMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsPasswordMismatch)) }
            return
        }
        update { it.copy(isApplying = true, errorMessage = null) }
        val flashed = runApplying {
            val response = sendAndWait("password ${current.newPassword}", rawMatching = true)
            if (NodeSettingsResponseParser.isPasswordChangeSuccessful(response)) {
                update { it.copy(newPassword = "", confirmPassword = "") }
                flashSuccess({ v -> update { it.copy(isApplying = v) } }, { v -> update { it.copy(changePasswordSuccess = v) } })
                true
            } else {
                update { it.copy(errorMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsPasswordChangeFailed)) }
                false
            }
        }
        if (flashed != true) update { it.copy(isApplying = false) }
    }

    /** Firmware reboots without replying, so a timeout is the expected outcome. */
    suspend fun reboot() {
        if (_state.value.session == null) return
        update { it.copy(isRebooting = true, errorMessage = null) }
        val rebootSent = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRebootSent)
        try {
            sendAndWait("reboot", RemoteOperationTimeouts.fireAndForgetCLI)
            update { it.copy(successMessage = rebootSent, showSuccessAlert = true) }
        } catch (error: CancellationException) {
            update { it.copy(isRebooting = false) }
            throw error
        } catch (error: Exception) {
            if (faults.isTimeout(error)) {
                update { it.copy(successMessage = rebootSent, showSuccessAlert = true) }
            } else {
                update { it.copy(errorMessage = userFacing(error)) }
            }
        }
        update { it.copy(isRebooting = false) }
    }

    suspend fun forceAdvert() {
        update { it.copy(isSendingAdvert = true) }
        try {
            sendAndWait("advert")
            update {
                it.copy(
                    successMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsAdvertSent),
                    showSuccessAlert = true,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update { it.copy(errorMessage = userFacing(error)) }
        } finally {
            update { it.copy(isSendingAdvert = false) }
        }
    }

    suspend fun syncTime() {
        update { it.copy(isApplying = true, errorMessage = null) }
        runApplying {
            val command = RemoteCLICommandRewriter.rewrite(RemoteCLICommandRewriter.CLOCK_SYNC_COMMAND, clock.now)
            val response = sendAndWait(command)
            when (val outcome = NodeSettingsResponseParser.classifyClockSyncResponse(response)) {
                NodeSettingsResponseParser.ClockSyncOutcome.Synced -> {
                    if (NodeSettingsResponseParser.clockResponseText(response) != null) {
                        applyDeviceTime(response)
                    } else {
                        update { it.copy(clockDrift = null) }
                    }
                    update {
                        it.copy(
                            successMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsTimeSynced),
                            showSuccessAlert = true,
                        )
                    }
                }
                NodeSettingsResponseParser.ClockSyncOutcome.ClockAhead -> update {
                    it.copy(errorMessage = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsClockAheadError))
                }
                is NodeSettingsResponseParser.ClockSyncOutcome.Failed -> update {
                    it.copy(
                        errorMessage = if (outcome.message.isEmpty()) {
                            RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsSyncTimeFailed)
                        } else {
                            RemoteNodesText.Verbatim(outcome.message)
                        },
                    )
                }
                NodeSettingsResponseParser.ClockSyncOutcome.Unexpected -> update {
                    it.copy(errorMessage = RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_settings_unexpectedresponse, response))
                }
            }
        }
        update { it.copy(isApplying = false) }
    }

    /**
     * Runs an apply body with Swift's `do { } catch { errorMessage = error.userFacingMessage }`.
     * Returns the body's result, or null after a failure. Cancellation clears `isApplying` and
     * propagates.
     */
    internal suspend fun <T> runApplying(clearApplying: () -> Unit = { update { it.copy(isApplying = false) } }, body: suspend () -> T): T? =
        try {
            body()
        } catch (error: CancellationException) {
            clearApplying()
            throw error
        } catch (error: Exception) {
            update { it.copy(errorMessage = userFacing(error)) }
            null
        }

    internal fun isOk(response: String): Boolean = CLIResponse.parse(response) == CLIResponse.Ok

    internal fun someSettingsFailed(): RemoteNodesText =
        RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsSomeSettingsFailedToApply)

    // MARK: - Late reply recovery

    /** Registers a field applier for a recoverable query; repeater/room holders add their own. */
    internal fun registerLateRecovery(query: String, apply: (CLIResponse) -> Unit) {
        synchronized(lock) { lateRecoveryAppliers[query] = apply }
    }

    private fun rememberSeenResponse(response: String) {
        recentResponses.addLast(response)
        if (recentResponses.size > RECENT_RESPONSES_LIMIT) recentResponses.removeAt(0)
    }

    /**
     * Adopts an out-of-band CLI reply for the one unanswered query it can only belong to; ambiguous or
     * duplicate replies (a mesh duplicate of an answered command) are ignored.
     */
    fun handleCommonLateResponse(response: String) {
        val (apply, value) = synchronized(lock) {
            if (recentResponses.contains(response)) return
            val recovered = NodeSettingsResponseParser.recoveredResponse(response, unansweredQueries.toSet()) ?: return
            val apply = lateRecoveryAppliers[recovered.query] ?: return
            unansweredQueries.remove(recovered.query)
            rememberSeenResponse(response)
            apply to recovered.value
        }
        apply(value)
    }

    private fun registerSharedLateRecovery() {
        registerLateRecovery(CLIResponse.Query.RADIO) { value ->
            val radio = value as? CLIResponse.Radio ?: return@registerLateRecovery
            update {
                it.copy(
                    frequency = radio.frequency, bandwidth = radio.bandwidth, spreadingFactor = radio.spreadingFactor,
                    codingRate = radio.codingRate, radioError = false,
                )
            }
        }
        registerLateRecovery(CLIResponse.Query.LATITUDE) { value ->
            val latitude = (value as? CLIResponse.Latitude)?.value ?: return@registerLateRecovery
            update { it.copy(latitude = latitude, originalLatitude = latitude) }
            update { it.copy(identityError = !it.identitySectionComplete) }
        }
        registerLateRecovery(CLIResponse.Query.LONGITUDE) { value ->
            val longitude = (value as? CLIResponse.Longitude)?.value ?: return@registerLateRecovery
            update { it.copy(longitude = longitude, originalLongitude = longitude) }
            update { it.copy(identityError = !it.identitySectionComplete) }
        }
        registerLateRecovery(CLIResponse.Query.CLOCK) { value ->
            val time = (value as? CLIResponse.DeviceTime)?.text ?: return@registerLateRecovery
            applyDeviceTime(time)
            update { it.copy(deviceInfoError = it.firmwareVersion == null) }
        }
    }

    companion object {
        /** How long an Apply button shows its success state. */
        val SUCCESS_FLASH: Duration = 1500.milliseconds
        private const val RECENT_RESPONSES_LIMIT = 16
    }
}

/** Disclosure sections of the shared settings screen. */
enum class NodeSettingsSection { DEVICE_INFO, RADIO, IDENTITY, CONTACT_INFO, SECURITY }
