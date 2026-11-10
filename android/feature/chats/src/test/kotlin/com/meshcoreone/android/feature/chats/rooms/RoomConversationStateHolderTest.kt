// PortedFrom: MC1Tests/ViewModels/RoomConversationViewModelOrderingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatReconnectPopulateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class RoomConversationStateHolderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val events = MutableSharedFlow<RoomConversationEvent>(extraBufferCapacity = 8)

    @After
    fun tearDown() = scope.cancel()

    @Test
    @OriginalCase("RoomConversationViewModelOrderingTests::out-of-order live message inserts into the middle()")
    fun `live arrivals insert by server timestamp`() = runBlocking {
        val rig = rig(messages = listOf(message(100u), message(300u)))
        rig.holder.start()
        rig.holder.load()

        events.emit(RoomConversationEvent.MessageReceived(rig.session.id, message(200u)))

        assertEquals(listOf(100u, 200u, 300u), rig.holder.state.value.messages.map { it.timestamp })
    }

    @Test
    @OriginalCase("RoomConversationViewModelOrderingTests::equal-timestamp message inserts after existing same-timestamp messages()")
    fun `equal timestamps preserve arrival order`() = runBlocking {
        val first = message(200u, text = "first")
        val rig = rig(messages = listOf(first))
        rig.holder.start()
        rig.holder.load()
        val second = message(200u, text = "second")

        events.emit(RoomConversationEvent.MessageReceived(rig.session.id, second))

        assertEquals(listOf(first.id, second.id), rig.holder.state.value.messages.map { it.id })
    }

    @Test
    @OriginalCase("RoomConversationViewModelOrderingTests::duplicate id is ignored()")
    fun `duplicate event is ignored`() = runBlocking {
        val existing = message(100u)
        val rig = rig(messages = listOf(existing))
        rig.holder.start()
        rig.holder.load()

        events.emit(RoomConversationEvent.MessageReceived(rig.session.id, existing.copy(text = "changed")))

        assertEquals(listOf("message"), rig.holder.state.value.messages.map { it.text })
    }

    @Test
    @OriginalCase("RoomConversationViewModelOrderingTests::300s same-prefix is one cluster; 301s is two()")
    fun `room rows preserve 300 second grouping boundary`() {
        val atBoundary = RoomConversationStateHolder.rows(listOf(message(1000u), message(1300u)))
        val outside = RoomConversationStateHolder.rows(listOf(message(1000u), message(1301u)))

        assertFalse(atBoundary.first().showAvatar)
        assertFalse(atBoundary.last().showSenderName)
        assertTrue(outside.first().showAvatar)
        assertTrue(outside.last().showSenderName)
        assertTrue(outside.last().showTimestamp)
    }

    @Test
    @OriginalCase("RoomConversationViewModelOrderingTests::different prefixes are two clusters even when display names match()")
    fun `author prefix not display name defines clusters`() {
        val rows = RoomConversationStateHolder.rows(
            listOf(message(100u, prefix = 1), message(101u, prefix = 2)),
        )

        assertTrue(rows.all { it.showSenderName && it.showAvatar })
    }

    @Test
    @OriginalCase("RoomConversationViewModelOrderingTests::self messages never show a name or avatar()")
    fun `self messages have no incoming chrome`() {
        val rows = RoomConversationStateHolder.rows(listOf(message(100u, self = true)))

        assertFalse(rows.single().showSenderName)
        assertFalse(rows.single().showAvatar)
    }

    @Test
    @OriginalCase("RoomConversationViewModel::loadMessages clears unread and failed-send state")
    fun `load performs room read side effects after fetch`() = runBlocking {
        val rig = rig(messages = listOf(message(100u)))

        rig.holder.load()

        assertEquals(listOf("fetch", "read", "failed", "notifications", "badge", "changed"), rig.log)
        assertTrue(rig.holder.state.value.hasLoadedOnce)
    }

    @Test
    @OriginalCase("RoomAuthenticationSheet::successful room login returns the authenticated session")
    fun `authentication resolves contact and joins with remember choice`() = runBlocking {
        val rig = rig(connected = false)
        rig.holder.prepareAuthentication()

        rig.holder.authenticate("secret", false)

        assertEquals("secret:false", rig.service.joinCall)
        assertTrue(rig.holder.state.value.session.isConnected)
        assertNull(rig.holder.state.value.authenticationContact)
        assertEquals(listOf("fetch", "read", "failed", "notifications", "badge", "changed"), rig.log)
    }

    @Test
    fun `pre-navigation authentication joins without reading or clearing notifications`() = runBlocking {
        val rig = rig(connected = false)
        rig.holder.prepareAuthentication()

        val joined = rig.holder.authenticateForNavigation("secret", true)

        assertTrue(requireNotNull(joined).isConnected)
        assertEquals("secret:true", rig.service.joinCall)
        assertTrue(rig.log.isEmpty())
        assertFalse(rig.holder.state.value.hasLoadedOnce)
    }

    @Test
    fun `authentication lookup and join failures remain visible without read side effects`() = runBlocking {
        val rig = rig(connected = false)
        rig.data.contactFailure = IllegalStateException("lookup failed")
        rig.holder.prepareAuthentication()
        assertIs<IllegalStateException>(rig.holder.state.value.failure)
        assertTrue(rig.holder.state.value.authenticationLookupComplete)
        assertNull(rig.holder.state.value.authenticationContact)

        rig.data.contactFailure = null
        rig.holder.prepareAuthentication()
        rig.service.joinFailure = IllegalStateException("login failed")
        assertNull(rig.holder.authenticateForNavigation("secret", false))
        assertIs<IllegalStateException>(rig.holder.state.value.failure)
        assertFalse(rig.holder.state.value.isAuthenticating)
        assertTrue(rig.log.isEmpty())
    }

    @Test
    fun `failed reauthentication reports failure even when the previous session was connected`() = runBlocking {
        val rig = rig(connected = true)
        rig.holder.prepareAuthentication()
        rig.service.joinFailure = IllegalStateException("login failed")

        assertFalse(rig.holder.authenticate("secret", false))
        assertTrue(rig.holder.state.value.session.isConnected)
        assertIs<IllegalStateException>(rig.holder.state.value.failure)
        assertTrue(rig.log.isEmpty())
    }

    @Test
    fun `authentication rejects a different returned room identity`() = runBlocking {
        val rig = rig(connected = false)
        rig.holder.prepareAuthentication()
        rig.service.joinResult = rig.session.copy(isConnected = true, publicKey = Bytes(ByteArray(32) { 9 }))

        assertNull(rig.holder.authenticateForNavigation("secret", false))
        assertIs<com.meshcoreone.android.core.contracts.domain.PersistenceStoreException>(rig.holder.state.value.failure)
        assertFalse(rig.holder.state.value.session.isConnected)
        assertTrue(rig.log.isEmpty())
    }

    @Test
    fun `cancelling an in-flight authentication propagates and a second submit does not join`() = runBlocking {
        val rig = rig(connected = false)
        rig.holder.prepareAuthentication()
        rig.service.joinGate = CompletableDeferred()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            rig.holder.authenticateForNavigation("secret", false)
        }
        assertTrue(rig.holder.state.value.isAuthenticating)
        assertNull(rig.holder.authenticateForNavigation("second", true))
        assertEquals("secret:false", rig.service.joinCall)
        pending.cancelAndJoin()
        assertFalse(rig.holder.state.value.isAuthenticating)
        assertNull(rig.holder.state.value.failure)
        assertTrue(rig.log.isEmpty())
    }

    @Test
    @OriginalCase("RoomConversationView::disconnected banner reauthenticates the existing room")
    fun `reconnect uses existing session and reloads`() = runBlocking {
        val rig = rig(connected = false)

        rig.holder.reconnect()

        assertEquals(1, rig.service.reconnectCalls)
        assertTrue(rig.holder.state.value.session.isConnected)
        assertTrue(rig.log.contains("fetch"))
    }

    @Test
    @OriginalCase("RoomConversationViewModel::sendMessage appends accepted pending message")
    fun `accepted send appends and clears draft`() = runBlocking {
        val rig = rig(permission = RoomPermissionLevel.READ_WRITE)
        rig.holder.updateDraft("hello")

        assertTrue(rig.holder.send())

        assertEquals("", rig.holder.state.value.draft)
        assertEquals("hello", rig.holder.state.value.messages.single().text)
    }

    @Test
    @OriginalCase("RoomConversationViewModel::sendMessage leaves text available after failure")
    fun `failed send preserves draft and exposes typed failure`() = runBlocking {
        val rig = rig(permission = RoomPermissionLevel.READ_WRITE)
        rig.service.postFailure = IllegalStateException("offline")
        rig.holder.updateDraft("keep me")

        assertFalse(rig.holder.send())

        assertEquals("keep me", rig.holder.state.value.draft)
        assertIs<IllegalStateException>(rig.holder.state.value.failure)
        Unit
    }

    @Test
    @OriginalCase("RoomConversationViewModel::sendMessage cancellation propagates")
    fun `send cancellation is never converted to an error`() = runBlocking {
        val rig = rig(permission = RoomPermissionLevel.READ_WRITE)
        rig.service.postFailure = CancellationException("cancel")
        rig.holder.updateDraft("text")

        try {
            rig.holder.send()
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            assertNull(rig.holder.state.value.failure)
            assertEquals("text", rig.holder.state.value.draft)
        }
    }

    @Test
    @OriginalCase("RoomConversationViewModel::retryMessage replaces the stored failed message")
    fun `retry replaces matching message only`() = runBlocking {
        val failed = message(100u, status = MessageStatus.FAILED)
        val other = message(200u)
        val rig = rig(messages = listOf(failed, other))
        rig.holder.load()

        rig.holder.retry(failed.id)

        assertEquals(MessageStatus.DELIVERED, rig.holder.state.value.messages.first().status)
        assertEquals(other.id, rig.holder.state.value.messages.last().id)
    }

    private fun rig(
        messages: List<RoomMessageDTO> = emptyList(),
        connected: Boolean = true,
        permission: RoomPermissionLevel = RoomPermissionLevel.GUEST,
    ): Rig {
        val session = Fixtures.room(isConnected = connected).copy(permissionLevel = permission)
        val log = mutableListOf<String>()
        val data = FakeRoomData(session, messages.toMutableList(), log)
        val service = FakeRoomService(session, data)
        val dependencies = object : RoomConversationDependencies {
            override val data = data
            override val service = service
            override val events: Flow<RoomConversationEvent> = this@RoomConversationStateHolderTest.events
            override suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) { log += "notifications" }
            override suspend fun updateBadgeCount() { log += "badge" }
            override fun conversationsChanged() { log += "changed" }
        }
        return Rig(session, RoomConversationStateHolder(session, dependencies, scope), data, service, log)
    }

    private fun message(
        timestamp: UInt,
        text: String = "message",
        prefix: Int = 1,
        self: Boolean = false,
        status: MessageStatus = MessageStatus.DELIVERED,
    ) = RoomMessageDTO(
        sessionID = UUID(0, 10),
        authorKeyPrefix = Bytes.of(prefix),
        authorName = "Alice",
        text = text,
        timestamp = timestamp,
        createdAt = Instant.ofEpochSecond(timestamp.toLong()),
        isFromSelf = self,
        statusRawValue = status.rawValue,
    )
}

