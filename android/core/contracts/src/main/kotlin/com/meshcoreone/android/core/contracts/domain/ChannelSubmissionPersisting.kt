// AndroidOnly: WP-201 Durable exact channel admission evidence, independent of transport authorization.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.QueuedChannelAttempt
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant

enum class ChannelSubmissionPhase(val rawValue: Long) {
    ADMITTED(0), ACCEPTED_COMPLETED(1), RETIRED(2),
}

data class ChannelSubmissionEvidence(
    val submission: ChannelSubmission,
    val enqueuedAt: Instant,
    val sequence: Long,
    val phase: ChannelSubmissionPhase,
    val pending: PendingSendDTO?,
    val attempts: SnapshotList<QueuedChannelAttempt>,
)

enum class ChannelSubmissionUnknown { ABSENT, REDACTED }

sealed interface ChannelSubmissionReconciliation {
    data class Known(val evidence: ChannelSubmissionEvidence) : ChannelSubmissionReconciliation
    data class Unknown(val reason: ChannelSubmissionUnknown) : ChannelSubmissionReconciliation
}

enum class ChannelSubmissionConflict { IDENTITY_CONFLICT, UNKNOWN_LEGACY_SUBMISSION, MESSAGE_MISMATCH, REDACTED_SUBMISSION }

class ChannelSubmissionRejected(val pending: EntityKey, val reason: ChannelSubmissionConflict) : Exception(reason.name)

/**
 * A receipt proves admission, not READY, nonacceptance, delivery or permission to replay.
 * Missing evidence is UNKNOWN: these operations do not fence an original owner that has not entered storage.
 */
interface ChannelSubmissionPersisting {
    suspend fun admitChannelSubmission(submission: ChannelSubmission, enqueuedAt: Instant): ChannelSubmissionEvidence
    suspend fun reconcileChannelSubmission(submission: ChannelSubmission): ChannelSubmissionReconciliation
}
