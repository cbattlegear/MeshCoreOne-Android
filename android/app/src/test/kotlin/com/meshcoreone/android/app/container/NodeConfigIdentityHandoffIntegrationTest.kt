// AndroidOnly: WP-303 CM-11 real protocol, Room and Settings generation handoff after irreversible identity import.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.Ed25519Crypto
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.IdentityImportRecoveryRequired
import com.meshcoreone.android.core.runtime.IdentityImportReadbackMismatch
import com.meshcoreone.android.core.runtime.ProcessConnectionPreferences
import com.meshcoreone.android.core.runtime.RuntimePreferenceSnapshot
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.core.services.remote.ImportProgress
import com.meshcoreone.android.core.services.remote.ImportStep
import com.meshcoreone.android.core.services.remote.ImportWriteStatus
import com.meshcoreone.android.core.services.remote.NodeConfigJson
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigDocuments
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeConfigIdentityHandoffIntegrationTest : RoomProcessTest() {
    private val importedPrivateKey = Ed25519Crypto.expandSeed(key(19))
    private val newKey = Ed25519Crypto.publicKeyFromExpanded(importedPrivateKey)
    private val contactKey = key(23)
    private val secret = "ab".repeat(16)
    private val sections = ConfigSections(nodeIdentity = true, channels = true, contacts = true)
    private val config = MeshCoreNodeConfig(
        privateKey = importedPrivateKey.hexString,
        channels = SnapshotList.of(MeshCoreNodeConfig.ChannelConfig("Restored", secret)),
        contacts = SnapshotList.of(MeshCoreNodeConfig.ContactConfig(
            type = 1u, name = "Restored contact", publicKey = contactKey.hexString, flags = 0u,
            latitude = "0", longitude = "0", lastAdvert = 0u, lastModified = 0u,
        )),
    )

    @Test fun lostChannelReplyIsReportedAndReconnectReplansWithoutDuplicateWrite() = runTest {
        lostNonIdentityReplyReplans(ConfigSections(channels = true), CommandCode.SET_CHANNEL)
    }

    @Test fun lostContactReplyIsReportedAndReconnectReplansWithoutDuplicateWrite() = runTest {
        lostNonIdentityReplyReplans(ConfigSections(contacts = true), CommandCode.UPDATE_CONTACT)
    }

    private suspend fun TestScope.lostNonIdentityReplyReplans(selected: ConfigSections, command: CommandCode) {
        withIdentityNode(seedGhost = false) { h, node, radio, _ ->
            val deviceId = assertNotNull(h.manager.connectedDevice).id
            val original = radio.respond
            var held = false
            val statuses = ConcurrentLinkedQueue<ImportWriteStatus>()
            radio.respond = { packet ->
                if (packet[0] == command.rawValue) {
                    original(packet)
                    held = true
                    emptyList()
                } else original(packet)
            }
            val work = backgroundScope.async {
                runCatching {
                    node.nodeConfigService.importConfig(config, selected, node.token.radioId,
                        onWriteStatus = { statuses.add(it) })
                }
            }
            h.eventually("write dispatched") { held }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.eventually("interrupted import") { work.isCompleted }
            assertTrue(work.await().isFailure)
            assertTrue(statuses.last().uncertain)
            h.manager.connect(h.target(deviceId))
            h.settle()
            h.assertReady()
            val restored = assertNotNull(h.sessions.current)
            val retry = backgroundScope.async {
                restored.nodeConfigService.importConfig(config, selected, restored.token.radioId)
            }
            h.eventually("fresh import plan completes", virtualBudgetMillis = 10_000) { retry.isCompleted }
            retry.await()
            assertTrue(h.links.radios.last().sent.none { it[0] == command.rawValue })
        }
    }

    @Test fun lostIdentityReplyRequiresExplicitIdentityOnlyRecoveryOnTheCapturedTarget() = runTest {
        withIdentityNode { h, node, radio, effectiveId ->
            val original = radio.respond
            var held = false
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) {
                    original(packet)
                    held = true
                    emptyList()
                } else original(packet)
            }

            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, sections, node.token.radioId) }
            }
            h.eventually("identity command dispatched") { held }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.eventually("interrupted import") { work.isCompleted }
            assertTrue(work.await().isFailure)
            val first = h.manager.identityImportRecoveries.value.single()
            assertNull(store.fetchContact(effectiveId, contactKey))

            val ordinary = backgroundScope.async { runCatching { h.manager.connect(first.target) } }
            h.eventually("ambiguous connection refused", virtualBudgetMillis = 10_000) { ordinary.isCompleted }
            assertIs<IdentityImportRecoveryRequired>(ordinary.await().exceptionOrNull())
            assertNull(h.sessions.current)
            assertNotNull(h.manager.prepareIdentityImportRecovery(first.receiptId))
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                h.manager.retryIdentityImport(first, Bytes.fromHex(config.privateKey!!))
            }

            val current = assertNotNull(h.manager.prepareIdentityImportRecovery(first.receiptId))
            val recovery = backgroundScope.async {
                h.manager.retryIdentityImport(current, Bytes.fromHex(config.privateKey!!))
            }
            h.eventually("identity-only recovery", virtualBudgetMillis = 10_000) { recovery.isCompleted }
            recovery.await()
            assertEquals(effectiveId, assertNotNull(h.sessions.current).token.radioId)
            assertTrue(h.manager.identityImportRecoveries.value.isEmpty())
            val retryRadio = h.links.radios.last()
            assertEquals(1, retryRadio.sent.count { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
            assertTrue(retryRadio.sent.none { it[0] == CommandCode.SET_CHANNEL.rawValue || it[0] == CommandCode.UPDATE_CONTACT.rawValue })
            assertNull(store.fetchContact(effectiveId, contactKey))
        }
    }

    @Test fun rejectedIdentityRecoveryRetainsTheReceiptForAnotherExplicitAttempt() = runTest {
        withIdentityNode { h, node, radio, effectiveId ->
            val original = radio.respond
            var held = false
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) {
                    original(packet)
                    held = true
                    emptyList()
                } else original(packet)
            }
            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, ConfigSections(nodeIdentity = true), node.token.radioId) }
            }
            h.eventually("identity write dispatched") { held }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.eventually("identity import interrupted") { work.isCompleted }
            assertTrue(work.await().isFailure)
            val configure = h.links.configureRadio
            h.links.configureRadio = { next ->
                configure(next)
                val respond = next.respond
                next.respond = { packet ->
                    if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) listOf(Bytes.of(1, 1))
                    else respond(packet)
                }
            }
            val first = assertNotNull(h.manager.prepareIdentityImportRecovery(h.manager.identityImportRecoveries.value.single().receiptId))
            val rejected = backgroundScope.async { runCatching { h.manager.retryIdentityImport(first, importedPrivateKey) } }
            h.eventually("recovery rejected", virtualBudgetMillis = 10_000) { rejected.isCompleted }
            assertTrue(rejected.await().isFailure)
            assertNull(h.sessions.current)
            val second = assertNotNull(h.manager.prepareIdentityImportRecovery(first.receiptId))
            assertTrue(first != second)
            h.links.configureRadio = configure
            val retry = backgroundScope.async { h.manager.retryIdentityImport(second, importedPrivateKey) }
            h.eventually("recovery retried", virtualBudgetMillis = 10_000) { retry.isCompleted }
            retry.await()
            assertEquals(effectiveId, assertNotNull(h.sessions.current).token.radioId)
            assertTrue(h.manager.identityImportRecoveries.value.isEmpty())
        }
    }

    @Test fun acknowledgedIdentityWriteWithUnchangedSourceReadbackIsRejected() = runTest {
        withIdentityNode(identityAfterWrite = key(7)) { h, node, radio, _ ->
            val source = assertNotNull(h.manager.connectedDevice)
            val work = backgroundScope.async {
                runCatching {
                    node.nodeConfigService.importConfig(config, ConfigSections(nodeIdentity = true), node.token.radioId)
                }
            }
            h.eventually("unchanged identity readback rejected", virtualBudgetMillis = 10_000) { work.isCompleted }
            assertIs<IdentityImportReadbackMismatch>(work.await().exceptionOrNull())
            assertEquals(source.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
            assertFalse(assertNotNull(store.fetchDevice(newKey)).isActive)
            assertEquals(1, radio.sent.count { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
            assertTrue(radio.sent.none { it[0] == CommandCode.SET_CHANNEL.rawValue || it[0] == CommandCode.UPDATE_CONTACT.rawValue })
        }
    }

    @Test fun choosingAnotherTargetInvalidatesTheOldRecoveryConfirmation() = runTest {
        withIdentityNode(seedGhost = false) { h, node, radio, _ ->
            val source = assertNotNull(h.manager.connectedDevice)
            val original = radio.respond
            var held = false
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) {
                    original(packet)
                    held = true
                    emptyList()
                } else original(packet)
            }
            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, ConfigSections(nodeIdentity = true), node.token.radioId) }
            }
            h.eventually("identity write dispatched") { held }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.eventually("identity import interrupted") { work.isCompleted }
            assertTrue(work.await().isFailure)
            val confirmation = h.manager.identityImportRecoveries.value.single()
            val configure = h.links.configureRadio
            h.links.configureRadio = { next -> configure(next); next.publicKey = key(77) }
            val other = h.target()
            h.manager.connect(other)
            h.settle()
            h.assertReady()
            assertEquals(other.deviceId, h.manager.connectedDevice?.id)
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                h.manager.retryIdentityImport(confirmation, importedPrivateKey)
            }
            assertTrue(h.links.radios.last().sent.none { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
            assertEquals(source.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
        }
    }

    @Test fun processReplacementDoesNotRestoreTheInMemoryRecoveryReceipt() = runTest {
        var activeKey = key(7)
        val h = ContainerHarness(this, store, identity = { activeKey })
        var closed = false
        h.links.configureRadio = { radio ->
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) {
                    activeKey = newKey
                    radio.publicKey = newKey
                    emptyList()
                } else null
            }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            val node = assertNotNull(h.sessions.current)
            val source = assertNotNull(h.manager.connectedDevice)
            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, ConfigSections(nodeIdentity = true), node.token.radioId) }
            }
            h.eventually("identity write dispatched") {
                h.links.radios.single().sent.any { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue }
            }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.eventually("identity import interrupted") { work.isCompleted }
            assertTrue(work.await().isFailure)
            assertEquals(1, h.manager.identityImportRecoveries.value.size)
            h.container.close()
            closed = true
            val replacement = ContainerHarness(this, store, identity = { activeKey })
            try {
                assertTrue(replacement.manager.identityImportRecoveries.value.isEmpty())
                val persisted = assertNotNull(store.fetchDevice(source.id))
                assertEquals(source.publicKey, persisted.publicKey)
                assertEquals(source.radioId, persisted.radioId)
                assertTrue(replacement.links.radios.isEmpty())
            } finally { replacement.container.close() }
        } finally { if (!closed) h.container.close() }
    }

    @Test fun anArrivalDuringKeyReadbackAndPartitionHandoffWaitsForTheNewGraph() = runTest {
        val messages = ConcurrentLinkedQueue<Bytes>()
        withIdentityNode(messageFrames = messages) { h, node, radio, effectiveId ->
            val handoff = CompletableDeferred<Unit>()
            val original = radio.respond
            var heldReadback = false
            radio.respond = { packet ->
                if (packet[0] == CommandCode.APP_START.rawValue && radio.publicKey == newKey && !heldReadback) {
                    heldReadback = true
                    emptyList()
                } else original(packet)
            }
            try {
                val work = backgroundScope.async {
                    node.nodeConfigService.importConfig(config, sections, node.token.radioId, handoff)
                }
                h.eventually("key readback with ingestion already quiesced") { heldReadback }
                assertFalse(node.messagePollingService.isAutoFetching)
                val stamp = EPOCH.epochSecond.toUInt()
                messages.add(Bytes.of(0x10, 20, 0, 0) + contactKey.prefix(6) + Bytes.of(0, 0) +
                    le32(stamp.toLong()) + Bytes.utf8("Arrived during handoff"))
                radio.push(Bytes.of(0x83))
                node.ensureListeners()
                testScheduler.runCurrent()
                assertEquals(1, messages.size)
                assertEquals(1, h.sessions.created)
                assertFalse(node.messagePollingService.isAutoFetching)
                radio.push(radio.selfPacket())
                h.eventually("remaining writes complete without consuming the arrival") { work.isCompleted }
                work.await()
                assertEquals(1, messages.size)
                val contact = assertNotNull(store.fetchContact(effectiveId, contactKey))
                assertTrue(store.fetchMessages(EntityKey(effectiveId, contact.id)).isEmpty())
                handoff.complete(Unit)
                h.eventually("replacement consumes the queued arrival in its effective partition", virtualBudgetMillis = 10_000) {
                    h.sessions.current?.token?.radioId == effectiveId &&
                        h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
                }
                assertEquals(listOf("Arrived during handoff"),
                    store.fetchMessages(EntityKey(effectiveId, contact.id)).map { it.text })
                assertTrue(messages.isEmpty())
                assertNull(store.fetchContact(node.token.radioId, contactKey))
                assertTrue(node.isTornDown)
                assertEquals(2, h.sessions.created)
                assertTrue(assertNotNull(h.sessions.current).messagePollingService.isAutoFetching)
            } finally { handoff.complete(Unit) }
        }
    }

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

    @Test fun importingTheCurrentIdentityResumesIngestionWithoutAnUnnecessaryReplacement() = runTest {
        withIdentityNode { h, node, _, effectiveId ->
            val first = backgroundScope.async {
                node.nodeConfigService.importConfig(config, sections, node.token.radioId)
            }
            h.eventually("first identity import") { first.isCompleted }
            first.await()
            h.eventually("first replacement ready", virtualBudgetMillis = 10_000) {
                h.sessions.current?.token?.radioId == effectiveId &&
                    h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            val current = assertNotNull(h.sessions.current)
            val second = backgroundScope.async {
                current.nodeConfigService.importConfig(config, sections, effectiveId)
            }
            h.eventually("unchanged identity import") { second.isCompleted }
            second.await()
            h.settle()
            assertSame(current, h.sessions.current)
            assertEquals(2, h.sessions.created)
            assertFalse(current.isTornDown)
            assertTrue(current.messagePollingService.isAutoFetching)
            assertFalse(h.manager.shouldDeferOpportunisticReconnect)
            assertPathUpdateRefreshesTheRealContact(h, current, h.links.radios.last(), contactKey)
        }
    }

    @Test fun aRejectedIdentityWriteResumesTheUnchangedGraph() = runTest {
        withIdentityNode { h, node, radio, _ ->
            val original = radio.respond
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) listOf(Bytes.of(1, 1)) else original(packet)
            }

            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, sections, node.token.radioId) }
            }
            h.eventually("rejected identity import") { work.isCompleted }
            assertTrue(work.await().isFailure)
            h.settle()
            assertSame(node, h.sessions.current)
            assertEquals(1, h.sessions.created)
            assertTrue(node.messagePollingService.isAutoFetching)
            assertFalse(h.manager.shouldDeferOpportunisticReconnect)
            assertPathUpdateRefreshesTheRealContact(h, node, radio, contactKey)
        }
    }

    @Test fun oldIdentityCannotQueueAChannelWriteBehindThePhysicalKeyMutation() = runTest {
        withIdentityNode { h, node, radio, _ ->
            node.channelService.setChannelWithSecret(node.token.radioId, 0u, "Before", Bytes.fromHex(secret))
            val original = radio.respond
            var heldKeyWrite = false
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) {
                    heldKeyWrite = true
                    emptyList()
                } else if (packet[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue) {
                    listOf(Bytes.of(0))
                } else original(packet)
            }
            val identity = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, ConfigSections(nodeIdentity = true), node.token.radioId) }
            }
            h.eventually("physical key mutation with old channel authority already paused") { heldKeyWrite }
            val oldSend = backgroundScope.async {
                assertFails { node.messageService.sendChannelMessage("Old identity", 0u, node.token.radioId, TextType.PLAIN) }
            }
            h.eventually("old-identity dispatch rejects before waiting behind the held key exchange") { oldSend.isCompleted }
            assertTrue(generateSequence(oldSend.await()) { it.cause }.any { it is ChannelDispatchFailure.RetiredSession })
            assertTrue(radio.sent.none { it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue })
            radio.push(Bytes.of(1, 1))
            h.eventually("rejected key mutation restores the original authority") { identity.isCompleted }
            assertTrue(identity.await().isFailure)
            assertSame(node, h.sessions.current)
            val resumed = node.messageService.sendChannelMessage("Original identity resumed", 0u, node.token.radioId, TextType.PLAIN)
            assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(node.token.radioId, resumed.id))?.status)
            assertEquals(1, radio.sent.count { it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue })
        }
    }

    @Test fun anAdmittedChannelSendSettlesBeforeThePhysicalKeyMutation() = runTest {
        withIdentityNode { h, node, radio, _ ->
            node.channelService.setChannelWithSecret(node.token.radioId, 0u, "Before", Bytes.fromHex(secret))
            val original = radio.respond
            var heldSend = false
            var keyWritten = false
            radio.respond = { packet ->
                when (packet[0]) {
                    CommandCode.SEND_CHANNEL_MESSAGE.rawValue -> {
                        heldSend = true
                        emptyList()
                    }
                    CommandCode.IMPORT_PRIVATE_KEY.rawValue -> {
                        keyWritten = true
                        original(packet)
                    }
                    else -> original(packet)
                }
            }
            val handoff = CompletableDeferred<Unit>()
            try {
                val sending = backgroundScope.async {
                    node.messageService.sendChannelMessage("Admitted original identity", 0u, node.token.radioId, TextType.PLAIN)
                }
                h.eventually("original-identity SEND awaiting its actual reply") { heldSend }
                // Import's initial self-info read can queue before its identity-admission callback.
                val importing = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    node.nodeConfigService.importConfig(config, ConfigSections(nodeIdentity = true), node.token.radioId, handoff)
                }
                assertFalse(sending.isCompleted)
                assertFalse(importing.isCompleted)
                assertFalse(keyWritten)
                assertTrue(radio.sent.none { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
                radio.push(Bytes.of(0))
                h.eventually("the acknowledged SEND and subsequent identity import finish") {
                    sending.isCompleted && importing.isCompleted
                }
                val receipt = sending.await()
                importing.await()
                assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(node.token.radioId, receipt.id))?.status)
                assertTrue(keyWritten)
                assertEquals(newKey, radio.publicKey)
                assertEquals(1, radio.sent.count { it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue })
                assertEquals(1, radio.sent.count { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
                assertSame(node, h.sessions.current)
            } finally { handoff.complete(Unit) }
        }
    }

    @Test fun anAcknowledgementDuringAReversibleIdentityPauseIsNotLost() = runTest {
        withIdentityNode { h, node, radio, _ ->
            val contact = ContactDTO(radioId = node.token.radioId, publicKey = contactKey,
                name = "Peer", typeRawValue = 1u, lastHeardTimestamp = null)
            store.saveContact(contact)
            val original = radio.respond
            var acknowledgement: Bytes? = null
            var heldKeyWrite = false
            radio.respond = { packet ->
                when (packet[0]) {
                    CommandCode.SEND_MESSAGE.rawValue -> {
                        acknowledgement = sentAndDelivered(packet, radio.publicKey)[1]
                        listOf(sentReply(packet, radio.publicKey))
                    }
                    CommandCode.IMPORT_PRIVATE_KEY.rawValue -> {
                        heldKeyWrite = true
                        emptyList()
                    }
                    else -> original(packet)
                }
            }
            val message = node.messageService.sendDirectMessage("Pending ACK", contact, TextType.PLAIN, null)
            val work = backgroundScope.async {
                runCatching { node.nodeConfigService.importConfig(config, sections, node.token.radioId) }
            }
            h.eventually("identity write held with partition ingestion paused") { heldKeyWrite }
            assertFalse(node.messagePollingService.isAutoFetching)
            radio.push(assertNotNull(acknowledgement))
            val delivered = backgroundScope.async {
                while (store.fetchMessage(EntityKey(node.token.radioId, message.id))?.status != MessageStatus.DELIVERED) {
                    kotlinx.coroutines.delay(1)
                }
            }
            h.eventually("ACK is persisted before the paused import resumes") { delivered.isCompleted }
            delivered.await()
            radio.push(Bytes.of(1, 1))
            h.eventually("rejected import cleanup") { work.isCompleted }
            assertTrue(work.await().isFailure)
            assertTrue(node.messagePollingService.isAutoFetching)
            assertEquals(MessageStatus.DELIVERED, assertNotNull(
                store.fetchMessage(EntityKey(node.token.radioId, message.id))).status)
        }
    }

    private suspend fun TestScope.assertPathUpdateRefreshesTheRealContact(
        h: ContainerHarness, node: RadioSessionContainer, radio: FirmwareRadio, peerKey: Bytes,
    ) {
        h.container.foreground.activityStarted()
        radio.knownContacts.clear()
        radio.knownContacts += contactFrame(peerKey, "After identity import", lastModified = 1001)
        val requests = radio.sent.count { it[0] == CommandCode.GET_CONTACTS.rawValue }
        radio.push(Bytes.of(0x81) + peerKey)
        h.eventually("resumed advert handler requests a real contact delta",
            virtualBudgetMillis = 10_000, virtualStepMillis = 100) {
            radio.sent.count { it[0] == CommandCode.GET_CONTACTS.rawValue } > requests
        }
        val refreshed = backgroundScope.async {
            while (store.fetchContact(node.token.radioId, peerKey)?.name != "After identity import") {
                kotlinx.coroutines.delay(1)
            }
        }
        h.eventually("advert delta persists the updated contact", virtualBudgetMillis = 10_000) { refreshed.isCompleted }
        refreshed.await()
        assertEquals("After identity import", assertNotNull(store.fetchContact(node.token.radioId, peerKey)).name)
        assertSame(node, h.sessions.current)
    }

    @Test fun cancellingAnUnsettledEffectiveIdentityChannelWriteRetainsQuarantine() = runTest {
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
            h.eventually("uncertain partial import retires its captured graph") { node.isTornDown }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            val previousAttempts = h.links.radios.size
            val reconnectFailure = assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> {
                h.manager.connect(h.target())
            }
            assertTrue(node.isTornDown)
            assertNull(h.sessions.current)
            assertEquals(1, h.sessions.created)
            assertEquals(0, h.sessions.outstanding)
            assertNull(store.fetchChannelOccupantSnapshot(effectiveId, 0u))
            val retainedCauses = generateSequence<Throwable>(reconnectFailure) { it.cause }.toList()
            assertTrue(retainedCauses.any { it is kotlinx.coroutines.CancellationException },
                "The quarantine must retain the cancelled exchange: ${retainedCauses.map { it.javaClass.name }}")
            val successors = h.links.radios.drop(previousAttempts)
            assertTrue(successors.isNotEmpty())
            successors.forEach { successor ->
                assertEquals(newKey, successor.publicKey)
                assertTrue(successor.sent.none { it[0] == CommandCode.GET_CHANNEL.rawValue })
                assertTrue(successor.sent.none { it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue })
            }
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
        assertFailedReadbackHandoff(supersedeWithDisconnect = false)
    }

    @Test fun userDisconnectAfterTheConfirmedKeyLosesItsReplyPreventsDeferredRecovery() = runTest {
        assertFailedReadbackHandoff(supersedeWithDisconnect = true)
    }

    private suspend fun TestScope.assertFailedReadbackHandoff(supersedeWithDisconnect: Boolean) {
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
                h.eventually("the uncorrelated session is quarantined") { node.isTornDown }
                assertEquals(1, h.sessions.created, "Quarantine must not replace the captured graph before handoff")
                assertTrue(h.manager.shouldDeferOpportunisticReconnect)
                h.manager.checkBLEConnectionHealth()
                h.settle()
                assertEquals(1, h.sessions.created, "Health recovery must respect the import consumer")
                assertNull(store.fetchContact(effectiveId, contactKey))
                if (supersedeWithDisconnect) h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                handoff.complete(Unit)
                if (supersedeWithDisconnect) {
                    h.settle()
                    assertEquals(1, h.sessions.created)
                    assertFalse(h.manager.connectionIntent.wantsConnection)
                    return@withIdentityNode
                }
                h.eventually("unknown identity requires recovery", virtualBudgetMillis = 10_000) {
                    h.manager.identityImportRecoveries.value.isNotEmpty() &&
                        h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED
                }
                val receipt = h.manager.identityImportRecoveries.value.single()
                val confirmation = assertNotNull(h.manager.prepareIdentityImportRecovery(receipt.receiptId))
                val recovery = backgroundScope.async {
                    h.manager.retryIdentityImport(confirmation, Bytes.fromHex(config.privateKey!!))
                }
                h.eventually("explicit identity recovery", virtualBudgetMillis = 10_000) { recovery.isCompleted }
                recovery.await()
                assertEquals(effectiveId, h.sessions.current?.token?.radioId)
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

    @Test fun newerUserDisconnectDuringIdentityTeardownPreventsTheOldReconnect() = runTest {
        supersedeIdentityTeardown(connectReplacement = false)
    }

    @Test fun newerConnectionDuringIdentityTeardownCannotBeOverriddenByTheOldReconnect() = runTest {
        supersedeIdentityTeardown(connectReplacement = true)
    }

    private suspend fun TestScope.supersedeIdentityTeardown(connectReplacement: Boolean) {
        withIdentityNode { h, node, radio, _ ->
            val handoff = CompletableDeferred<Unit>()
            val teardown = CompletableDeferred<Unit>()
            try {
                val work = backgroundScope.async {
                    node.nodeConfigService.importConfig(config, sections, node.token.radioId, handoff)
                }
                h.eventually("import before held teardown") { work.isCompleted }
                work.await()
                radio.disconnectGate = teardown
                handoff.complete(Unit)
                h.eventually("identity teardown holds the transport", virtualBudgetMillis = 0) { radio.disconnecting }
                val replacement = h.target()
                val newer = backgroundScope.async {
                    if (connectReplacement) h.manager.connect(replacement)
                    else h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                }
                h.eventually("newer request is admitted before teardown completes", virtualBudgetMillis = 0) {
                    if (connectReplacement) h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.CONNECTING
                    else h.manager.connectionIntent == com.meshcoreone.android.core.contracts.domain.ConnectionIntent.UserDisconnected
                }
                teardown.complete(Unit)
                h.eventually("newer request finishes", virtualBudgetMillis = 10_000) { newer.isCompleted }
                newer.await()
                h.settle()
                if (connectReplacement) {
                    h.assertReady()
                    assertEquals(replacement.deviceId, h.manager.connectedDevice?.id)
                    assertEquals(2, h.sessions.created)
                    assertEquals(1, h.sessions.outstanding)
                } else {
                    assertEquals(com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
                    assertNull(h.sessions.current)
                    assertEquals(1, h.sessions.created)
                }
            } finally {
                teardown.complete(Unit)
                handoff.complete(Unit)
            }
        }
    }

    @Test fun failedIdentityPreferencePersistenceStillQueuesRecoveryAfterTheConsumerHandoff() = runTest {
        val memory = MemoryConnectionPreferences()
        var failNextWrite = false
        val preferences = object : ProcessConnectionPreferences by memory {
            override suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot {
                if (failNextWrite) {
                    failNextWrite = false
                    throw IllegalStateException("identity preference persistence failed")
                }
                return memory.update(transform)
            }
        }
        withIdentityNode(preferencesOverride = preferences) { h, node, _, effectiveId ->
            val handoff = CompletableDeferred<Unit>()
            try {
                failNextWrite = true
                val work = backgroundScope.async {
                    runCatching { node.nodeConfigService.importConfig(config, sections, node.token.radioId, handoff) }
                }
                h.eventually("identity persistence failure") { work.isCompleted }
                assertEquals("identity preference persistence failed", work.await().exceptionOrNull()?.message)
                assertSame(node, h.sessions.current)
                assertEquals(effectiveId, assertNotNull(store.fetchDevice(assertNotNull(h.manager.connectedDevice).id)).radioId)
                handoff.complete(Unit)
                h.eventually("recovery after failed identity persistence", virtualBudgetMillis = 10_000) {
                    h.sessions.current?.token?.radioId == effectiveId && h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
                }
                assertTrue(node.isTornDown)
                assertEquals(2, h.sessions.created)
                assertEquals(1, h.sessions.outstanding)
            } finally { handoff.complete(Unit) }
        }
    }

    @Test fun aSavedMatchingPublicKeyStillReconcilesItsGhostBeforeConstructingTheGraph() = runTest {
        withIdentityNode { h, node, _, effectiveId ->
            val device = assertNotNull(h.manager.connectedDevice)
            val keyWrite = backgroundScope.async {
                node.settingsService.importPrivateKey(Bytes.fromHex(assertNotNull(config.privateKey)))
            }
            h.eventually("direct key write is confirmed") { keyWrite.isCompleted }
            keyWrite.await()
            store.saveDevice(device.copy(publicKey = newKey))
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.manager.connect(h.target(device.id))
            h.settle()
            h.assertReady()
            assertEquals(effectiveId, assertNotNull(h.sessions.current).token.radioId)
            assertEquals(newKey, assertNotNull(h.manager.connectedDevice).publicKey)
            assertEquals(2, h.sessions.created)
        }
    }

    private suspend fun TestScope.withIdentityNode(
        seedGhost: Boolean = true,
        preferencesOverride: ProcessConnectionPreferences? = null,
        messageFrames: ConcurrentLinkedQueue<Bytes> = ConcurrentLinkedQueue(),
        identityAfterWrite: Bytes = newKey,
        block: suspend TestScope.(ContainerHarness, RadioSessionContainer, FirmwareRadio, RadioId) -> Unit,
    ) {
        var activeKey = key(7)
        val savedContacts = mutableListOf<Bytes>()
        val channels = mutableMapOf<UByte, Bytes>()
        val h = ContainerHarness(this, store, identity = { activeKey }, preferencesOverride = preferencesOverride)
        h.links.configureRadio = { radio ->
            radio.knownContacts += savedContacts
            radio.respond = { packet ->
                when (packet[0]) {
                    CommandCode.GET_MESSAGE.rawValue -> listOf(messageFrames.poll() ?: Bytes.of(0x0a))
                    CommandCode.IMPORT_PRIVATE_KEY.rawValue -> {
                        activeKey = identityAfterWrite
                        radio.publicKey = identityAfterWrite
                        listOf(Bytes.of(0))
                    }
                    CommandCode.SET_CHANNEL.rawValue -> {
                        channels[packet[1]] = Bytes.of(0x12) + packet.slice(1, packet.size)
                        listOf(Bytes.of(0))
                    }
                    CommandCode.GET_CHANNEL.rawValue -> listOf(
                        channels[packet[1]] ?: (Bytes.of(0x12, packet[1].toInt()) + Bytes(ByteArray(48))),
                    )
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
