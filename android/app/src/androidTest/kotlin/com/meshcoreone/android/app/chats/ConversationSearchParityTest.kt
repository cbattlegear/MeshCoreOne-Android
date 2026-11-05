// AndroidOnly: WP-306 Instrumented pinned Foundation search vectors through the actual helper and list consumer; source is not runtime evidence.
package com.meshcoreone.android.app.chats

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.ChatFilter
import com.meshcoreone.android.feature.chats.list.ChatListStrings
import com.meshcoreone.android.feature.chats.list.ChatTextMatching
import com.meshcoreone.android.feature.chats.list.Conversation
import com.meshcoreone.android.feature.chats.list.filtered
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSearchParityTest {
    @Test
    fun productionStandardContainsMatchesCapturedFoundationVectors() {
        for ((name, query, matches) in vectors) {
            assertEquals(
                "$name contains $query",
                matches,
                ChatTextMatching.standardContains(name, query),
            )
        }
        assertEquals(false, ChatTextMatching.standardContains("alice", ""))
    }

    @Test
    fun productionConversationFilteredMatchesCapturedVectorsAcrossEveryFilter() {
        for ((name, query, matches) in vectors) {
            val rows = conversations(name)
            val expected = if (matches) rows else emptyList()
            for (filter in ChatFilter.entries) {
                assertEquals(
                    "$filter: $name contains $query",
                    expected,
                    rows.filtered(filter, query, strings),
                )
            }
        }
    }

    @Test
    fun productionConversationFilteredRetainsOnlyMatchingRowsInInputOrder() {
        val matchingRoom = Conversation.Room(
            RemoteNodeSessionDTO(
                id = UUID(0, 11), radioId = radio, publicKey = key, name = "Alpha Room",
                role = RemoteNodeRole.ROOM_SERVER, notificationLevel = NotificationLevel.MUTED,
            ),
        )
        val otherDirect = Conversation.Direct(
            ContactDTO(
                id = UUID(0, 12), radioId = radio, publicKey = key, name = "Bravo",
                lastHeardTimestamp = null, isMuted = true,
            ),
        )
        val matchingChannel = Conversation.Channel(
            ChannelDTO(
                id = UUID(0, 13), radioId = radio, index = 1u, name = "Alpha Beacon",
                notificationLevel = NotificationLevel.MUTED,
            ),
        )
        val otherRoom = Conversation.Room(
            RemoteNodeSessionDTO(
                id = UUID(0, 14), radioId = radio, publicKey = key, name = "Echo",
                role = RemoteNodeRole.ROOM_SERVER, notificationLevel = NotificationLevel.MUTED,
            ),
        )
        val matchingDirect = Conversation.Direct(
            ContactDTO(
                id = UUID(0, 15), radioId = radio, publicKey = key, name = "Alpha Direct",
                lastHeardTimestamp = null, isMuted = true,
            ),
        )
        val otherChannel = Conversation.Channel(
            ChannelDTO(
                id = UUID(0, 16), radioId = radio, index = 2u, name = "Foxtrot",
                notificationLevel = NotificationLevel.MUTED,
            ),
        )
        val rows = listOf(
            matchingRoom, otherDirect, matchingChannel, otherRoom, matchingDirect, otherChannel,
        )
        for (filter in ChatFilter.entries) {
            assertEquals(
                "$filter: mixed matching rows retain input order",
                listOf(matchingRoom, matchingChannel, matchingDirect),
                rows.filtered(filter, "alpha", strings),
            )
            assertEquals(
                "$filter: only the channel matches beacon",
                listOf(matchingChannel),
                rows.filtered(filter, "beacon", strings),
            )
            assertEquals(
                "$filter: only the room matches echo",
                listOf(otherRoom),
                rows.filtered(filter, "echo", strings),
            )
        }
    }

    private fun conversations(name: String): List<Conversation> = listOf(
        Conversation.Direct(
            ContactDTO(
                id = UUID(0, 1), radioId = radio, publicKey = key, name = name,
                lastHeardTimestamp = null, isMuted = true,
            ),
        ),
        Conversation.Channel(
            ChannelDTO(
                id = UUID(0, 2), radioId = radio, index = 1u, name = name,
                notificationLevel = NotificationLevel.MUTED,
            ),
        ),
        Conversation.Room(
            RemoteNodeSessionDTO(
                id = UUID(0, 3), radioId = radio, publicKey = key, name = name,
                role = RemoteNodeRole.ROOM_SERVER, notificationLevel = NotificationLevel.MUTED,
            ),
        ),
    )

    private val radio = RadioId(UUID(0, 4))
    private val key = Bytes(ByteArray(32))
    private val strings = object : ChatListStrings {
        override fun channelDefaultName(index: Int) = "Channel $index"
        override fun floodRouting() = error("Search does not render routing labels")
        override fun directHops(hops: Long) = error("Search does not render hop labels")
        override fun headerRegion(region: String) = error("Search does not render region headers")
        override fun scopedDefault(region: String) = error("Search does not render region scope")
    }

    // Captured std outputs: WP-306/oracle/wp306_strings.output.txt and
    // WP-311/oracle/wp311_more.output.txt + wp311_strings.output.txt. The more output has no locale stamp.
    private val vectors = listOf(
        Triple("Caf\u00e9", "cafe", true),
        Triple("cafe", "Caf\u00e9", true),
        Triple("Stra\u00dfe", "strasse", true),
        Triple("strasse", "Stra\u00dfe", true),
        Triple("\uff41lice", "alice", false),
        Triple("Alice", "\uff41", false),
        Triple("\u0130stanbul", "istanbul", true),
        Triple("\u00c5ngstr\u00f6m", "angstrom", true),
        Triple("Alice", "ALI", true),
        Triple("a b", "a  b", false),
        Triple("stra\u00dfe", "stras", false),
        Triple("\ufb01le", "f", false),
        Triple("\ud55c\uae00", "\ud558", false),
        Triple("strasse", "stra\u00df", true),
        Triple("\ufb01le", "fi", true),
        Triple("\u03a3\u03af\u03c3\u03c5\u03c6\u03bf\u03c2", "\u03c3\u03b9\u03c3\u03c5\u03c6\u03bf\u03c3", true),
        Triple("\uff11\uff12", "12", true),
        Triple("\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67", "\ud83d\udc68", true),
        Triple("\u0131stanbul", "istanbul", false),
        Triple("\u1e9e", "ss", true),
        Triple("Alpha Repeater", "alpha", true),
    )
}
