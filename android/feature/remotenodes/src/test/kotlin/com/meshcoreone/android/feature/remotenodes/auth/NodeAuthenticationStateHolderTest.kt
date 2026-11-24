// AndroidOnly: WP-313 Native checks of the login sheet flow (no Swift test covers NodeAuthenticationSheet).
package com.meshcoreone.android.feature.remotenodes.auth

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeLoginPort
import com.meshcoreone.android.feature.remotenodes.support.TEST_RADIO
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.bytes
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.support.session
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import org.junit.Test

class NodeAuthenticationStateHolderTest {
    private class Timeout : Exception("timeout")

    private object Faults : RemoteNodeFaultClassifier {
        override fun isTimeout(error: Throwable) = error is Timeout
        override fun isRemoteNoResponseYet(error: Throwable) = false
        override fun isBinarySessionTimeout(error: Throwable) = false
    }

    private class FakeLogin : RemoteNodeLoginPort {
        val calls = mutableListOf<String>()
        var saved: String? = null
        var failures = ArrayDeque<Exception>()
        var timeoutSeconds: Long? = null
        var hang = false
        override suspend fun retrievePassword(contact: ContactDTO) = saved
        override suspend fun deletePassword(contact: ContactDTO) { calls += "delete" }
        override suspend fun resetPath(radioId: RadioId, publicKey: Bytes) { calls += "reset" }
        override suspend fun connectAsAdmin(
            radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
            onTimeoutKnown: suspend (Long) -> Unit,
        ) = login("admin", password, rememberPassword, pathLength, onTimeoutKnown)
        override suspend fun joinRoom(
            radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
            onTimeoutKnown: suspend (Long) -> Unit,
        ) = login("join", password, rememberPassword, pathLength, onTimeoutKnown)

        private suspend fun login(kind: String, password: String, remember: Boolean, path: UByte, onTimeout: suspend (Long) -> Unit): RemoteNodeSessionDTO {
            calls += "$kind:$password:$remember:$path"
            timeoutSeconds?.let { onTimeout(it) }
            if (hang) awaitCancellation()
            failures.removeFirstOrNull()?.let { throw it }
            return session()
        }
    }

    private fun contact(outPathLength: UByte) = ContactDTO(
        radioId = TEST_RADIO, publicKey = bytes(32, 0x33), name = "Node", outPathLength = outPathLength,
        outPath = bytes(2, 0x10), lastHeardTimestamp = null,
    )

    private fun holder(
        scope: CoroutineScope, login: FakeLogin, contact: ContactDTO = contact(2u), role: RemoteNodeRole = RemoteNodeRole.REPEATER,
        radio: RadioId? = TEST_RADIO, clock: VirtualClock = VirtualClock(), successes: MutableList<RemoteNodeSessionDTO> = mutableListOf(),
    ) = NodeAuthenticationStateHolder(contact, role, { login }, { radio }, Faults, clock, scope) { successes += it }

    @Test
    fun `direct-path timeout retries once via flood after resetting the path`() = runSuspend {
        val login = FakeLogin().apply { failures.add(Timeout()) }
        val successes = mutableListOf<RemoteNodeSessionDTO>()
        val holder = holder(this, login, successes = successes)
        holder.setPassword("secret")
        holder.authenticate().join()
        assertEquals(listOf("admin:secret:true:2", "reset", "admin:secret:true:${PacketBuilder.FLOOD_PATH_SENTINEL}"), login.calls)
        assertEquals(1, successes.size)
        assertTrue(holder.state.value.didResetPath)
        assertFalse(holder.state.value.isRetryingViaFlood)
        assertTrue(holder.state.value.isAuthenticating, "Swift dismisses without clearing the spinner")
    }

    @Test
    fun `flood toggle resets the path once and later attempts stay flooded`() = runSuspend {
        val login = FakeLogin().apply { failures.add(IllegalStateException("bad password")) }
        val holder = holder(this, login, role = RemoteNodeRole.ROOM_SERVER)
        holder.setUseFloodRouting(true)
        holder.setPassword("p")
        holder.authenticate().join()
        assertIs<RemoteNodesText.Failure>(holder.state.value.errorMessage)
        holder.setUseFloodRouting(false)
        holder.authenticate().join()
        val flood = PacketBuilder.FLOOD_PATH_SENTINEL
        assertEquals(listOf("reset", "join:p:true:$flood", "join:p:true:$flood"), login.calls)
    }

