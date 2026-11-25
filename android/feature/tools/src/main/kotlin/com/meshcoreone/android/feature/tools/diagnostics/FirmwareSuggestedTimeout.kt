// PortedFrom: MC1/Utilities/FirmwareSuggestedTimeout.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sanitizes the firmware `suggested_timeout_ms` hint used by trace, ping, and path-discovery
 * waits. Scales the hint for slack and clamps it into a per-use-case band. A missing hint (0) uses
 * the profile default so the wait does not expire immediately.
 */
object FirmwareSuggestedTimeout {
    const val MULTIPLIER: Double = 1.2
    private const val MILLISECONDS_PER_SECOND = 1000.0

    /** Per-use-case bounds; [graceSeconds] is fixed slack added to the scaled hint before clamping. */
    data class Profile(
        val minimumSeconds: Double,
        val defaultSeconds: Double,
        val maximumSeconds: Double,
        val graceSeconds: Double,
    ) {
        companion object {
            /** Single-neighbor ping to a direct contact; honors a small valid hint. */
            val ZERO_HOP = Profile(minimumSeconds = 1.0, defaultSeconds = 5.0, maximumSeconds = 30.0, graceSeconds = 0.0)

            /** Flood path discovery and multi-hop traces; grace covers return-leg jitter. */
            val FLOOD = Profile(minimumSeconds = 5.0, defaultSeconds = 30.0, maximumSeconds = 60.0, graceSeconds = 8.0)
        }
    }

    /** Floor for the Discover Path overall wait. */
    const val PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS: Double = 20.0

    /** Retransmit spacing multiplier on the firmware estimate. */
    const val PATH_DISCOVERY_RETRANSMIT_RTT_HEADROOM: Long = 2

    /** Minimum spacing between path-discovery resends. */
    val pathDiscoveryRetransmitFloor: Duration = 5.seconds

    /** Scaled hint before clamping. */
    fun candidateSeconds(suggestedTimeoutMs: UInt): Double =
        suggestedTimeoutMs.toDouble() / MILLISECONDS_PER_SECOND * MULTIPLIER

    /** Scaled hint plus profile grace, clamped into the profile band; a missing hint yields the default. */
    fun sanitizedSeconds(suggestedTimeoutMs: UInt, profile: Profile): Double {
        if (suggestedTimeoutMs == 0u) return profile.defaultSeconds
        val candidate = candidateSeconds(suggestedTimeoutMs) + profile.graceSeconds
        return minOf(maxOf(candidate, profile.minimumSeconds), profile.maximumSeconds)
    }

    /** Discover Path overall wait: flood-sanitized hint, at least the multi-hop minimum. */
    fun pathDiscoverySeconds(suggestedTimeoutMs: UInt): Double =
        maxOf(PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS, sanitizedSeconds(suggestedTimeoutMs, Profile.FLOOD))

    /** Spacing between path-discovery resends, or null when firmware gave no hint. */
    fun pathDiscoveryRetransmitInterval(suggestedTimeoutMs: UInt): Duration? {
        if (suggestedTimeoutMs == 0u) return null
        val headed = suggestedTimeoutMs.toLong() * PATH_DISCOVERY_RETRANSMIT_RTT_HEADROOM
        return maxOf(pathDiscoveryRetransmitFloor, headed.milliseconds)
    }
}
