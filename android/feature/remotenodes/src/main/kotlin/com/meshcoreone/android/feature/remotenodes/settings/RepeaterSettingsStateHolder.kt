// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterSettingsViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.cli.CLIResponse
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.dependencies.RepeaterAdminPort
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Behavior section (repeat mode, advert intervals, flood max) of the repeater settings screen. */
data class RepeaterBehaviorState(
    val advertIntervalMinutes: Long? = null,
    val floodAdvertIntervalHours: Long? = null,
    val floodMaxHops: Long? = null,
    val repeaterEnabled: Boolean? = null,
    val originalAdvertIntervalMinutes: Long? = null,
    val originalFloodAdvertIntervalHours: Long? = null,
    val originalFloodMaxHops: Long? = null,
    val originalRepeaterEnabled: Boolean? = null,
    val isLoadingBehavior: Boolean = false,
    val behaviorError: Boolean = false,
    val advertIntervalError: RemoteNodesText? = null,
    val floodAdvertIntervalError: RemoteNodesText? = null,
    val floodMaxHopsError: RemoteNodesText? = null,
    val behaviorApplySuccess: Boolean = false,
    val isBehaviorExpanded: Boolean = false,
) {
    val behaviorLoaded: Boolean get() = repeaterEnabled != null || advertIntervalMinutes != null
    val behaviorSettingsModified: Boolean
        get() = (repeaterEnabled != null && repeaterEnabled != originalRepeaterEnabled) ||
            (advertIntervalMinutes != null && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
            (floodAdvertIntervalHours != null && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
            (floodMaxHops != null && floodMaxHops != originalFloodMaxHops)
    internal val sectionComplete: Boolean
        get() = originalRepeaterEnabled != null && originalAdvertIntervalMinutes != null &&
            originalFloodAdvertIntervalHours != null && originalFloodMaxHops != null
}

/**
 * Repeater settings (Swift `RepeaterSettingsViewModel`): the shared [helper], the repeater-only
 * behavior section and [regions]. The admin service is read from [repeaterAdmin] at call time; a
 * null port mirrors a disconnected radio and commands no-op. [scope] runs Swift's detached
 * `Task { fetchNodeInfo() }`.
 */
class RepeaterSettingsStateHolder(
    clock: RemoteNodesClock,
    private val faults: RemoteNodeFaultClassifier,
    private val scope: CoroutineScope,
) {
    val helper = NodeSettingsStateHolder(clock, faults)
    val regions = RepeaterRegionsStateHolder(helper, faults)

    private val _state = MutableStateFlow(RepeaterBehaviorState())
    val state: StateFlow<RepeaterBehaviorState> = _state.asStateFlow()

    private var repeaterAdmin: () -> RepeaterAdminPort? = { null }
    private var isLoadingNodeInfo = false

    private fun update(transform: (RepeaterBehaviorState) -> RepeaterBehaviorState) = _state.update(transform)

    fun setRepeaterEnabled(value: Boolean?) = update { it.copy(repeaterEnabled = value) }
    fun setAdvertIntervalMinutes(value: Long?) = update { it.copy(advertIntervalMinutes = value) }
    fun setFloodAdvertIntervalHours(value: Long?) = update { it.copy(floodAdvertIntervalHours = value) }
    fun setFloodMaxHops(value: Long?) = update { it.copy(floodMaxHops = value) }
    fun setBehaviorExpanded(expanded: Boolean) = update { it.copy(isBehaviorExpanded = expanded) }

    /** Replaces only the CLI handler slot (shared with the status surface), then tears down the helper. */
    fun cleanup() {
        repeaterAdmin()?.setCLIHandler { _, _ -> }
        helper.cleanup()
    }

    fun configure(repeaterAdmin: () -> RepeaterAdminPort?, session: RemoteNodeSessionDTO) {
        this.repeaterAdmin = repeaterAdmin
        val service = repeaterAdmin() ?: return
        helper.configure(
            session = session,
            sendCommand = { key, command, timeout -> service.sendCommand(key, command, timeout) },
            sendRawCommand = { key, command, timeout -> service.sendRawCommand(key, command, timeout) },
        )
        helper.setName(session.name)
        helper.onPreFetchNodeInfo = { fetchNodeInfo() }
        registerBehaviorLateRecovery()
        service.setCLIHandler { message, _ -> helper.handleCommonLateResponse(message.text) }
        // Detached so configure returns without waiting on the owner-info round trip.
        scope.launch { fetchNodeInfo() }
    }

    /** The node-CLI send function with this session pre-bound, or null when not connected. */
    fun makeNodeCLISend(session: RemoteNodeSessionDTO): (suspend (command: String, timeout: Duration) -> String)? {
        val service = repeaterAdmin() ?: return null
        val key = EntityKey(session.radioId, session.id)
        return { command, timeout -> service.sendRawCommand(key, command, timeout) }
    }

    private suspend fun fetchNodeInfo() {
        val session = helper.state.value.session
        val service = repeaterAdmin()
        if (isLoadingNodeInfo || session == null || service == null) return
        isLoadingNodeInfo = true
        try {
            val response = service.requestOwnerInfo(EntityKey(session.radioId, session.id), null)
            helper.setNodeInfo(response.firmwareVersion, response.nodeName, response.ownerInfo)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Swift logs "Failed to fetch node info via binary" and leaves the fields to the CLI fallbacks.
        } finally {
            isLoadingNodeInfo = false
        }
    }

    private fun registerBehaviorLateRecovery() {
        helper.registerLateRecovery(CLIResponse.Query.REPEAT_MODE) { value ->
            val enabled = (value as? CLIResponse.RepeatMode)?.enabled ?: return@registerLateRecovery
            recovered { it.copy(repeaterEnabled = enabled, originalRepeaterEnabled = enabled) }
        }
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

    private fun recovered(transform: (RepeaterBehaviorState) -> RepeaterBehaviorState) = update {
        val next = transform(it)
        next.copy(behaviorError = !next.sectionComplete)
    }

    suspend fun fetchBehaviorSettings() {
        update { it.copy(isLoadingBehavior = true, behaviorError = false) }
        var hadTimeout = false
        try {
            (helper.query(CLIResponse.Query.REPEAT_MODE) { hadTimeout = true } as? CLIResponse.RepeatMode)?.let { parsed ->
                update { it.copy(repeaterEnabled = parsed.enabled, originalRepeaterEnabled = parsed.enabled) }
            }
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
        helper.update { it.copy(isApplying = true, errorMessage = null) }
        val flashed = helper.runApplying {
            var allSucceeded = true
            _state.value.repeaterEnabled?.let { enabled ->
                if (enabled != _state.value.originalRepeaterEnabled) {
                    if (helper.isOk(helper.sendAndWait("set repeat ${if (enabled) "on" else "off"}"))) {
                        update { it.copy(originalRepeaterEnabled = enabled) }
                    } else allSucceeded = false
                }
            }
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
                    { v -> helper.update { it.copy(isApplying = v) } },
                    { v -> update { it.copy(behaviorApplySuccess = v) } },
                )
                true
            } else {
                helper.update { it.copy(errorMessage = helper.someSettingsFailed()) }
                false
            }
        }
        if (flashed != true) helper.update { it.copy(isApplying = false) }
    }
}
