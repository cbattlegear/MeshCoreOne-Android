// PortedFrom: MC1/Views/Chats/CreatePrivateChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinChannelConfirmationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.channels

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ChannelSheetsStateHolderTest {
    @Test
    @OriginalCase("ChannelOptionsSheet::slot zero is reserved and occupied user slots are excluded")
    fun `load calculates user slots and public occupancy`() = runBlocking {
        val rig = rig(listOf(channel(0u), channel(2u)), maxChannels = 4u)

        rig.holder.load()

        assertEquals(listOf(1.toUByte(), 3.toUByte()), rig.holder.state.value.availableSlots)
        assertTrue(rig.holder.state.value.hasPublicChannel)
    }

    @Test
    @OriginalCase("CreatePrivateChannelView::channel name is limited by UTF-8 bytes")
    fun `name truncation never splits a UTF-8 code point`() {
        assertEquals("éé", ChannelSheetsStateHolder.utf8Prefix("ééé", 5))
        assertEquals("😀", ChannelSheetsStateHolder.utf8Prefix("😀a", 4))
    }

    @Test
    @OriginalCase("JoinPrivateChannelView::secret must be exactly 32 hexadecimal characters")
    fun `private secret parser validates exact bytes`() {
        val parsed = assertNotNull(ChannelSheetsStateHolder.parseSecret("00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff"))

        assertContentEquals(
            byteArrayOf(0, 17, 34, 51, 68, 85, 102, 119, -120, -103, -86, -69, -52, -35, -18, -1),
            parsed.toByteArray(),
        )
        assertNull(ChannelSheetsStateHolder.parseSecret("0011"))
        assertNull(ChannelSheetsStateHolder.parseSecret("GG112233445566778899AABBCCDDEEFF"))
    }

    @Test
    @OriginalCase("JoinHashtagChannelView::input is lowercase, sanitized, and cannot begin with hyphen")
    fun `hashtag sanitization and validation match source`() {
        assertEquals("opsroom", ChannelSheetsStateHolder.sanitizeHashtag("--Ops! Room"))
        assertTrue(ChannelSheetsStateHolder.isValidHashtag("ops-room2"))
        assertFalse(ChannelSheetsStateHolder.isValidHashtag("-ops"))
        assertFalse(ChannelSheetsStateHolder.isValidHashtag(""))
        assertFalse(ChannelSheetsStateHolder.isValidHashtag("ops-é"))
        val holder = rig(emptyList(), maxChannels = 4u).holder
        holder.updateHashtag("--#Équipe 日本 Ops! 42-\uD83D\uDCBB")
        assertEquals("quipeops42-", holder.state.value.name)
    }

    @Test
    @OriginalCase("CreatePrivateChannelView::successful create advances to share with exported URI")
    fun `create uses a generated 16-byte secret and opens share page`() = runBlocking {
        val rig = rig(emptyList(), maxChannels = 3u)
        rig.holder.load()
        rig.holder.open(ChannelSheetPage.CREATE_PRIVATE)
        rig.holder.updateName("Private")

        val created = assertNotNull(rig.holder.createPrivate())

        assertEquals(1u, created.index)
        assertEquals(16, rig.service.lastSecret?.size)
        assertEquals(ChannelSheetPage.SHARE, rig.holder.state.value.page)
        assertEquals("meshcore://channel/add?name=Private", rig.holder.state.value.shareUri)
    }

    @Test
    @OriginalCase("JoinPrivateChannelView::join writes the selected free slot and returns the persisted channel")
    fun `private join validates then returns stored channel`() = runBlocking {
        val rig = rig(listOf(channel(1u)), maxChannels = 4u)
        rig.holder.load()
        rig.holder.updateName("Team")
        rig.holder.updateSecret("00112233445566778899aabbccddeeff")

        val joined = assertNotNull(rig.holder.joinPrivate())

        assertEquals(2u, joined.index)
        assertEquals("Team", joined.name)
    }

    @Test
    @OriginalCase("JoinPublicChannelView::public channel always uses slot zero")
    fun `public join returns slot zero`() = runBlocking {
        val rig = rig(emptyList(), maxChannels = 4u)
        rig.holder.load()

        val joined = assertNotNull(rig.holder.joinPublic())

        assertEquals(0u, joined.index)
        assertEquals(1, rig.service.publicCalls)
    }

    @Test
    @OriginalCase("JoinHashtagChannelView::existing case-insensitive channel navigates without rewriting radio")
    fun `existing hashtag is reused case insensitively`() = runBlocking {
        val existing = channel(2u, "#Ops")
        val rig = rig(listOf(existing), maxChannels = 4u)
        rig.holder.load()
        rig.holder.updateHashtag("ops")

        assertEquals(existing, rig.holder.joinHashtag())
        assertEquals(0, rig.service.passphraseCalls)
    }

    @Test
    @OriginalCase("JoinHashtagChannelView::new hashtag hashes the full name including prefix")
    fun `new hashtag passes full prefixed name as passphrase`() = runBlocking {
        val rig = rig(emptyList(), maxChannels = 4u)
        rig.holder.load()
        rig.holder.updateHashtag("ops")

        val joined = assertNotNull(rig.holder.joinHashtag())

        assertEquals("#ops:#ops", rig.service.lastPassphrase)
        assertEquals("#ops", joined.name)
    }

    @Test
    @OriginalCase("JoinChannelConfirmationSheet::region scope is applied after channel creation")
    fun `link join applies declared region and returns updated channel`() = runBlocking {
        val rig = rig(emptyList(), maxChannels = 4u)
        rig.holder.load()

        val joined = assertNotNull(
            rig.holder.joinLink(ChannelLink("Ops", Bytes(ByteArray(16)), "testregion")),
        )

        assertEquals(ChannelFloodScope.Region("testregion"), joined.floodScope)
        assertEquals("testregion", rig.data.lastRegion)
    }

    @Test
    @OriginalCase("JoinChannelConfirmationSheet::region write failure does not undo a joined channel")
    fun `region failure keeps successful channel join`() = runBlocking {
        val rig = rig(emptyList(), maxChannels = 4u)
        rig.data.regionFailure = IllegalStateException("database")
        rig.holder.load()

        val joined = assertNotNull(
            rig.holder.joinLink(ChannelLink("Ops", Bytes(ByteArray(16)), "testregion")),
        )

        assertEquals(ChannelFloodScope.Inherit, joined.floodScope)
        assertEquals("set joined channel region", rig.diagnostics.single())
    }

    @Test
    @OriginalCase("ChannelOptionsSheet::no free user slots disables create and private joins")
    fun `no slots is a typed failure`() = runBlocking {
        val rig = rig(listOf(channel(1u)), maxChannels = 2u)
        rig.holder.load()
        rig.holder.updateName("Full")
        rig.holder.updateSecret("00112233445566778899AABBCCDDEEFF")

        assertNull(rig.holder.joinPrivate())
        assertIs<ChannelSheetError.NoAvailableSlots>(rig.holder.state.value.failure)
        Unit
    }

    @Test
    @OriginalCase("Channel write cancellation propagates and clears progress")
    fun `write cancellation is never reported as ordinary failure`() = runBlocking {
        val rig = rig(emptyList(), maxChannels = 2u)
        rig.service.failure = CancellationException("cancel")
        rig.holder.load()
        rig.holder.updateName("Cancel")
        rig.holder.updateSecret("00112233445566778899AABBCCDDEEFF")

        try {
            rig.holder.joinPrivate()
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(rig.holder.state.value.isSubmitting)
            assertNull(rig.holder.state.value.failure)
        }
    }

    private fun rig(channels: List<ChannelDTO>, maxChannels: UByte): ChannelRig {
        val data = FakeChannelData(channels.toMutableList())
        val service = FakeChannelService(data)
        val diagnostics = mutableListOf<String>()
        val dependencies = object : ChannelSheetDependencies {
            override val data = data
            override val service = service
            override val diagnostics = ChannelSheetDiagnostics { operation, _ -> diagnostics += operation }
        }
        return ChannelRig(ChannelSheetsStateHolder(Fixtures.radio, maxChannels, dependencies), data, service, diagnostics)
    }

    private fun channel(index: UByte, name: String = "Channel $index") =
        ChannelDTO(radioId = Fixtures.radio, index = index, name = name, secret = Bytes(ByteArray(16)))
}

