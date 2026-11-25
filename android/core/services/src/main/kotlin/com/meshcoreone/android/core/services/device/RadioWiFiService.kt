// AndroidOnly: WP-211 Guarded companion Wi-Fi commands compared with current upstream.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.contracts.domain.RadioWiFiState
import com.meshcoreone.android.core.contracts.domain.RadioWiFiResetObservation
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.session.ConfigurationSessionOps
import com.meshcoreone.android.core.protocol.session.SessionClock
import java.util.UUID
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RadioWiFiCapability {
    data class Supported(val probe: RadioWiFiProbe) : RadioWiFiCapability
    data object Unavailable : RadioWiFiCapability
    data object Busy : RadioWiFiCapability
}

data class RadioWiFiProbe internal constructor(val enabled: Boolean, val contextId: UUID, val revision: ULong)

data class RadioWiFiObservation(
    val contextId: UUID,
    val radioId: RadioId,
    val revision: ULong,
    val enabled: Boolean,
    val networkName: String?,
    val networkNameWasRead: Boolean,
    val associated: Boolean?,
    val ipAddress: String?,
    val ipAddressWasRead: Boolean,
    val error: SettingsServiceException?,
)

class RadioWiFiCredentialBaseline internal constructor(
    val contextId: UUID,
    val radioId: RadioId,
    val editorId: UUID,
    val revision: ULong,
    val ssid: String,
    val password: String,
    internal val readId: UUID,
) {
    override fun toString(): String = "RadioWiFiCredentialBaseline(<redacted>)"
}

class RadioWiFiMutationReceipt(
    val contextId: UUID,
    val ssidAccepted: Boolean,
    val passwordAccepted: Boolean,
    val revision: ULong?,
    val uncertain: Boolean,
    val error: Exception?,
    val updatedBaseline: RadioWiFiCredentialBaseline?,
) {
    override fun toString(): String =
        "RadioWiFiMutationReceipt(ssidAccepted=$ssidAccepted, passwordAccepted=$passwordAccepted, uncertain=$uncertain)"
}

data class RadioWiFiCommandReceipt(
    val contextId: UUID,
    val revision: ULong?,
    val outcome: RadioWiFiState.MutationOutcome,
    val error: Exception?,
)

class RadioWiFiOwnershipLost : Exception("The Wi-Fi editor or radio session is no longer current")

/**
 * One connection's Wi-Fi operations. [state] must be the process-owned instance for this radio,
 * shared with previous and subsequent connection generations.
 */
