// AndroidOnly: WP-201 Channel metadata and local occupant proof captured together for a new send intent.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelDTO
import java.util.UUID

data class ChannelOccupantSnapshot(val channel: ChannelDTO, val occupantID: UUID)
