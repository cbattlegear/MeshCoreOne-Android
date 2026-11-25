// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RemoteNodeLoginHealTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ErrorCode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The login auto-heal path: when the radio reports a contact missing from its table (firmware
 * notFound, 0x02) during `sendLogin`, the service pushes the local contact to the radio and retries once.
 */
class RemoteNodeLoginHealTest {
    private val publicKey = remoteCoreKey(0xCC)
    private val directPath = Bytes.of(0x01, 0x02)
    private val notFound = { MeshCoreException.DeviceError(ErrorCode.NOT_FOUND.rawValue) }
    private val tableFull = { MeshCoreException.DeviceError(ErrorCode.TABLE_FULL.rawValue) }

    private fun case(name: String, body: suspend RemoteCoreHarness.() -> Unit): DynamicTest =
        remoteCoreOriginal("RemoteNodeLoginHealTests", name) { withRemoteCoreHarness { body() } }

    /** A direct-routed contact present locally but absent from the radio's table. */
    private fun RemoteCoreHarness.saveDirectContact(typeRawValue: UByte? = null) = store.saveContact(
        if (typeRawValue == null) remoteCoreContact(radioId, publicKey, outPathLength = 2u, outPath = directPath)
        else remoteCoreContact(radioId, publicKey, typeRawValue, outPathLength = 2u, outPath = directPath),
    )

    private suspend fun RemoteCoreHarness.heal() = service.sendLoginHealingIfNeeded(publicKey, radioId, "")

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        case("notFound on login pushes the local contact to the radio and retries") {
            saveDirectContact()
            session.setSendLoginResults(listOf(Result.failure(notFound()), Result.success(RemoteCoreFakeSession.sentInfo(5000u))))
            heal()
            // The radio was sent the missing contact exactly once, and login was retried.
            assertEquals(1, session.addContactInvocations.size)
            assertEquals(2, session.sendLoginInvocations.size)
            // The pushed contact is the right node, flood-routed, and the local row is reconciled to match.
            val pushed = session.addContactInvocations.first()
            assertEquals(publicKey, pushed.publicKey)
            assertEquals(PacketBuilder.FLOOD_PATH_SENTINEL, pushed.outPathLength)
            assertEquals(true, store.contact(radioId, publicKey)?.isFloodRouted)
        },
        case("healing preserves a contact type byte not modeled by ContactType") {
            // A type byte newer firmware might use must reach the radio verbatim, not coerced to chat.
            val unmodeledType: UByte = 0x7Fu
            saveDirectContact(unmodeledType)
            session.setSendLoginResults(listOf(Result.failure(notFound()), Result.success(RemoteCoreFakeSession.sentInfo(5000u))))
            heal()
            assertEquals(unmodeledType, session.addContactInvocations.first().typeRawValue)
        },
        case("a non-notFound login error skips healing and is not retried") {
            saveDirectContact()
            // tableFull shares its code with the add-contact path; only notFound from sendLogin may heal.
            session.setSendLoginResults(listOf(Result.failure(tableFull())))
            val thrown = assertFailsWith<MeshCoreException.DeviceError> { heal() }
            assertEquals(ErrorCode.TABLE_FULL.rawValue, thrown.code)
            assertTrue(session.addContactInvocations.isEmpty())
            assertEquals(1, session.sendLoginInvocations.size)
        },
        case("a second notFound after re-adding the contact surfaces the device error unhealed") {
            saveDirectContact()
            session.setSendLoginResults(listOf(Result.failure(notFound()), Result.failure(notFound())))
            val thrown = assertFailsWith<MeshCoreException.DeviceError> { heal() }
            assertEquals(ErrorCode.NOT_FOUND.rawValue, thrown.code)
            // Re-added once, then the retry's second notFound surfaced without re-adding.
            assertEquals(1, session.addContactInvocations.size)
            assertEquals(2, session.sendLoginInvocations.size)
        },
        case("a full radio contact table surfaces radioContactsFull") {
            saveDirectContact()
            session.setSendLoginResults(listOf(Result.failure(notFound())))
            session.addContactError = tableFull()
            assertFailsWith<RemoteNodeError.RadioContactsFull> { heal() }
            // The add was attempted once and the retry never fired, so login stops at the full table.
            assertEquals(1, session.addContactInvocations.size)
            assertEquals(1, session.sendLoginInvocations.size)
        },
        case("a contact absent from the local store cannot be healed") {
            session.setSendLoginResults(listOf(Result.failure(notFound())))
            assertFailsWith<RemoteNodeError.ContactNotFound> { heal() }
            assertTrue(session.addContactInvocations.isEmpty())
        },
        case("login() surfaces radioContactsFull through the continuation instead of a generic session error") {
            val key = addSession(remoteCoreSession(radioId, publicKey))
            saveDirectContact()
            // notFound, then re-adding hits a full table: login must resolve with the typed
            // RemoteNodeError.RadioContactsFull, not collapse into the generic sessionError fallback.
            session.setSendLoginResults(listOf(Result.failure(notFound())))
            session.addContactError = tableFull()
            assertFailsWith<RemoteNodeError.RadioContactsFull> { service.login(key, password = "") }
            assertEquals(1, session.sendLoginInvocations.size)
            assertEquals(1, session.addContactInvocations.size)
        },
    )
}
