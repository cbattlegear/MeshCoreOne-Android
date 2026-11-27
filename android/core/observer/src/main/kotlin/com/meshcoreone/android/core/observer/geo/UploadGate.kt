// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import com.meshcoreone.android.core.observer.location.PositionResult

enum class GateState { WAITING_FOR_FIX, INSIDE, OUTSIDE }

enum class RejectReason { OUTSIDE, NO_FIX, NO_REGION }

sealed interface GateDecision {
    /** Upload, on the topic of [region] (`nd` or `mn`). */
    data class Accept(val region: String) : GateDecision
    /** Do not upload and do not queue. */
    data class Reject(val reason: RejectReason) : GateDecision
}

/**
 * Decides, per packet or health record, whether it may leave the phone, from the position resolved at its rx instant.
 *
 *  - INSIDE (position inside ND/MN plus the 2 km buffer): upload, region chosen from the position.
 *  - OUTSIDE: paused ("Paused: outside ND and MN"). Packets heard meanwhile are rejected, never queued.
 *  - No fix: accepted only when the last good fix is at most [lastGoodMaxAgeMs] (10 minutes) old and inside; otherwise
 *    rejected. The accepted record goes to the last good fix's region and carries no location.
 * Health records use the same rule. Thread safe for the single writer of [state]; [state] is volatile for the UI.
 */
class UploadGate(
    private val geofence: RegionGeofence,
    private val lastGoodMaxAgeMs: Long = DEFAULT_LAST_GOOD_MAX_AGE_MS,
) {
    @Volatile var state: GateState = GateState.WAITING_FOR_FIX
        private set

    /** Text for the ongoing notification while paused; null when uploading or before the first fix. */
    val notificationText: String?
        get() = when (state) {
            GateState.OUTSIDE -> NOTIFICATION_OUTSIDE
            GateState.WAITING_FOR_FIX -> NOTIFICATION_WAITING
            GateState.INSIDE -> null
        }.takeIf { state != GateState.WAITING_FOR_FIX || sawAnyDecision }

    @Volatile private var sawAnyDecision = false

    fun admit(position: PositionResult): GateDecision {
        sawAnyDecision = true
        return when (position) {
            is PositionResult.Resolved -> decide(position.location.latitude, position.location.longitude)
            is PositionResult.NoFix -> admitWithoutFix(position)
        }
    }

    private fun admitWithoutFix(noFix: PositionResult.NoFix): GateDecision {
        val last = noFix.lastGood
        val age = noFix.lastGoodAgeMs
        if (last == null || age == null || age > lastGoodMaxAgeMs) {
            state = GateState.WAITING_FOR_FIX
            return GateDecision.Reject(RejectReason.NO_FIX)
        }
        return decide(last.latitude, last.longitude)
    }

    private fun decide(latitude: Double, longitude: Double): GateDecision {
        if (!geofence.contains(latitude, longitude)) {
            state = GateState.OUTSIDE
            return GateDecision.Reject(RejectReason.OUTSIDE)
        }
        state = GateState.INSIDE
        val region = geofence.regionFor(latitude, longitude) ?: return GateDecision.Reject(RejectReason.NO_REGION)
        return GateDecision.Accept(region)
    }

    companion object {
        const val DEFAULT_LAST_GOOD_MAX_AGE_MS = 10 * 60_000L
        const val NOTIFICATION_OUTSIDE = "Paused: outside ND and MN"
        const val NOTIFICATION_WAITING = "Paused: waiting for a location fix"
    }
}
