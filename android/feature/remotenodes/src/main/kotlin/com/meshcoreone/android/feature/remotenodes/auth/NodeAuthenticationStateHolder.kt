// PortedFrom: MC1/Views/RemoteNodes/NodeAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.auth

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeLoginPort
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Raised when no radio or services are connected (Swift throws `RemoteNodeError.notConnected`). */
class RemoteNodeNotConnectedException : Exception("Not connected to mesh device")

data class NodeAuthenticationState(
    val password: String = "",
    val rememberPassword: Boolean = true,
    val useFloodRouting: Boolean,
    val didResetPath: Boolean = false,
    val isRetryingViaFlood: Boolean = false,
    val isAuthenticating: Boolean = false,
    val errorMessage: RemoteNodesText? = null,
    val hasSavedPassword: Boolean = false,
    val authSecondsRemaining: Int? = null,
    internal val authStartTime: Instant? = null,
    internal val authTimeoutSeconds: Int? = null,
)

/**
 * Password login sheet for repeaters and rooms (Swift `NodeAuthenticationSheet`): saved-password
 * prefill, 15-character truncation, flood-path reset, one automatic flood retry after a direct-path
 * timeout, firmware-timeout countdown and saved-password removal. Login runs in [scope]; [cancel]
 * mirrors the sheet's Cancel/disappear.
 */
