// PortedFrom: MC1Services/Sources/MC1Services/Services/RoomServerEvent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageStatus

/**
 * Room-server notifications broadcast by [RoomServerService.events].
 * The stream is multicast: every subscriber receives every event.
 *
 * Swift keys both cases by UUID; the Android store partitions rows by radio, so the full [EntityKey] is
 * carried and [EntityKey.id] is the Swift `messageID` / `sessionID`.
 */
sealed interface RoomServerEvent {
    /** An outbound room message's delivery status changed. */
    data class StatusUpdated(val message: EntityKey, val status: MessageStatus) : RoomServerEvent

    /** An incoming message recovered a disconnected room session. */
    data class ConnectionRecovered(val session: EntityKey) : RoomServerEvent
}
