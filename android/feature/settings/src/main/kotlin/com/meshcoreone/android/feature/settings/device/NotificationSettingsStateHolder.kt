// PortedFrom: MC1/Views/Settings/Sections/NotificationSettingsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/NotificationPreferences.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.model.NotificationPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationSettingsState(
    val authorization: NotificationAuthorization = NotificationAuthorization.NOT_DETERMINED,
    val preferences: NotificationPreferences? = null,
) {
    /** The toggles only show once the system permission is granted; "not determined" offers Enable, "denied" offers Open Settings. */
    val showsToggles: Boolean get() = authorization == NotificationAuthorization.AUTHORIZED
    val showsDiscoveryChildren: Boolean get() = preferences?.newContactDiscoveredEnabled == true
}

/** Notification category toggles behind the runtime permission (`POST_NOTIFICATIONS`). */
class NotificationSettingsStateHolder(
    private val env: SettingsEnvironment,
    private val port: NotificationPreferencesPort,
    private val permission: NotificationPermissionPort,
    private val discoveryChoices: DiscoveryChoiceSource,
) {
    private val mutable = MutableStateFlow(NotificationSettingsState(preferences = port.preferences.value))
    private var observing: Job? = null
    val state: StateFlow<NotificationSettingsState> = mutable.asStateFlow()

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { port.preferences.collect { p -> mutable.update { it.copy(preferences = p) } } }
            refreshAuthorization()
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    /** `.task` and every return to the foreground (the user may have changed the system setting). */
    fun onResume() {
        env.scope.launch { refreshAuthorization() }
    }

    private suspend fun refreshAuthorization() {
        val status = permission.status()
        mutable.update { it.copy(authorization = status) }
    }

    fun requestAuthorization() {
        env.scope.launch {
            val granted = try {
                permission.request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                false
            }
            mutable.update { it.copy(authorization = if (granted) NotificationAuthorization.AUTHORIZED else NotificationAuthorization.DENIED) }
        }
    }

    private fun change(transform: (NotificationPreferences) -> NotificationPreferences) {
        val current = mutable.value.preferences ?: return
        val next = transform(current)
        mutable.update { it.copy(preferences = next) }
        env.scope.launch { port.update(next) }
    }

    fun onContactMessages(on: Boolean) = change { it.copy(contactMessagesEnabled = on) }
    fun onChannelMessages(on: Boolean) = change { it.copy(channelMessagesEnabled = on) }
    fun onRoomMessages(on: Boolean) = change { it.copy(roomMessagesEnabled = on) }
    fun onReactions(on: Boolean) = change { it.copy(reactionNotificationsEnabled = on) }
    fun onLowBattery(on: Boolean) = change { it.copy(lowBatteryEnabled = on) }
    fun onDiscoveryContact(on: Boolean) = change { it.copy(discoveryContactEnabled = on) }
    fun onDiscoveryRepeater(on: Boolean) = change { it.copy(discoveryRepeaterEnabled = on) }
    fun onDiscoveryRoom(on: Boolean) = change { it.copy(discoveryRoomEnabled = on) }

    /** First activation turns every child on, unless the user already chose some (keys never written before). */
    fun onNewContactDiscovered(on: Boolean) {
        val stored = discoveryChoices.storedChoices()
        change { it.enablingDiscovery(on, stored.contact, stored.repeater, stored.room) }
    }
}
