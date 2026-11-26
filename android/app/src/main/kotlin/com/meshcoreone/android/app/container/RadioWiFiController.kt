// AndroidOnly: WP-211 Connection-owned radio Wi-Fi presentation compared with current upstream.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.RadioWiFiState
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.services.device.RadioWiFiCapability
import com.meshcoreone.android.core.services.device.RadioWiFiCredentialBaseline
import com.meshcoreone.android.core.services.device.RadioWiFiCredentials
import com.meshcoreone.android.core.services.device.RadioWiFiProbe
import com.meshcoreone.android.core.services.device.RadioWiFiService
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class RadioWiFiSupport { UNKNOWN, SUPPORTED, UNAVAILABLE }
internal enum class RadioWiFiConfirmation { SAVE, DISABLE, CLEAR, REBOOT }

internal data class RadioWiFiEditor(
    val id: UUID,
    val ssid: String = "",
    val password: String = "",
    val ssidEdited: Boolean = false,
    val passwordEdited: Boolean = false,
    val baseline: RadioWiFiCredentialBaseline? = null,
) {
    override fun toString() = "RadioWiFiEditor(<redacted>)"
}

internal data class RadioWiFiPresentation(
    val support: RadioWiFiSupport = RadioWiFiSupport.UNKNOWN,
    val busy: Boolean = false,
    val writing: Boolean = false,
    val enabled: Boolean = false,
    val network: String? = null,
    val networkCurrent: Boolean = false,
    val associated: Boolean? = null,
    val address: String? = null,
    val pendingReboot: Boolean = false,
    val editor: RadioWiFiEditor? = null,
    val confirmation: RadioWiFiConfirmation? = null,
    val error: Throwable? = null,
)