private data class Rig(
    val session: RemoteNodeSessionDTO,
    val holder: RoomConversationStateHolder,
    val data: FakeRoomData,
    val service: FakeRoomService,
    val log: MutableList<String>,
)

private class FakeRoomData(
    private var session: RemoteNodeSessionDTO,
    val messages: MutableList<RoomMessageDTO>,
    private val log: MutableList<String>,
) : RoomConversationDataSource {
    var contactFailure: Throwable? = null
    override suspend fun fetchMessages(session: RemoteNodeSessionDTO): List<RoomMessageDTO> {
        log += "fetch"
        return messages
    }
    override suspend fun refreshSession(session: RemoteNodeSessionDTO) = this.session
    override suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO {
        contactFailure?.let { throw it }
        return Fixtures.contact(name = session.name, typeRawValue = 3u, publicKey = session.publicKey)
    }
    override suspend fun markAsRead(session: RemoteNodeSessionDTO) { log += "read" }
    override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) { log += "failed" }
    fun updateSession(value: RemoteNodeSessionDTO) { session = value }
}

private class FakeRoomService(
    private val original: RemoteNodeSessionDTO,
    private val data: FakeRoomData,
) : RoomConversationService {
    var joinCall: String? = null
    var joinFailure: Throwable? = null
    var joinResult: RemoteNodeSessionDTO? = null
    var joinGate: CompletableDeferred<RemoteNodeSessionDTO>? = null
    var reconnectCalls = 0
    var postFailure: Throwable? = null

    override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean): RemoteNodeSessionDTO {
        joinCall = "$password:$rememberPassword"
        joinFailure?.let { throw it }
        return (joinGate?.await() ?: joinResult ?:
            original.copy(isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)).also(data::updateSession)
    }
    override suspend fun reconnect(session: RemoteNodeSessionDTO): RemoteNodeSessionDTO {
        reconnectCalls++
        return session.copy(isConnected = true).also(data::updateSession)
    }
    override suspend fun post(session: RemoteNodeSessionDTO, text: String): RoomMessageDTO {
        postFailure?.let { throw it }
        return RoomMessageDTO(
            sessionID = session.id,
            authorKeyPrefix = Bytes.of(9),
            authorName = "Me",
            text = text,
            timestamp = 500u,
            isFromSelf = true,
            statusRawValue = MessageStatus.PENDING.rawValue,
        ).also(data.messages::add)
    }
    override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID): RoomMessageDTO =
        data.messages.first { it.id == messageId }.copy(statusRawValue = MessageStatus.DELIVERED.rawValue)
}
