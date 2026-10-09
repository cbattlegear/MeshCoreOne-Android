// AndroidOnly: WP-402 Android 16 QPR1 Live Update promotion is requested only for a qualifying activity.
package com.meshcoreone.android.platform.notifications.status

import android.os.Build

/** Why a notification stays a standard ongoing notification. */
enum class LiveUpdateDenial { PLATFORM_TOO_OLD, NOT_IN_PROGRESS, NOT_USER_INITIATED, PERMISSION_NOT_GRANTED }

sealed interface LiveUpdateDecision {
    data object Promote : LiveUpdateDecision
    data class Standard(val reason: LiveUpdateDenial) : LiveUpdateDecision
}

/**
 * Live Updates are for user-initiated, time-bound activities the user is waiting on. A steady
 * "connected" state, an automatic reconnect, a disconnected or error state is none of those, so it is
 * never promoted. Eligible: API 36.1+, the user's own connect request, still connecting or syncing, and
 * the promotion grant held (`NotificationManager.canPostPromotedNotifications`). Promotion ends by
 * itself when the status leaves the in-progress states, because it is re-evaluated on every update.
 */
object LiveUpdateEligibility {
    const val MIN_SDK_FULL = 3_600_001

    fun evaluate(status: ConnectionStatus, sdkIntFull: Int, promotionGranted: Boolean): LiveUpdateDecision = when {
        sdkIntFull < MIN_SDK_FULL -> LiveUpdateDecision.Standard(LiveUpdateDenial.PLATFORM_TOO_OLD)
        !status.inProgress -> LiveUpdateDecision.Standard(LiveUpdateDenial.NOT_IN_PROGRESS)
        !status.userInitiated -> LiveUpdateDecision.Standard(LiveUpdateDenial.NOT_USER_INITIATED)
        !promotionGranted -> LiveUpdateDecision.Standard(LiveUpdateDenial.PERMISSION_NOT_GRANTED)
        else -> LiveUpdateDecision.Promote
    }
}

/**
 * Returns the full platform version without linking the API-36.1 field on older releases.
 * Full SDK integers encode `major * 100000 + minor`; API 36.0 therefore remains below 36.1.
 */
internal object PlatformSdkVersion {
    fun currentFull(): Int = try {
        Build.VERSION::class.java.getField("SDK_INT_FULL").getInt(null)
    } catch (_: ReflectiveOperationException) {
        Build.VERSION.SDK_INT * 100_000
    } catch (_: SecurityException) {
        Build.VERSION.SDK_INT * 100_000
    }
}
