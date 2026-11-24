// AndroidOnly: WP-210 Native CLI path-recovery, fire-and-forget, password, slot cancellation and shutdown cases for RemoteNodeService.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteNodeCLIBehaviorTest {
    private val publicKey = remoteCoreKey(0xC1)

    private fun case(
        name: String, permission: RoomPermissionLevel = RoomPermissionLevel.ADMIN, outPathLength: UByte? = 1u,
        body: suspend CoroutineScope.(RemoteCoreHarness, EntityKey) -> Unit,
    ): DynamicTest = remoteCoreNative(name) {
        withRemoteCoreHarness {
            val key = addSession(remoteCoreSession(radioId, publicKey, RemoteNodeRole.REPEATER, permission))
            outPathLength?.let {
                val path = if (it == 0xFF.toUByte()) Bytes.EMPTY else Bytes.of(0x0A)
                store.saveContact(remoteCoreContact(radioId, publicKey, outPathLength = it, outPath = path))
            }
            startMonitoring()
            body(this@remoteCoreNative, this, key)
        }
    }

    private val RemoteCoreHarness.sentBodies: List<String>
        get() = session.sendCommandInvocations.map { CLIResponse.splitEchoedPrefix(it.command)?.body ?: it.command }

    private fun RemoteCoreHarness.lastPrefix(): String =
        CLIResponse.splitEchoedPrefix(session.sendCommandInvocations.last().command)?.prefix.orEmpty()

    @TestFactory
    fun recoveryCases(): List<DynamicTest> = listOf(
        case("a direct-path CLI timeout resets the path to flood and retries once with a fresh wire prefix") { h, key ->
            val command = async { h.service.sendCLICommand(key, "get tx", 300.milliseconds) }
            remoteCoreAwait("first send missing") { h.session.sendCommandInvocations.size == 1 }
            val firstPrefix = h.lastPrefix()
            remoteCoreAdvanceUntil(h.clock, "no flood retry") { h.session.sendCommandInvocations.size == 2 }
            assertEquals(listOf(publicKey), h.session.resetPathPublicKeys)
            assertEquals(true, h.store.contact(h.radioId, publicKey)?.isFloodRouted)
            assertEquals("00|", firstPrefix)
            assertEquals("01|", h.lastPrefix())
            h.yieldReply(firstPrefix + "> 21", publicKey)
            h.yieldReply(h.lastPrefix() + "> 22", publicKey)
            assertEquals("> 22", command.await())
        },
        case("a flood-routed contact's CLI timeout surfaces without reset or retry", outPathLength = 0xFFu) { h, key ->
            val command = async { runCatching { h.service.sendCLICommand(key, "get tx", 300.milliseconds) } }
            remoteCoreAdvanceUntil(h.clock, "never timed out") { command.isCompleted }
            assertIs<RemoteNodeError.Timeout>(command.await().exceptionOrNull())
            assertTrue(h.session.resetPathPublicKeys.isEmpty())
            assertEquals(1, h.session.sendCommandInvocations.size)
        },
        case("a failed path reset rethrows the original timeout without retrying") { h, key ->
            h.session.resetPathError = MeshCoreException.NotConnected()
            val command = async { runCatching { h.service.sendCLICommand(key, "get tx", 300.milliseconds) } }
            remoteCoreAdvanceUntil(h.clock, "never timed out") { command.isCompleted }
            assertIs<RemoteNodeError.Timeout>(command.await().exceptionOrNull())
            assertEquals(1, h.session.sendCommandInvocations.size)
            assertEquals(false, h.store.contact(h.radioId, publicKey)?.isFloodRouted)
        },
        case("reboot is fire-and-forget: one send, then timeout, never a path reset") { h, key ->
            val command = async {
                runCatching { h.service.sendRawCLICommand(key, " Reboot ", RemoteOperationTimeoutPolicy.fireAndForgetCLI) }
            }
            remoteCoreAdvanceUntil(h.clock, "never timed out") { command.isCompleted }
            assertIs<RemoteNodeError.Timeout>(command.await().exceptionOrNull())
            assertEquals(1, h.session.sendCommandInvocations.size)
            assertTrue(h.session.resetPathPublicKeys.isEmpty())
        },
        case("a send failure surfaces sessionError and frees the slot") { h, key ->
            h.session.sendCommandResult = Result.failure(MeshCoreException.NotConnected())
            val error = assertFailsWith<RemoteNodeError.SessionError> { h.service.sendCLICommand(key, "get tx") }
            assertIs<MeshCoreException.NotConnected>(error.error)
            h.session.sendCommandResult = Result.success(RemoteCoreFakeSession.sentInfo(100u))
            val next = async { h.service.sendCLICommand(key, "get tx") }
            remoteCoreAwait("slot leaked") { h.session.sendCommandInvocations.size == 2 }
            h.yieldReply("> 5", publicKey)
            assertEquals("> 5", next.await())
        },
    ) + listOf<UByte?>(1u, 0xFFu, null).map { route ->
        case("the live setting disables CLI recovery on route $route", outPathLength = route) { h, key ->
            val command = async { runCatching { h.service.sendCLICommand(key, "get tx", 300.milliseconds) } }
            remoteCoreAwait("first send missing") { h.session.sendCommandInvocations.size == 1 }
            h.floodFallbackEnabled = { false }
            remoteCoreAdvanceUntil(h.clock, "never timed out") { command.isCompleted }
            assertIs<RemoteNodeError.Timeout>(command.await().exceptionOrNull())
            assertTrue(h.session.resetPathPublicKeys.isEmpty())
            assertEquals(1, h.session.sendCommandInvocations.size)
        }
    } + listOf(
        case("an unknown route is eligible but a second CLI timeout never retries", outPathLength = null) { h, key ->
            val command = async { runCatching { h.service.sendCLICommand(key, "get tx", 300.milliseconds) } }
            remoteCoreAdvanceUntil(h.clock, "never timed out") { command.isCompleted }
            assertIs<RemoteNodeError.Timeout>(command.await().exceptionOrNull())
            assertEquals(listOf(publicKey), h.session.resetPathPublicKeys)
            assertEquals(2, h.session.sendCommandInvocations.size)
        },
    ) + listOf(false, true).map { cancelOwner ->
        case("CLI recovery cannot reset after ${if (cancelOwner) "owner cancellation" else "replacement"}") { h, key ->
            val entered = CompletableDeferred<Unit>()
            val allowed = CompletableDeferred<Boolean>()
            h.floodFallbackEnabled = { entered.complete(Unit); allowed.await() }
            val command = async { runCatching { h.service.sendCLICommand(key, "get tx", 300.milliseconds) } }
            remoteCoreAdvanceUntil(h.clock, "recovery did not read the setting") { entered.isCompleted }
            if (cancelOwner) h.scope.cancel() else h.recoveryOwnerCurrent = false
            allowed.complete(true)
            assertIs<CancellationException>(command.await().exceptionOrNull())
            assertTrue(h.session.resetPathPublicKeys.isEmpty())
            assertEquals(1, h.session.sendCommandInvocations.size)
        }
    }

    @TestFactory
    fun accessCases(): List<DynamicTest> = listOf(
        case("non-admin CLI is denied before anything is sent", permission = RoomPermissionLevel.READ_WRITE) { h, key ->
            assertFailsWith<RemoteNodeError.PermissionDenied> { h.service.sendCLICommand(key, "get tx") }
            assertFailsWith<RemoteNodeError.SessionNotFound> {
                h.service.sendCLICommand(EntityKey(h.radioId, java.util.UUID.randomUUID()), "get tx")
            }
            assertTrue(h.session.sendCommandInvocations.isEmpty())
        },
        case("an admin password change clears the stored password; a guest password change keeps it") { h, key ->
            h.passwords.storePassword("old", publicKey)
            val guest = async { h.service.sendRawCLICommand(key, "set guest.password x") }
            remoteCoreAwait("guest change not sent") { h.session.sendCommandInvocations.size == 1 }
            h.yieldReply(h.lastPrefix() + "OK", publicKey)
            guest.await()
            assertEquals("old", h.passwords.stored(publicKey))

            val admin = async { h.service.sendRawCLICommand(key, "password hunter2") }
            remoteCoreAwait("admin change not sent") { h.session.sendCommandInvocations.size == 2 }
            h.yieldReply(h.lastPrefix() + "password now: hunter2", publicKey)
            assertEquals("password now: hunter2", admin.await())
            assertNull(h.passwords.stored(publicKey))
        },
    )

    @TestFactory
    fun concurrencyCases(): List<DynamicTest> = listOf(
        case("cancelling a queued CLI caller removes it from the FIFO; later commands keep their order") { h, key ->
            val first = async { h.service.sendCLICommand(key, "get tx") }
            remoteCoreAwait("first not sent") { h.session.sendCommandInvocations.size == 1 }
            val second = async { h.service.sendCLICommand(key, "get lat") }
            val third = async { h.service.sendCLICommand(key, "get radio") }
            remoteCoreSettle()
            second.cancelAndJoin()
            h.yieldReply("> 22", publicKey)
            assertEquals("> 22", first.await())
            remoteCoreAwait("third never sent") { h.session.sendCommandInvocations.size == 2 }
            assertEquals(listOf("get tx", "get radio"), h.sentBodies)
            h.yieldReply(h.lastPrefix() + "> 915.000,250.0,10,5", publicKey)
            assertEquals("> 915.000,250.0,10,5", third.await())
        },
        case("cancelling the in-flight CLI caller releases the slot and drops its pending request") { h, key ->
            val first = async { h.service.sendCLICommand(key, "get tx") }
            remoteCoreAwait("first not sent") { h.session.sendCommandInvocations.size == 1 }
            val second = async { h.service.sendCLICommand(key, "get name") }
            first.cancelAndJoin()
            remoteCoreAwait("second never sent") { h.session.sendCommandInvocations.size == 2 }
            assertNotEquals(CLIResponse.splitEchoedPrefix(h.session.sendCommandInvocations[0].command)?.prefix, h.lastPrefix())
            h.yieldReply(h.lastPrefix() + "Alpha", publicKey)
            assertEquals("Alpha", second.await())
        },
        case("a slot granted to a caller cancelled at that moment is passed on, never leaked") { h, key ->
            val first = async { h.service.sendCLICommand(key, "get tx") }
            remoteCoreAwait("first not sent") { h.session.sendCommandInvocations.size == 1 }
            val second = async { h.service.sendCLICommand(key, "get lat") }
            remoteCoreSettle()
            // Same scheduler turn: `first` unwinds and hands the slot to `second`, which is already
            // cancelled and must pass the grant on instead of leaking the slot.
            first.cancel()
            second.cancel()
            first.join()
            second.join()
            assertEquals(1, h.session.sendCommandInvocations.size)
            val third = async { h.service.sendCLICommand(key, "get radio") }
            remoteCoreAwait("slot leaked by the cancelled grantee") { h.session.sendCommandInvocations.size == 2 }
            assertTrue(synchronized(h.service.lock) { h.service.cliSlotWaiters.isEmpty() })
            h.yieldReply(h.lastPrefix() + "> 915.000,250.0,10,5", publicKey)
            assertEquals("> 915.000,250.0,10,5", third.await())
        },
        case("close fails the in-flight CLI caller and queued waiters with cancelled") { h, key ->
            val first = async { runCatching { h.service.sendCLICommand(key, "get tx") } }
            remoteCoreAwait("first not sent") { h.session.sendCommandInvocations.size == 1 }
            val second = async { runCatching { h.service.sendCLICommand(key, "get lat") } }
            remoteCoreSettle()
            h.service.close()
            assertIs<RemoteNodeError.Cancelled>(first.await().exceptionOrNull())
            assertIs<RemoteNodeError.Cancelled>(second.await().exceptionOrNull())
            assertEquals(1, h.session.sendCommandInvocations.size)
        },
    )
}
