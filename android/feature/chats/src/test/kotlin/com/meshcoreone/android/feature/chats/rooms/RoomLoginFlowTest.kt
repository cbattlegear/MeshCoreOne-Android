// AndroidOnly: WP-310 Room sign-in behavior the Swift sheet inherited from NodeAuthenticationSheet: guest login, 15-character limit, saved password, progress.
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.RemoteNodeFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class RoomLoginFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Service(private val room: RemoteNodeSessionDTO) : RoomConversationService {
        var saved: String? = null
        var savedFailure: Exception? = null
        var joinPassword: String? = "unset"
        var joinFailure: Exception? = null
        var progress: List<RoomLoginProgress> = emptyList()
        var forgotten = 0
        var progressSeenDuringJoin: RoomLoginProgress? = null
        var holderState: (() -> RoomConversationState)? = null

        override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean) =
            error("the progress overload must be used")
        override suspend fun join(
            contact: ContactDTO, password: String?, rememberPassword: Boolean, onProgress: (RoomLoginProgress) -> Unit,
        ): RemoteNodeSessionDTO {
            joinPassword = password
            progress.forEach {
                onProgress(it)
                progressSeenDuringJoin = holderState?.invoke()?.loginProgress
            }
            joinFailure?.let { throw it }
            return room.copy(isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
        }
        override suspend fun savedPassword(contact: ContactDTO): String? {
            savedFailure?.let { throw it }
            return saved
        }
        override suspend fun forgetPassword(contact: ContactDTO) { forgotten++ }
        override suspend fun reconnect(session: RemoteNodeSessionDTO) = session
        override suspend fun post(session: RemoteNodeSessionDTO, text: String): RoomMessageDTO = error("unused")
        override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID): RoomMessageDTO = error("unused")
    }

    private fun rig(clock: () -> Long = { 1_000L }): Pair<RoomConversationStateHolder, Service> {
        val room = Fixtures.room(isConnected = false)
        val service = Service(room)
        val dependencies = object : RoomConversationDependencies {
            override val data = object : RoomConversationDataSource {
                override suspend fun fetchMessages(session: RemoteNodeSessionDTO) = emptyList<RoomMessageDTO>()
                override suspend fun refreshSession(session: RemoteNodeSessionDTO) = session
                override suspend fun contactFor(session: RemoteNodeSessionDTO) =
                    Fixtures.contact(name = session.name, typeRawValue = 3u, publicKey = session.publicKey)
                override suspend fun markAsRead(session: RemoteNodeSessionDTO) = Unit
                override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) = Unit
            }
            override val service = service
            override val events = emptyFlow<RoomConversationEvent>()
        }
        val holder = RoomConversationStateHolder(room, dependencies, scope, nowMillis = clock)
        service.holderState = { holder.state.value }
        return holder to service
    }

    @Test
    fun `an empty password is a guest login and is never swapped for the stored one`() = runBlocking {
        val (holder, service) = rig()
        holder.prepareAuthentication()
        holder.authenticateForNavigation("", true)
        assertEquals("", service.joinPassword)
    }

    @Test
    fun `a null password still lets the service use the stored one`() = runBlocking {
        val (holder, service) = rig()
        holder.prepareAuthentication()
        holder.authenticateForNavigation(null, true)
        assertNull(service.joinPassword)
    }

    @Test
    fun `a pasted line break never reaches the node`() = runBlocking {
        val (holder, service) = rig()
        holder.prepareAuthentication()
        holder.authenticateForNavigation("secret\r\n", true)
        assertEquals("secret", service.joinPassword)
    }

    @Test
    fun `passwords are cut to fifteen characters and emoji count once`() = runBlocking {
        val (holder, service) = rig()
        holder.prepareAuthentication()
        holder.authenticateForNavigation("abcdefghijklmnopqrst", true)
        assertEquals("abcdefghijklmno", service.joinPassword)
        holder.authenticateForNavigation("abcdefghijklmn👍🏽xyz", true)
        assertEquals("abcdefghijklmn👍🏽", service.joinPassword)
        assertEquals(1, RoomConversationStateHolder.characterCount("👍🏽"))
    }

    @Test
    fun `the saved password is offered to the sheet and a read failure reads as none`() = runBlocking {
        val (holder, service) = rig()
        service.saved = "hunter2"
        holder.prepareAuthentication()
        assertEquals("hunter2", holder.state.value.savedPassword)
        service.savedFailure = IllegalStateException("keystore locked")
        holder.prepareAuthentication()
        assertNull(holder.state.value.savedPassword)
        assertTrue(holder.state.value.authenticationLookupComplete)
        assertNull(holder.state.value.failure)
    }

    @Test
    fun `unchecking remember after a saved password forgets it once the login succeeds`() = runBlocking {
        val (holder, service) = rig()
        service.saved = "hunter2"
        holder.prepareAuthentication()
        service.joinFailure = IllegalStateException("no")
        holder.authenticateForNavigation("hunter2", false)
        assertEquals(0, service.forgotten, "a failed login keeps the saved password")
        service.joinFailure = null
        holder.authenticateForNavigation("hunter2", false)
        assertEquals(1, service.forgotten)
        service.saved = null
        holder.prepareAuthentication()
        holder.authenticateForNavigation("x", false)
        assertEquals(1, service.forgotten, "nothing saved, nothing to forget")
    }

    @Test
    fun `progress is visible while joining and cleared afterwards`() = runBlocking {
        var now = 5_000L
        val (holder, service) = rig(clock = { now })
        holder.prepareAuthentication()
        service.progress = listOf(RoomLoginProgress.AwaitingReply(20), RoomLoginProgress.SyncingHistory)
        holder.authenticateForNavigation("pw", true)
        assertEquals(RoomLoginProgress.SyncingHistory, service.progressSeenDuringJoin)
        assertNull(holder.state.value.loginProgress)
        assertNull(holder.state.value.authenticationStartedAtMillis)
        assertFalse(holder.state.value.isAuthenticating)
        now += 1
    }

    @Test
    fun `a failed login keeps the contact so Retry can run again and clears progress`() = runBlocking {
        val (holder, service) = rig()
        holder.prepareAuthentication()
        service.progress = listOf(RoomLoginProgress.RetryingViaFlood)
        service.joinFailure = MessageServiceException(MessageServiceError.NotConnected)
        assertNull(holder.authenticateForNavigation("pw", true))
        assertNotNull(holder.state.value.authenticationContact)
        assertNull(holder.state.value.loginProgress)
        assertNotNull(holder.state.value.failure)
        service.joinFailure = null
        assertNotNull(holder.authenticateForNavigation("pw", true))
        assertNull(holder.state.value.failure)
    }

    private class Carrier(override val sourceServiceFault: SourceServiceFault) : Exception(), SourceServiceFaultCarrier

    @Test
    fun `failures split into wrong password no reply and not connected`() {
        assertEquals(RoomLoginFailureKind.WRONG_PASSWORD, RoomLoginFailureKind.of(Carrier(RemoteNodeFault.LoginFailed("authentication failed"))))
        assertEquals(RoomLoginFailureKind.NO_RESPONSE, RoomLoginFailureKind.of(Carrier(RemoteNodeFault.Timeout)))
        assertEquals(RoomLoginFailureKind.NOT_CONNECTED, RoomLoginFailureKind.of(Carrier(RemoteNodeFault.NotConnected)))
        assertEquals(RoomLoginFailureKind.NOT_CONNECTED, RoomLoginFailureKind.of(MessageServiceException(MessageServiceError.NotConnected)))
        assertEquals(RoomLoginFailureKind.OTHER, RoomLoginFailureKind.of(Carrier(RemoteNodeFault.RadioContactsFull)))
        assertEquals(RoomLoginFailureKind.OTHER, RoomLoginFailureKind.of(IllegalStateException("boom")))
        assertNull(RoomLoginFailureKind.OTHER.messageResource())
        assertNotNull(RoomLoginFailureKind.WRONG_PASSWORD.messageResource())
    }
}
