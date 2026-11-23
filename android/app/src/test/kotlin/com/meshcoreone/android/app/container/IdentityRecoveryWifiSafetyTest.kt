// AndroidOnly: WP-303 Recovery peer and endpoint admission over real protocol and Room.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.Ed25519Crypto
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.runtime.IdentityImportKeyMismatch
import com.meshcoreone.android.core.runtime.IdentityImportPeerMismatch
import com.meshcoreone.android.core.runtime.IdentityImportPeerUnidentified
import com.meshcoreone.android.core.runtime.IdentityImportReadbackMismatch
import com.meshcoreone.android.core.runtime.IdentityImportRecoveryRequired
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityRecoveryWifiSafetyTest : RoomProcessTest() {
    private val privateKey = Ed25519Crypto.expandSeed(key(19))
    private val importedKey = Ed25519Crypto.publicKeyFromExpanded(privateKey)
    private val original = ConnectionTarget.WiFi("radio.example", 5000u)
    private val alternate = ConnectionTarget.WiFi("radio-new.example", 5000u)

    @Test fun replacementAtOriginalEndpointNeverReceivesThePrivateKey() = runTest {
        withUnknownIdentity { h, peers, source ->
            peers[original] = key(77)
            val confirmation = h.manager.identityImportRecoveries.value.single()
            val attempt = backgroundScope.async {
                runCatching { h.manager.retryIdentityImport(confirmation, privateKey) }
            }
            h.eventually("replacement peer rejected", virtualBudgetMillis = 10_000) { attempt.isCompleted }
            assertIs<IdentityImportPeerMismatch>(attempt.await().exceptionOrNull())
            assertTrue(h.links.radios.last().sent.none { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
            assertEquals(source.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
            assertNotNull(h.manager.prepareIdentityImportRecovery(confirmation.receiptId))
            assertNull(h.sessions.current)
        }
    }

    @Test fun alternateAddressWithImportedIdentityCannotCreateANewPartition() = runTest {
        withUnknownIdentity { h, peers, source ->
            peers[alternate] = importedKey
            val attempt = backgroundScope.async { runCatching { h.manager.connect(alternate) } }
            h.eventually("alternate address quarantined", virtualBudgetMillis = 10_000) { attempt.isCompleted }
            assertIs<IdentityImportRecoveryRequired>(attempt.await().exceptionOrNull())
            assertNull(h.sessions.current)
            assertNull(store.fetchDevice(importedKey))
            assertEquals(source.radioId, assertNotNull(store.fetchDevice(source.id)).radioId)
            val confirmation = h.manager.identityImportRecoveries.value.single()
            assertEquals(alternate, confirmation.target)
            val retry = backgroundScope.async { h.manager.retryIdentityImport(confirmation, privateKey) }
            h.eventually("recovery on the verified alternate address", virtualBudgetMillis = 10_000) { retry.isCompleted }
            retry.await()
            assertEquals(source.id, assertNotNull(h.manager.connectedDevice).id)
            assertTrue(h.manager.identityImportRecoveries.value.isEmpty())
            assertEquals(1, h.links.radios.last().sent.count { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
        }
    }

    @Test fun alternateAddressWithOriginalIdentityReusesTheSourcePartition() = runTest {
        withUnknownIdentity { h, peers, source ->
            peers[alternate] = source.publicKey
            h.manager.connect(alternate)
            h.settle()
            h.assertReady()
            assertEquals(source.id, assertNotNull(h.manager.connectedDevice).id)
            assertEquals(source.radioId, assertNotNull(h.manager.connectedDevice).radioId)
            assertTrue(h.manager.identityImportRecoveries.value.isEmpty())
        }
    }

    @Test fun unidentifiableChangedIdentityCannotCreateANewWifiPartition() = runTest {
        withUnknownIdentity(originalPrivateKey = Bytes.fromHex("cd".repeat(64))) { h, peers, source ->
            val unknownKey = key(80)
            peers[alternate] = unknownKey
            val attempt = backgroundScope.async { runCatching { h.manager.connect(alternate) } }
            h.eventually("unidentified peer quarantined", virtualBudgetMillis = 10_000) { attempt.isCompleted }
            assertIs<IdentityImportPeerUnidentified>(attempt.await().exceptionOrNull())
            assertNull(store.fetchDevice(unknownKey))
            assertEquals(source.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
            assertEquals(1, h.manager.identityImportRecoveries.value.size)
        }
    }

    @Test fun unidentifiableChangedPeerCannotReceiveARetryAtTheOriginalEndpoint() = runTest {
        withUnknownIdentity(originalPrivateKey = Bytes.fromHex("cd".repeat(64))) { h, _, _ ->
            val confirmation = h.manager.identityImportRecoveries.value.single()
            val attempt = backgroundScope.async {
                runCatching { h.manager.retryIdentityImport(confirmation, privateKey) }
            }
            h.eventually("unidentified recovery peer rejected", virtualBudgetMillis = 10_000) { attempt.isCompleted }
            assertIs<IdentityImportPeerMismatch>(attempt.await().exceptionOrNull())
            assertTrue(h.links.radios.last().sent.none { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
            assertNotNull(h.manager.prepareIdentityImportRecovery(confirmation.receiptId))
        }
    }

    @Test fun acknowledgedRetryWithUnexpectedReadbackDoesNotReconcile() = runTest {
        withUnknownIdentity { h, peers, source ->
            peers[original] = source.publicKey
            val configure = h.links.configureRadio
            h.links.configureRadio = { radio ->
                configure(radio)
                val respond = radio.respond
                radio.respond = { packet ->
                    if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) listOf(Bytes.of(0))
                    else respond(packet)
                }
            }
            val confirmation = h.manager.identityImportRecoveries.value.single()
            val attempt = backgroundScope.async {
                runCatching { h.manager.retryIdentityImport(confirmation, privateKey) }
            }
            h.eventually("unexpected readback rejected", virtualBudgetMillis = 10_000) { attempt.isCompleted }
            assertIs<IdentityImportReadbackMismatch>(attempt.await().exceptionOrNull())
            assertEquals(source.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
            assertNotNull(h.manager.prepareIdentityImportRecovery(confirmation.receiptId))
            assertNull(h.sessions.current)
        }
    }

    @Test fun establishedUnrelatedWifiPeerRemainsUsable() = runTest {
        withUnknownIdentity { h, peers, source ->
            val otherKey = key(81)
            val other = source.copy(
                id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), publicKey = otherKey,
                isActive = false, connectionMethods = SnapshotList.of(ConnectionMethod.WiFi(alternate.host, alternate.port)),
            )
            store.saveDevice(other)
            peers[alternate] = otherKey
            h.manager.connect(alternate)
            h.settle()
            h.assertReady()
            assertEquals(other.id, assertNotNull(h.manager.connectedDevice).id)
            assertEquals(source.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
            assertEquals(1, h.manager.identityImportRecoveries.value.size)
        }
    }

    @Test fun recoveryRejectsADifferentSelectedKeyBeforeOpeningALink() = runTest {
        withUnknownIdentity { h, _, _ ->
            val confirmation = h.manager.identityImportRecoveries.value.single()
            val otherKey = Ed25519Crypto.expandSeed(key(20))
            val count = h.links.radios.size
            val failure = runCatching { h.manager.retryIdentityImport(confirmation, otherKey) }.exceptionOrNull()
            assertIs<IdentityImportKeyMismatch>(failure)
            assertEquals(count, h.links.radios.size)
        }
    }

    private suspend fun TestScope.withUnknownIdentity(
        originalPrivateKey: Bytes = privateKey,
        block: suspend TestScope.(ContainerHarness, ConcurrentHashMap<ConnectionTarget, Bytes>, DeviceDTO) -> Unit,
    ) {
        val activeKey = AtomicReference(key(7))
        val peers = ConcurrentHashMap<ConnectionTarget, Bytes>()
        val held = AtomicBoolean(false)
        val h = ContainerHarness(this, store, identity = { peers[it] ?: activeKey.get() })
        h.links.configureRadio = { radio ->
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) {
                    activeKey.set(importedKey)
                    radio.publicKey = importedKey
                    if (held.compareAndSet(false, true)) emptyList() else listOf(Bytes.of(0))
                } else null
            }
        }
        try {
            h.manager.connect(original)
            h.settle()
            h.assertReady()
            val node = assertNotNull(h.sessions.current)
            val source = assertNotNull(h.manager.connectedDevice)
            val config = MeshCoreNodeConfig(privateKey = originalPrivateKey.hexString)
            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, ConfigSections(nodeIdentity = true), node.token.radioId) }
            }
            h.eventually("identity write dispatched") { held.get() }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.eventually("identity import interrupted") { work.isCompleted }
            assertTrue(work.await().isFailure)
            assertEquals(1, h.manager.identityImportRecoveries.value.size)
            block(h, peers, source)
        } finally { h.container.close() }
    }
}
