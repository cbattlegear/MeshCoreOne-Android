// AndroidOnly: WP-303 CM-11 real protocol, Room and Settings generation handoff after irreversible identity import.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.remote.ImportProgress
import com.meshcoreone.android.core.services.remote.ImportStep
import com.meshcoreone.android.core.services.remote.NodeConfigJson
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigDocuments
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeConfigIdentityHandoffIntegrationTest : RoomProcessTest() {
    private val newKey = key(19)
    private val contactKey = key(23)
    private val secret = "ab".repeat(16)
    private val sections = ConfigSections(nodeIdentity = true, channels = true, contacts = true)
    private val config = MeshCoreNodeConfig(
        privateKey = "cd".repeat(64),
        channels = SnapshotList.of(MeshCoreNodeConfig.ChannelConfig("Restored", secret)),
        contacts = SnapshotList.of(MeshCoreNodeConfig.ContactConfig(
            type = 1u, name = "Restored contact", publicKey = contactKey.hexString, flags = 0u,
            latitude = "0", longitude = "0", lastAdvert = 0u, lastModified = 0u,
        )),
    )

    @Test fun remainingWritesAndProgressUseTheEffectivePartitionBeforeExactlyOneRebuild() = runTest {
        withIdentityNode { h, node, radio, effectiveId ->
            val handoff = CompletableDeferred<Unit>()
            val progress = mutableListOf<ImportProgress>()
            try {
                val work = backgroundScope.async {
                    node.nodeConfigService.importConfig(config, sections, node.token.radioId, handoff) { progress += it }
                }
                h.eventually("remaining import writes") { work.isCompleted }
                work.await()
                h.settle()
                assertSame(node, h.sessions.current)
                assertFalse(node.isTornDown)
                assertEquals(1, h.sessions.created)
                assertEquals(1, radio.readers)
                assertEquals(listOf(ImportStep.PrivateKey, ImportStep.Channel("Restored"), ImportStep.Contact("Restored contact")),
                    progress.map { it.step })
                assertEquals(listOf(1, 2, 3), progress.map { it.current })
                assertTrue(progress.all { it.total == 3 })
                assertEquals("Restored", assertNotNull(store.fetchChannel(effectiveId, 0u)).name)
                assertNotNull(store.fetchContact(effectiveId, contactKey))
                assertNull(store.fetchContact(node.token.radioId, contactKey))
                handoff.complete(Unit)
                h.eventually("identity replacement ready", virtualBudgetMillis = 10_000) {
                    h.sessions.current?.token?.radioId == effectiveId && h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
                }
                assertTrue(node.isTornDown)
                assertEquals(2, h.sessions.created)
                assertEquals(1, h.sessions.tornDown)
                assertEquals(1, h.sessions.outstanding)
                assertEquals(0, node.liveJobCount)
                assertNotNull(store.fetchContact(effectiveId, contactKey))
            } finally { handoff.complete(Unit) }
        }
    }

    @Test fun cancellingAfterThePrivateKeyWriteReconcilesAndReleasesThePartialImport() = runTest {
        withIdentityNode { h, node, radio, effectiveId ->
            val original = radio.respond
            radio.respond = { packet ->
                if (packet[0] == CommandCode.SET_CHANNEL.rawValue) emptyList() else original(packet)
            }
            val progress = mutableListOf<ImportProgress>()
            val work = backgroundScope.async {
                node.nodeConfigService.importConfig(config, sections, node.token.radioId) { progress += it }
            }
            h.eventually("blocked remaining channel write") {
                radio.sent.any { it[0] == CommandCode.SET_CHANNEL.rawValue }
            }
            assertSame(node, h.sessions.current)
            assertFalse(node.isTornDown)
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertEquals(listOf(ImportStep.PrivateKey), progress.map { it.step })
            assertNull(store.fetchContact(effectiveId, contactKey))
            h.eventually("partial identity handoff", virtualBudgetMillis = 10_000) {
                h.sessions.current?.token?.radioId == effectiveId
            }
            assertTrue(node.isTornDown)
            assertEquals(2, h.sessions.created)
        }
    }

    @Test fun aChangedIdentityWithoutAGhostStillRebuildsAfterTheSamePartitionWritesFinish() = runTest {
        withIdentityNode(seedGhost = false) { h, node, _, effectiveId ->
            val handoff = CompletableDeferred<Unit>()
            try {
                val work = backgroundScope.async {
                    node.nodeConfigService.importConfig(config, sections, node.token.radioId, handoff)
                }
                h.eventually("same-partition import") { work.isCompleted }
                work.await()
                assertSame(node, h.sessions.current)
                assertNotNull(store.fetchContact(effectiveId, contactKey))
                handoff.complete(Unit)
                h.eventually("same-partition new identity", virtualBudgetMillis = 10_000) {
                    h.sessions.current !== node && h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
                }
                assertEquals(effectiveId, assertNotNull(h.sessions.current).token.radioId)
                assertEquals(newKey, assertNotNull(h.manager.connectedDevice).publicKey)
                assertEquals(2, h.sessions.created)
                assertEquals(1, h.sessions.outstanding)
            } finally { handoff.complete(Unit) }
        }
    }

    @Test fun aConfirmedKeyWriteWithAnUnresolvedSelfInfoReplyRecoversOnTheCorrectPartition() = runTest {
        withIdentityNode { h, node, radio, effectiveId ->
            val original = radio.respond
            radio.respond = { packet ->
                if (packet[0] == CommandCode.APP_START.rawValue && radio.publicKey == newKey) emptyList() else original(packet)
            }
            val handoff = CompletableDeferred<Unit>()
            val progress = mutableListOf<ImportProgress>()
            try {
                val work = backgroundScope.async {
                    runCatching { node.nodeConfigService.importConfig(config, sections, node.token.radioId, handoff) { progress += it } }
                }
                h.eventually("confirmed identity progress") { progress.any { it.step == ImportStep.PrivateKey } }
                assertSame(node, h.sessions.current)
                h.eventually("post-write query failure", virtualBudgetMillis = 30_000, virtualStepMillis = 100) { work.isCompleted }
                assertTrue(work.await().isFailure)
                assertEquals(listOf(ImportStep.PrivateKey), progress.map { it.step })
                assertSame(node, h.sessions.current, "Even error recovery waits for the captured import consumer")
                assertNull(store.fetchContact(effectiveId, contactKey))
                handoff.complete(Unit)
                h.eventually("confirmed identity recovery", virtualBudgetMillis = 10_000) {
                    h.sessions.current?.token?.radioId == effectiveId && h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
                }
                assertEquals(newKey, assertNotNull(h.manager.connectedDevice).publicKey)
                assertTrue(node.isTornDown)
                assertEquals(2, h.sessions.created)
                assertEquals(1, h.sessions.outstanding)
            } finally { handoff.complete(Unit) }
        }
    }

    @Test fun failureBeforeTheNormalIdentityCallbackStillReconcilesTheCommittedKey() = runTest {
        withIdentityNode { h, node, radio, effectiveId ->
            val original = radio.respond
            radio.respond = { packet ->
                if (packet[0] == CommandCode.SET_NAME.rawValue) listOf(Bytes.of(1, 1)) else original(packet)
            }
            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config.copy(name = "Changed name"), sections, node.token.radioId) }
            }
            h.eventually("partial import failure") { work.isCompleted }
            assertTrue(work.await().isFailure)
            assertNull(store.fetchContact(effectiveId, contactKey))
            h.eventually("failed import identity handoff", virtualBudgetMillis = 10_000) {
                h.sessions.current?.token?.radioId == effectiveId
            }
            assertTrue(node.isTornDown)
            assertEquals(2, h.sessions.created)
        }
    }

    @Test fun cancellationWhileTheWrittenIdentityExchangeDrainsStillReconcilesItsChangedKey() = runTest {
        withIdentityNode { h, node, radio, effectiveId ->
            val original = radio.respond
            var heldSelfInfo = false
            radio.respond = { packet ->
                if (packet[0] == CommandCode.APP_START.rawValue && radio.publicKey == newKey && !heldSelfInfo) {
                    heldSelfInfo = true
                    emptyList()
                } else original(packet)
            }
            val work = backgroundScope.async {
                node.nodeConfigService.importConfig(config, sections, node.token.radioId)
            }
            h.eventually("written identity waiting for self info") { heldSelfInfo }
            work.cancel()
            testScheduler.runCurrent()
            assertSame(node, h.sessions.current)
            radio.push(radio.selfPacket())
            h.eventually("cancelled import cleanup") { work.isCompleted }
            assertTrue(work.isCancelled)
            assertNull(store.fetchContact(effectiveId, contactKey))
            h.eventually("drained identity handoff", virtualBudgetMillis = 10_000) {
                h.sessions.current?.token?.radioId == effectiveId
            }
            assertTrue(node.isTornDown)
            assertEquals(2, h.sessions.created)
        }
    }

    @Test fun aReleasedOldImportCannotDisconnectOrReconnectAReplacementGeneration() = runTest {
        withIdentityNode { h, node, _, _ ->
            val handoff = CompletableDeferred<Unit>()
            try {
                val work = backgroundScope.async {
                    node.nodeConfigService.importConfig(config, sections, node.token.radioId, handoff)
                }
                h.eventually("import waiting for handoff") { work.isCompleted }
                work.await()
                h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                h.manager.connect(h.target())
                h.settle()
                val replacement = assertNotNull(h.sessions.current)
                val created = h.sessions.created
                handoff.complete(Unit)
                h.settle()
                assertSame(replacement, h.sessions.current)
                assertEquals(created, h.sessions.created)
                assertFalse(replacement.isTornDown)
                assertEquals(1, h.sessions.outstanding)
            } finally { handoff.complete(Unit) }
        }
    }

    @Test fun actualSettingsConsumerFinishesItsChecksBeforeIdentityReconstruction() = runTest {
        withIdentityNode { h, node, _, effectiveId ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val holder = AppSettingsFeature.create(h.container, MeshCoreStorage.get(context)).nodeConfig(context.resources)
            val documents = object : NodeConfigDocuments {
                override suspend fun pick() = NodeConfigJson.encodeToBytes(config)
                override suspend fun save(name: String, bytes: ByteArray): Boolean = error("not used")
            }
            holder.pick(documents)
            h.eventually("import selection") { holder.importState.value.config != null }
            holder.prepareConfirmation()
            h.eventually("identity import confirmation") { holder.importState.value.confirmation }
            holder.apply()
            h.eventually("Settings import completes before rebuilding", virtualBudgetMillis = 10_000) {
                holder.importState.value.complete || holder.importState.value.error != null
            }
            assertNull(holder.importState.value.error)
            assertTrue(holder.importState.value.complete)
            assertEquals(3, holder.importState.value.progress?.current)
            h.eventually("Settings identity handoff", virtualBudgetMillis = 10_000) {
                h.sessions.current?.token?.radioId == effectiveId
            }
            assertTrue(node.isTornDown)
            assertEquals(2, h.sessions.created)
        }
    }

    private suspend fun TestScope.withIdentityNode(
        seedGhost: Boolean = true,
        block: suspend TestScope.(ContainerHarness, RadioSessionContainer, FirmwareRadio, RadioId) -> Unit,
    ) {
        var activeKey = key(7)
        val savedContacts = mutableListOf<Bytes>()
        val channels = mutableMapOf<UByte, Bytes>()
        val h = ContainerHarness(this, store, identity = { activeKey })
        h.links.configureRadio = { radio ->
            radio.knownContacts += savedContacts
            radio.respond = { packet ->
                when (packet[0]) {
                    CommandCode.IMPORT_PRIVATE_KEY.rawValue -> {
                        activeKey = newKey
                        radio.publicKey = newKey
                        listOf(Bytes.of(0))
                    }
                    CommandCode.SET_CHANNEL.rawValue -> {
                        channels[packet[1]] = Bytes.of(0x12) + packet.slice(1, packet.size)
                        listOf(Bytes.of(0))
                    }
                    CommandCode.GET_CHANNEL.rawValue -> channels[packet[1]]?.let { listOf(it) }
                    CommandCode.UPDATE_CONTACT.rawValue -> {
                        val frame = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                        radio.knownContacts += frame
                        savedContacts += frame
                        listOf(Bytes.of(0))
                    }
                    else -> null
                }
            }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val node = assertNotNull(h.sessions.current)
            val ghost = deviceOf().copy(publicKey = newKey, connectionMethods = SnapshotList.of(ConnectionMethod.WiFi("192.0.2.1", 5000u)))
            if (seedGhost) store.saveDevice(ghost)
            block(h, node, h.links.radios.single(), if (seedGhost) ghost.radioId else node.token.radioId)
        } finally { h.container.close() }
    }
}
