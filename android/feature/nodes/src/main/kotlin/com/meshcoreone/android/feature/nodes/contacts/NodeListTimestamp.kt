// AndroidOnly: WP-311 Adopt upstream node-list advert validation without advancing the frozen Swift pin.
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.model.ContactDTO
import java.time.Instant

internal fun ContactDTO.nodeListTimestamp(referenceTime: Instant): UInt? =
    if (lastAdvertTimestamp >= 1_577_836_800u &&
        Instant.ofEpochSecond(lastAdvertTimestamp.toLong()) <= referenceTime.plusSeconds(300)
    ) {
        lastAdvertTimestamp
    } else {
        lastHeardTimestamp?.takeIf { it > 0u }
    }
