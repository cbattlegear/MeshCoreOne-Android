// PortedFrom: MC1Services/Sources/MC1Services/Services/AppStorageKey.swift@db14559b39d32322b06477c6ae676112f583db50
// Source consumers retain raw selections, notification choice presence and explicit theme correction.
package com.meshcoreone.android.core.datastore

import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class AppearanceSelection(val themeID: String, val colorSchemePreference: String)
data class AppearanceResolution(val selection: AppearanceSelection, val correctedKeys: SnapshotList<String>)

class AppearancePreferenceStore(private val preferences: PreferenceStore) {
    val selections: Flow<AppearanceSelection> =
        preferences.snapshots.map(::appearance).distinctUntilChanged()

    suspend fun resolveStoredSelection(): AppearanceResolution {
        val corrected = mutableListOf<String>()
        val result = preferences.update {
            val storedTheme = snapshot.stored(AppearanceStorageKey.selectedThemeID)
            val storedScheme = snapshot.stored(AppearanceStorageKey.appColorSchemePreference)
            if (storedTheme != null && storedTheme !in UNLOCKED_THEME_IDS) {
                this[AppearanceStorageKey.selectedThemeID] = "default"
                corrected += AppearanceStorageKey.selectedThemeID.rawValue
            }
            if (storedScheme != null && storedScheme !in COLOR_SCHEMES) {
                this[AppearanceStorageKey.appColorSchemePreference] = "system"
                corrected += AppearanceStorageKey.appColorSchemePreference.rawValue
            }
        }
        return AppearanceResolution(appearance(result), corrected.snapshot())
    }

    suspend fun setTheme(themeID: String) {
        if (themeID !in UNLOCKED_THEME_IDS) {
            preferences.reject(StorageProblem.InvalidPreference(AppearanceStorageKey.selectedThemeID.rawValue), StorageOperation.WRITE)
        }
        preferences.set(AppearanceStorageKey.selectedThemeID, themeID)
    }

    suspend fun setColorScheme(raw: String) {
        if (raw !in COLOR_SCHEMES) {
            preferences.reject(StorageProblem.InvalidPreference(AppearanceStorageKey.appColorSchemePreference.rawValue), StorageOperation.WRITE)
        }
        preferences.set(AppearanceStorageKey.appColorSchemePreference, raw)
    }

    companion object {
        val UNLOCKED_THEME_IDS: SnapshotList<String> = listOf(
            "default", "ember", "fern", "marine", "olive", "lavender", "sakura", "solarized", "nord", "catppuccin",
            "gradient", // AndroidOnly: not in the pinned Swift registry
        ).snapshot()
        val COLOR_SCHEMES: SnapshotList<String> = listOf("system", "light", "dark").snapshot()

        private fun appearance(snapshot: PreferenceSnapshot) = AppearanceSelection(
            snapshot[AppearanceStorageKey.selectedThemeID].takeIf { it in UNLOCKED_THEME_IDS } ?: "default",
            snapshot[AppearanceStorageKey.appColorSchemePreference].takeIf { it in COLOR_SCHEMES } ?: "system",
        )
    }
}

class NotificationPreferenceStore private constructor(
    private val store: PreferenceStore,
    initial: NotificationPreferences,
) : NotificationPreferencesPort {
    private val values = MutableStateFlow(initial)
    private val status = MutableStateFlow<StoreState<NotificationPreferences>>(StoreState.Ready(initial))
    override val preferences: StateFlow<NotificationPreferences> = values.asStateFlow()
    val states: StateFlow<StoreState<NotificationPreferences>> = status.asStateFlow()

    override suspend fun update(preferences: NotificationPreferences) {
        store.update { putNotifications(preferences) }
    }

    suspend fun setDiscoveryEnabled(enabled: Boolean) {
        store.update {
            val current = notifications(snapshot)
            val updated = current.enablingDiscovery(
                enabled, snapshot.contains(AppStorageKey.notifyNewContactsContact),
                snapshot.contains(AppStorageKey.notifyNewContactsRepeater),
                snapshot.contains(AppStorageKey.notifyNewContactsRoom),
            )
            this[AppStorageKey.notifyNewContacts] = updated.newContactDiscoveredEnabled
            if (updated.discoveryContactEnabled != current.discoveryContactEnabled ||
                (enabled && !current.newContactDiscoveredEnabled &&
                    !snapshot.contains(AppStorageKey.notifyNewContactsContact) &&
                    !snapshot.contains(AppStorageKey.notifyNewContactsRepeater) &&
                    !snapshot.contains(AppStorageKey.notifyNewContactsRoom))
            ) {
                this[AppStorageKey.notifyNewContactsContact] = updated.discoveryContactEnabled
                this[AppStorageKey.notifyNewContactsRepeater] = updated.discoveryRepeaterEnabled
                this[AppStorageKey.notifyNewContactsRoom] = updated.discoveryRoomEnabled
            }
        }
    }

    companion object {
        internal suspend fun create(store: PreferenceStore, scope: CoroutineScope): NotificationPreferenceStore {
            val result = NotificationPreferenceStore(store, notifications(store.snapshot()))
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                store.states.collect { state ->
                    when (state) {
                        StoreState.Loading -> Unit
                        is StoreState.Ready -> {
                            val value = notifications(state.value)
                            result.values.value = value
                            result.status.value = StoreState.Ready(value)
                        }
                        is StoreState.Failed -> result.status.value = state
                    }
                }
            }
            return result
        }

        private fun notifications(snapshot: PreferenceSnapshot) = NotificationPreferences(
            snapshot[AppStorageKey.notifyContactMessages], snapshot[AppStorageKey.notifyChannelMessages],
            snapshot[AppStorageKey.notifyRoomMessages], snapshot[AppStorageKey.notifyNewContacts],
            snapshot[AppStorageKey.notifyNewContactsContact], snapshot[AppStorageKey.notifyNewContactsRepeater],
            snapshot[AppStorageKey.notifyNewContactsRoom], snapshot[AppStorageKey.notifyReactions],
            snapshot[AppStorageKey.notificationSoundEnabled], snapshot[AppStorageKey.notificationBadgeEnabled],
            snapshot[AppStorageKey.notifyLowBattery],
        )

        private fun PreferenceEditor.putNotifications(values: NotificationPreferences) {
            this[AppStorageKey.notifyContactMessages] = values.contactMessagesEnabled
            this[AppStorageKey.notifyChannelMessages] = values.channelMessagesEnabled
            this[AppStorageKey.notifyRoomMessages] = values.roomMessagesEnabled
            this[AppStorageKey.notifyNewContacts] = values.newContactDiscoveredEnabled
            this[AppStorageKey.notifyNewContactsContact] = values.discoveryContactEnabled
            this[AppStorageKey.notifyNewContactsRepeater] = values.discoveryRepeaterEnabled
            this[AppStorageKey.notifyNewContactsRoom] = values.discoveryRoomEnabled
            this[AppStorageKey.notifyReactions] = values.reactionNotificationsEnabled
            this[AppStorageKey.notificationSoundEnabled] = values.soundEnabled
            this[AppStorageKey.notificationBadgeEnabled] = values.badgeEnabled
            this[AppStorageKey.notifyLowBattery] = values.lowBatteryEnabled
        }
    }
}
