// PortedFrom: MC1Services/Sources/MC1Services/Services/PendingAck.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 immutable ACK snapshots and an owned register-before-send delivery receipt.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.DirectAckAttempt
import com.meshcoreone.android.core.contracts.domain.DirectAckMode
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

internal data class PendingAck(
    val messageID: UUID,
    val contactID: UUID,
    val ackCodes: SnapshotSet<Bytes>,
    val sentAt: Instant,
    val timeout: Double,
    val isDelivered: Boolean = false,
    val publicKey: Bytes? = null,
    val acknowledgement: MeshAcknowledgement? = null,
    val receipt: CompletableDeferred<MeshAcknowledgement> = CompletableDeferred(),
    val authority: DirectAckAttempt? = null,
)

internal data class MeshAcknowledgement(val code: Bytes, val tripTime: UInt?)

internal data class AcceptedDirectWire(val requestID: UUID, val info: MessageSentInfo)

internal class DirectSendClaim(
    val id: UUID,
    val messageID: UUID,
    val contactID: UUID,
    val publicKey: Bytes,
    val text: String,
    val isResend: Boolean,
    val pendingSendID: UUID? = null,
    val authorityMode: DirectAckMode = DirectAckMode.SEND,
) {
    var timestamp: UInt? = null
    var lastSentInfo: MessageSentInfo? = null
    var acknowledgement: MeshAcknowledgement? = null
    var retiredWithoutAcknowledgement = false
    var wireFinished = false
    var sendCountCommitted = false
    var resentPublished = false
    var nextWireIndex = 0L
    var floodAttempts = 0L
    var usingFlood = false
    var acceptedWire: AcceptedDirectWire? = null
}
