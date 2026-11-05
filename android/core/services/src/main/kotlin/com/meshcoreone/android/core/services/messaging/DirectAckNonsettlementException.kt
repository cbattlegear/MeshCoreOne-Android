// AndroidOnly: WP-208 Typed delivery uncertainty; not a transport rejection or permission to replay.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.DirectAckRadioNonsettlingReason
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID

class DirectAckNonsettlementException(
    val token: SessionToken,
    val messageID: UUID,
    val acknowledgedCode: UInt,
    val reasons: SnapshotSet<DirectAckRadioNonsettlingReason>,
) : Exception("Direct ACK delivery cannot be verified: ${reasons.joinToString { it.name }}")
