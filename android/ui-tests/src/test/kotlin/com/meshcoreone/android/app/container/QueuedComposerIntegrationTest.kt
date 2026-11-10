// AndroidOnly: WP-502 Exercise the actual app queue binding over Room and independently rejecting firmware.
// ReferenceInput: MC1/Views/Chats/ViewModel/ChatViewModel+SendQueues.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/ViewModel/ChatViewModel+EventStream.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryConflict
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryRejected
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelMessageCreation
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.util.Collections
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedComposerIntegrationTest : RoomProcessTest() {
    private enum class Kind { DIRECT, CHANNEL }
    private val peerKey = key(4)
    private val original = "Queue original \u6d4b\u8bd5 \u0645\u0631\u062d\u0628\u0627"
    private val noLocation = object : ComposerLocationSource {
        override val isAuthorized = false
        override val currentCoordinate: Coordinate? = null
        override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? = null
    }

    private data class Graph(val harness: ContainerHarness, val contact: ContactDTO, val channel: ChannelDTO) {
        val radio get() = contact.radioId
        val feature get() = harness.container.chatsFeature
        fun target(kind: Kind): ComposerTarget = when (kind) {
            Kind.DIRECT -> ComposerTarget.Direct(contact)
            Kind.CHANNEL -> ComposerTarget.Channel(channel)
        }
        fun conversation(kind: Kind): TimelineConversation = when (kind) {
            Kind.DIRECT -> TimelineConversation.Direct(contact)
            Kind.CHANNEL -> TimelineConversation.Channel(channel)
        }
        fun select(kind: Kind) = when (kind) {
            Kind.DIRECT -> harness.container.navigation.navigateToChat(contact)
            Kind.CHANNEL -> harness.container.navigation.navigateToChannel(channel)
        }
    }

    private suspend fun graph(test: TestScope, persistence: PersistenceStoreProtocol = store): Graph {
        val h = ContainerHarness(test, store, persistenceOverride = persistence)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(peerKey, "Synthetic queue peer")
            radio.respond = { frame ->
                when {
                    frame[0].toInt() in 2..3 -> listOf(Bytes.of(1, 2))
                    frame[0].toInt() == 0x1f -> listOf(
                        if (frame[1].toInt() == 0) {
                            Bytes.of(0x12, 0) + Bytes.utf8("Synthetic local channel").paddedOrTruncated(32) +
                                Bytes(ByteArray(16) { 0x11 })
                        } else Bytes.of(0x12, frame[1].toInt()) + Bytes(ByteArray(48)),
                    )
                    else -> null
                }
            }
        }
        h.container.foreground.activityStarted()
        h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000215")))
        h.eventually("queue graph ready") {
            h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        }
        val radio = assertNotNull(h.appState.currentRadioId)
        val contact = assertNotNull(store.fetchContact(radio, peerKey))
        val channel = assertNotNull(store.fetchChannel(radio, 0u))
        return Graph(h, contact, channel)
    }

    private fun observeFailures(test: TestScope, graph: Graph): MutableList<UUID> {
        val result = Collections.synchronizedList(mutableListOf<UUID>())
        test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.harness.appState.messageEventStream.events().collect {
                if (it is MessageEvent.MessageFailed) result += it.messageID
            }
        }
        return result
    }

    private suspend fun pending(graph: Graph, kind: Kind): MessageDTO {
        val sender = graph.feature.sender(graph.radio)
        return when (kind) {
            Kind.DIRECT -> sender.createPendingDirect(original, graph.contact)
            Kind.CHANNEL -> sender.createPendingChannel(original, graph.channel)
        }
    }

    @Test fun displayedCreationHoldsReplacementUntilItsOriginalPendingPublicationCommits() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persistence = object : PersistenceStoreProtocol by store, ChannelHistoryPersisting by store {
            override suspend fun createOrReconcileChannelMessage(
                creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant,
            ): MessageDTO {
                entered.complete(Unit)
                release.await()
                return store.createOrReconcileChannelMessage(creation, timestamp, createdAt)
            }
        }
        val graph = graph(this, persistence)
        val h = graph.harness
        try {
            val displayed = graph.channel
            val creation = backgroundScope.async {
                graph.feature.sender(graph.radio).createPendingChannel(original, displayed)
            }
            entered.await()
            val replacement = backgroundScope.async {
                store.saveChannel(graph.radio, ChannelInfo(displayed.index, "Replacement", Bytes(ByteArray(16) { 0x22 })))
            }
            testScheduler.runCurrent()
            assertFalse(creation.isCompleted)
            assertFalse(replacement.isCompleted, "Replacement must not pass the validated publication boundary")
            release.complete(Unit)
            val saved = creation.await()
            replacement.await()
            assertEquals(displayed.occupantID, saved.channelOccupantID)
            assertEquals(saved, store.fetchMessage(EntityKey(graph.radio, saved.id)))
            assertNotEquals(saved.channelOccupantID, store.fetchChannel(graph.radio, displayed.index)?.occupantID)
            val failure = assertFailsWith<PersistenceStoreException> {
                store.validateChannelMessageOrigin(EntityKey(graph.radio, saved.id))
            }
            assertEquals(ChannelHistoryConflict.STALE_OCCUPANT, assertIs<ChannelHistoryRejected>(failure.cause).reason)
            assertTrue(store.fetchPendingSends(graph.radio).isEmpty())
            assertTrue(h.links.radios.single().sent.none { it[0].toInt() == 3 })
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun displayedChannelCannotCreateForAReplacementWithTheSameRowID() = runTest {
        rejectsDisplayedOrigin(restoreOriginalSecret = false, missingWitness = false)
    }

    @Test fun displayedChannelCannotCreateAfterAnABAReplacement() = runTest {
        rejectsDisplayedOrigin(restoreOriginalSecret = true, missingWitness = false)
    }

    @Test fun aMissingDisplayedWitnessCannotBeFilledFromTheCurrentSlot() = runTest {
        rejectsDisplayedOrigin(restoreOriginalSecret = false, missingWitness = true)
    }

    private suspend fun TestScope.rejectsDisplayedOrigin(restoreOriginalSecret: Boolean, missingWitness: Boolean) {
        val graph = graph(this)
        val h = graph.harness
        try {
            val displayed = graph.channel
            assertNotNull(displayed.occupantID)
            if (!missingWitness) {
                store.saveChannel(graph.radio, ChannelInfo(displayed.index, "Replacement", Bytes(ByteArray(16) { 0x22 })))
                if (restoreOriginalSecret) store.saveChannel(graph.radio, displayed.toChannelInfo())
                val current = assertNotNull(store.fetchChannel(graph.radio, displayed.index))
                assertEquals(displayed.id, current.id, "The reusable row UUID is deliberately unchanged")
                assertNotEquals(displayed.occupantID, current.occupantID)
                if (restoreOriginalSecret) assertEquals(displayed.secret, current.secret)
            }
            val intent = if (missingWitness) displayed.copy(occupantID = null) else displayed
            val failure = assertFailsWith<PersistenceStoreException> {
                graph.feature.sender(graph.radio).createPendingChannel(original, intent)
            }
            assertEquals(
                if (missingWitness) ChannelHistoryConflict.UNKNOWN_AUTHORITY else ChannelHistoryConflict.STALE_OCCUPANT,
                assertIs<ChannelHistoryRejected>(failure.cause).reason,
            )
            assertTrue(store.fetchMessages(graph.radio, displayed.index).isEmpty())
            assertTrue(store.fetchPendingSends(graph.radio).isEmpty())
            assertTrue(h.links.radios.single().sent.none { it[0].toInt() == 3 })
        } finally { h.container.close() }
    }

    @Test fun displayedOriginSurvivesASameOccupantRenameBeforeCreation() = runTest {
        val graph = graph(this)
        val h = graph.harness
        try {
            val displayed = graph.channel
            val occupant = assertNotNull(displayed.occupantID)
            store.saveChannel(graph.radio, displayed.toChannelInfo().copy(name = "Renamed"))
            assertEquals(occupant, store.fetchChannel(graph.radio, displayed.index)?.occupantID)
            val saved = graph.feature.sender(graph.radio).createPendingChannel(original, displayed)
            assertEquals(occupant, saved.channelOccupantID)
            assertEquals(original, saved.text)
            assertEquals(saved, store.fetchMessage(EntityKey(graph.radio, saved.id)))
            assertTrue(store.fetchPendingSends(graph.radio).isEmpty())
            assertTrue(h.links.radios.single().sent.none { it[0].toInt() == 3 })
        } finally { h.container.close() }
    }

    @Test fun explicitChannelResendForwardsTheSavedOriginIntoTheRealQueueAndAttempt() = runTest {
        val graph = graph(this)
        val h = graph.harness
        try {
            val saved = pending(graph, Kind.CHANNEL)
            val occupant = assertNotNull(saved.channelOccupantID)
            val key = EntityKey(graph.radio, saved.id)
            store.updateMessageStatus(key, MessageStatus.FAILED)
            val failed = assertNotNull(store.fetchMessage(key))
            val observed = Collections.synchronizedList(mutableListOf<PendingSendDTO>())
            val firmware = h.links.radios.single()
            firmware.respond = { command ->
                if (command[0].toInt() == 3) {
                    observed += runBlocking { store.fetchPendingSendsForMessage(key).single() }
                    listOf(Bytes.of(1, 2))
                } else null
            }
            graph.feature.sendAgain(failed.copy(channelOccupantID = UUID.randomUUID()), graph.conversation(Kind.CHANNEL))
            h.eventually("explicit resend retains its exact saved origin and uncertain attempt") {
                runBlocking {
                    store.fetchPendingSendsForMessage(key).singleOrNull()?.channelDispatchState ==
                        QueuedChannelDispatchState.UNCERTAIN
                }
            }
            val retained = store.fetchPendingSendsForMessage(key).single()
            val attempt = store.fetchQueuedChannelAttempts(EntityKey(graph.radio, retained.id)).single()
            assertEquals(1, observed.size)
            assertEquals(saved.id, retained.messageID)
            assertEquals(occupant, retained.channelOccupantID)
            assertEquals(occupant, observed.single().channelOccupantID)
            assertEquals(occupant, attempt.channelOccupantID)
            assertEquals(saved.text, retained.messageText)
            assertEquals(saved.timestamp, retained.messageTimestamp)
            assertTrue(retained.isResend)
            assertTrue(attempt.isResend)
            assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempt.phase)
            assertEquals(occupant, store.fetchMessage(key)?.channelOccupantID)
            assertEquals(1, firmware.sent.count { it[0].toInt() == 3 })
        } finally { h.container.close() }
    }

    private suspend fun enqueue(graph: Graph, kind: Kind, message: MessageDTO) {
        val sender = graph.feature.sender(graph.radio)
        when (kind) {
            Kind.DIRECT -> sender.enqueueDM(DirectMessageEnvelope(message.id, graph.contact.id))
            Kind.CHANNEL -> sender.enqueueChannel(ChannelMessageEnvelope(
                message.id, graph.channel.index, false, message.text, message.timestamp,
                graph.harness.appState.connectedDevice?.nodeName,
                checkNotNull(message.channelOccupantID),
            ))
        }
    }

    private fun holder(test: TestScope, graph: Graph, kind: Kind, port: ComposerSendPort, failures: MutableList<Throwable>): ComposerStateHolder {
        val target = graph.target(kind)
        val environment = graph.feature.environment(target, test.backgroundScope, noLocation)
        graph.harness.eventually("composer environment ready") {
            environment.connectionState.value == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        }
        return ComposerStateHolder(
            target, port, graph.feature.drafts, environment, test.backgroundScope,
            ComposerTimer { delay(it) }, ComposerDiagnostics { _, failure -> failures += failure },
        )
    }

    private suspend fun terminalModes(test: TestScope, kind: Kind) {
        val graph = graph(test)
        val h = graph.harness
        val failed = observeFailures(test, graph)
        val snapshots = Collections.synchronizedList(mutableListOf<PendingSendDTO>())
        val queueQueries = Collections.synchronizedList(mutableListOf<Bytes>())
        val firmware = h.links.radios.single()
        val opcode = if (kind == Kind.DIRECT) 2 else 3
        firmware.respond = { frame ->
            when {
                frame[0].toInt() == opcode -> {
                    snapshots += runBlocking { store.fetchPendingSends(graph.radio).single() }
                    listOf(Bytes.of(1, if (kind == Kind.DIRECT) 2 else 6))
                }
                kind == Kind.CHANNEL && frame[0].toInt() == 31 -> {
                    queueQueries += frame
                    emptyList()
                }
                else -> null
            }
        }
        try {
            val message = pending(graph, kind)
            val key = EntityKey(graph.radio, message.id)
            if (kind == Kind.CHANNEL) {
                enqueue(graph, kind, message)
                h.eventually("raw channel error is retained as uncertain without retry or terminal event") {
                    runBlocking {
                        store.fetchMessage(key)?.status == MessageStatus.PENDING &&
                            store.fetchPendingSendsForMessage(key).singleOrNull()
                                ?.channelDispatchState == QueuedChannelDispatchState.UNCERTAIN
                    }
                }
                val retained = store.fetchPendingSendsForMessage(key).single()
                assertEquals(1L, retained.attemptCount)
                assertNotNull(retained.channelAttemptID)
                assertEquals(original, assertNotNull(store.fetchMessage(key)).text)
                assertEquals(1, snapshots.size)
                assertEquals(message.id, snapshots.single().messageID)
                assertFalse(snapshots.single().isResend)
                assertTrue(failed.isEmpty())
                assertTrue(queueQueries.isEmpty(), "Raw channel errors do not authorize GET_CHANNEL")
                assertEquals(1, firmware.sent.count { it[0].toInt() == opcode })
                assertEquals(1, h.lifecycle.created.size)
                return
            }
            repeat(3) { mode ->
                val before = assertNotNull(store.fetchMessage(key))
                when (mode) {
                    0 -> enqueue(graph, kind, before)
                    1 -> graph.feature.timeline(graph.conversation(kind)).retryService.retry(before, graph.conversation(kind))
                    2 -> graph.feature.sendAgain(before, graph.conversation(kind))
                }
                h.eventually("terminal queue event and cleanup $kind/$mode") {
                    failed.size == mode + 1 && runBlocking {
                        store.fetchMessage(key)?.status == MessageStatus.FAILED &&
                            store.fetchPendingSendsForMessage(key).isEmpty()
                    }
                }
                assertEquals(original, assertNotNull(store.fetchMessage(key)).text)
                val accepted = snapshots[mode]
                assertEquals(message.id, accepted.messageID)
                assertEquals(1L, accepted.attemptCount)
                assertEquals(mode == 2 || kind == Kind.CHANNEL && mode == 1, accepted.isResend)
                if (kind == Kind.CHANNEL) {
                    assertEquals(original, accepted.messageText)
                    assertEquals(before.timestamp, accepted.messageTimestamp)
                    assertEquals("TestNode", accepted.localNodeName)
                }
            }
            assertEquals(if (kind == Kind.DIRECT) listOf(false, false, true) else listOf(false, true, true), snapshots.map { it.isResend })
            assertEquals(List(3) { message.id }, failed.toList())
            assertEquals(3, firmware.sent.count { it[0].toInt() == opcode })
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(0, assertNotNull(h.sessions.current).chatSendQueueService.queuedEnvelopeCount)
        } finally { h.container.close() }
    }

    @Test fun directPendingRetryAndSendAgainHaveOneDurableOwnerAndTerminalEvent() = runTest {
        terminalModes(this, Kind.DIRECT)
    }

    @Test fun channelPendingRawErrorRemainsUncertainWithoutRetryOrFailureEvent() = runTest {
        terminalModes(this, Kind.CHANNEL)
    }

    private suspend fun localFailure(test: TestScope, persistence: Boolean) {
        for (kind in Kind.entries) {
            val graph = graph(test)
            val h = graph.harness
            val failed = observeFailures(test, graph)
            val diagnostics = Collections.synchronizedList(mutableListOf<Throwable>())
            val underlying = graph.feature.sender(graph.radio)
            var saved: MessageDTO? = null
            val port = object : ComposerSendPort by underlying {
                private suspend fun created(message: MessageDTO): MessageDTO {
                    saved = message
                    if (!persistence) h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                    return message
                }
                override suspend fun createPendingDirect(text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?) =
                    created(underlying.createPendingDirect(text, contact, textType, replyToId))
                override suspend fun createPendingChannel(text: String, channel: ChannelDTO, textType: TextType) =
                    created(underlying.createPendingChannel(text, channel, textType))
            }
            graph.select(kind)
            val composer = holder(test, graph, kind, port, diagnostics)
            if (persistence) db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER wp502_reject_enqueue BEFORE INSERT ON pending_sends BEGIN SELECT RAISE(ABORT, 'enqueue fixture'); END",
            )
            try {
                composer.onDraftChanged(original)
                assertTrue(composer.send())
                h.eventually("saved optimistic row failed with actual enqueue error $kind") {
                    failed.size == 1 && composer.state.value.sendPhase is ComposerSendPhase.Failed
                }
                val phase = assertIs<ComposerSendPhase.Failed>(composer.state.value.sendPhase)
                assertTrue(phase.messageSaved)
                val error = assertIs<ChatSendQueueServiceException>(diagnostics.single())
                if (persistence) assertIs<PersistenceStoreException>(assertIs<ChatSendQueueServiceError.PersistFailed>(error.error).underlying)
                else assertEquals(ChatSendQueueServiceError.NotConnected, error.error)
                val message = assertNotNull(saved)
                val key = EntityKey(graph.radio, message.id)
                val stored = assertNotNull(store.fetchMessage(key))
                assertEquals(original, stored.text)
                assertEquals(MessageStatus.FAILED, stored.status)
                assertTrue(stored.failureSeen)
                assertEquals(listOf(message.id), failed.toList())
                assertTrue(store.fetchPendingSendsForMessage(key).isEmpty())
                assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
                assertEquals("", composer.state.value.draft)
                store.updateMessageStatus(key, MessageStatus.DELIVERED)
                underlying.recordLocalEnqueueFailure(message.id)
                h.scheduler.runCurrent()
                assertEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(key)).status)
                assertEquals(1, failed.size)
            } finally {
                composer.close()
                if (persistence) db.openHelper.writableDatabase.execSQL("DROP TRIGGER wp502_reject_enqueue")
                h.container.close()
            }
        }
    }

    @Test fun missingQueueProviderSurfacesTypedFailureAndFailsSavedDirectAndChannelRows() = runTest {
        localFailure(this, persistence = false)
    }

    @Test fun realRoomEnqueueFailureSurfacesTypedCauseAndFailsSavedDirectAndChannelRows() = runTest {
        localFailure(this, persistence = true)
    }

    @Test fun failedSeenBookkeepingCannotSuppressDurableFailureEventOrRefresh() = runTest {
        for (kind in Kind.entries) {
            val graph = graph(this)
            val h = graph.harness
            val failed = observeFailures(this, graph)
            graph.select(kind)
            val message = pending(graph, kind)
            val key = EntityKey(graph.radio, message.id)
            val before = h.appState.conversationsVersion.value
            val records = Collections.synchronizedList(mutableListOf<LogRecord>())
            val handler = object : Handler() {
                override fun publish(record: LogRecord) {
                    if (record.message == "Mark visible failed send seen") {
                        assertTrue(h.appState.conversationsVersion.value > before)
                        records += record
                    }
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            val logger = Logger.getLogger("com.mc1.AppChatsFeature")
            logger.addHandler(handler)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER wp502_reject_seen BEFORE UPDATE OF failureSeen ON messages " +
                    "WHEN NEW.failureSeen = 1 BEGIN SELECT RAISE(ABORT, 'seen fixture'); END",
            )
            try {
                graph.feature.sender(graph.radio).recordLocalEnqueueFailure(message.id)
                h.eventually("real failure published despite rejected seen write") { failed.size == 1 }
                val stored = assertNotNull(store.fetchMessage(key))
                assertEquals(MessageStatus.FAILED, stored.status)
                assertFalse(stored.failureSeen)
                assertEquals(listOf(message.id), failed.toList())
                assertIs<PersistenceStoreException>(records.single().thrown)
                assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER wp502_reject_seen")
                logger.removeHandler(handler)
                h.container.close()
            }
        }
    }

    @Test fun cancelledComposerAcceptanceRemainsDurableForOneReplacementGeneration() = runTest {
        val graph = graph(this)
        val h = graph.harness
        val failed = observeFailures(this, graph)
        val diagnostics = Collections.synchronizedList(mutableListOf<Throwable>())
        val accepted = CompletableDeferred<UUID>()
        val underlying = graph.feature.sender(graph.radio)
        h.links.radios.single().respond = { if (it[0].toInt() == 2) emptyList() else null }
        val port = object : ComposerSendPort by underlying {
            override suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
                underlying.enqueueDM(envelope)
                accepted.complete(envelope.messageID)
                awaitCancellation()
            }
        }
        val composer = holder(this, graph, Kind.DIRECT, port, diagnostics)
        try {
            composer.onDraftChanged(original)
            assertTrue(composer.send())
            h.eventually("durable queue acceptance", virtualBudgetMillis = 0) { accepted.isCompleted }
            val id = accepted.await()
            val key = EntityKey(graph.radio, id)
            composer.close()
            h.scheduler.runCurrent()
            assertTrue(diagnostics.isEmpty())
            assertTrue(failed.isEmpty())
            assertEquals(1, store.fetchPendingSendsForMessage(key).size)
            assertNotEquals(MessageStatus.FAILED, assertNotNull(store.fetchMessage(key)).status)
            val old = assertNotNull(h.sessions.current)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertTrue(old.isTornDown)
            assertEquals(1, store.fetchPendingSendsForMessage(key).size)
            h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000215")))
            h.eventually("replacement generation owns terminal failure") {
                failed.size == 1 && runBlocking {
                    store.fetchMessage(key)?.status == MessageStatus.FAILED &&
                        store.fetchPendingSendsForMessage(key).isEmpty()
                }
            }
            assertNotEquals(old.token, assertNotNull(h.sessions.current).token)
            assertEquals(2, h.lifecycle.created.size)
            assertEquals(1, h.lifecycle.tornDown.size)
            assertEquals(listOf(id), failed.toList())
            assertEquals(original, assertNotNull(store.fetchMessage(key)).text)
        } finally {
            composer.close()
            h.container.close()
        }
    }
}
