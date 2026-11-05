// AndroidOnly: WP-215 Inactive structural parsing and atomic journal admission boundary.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Intent
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.StageResult
import java.util.UUID

/**
 * Unwired: neither authenticates a generation nor grants publication, dispatch or UI authority.
 * The caller must supply those prerequisites before live use. No receiver constructs this class.
 */
internal class NotificationReplyAdmission(
    private val parse: (Intent) -> NotificationReplyDeliveryParsing,
    private val admitIssued: suspend (UUID, UUID, String) -> StageResult,
) {
    constructor(adapter: NotificationReplyIssuanceAdapter, store: NotificationReplyStagingStore) :
        this(adapter::parseDelivery, store::admitIssued)

    sealed interface Result {
        data class ParsingRejected(
            val reason: NotificationReplyDeliveryParsing.ParseFailure,
            val inputStatus: NotificationReplyDeliveryParsing.InputStatus,
            val transientNotRetained: TransientNotRetained?,
        ) : Result

        /** Readable text rejected before any journal write; memory-only, not definitely unsent. */
        data class TransientNotRetained(val exactText: String) {
            override fun toString(): String = "TransientNotRetained(text=redacted)"
        }

        /**
         * Keeps the journal's exact outcome, not a send acknowledgement. Text exists in memory only;
         * Rejected does not imply unsent, and Indeterminate does not imply retained or retryable.
         */
        data class JournalAttempt(val exactText: String, val outcome: StageResult) : Result {
            override fun toString(): String = "JournalAttempt(text=redacted, outcome=$outcome)"
        }
    }

    suspend fun admit(intent: Intent): Result = when (val input = parse(intent)) {
        is NotificationReplyDeliveryParsing.Rejected -> Result.ParsingRejected(
            input.reason, input.inputStatus, input.readableExactText?.let { Result.TransientNotRetained(it) },
        )
        is NotificationReplyDeliveryParsing.ReadyForAdmission ->
            Result.JournalAttempt(input.exactText, admitIssued(input.storeId, input.token, input.exactText))
    }
}
