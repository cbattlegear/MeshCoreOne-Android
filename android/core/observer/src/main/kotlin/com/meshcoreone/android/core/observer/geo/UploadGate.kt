// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PositionResult
import com.meshcoreone.android.core.observer.upload.RecordLocation
import kotlinx.coroutines.CancellationException

enum class GateState { WAITING_FOR_FIX, INSIDE, OUTSIDE, LOCATION_OFF }

enum class RejectReason { LOCATION_OFF, NO_FIX, POOR_FIX, OUTSIDE }

sealed interface GateDecision {
    /** Upload with this position (the one the server will see). */
    data class Accept(val location: RecordLocation) : GateDecision
    /** Do not upload and do not queue. */
    data class Reject(val reason: RejectReason) : GateDecision
}

/** The limits the Prizm server applies to a record's position (contract "Packets"); the app drops what the server would drop. */
object ServerGpsLimits {
    const val MAX_ACCURACY_METERS = 50.0
    const val MAX_FIX_AGE_MS = 10_000L
}

/**
 * Decides, per packet or health sample, whether it may leave the phone, from the position resolved at its rx instant.
 * The Prizm server only records packets that have a position, so a record that cannot carry one is never uploaded:
 *  - sharing off: LOCATION_OFF, paused ("Upload paused: location is off"); checked first, fails closed if the setting throws;
 *  - no fix, or one the server would drop (accuracy over 50 m, older than 10 s, at 0,0): WAITING_FOR_FIX;
 *  - position outside ND/MN plus the 2 km buffer ([geofence], the server's own outline): OUTSIDE, paused, nothing queued;
 *  - otherwise INSIDE, [GateDecision.Accept] with the position.
 * Privacy zones are applied after the gate by `LocationRedactor` (a withheld record is not uploaded either).
 * [state] is volatile for the UI; there is a single writer.
 */
class UploadGate(private val geofence: Geofence, private val sharing: LocationSharing) {
    @Volatile var state: GateState = GateState.WAITING_FOR_FIX
        private set

    @Volatile private var sawAnyDecision = false

    /** Text for the ongoing notification while paused; null when uploading or before the first decision. */
    val notificationText: String?
        get() = when (state) {
            GateState.OUTSIDE -> NOTIFICATION_OUTSIDE
            GateState.LOCATION_OFF -> NOTIFICATION_LOCATION_OFF
            GateState.WAITING_FOR_FIX -> if (sawAnyDecision) NOTIFICATION_WAITING else null
            GateState.INSIDE -> null
        }

    fun admit(position: PositionResult): GateDecision {
        sawAnyDecision = true
        if (!sharingEnabled()) return reject(GateState.LOCATION_OFF, RejectReason.LOCATION_OFF)
        val location = (position as? PositionResult.Resolved)?.location ?: return reject(GateState.WAITING_FOR_FIX, RejectReason.NO_FIX)
        if (!usable(location)) return reject(GateState.WAITING_FOR_FIX, RejectReason.POOR_FIX)
        if (!geofence.contains(location.latitude, location.longitude)) return reject(GateState.OUTSIDE, RejectReason.OUTSIDE)
        state = GateState.INSIDE
        return GateDecision.Accept(location)
    }

    /**
     * Brings [state] in line with the sharing switch without a packet: called on the session timer so the notification and the
     * Settings screen show "Upload paused: location is off" as soon as the switch is flipped. When it is flipped back on, the state
     * is re-decided from [position] (the position now) instead of waiting for the next packet.
     */
    fun refreshSharing(position: PositionResult) {
        if (!sharingEnabled()) {
            sawAnyDecision = true
            state = GateState.LOCATION_OFF
        } else if (state == GateState.LOCATION_OFF) {
            admit(position)
        }
    }

    private fun usable(location: RecordLocation): Boolean =
        !(location.latitude == 0.0 && location.longitude == 0.0) &&
            location.hAccuracyMeters <= ServerGpsLimits.MAX_ACCURACY_METERS &&
            (location.fixAgeMs ?: 0L) <= ServerGpsLimits.MAX_FIX_AGE_MS

    private fun sharingEnabled(): Boolean = try {
        sharing.isEnabled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun reject(next: GateState, reason: RejectReason): GateDecision {
        state = next
        return GateDecision.Reject(reason)
    }

    companion object {
        const val NOTIFICATION_OUTSIDE = "Paused: outside ND and MN"
        const val NOTIFICATION_LOCATION_OFF = "Upload paused: location is off"
        const val NOTIFICATION_WAITING = "Paused: waiting for a location fix"
    }
}
