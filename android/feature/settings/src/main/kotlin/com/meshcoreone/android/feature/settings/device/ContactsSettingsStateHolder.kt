// PortedFrom: MC1/Views/Settings/Sections/NodesSettingsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.AutoAddMode
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One entry of the max-hops menu; [value] is the wire byte (`0` no limit, `1` direct only, `2` one hop, `hops + 1` above). */
data class MaxHopsOption(val value: UByte, val hops: Int?, val labelId: Int?)

data class ContactsSettingsState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val autoAddMode: AutoAddMode = AutoAddMode.MANUAL,
    val autoAddContacts: Boolean = false,
    val autoAddRepeaters: Boolean = false,
    val autoAddRoomServers: Boolean = false,
    val overwriteOldest: Boolean = false,
    val autoAddMaxHops: UByte = 0u,
    val isApplying: Boolean = false,
    val showSuccess: Boolean = false,
    val errorMessage: UiText? = null,
) {
    val supportsAutoAddConfig: Boolean get() = device?.supportsAutoAddConfig ?: false
    val supportsAutoAddMaxHops: Boolean get() = device?.supportsAutoAddMaxHops ?: false
    val showsTypeToggles: Boolean get() = supportsAutoAddConfig && autoAddMode == AutoAddMode.SELECTED_TYPES
    val showsMaxHops: Boolean get() = supportsAutoAddMaxHops && autoAddMode != AutoAddMode.MANUAL

    /** Older firmware only has the manual/all switch, so the "selected types" mode is offered from v1.12. */
    val availableModes: List<AutoAddMode>
        get() = if (supportsAutoAddConfig) AutoAddMode.entries else listOf(AutoAddMode.MANUAL, AutoAddMode.ALL)

    val settingsModified: Boolean
        get() {
            val current = device ?: return false
            return if (current.supportsAutoAddConfig) {
                autoAddMode != current.autoAddMode || autoAddContacts != current.autoAddContacts ||
                    autoAddRepeaters != current.autoAddRepeaters || autoAddRoomServers != current.autoAddRoomServers ||
                    overwriteOldest != current.overwriteOldest || autoAddMaxHops != current.autoAddMaxHops
            } else {
                autoAddMode != if (current.manualAddContacts) AutoAddMode.MANUAL else AutoAddMode.ALL
            }
        }

    val canApply: Boolean
        get() = connectionState == DeviceConnectionState.READY && settingsModified && !isApplying && !showSuccess

    /** Footer: the mode description, plus the hop-limit note while a limit is active. */
    val footerIds: List<Int>
        get() = buildList {
            add(
                when (autoAddMode) {
                    AutoAddMode.MANUAL -> AppSettingsStrings.nodesAutoAddModeManualDescription
                    AutoAddMode.SELECTED_TYPES -> AppSettingsStrings.nodesAutoAddModeSelectedTypesDescription
                    AutoAddMode.ALL -> AppSettingsStrings.nodesAutoAddModeAllDescription
                },
            )
            if (showsMaxHops && autoAddMaxHops > 0u) add(AppSettingsStrings.nodesMaxHopsFooterActive)
        }
}

/** The max-hops menu: no limit, direct only, one hop, then 2 through 6 hops (wire value hops + 1). */
val MaxHopsOptions: List<MaxHopsOption> = listOf(
    MaxHopsOption(0u, null, AppSettingsStrings.nodesMaxHopsNoLimit),
    MaxHopsOption(1u, null, AppSettingsStrings.nodesMaxHopsDirectOnly),
    MaxHopsOption(2u, null, AppSettingsStrings.nodesMaxHopsOneHop),
) + (2..6).map { MaxHopsOption((it + 1).toUByte(), it, null) }

/** Auto-add mode, node types, hop limit and overwrite-oldest, written as a manual-add flag plus an auto-add config. */
class ContactsSettingsStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(ContactsSettingsState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    val state: StateFlow<ContactsSettingsState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.connectedDevice.collect { device ->
                mutable.update { it.copy(device = device) }
                loadFromDevice()
            }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun onModeSelected(mode: AutoAddMode) = mutable.update { it.copy(autoAddMode = mode) }
    fun onContactsToggled(on: Boolean) = mutable.update { it.copy(autoAddContacts = on) }
    fun onRepeatersToggled(on: Boolean) = mutable.update { it.copy(autoAddRepeaters = on) }
    fun onRoomServersToggled(on: Boolean) = mutable.update { it.copy(autoAddRoomServers = on) }
    fun onOverwriteOldestToggled(on: Boolean) = mutable.update { it.copy(overwriteOldest = on) }
    fun onMaxHopsSelected(value: UByte) = mutable.update { it.copy(autoAddMaxHops = value) }

    private fun loadFromDevice() {
        val device = mutable.value.device ?: return
        mutable.update {
            if (device.supportsAutoAddConfig) {
                it.copy(
                    autoAddMode = device.autoAddMode, autoAddContacts = device.autoAddContacts,
                    autoAddRepeaters = device.autoAddRepeaters, autoAddRoomServers = device.autoAddRoomServers,
                    overwriteOldest = device.overwriteOldest, autoAddMaxHops = device.autoAddMaxHops,
                )
            } else {
                // Older firmware only has the manual/all switch through manualAddContacts.
                it.copy(
                    autoAddMode = if (device.manualAddContacts) AutoAddMode.MANUAL else AutoAddMode.ALL,
                    autoAddContacts = false, autoAddRepeaters = false, autoAddRoomServers = false, overwriteOldest = false,
                )
            }
        }
    }

    fun apply() {
        if (mutable.value.isApplying) return
        val device = mutable.value.device ?: return
        val service = settingsService() ?: return
        val form = mutable.value
        mutable.update { it.copy(isApplying = true) }
        env.scope.launch { runApply(service, device, form) }
    }

    private suspend fun runApply(service: SettingsRadioPort, device: DeviceDTO, form: ContactsSettingsState) {
        try {
            // Protocol: manualAddContacts is true for manual and selected-types, false only for all.
            val manualAdd = form.autoAddMode != AutoAddMode.ALL
            service.setOtherParamsVerified(device, autoAddContacts = !manualAdd)
            if (device.supportsAutoAddConfig) {
                service.setAutoAddConfigVerified(AutoAddConfig(configBitmask(form), form.autoAddMaxHops))
            }
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(isApplying = false) }
            throw cancelled
        } catch (error: Exception) {
            loadFromDevice()
            val message = failures.route(error) { apply() }
            mutable.update { it.copy(errorMessage = message ?: it.errorMessage, isApplying = false) }
            return
        }
        retryAlert.reset()
        mutable.update { it.copy(isApplying = false, showSuccess = true) }
        try {
            env.clock.sleep(SUCCESS_DISPLAY)
        } finally {
            mutable.update { it.copy(showSuccess = false) }
        }
    }

    private fun configBitmask(form: ContactsSettingsState): UByte {
        var config = 0
        if (form.overwriteOldest) config = config or AutoAddConfig.OVERWRITE_OLDEST_BIT.toInt()
        if (form.autoAddMode == AutoAddMode.SELECTED_TYPES) {
            if (form.autoAddContacts) config = config or AutoAddConfig.CONTACTS_BIT.toInt()
            if (form.autoAddRepeaters) config = config or AutoAddConfig.REPEATERS_BIT.toInt()
            if (form.autoAddRoomServers) config = config or AutoAddConfig.ROOM_SERVERS_BIT.toInt()
        }
        return config.toUByte()
    }
}
