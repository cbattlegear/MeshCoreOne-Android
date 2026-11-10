// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.FirmwareRadio
import com.meshcoreone.android.app.container.openMemoryDatabase
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ChannelServiceClock
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*

internal fun assertOriginalChannelDeviceError2(cause: Throwable) {
    val error = generateSequence(cause) { it.cause }
        .filterIsInstance<MeshCoreException.DeviceError>()
        .firstOrNull()
    assertNotNull("Expected raw DeviceError(2) in cause chain: ${cause.stackTraceToString()}", error)
    assertEquals(2.toUByte(), error!!.code)
}

internal suspend fun TestScope.withChannelQueueFixture(
    attempts: Long,
    config: MessageServiceConfig = MessageServiceConfig(),
    queueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    body: suspend (ChannelQueueOriginalFixture) -> Unit,
) {
    val f = ChannelQueueOriginalFixture(this, attempts, config, queueConfig)
    var primaryFailure: Throwable? = null
    suspend fun retainFailure(action: suspend () -> Unit) {
        try { action() } catch (failure: Throwable) {
            val primary = primaryFailure
            if (primary == null) primaryFailure = failure
            else if (primary !== failure) primary.addSuppressed(failure)
        }
    }
    retainFailure {
        f.seed()
        body(f)
        assertEquals(listOf(f.device), f.store.fetchDevices())
        assertEquals(listOf(f.channel), f.store.fetchChannels(f.radio))
        assertTrue(f.store.fetchContacts(f.radio).isEmpty())
    }
    withContext(NonCancellable) {
        retainFailure {
            val report = f.queue.shutdown()
            assertTrue(report.isComplete)
        }
        retainFailure { assertTrue(f.messages.close().isComplete) }
        retainFailure { f.session.stop() }
        retainFailure { f.channelDispatchFence.retire(f.channelDispatchLease) }
        retainFailure { f.store.close() }
        retainFailure { f.job.cancelAndJoin() }
        retainFailure { f.database.close() }
    }
    primaryFailure?.let { throw it }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChannelQueueOriginalFixture(
    val test: TestScope,
    private val attempts: Long,
    config: MessageServiceConfig,
    queueConfig: ChatSendQueueConfig,
) {
    val job = SupervisorJob(test.coroutineContext[Job])
    private val scope = CoroutineScope(test.coroutineContext + job)
    private val instant = Instant.ofEpochSecond(1_704_067_200)
    private val clock = object : SessionClock {
        override val now: Duration get() = test.testScheduler.currentTime.milliseconds
        override val wallClock: Clock = Clock.fixed(instant, ZoneOffset.UTC)
        override suspend fun sleepFor(duration: Duration) = delay(duration)
    }
    val device = DeviceDTO(
        id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 0x11 }),
        nodeName = "Test Radio", firmwareVersion = 0u, firmwareVersionString = "", manufacturerName = "", buildDate = "",
        maxContacts = 100u, maxChannels = 8u, frequency = 915_000u, bandwidth = 250_000u,
        spreadingFactor = 10u, codingRate = 5u, txPower = 20, maxTxPower = 20,
        latitude = 0.0, longitude = 0.0, blePin = 0u, clientRepeat = false, pathHashMode = 0u,
        defaultFloodScopeName = null, preRepeatFrequency = null, preRepeatBandwidth = null,
        preRepeatSpreadingFactor = null, preRepeatCodingRate = null, manualAddContacts = false,
        autoAddConfig = 0u, autoAddMaxHops = 0u, multiAcks = 2u, telemetryModeBase = 2u,
        telemetryModeLoc = 0u, telemetryModeEnv = 0u, advertLocationPolicy = 0u,
        lastConnected = instant, lastContactSync = 0u, isActive = false, ocvPreset = null,
        appliedRadioPresetID = null, customOCVArrayString = null,
        connectionMethods = SnapshotList.empty(), knownRegions = SnapshotList.empty(),
    )
    val radio = device.radioId
    private val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), radio)
    val channel = ChannelDTO(UUID.randomUUID(), radio, 0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }),
        true, null, 0, 0, NotificationLevel.ALL, false, "inherit", null)
    private val channelInfoResponse =
        Bytes.fromHex("1200" + "54657374204368616e6e656c" + "00".repeat(20) + "AA".repeat(16))
    private val unconfiguredChannelInfoResponse = Bytes.fromHex("1200" + "00".repeat(48))
    val database = openMemoryDatabase()
    val store = RoomPersistenceStore(database, scope, clock.wallClock)
    private val signals = Signals(token)
    private val diagnostics = mutableListOf<Pair<Long, MessagingDiagnostic>>()
    private val reporter = MessagingIssueReporter { diagnostics += test.testScheduler.currentTime to it }
    private var channelInfoReads = 0
    val channelInfoReadCount: Int get() = channelInfoReads
    private val transport = FirmwareRadio(device.publicKey).also { radio ->
        radio.respond = { command ->
            when (command[0].toInt()) {
                1 -> listOf(Bytes.fromHex("05001414" + "11".repeat(32) +
                    "00000000000000000200020038f60d0090d003000a05546573744e6f6465"))
                3 -> listOf(Bytes.of(1, 2))
                31 -> listOf(if (channelInfoReads++ == 0) channelInfoResponse else unconfiguredChannelInfoResponse)
                else -> error("Unexpected command in channel original: ${command.hexString}")
            }
        }
    }
    val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 0.2), clock, scope.coroutineContext)
    val channelDispatchFence = ChannelDispatchFence(ChannelIdentityMutex.PROCESS)
    val channelDispatchLease = channelDispatchFence.openSession(
        token, session, ChannelDispatchAdmission(token, session),
    )
    val messages = MessageService(token, device.publicKey, session, store, signals, scope,
        config, clock, jitter = { 1.0 }, reporter = reporter, channelDispatchLease = channelDispatchLease)
    private val channelService = ChannelService(session, store, null, ChannelServiceClock { delay(it) }, Random(0))
    val queries = mutableListOf<UByte>()
    val queryResults = mutableListOf<ChannelInfo?>()
    val queue = ChatSendQueueService(token, store, messages, MessagingChannelQuery { index ->
        queries += index
        channelService.fetchChannel(index).also { queryResults += it }
    }, OutgoingChannelReactionIndexer { _, _, _, _, _ -> error("Failed originals must not index reactions") },
        signals, scope, queueConfig, clock, reporter)
    lateinit var messageA: MessageDTO
    lateinit var messageB: MessageDTO
    lateinit var pendingA: PendingSendDTO

    suspend fun seed() {
        require(attempts in 0..Int.MAX_VALUE.toLong())
        assertNotEquals(device.id, radio.value)
        store.saveDevice(device)
        store.saveChannel(channel)
        assertEquals(device, store.fetchDevice(device.id))
        assertEquals(channel, store.fetchChannel(radio, 0u))
        session.start()
        assertEquals(SelfInfo(0u, 20, 20, device.publicKey, 0.0, 0.0, 2u, 0u, 0u, 0u, 2u,
            false, 915.0, 250.0, 10u, 5u, "TestNode"), session.currentSelfInfo)
        val observed = checkNotNull(channelService.fetchChannel(channel.index))
        check(observed == ChannelInfo(channel.index, channel.name, channel.secret)) {
            "The active radio did not return the seeded channel occupant"
        }
        channelOccupantID = store.withChannelOperation { scoped, identity ->
            val persisted = scoped.batchSaveChannels(radio, listOf(observed).snapshot(), SnapshotList.empty(), null)
            check(persisted.any { it.index == channel.index })
            val occupant = checkNotNull(scoped.fetchChannelOccupantID(radio, channel.index))
            channelDispatchFence.recordReconciled(channelDispatchLease, identity, listOf(channel.index))
            occupant
        }
        assertEquals(channel, store.fetchChannel(radio, channel.index))
        messageA = channelMessage("Hello channel")
        messageB = channelMessage("Second message")
        assertNotEquals(messageA.id, messageB.id)
        assertMessages(messageA, messageB)
        val envelopeA = ChannelMessageEnvelope(messageA.id, 0u, true, "Hello channel",
            1_704_067_200u, "Test Radio", channelOccupantID)
        val submission = ChannelSubmission(
            UUID.randomUUID(), ChannelHistoryScope(radio, channel.index, channelOccupantID), envelopeA,
        )
        store.admitChannelSubmission(submission, instant)
        repeat(attempts.toInt()) {
            store.incrementReadyQueuedChannelAttemptCount(EntityKey(radio, submission.pendingID), messageA.id)
        }
        pendingA = checkNotNull(store.fetchPendingSendsForMessage(EntityKey(radio, submission.pendingID)).singleOrNull())
        assertRows(pendingA)
        assertEquals(envelopeA, pendingA.channelMessageEnvelope())
        val envelopeB = ChannelMessageEnvelope(messageB.id, 0u, false, "Second message",
            1_704_067_200u, "Test Radio", channelOccupantID)
        val pendingB = PendingSendDTO.fromEnvelope(envelopeB, radio, enqueuedAt = instant)
        assertEquals(envelopeB, pendingB.channelMessageEnvelope())
    }

    suspend fun startDrain() { queue.hydrate(); test.runCurrent(); signals.ready(); test.runCurrent() }
    suspend fun assertRows(vararg rows: PendingSendDTO) { assertEquals(rows.toList(), store.fetchPendingSends(radio)) }
    suspend fun assertMessages(a: MessageDTO, b: MessageDTO) {
        assertEquals(a, store.fetchMessage(EntityKey(radio, messageA.id)))
        assertEquals(b, store.fetchMessage(EntityKey(radio, messageB.id)))
    }
    suspend fun assertUncertainAttempt(pending: PendingSendDTO, expectedAttemptCount: Long): QueuedChannelAttempt {
        val attempts = store.fetchQueuedChannelAttempts(EntityKey(radio, pending.id))
        assertEquals(1, attempts.size)
        val attempt = attempts.single()
        val expected = pending.copy(
            attemptCount = expectedAttemptCount,
            channelDispatchState = QueuedChannelDispatchState.UNCERTAIN,
            channelAttemptID = attempt.key.attemptID,
        )
        assertRows(expected)
        assertEquals(channelOccupantID, pending.channelOccupantID)
        assertEquals(channelOccupantID, attempt.channelOccupantID)
        assertEquals(pending.channelIndex, attempt.channelIndex)
        assertEquals(QueuedChannelAttemptPhase.UNCERTAIN, attempt.phase)
        assertFalse(attempt.isCompleted)
        return attempt
    }
    fun sends(command: Int) = transport.sent.filter { it[0].toInt() == command }
    fun failures(phase: String, id: UUID) = diagnostics.map { it.second }
        .filterIsInstance<MessagingDiagnostic.Failure>().filter { it.operation == "$phase.$id" }.map { it.cause }

    private suspend fun channelMessage(text: String) = store.createOrReconcileChannelMessage(
        ChannelMessageCreation(UUID.randomUUID(), ChannelHistoryScope(radio, channel.index, channelOccupantID), text),
        timestamp = 1_704_067_200u,
        createdAt = instant,
    )

    lateinit var channelOccupantID: UUID

    private class Signals(private val token: SessionToken) : ConnectionSignals {
        private val subscriptions = mutableSetOf<Channel<ConnectionSnapshot>>()
        override val snapshot = MutableStateFlow(value(DeviceConnectionState.CONNECTED))
        private fun value(state: DeviceConnectionState) = ConnectionSnapshot(state, ConnectionState.Connected,
            null, ConnectionIntent.WantsConnection(), token, null)
        fun ready() {
            snapshot.value = value(DeviceConnectionState.READY)
            subscriptions.toList().forEach { it.trySend(snapshot.value).getOrThrow() }
        }
        override suspend fun subscribeTransitions(): ConnectionSubscription {
            val channel = Channel<ConnectionSnapshot>(Channel.UNLIMITED)
            subscriptions += channel
            return object : ConnectionSubscription {
                override val initial = snapshot.value
                override val transitions = channel.receiveAsFlow()
                override fun close() { subscriptions -= channel; channel.close() }
            }
        }
    }
}
