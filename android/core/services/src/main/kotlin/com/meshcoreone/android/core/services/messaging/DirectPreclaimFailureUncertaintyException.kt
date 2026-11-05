// AndroidOnly: WP-208 Missing-contact cleanup cannot infer non-emission from absent dispatch history.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID

class DirectPreclaimFailureUncertaintyException(
    val token: SessionToken,
    val messageID: UUID,
) : Exception("Missing-contact direct message has no persisted non-emission proof; pending send retained")
