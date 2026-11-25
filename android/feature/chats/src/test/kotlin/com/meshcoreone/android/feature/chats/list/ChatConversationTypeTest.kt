// PortedFrom: MC1Tests/Views/Chats/ChatConversationTypeTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.list.support.Fixtures.channel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import com.meshcoreone.android.feature.chats.list.support.TestStrings
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ChatConversationTypeTest {
    private fun dm(name: String = "TestUser", nickname: String? = null, outPathLength: UByte = 2u, id: UUID = UUID.randomUUID(), radio: RadioId = com.meshcoreone.android.feature.chats.list.support.Fixtures.radio) =
        ChatConversationType.Dm(contact(name, id = id, nickname = nickname, outPathLength = outPathLength, radioId = radio))

    private fun ch(name: String = "General", index: UByte = 1u, scope: ChannelFloodScope = ChannelFloodScope.Inherit, id: UUID = UUID.randomUUID(), radio: RadioId = com.meshcoreone.android.feature.chats.list.support.Fixtures.radio) =
        ChatConversationType.Channel(channel(name, id = id, index = index, floodScope = scope, radioId = radio))

    private fun subtitle(type: ChatConversationType, deviceDefault: String? = null) = type.navigationSubtitle(TestStrings, deviceDefault)

    @Test @OriginalCase("ChatConversationTypeTests::DM navigationTitle returns contact displayName()")
    fun `DM navigationTitle returns contact displayName`() = assertEquals("Alice", dm("Alice").navigationTitle(TestStrings))

    @Test @OriginalCase("ChatConversationTypeTests::DM navigationTitle prefers nickname when set()")
    fun `DM navigationTitle prefers nickname when set`() = assertEquals("Ally", dm("Alice", nickname = "Ally").navigationTitle(TestStrings))

    @Test @OriginalCase("ChatConversationTypeTests::Channel navigationTitle returns channel name()")
    fun `Channel navigationTitle returns channel name`() = assertEquals("General", ch("General").navigationTitle(TestStrings))

    @Test @OriginalCase("ChatConversationTypeTests::Channel navigationTitle returns default name when empty()")
    fun `Channel navigationTitle returns default name when empty`() =
        assertEquals(TestStrings.channelDefaultName(3), ch("", index = 3u).navigationTitle(TestStrings))

    @Test @OriginalCase("ChatConversationTypeTests::DM subtitle shows flood routing when flood routed()")
    fun `DM subtitle shows flood routing when flood routed`() = assertEquals(TestStrings.floodRouting(), subtitle(dm(outPathLength = 0xFFu)))

    @Test @OriginalCase("ChatConversationTypeTests::DM subtitle shows direct path with hop count()")
    fun `DM subtitle shows direct path with hop count`() {
        val type = dm(outPathLength = 2u)
        assertEquals(TestStrings.directHops(type.contact.pathHopCount), subtitle(type))
    }

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle empty for public channel with no region()")
    fun `Channel subtitle empty for public channel with no region`() = assertEquals("", subtitle(ch("Public", index = 0u)))

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle empty for hashtag channel with no region()")
    fun `Channel subtitle empty for hashtag channel with no region`() = assertEquals("", subtitle(ch("#random", index = 5u)))

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle empty for private channel with no region()")
    fun `Channel subtitle empty for private channel with no region`() = assertEquals("", subtitle(ch("Secret", index = 3u)))

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle shows Region label for explicit region()")
    fun `Channel subtitle shows Region label for explicit region`() {
        val type = ch("Ops", index = 3u, scope = ChannelFloodScope.Region("Germany"))
        val expected = TestStrings.headerRegion("Germany")
        assertEquals(expected, subtitle(type, null))
        assertEquals(expected, subtitle(type, "Spain"))
    }

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle shows Region label with (default) when inheriting device default()")
    fun `Channel subtitle shows Region label with default when inheriting device default`() {
        val type = ch("Ops", index = 3u, scope = ChannelFloodScope.Inherit)
        assertEquals(TestStrings.headerRegion(TestStrings.scopedDefault("Spain")), subtitle(type, "Spain"))
    }

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle shows Region label with (default) when explicit region matches device default()")
    fun `Channel subtitle shows Region label with default when explicit region matches device default`() {
        val type = ch("Ops", index = 3u, scope = ChannelFloodScope.Region("Spain"))
        assertEquals(TestStrings.headerRegion(TestStrings.scopedDefault("Spain")), subtitle(type, "Spain"))
    }

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle empty when inherit and no device default()")
    fun `Channel subtitle empty when inherit and no device default`() {
        val type = ch("Ops", index = 3u, scope = ChannelFloodScope.Inherit)
        assertEquals("", subtitle(type, null))
        assertEquals("", subtitle(type, ""))
    }

    @Test @OriginalCase("ChatConversationTypeTests::Channel subtitle empty for allRegions scope()")
    fun `Channel subtitle empty for allRegions scope`() =
        assertEquals("", subtitle(ch("Ops", index = 3u, scope = ChannelFloodScope.AllRegions), "Spain"))

    @Test @OriginalCase("ChatConversationTypeTests::DM conversationID returns contact ID()")
    fun `DM conversationID returns contact ID`() {
        val id = UUID.randomUUID()
        assertEquals(id, dm(id = id).conversationID)
    }

    @Test @OriginalCase("ChatConversationTypeTests::Channel conversationID returns channel ID()")
    fun `Channel conversationID returns channel ID`() {
        val id = UUID.randomUUID()
        assertEquals(id, ch(id = id).conversationID)
    }

    @Test @OriginalCase("ChatConversationTypeTests::DM isPublicStyleChannel is false()")
    fun `DM isPublicStyleChannel is false`() = assertFalse(dm().isPublicStyleChannel)

    @Test @OriginalCase("ChatConversationTypeTests::Public channel (index 0) isPublicStyleChannel is true()")
    fun `Public channel index 0 isPublicStyleChannel is true`() = assertTrue(ch("Public", index = 0u).isPublicStyleChannel)

    @Test @OriginalCase("ChatConversationTypeTests::Hash-prefixed channel isPublicStyleChannel is true()")
    fun `Hash-prefixed channel isPublicStyleChannel is true`() = assertTrue(ch("#general", index = 5u).isPublicStyleChannel)

    @Test @OriginalCase("ChatConversationTypeTests::Private channel isPublicStyleChannel is false()")
    fun `Private channel isPublicStyleChannel is false`() = assertFalse(ch("Secret", index = 3u).isPublicStyleChannel)

    @Test @OriginalCase("ChatConversationTypeTests::Channel named wardriving suppresses map previews()")
    fun `Channel named wardriving suppresses map previews`() = assertTrue(ch("wardriving").suppressesMapPreviews)

    @Test @OriginalCase("ChatConversationTypeTests::Wardriving match is case-insensitive()")
    fun `Wardriving match is case-insensitive`() {
        assertTrue(ch("Wardriving").suppressesMapPreviews)
        assertTrue(ch("WARDRIVING").suppressesMapPreviews)
    }

    @Test @OriginalCase("ChatConversationTypeTests::Wardriving match trims surrounding whitespace()")
    fun `Wardriving match trims surrounding whitespace`() = assertTrue(ch(" wardriving ").suppressesMapPreviews)

    @Test @OriginalCase("ChatConversationTypeTests::Hash-prefixed wardriving suppresses (tolerates the # channel convention)()")
    fun `Hash-prefixed wardriving suppresses`() {
        assertTrue(ch("#wardriving").suppressesMapPreviews)
        assertTrue(ch("#Wardriving").suppressesMapPreviews)
    }

    @Test @OriginalCase("ChatConversationTypeTests::Wardriving with suffix does not suppress (exact match, not prefix)()")
    fun `Wardriving with suffix does not suppress`() = assertFalse(ch("wardriving-east").suppressesMapPreviews)

    @Test @OriginalCase("ChatConversationTypeTests::Ordinary channel does not suppress map previews()")
    fun `Ordinary channel does not suppress map previews`() = assertFalse(ch("general").suppressesMapPreviews)

    @Test @OriginalCase("ChatConversationTypeTests::DM never suppresses map previews()")
    fun `DM never suppresses map previews`() = assertFalse(dm().suppressesMapPreviews)

    @Test @OriginalCase("ChatConversationTypeTests::radioID returns the contact's radioID for a DM conversation()")
    fun `radioID returns the contact radioID for a DM conversation`() {
        val radio = RadioId(UUID.randomUUID())
        assertEquals(radio, dm(radio = radio).radioId)
    }

    @Test @OriginalCase("ChatConversationTypeTests::radioID returns the channel's radioID for a channel conversation()")
    fun `radioID returns the channel radioID for a channel conversation`() {
        val radio = RadioId(UUID.randomUUID())
        assertEquals(radio, ch(radio = radio).radioId)
    }

    @Test @OriginalCase("ChatConversationTypeTests::replacingContact returns DM with updated contact()")
    fun `replacingContact returns DM with updated contact`() {
        val result = dm("Alice").replacingContact(contact("Bob"))
        assertEquals("Bob", result.navigationTitle(TestStrings))
    }

    @Test @OriginalCase("ChatConversationTypeTests::replacingContact returns self for channel()")
    fun `replacingContact returns self for channel`() {
        val result = ch("General").replacingContact(contact("Alice"))
        assertEquals("General", result.navigationTitle(TestStrings))
    }

    @Test @OriginalCase("ChatConversationTypeTests::DM draftConversationID keys on radioID and contact id()")
    fun `DM draftConversationID keys on radioID and contact id`() {
        val type = dm()
        assertEquals(ChatConversationID.dm(type.contact.radioId, type.contact.id), type.draftConversationID)
    }

    @Test @OriginalCase("ChatConversationTypeTests::Channel draftConversationID keys on the slot index, not the row UUID()")
    fun `Channel draftConversationID keys on the slot index not the row UUID`() {
        val type = ch(index = 4u)
        assertEquals(ChatConversationID.channel(type.channel.radioId, 4u), type.draftConversationID)
        assertEquals(type.draftConversationID, type.coordinatorID)
    }
}
