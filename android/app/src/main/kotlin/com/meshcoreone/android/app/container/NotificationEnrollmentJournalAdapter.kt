// AndroidOnly: WP-303 Exact coordinator mapping to WP-401's real loss-aware generation journal.
package com.meshcoreone.android.app.container

import android.content.Context
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator as Enrollment
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyEnrollmentStore as Store
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyLimits
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore
import java.util.UUID

/** Limits are required inputs, not an implicit runtime retention/expiry policy. */
internal class NotificationEnrollmentJournalAdapter(
    context: Context,
    private val replyLimits: NotificationReplyLimits,
    private val receiptBudget: Long,
    private val issuanceLimits: NotificationReplyStagingStore.IssuanceLimits,
    private val unboundLimits: NotificationReplyLimits,
) : Enrollment.Journal {
    private val application = context.applicationContext

    private fun store(operation: Enrollment.Operation) = Store(
        application, operation.storeId, replyLimits, receiptBudget, issuanceLimits, unboundLimits,
    )

    override suspend fun prepare(operation: Enrollment.Operation): Enrollment.JournalResult =
        store(operation).prepare(operation.toStoreOperation()).toCoordinatorResult()

    override suspend fun reconcile(
        operation: Enrollment.Operation,
        expectedNonce: UUID,
    ): Enrollment.JournalResult =
        store(operation).reconcile(operation.toStoreOperation(), expectedNonce).toCoordinatorResult()

    override suspend fun bind(
        preparing: Enrollment.CreationReceipt,
        witnessId: UUID,
    ): Enrollment.JournalResult = store(preparing.operation).bind(
        preparing.toStoreReceipt(),
        Store.WitnessAcceptance(preparing.operation.toStoreOperation(), preparing.creationNonce, witnessId),
    ).toCoordinatorResult()
}

private fun Enrollment.Operation.toStoreOperation() = Store.EnrollmentOperation(operationId, storeId, creationNonce)

private fun Store.EnrollmentOperation.toCoordinatorOperation() = Enrollment.Operation(operationId, storeId, creationNonce)

private fun Enrollment.CreationReceipt.toStoreReceipt() = Store.CreationReceipt(
    operation.toStoreOperation(), revision,
    when (phase) {
        Enrollment.Phase.PREPARING -> Store.Phase.PREPARING
        Enrollment.Phase.BOUND -> Store.Phase.BOUND
    },
    creationNonce, witnessId,
)

private fun Store.CreationReceipt.toCoordinatorReceipt() = Enrollment.CreationReceipt(
    operation.toCoordinatorOperation(), revision,
    when (state) {
        Store.Phase.PREPARING -> Enrollment.Phase.PREPARING
        Store.Phase.BOUND -> Enrollment.Phase.BOUND
    },
    creationNonce, witnessId,
)

private fun Store.EnrollmentResult.toCoordinatorResult(): Enrollment.JournalResult = when (this) {
    is Store.EnrollmentResult.Preparing -> Enrollment.JournalResult.Observed(receipt.toCoordinatorReceipt())
    is Store.EnrollmentResult.AlreadyPreparing -> Enrollment.JournalResult.Observed(receipt.toCoordinatorReceipt())
    is Store.EnrollmentResult.Bound -> Enrollment.JournalResult.Observed(receipt.toCoordinatorReceipt())
    is Store.EnrollmentResult.AlreadyBound -> Enrollment.JournalResult.Observed(receipt.toCoordinatorReceipt())
    is Store.EnrollmentResult.Observed -> Enrollment.JournalResult.Observed(receipt.toCoordinatorReceipt())
    is Store.EnrollmentResult.Indeterminate -> Enrollment.JournalResult.Indeterminate(operation.toCoordinatorOperation())
    is Store.EnrollmentResult.NotRetained -> Enrollment.JournalResult.NotRetained(when (reason) {
        Store.Failure.INVALID_LIMITS, Store.Failure.POLICY_MISMATCH -> Enrollment.Failure.INVALID_RECEIPT
        Store.Failure.MISSING_STORAGE, Store.Failure.NOT_BOUND,
        Store.Failure.INVALID_TEXT, Store.Failure.RECORD_CAPACITY, Store.Failure.PRIVATE_DATA_CAPACITY ->
            Enrollment.Failure.NOT_RETAINED
        Store.Failure.EXISTING_UNATTRIBUTED_STORAGE, Store.Failure.MISMATCH -> Enrollment.Failure.IDENTITY_MISMATCH
        Store.Failure.CORRUPT_STORAGE -> Enrollment.Failure.CORRUPT_STORAGE
        Store.Failure.STORAGE_UNAVAILABLE -> Enrollment.Failure.STORAGE_UNAVAILABLE
    })
}
