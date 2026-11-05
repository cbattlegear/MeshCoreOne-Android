// AndroidOnly: WP-208 Missing-contact cleanup cannot infer non-emission from absent dispatch history.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID

enum class DirectPreclaimFailureUncertaintyReason { MISSING_CONTACT, UNPROVED_FAILURE }

class DirectPreclaimFailureUncertaintyException(
    val token: SessionToken,
    val messageID: UUID,
    val reason: DirectPreclaimFailureUncertaintyReason = DirectPreclaimFailureUncertaintyReason.MISSING_CONTACT,
) : Exception("Direct message failure has no persisted non-emission proof; row retained ($reason)")
