// AndroidOnly: WP-208 Report accepted channel work whose original completion grant is no longer available.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.DirectAckRejection
import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID

class ChannelSendCompletionUncertaintyException(
    val token: SessionToken,
    val captureRequestID: UUID,
    val completionRequestID: UUID,
    val messageID: UUID,
    val rejection: DirectAckRejection?,
) : Exception("Channel transport acceptance was observed; original completion work retired without redispatch or replacement cleanup")
