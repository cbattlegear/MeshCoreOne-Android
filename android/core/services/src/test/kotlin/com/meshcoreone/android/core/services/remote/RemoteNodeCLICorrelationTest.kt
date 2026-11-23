// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RemoteNodeCLICorrelationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RoomPermissionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CLI response correlation: one command in flight per node, replies that don't match the pending
 * command's shape are dropped, and queued commands wait for the slot instead of racing for replies.
 */
class RemoteNodeCLICorrelationTest {
    private val publicKey = remoteCoreKey(0xCC)

    private fun case(name: String, body: suspend CoroutineScope.(RemoteCoreHarness, EntityKey) -> Unit): DynamicTest =
        remoteCoreOriginal("RemoteNodeCLICorrelationTests", name) {
            withRemoteCoreHarness {
                val key = addSession(remoteCoreSession(radioId, publicKey, permissionLevel = RoomPermissionLevel.ADMIN))
                startMonitoring()
                body(this@remoteCoreOriginal, this, key)
            }
        }

    private suspend fun RemoteCoreHarness.awaitSent(count: Int, what: String = "command was never sent") =
        remoteCoreAwait(what) { session.sendCommandInvocations.size == count }

    /** The wire prefix of the most recently sent command. */
    private fun RemoteCoreHarness.sentWirePrefix(): String {
        val sent = session.sendCommandInvocations.lastOrNull()?.command.orEmpty()
        return assertNotNull(CLIResponse.splitEchoedPrefix(sent)).prefix
    }

    @TestFactory
    fun sourceCases(): List<DynamicTest> = shapeCases() + wirePrefixCases() + radioCliCases()

