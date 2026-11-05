// AndroidOnly: WP-208 Conditional in-memory channel authority; not Room, migration or durability evidence.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecordingChannelFailureAuthority(private val store: RecordingStore) : ChannelFailureAuthority {
    private data class Preparation(val key: EntityKey, val pendingID: UUID?, val timestamp: UInt?)
    private class Capture(
        val lease: DirectAckLease, val id: UUID, val key: EntityKey, val pendingID: UUID?, val arguments: Any,
    ) {
        lateinit var current: Attempt
        val operations = linkedMapOf<UUID, Operation>()
    }
    private class Attempt(
        val capture: Capture, var messageRevision: Long, val pendingRevision: Long?,
    ) : ChannelFailureAttempt {
        var retired = false
    }
    private data class Operation(
        val source: Attempt, val kind: ChannelFailureOperation, val successor: Attempt? = null,
    )
    private val captures = mutableMapOf<UUID, Capture>()
    private val handles = mutableSetOf<Attempt>()
    private val mutations = Mutex()

    override suspend fun activateChannelFailureSession(token: SessionToken, requestId: UUID) =
        store.directAckAuthority.activateDirectAckSession(token, requestId)

    override suspend fun revokeChannelFailureSession(lease: DirectAckLease) =
        store.directAckAuthority.revokeDirectAckSession(lease)

    private fun pendingKey(capture: Capture) = capture.pendingID?.let { EntityKey(capture.key.radioId, it) }

    private fun permission(attempt: Attempt): DirectAckPermission {
        val capture = attempt.capture
        return when {
            !store.directAckAuthority.isCurrentLease(capture.lease, capture.key.radioId) -> DirectAckPermission.STALE_LEASE
            store.messages[capture.key] == null -> DirectAckPermission.MISSING
            attempt.retired || capture.current !== attempt ||
                store.messageRevision(capture.key) != attempt.messageRevision ||
                pendingKey(capture)?.let { store.pendingRevision(it) != attempt.pendingRevision || store.pending[it] == null } == true ->
                DirectAckPermission.INVALIDATED
            else -> DirectAckPermission.CURRENT
        }
    }

    private fun receipt(attempt: Attempt): ChannelFailureReceipt {
        val capture = attempt.capture
        val permission = permission(attempt)
        val current = permission == DirectAckPermission.CURRENT
        return ChannelFailureReceipt(capture.id, capture.key, store.messages[capture.key].takeIf { current },
            capture.pendingID, pendingKey(capture)?.let { store.pending[it] }.takeIf { current }, permission,
            capture.operations.map { (id, value) -> ChannelFailureOperationReceipt(id, value.kind) }.snapshot())
    }

    private fun rejected(attempt: Attempt): DirectAckRejection? = when (permission(attempt)) {
        DirectAckPermission.CURRENT -> null
        DirectAckPermission.INVALIDATED -> DirectAckRejection.INVALIDATED
        DirectAckPermission.MISSING -> DirectAckRejection.MISSING
        DirectAckPermission.STALE_LEASE -> DirectAckRejection.STALE_LEASE
    }

    private fun resolve(handle: ChannelFailureAttempt) = (handle as? Attempt)?.takeIf { it in handles }

    private fun replayCapture(
        lease: DirectAckLease, id: UUID, arguments: Any,
    ): ChannelFailureCaptureResult? {
        val capture = captures[id] ?: return null
        if (capture.lease !== lease || capture.arguments != arguments) {
            return ChannelFailureCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejected(capture.current)?.let { return ChannelFailureCaptureResult.Rejected(it) }
        return ChannelFailureCaptureResult.AlreadyCaptured(capture.current, receipt(capture.current))
    }

    private fun capture(
        lease: DirectAckLease, id: UUID, key: EntityKey, pendingID: UUID?, arguments: Any,
    ): ChannelFailureCaptureResult {
        val capture = Capture(lease, id, key, pendingID, arguments)
        val attempt = Attempt(capture, store.messageRevision(key), pendingID?.let {
            store.pendingRevision(EntityKey(key.radioId, it))
        })
        capture.current = attempt
        captures[id] = capture
        handles += attempt
        return ChannelFailureCaptureResult.Captured(attempt, receipt(attempt))
    }

    override suspend fun createChannelFailureAttempt(
        lease: DirectAckLease, creationRequestId: UUID, message: MessageDTO,
    ): ChannelFailureCaptureResult = mutations.withLock {
        replayCapture(lease, creationRequestId, message)?.let { return@withLock it }
        val key = EntityKey(message.radioId, message.id)
        store.authorityAccess("saveMessage")
        if (!store.directAckAuthority.isCurrentLease(lease, message.radioId)) {
            return@withLock ChannelFailureCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        }
        if (message.channelIndex == null || message.contactID != null || message.direction != MessageDirection.OUTGOING ||
            message.status != MessageStatus.PENDING) {
            return@withLock ChannelFailureCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (key in store.messages) return@withLock ChannelFailureCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        store.messages[key] = message
        capture(lease, creationRequestId, key, null, message)
    }

    override suspend fun prepareChannelFailureAttempt(
        lease: DirectAckLease, prepareRequestId: UUID, key: EntityKey, pendingSendId: UUID?, resendTimestamp: UInt?,
    ): ChannelFailureCaptureResult = mutations.withLock {
        val arguments = Preparation(key, pendingSendId, resendTimestamp)
        replayCapture(lease, prepareRequestId, arguments)?.let { return@withLock it }
        store.authorityAccess("fetchMessage")
        if (resendTimestamp != null) store.authorityAccess("updateMessageTimestamp")
        if (!store.directAckAuthority.isCurrentLease(lease, key.radioId)) {
            return@withLock ChannelFailureCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        }
        val message = store.messages[key]
            ?: return@withLock ChannelFailureCaptureResult.Rejected(DirectAckRejection.MISSING)
        if (message.channelIndex == null || message.contactID != null || message.direction != MessageDirection.OUTGOING ||
            message.status == MessageStatus.DELIVERED) {
            return@withLock ChannelFailureCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val pending = pendingSendId?.let { store.pending[EntityKey(key.radioId, it)] }
        if (pendingSendId != null && (pending == null || pending.kind != PendingSendKind.CHANNEL ||
                pending.messageID != key.id || pending.radioId != key.radioId || pending.channelIndex != message.channelIndex)) {
            return@withLock ChannelFailureCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (resendTimestamp != null) store.messages[key] = message.copy(timestamp = resendTimestamp)
        capture(lease, prepareRequestId, key, pendingSendId, arguments)
    }

    override suspend fun remapChannelFailureAttempt(
        attempt: ChannelFailureAttempt, requestId: UUID,
    ): ChannelFailureRemapResult = mutations.withLock {
        val value = resolve(attempt)
            ?: return@withLock ChannelFailureRemapResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val capture = value.capture
        capture.operations[requestId]?.let { operation ->
            if (operation.source !== value || operation.kind != ChannelFailureOperation.REMAPPED_TO_PENDING) {
                return@withLock ChannelFailureRemapResult.Rejected(DirectAckRejection.CONFLICT)
            }
            val successor = requireNotNull(operation.successor)
            rejected(successor)?.let { return@withLock ChannelFailureRemapResult.Rejected(it) }
            return@withLock ChannelFailureRemapResult.AlreadyRemapped(successor, receipt(successor))
        }
        rejected(value)?.let { return@withLock ChannelFailureRemapResult.Rejected(it) }
        if (store.messages[capture.key]?.status == MessageStatus.DELIVERED) {
            return@withLock ChannelFailureRemapResult.AlreadyDelivered(receipt(value))
        }
        store.authorityAccess("updateMessageStatusUnlessDelivered")
        rejected(value)?.let { return@withLock ChannelFailureRemapResult.Rejected(it) }
        val message = requireNotNull(store.messages[capture.key])
        store.messages[capture.key] = message.copy(status = MessageStatus.PENDING)
        val successor = Attempt(capture, store.messageRevision(capture.key), value.pendingRevision)
        handles += successor
        capture.current = successor
        capture.operations[requestId] = Operation(value, ChannelFailureOperation.REMAPPED_TO_PENDING, successor)
        ChannelFailureRemapResult.Remapped(successor, receipt(successor))
    }

    override suspend fun recordChannelFailure(
        attempt: ChannelFailureAttempt, requestId: UUID, cleanup: ChannelFailureCleanup,
    ): ChannelFailureResult = mutations.withLock {
        val value = resolve(attempt)
            ?: return@withLock ChannelFailureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val capture = value.capture
        val kind = if (cleanup == ChannelFailureCleanup.KEEP_PENDING) ChannelFailureOperation.FAILED
            else ChannelFailureOperation.FAILED_AND_PENDING_DELETED
        capture.operations[requestId]?.let {
            return@withLock if (it.source === value && it.kind == kind) ChannelFailureResult.AlreadyApplied(receipt(value))
                else ChannelFailureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejected(value)?.let { return@withLock ChannelFailureResult.Rejected(it) }
        if (store.messages[capture.key]?.status == MessageStatus.DELIVERED) {
            return@withLock ChannelFailureResult.AlreadyDelivered(receipt(value))
        }
        if (cleanup == ChannelFailureCleanup.DELETE_CAPTURED_PENDING && capture.pendingID == null) {
            return@withLock ChannelFailureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        store.authorityAccess("updateMessageStatusUnlessDelivered")
        if (cleanup == ChannelFailureCleanup.DELETE_CAPTURED_PENDING) store.authorityAccess("deletePendingSend")
        rejected(value)?.let { return@withLock ChannelFailureResult.Rejected(it) }
        val message = requireNotNull(store.messages[capture.key])
        store.messages[capture.key] = message.copy(status = MessageStatus.FAILED,
            failureSeen = if (message.status != MessageStatus.FAILED) false else message.failureSeen)
        if (cleanup == ChannelFailureCleanup.DELETE_CAPTURED_PENDING) {
            store.pending.remove(requireNotNull(pendingKey(capture)))
            value.retired = true
        }
        value.messageRevision = store.messageRevision(capture.key)
        capture.operations[requestId] = Operation(value, kind)
        ChannelFailureResult.Applied(receipt(value))
    }

    override suspend fun inspectChannelFailureAttempt(
        lease: DirectAckLease, captureRequestId: UUID,
    ): ChannelFailureObservation = mutations.withLock {
        val capture = captures[captureRequestId]?.takeIf { it.lease === lease }
            ?: return@withLock ChannelFailureObservation.Rejected(DirectAckRejection.MISSING)
        ChannelFailureObservation.Found(receipt(capture.current))
    }
}
