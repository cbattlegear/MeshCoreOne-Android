// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomSettingsViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.cli.CLIResponse
import com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.dependencies.RoomAdminPort
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Room-only settings sections: access (guest password, read-only) and behavior. */
data class RoomSettingsState(
    val guestPassword: String? = null,
    val allowReadOnly: Boolean? = null,
    val originalGuestPassword: String? = null,
    val originalAllowReadOnly: Boolean? = null,
    val isLoadingRoomAccess: Boolean = false,
    val roomAccessError: Boolean = false,
    val isApplyingRoomAccess: Boolean = false,
    val roomAccessApplySuccess: Boolean = false,
    val isRoomAccessExpanded: Boolean = false,
    val advertIntervalMinutes: Long? = null,
    val floodAdvertIntervalHours: Long? = null,
    val floodMaxHops: Long? = null,
    val originalAdvertIntervalMinutes: Long? = null,
    val originalFloodAdvertIntervalHours: Long? = null,
    val originalFloodMaxHops: Long? = null,
    val isLoadingBehavior: Boolean = false,
    val behaviorError: Boolean = false,
    val isApplyingBehavior: Boolean = false,
    val behaviorApplySuccess: Boolean = false,
    val isBehaviorExpanded: Boolean = false,
    val advertIntervalError: RemoteNodesText? = null,
    val floodAdvertIntervalError: RemoteNodesText? = null,
    val floodMaxHopsError: RemoteNodesText? = null,
) {
    val roomAccessLoaded: Boolean get() = guestPassword != null || allowReadOnly != null
    val roomAccessModified: Boolean
        get() = (guestPassword != null && guestPassword != originalGuestPassword) ||
            (allowReadOnly != null && allowReadOnly != originalAllowReadOnly)
    val behaviorLoaded: Boolean get() = advertIntervalMinutes != null || floodAdvertIntervalHours != null || floodMaxHops != null
    val behaviorModified: Boolean
        get() = (advertIntervalMinutes != null && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
            (floodAdvertIntervalHours != null && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
            (floodMaxHops != null && floodMaxHops != originalFloodMaxHops)
    internal val behaviorSectionComplete: Boolean
        get() = originalAdvertIntervalMinutes != null && originalFloodAdvertIntervalHours != null && originalFloodMaxHops != null
}

/** Room server settings (Swift `RoomSettingsViewModel`); firmware info comes from CLI `ver`. */
class RoomSettingsStateHolder(
    clock: RemoteNodesClock,
    private val faults: RemoteNodeFaultClassifier,
    private val scope: CoroutineScope,
) {
    val helper = NodeSettingsStateHolder(clock, faults)

    private val _state = MutableStateFlow(RoomSettingsState())
    val state: StateFlow<RoomSettingsState> = _state.asStateFlow()

    private var roomAdmin: () -> RoomAdminPort? = { null }

    private fun update(transform: (RoomSettingsState) -> RoomSettingsState) = _state.update(transform)

    fun setGuestPassword(value: String?) = update { it.copy(guestPassword = value) }
    fun setAllowReadOnly(value: Boolean?) = update { it.copy(allowReadOnly = value) }
    fun setAdvertIntervalMinutes(value: Long?) = update { it.copy(advertIntervalMinutes = value) }
    fun setFloodAdvertIntervalHours(value: Long?) = update { it.copy(floodAdvertIntervalHours = value) }
    fun setFloodMaxHops(value: Long?) = update { it.copy(floodMaxHops = value) }
    fun setRoomAccessExpanded(expanded: Boolean) = update { it.copy(isRoomAccessExpanded = expanded) }
    fun setBehaviorExpanded(expanded: Boolean) = update { it.copy(isBehaviorExpanded = expanded) }

    fun cleanup() {
        roomAdmin()?.setCLIHandler { _, _ -> }
        helper.cleanup()
    }

    fun configure(roomAdmin: () -> RoomAdminPort?, session: RemoteNodeSessionDTO) {
        this.roomAdmin = roomAdmin
        val service = roomAdmin() ?: return
        helper.configure(
            session = session,
            sendCommand = { key, command, timeout -> service.sendCommand(key, command, timeout) },
            sendRawCommand = { key, command, timeout -> service.sendRawCommand(key, command, timeout) },
        )
        helper.setNodeInfo(firmwareVersion = null, name = session.name, ownerInfo = null)
        // Rooms have no binary owner-info request; firmware comes from CLI `ver`.
        helper.onPreFetchNodeInfo = null
        registerBehaviorLateRecovery()
        service.setCLIHandler { message, _ -> helper.handleCommonLateResponse(message.text) }
        scope.launch { helper.fetchDeviceInfo() }
    }

    fun makeNodeCLISend(session: RemoteNodeSessionDTO): (suspend (command: String, timeout: Duration) -> String)? {
        val service = roomAdmin() ?: return null
        val key = EntityKey(session.radioId, session.id)
        return { command, timeout -> service.sendRawCommand(key, command, timeout) }
    }

    private fun registerBehaviorLateRecovery() {
        helper.registerLateRecovery(CLIResponse.Query.ADVERT_INTERVAL) { value ->
            val minutes = (value as? CLIResponse.AdvertInterval)?.minutes ?: return@registerLateRecovery
            recovered { it.copy(advertIntervalMinutes = minutes, originalAdvertIntervalMinutes = minutes) }
        }
        helper.registerLateRecovery(CLIResponse.Query.FLOOD_ADVERT_INTERVAL) { value ->
            val hours = (value as? CLIResponse.FloodAdvertInterval)?.hours ?: return@registerLateRecovery
            recovered { it.copy(floodAdvertIntervalHours = hours, originalFloodAdvertIntervalHours = hours) }
        }
        helper.registerLateRecovery(CLIResponse.Query.FLOOD_MAX) { value ->
            val hops = (value as? CLIResponse.FloodMax)?.hops ?: return@registerLateRecovery
            recovered { it.copy(floodMaxHops = hops, originalFloodMaxHops = hops) }
        }
    }

    private fun recovered(transform: (RoomSettingsState) -> RoomSettingsState) = update {
        val next = transform(it)
        next.copy(behaviorError = !next.behaviorSectionComplete)
    }

    suspend fun fetchRoomAccess() {
        update { it.copy(isLoadingRoomAccess = true, roomAccessError = false) }
        try {
            fetchGuestPassword()
            val readOnly = helper.query("get allow.read.only", rawMatching = true) { update { it.copy(roomAccessError = true) } }
            if (readOnly is CLIResponse.Raw) {
                val isOn = readOnly.text.lowercase() == "on"
                update { it.copy(allowReadOnly = isOn, originalAllowReadOnly = isOn) }
            }
        } finally {
            update { it.copy(isLoadingRoomAccess = false) }
        }
    }

    /** `get guest.password`: an OK/error reply means none; anything else is the trimmed raw reply. */
    private suspend fun fetchGuestPassword() {
        val query = "get guest.password"
        val response = try {
            helper.sendAndWait(query, rawMatching = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (faults.isTimeout(error)) update { it.copy(roomAccessError = true) }
            return
        }
        when (CLIResponse.parse(response, query)) {
            CLIResponse.Ok, is CLIResponse.Error, is CLIResponse.UnknownCommand ->
                update { it.copy(guestPassword = "", originalGuestPassword = "") }
            else -> {
                val trimmed = RemoteSwiftText.trimWhitespacesAndNewlines(response)
                val value = if (trimmed.startsWith("> ")) trimmed.substring(2) else trimmed
                update { it.copy(guestPassword = value, originalGuestPassword = value) }
            }
        }
    }

    suspend fun applyRoomAccess() {
        update { it.copy(isApplyingRoomAccess = true) }
        helper.update { it.copy(errorMessage = null) }
        val clearApplying = { update { it.copy(isApplyingRoomAccess = false) } }
        val flashed = helper.runApplying(clearApplying) {
            var allSucceeded = true
            _state.value.guestPassword?.let { password ->
                if (password != _state.value.originalGuestPassword) {
                    if (helper.isOk(helper.sendAndWait("set guest.password $password"))) {
                        update { it.copy(originalGuestPassword = password) }
                    } else allSucceeded = false
                }
            }
            _state.value.allowReadOnly?.let { allow ->
                if (allow != _state.value.originalAllowReadOnly) {
                    if (helper.isOk(helper.sendAndWait("set allow.read.only ${if (allow) "on" else "off"}"))) {
                        update { it.copy(originalAllowReadOnly = allow) }
                    } else allSucceeded = false
                }
            }
            if (allSucceeded) {
                helper.flashSuccess(
                    { v -> update { it.copy(isApplyingRoomAccess = v) } },
                    { v -> update { it.copy(roomAccessApplySuccess = v) } },
                )
                true
            } else {
                helper.update { it.copy(errorMessage = helper.someSettingsFailed()) }
                false
            }
        }
        if (flashed != true) clearApplying()
    }

    suspend fun fetchBehaviorSettings() {
        update { it.copy(isLoadingBehavior = true, behaviorError = false) }
        var hadTimeout = false
        try {
            (helper.query(CLIResponse.Query.ADVERT_INTERVAL) { hadTimeout = true } as? CLIResponse.AdvertInterval)?.let { parsed ->
                update { it.copy(advertIntervalMinutes = parsed.minutes, originalAdvertIntervalMinutes = parsed.minutes) }
            }
            (helper.query(CLIResponse.Query.FLOOD_ADVERT_INTERVAL) { hadTimeout = true } as? CLIResponse.FloodAdvertInterval)
                ?.let { parsed -> update { it.copy(floodAdvertIntervalHours = parsed.hours, originalFloodAdvertIntervalHours = parsed.hours) } }
            (helper.query(CLIResponse.Query.FLOOD_MAX) { hadTimeout = true } as? CLIResponse.FloodMax)?.let { parsed ->
                update { it.copy(floodMaxHops = parsed.hops, originalFloodMaxHops = parsed.hops) }
            }
            if (hadTimeout) update { it.copy(behaviorError = true) }
        } finally {
            update { it.copy(isLoadingBehavior = false) }
        }
    }

    suspend fun applyBehaviorSettings() {
        val current = _state.value
        val validation = NodeSettingsValidation.validateBehaviorFields(
            current.advertIntervalMinutes, current.floodAdvertIntervalHours, current.floodMaxHops,
        )
        update {
            it.copy(
                advertIntervalError = validation.advertInterval,
                floodAdvertIntervalError = validation.floodInterval,
                floodMaxHopsError = validation.floodMaxHops,
            )
        }
        if (validation.hasErrors) return
        update { it.copy(isApplyingBehavior = true) }
        helper.update { it.copy(errorMessage = null) }
        val clearApplying = { update { it.copy(isApplyingBehavior = false) } }
        val flashed = helper.runApplying(clearApplying) {
            var allSucceeded = true
            _state.value.advertIntervalMinutes?.let { minutes ->
                if (minutes != _state.value.originalAdvertIntervalMinutes) {
                    if (helper.isOk(helper.sendAndWait("set advert.interval $minutes"))) {
                        update { it.copy(originalAdvertIntervalMinutes = minutes) }
                    } else allSucceeded = false
                }
            }
            _state.value.floodAdvertIntervalHours?.let { hours ->
                if (hours != _state.value.originalFloodAdvertIntervalHours) {
                    if (helper.isOk(helper.sendAndWait("set flood.advert.interval $hours"))) {
                        update { it.copy(originalFloodAdvertIntervalHours = hours) }
                    } else allSucceeded = false
                }
            }
            _state.value.floodMaxHops?.let { hops ->
                if (hops != _state.value.originalFloodMaxHops) {
                    if (helper.isOk(helper.sendAndWait("set flood.max $hops"))) {
                        update { it.copy(originalFloodMaxHops = hops) }
                    } else allSucceeded = false
                }
            }
            if (allSucceeded) {
                helper.flashSuccess(
                    { v -> update { it.copy(isApplyingBehavior = v) } },
                    { v -> update { it.copy(behaviorApplySuccess = v) } },
                )
                true
            } else {
                helper.update { it.copy(errorMessage = helper.someSettingsFailed()) }
                false
            }
        }
        if (flashed != true) clearApplying()
    }
}
