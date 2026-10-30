// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.contracts.domain.errors.SyncFault
import java.time.Instant

/** Current state of the sync coordinator. */
sealed interface SyncState {
    data object Idle : SyncState
    data class Syncing(val progress: SyncProgress) : SyncState
    data object Synced : SyncState

    /** Swift's equality treats every `.failed` as equal regardless of the error ("simplified equality"). */
    class Failed(val error: SyncCoordinatorError) : SyncState {
        override fun equals(other: Any?): Boolean = other is Failed
        override fun hashCode(): Int = Failed::class.hashCode()
        override fun toString(): String = "Failed(${error.message})"
    }

    /** Whether currently syncing. */
    val isSyncing: Boolean get() = this is Syncing
}

/** Progress information during sync. */
data class SyncProgress(val phase: SyncPhase, val current: Long, val total: Long)

/** Phases of the sync process. */
enum class SyncPhase { CONTACTS, CHANNELS, MESSAGES }

/** Phase-level sync outcome. */
sealed interface SyncPhaseStatus {
    data object Clean : SyncPhaseStatus
    data object Partial : SyncPhaseStatus
    data object Skipped : SyncPhaseStatus
    data class Failed(val reason: String) : SyncPhaseStatus

    val isClean: Boolean get() = this == Clean
}

/** Structured result for an initial or full sync. */
data class FullSyncResult(
    val contacts: SyncPhaseStatus,
    val channels: SyncPhaseStatus,
    val messages: SyncPhaseStatus,
    val channelRetryIndices: SnapshotList<UByte> = SnapshotList.empty(),
) {
    /** Contacts remain connection-critical; channel and message phases are degraded state. */
    val isConnectionUsable: Boolean get() = contacts == SyncPhaseStatus.Clean

    companion object {
        val SKIPPED = FullSyncResult(SyncPhaseStatus.Skipped, SyncPhaseStatus.Skipped, SyncPhaseStatus.Skipped)
    }
}

/** Errors from SyncCoordinator operations; messages are the Swift `LocalizedError` descriptions. */
sealed class SyncCoordinatorError(message: String) : Exception(message), SourceServiceFaultCarrier {
    override val sourceServiceFault: SyncFault
        get() = when (this) {
            is NotConnected -> SyncFault.NotConnected
            is SyncFailed -> SyncFault.SyncFailed(reason)
            is AlreadySyncing -> SyncFault.AlreadySyncing
        }

    class NotConnected : SyncCoordinatorError("Not connected to device.")
    class SyncFailed(val reason: String) : SyncCoordinatorError("Sync failed: $reason")
    class AlreadySyncing : SyncCoordinatorError("A sync is already in progress.")
}

/**
 * How to use a stored contact-sync watermark for one fetch round.
 * Does not rewrite storage; only decides the `since` filter for this round.
 */
sealed interface ContactWatermarkUse {
    /** No successful contact sync stamp yet. */
    data object None : ContactWatermarkUse

    /** Stamp is usable for incremental `since = watermark - 1`. */
    data class Incremental(val watermark: UInt) : ContactWatermarkUse

    /** Stamp is implausibly ahead of the reference; fetch without the stamp this round only. */
    data class Invalid(val stored: UInt) : ContactWatermarkUse
}

// AndroidOnly: WP-214 Recoverable receive-clock range failure; the sender stamp remains deliverable.
class TimestampRangeError(val receiveTime: Instant) :
    Exception("Receive time $receiveTime is outside UInt32 Unix seconds; retaining the original sender timestamp.")

/** Swift correction tuple, with the approved native recoverable receive-clock error. */
data class TimestampCorrection(
    val correctedTimestamp: UInt,
    val wasCorrected: Boolean,
    val rangeError: TimestampRangeError? = null,
)

/** Swift `(senderNodeName:, messageText:)` tuple from `parseChannelMessage`. */
data class ParsedChannelText(val senderNodeName: String?, val messageText: String)