class RadioWiFiService(
    private val session: ConfigurationSessionOps,
    private val context: DeviceSettingsContext,
    val state: RadioWiFiState,
    private val clock: SessionClock,
    val contextId: UUID = UUID.randomUUID(),
) {
    private val operationLock = Mutex()
    private val editorLock = Any()
    private var editorId: UUID? = null
    private var editorRead: Pair<UUID, ULong>? = null
    private var retired = false
    private val logger = Logger.getLogger(RadioWiFiService::class.java.name)

    init {
        require(state.radioId == context.token.radioId) { "Wi-Fi state must belong to the connected radio" }
        context.onClose {
            synchronized(editorLock) {
                retired = true
                editorId = null
                editorRead = null
            }
        }
    }

    private suspend fun <T> operation(action: suspend () -> T): T = context.operation {
        operationLock.withLock {
            context.requireCurrent()
            action()
        }
    }

    private fun readIsCurrent(revision: ULong): Boolean =
        synchronized(editorLock) { !retired } && context.isCurrent && state.stableRevision() == revision

    private suspend fun cli(command: String, tracked: Boolean = false): String {
        context.requireCurrent()
        val response = try {
            if (tracked) session.runTrackedCorrelatedCliCommand(command) else session.runCorrelatedCliCommand(command)
        } catch (failure: MeshCoreException) {
            throw SettingsServiceException(SettingsServiceError.SessionError(failure))
        } catch (failure: MeshTransportError) {
            throw SettingsServiceException(SettingsServiceError.SendFailed, failure)
        }
        context.requireCurrent()
        return response
    }

    private suspend fun value(key: String): String? = when (val reply = RadioCLIReply.parse(cli("get $key"))) {
        is RadioCLIReply.Value -> reply.text
        RadioCLIReply.UnknownCommand -> null
        is RadioCLIReply.Error -> throw SettingsServiceException(SettingsServiceError.RadioCLIRejected(reply.text))
        is RadioCLIReply.Other ->
            if (reply.text == "??: $key") null else throw SettingsServiceException(SettingsServiceError.InvalidResponse)
        is RadioCLIReply.Ok -> throw SettingsServiceException(SettingsServiceError.InvalidResponse)
    }

    private suspend fun write(command: String) {
        when (val reply = RadioCLIReply.parse(cli(command, tracked = true))) {
            is RadioCLIReply.Ok, is RadioCLIReply.Value -> Unit
            is RadioCLIReply.Error -> throw SettingsServiceException(SettingsServiceError.RadioCLIRejected(reply.text))
            RadioCLIReply.UnknownCommand ->
                throw SettingsServiceException(SettingsServiceError.RadioCLIRejected("Unknown command"))
            is RadioCLIReply.Other -> throw SettingsServiceException(SettingsServiceError.InvalidResponse)
        }
    }

    private suspend fun resetObservation(): RadioWiFiResetObservation? {
        val started = clock.now
        val stats = try {
            session.getStatsCore()
        } catch (failure: MeshCoreException) {
            logger.warning("Cannot obtain radio uptime for Wi-Fi reboot confirmation: ${failure.javaClass.simpleName}")
            return null
        } catch (failure: MeshTransportError) {
            logger.warning("Cannot obtain radio uptime for Wi-Fi reboot confirmation: ${failure.javaClass.simpleName}")
            return null
        }
        return RadioWiFiResetObservation(stats.uptimeSeconds, started, clock.now)
    }

    suspend fun reboot(expectedRevision: ULong? = null) = operation {
        val observation = if (state.isRebootPending) resetObservation() else null
        context.requireCurrent()
        val candidate = state.prepareExpectedReboot(contextId, expectedRevision, observation)
        if (expectedRevision != null && candidate == null) throw RadioWiFiOwnershipLost()
        var submitted = false
        try {
            session.reboot()
            submitted = true
        } catch (failure: MeshCoreException) {
            throw SettingsServiceException(SettingsServiceError.SessionError(failure))
        } catch (failure: MeshTransportError) {
            throw SettingsServiceException(SettingsServiceError.SendFailed, failure)
        } finally {
            candidate?.let { state.completeRebootSubmission(it, succeeded = submitted) }
        }
    }

    suspend fun confirmExpectedReboot(
        candidate: UUID,
        whileOwnerIsCurrent: ((() -> Boolean) -> Boolean),
    ): Boolean = operation {
        val revision = state.stableRevision() ?: return@operation false
        val observation = resetObservation() ?: return@operation false
        if (!readIsCurrent(revision)) return@operation false
        whileOwnerIsCurrent {
            state.clearExpectedReboot(candidate, contextId, revision, observation)
        }
    }

    suspend fun probe(): RadioWiFiCapability = operation {
        val revision = state.stableRevision() ?: return@operation RadioWiFiCapability.Busy
        if (!readIsCurrent(revision)) return@operation RadioWiFiCapability.Busy
        val enabled = value("wifi.enabled")
        if (!readIsCurrent(revision)) return@operation RadioWiFiCapability.Busy
        if (enabled == null) return@operation RadioWiFiCapability.Unavailable
        val flag = when (enabled.trim()) {
            "1" -> true
            "0" -> false
            else -> throw SettingsServiceException(SettingsServiceError.InvalidResponse)
        }
        RadioWiFiCapability.Supported(RadioWiFiProbe(flag, contextId, revision))
    }

    suspend fun observe(probe: RadioWiFiProbe): RadioWiFiObservation? = operation {
        if (probe.contextId != contextId || !readIsCurrent(probe.revision)) return@operation null
        var ssid: String? = null
        var ssidRead = false
        var connected: Boolean? = null
        var ip: String? = null
        var ipRead = false
        var error: SettingsServiceException? = null
        try {
            val name = value("wifi.ssid") ?: throw SettingsServiceException(SettingsServiceError.InvalidResponse)
            if (!readIsCurrent(probe.revision)) return@operation null
            ssid = name.takeUnless { it.isEmpty() || it == RadioCLIReply.NOT_SET }
            ssidRead = true
            val status = value("wifi.status") ?: throw SettingsServiceException(SettingsServiceError.InvalidResponse)
            if (!readIsCurrent(probe.revision)) return@operation null
            connected = status.trim() == "connected"
            val address = value("wifi.ip") ?: throw SettingsServiceException(SettingsServiceError.InvalidResponse)
            if (!readIsCurrent(probe.revision)) return@operation null
            ip = address.takeUnless { it.isEmpty() || it == RadioCLIReply.NOT_CONNECTED }
            ipRead = true
        } catch (failure: SettingsServiceException) {
            if (!readIsCurrent(probe.revision)) return@operation null
            error = failure
        }
        RadioWiFiObservation(contextId, state.radioId, probe.revision, probe.enabled, ssid, ssidRead, connected, ip, ipRead, error)
    }

    suspend fun readCredentials(editor: UUID): RadioWiFiCredentialBaseline? {
        currentCoroutineContext().ensureActive()
        context.requireCurrent()
        synchronized(editorLock) { editorId = editor; editorRead = null }
        return operation {
            val revision = state.stableRevision() ?: return@operation null
            if (!editorIsCurrent(editor, revision)) return@operation null
            val ssid = value("wifi.ssid")
            if (!editorIsCurrent(editor, revision)) return@operation null
            val password = value("wifi.pwd")
            if (!editorIsCurrent(editor, revision)) return@operation null
            if (ssid == null || password == null) throw SettingsServiceException(SettingsServiceError.InvalidResponse)
            val readId = UUID.randomUUID()
            synchronized(editorLock) {
                if (editorId != editor || retired || !readIsCurrent(revision)) return@operation null
                editorRead = readId to revision
            }
            RadioWiFiCredentialBaseline(
                contextId, state.radioId, editor, revision, if (ssid == RadioCLIReply.NOT_SET) "" else ssid, password, readId,
            )
        }
    }

    fun closeEditor(editor: UUID) = synchronized(editorLock) {
        if (editorId == editor) { editorId = null; editorRead = null }
    }

    suspend fun changeEnabled(probe: RadioWiFiProbe, enabled: Boolean): RadioWiFiCommandReceipt =
        mutate(probe, "set wifi.enabled ${if (enabled) "1" else "0"}")

    suspend fun clear(probe: RadioWiFiProbe): RadioWiFiCommandReceipt = mutate(probe, "set wifi.clear")

    private suspend fun mutate(probe: RadioWiFiProbe, command: String): RadioWiFiCommandReceipt = operation {
        if (probe.contextId != contextId) throw RadioWiFiOwnershipLost()
        val token = state.beginMutation(probe.revision) ?: throw RadioWiFiOwnershipLost()
        var attempted = false
        var accepted = false
        var error: Exception? = null
        var outcome = RadioWiFiState.MutationOutcome.REJECTED
        var revision: ULong? = null
        try {
            if (!state.isUncontended(token) || !context.isCurrent) throw RadioWiFiOwnershipLost()
            attempted = true
            write(command)
            attempted = false
            accepted = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (lost: RadioWiFiOwnershipLost) {
            error = lost
        } catch (failure: SettingsServiceException) {
            if (isKnownRejection(failure)) attempted = false
            error = failure
        } finally {
            outcome = when {
                attempted -> RadioWiFiState.MutationOutcome.UNCERTAIN
                accepted -> RadioWiFiState.MutationOutcome.ACCEPTED
                else -> RadioWiFiState.MutationOutcome.REJECTED
            }
            revision = state.settle(token, outcome)
        }
        RadioWiFiCommandReceipt(contextId, revision, outcome, error)
    }

    private fun isKnownRejection(failure: SettingsServiceException): Boolean =
        failure.error is SettingsServiceError.RadioCLIRejected ||
            (failure.error as? SettingsServiceError.SessionError)?.error is MeshCoreException.InvalidInput

    private fun editorIsCurrent(editor: UUID, revision: ULong): Boolean =
        synchronized(editorLock) { editorId == editor && !retired } && readIsCurrent(revision)

    private fun ownsBaseline(baseline: RadioWiFiCredentialBaseline, editor: UUID): Boolean =
        baseline.contextId == contextId && baseline.radioId == state.radioId && baseline.editorId == editor &&
            synchronized(editorLock) {
                !retired && editorId == editor && editorRead == (baseline.readId to baseline.revision)
            }

    suspend fun saveCredentials(
        ssid: String,
        password: String,
        baseline: RadioWiFiCredentialBaseline,
        editor: UUID,
        ownerIsCurrent: () -> Boolean,
    ): RadioWiFiMutationReceipt = operation {
        if (!RadioWiFiCredentials.valid(ssid, password)) throw SettingsServiceException(SettingsServiceError.InvalidResponse)
        if (!ownsBaseline(baseline, editor) || !ownerIsCurrent()) throw RadioWiFiOwnershipLost()
        val token = state.beginMutation(baseline.revision) ?: throw RadioWiFiOwnershipLost()
        var attempted = false
        var ssidAccepted = false
        var passwordAccepted = false
        var failure: Exception? = null
        var revision: ULong? = null
        fun requireOwner() {
            if (!context.isCurrent || !ownerIsCurrent() || !ownsBaseline(baseline, editor) || !state.isUncontended(token)) {
                throw RadioWiFiOwnershipLost()
            }
        }
        try {
            requireOwner()
            val passwordChanged = !RadioWiFiCredentials.hasSameBytes(password, baseline.password)
            if (!RadioWiFiCredentials.hasSameBytes(ssid, baseline.ssid) || passwordChanged) {
                attempted = true
                write("set wifi.ssid $ssid")
                attempted = false
                ssidAccepted = true
            }
            if (passwordChanged) {
                requireOwner()
                attempted = true
                write("set wifi.pwd $password")
                attempted = false
                passwordAccepted = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (lost: RadioWiFiOwnershipLost) {
            failure = lost
        } catch (error: SettingsServiceException) {
            if (isKnownRejection(error)) attempted = false
            failure = error
        } finally {
            val outcome = when {
                attempted -> RadioWiFiState.MutationOutcome.UNCERTAIN
                ssidAccepted || passwordAccepted -> RadioWiFiState.MutationOutcome.ACCEPTED
                else -> RadioWiFiState.MutationOutcome.REJECTED
            }
            revision = state.settle(token, outcome)
        }
        val ownerStillCurrent = ownerIsCurrent()
        val updated = synchronized(editorLock) {
            if (ownsBaseline(baseline, editor) && revision != null && !attempted && ownerStillCurrent) {
                editorRead = baseline.readId to revision!!
                RadioWiFiCredentialBaseline(
                    contextId, state.radioId, editor, revision!!,
                    if (ssidAccepted) ssid else baseline.ssid,
                    if (passwordAccepted) password else baseline.password, baseline.readId,
                )
            } else {
                if (editorId == editor && editorRead?.first == baseline.readId) editorRead = null
                null
            }
        }
        RadioWiFiMutationReceipt(contextId, ssidAccepted, passwordAccepted, revision, attempted, failure, updated)
    }
}
