// AndroidOnly: WP-208 Failure denied by atomic radio evidence; no ACK or non-emission is inferred.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.DirectAckRadioNonfailingReason
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID

class DirectAckFailureNonsettlementException(
    val token: SessionToken,
    val messageID: UUID,
    val reasons: SnapshotSet<DirectAckRadioNonfailingReason>,
) : Exception("Direct message failure cannot be established: ${reasons.joinToString { it.name }}")
