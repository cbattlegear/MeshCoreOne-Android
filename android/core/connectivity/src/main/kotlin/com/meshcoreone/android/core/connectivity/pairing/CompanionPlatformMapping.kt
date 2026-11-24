// AndroidOnly: WP-206 Pure CompanionDeviceManager failure-reason mapping and chooser request correlation.
package com.meshcoreone.android.core.connectivity.pairing

import java.util.UUID

/** How a CDM `onFailure` maps onto the picker: a benign dismissal or a typed setup error. */
sealed interface CompanionFailureOutcome {
    data object Dismissed : CompanionFailureOutcome
    data class Failed(val error: CompanionSetupError) : CompanionFailureOutcome
}

object CompanionFailureMapping {
    /** `CompanionDeviceManager.RESULT_*` codes delivered by `onFailure(int, CharSequence)` (API 36+). */
    const val RESULT_CANCELED = 0
    const val RESULT_USER_REJECTED = 1
    const val RESULT_DISCOVERY_TIMEOUT = 2

    /** AOSP `REASON_*` strings delivered by `onFailure(CharSequence)` on API 31-35 (internal, not localized). */
    const val REASON_USER_REJECTED = "user_rejected"
    const val REASON_CANCELED = "canceled"
    const val REASON_DISCOVERY_TIMEOUT = "discovery_timeout"

    fun fromCode(code: Int): CompanionFailureOutcome = when (code) {
        RESULT_CANCELED, RESULT_USER_REJECTED -> CompanionFailureOutcome.Dismissed
        RESULT_DISCOVERY_TIMEOUT -> CompanionFailureOutcome.Failed(CompanionSetupError.DiscoveryTimeout())
        else -> CompanionFailureOutcome.Failed(CompanionSetupError.ConnectionFailed())
    }

    fun fromReason(reason: CharSequence?): CompanionFailureOutcome = when (reason?.toString()?.trim()) {
        REASON_USER_REJECTED, REASON_CANCELED -> CompanionFailureOutcome.Dismissed
        REASON_DISCOVERY_TIMEOUT -> CompanionFailureOutcome.Failed(CompanionSetupError.DiscoveryTimeout())
        else -> CompanionFailureOutcome.Failed(CompanionSetupError.ConnectionFailed())
    }
}

/**
 * Correlates chooser activity results with the request that launched them. A result is delivered
 * only to the request with the same id, so a late result can never resolve a newer request.
 */
class ChooserRequests<E : Any>(private val capacity: Int = 8) {
    private val lock = Any()
    private val open = linkedMapOf<UUID, E>()

    fun register(events: E): UUID = synchronized(lock) {
        val id = UUID.randomUUID()
        open[id] = events
        while (open.size > capacity) open.remove(open.keys.first())
        id
    }

    /** Removes and returns the request for [requestId]; `null` for unknown or already-finished ids. */
    fun take(requestId: UUID): E? = synchronized(lock) { open.remove(requestId) }

    fun finish(events: E) { synchronized(lock) { open.entries.removeAll { it.value === events } } }

    val size: Int get() = synchronized(lock) { open.size }
}
