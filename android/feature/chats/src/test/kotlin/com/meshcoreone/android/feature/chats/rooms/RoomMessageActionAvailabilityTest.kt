// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageActionAvailability.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.MessageAction
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import kotlin.test.assertEquals
import org.junit.Test

class RoomMessageActionAvailabilityTest {
    @Test fun `all permissions connections authors and directions preserve source availability`() {
        var cases = 0
        for (permission in RoomPermissionLevel.entries) {
            for (connected in listOf(false, true)) {
                for (self in listOf(false, true)) {
                    for (author in listOf(null, "", "Alice")) {
                        val session = Fixtures.room(isConnected = connected).copy(permissionLevel = permission)
                        val message = RoomMessageDTO(
                            sessionID = session.id, authorKeyPrefix = Bytes.of(1), authorName = author,
                            text = "original", timestamp = 100u, isFromSelf = self,
                        )
                        val expected = buildList {
                            if (!self && session.canPost) add(MessageAction.Reply)
                            if (!self && author != null) add(MessageAction.SendDirectMessage)
                            add(MessageAction.Copy)
                            if (self) add(MessageAction.SendAgain)
                        }
                        assertEquals(expected, RoomMessageActionAvailability.forMessage(message, session).actions)
                        cases++
                    }
                }
            }
        }
        assertEquals(RoomPermissionLevel.entries.size * 12, cases)
    }
}