internal class RadioWiFiController(
    private val scope: CoroutineScope,
    private val currentService: () -> RadioWiFiService?,
    private val isReady: () -> Boolean,
    private val transport: () -> TransportType?,
) {
    private sealed interface Confirmation {
        val owner: RadioWiFiService
        data class Disable(override val owner: RadioWiFiService, val probe: RadioWiFiProbe) : Confirmation
        data class Clear(override val owner: RadioWiFiService, val probe: RadioWiFiProbe) : Confirmation
        data class Reboot(override val owner: RadioWiFiService, val revision: ULong) : Confirmation
        data class Save(
            override val owner: RadioWiFiService,
            val editorId: UUID,
            val baseline: RadioWiFiCredentialBaseline,
            val ssid: String,
            val password: String,
        ) : Confirmation {
            override fun toString() = "Save(<redacted>)"
        }
    }

    private val lock = Any()
    private val mutable = MutableStateFlow(RadioWiFiPresentation())
    val state: StateFlow<RadioWiFiPresentation> = mutable.asStateFlow()
    private var owner: RadioWiFiService? = null
    private var pendingRebootBinding = Any()
    private var pendingRebootJob: Job? = null
    private var probe: RadioWiFiProbe? = null
    private var operation: UUID? = null
    private var confirmation: Confirmation? = null
    private var owesRefresh = false
    private var observationError: Throwable? = null
    private var actionError: Throwable? = null

    fun bind(service: RadioWiFiService?, supportsCli: Boolean) {
        synchronized(lock) {
            if (owner !== service) {
                mutable.value.editor?.let { editor -> owner?.closeEditor(editor.id) }
                pendingRebootJob?.cancel()
                val binding = Any()
                pendingRebootBinding = binding
                owner = service
                probe = null
                operation = null
                confirmation = null
                owesRefresh = false
                observationError = null
                actionError = null
                mutable.value = RadioWiFiPresentation(pendingReboot = service?.state?.isRebootPending == true)
                pendingRebootJob = service?.let { current ->
                    scope.launch {
                        current.state.pendingRebootChanges.collect { pending ->
                            synchronized(lock) {
                                if (pendingRebootBinding === binding) {
                                    mutable.update { it.copy(pendingReboot = pending) }
                                }
                            }
                        }
                    }
                }
            }
            if (service == null || !supportsCli || !isReady()) {
                probe = null
                mutable.update { it.copy(
                    support = if (service != null && !supportsCli) RadioWiFiSupport.UNAVAILABLE else RadioWiFiSupport.UNKNOWN,
                    associated = null, address = null,
                ) }
                return
            }
        }
        refresh()
    }

    private fun matches(service: RadioWiFiService): Boolean =
        owner === service && currentService() === service && isReady()

    private fun begin(service: RadioWiFiService, writing: Boolean): UUID? = synchronized(lock) {
        if (!matches(service) || operation != null) return@synchronized null
        UUID.randomUUID().also {
            operation = it
            mutable.update { state -> state.copy(busy = true, writing = writing) }
        }
    }

    private fun owns(service: RadioWiFiService, id: UUID): Boolean = synchronized(lock) {
        // Readiness callbacks can rebind the controller reentrantly.
        operation == id && matches(service) && owner === service && operation == id
    }

    private fun finish(service: RadioWiFiService, id: UUID) {
        val refresh = synchronized(lock) {
            if (operation != id) return
            operation = null
            mutable.update { it.copy(
                busy = false, writing = false, pendingReboot = service.state.isRebootPending,
            ) }
            (owesRefresh && mutable.value.editor == null && matches(service)).also {
                if (it) owesRefresh = false
            }
        }
        if (refresh) refresh()
    }

    private fun failed(service: RadioWiFiService, id: UUID, error: Throwable, observation: Boolean = false) = synchronized(lock) {
        if (owns(service, id)) {
            if (observation) observationError = error else actionError = error
            mutable.update { it.copy(error = actionError ?: observationError, associated = null, address = null) }
        }
    }

    fun dismissError() = synchronized(lock) {
        if (actionError != null) actionError = null else observationError = null
        mutable.update { it.copy(error = actionError ?: observationError) }
    }

    fun refresh() {
        val service = synchronized(lock) {
            val service = owner ?: return
            if (mutable.value.editor != null || operation != null) {
                owesRefresh = true
                return
            }
            service
        }
        val id = begin(service, writing = false) ?: return
        scope.launch {
            try {
                when (val capability = service.probe()) {
                    RadioWiFiCapability.Unavailable -> synchronized(lock) {
                        if (owns(service, id)) {
                            probe = null
                            mutable.update { it.copy(
                                support = RadioWiFiSupport.UNAVAILABLE, network = null, networkCurrent = false,
                                associated = null, address = null,
                            ) }
                        }
                    }
                    RadioWiFiCapability.Busy -> synchronized(lock) {
                        if (owns(service, id)) {
                            probe = null
                            mutable.update { it.copy(associated = null, address = null) }
                        }
                    }
                    is RadioWiFiCapability.Supported -> {
                        val read = service.observe(capability.probe)
                        synchronized(lock) {
                            if (owns(service, id) && service.state.stableRevision() == capability.probe.revision) {
                                probe = capability.probe
                                if (read != null) observationError = read.error
                                mutable.update { presentation -> presentation.copy(
                                    support = RadioWiFiSupport.SUPPORTED, enabled = capability.probe.enabled,
                                    network = if (read?.networkNameWasRead == true) read.networkName else presentation.network,
                                    networkCurrent = read?.networkNameWasRead == true,
                                    associated = read?.associated, address = read?.ipAddress,
                                    error = actionError ?: observationError,
                                ) }
                            } else if (owns(service, id)) {
                                owesRefresh = true
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed(service, id, error, observation = true)
            } finally {
                finish(service, id)
            }
        }
    }

    fun openEditor() {
        synchronized(lock) {
            val service = owner ?: return
            val current = probe ?: return
            if (!matches(service) || operation != null || mutable.value.support != RadioWiFiSupport.SUPPORTED ||
                service.state.stableRevision() != current.revision
            ) return
            mutable.update { it.copy(editor = RadioWiFiEditor(UUID.randomUUID(), it.network.orEmpty())) }
        }
        readEditor()
    }

    fun readEditor() {
        val pair = synchronized(lock) {
            val service = owner ?: return
            val editor = mutable.value.editor ?: return
            service to editor.id
        }
        val (service, editorId) = pair
        val id = begin(service, writing = false) ?: return
        synchronized(lock) {
            mutable.value.editor?.takeIf { it.id == editorId }?.let {
                mutable.update { state -> state.copy(editor = it.copy(baseline = null)) }
            }
        }
        scope.launch {
            try {
                val baseline = service.readCredentials(editorId)
                synchronized(lock) {
                    val editor = mutable.value.editor
                    if (owns(service, id) && editor?.id == editorId && baseline != null) {
                        mutable.update { it.copy(
                            editor = editor.copy(
                                baseline = baseline,
                                ssid = if (editor.ssidEdited) editor.ssid else baseline.ssid,
                                password = if (editor.passwordEdited) editor.password else baseline.password,
                            ),
                            network = baseline.ssid.ifEmpty { null }, networkCurrent = true,
                        ) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed(service, id, error)
            } finally {
                finish(service, id)
            }
        }
    }

    fun setSsid(value: String) = synchronized(lock) {
        mutable.value.editor?.let { editor ->
            mutable.update { it.copy(editor = editor.copy(ssid = value, ssidEdited = true)) }
        }
    }

    fun setPassword(value: String) = synchronized(lock) {
        mutable.value.editor?.let { editor ->
            mutable.update { it.copy(editor = editor.copy(password = value, passwordEdited = true)) }
        }
    }

    fun canSave(): Boolean = synchronized(lock) {
        val service = owner ?: return@synchronized false
        val editor = mutable.value.editor ?: return@synchronized false
        val baseline = editor.baseline ?: return@synchronized false
        matches(service) && operation == null && service.state.stableRevision() == baseline.revision &&
            RadioWiFiCredentials.valid(editor.ssid, editor.password) &&
            (!RadioWiFiCredentials.hasSameBytes(editor.ssid, baseline.ssid) ||
                !RadioWiFiCredentials.hasSameBytes(editor.password, baseline.password))
    }

    fun canOperate(): Boolean = synchronized(lock) {
        val service = owner ?: return@synchronized false
        val current = probe ?: return@synchronized false
        matches(service) && operation == null && mutable.value.support == RadioWiFiSupport.SUPPORTED &&
            service.state.stableRevision() == current.revision
    }

    fun canReboot(): Boolean = synchronized(lock) {
        val service = owner ?: return@synchronized false
        matches(service) && operation == null && service.state.stableRevision() != null && service.state.isRebootPending
    }

    fun closeEditor() = closeEditorFor(null)

    fun closeEditorIfCurrent(editorId: UUID) = closeEditorFor(editorId)

    private fun closeEditorFor(expectedEditorId: UUID?) {
        val service: RadioWiFiService?
        val editor: RadioWiFiEditor?
        synchronized(lock) {
            if (mutable.value.writing ||
                (expectedEditorId != null && mutable.value.editor?.id != expectedEditorId)
            ) return
            service = owner
            editor = mutable.value.editor
            confirmation = null
            mutable.update { it.copy(editor = null, confirmation = null) }
            owesRefresh = true
        }
        editor?.let { service?.closeEditor(it.id) }
        refresh()
    }

    fun requestSave() {
        val request = synchronized(lock) {
            if (!canSave()) return
            val service = owner ?: return
            val editor = mutable.value.editor ?: return
            Confirmation.Save(service, editor.id, requireNotNull(editor.baseline), editor.ssid, editor.password)
        }
        if (transport() == TransportType.WIFI) showConfirmation(request, RadioWiFiConfirmation.SAVE)
        else submit(request)
    }

    fun requestEnabled(enabled: Boolean) {
        val request = synchronized(lock) {
            val service = owner ?: return
            val current = probe ?: return
            if (!matches(service) || operation != null || service.state.stableRevision() != current.revision) return
            Confirmation.Disable(service, current)
        }
        if (!enabled && transport() == TransportType.WIFI) showConfirmation(request, RadioWiFiConfirmation.DISABLE)
        else writeEnabled(request, enabled)
    }

    fun requestClear() {
        val request = synchronized(lock) {
            val service = owner ?: return
            val current = probe ?: return
            if (!matches(service) || operation != null || !mutable.value.networkCurrent ||
                mutable.value.network == null || service.state.stableRevision() != current.revision
            ) return
            Confirmation.Clear(service, current)
        }
        showConfirmation(request, RadioWiFiConfirmation.CLEAR)
    }

    fun requestReboot() {
        val request = synchronized(lock) {
            val service = owner ?: return
            val revision = service.state.stableRevision() ?: return
            if (!matches(service) || operation != null || !service.state.isRebootPending) return
            Confirmation.Reboot(service, revision)
        }
        showConfirmation(request, RadioWiFiConfirmation.REBOOT)
    }

    private fun showConfirmation(request: Confirmation, kind: RadioWiFiConfirmation) = synchronized(lock) {
        if (matches(request.owner) && operation == null) {
            confirmation = request
            mutable.update { it.copy(confirmation = kind) }
        }
    }

    fun cancelConfirmation() = synchronized(lock) {
        confirmation = null
        mutable.update { it.copy(confirmation = null) }
    }

    fun confirm() {
        val request = synchronized(lock) {
            confirmation.also {
                confirmation = null
                mutable.update { state -> state.copy(confirmation = null) }
            }
        } ?: return
        when (request) {
            is Confirmation.Save -> submit(request)
            is Confirmation.Disable -> writeEnabled(request, false)
            is Confirmation.Clear -> execute(request.owner) { service, id ->
                val receipt = service.clear(request.probe)
                synchronized(lock) {
                    if (owns(service, id)) {
                        actionError = receipt.error
                        mutable.update { it.copy(error = actionError ?: observationError, networkCurrent = false) }
                        owesRefresh = true
                    }
                }
            }
            is Confirmation.Reboot -> execute(request.owner) { service, _ -> service.reboot(request.revision) }
        }
    }

    private fun writeEnabled(request: Confirmation.Disable, enabled: Boolean) =
        execute(request.owner) { service, id ->
            val receipt = service.changeEnabled(request.probe, enabled)
            synchronized(lock) {
                if (owns(service, id)) {
                    actionError = receipt.error
                    mutable.update { presentation -> presentation.copy(
                        error = actionError ?: observationError,
                        enabled = if (receipt.outcome == RadioWiFiState.MutationOutcome.ACCEPTED) enabled else presentation.enabled,
                    ) }
                    owesRefresh = true
                }
            }
        }

    private fun submit(request: Confirmation.Save) = execute(request.owner) { service, id ->
        val receipt = service.saveCredentials(
            request.ssid, request.password, request.baseline, request.editorId,
            ownerIsCurrent = {
                synchronized(lock) {
                    operation == id && matches(service) && mutable.value.editor?.id == request.editorId
                }
            },
        )
        synchronized(lock) {
            val editor = mutable.value.editor
            if (owns(service, id) && editor?.id == request.editorId) {
                actionError = receipt.error
                mutable.update { presentation -> presentation.copy(
                    editor = editor.copy(baseline = receipt.updatedBaseline),
                    network = if (receipt.ssidAccepted) request.ssid else presentation.network,
                    networkCurrent = receipt.ssidAccepted && receipt.revision != null && !receipt.uncertain,
                    error = actionError ?: observationError,
                ) }
                if (receipt.error == null && receipt.revision != null && !receipt.uncertain) {
                    service.closeEditor(editor.id)
                    mutable.update { it.copy(editor = null) }
                    owesRefresh = true
                }
            }
        }
    }

    private fun execute(service: RadioWiFiService, action: suspend (RadioWiFiService, UUID) -> Unit) {
        val id = begin(service, writing = true) ?: return
        scope.launch {
            try {
                action(service, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed(service, id, error)
                synchronized(lock) { if (operation == id) owesRefresh = true }
            } finally {
                finish(service, id)
            }
        }
    }
}
