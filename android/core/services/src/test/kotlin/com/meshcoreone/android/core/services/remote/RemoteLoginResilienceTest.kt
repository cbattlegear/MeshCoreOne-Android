// AndroidOnly: WP-210 Login resilience cases the Swift screens never needed (encrypted storage can fail; the flood fallback is shared).
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteLoginResilienceTest {
    private val roomKey = remoteCoreKey(0xE5)
    private val repeaterKey = remoteCoreKey(0xE6)

    @TestFactory
    fun storageFailureCases(): List<DynamicTest> = listOf(
        remoteCoreNative("a password-store failure after a successful room login does not fail the join") {
            withRemoteAdminHarness {
                core.startMonitoring()
                val contact = remoteCoreContact(core.radioId, roomKey, ContactType.ROOM.rawValue, outPathLength = 1u, outPath = Bytes.of(0x0A))
                core.store.saveContact(contact)
                core.passwords.storeError = IllegalStateException("keystore unavailable")
                core.session.setSendLoginResults(listOf(Result.success(RemoteCoreFakeSession.sentInfo(1000u))))
                val join = async { rooms.joinRoom(core.radioId, contact, "pw", rememberPassword = true) }
                remoteAdminAnswerLogin(roomKey, 1u, admin = false)
                assertTrue(join.await().isConnected)
            }
        },
        remoteCoreNative("a password-store failure after a successful repeater login does not fail the connection") {
            withRemoteAdminHarness {
                core.startMonitoring()
                val contact = remoteCoreContact(core.radioId, repeaterKey, ContactType.REPEATER.rawValue, outPathLength = 1u, outPath = Bytes.of(0x0A))
                core.store.saveContact(contact)
                core.passwords.storeError = IllegalStateException("keystore unavailable")
                core.session.setSendLoginResults(listOf(Result.success(RemoteCoreFakeSession.sentInfo(1000u))))
                val connect = async { repeater.connectAsAdmin(core.radioId, contact, "pw", rememberPassword = true) }
                remoteAdminAnswerLogin(repeaterKey, 2u, admin = true)
                assertTrue(connect.await().isConnected)
            }
        },
        remoteCoreNative("onLoggedIn runs after the login and before the history sync") {
            withRemoteAdminHarness {
                core.startMonitoring()
                val contact = remoteCoreContact(core.radioId, roomKey, ContactType.ROOM.rawValue, outPathLength = 1u, outPath = Bytes.of(0x0A))
                core.store.saveContact(contact)
                core.session.setSendLoginResults(listOf(Result.success(RemoteCoreFakeSession.sentInfo(1000u))))
                core.session.requestStatusResult = Result.success(remoteAdminStatus(roomKey.prefix(6)))
                var statusRequestsAtCallback = -1
                val join = async {
                    rooms.joinRoom(core.radioId, contact, "pw", onLoggedIn = {
                        statusRequestsAtCallback = core.session.requestStatusInvocations.size
                    })
                }
                remoteAdminAnswerLogin(roomKey, 1u, admin = false)
                join.await()
                assertEquals(0, statusRequestsAtCallback)
                assertEquals(1, core.session.requestStatusInvocations.size)
            }
        },
    )

    @Test
    fun `a direct-path timeout resets the path and retries once with the flood sentinel`() = runBlocking {
        val attempts = mutableListOf<UByte>()
        val events = mutableListOf<String>()
        val result = LoginRouting.withFloodFallback(
            pathLength = 2u,
            resetPath = { events += "reset" },
            onFallback = { events += "fallback" },
        ) { path ->
            attempts += path
            if (path != PacketBuilder.FLOOD_PATH_SENTINEL) throw RemoteNodeError.Timeout()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf<UByte>(2u, PacketBuilder.FLOOD_PATH_SENTINEL), attempts)
        assertEquals(listOf("fallback", "reset"), events)
    }

    @Test
    fun `a flood timeout and a wrong password are not retried`() = runBlocking {
        var attempts = 0
        assertFailsWith<RemoteNodeError.Timeout> {
            LoginRouting.withFloodFallback<Unit>(PacketBuilder.FLOOD_PATH_SENTINEL, { error("no reset") }) {
                attempts++
                throw RemoteNodeError.Timeout()
            }
        }
        assertFailsWith<RemoteNodeError.LoginFailed> {
            LoginRouting.withFloodFallback<Unit>(2u, { error("no reset") }) {
                attempts++
                throw RemoteNodeError.LoginFailed("authentication failed")
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `the flood retry reports a failed reset instead of hiding the timeout`() = runBlocking {
        val error = assertFailsWith<IllegalStateException> {
            LoginRouting.withFloodFallback<Unit>(1u, { throw IllegalStateException("radio refused") }) {
                throw RemoteNodeError.Timeout()
            }
        }
        assertEquals("radio refused", error.message)
    }
}
