// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.rooms

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.rooms.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class RoomKeepAliveComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = RemoteNodeSessionDTO(
        radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 42 }),
        name = "Visible room", role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
    )
    private val log = mutableListOf<String>()
    private val sources = MutableStateFlow<RoomSessionKeepAlive?>(source("original"))
    private val mounted = mutableStateOf<RoomConversationStateHolder?>(null)

    private fun source(name: String) = RoomSessionKeepAlive {
        log += "start:$name:${it.id}"
        AutoCloseable { log += "stop:$name:${it.id}" }
    }

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().setContent {
            MeshCoreTheme(theme = ThemeRegistry.default, systemDark = false, motionScale = 0f) {
                mounted.value?.let { holder ->
                    RoomConversationRoute(holder, ConversationLinkActions(
                        contacts = { emptyList() }, connectedDeviceName = { "Me" },
                        radioId = { session.radioId }, route = { false }, openExternal = {},
                        navigateToContact = {},
                    ))
                }
            }
        }
    }

    @After fun destroyHost() {
        scope.cancel()
        host.pause().stop().destroy()
    }

    private inner class Fixture(var room: RemoteNodeSessionDTO = session) : RoomConversationDependencies {
        override val keepAlive = sources
        override val events = MutableSharedFlow<RoomConversationEvent>(extraBufferCapacity = 8)
        override val diagnostics = RoomConversationDiagnostics { operation, failure -> throw AssertionError(operation, failure) }
        override val data = object : RoomConversationDataSource {
            override suspend fun fetchMessages(session: RemoteNodeSessionDTO) = emptyList<RoomMessageDTO>()
            override suspend fun refreshSession(session: RemoteNodeSessionDTO) = room
            override suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO? = null
            override suspend fun markAsRead(session: RemoteNodeSessionDTO) = Unit
            override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) = Unit
        }
        override val service = object : RoomConversationService {
            override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean) = error("Unexpected join")
            override suspend fun reconnect(session: RemoteNodeSessionDTO) = error("Unexpected reconnect")
            override suspend fun post(session: RemoteNodeSessionDTO, text: String) = error("Unexpected post")
            override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID) = error("Unexpected retry")
        }
        val holder = RoomConversationStateHolder(room, this, scope)
        fun refresh(room: RemoteNodeSessionDTO) {
            this.room = room
            compose.runOnIdle { scope.launch { holder.refreshSession() } }
            compose.waitForIdle()
        }
    }

    private fun mount(fixture: Fixture) {
        compose.runOnIdle { mounted.value = fixture.holder }
        compose.waitForIdle()
    }
    private fun expected(vararg actions: String) = actions.map { "$it:${session.id}" }

    @Test fun appearanceRecompositionPauseResumeAndDisappearanceOwnExactlyOneLoop() {
        val fixture = Fixture()
        mount(fixture)
        assertEquals(expected("start:original"), log)
        compose.runOnIdle { fixture.holder.updateDraft("unrelated recomposition") }
        compose.waitForIdle()
        fixture.refresh(session.copy(unreadCount = 12))
        assertEquals(expected("start:original"), log)
        compose.runOnIdle { host.pause().stop() }
        assertEquals(expected("start:original", "stop:original"), log)
        compose.runOnIdle { host.start().resume() }
        compose.waitForIdle()
        assertEquals(expected("start:original", "stop:original", "start:original"), log)
        compose.runOnIdle { mounted.value = null }
        compose.waitForIdle()
        assertEquals(expected("start:original", "stop:original", "start:original", "stop:original"), log)
    }

    @Test fun disconnectedRoomStartsOnConnectionStopsOnLossAndWaitsForForeground() {
        val fixture = Fixture(session.copy(isConnected = false))
        mount(fixture)
        assertTrue(log.isEmpty())
        fixture.refresh(session)
        assertEquals(expected("start:original"), log)
        fixture.refresh(session.copy(isConnected = false))
        assertEquals(expected("start:original", "stop:original"), log)
        compose.runOnIdle { host.pause().stop() }
        fixture.refresh(session)
        assertEquals(expected("start:original", "stop:original"), log)
        compose.runOnIdle { host.start().resume() }
        compose.waitForIdle()
        assertEquals(expected("start:original", "stop:original", "start:original"), log)
    }

    @Test fun liveGraphReplacementAndWithdrawalCloseTheCapturedOwner() {
        val fixture = Fixture()
        mount(fixture)
        compose.runOnIdle { sources.value = source("replacement") }
        compose.waitForIdle()
        assertEquals(expected("start:original", "stop:original", "start:replacement"), log)
        compose.runOnIdle { sources.value = null }
        compose.waitForIdle()
        assertEquals(expected("start:original", "stop:original", "start:replacement", "stop:replacement"), log)
        compose.runOnIdle { sources.value = source("reconnected") }
        compose.waitForIdle()
        assertEquals(expected("start:original", "stop:original", "start:replacement", "stop:replacement", "start:reconnected"), log)
    }

    @Test fun replacingTheMountedRoomStopsTheOldIdentityAndStartsTheNewOne() {
        mount(Fixture())
        val next = session.copy(id = UUID.randomUUID(), publicKey = Bytes(ByteArray(32) { 43 }))
        mount(Fixture(next))
        assertEquals(expected("start:original", "stop:original") + "start:original:${next.id}", log)
        compose.runOnIdle { mounted.value = null }
        compose.waitForIdle()
        assertEquals(
            expected("start:original", "stop:original") + listOf("start:original:${next.id}", "stop:original:${next.id}"),
            log,
        )
    }
}
