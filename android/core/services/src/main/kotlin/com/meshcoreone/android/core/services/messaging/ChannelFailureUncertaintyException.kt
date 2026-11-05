// AndroidOnly: WP-208 Report an unavailable or retired channel failure grant without mutating by ID.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.DirectAckRejection
import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID

class ChannelFailureUncertaintyException(
    val token: SessionToken,
    val messageID: UUID,
    val rejection: DirectAckRejection?,
) : Exception("Channel failure capture is unavailable or no longer current; persisted work was retained")
