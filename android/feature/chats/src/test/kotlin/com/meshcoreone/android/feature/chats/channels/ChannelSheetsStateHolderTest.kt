// PortedFrom: MC1/Views/Chats/CreatePrivateChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinChannelConfirmationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.channels

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

    @Test
    fun `join discards holder slot snapshot and existing hashtag is rechecked by service`() = runBlocking {
        val rig = rig(emptyList(), maxChannels = 4u)
        rig.holder.load()
        rig.data.channels += channel(1u, "Written after load")
        rig.holder.updateName("Private")
        rig.holder.updateSecret("00112233445566778899AABBCCDDEEFF")
        assertEquals(2u, assertNotNull(rig.holder.joinPrivate()).index)

        val existing = channel(3u, "#Ops")
        rig.data.channels += existing
        rig.holder.open(ChannelSheetPage.JOIN_HASHTAG)
        rig.holder.updateHashtag("ops")
        assertEquals(existing, rig.holder.joinHashtag())
        assertEquals(0, rig.service.passphraseCalls)
    }

    @Test
    fun `post-save failure cannot resubmit the same confirmation but a new form is independent`() = runBlocking {
        for (hashtag in listOf(false, true)) {
            val rig = rig(emptyList(), 4u)
            rig.holder.load()
            rig.holder.updateHashtag("ops")
            val failure = IllegalStateException("post-save fetch")
            rig.service.completionFailure = failure
            val link = ChannelLink("#ops", Bytes(ByteArray(16)))
            suspend fun join() = if (hashtag) rig.holder.joinHashtag() else rig.holder.joinLink(link)
            assertNull(join())
            val saved = rig.data.channels.single()
            val receipt = assertNotNull(rig.holder.state.value.committedWrite)
            assertEquals(ChannelWriteReceipt(EntityKey(saved.radioId, saved.id), saved.index), receipt)
            assertEquals(failure, assertIs<ChannelSheetError.AlreadyCommitted>(rig.holder.state.value.failure).cause)
            assertFalse(rig.holder.state.value.isSubmitting)
            assertNull(rig.holder.state.value.createdChannel, "A committed receipt is not a fabricated success DTO")
            rig.service.completionFailure = null
            rig.holder.clearFailure()
            assertIs<ChannelSheetError.AlreadyCommitted>(rig.holder.state.value.failure)
            assertNull(join())
            assertEquals(listOf(saved), rig.data.channels)
            rig.holder.back()
            rig.holder.open(ChannelSheetPage.JOIN_PRIVATE)
            assertNull(rig.holder.state.value.committedWrite)
            assertEquals(2u, assertNotNull(rig.holder.joinLink(link)).index)
        }
    }

    @Test
    fun `cancellation after returned commit preserves cancellation and blocks resubmission`() = runBlocking {
        for (hashtag in listOf(false, true)) {
            val rig = rig(emptyList(), 4u)
            rig.holder.updateHashtag("ops")
            val cancelled = CancellationException("after returned save UUID")
            rig.service.completionFailure = cancelled
            val link = ChannelLink("#ops", Bytes(ByteArray(16)))
            try {
                if (hashtag) rig.holder.joinHashtag() else rig.holder.joinLink(link)
                throw AssertionError("Expected original cancellation")
            } catch (failure: CancellationException) {
                assertTrue(failure === cancelled)
            }
            val saved = rig.data.channels.single()
            assertEquals(ChannelWriteReceipt(EntityKey(saved.radioId, saved.id), saved.index), rig.holder.state.value.committedWrite)
            assertEquals(cancelled, assertIs<ChannelSheetError.AlreadyCommitted>(rig.holder.state.value.failure).cause)
            assertFalse(rig.holder.state.value.isSubmitting)
            rig.service.completionFailure = null
            assertNull(if (hashtag) rig.holder.joinHashtag() else rig.holder.joinLink(link))
            assertEquals(listOf(saved), rig.data.channels)
        }
    }

    @Test
    fun `a held allocation cannot admit a second submission from the same confirmation`() = runBlocking {
        for (hashtag in listOf(false, true)) {
            val rig = rig(emptyList(), 4u)
            rig.holder.updateHashtag("ops")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            rig.service.beforeAllocation = {
                entered.complete(Unit)
                release.await()
            }
            val link = ChannelLink("#ops", Bytes(ByteArray(16)))
            suspend fun join() = if (hashtag) rig.holder.joinHashtag() else rig.holder.joinLink(link)
            val first = async(start = CoroutineStart.UNDISPATCHED) { join() }
            try {
                assertTrue(entered.isCompleted)
                assertTrue(rig.holder.state.value.isSubmitting)
                assertNull(rig.holder.state.value.committedWrite)
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { join() }
                assertTrue(duplicate.isCompleted, "Duplicate consent must not enter the held service")
                assertNull(duplicate.await())
                assertEquals(1, rig.service.allocationCalls)
                assertTrue(rig.data.channels.isEmpty())
                assertTrue(rig.holder.state.value.isSubmitting)
                assertNull(rig.holder.state.value.failure)
                assertEquals(listOf("channel confirmation already submitting"), rig.diagnostics)
            } finally {
                release.complete(Unit)
            }
            val joined = assertNotNull(first.await())
            assertEquals(listOf(joined), rig.data.channels)
            assertEquals(1, rig.service.allocationCalls)
            assertFalse(rig.holder.state.value.isSubmitting)
            assertEquals(joined, rig.holder.state.value.createdChannel)
            assertEquals(
                ChannelWriteReceipt(EntityKey(joined.radioId, joined.id), joined.index),
                rig.holder.state.value.committedWrite,
            )
        }
    }

    @Test
    fun `late commit and success cannot adopt a reopened form with the same page and name`() = runBlocking {
        for (hashtag in listOf(false, true)) {
            val rig = rig(emptyList(), 4u)
            val page = if (hashtag) ChannelSheetPage.JOIN_HASHTAG else ChannelSheetPage.JOIN_PRIVATE
            rig.holder.open(page)
            rig.holder.updateHashtag("ops")
            val originalForm = rig.holder.state.value.formId
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            rig.service.beforeAllocation = {
                entered.complete(Unit)
                release.await()
            }
            val link = ChannelLink("#ops", Bytes(ByteArray(16)))
            suspend fun join() = if (hashtag) rig.holder.joinHashtag() else rig.holder.joinLink(link)
            val first = async(start = CoroutineStart.UNDISPATCHED) { join() }
            lateinit var replacement: ChannelSheetsState
            try {
                assertTrue(entered.isCompleted)
                rig.holder.back()
                rig.holder.open(page)
                rig.holder.updateHashtag("ops")
                replacement = rig.holder.state.value
                assertNotEquals(originalForm, replacement.formId)
                assertTrue(replacement.isSubmitting, "Changing forms does not cancel the admitted operation")
            } finally {
                release.complete(Unit)
            }
            assertNull(first.await(), "Old completion must not return a current-form navigation result")
            assertEquals(replacement.copy(isSubmitting = false), rig.holder.state.value)
            val firstSaved = rig.data.channels.single()
            assertEquals("#ops", firstSaved.name)
            assertEquals(listOf("channel confirmation form replaced"), rig.diagnostics)
            rig.service.beforeAllocation = null
            val next = assertNotNull(join())
            assertEquals(2, rig.service.allocationCalls)
            assertEquals(if (hashtag) 1 else 2, rig.data.channels.size)
            assertEquals(next, rig.holder.state.value.createdChannel)
            assertEquals(replacement.formId, rig.holder.state.value.formId)
            assertTrue(firstSaved in rig.data.channels, "Replacing a form does not delete a committed row")
        }
    }

    @Test
    fun `replaced form does not inherit an older committed failure or cancellation`() = runBlocking {
        for (hashtag in listOf(false, true)) for (cancel in listOf(false, true)) {
            val rig = rig(emptyList(), 4u)
            rig.holder.updateHashtag("ops")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failure = if (cancel) CancellationException("older committed form") else IllegalStateException("older committed form")
            rig.service.completionFailure = failure
            rig.service.afterCommit = {
                entered.complete(Unit)
                release.await()
            }
            val link = ChannelLink("#ops", Bytes(ByteArray(16)))
            var observedCancellation: CancellationException? = null
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (hashtag) rig.holder.joinHashtag() else rig.holder.joinLink(link)
                } catch (cancelled: CancellationException) {
                    observedCancellation = cancelled
                    throw cancelled
                }
            }
            lateinit var replacement: ChannelSheetsState
            try {
                assertTrue(entered.isCompleted)
                assertNotNull(rig.holder.state.value.committedWrite)
                rig.holder.back()
                rig.holder.open(ChannelSheetPage.JOIN_PRIVATE)
                rig.holder.updateName("New form")
                rig.holder.updateSecret("00112233445566778899AABBCCDDEEFF")
                replacement = rig.holder.state.value
                assertNull(replacement.committedWrite)
            } finally {
                release.complete(Unit)
            }
            if (cancel) {
                assertFailsWith<CancellationException> { first.await() }
                assertTrue(observedCancellation === failure)
            } else assertNull(first.await())
            assertEquals(replacement.copy(isSubmitting = false), rig.holder.state.value)
            val saved = rig.data.channels.single()
            rig.service.afterCommit = null
            rig.service.completionFailure = null
            val next = assertNotNull(rig.holder.joinPrivate())
            assertEquals(2u, next.index)
            assertEquals("New form", next.name)
            assertEquals(saved, rig.data.channels.first())
            assertEquals(2, rig.service.allocationCalls)
        }
    }

    @Test
    fun `older create completion cannot switch a replacement form to share`() = runBlocking {
        val rig = rig(emptyList(), 4u)
        rig.holder.open(ChannelSheetPage.CREATE_PRIVATE)
        rig.holder.updateName("Older create")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.service.beforeAllocation = {
            entered.complete(Unit)
            release.await()
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { rig.holder.createPrivate() }
        lateinit var replacement: ChannelSheetsState
        try {
            assertTrue(entered.isCompleted)
            rig.holder.back()
            rig.holder.open(ChannelSheetPage.JOIN_PRIVATE)
            rig.holder.updateName("Replacement")
            replacement = rig.holder.state.value
        } finally {
            release.complete(Unit)
        }
        assertNull(first.await())
        assertEquals(replacement.copy(isSubmitting = false), rig.holder.state.value)
        assertEquals("Older create", rig.data.channels.single().name)
        assertNull(rig.holder.state.value.shareUri)
    }

    @Test
    fun `region setup remains part of the original form submission`() = runBlocking {
        val rig = rig(emptyList(), 4u)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.data.beforeRegionWrite = {
            entered.complete(Unit)
            release.await()
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            rig.holder.joinLink(ChannelLink("Older join", Bytes(ByteArray(16)), "testregion"))
        }
        lateinit var replacement: ChannelSheetsState
        try {
            assertTrue(entered.isCompleted)
            assertTrue(rig.holder.state.value.isSubmitting)
            assertNull(rig.holder.state.value.committedWrite, "Receipt publication waits for the scoped operation to exit")
            rig.holder.back()
            rig.holder.open(ChannelSheetPage.JOIN_PRIVATE)
            rig.holder.updateName("New form")
            replacement = rig.holder.state.value
            assertTrue(replacement.isSubmitting)
        } finally {
            release.complete(Unit)
        }
        assertNull(first.await())
        assertEquals(replacement.copy(isSubmitting = false), rig.holder.state.value)
        assertEquals(ChannelFloodScope.Region("testregion"), rig.data.channels.single().floodScope)
        assertEquals(1, rig.service.allocationCalls)
    }

    private fun rig(channels: List<ChannelDTO>, maxChannels: UByte): ChannelRig {
        val data = FakeChannelData(channels.toMutableList())
        val diagnostics = mutableListOf<String>()
        val service = FakeChannelService(data) { operation, _ -> diagnostics += operation }
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
    var beforeRegionWrite: (suspend () -> Unit)? = null
    override suspend fun fetchChannels(radioId: RadioId) = channels.toList()
    override suspend fun fetchChannel(radioId: RadioId, index: UByte) = channels.firstOrNull { it.index == index }
    suspend fun setFloodScope(channel: ChannelDTO, scope: ChannelFloodScope): ChannelDTO {
        beforeRegionWrite?.invoke()
        regionFailure?.let { throw it }
        lastRegion = (scope as? ChannelFloodScope.Region)?.name
        return channel.withFloodScope(scope).also { updated ->
            channels.replaceAll { if (it.id == channel.id) updated else it }
        }
    }
}

private class FakeChannelService(
    private val data: FakeChannelData,
    private val report: (String, Throwable) -> Unit,
) : ChannelSheetService {
    var lastSecret: Bytes? = null
    var lastPassphrase: String? = null
    var passphraseCalls = 0
    var publicCalls = 0
    var failure: Throwable? = null
    var completionFailure: Throwable? = null
    var beforeAllocation: (suspend () -> Unit)? = null
    var afterCommit: (suspend () -> Unit)? = null
    var allocationCalls = 0

    override suspend fun createChannelWithSecret(
        radioId: RadioId, maxChannels: UByte, name: String, secret: Bytes, regionScope: String?,
        onCommitted: (ChannelWriteReceipt) -> Unit,
    ): ChannelDTO {
        allocationCalls++
        beforeAllocation?.invoke()
        val index = freeSlot(maxChannels)
        setChannelWithSecret(radioId, index, name, secret)
        val saved = requireNotNull(data.fetchChannel(radioId, index))
        val completed = try {
            regionScope?.takeIf(String::isNotEmpty)?.let {
                try {
                    data.setFloodScope(saved, ChannelFloodScope.Region(it))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    report("set joined channel region", failure)
                    saved
                }
            } ?: saved
        } finally {
            onCommitted(ChannelWriteReceipt(EntityKey(radioId, saved.id), index))
        }
        afterCommit?.invoke()
        completionFailure?.let { throw it }
        return completed
    }
    override suspend fun joinHashtagChannel(
        radioId: RadioId, maxChannels: UByte, name: String, onCommitted: (ChannelWriteReceipt) -> Unit,
    ): ChannelDTO {
        allocationCalls++
        beforeAllocation?.invoke()
        data.channels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val index = freeSlot(maxChannels)
        setChannel(radioId, index, name, name)
        val saved = requireNotNull(data.fetchChannel(radioId, index))
        onCommitted(ChannelWriteReceipt(EntityKey(radioId, saved.id), index))
        afterCommit?.invoke()
        completionFailure?.let { throw it }
        return saved
    }
    private fun freeSlot(maxChannels: UByte): UByte =
        (1 until maxChannels.toInt()).map(Int::toUByte).firstOrNull { index -> data.channels.none { it.index == index } }
            ?: throw ChannelSheetError.NoAvailableSlots

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