    private fun radioCliCases() = listOf(
        case("radio CLI poller retains both caller and radio owner guards through dispatch admission") { h, _ ->
            h.store.saveContact(remoteCoreContact(h.radioId, publicKey))
            for (retireOwner in listOf(false, true)) {
                val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val release = kotlinx.coroutines.CompletableDeferred<Unit>()
                var callerCurrent = true
                h.recoveryOwnerCurrent = true
                h.session.beforeRadioCLICommand = {
                    entered.complete(Unit)
                    release.await()
                    assertNotNull(kotlinx.coroutines.currentCoroutineContext()[
                        com.meshcoreone.android.core.protocol.session.ProtocolDispatchGuard
                    ]).requireCurrent()
                }
                val command = async {
                    runCatching {
                        kotlinx.coroutines.withContext(
                            com.meshcoreone.android.core.protocol.session.ProtocolDispatchGuard { callerCurrent },
                        ) { h.service.sendRadioCLICommand(h.radioId, publicKey, "ver") }
                    }
                }
                entered.await()
                if (retireOwner) h.recoveryOwnerCurrent = false else callerCurrent = false
                release.complete(Unit)
                assertFailsWith<RemoteNodeError.Cancelled> { command.await().getOrThrow() }
                assertTrue(h.session.sendRadioCLICommandInvocations.isEmpty())
            }
        },
        case("radio CLI queued behind admin rejects an owner retired before slot admission") { h, key ->
            h.store.saveContact(remoteCoreContact(h.radioId, publicKey))
            val admin = async { h.service.sendCLICommand(key, "get tx") }
            h.awaitSent(1)
            val command = async { runCatching { h.service.sendRadioCLICommand(h.radioId, publicKey, "ver") } }
            remoteCoreSettle()
            h.recoveryOwnerCurrent = false
            h.yieldReply("> 22", publicKey)
            assertEquals("> 22", admin.await())
            assertFailsWith<kotlinx.coroutines.CancellationException> { command.await().getOrThrow() }
            assertTrue(h.session.sendRadioCLICommandInvocations.isEmpty())
        },
        case("radio CLI uses its own wire type and ignores stale and unprefixed replies") { h, _ ->
            h.store.saveContact(remoteCoreContact(h.radioId, publicKey))
            val first = async { h.service.sendRadioCLICommand(h.radioId, publicKey, "ver") }
            remoteCoreAwait("radio command was never sent") { h.session.sendRadioCLICommandInvocations.size == 1 }
            val firstPrefix = assertNotNull(CLIResponse.splitEchoedPrefix(h.session.sendRadioCLICommandInvocations.last().command)).prefix
            first.cancelAndJoin()
            val second = async { h.service.sendRadioCLICommand(h.radioId, publicKey, "ver") }
            remoteCoreAwait("replacement was never sent") { h.session.sendRadioCLICommandInvocations.size == 2 }
            val secondPrefix = assertNotNull(CLIResponse.splitEchoedPrefix(h.session.sendRadioCLICommandInvocations.last().command)).prefix
            h.yieldReply(firstPrefix + "stale", publicKey)
            h.yieldReply("unprefixed", publicKey)
            remoteCoreSettle()
            assertTrue(!second.isCompleted)
            h.yieldReply(secondPrefix + "current", publicKey)
            assertEquals("current", second.await())
            assertTrue(h.session.sendCommandInvocations.isEmpty())
        },
        case("radio CLI timeout does not reset or retry the contact route") { h, _ ->
            h.store.saveContact(remoteCoreContact(h.radioId, publicKey, outPathLength = 1u))
            val command = async { runCatching { h.service.sendRadioCLICommand(h.radioId, publicKey, "ver", 300.milliseconds) } }
            remoteCoreAwait("radio command was never sent") { h.session.sendRadioCLICommandInvocations.size == 1 }
            remoteCoreAdvanceUntil(h.clock, "radio command never timed out") { command.isCompleted }
            assertFailsWith<RemoteNodeError.Timeout> { command.await().getOrThrow() }
            assertTrue(h.session.resetPathPublicKeys.isEmpty())
            assertEquals(1, h.session.sendRadioCLICommandInvocations.size)
        },
        case("radio CLI rejects blocked and non-chat contacts") { h, _ ->
            for (contact in listOf(
                remoteCoreContact(h.radioId, publicKey).copy(isBlocked = true),
                remoteCoreContact(h.radioId, publicKey, typeRawValue = 4u),
                remoteCoreContact(h.radioId, publicKey, typeRawValue = 255u),
            )) {
                h.store.saveContact(contact)
                assertFailsWith<RemoteNodeError.PermissionDenied> { h.service.sendRadioCLICommand(h.radioId, publicKey, "ver") }
            }
            assertTrue(h.session.sendRadioCLICommandInvocations.isEmpty())
        },
        case("radio CLI waits for the destination slot and rechecks eligibility") { h, key ->
            h.store.saveContact(remoteCoreContact(h.radioId, publicKey))
            val admin = async { h.service.sendCLICommand(key, "get tx") }
            h.awaitSent(1)
            val contactCommand = async { runCatching { h.service.sendRadioCLICommand(h.radioId, publicKey, "ver") } }
            remoteCoreSettle()
            assertTrue(h.session.sendRadioCLICommandInvocations.isEmpty())
            h.store.saveContact(remoteCoreContact(h.radioId, publicKey).copy(isBlocked = true))
            h.yieldReply("> 22", publicKey)
            assertEquals("> 22", admin.await())
            assertFailsWith<RemoteNodeError.PermissionDenied> { contactCommand.await().getOrThrow() }
            assertTrue(h.session.sendRadioCLICommandInvocations.isEmpty())
        },
    )