    @Test
    fun `flood-routed contact never resets and a flood timeout is reported`() = runSuspend {
        val login = FakeLogin().apply { failures.add(Timeout()) }
        val holder = holder(this, login, contact = contact(255u))
        assertFalse(holder.hasStoredPath)
        holder.setUseFloodRouting(false)
        assertTrue(holder.state.value.useFloodRouting)
        holder.authenticate().join()
        assertEquals(listOf("admin::true:${PacketBuilder.FLOOD_PATH_SENTINEL}"), login.calls)
        assertEquals(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesStatusRequestTimedOut), holder.state.value.errorMessage)
        assertFalse(holder.state.value.isAuthenticating)
    }

    @Test
    fun `passwords truncate to fifteen characters and an unchecked saved password is deleted`() = runSuspend {
        val login = FakeLogin().apply { saved = "abcdefghijklmnopq👍🏽" }
        val holder = holder(this, login)
        holder.loadSavedPassword()
        assertTrue(holder.state.value.hasSavedPassword)
        holder.setRememberPassword(false)
        holder.authenticate().join()
        assertEquals(listOf("admin:abcdefghijklmno:false:2", "delete"), login.calls)
        assertEquals("ab👍🏽", NodeAuthenticationStateHolder.truncatedPassword("ab👍🏽"))
    }

    @Test
    fun `sensor passwords are checked in UTF-8 bytes before login or path reset`() = runSuspend {
        val login = FakeLogin()
        val holder = holder(this, login, role = RemoteNodeRole.SENSOR)
        holder.setUseFloodRouting(true)
        holder.setPassword("é".repeat(8))
        val warning = RemoteNodesText.resource(R.string.sensor_password_byte_limit, 15)
        assertEquals(listOf(warning), NodeAuthenticationPresentation.authenticationFooter(holder.state.value, RemoteNodeRole.SENSOR))
        holder.authenticate().join()
        assertEquals(warning, holder.state.value.errorMessage)
        assertTrue(login.calls.isEmpty())
        holder.setPassword("é".repeat(7) + "a")
        assertTrue(NodeAuthenticationPresentation.authenticationFooter(holder.state.value, RemoteNodeRole.SENSOR).isEmpty())
        holder.setPassword("a".repeat(16))
        assertEquals(listOf(warning), NodeAuthenticationPresentation.authenticationFooter(holder.state.value, RemoteNodeRole.SENSOR))
    }

    @Test
    fun `missing radio fails as not connected`() = runSuspend {
        val holder = holder(this, FakeLogin(), radio = null)
        holder.authenticate().join()
        assertIs<RemoteNodeNotConnectedException>((holder.state.value.errorMessage as RemoteNodesText.Failure).error)
        assertFalse(holder.state.value.isAuthenticating)
    }

    @Test
    fun `countdown follows the firmware timeout and cancel clears it`() = runSuspend {
        val clock = VirtualClock()
        val login = FakeLogin().apply { timeoutSeconds = 3; hang = true }
        val holder = holder(this, login, clock = clock)
        val job = holder.authenticate()
        repeat(10) { yield() }
        assertEquals(0, holder.state.value.authSecondsRemaining)
        holder.cancel()
        job.join()
        assertNull(holder.state.value.authSecondsRemaining)
        assertFalse(holder.state.value.isAuthenticating)
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `presentation picks footers paths and announcements`() {
        val base = NodeAuthenticationState(useFloodRouting = false)
        assertTrue(NodeAuthenticationPresentation.authenticationFooter(base, RemoteNodeRole.REPEATER).isEmpty())
        assertEquals(
            listOf(RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_auth_passwordtoolongrooms, 15)),
            NodeAuthenticationPresentation.authenticationFooter(base.copy(password = "x".repeat(16)), RemoteNodeRole.ROOM_SERVER),
        )
        assertEquals(
            listOf(
                RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesAuthFloodRetryStatus),
                RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_auth_secondsremaining, 7),
            ),
            NodeAuthenticationPresentation.authenticationFooter(base.copy(isRetryingViaFlood = true, authSecondsRemaining = 7), RemoteNodeRole.REPEATER),
        )
        assertTrue(NodeAuthenticationPresentation.shouldAnnounce(null, 40))
        assertFalse(NodeAuthenticationPresentation.shouldAnnounce(40, 39))
        assertTrue(NodeAuthenticationPresentation.shouldAnnounce(32, 29))
        assertTrue(NodeAuthenticationPresentation.shouldAnnounce(6, 5))
        assertFalse(NodeAuthenticationPresentation.shouldAnnounce(1, 0))
        assertEquals(NodeAuthenticationPresentation.PathRows.HOPS, NodeAuthenticationPresentation.pathRows(true, false, true))
        assertEquals(NodeAuthenticationPresentation.PathRows.SUMMARY, NodeAuthenticationPresentation.pathRows(true, false, false))
        assertEquals(NodeAuthenticationPresentation.PathRows.NO_ROUTE, NodeAuthenticationPresentation.pathRows(false, true, false))
        assertEquals(NodeAuthenticationPresentation.PathRows.NONE, NodeAuthenticationPresentation.pathRows(true, true, true))
    }
}