private data class ChannelRig(
    val holder: ChannelSheetsStateHolder,
    val data: FakeChannelData,
    val service: FakeChannelService,
    val diagnostics: MutableList<String>,
)

private class FakeChannelData(val channels: MutableList<ChannelDTO>) : ChannelSheetDataSource {
    var lastRegion: String? = null
    var regionFailure: Throwable? = null
    override suspend fun fetchChannels(radioId: RadioId) = channels.toList()
    override suspend fun fetchChannel(radioId: RadioId, index: UByte) = channels.firstOrNull { it.index == index }
    override suspend fun setFloodScope(channel: ChannelDTO, scope: ChannelFloodScope): ChannelDTO {
        regionFailure?.let { throw it }
        lastRegion = (scope as? ChannelFloodScope.Region)?.name
        return channel.withFloodScope(scope).also { updated ->
            channels.replaceAll { if (it.id == channel.id) updated else it }
        }
    }
}

private class FakeChannelService(private val data: FakeChannelData) : ChannelSheetService {
    var lastSecret: Bytes? = null
    var lastPassphrase: String? = null
    var passphraseCalls = 0
    var publicCalls = 0
    var failure: Throwable? = null

    override suspend fun setChannelWithSecret(radioId: RadioId, index: UByte, name: String, secret: Bytes) {
        failure?.let { throw it }
        lastSecret = secret
        data.channels += ChannelDTO(UUID.randomUUID(), radioId, index, name, secret)
    }
    override suspend fun setChannel(radioId: RadioId, index: UByte, name: String, passphrase: String) {
        failure?.let { throw it }
        passphraseCalls++
        lastPassphrase = "$name:$passphrase"
        data.channels += ChannelDTO(UUID.randomUUID(), radioId, index, name, Bytes(ByteArray(16) { 1 }))
    }
    override suspend fun setupPublicChannel(radioId: RadioId) {
        failure?.let { throw it }
        publicCalls++
        data.channels += ChannelDTO(UUID.randomUUID(), radioId, 0u, "Public Channel", Bytes(ByteArray(16)))
    }
    override fun exportChannelUri(channel: ChannelDTO) = "meshcore://channel/add?name=${channel.name}"
}
