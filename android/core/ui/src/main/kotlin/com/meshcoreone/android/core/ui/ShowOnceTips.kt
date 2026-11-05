// PortedFrom: MC1/Tips/DeviceMenuTip.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Tips/LiveActivityTip.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/MainSidebarView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Components/BLEStatusIndicatorView.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: atomic process-owned DataStore claims; no permission or Live Update eligibility claim.
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings

enum class SharedTip(
    val sourceId: String,
    val eventId: String,
    val titleResource: Int,
    val messageResource: Int,
) {
    DEVICE_MENU("DeviceMenuTip", "hasCompletedOnboarding",
        AppChatsStrings.chatsTipDeviceMenuTitle, AppChatsStrings.chatsTipDeviceMenuMessage),
    LIVE_STATUS("LiveActivityTip", "radioConnected",
        AppSettingsStrings.liveActivityTipTitle, AppSettingsStrings.liveActivityTipMessage);

    internal val eventKey get() = PreferenceKey.BooleanKey("tips.event.$eventId", false)
    internal val displayedKey get() = PreferenceKey.BooleanKey("tips.$sourceId.displayed", false)
}

data class TipHostState(
    val selectedTab: AppTab,
    val deviceMenuVisible: Boolean,
    val connectedDevicePresent: Boolean,
    val liveStatusPresented: Boolean,
    val overlayQuiescent: Boolean = true,
    val taskInProgress: Boolean = false,
) {
    fun eligible(tip: SharedTip): Boolean = overlayQuiescent && !taskInProgress && when (tip) {
        SharedTip.DEVICE_MENU -> deviceMenuVisible && connectedDevicePresent
        SharedTip.LIVE_STATUS -> liveStatusPresented
    }
}

class ShowOnceTips(private val preferences: PreferenceStore) {
    private val pendingCompletion = PreferenceKey.StringKey("onboarding.completion.pending", "")
    private val finalizedCompletion = PreferenceKey.StringKey("onboarding.completion.finalized", "")

    suspend fun stageOnboardingCompletion(attemptId: String) {
        preferences.set(pendingCompletion, attemptId)
    }

    /** Legacy Boolean writes have no attempt identity, even when the value is unchanged. */
    suspend fun persistOnboardingFlag(completed: Boolean) {
        preferences.update {
            this[AppStorageKey.hasCompletedOnboarding] = completed
            remove(finalizedCompletion)
            remove(pendingCompletion)
        }
    }

    /** Commits the first-run gate and its pending show-once event as one preference transaction. */
    suspend fun completeOnboarding(attemptId: String? = null) {
        preferences.update {
            this[AppStorageKey.hasCompletedOnboarding] = true
            this[SharedTip.DEVICE_MENU.eventKey] = true
            if (attemptId != null) this[finalizedCompletion] = attemptId else remove(finalizedCompletion)
            remove(pendingCompletion)
        }
    }

    suspend fun isOnboardingCompletionFinalized(attemptId: String): Boolean {
        val snapshot = preferences.snapshot()
        return snapshot[finalizedCompletion] == attemptId && snapshot[AppStorageKey.hasCompletedOnboarding] &&
            snapshot[SharedTip.DEVICE_MENU.eventKey]
    }

    suspend fun donateCompletedOnboarding() = donate(SharedTip.DEVICE_MENU)
    suspend fun donateRadioConnected() = donate(SharedTip.LIVE_STATUS)

    private suspend fun donate(tip: SharedTip) {
        preferences.update { this[tip.eventKey] = true }
    }

    suspend fun claim(tip: SharedTip, currentHost: () -> TipHostState): Boolean {
        var claimed = false
        preferences.update {
            val stored = snapshot
            if (currentHost().eligible(tip) && stored[tip.eventKey] && !stored[tip.displayedKey]) {
                this[tip.displayedKey] = true
                claimed = true
            }
        }
        return claimed
    }

    suspend fun hasDisplayed(tip: SharedTip): Boolean = preferences.get(tip.displayedKey)
}