class NodeAuthenticationStateHolder(
    private val contact: ContactDTO,
    val role: RemoteNodeRole,
    private val login: () -> RemoteNodeLoginPort?,
    private val connectedRadioId: () -> RadioId?,
    private val faults: RemoteNodeFaultClassifier,
    private val clock: RemoteNodesClock,
    private val scope: CoroutineScope,
    private val onSuccess: (RemoteNodeSessionDTO) -> Unit,
) {
    private val _state = MutableStateFlow(NodeAuthenticationState(useFloodRouting = contact.isFloodRouted))
    val state: StateFlow<NodeAuthenticationState> = _state.asStateFlow()

    private var authenticationJob: Job? = null
    private var countdownJob: Job? = null

    private fun update(transform: (NodeAuthenticationState) -> NodeAuthenticationState) = _state.update(transform)

    /** Editing the password clears a shown error (the sheet's `onChange(of: password)`). */
    fun setPassword(value: String) = update { it.copy(password = value, errorMessage = null) }
    fun setRememberPassword(value: Boolean) = update { it.copy(rememberPassword = value) }

    /** The flood toggle is disabled when the contact has no stored path. */
    fun setUseFloodRouting(value: Boolean) {
        if (hasStoredPath) update { it.copy(useFloodRouting = value) }
    }

    val hasStoredPath: Boolean get() = !contact.isFloodRouted

    /** Prefills a saved password (the sheet's first `.task`). */
    suspend fun loadSavedPassword() {
        val saved = login()?.retrievePassword(contact) ?: return
        update { it.copy(password = saved, hasSavedPassword = true) }
    }

    fun authenticate(): Job {
        update { it.copy(errorMessage = null, isAuthenticating = true, isRetryingViaFlood = false) }
        authenticationJob?.cancel()
        cleanupCountdownState()
        return scope.launch { runAuthentication() }.also { authenticationJob = it }
    }

    fun cancel() {
        authenticationJob?.cancel()
        cleanupCountdownState()
    }

    private suspend fun runAuthentication() {
        try {
            val radioId = connectedRadioId() ?: throw RemoteNodeNotConnectedException()
            val service = login() ?: throw RemoteNodeNotConnectedException()
            val pathLength = initialPathLength(service, radioId)
            val password = truncatedPassword(_state.value.password)
            val session = try {
                performLogin(service, radioId, password, pathLength)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!faults.isTimeout(error) || pathLength == PacketBuilder.FLOOD_PATH_SENTINEL) throw error
                // The stored route produced no reply: clear it and let the network find a path.
                update { it.copy(isRetryingViaFlood = true) }
                cleanupCountdownState()
                service.resetPath(radioId, contact.publicKey)
                update { it.copy(didResetPath = true) }
                performLogin(service, radioId, password, PacketBuilder.FLOOD_PATH_SENTINEL)
            }
            if (_state.value.hasSavedPassword && !_state.value.rememberPassword) {
                try {
                    service.deletePassword(contact)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    java.util.logging.Logger.getLogger(NodeAuthenticationStateHolder::class.java.name).log(
                        java.util.logging.Level.WARNING, "Failed to delete saved password", error,
                    )
                }
            }
            finish(null, keepAuthenticating = true)
            onSuccess(session)
        } catch (error: CancellationException) {
            finish(null)
            throw error
        } catch (error: Exception) {
            val text = if (faults.isTimeout(error)) {
                RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesStatusRequestTimedOut)
            } else {
                RemoteNodesText.Failure(error)
            }
            finish(text)
        }
    }

    /**
     * Resets the stored path once per sheet when flooding a routed contact; afterwards (or when the
     * contact already floods) logins use the flood sentinel, otherwise the stored path length.
     */
    private suspend fun initialPathLength(service: RemoteNodeLoginPort, radioId: RadioId): UByte {
        val current = _state.value
        return when {
            current.useFloodRouting && !contact.isFloodRouted && !current.didResetPath -> {
                service.resetPath(radioId, contact.publicKey)
                update { it.copy(didResetPath = true) }
                PacketBuilder.FLOOD_PATH_SENTINEL
            }
            current.useFloodRouting || current.didResetPath -> PacketBuilder.FLOOD_PATH_SENTINEL
            else -> contact.outPathLength
        }
    }

    private suspend fun performLogin(service: RemoteNodeLoginPort, radioId: RadioId, password: String, pathLength: UByte): RemoteNodeSessionDTO {
        val remember = _state.value.rememberPassword
        val onTimeoutKnown: suspend (Long) -> Unit = { seconds -> startCountdown(seconds.toInt()) }
        return if (role == RemoteNodeRole.ROOM_SERVER) {
            service.joinRoom(radioId, contact, password, remember, pathLength, onTimeoutKnown)
        } else {
            service.connectAsAdmin(radioId, contact, password, remember, pathLength, onTimeoutKnown)
        }
    }

    private fun finish(error: RemoteNodesText?, keepAuthenticating: Boolean = false) {
        authenticationJob = null
        cleanupCountdownState()
        update {
            it.copy(
                isRetryingViaFlood = false,
                errorMessage = error ?: it.errorMessage,
                // On success Swift dismisses the sheet without clearing the spinner.
                isAuthenticating = keepAuthenticating && it.isAuthenticating,
            )
        }
    }

    private fun startCountdown(seconds: Int) {
        update { it.copy(authTimeoutSeconds = seconds, authStartTime = clock.now, authSecondsRemaining = seconds) }
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (isActive) {
                val current = _state.value
                val timeout = current.authTimeoutSeconds ?: break
                val start = current.authStartTime ?: break
                clock.sleep(COUNTDOWN_TICK)
                val elapsed = Duration.between(start, clock.now).seconds.toInt()
                val remaining = maxOf(0, timeout - elapsed)
                update { it.copy(authSecondsRemaining = remaining) }
                if (remaining == 0) break
            }
        }
    }

    private fun cleanupCountdownState() {
        countdownJob?.cancel()
        countdownJob = null
        update { it.copy(authSecondsRemaining = null, authStartTime = null, authTimeoutSeconds = null) }
    }

    companion object {
        /** MeshCore repeaters and rooms accept 15-character passwords. */
        const val MAX_PASSWORD_LENGTH = 15
        private val COUNTDOWN_TICK = 1.seconds

        /** Truncates to [MAX_PASSWORD_LENGTH] Swift `Character`s. */
        fun truncatedPassword(password: String): String =
            if (RemoteSwiftText.characterCount(password) > MAX_PASSWORD_LENGTH) {
                RemoteSwiftText.leadingCharacters(password, MAX_PASSWORD_LENGTH).joinToString("")
            } else {
                password
            }
    }
}