    private fun shapeCases() = listOf(
        case("matching reply resolves the pending command") { h, key ->
            val command = async { h.service.sendCLICommand(key, "get tx") }
            h.awaitSent(1)
            h.yieldReply("> 22", publicKey)
            assertEquals("> 22", command.await())
        },
        case("radio CSV reply never resolves a pending get tx") { h, key ->
            val command = async { h.service.sendCLICommand(key, "get tx") }
            h.awaitSent(1)
            // A stale "get radio" reply must be dropped, not adopted as 910 dBm.
            h.yieldReply("> 910.525,62.500,7,7", publicKey)
            h.yieldReply("> 22", publicKey)
            assertEquals("> 22", command.await())
        },
        case("mismatched reply is dropped and the command times out") { h, key ->
            val command = async { runCatching { h.service.sendCLICommand(key, "get tx", 300.milliseconds) } }
            h.awaitSent(1)
            h.yieldReply("> 910.525,62.500,7,7", publicKey)
            remoteCoreSettle()
            // No contact row counts as direct, so the timeout is retried once over flood before surfacing.
            remoteCoreAdvanceUntil(h.clock, "command never timed out") { command.isCompleted }
            assertFailsWith<RemoteNodeError.Timeout> { command.await().getOrThrow() }
        },
        case("second command waits for the slot until the first resolves") { h, key ->
            val first = async { h.service.sendCLICommand(key, "get tx") }
            h.awaitSent(1, "first command was never sent")
            val second = async { h.service.sendCLICommand(key, "get radio") }
            // The second command must not reach the radio while the first is pending.
            remoteCoreSettle()
            assertEquals(1, h.session.sendCommandInvocations.size)

            h.yieldReply("> 22", publicKey)
            assertEquals("> 22", first.await())
            h.awaitSent(2, "second command was never sent")
            h.yieldReply("> 915.000,250.0,10,5", publicKey)
            assertEquals("> 915.000,250.0,10,5", second.await())
        },
        case("raw command accepts a free-form reply") { h, key ->
            val command = async { h.service.sendRawCLICommand(key, "region") }
            h.awaitSent(1)
            h.yieldReply("US/CA^\n  local F", publicKey)
            assertEquals("US/CA^\n  local F", command.await())
        },
    )

    private fun wirePrefixCases() = listOf(
        case("command is sent with a hex wire prefix ahead of the command text") { h, key ->
            val command = async { runCatching { h.service.sendCLICommand(key, "get tx", 300.milliseconds) } }
            h.awaitSent(1)
            val sent = h.session.sendCommandInvocations.last().command
            assertEquals("get tx", assertNotNull(CLIResponse.splitEchoedPrefix(sent)).body)
            remoteCoreAdvanceUntil(h.clock, "command never finished") { command.isCompleted }
        },
        case("reply echoing the wire prefix resolves and is delivered stripped") { h, key ->
            val command = async { h.service.sendCLICommand(key, "get tx") }
            h.awaitSent(1)
            h.yieldReply(h.sentWirePrefix() + "> 22", publicKey)
            assertEquals("> 22", command.await())
        },
        case("prefixed echo is authoritative even when the reply shape looks wrong") { h, key ->
            val command = async { h.service.sendCLICommand(key, "get tx") }
            h.awaitSent(1)
            // A CSV fails get tx shape validation, but the echoed prefix proves the reply answers this command.
            h.yieldReply(h.sentWirePrefix() + "> 910.525,62.500,7,7", publicKey)
            assertEquals("> 910.525,62.500,7,7", command.await())
        },
        case("reply echoing a foreign prefix is dropped even for raw commands") { h, key ->
            val command = async { h.service.sendRawCLICommand(key, "region") }
            h.awaitSent(1)
            val prefix = h.sentWirePrefix()
            val foreign = if (prefix == "A7|") "B8|" else "A7|"
            h.yieldReply(foreign + "stale reply to an earlier command", publicKey)
            h.yieldReply(prefix + "US/CA^", publicKey)
            assertEquals("US/CA^", command.await())
        },
        case("clock sync is rewritten to time with the host epoch on the wire") { h, key ->
            val before = h.clock.wallClock.instant().epochSecond
            val command = async { h.service.sendRawCLICommand(key, "clock sync") }
            h.awaitSent(1)
            val after = h.clock.wallClock.instant().epochSecond

            val split = assertNotNull(CLIResponse.splitEchoedPrefix(h.session.sendCommandInvocations.last().command))
            assertTrue(split.body.startsWith(RemoteCLICommandRewriter.TIME_COMMAND_PREFIX))
            val epoch = assertNotNull(split.body.drop(RemoteCLICommandRewriter.TIME_COMMAND_PREFIX.length).toLongOrNull())
            assertTrue(epoch in before..after)

            h.yieldReply(h.sentWirePrefix() + "OK - clock set: 15:48 - 14/8/2026 UTC", publicKey)
            assertEquals("OK - clock set: 15:48 - 14/8/2026 UTC", command.await())
        },
    )
}
