// AndroidOnly: WP-202 Explicit result for current-occupant conditional channel metadata persistence.
package com.meshcoreone.android.core.contracts.domain

enum class ChannelOccupantMetadataMutationOutcome {
    APPLIED,
    STALE_OR_UNPROVED,
}
