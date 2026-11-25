// AndroidOnly: WP-211 Companion Wi-Fi mutation and reboot evidence compared with current upstream.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.coroutines.CompletableDeferred

data class RadioWiFiResetObservation(
    val uptimeSeconds: UInt,
    val startedAt: Duration,
    val completedAt: Duration,
) {
    fun provesReset(since: RadioWiFiResetObservation): Boolean {
        if (!startedAt.isFinite() || !completedAt.isFinite() ||
            !since.startedAt.isFinite() || !since.completedAt.isFinite() ||
            since.startedAt > since.completedAt || startedAt > completedAt || startedAt < since.completedAt
        ) return false
        val elapsed = (completedAt - since.startedAt).toDouble(DurationUnit.SECONDS)
        val upperBound = elapsed * 1.01 + 2.0
        val periodMillis = 1L shl 32
        if (upperBound >= periodMillis / 1000.0 || uptimeSeconds.toDouble() > upperBound) return false
        val earlierLow = since.uptimeSeconds.toLong() * 1000
        val laterLow = uptimeSeconds.toLong() * 1000
        if (earlierLow >= periodMillis || laterLow >= periodMillis) return false
        val earlierHigh = minOf(earlierLow + 999, periodMillis - 1)
        val laterHigh = minOf(laterLow + 999, periodMillis - 1)
        val minimumProgression = when {
            laterLow <= earlierHigh && laterHigh >= earlierLow -> 0
            laterLow > earlierHigh -> laterLow - earlierHigh
            else -> periodMillis - earlierHigh + laterLow
        }
        return minimumProgression / 1000.0 > upperBound
    }
}

/**
 * Process-owned evidence for one radio. It must be shared across connection generations;
 * creating a new instance for each session would lose pending or uncertain writes.
 */
class RadioWiFiState(val radioId: RadioId) {
    class MutationToken internal constructor(internal val id: UUID, val revision: ULong)
    enum class MutationOutcome { ACCEPTED, REJECTED, UNCERTAIN }

    private data class ExpectedReboot(
        val id: UUID,
        val contextId: UUID,
        val revision: ULong,
        val observation: RadioWiFiResetObservation?,
        var submitted: Boolean = false,
        var linkEnded: Boolean = false,
    )

    private val lock = Any()
    private var revision = 0uL
    private var rebootPending = false
    private var admissionClosed = false
    private val mutations = mutableSetOf<UUID>()
    private var settlement = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var expectedReboot: ExpectedReboot? = null

    val isRebootPending: Boolean get() = synchronized(lock) { rebootPending }
    val expectedRebootId: UUID? get() = synchronized(lock) { expectedReboot?.id }
    fun stableRevision(): ULong? = synchronized(lock) {
        revision.takeIf { mutations.isEmpty() && !admissionClosed }
    }

    fun beginMutation(expectedRevision: ULong?): MutationToken? = synchronized(lock) {
        if (admissionClosed || expectedRevision != null &&
            (mutations.isNotEmpty() || revision != expectedRevision)
        ) return@synchronized null
        expectedReboot = null
        revision += 1u
        if (mutations.isEmpty()) settlement = CompletableDeferred()
        MutationToken(UUID.randomUUID(), revision).also { mutations.add(it.id) }
    }

    fun isUncontended(token: MutationToken): Boolean = synchronized(lock) { isUncontendedLocked(token) }
    private fun isUncontendedLocked(token: MutationToken): Boolean =
        !admissionClosed && mutations.size == 1 && token.id in mutations && revision == token.revision

    fun settle(token: MutationToken, outcome: MutationOutcome): ULong? {
        val result: ULong?
        val completed: CompletableDeferred<Unit>?
        synchronized(lock) {
            val uncontended = isUncontendedLocked(token)
            if (!mutations.remove(token.id)) return null
            revision += 1u
            if (outcome != MutationOutcome.REJECTED) rebootPending = true
            completed = settlement.takeIf { mutations.isEmpty() }
            result = revision.takeIf { uncontended && mutations.isEmpty() }
        }
        completed?.complete(Unit)
        return result
    }

    fun closeAdmission() = synchronized(lock) {
        admissionClosed = true
        expectedReboot = null
    }

    fun openAdmission() = synchronized(lock) { admissionClosed = false }

    /** Close admission first, so no new mutation can start after this wait observes an empty set. */
    suspend fun waitForMutationSettlement() {
        synchronized(lock) { settlement }.await()
    }

    fun adoptCanonicalEvidence(inheritedRebootPending: Boolean) = synchronized(lock) {
        rebootPending = rebootPending || inheritedRebootPending
        revision += 1u
        expectedReboot = null
    }

    fun prepareExpectedReboot(
        contextId: UUID,
        expectedRevision: ULong? = null,
        observation: RadioWiFiResetObservation? = null,
    ): UUID? = synchronized(lock) {
        if (expectedRevision != null && expectedRevision != revision ||
            admissionClosed || !rebootPending || mutations.isNotEmpty()
        ) return@synchronized null
        UUID.randomUUID().also { expectedReboot = ExpectedReboot(it, contextId, revision, observation) }
    }

    fun completeRebootSubmission(id: UUID, succeeded: Boolean) = synchronized(lock) {
        val candidate = expectedReboot
        if (candidate?.id == id) {
            if (succeeded) candidate.submitted = true else expectedReboot = null
        }
    }

    fun noteLinkEnded(contextId: UUID) = synchronized(lock) {
        expectedReboot?.takeIf { it.contextId == contextId }?.linkEnded = true
    }

    fun cancelExpectedReboot() = synchronized(lock) { expectedReboot = null }

    fun clearExpectedReboot(
        id: UUID,
        contextId: UUID,
        readRevision: ULong,
        observation: RadioWiFiResetObservation?,
    ): Boolean = synchronized(lock) {
        val candidate = expectedReboot ?: return@synchronized false
        if (candidate.id != id || candidate.contextId == contextId || !candidate.submitted || !candidate.linkEnded ||
            admissionClosed || candidate.observation == null || observation == null ||
            !observation.provesReset(candidate.observation) || mutations.isNotEmpty() ||
            candidate.revision != revision || readRevision != revision
        ) return@synchronized false
        rebootPending = false
        expectedReboot = null
        true
    }
}
