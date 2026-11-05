// AndroidOnly: WP-305 Feature-owned slice of AppState.donateDeviceMenuTipIfOnValidTab (AppState itself is not owned by WP-305).
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.contracts.AppTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Post-onboarding "device menu" tip hand-off: donates when the user is on Chats/Nodes/Map,
 * otherwise leaves the donation pending until they navigate there.
 */
class DeviceMenuTipCoordinator(
    private val selectedTab: () -> AppTab,
    private val donate: suspend () -> Unit,
) {
    private val pending = MutableStateFlow(false)
    val pendingDonation: StateFlow<Boolean> = pending.asStateFlow()

    val isOnValidTab: Boolean
        get() = selectedTab() in VALID_TABS

    fun markPending(value: Boolean) { pending.value = value }

    suspend fun donateIfOnValidTab() {
        if (isOnValidTab) {
            pending.value = false
            donate()
        } else {
            pending.value = true
        }
    }

    suspend fun donateUnconditionally() {
        pending.value = false
        donate()
    }

    private companion object {
        val VALID_TABS = setOf(AppTab.CHATS, AppTab.NODES, AppTab.MAP)
    }
}
