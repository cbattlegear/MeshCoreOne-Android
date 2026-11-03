// AndroidOnly: WP-209 Real-session cancellation, accepted-write accounting and replacement regressions.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.parser.Parsers
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransportException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactWriteRecoveryTests {
    private val radioA = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000209"))
    private val radioB = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000210"))

    @Test
    fun cancellationQueuedBehindAnActualExchangeIsDefinitelyUnsent() = runTest {
        withFixture {
            val blocking = async { session.addContact(frame(1, "Blocking").toMeshContact()) }
            runCurrent()
            val queued = async { service().addOrUpdateContact(radioA, frame(2, "Unsent")) }
            runCurrent()
            queued.cancelAndJoin()
            radio.accept()
            runCurrent()
            blocking.await()
            assertEquals(1, radio.writes.size)
            assertNull(store.fetchContact(radioA, contactsKey(2)))
            assertFalse(service().needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun cancelledCallerNeverReturnsSuccessButLateAckPersistsOnTheInitiatingRadio() = runTest {
        withFixture {
            val other = ContactDTO.fromFrame(radioB, frame(1, "Other radio"))
            store.seed(other)
            val coordinator = ContactsFakeSyncCoordinator()
            val producer = service(coordinator = coordinator)
            var navigated = false
            val delivery = async {
                producer.addOrUpdateContact(radioA, frame(1, "Alice"))
                navigated = true
            }
            runCurrent()
            assertEquals(1, radio.writes.size)
            delivery.cancelAndJoin()
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            radio.accept()
            runCurrent()
            assertEquals("Alice", store.fetchContact(radioA, contactsKey(1))?.name)
            assertEquals("Alice", radio.contact(contactsKey(1))?.advertisedName)
            assertEquals(other, store.fetchContact(radioB, contactsKey(1)))
            assertEquals(listOf("contactsChanged"), coordinator.notifications)
            assertFalse(navigated)
            assertTrue(delivery.isCancelled)
            assertFalse(producer.needsContactWriteReconciliation(radioB))
        }
    }

    @Test fun identicalReplacementDeliveryCannotConsumeTheRetiredAck() = replacementDelivery(sameKey = true)
    @Test fun differentReplacementDeliveryCannotConsumeTheRetiredAck() = replacementDelivery(sameKey = false)

    private fun replacementDelivery(sameKey: Boolean) = runTest {
        withFixture {
            val producer = service()
            var oldAcknowledged = false
            val old = async {
                producer.addOrUpdateContact(radioA, frame(1, "Old"))
                oldAcknowledged = true
            }
            runCurrent()
            old.cancelAndJoin()
            val replacement = frame(if (sameKey) 1 else 2, "Replacement", modified = 2u)
            val next = async { producer.addOrUpdateContact(radioA, replacement) }
            runCurrent()
            assertEquals(1, radio.writes.size)
            radio.accept()
            runCurrent()
            assertEquals("Old", store.fetchContact(radioA, contactsKey(1))?.name)
            assertEquals(2, radio.writes.size)
            assertFalse(next.isCompleted)
            radio.accept()
            runCurrent()
            next.await()
            assertEquals("Replacement", store.fetchContact(radioA, replacement.publicKey)?.name)
            assertFalse(oldAcknowledged)
        }
    }

    @Test
    fun terminalRejectionPreservesTypedFailureAndDoesNotInventPersistenceOrUncertainty() = runTest {
        withFixture {
            val producer = service()
            val result = backgroundScope.async {
                try {
                    producer.addOrUpdateContact(radioA, frame(1, "Full"))
                    null
                } catch (error: ContactServiceError) {
                    error
                }
            }
            runCurrent()
            radio.reject(3)
            runCurrent()
            assertIs<ContactServiceError.ContactTableFull>(result.await())
            assertTrue(store.fetchContacts(radioA).isEmpty())
            assertNull(radio.contact(contactsKey(1)))
            assertFalse(producer.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun lateRejectionAfterCancellationRemainsRecoverableWithoutSuccess() = runTest {
        withFixture {
            val producer = service()
            val result = async { producer.addOrUpdateContact(radioA, frame(1, "Rejected")) }
            runCurrent()
            result.cancelAndJoin()
            radio.reject(3)
            runCurrent()
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            assertTrue(store.fetchContacts(radioA).isEmpty())
            val replacement = service()
            val sync = replacement.syncContacts(radioA, Instant.ofEpochSecond(50))
            assertFalse(sync.isIncremental)
            assertFalse(replacement.needsContactWriteReconciliation(radioA))
            assertEquals(1, radio.contactRequests.single().size)
        }
    }

    @Test
    fun unansweredAcceptedWriteRequiresFullReconciliationInTheReplacementSession() = runTest {
        withFixture {
            val producer = service()
            val result = backgroundScope.async {
                try {
                    producer.addOrUpdateContact(radioA, frame(1, "Uncertain"))
                    null
                } catch (error: ContactServiceError.SessionError) {
                    error
                }
            }
            runCurrent()
            radio.accept(reply = false)
            advanceTimeBy(5_001)
            runCurrent()
            assertIs<MeshCoreException.Timeout>(assertNotNull(result.await()).error)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            session.stop()
            val replacement = service(newSession(radio))
            val sync = replacement.syncContacts(radioA, Instant.ofEpochSecond(50))
            assertEquals(1L, sync.contactsReceived)
            assertFalse(sync.isIncremental)
            assertEquals("Uncertain", store.fetchContact(radioA, contactsKey(1))?.name)
            assertFalse(replacement.needsContactWriteReconciliation(radioA))
            assertEquals(1, radio.contactRequests.single().size)
        }
    }

    @Test
    fun localSaveFailureReportsRadioAcceptanceAndFullSyncCanRepairIt() = runTest {
        withFixture {
            val diskFailure = IOException("test disk failure")
            val failing = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult = throw diskFailure
            }
            val producer = service(persistence = failing)
            val result = backgroundScope.async {
                try {
                    producer.addOrUpdateContact(radioA, frame(1, "Accepted"))
                    null
                } catch (error: ContactWriteCompletionFailed) {
                    error
                }
            }
            runCurrent()
            radio.accept()
            runCurrent()
            val failure = assertNotNull(result.await())
            assertSame(diskFailure, failure.cause)
            assertEquals(radioA, failure.radioId)
            assertEquals(contactsKey(1), failure.publicKey)
            assertNotNull(radio.contact(contactsKey(1)))
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            val replacement = service()
            replacement.syncContacts(radioA, Instant.ofEpochSecond(50))
            assertEquals("Accepted", store.fetchContact(radioA, contactsKey(1))?.name)
            assertFalse(replacement.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun cancelledLateAcceptanceWithDiskFailureRetainsRecoveryAfterItsServiceIsRetired() = runTest {
        withFixture {
            val failing = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult = throw IOException("test disk failure")
            }
            val producer = service(persistence = failing)
            val old = async { producer.addOrUpdateContact(radioA, frame(1, "Late")) }
            runCurrent()
            old.cancelAndJoin()
            producer.finishEvents()
            radio.accept()
            runCurrent()
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(service().needsContactWriteReconciliation(radioA))
            assertTrue(diagnostics.any { it is com.meshcoreone.android.core.protocol.session.SessionDiagnostic.BackgroundFailure })
            service().syncContacts(radioA, null)
            assertEquals("Late", store.fetchContact(radioA, contactsKey(1))?.name)
            assertFalse(service().needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun acceptedPersistenceTailKeepsSerializationUntilTheSaveFinishes() = runTest {
        withFixture {
            val saveGate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val held = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult? {
                    entered.complete(Unit)
                    saveGate.await()
                    return store.saveContactPreservingNewer(radioId, frame, expectedFrame)
                }
            }
            val first = async { service(persistence = held).addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            radio.accept()
            runCurrent()
            assertTrue(entered.isCompleted)
            first.cancelAndJoin()
            val second = async { service().addOrUpdateContact(radioA, frame(1, "Second", 2u)) }
            runCurrent()
            assertEquals(1, radio.writes.size)
            saveGate.complete(Unit)
            runCurrent()
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            second.await()
            assertEquals("Second", store.fetchContact(radioA, contactsKey(1))?.name)
        }
    }

    @Test
    fun greaterAndEqualTimestampReplacementMetadataSurvivesOlderCompletion() = runTest {
        withFixture {
            for (modified in listOf(1u, 2u)) {
                val oldFrame = frame(modified.toInt(), "Old", 1u)
                val original = ContactDTO.fromFrame(radioA, oldFrame)
                store.seed(original)
                val old = async { service().addOrUpdateContact(radioA, oldFrame.copy(name = "Imported")) }
                runCurrent()
                old.cancelAndJoin()
                val newer = original.copy(
                    name = "Newer", lastModified = modified, nickname = "Local nickname",
                    unreadCount = 8, isFavorite = true, isMuted = true, avatarImageData = Bytes.of(3, 4),
                )
                store.seed(newer)
                radio.accept()
                runCurrent()
                assertEquals(newer, store.fetchContact(radioA, original.publicKey))
            }
        }
    }

    @Test
    fun liveSupersededCompletionRequiresReconciliationInsteadOfClaimingImportSuccess() = runTest {
        withFixture {
            val producer = service()
            val original = ContactDTO.fromFrame(radioA, frame(1, "Initial"))
            store.seed(original)
            val result = backgroundScope.async {
                try {
                    producer.addOrUpdateContact(radioA, frame(1, "Imported"))
                    null
                } catch (failure: ContactWriteCompletionFailed) {
                    failure
                }
            }
            runCurrent()
            val newer = original.copy(name = "Newer", lastModified = 2u, nickname = "Retained")
            store.seed(newer)
            radio.accept()
            runCurrent()
            assertNotNull(result.await())
            assertEquals(newer, store.fetchContact(radioA, contactsKey(1)))
            assertTrue(producer.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun failedDurableRecoveryRecordingRemainsExplicitAndRequiredInTheProcess() = runTest {
        withFixture {
            val diskFailure = IOException("test recovery flag failure")
            var failCommits = true
            var recoveryWrites = 0
            val flags = object : ContactPreferenceFlags by preferences {
                override fun set(key: String, value: Boolean) {
                    if (key.startsWith("contactWriteReconciliation.")) recoveryWrites++
                    if (failCommits) throw diskFailure
                    preferences.set(key, value)
                }
            }
            val producer = service(flags = flags)
            val write = async { producer.addOrUpdateContact(radioA, frame(1, "Accepted")) }
            runCurrent()
            assertEquals(1, radio.writes.size)
            write.cancelAndJoin()
            assertTrue(write.isCancelled)
            assertEquals(1, recoveryWrites)
            assertFalse(preferences.bool("contactWriteReconciliation.${radioA.value}"))
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            radio.accept()
            runCurrent()
            val replacement = service(flags = flags)
            assertSame(diskFailure, assertFailsWith<IOException> { replacement.syncContacts(radioA, null) })
            assertTrue(replacement.needsContactWriteReconciliation(radioA))
            failCommits = false
            replacement.syncContacts(radioA, null)
            assertFalse(replacement.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun teardownAndDifferentRadioReplacementCannotSaveAnOldReplyIntoTheNewRadio() = runTest {
        withFixture {
            val producer = service()
            val old = async { producer.addOrUpdateContact(radioA, frame(1, "Radio A")) }
            runCurrent()
            old.cancelAndJoin()
            producer.finishEvents()
            session.stop()
            val otherRadio = HeldRadio()
            val otherService = service(newSession(otherRadio))
            val next = async { otherService.addOrUpdateContact(radioB, frame(1, "Radio B")) }
            runCurrent()
            radio.accept(reply = false)
            assertFailsWith<MockTransportException.NotConnected> { radio.replyOK() }
            runCurrent()
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertNull(store.fetchContact(radioB, contactsKey(1)))
            assertFalse(next.isCompleted)
            otherRadio.accept()
            runCurrent()
            next.await()
            assertEquals("Radio B", store.fetchContact(radioB, contactsKey(1))?.name)
            assertTrue(otherService.needsContactWriteReconciliation(radioA))
            assertFalse(otherService.needsContactWriteReconciliation(radioB))
            service(newSession(radio)).syncContacts(radioA, Instant.ofEpochSecond(50))
            assertEquals("Radio A", store.fetchContact(radioA, contactsKey(1))?.name)
            assertEquals("Radio B", store.fetchContact(radioB, contactsKey(1))?.name)
        }
    }

    @Test
    fun incompleteSnapshotCannotClearRecoveryAndAnOlderSnapshotCannotClearANewerUncertainty() = runTest {
        withFixture {
            val old = async { service().addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            old.cancelAndJoin()
            radio.accept()
            runCurrent()
            radio.reportedTotal = 2
            try {
                service().syncContacts(radioA, null)
                throw AssertionError("Incomplete reconciliation was accepted")
            } catch (_: ContactServiceError.SyncInterrupted) {
                assertTrue(service().needsContactWriteReconciliation(radioA))
            }
            radio.reportedTotal = null
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val held = object : ContactPersisting by store {
                override suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long {
                    entered.complete(Unit)
                    gate.await()
                    return store.batchSaveContacts(radioId, frames)
                }
            }
            val snapshot = async { service(persistence = held).syncContacts(radioA, null) }
            runCurrent()
            assertTrue(entered.isCompleted)
            val newer = async { service().addOrUpdateContact(radioA, frame(2, "Second")) }
            runCurrent()
            newer.cancelAndJoin()
            gate.complete(Unit)
            runCurrent()
            snapshot.await()
            assertTrue(service().needsContactWriteReconciliation(radioA))
            radio.accept()
            runCurrent()
            service().syncContacts(radioA, null)
            assertFalse(service().needsContactWriteReconciliation(radioA))
        }
    }

    private fun frame(byte: Int, name: String, modified: UInt = 1u) = ContactFrame(
        contactsKey(byte), ContactType.CHAT, 0u, 255u, Bytes.EMPTY, name, 0u, 0.0, 0.0, modified,
    )

    private suspend fun TestScope.withFixture(body: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try {
            fixture.session = fixture.newSession(fixture.radio)
            fixture.body()
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture(private val scope: TestScope) {
        val radio = HeldRadio()
        val store = ContactsFakeStore(listOf(contactsDevice(radioA), contactsDevice(radioB)))
        val preferences = ContactsFakePreferences()
        val diagnostics = mutableListOf<com.meshcoreone.android.core.protocol.session.SessionDiagnostic>()
        lateinit var session: MeshCoreSession
        private val sessions = mutableListOf<MeshCoreSession>()

        suspend fun newSession(transport: HeldRadio): MeshCoreSession {
            val clock = object : SessionClock {
                override val now: Duration get() = scope.testScheduler.currentTime.milliseconds
                override val wallClock: Clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC)
                override suspend fun sleepFor(duration: Duration) = delay(duration)
            }
            return MeshCoreSession(
                transport, clock = clock, coroutineContext = scope.backgroundScope.coroutineContext,
                onDiagnostic = { diagnostics += it },
            ).also { sessions += it; it.start() }
        }

        fun service(
            connection: MeshCoreSession = session,
            persistence: ContactPersisting = store,
            coordinator: ContactSyncCoordinating? = null,
            flags: ContactPreferenceFlags = preferences,
        ) = ContactService(connection, store, persistence, coordinator, null, flags)

        suspend fun close() { sessions.asReversed().forEach { it.stop() } }
    }

    private class HeldRadio : MeshTransport {
        private val mock = MockTransport()
        private val pending = ArrayDeque<Bytes>()
        private val rows = linkedMapOf<Bytes, Bytes>()
        val writes = mutableListOf<Bytes>()
        val contactRequests = mutableListOf<Bytes>()
        var reportedTotal: Int? = null

        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun isConnected(): Boolean = mock.isConnected()
        override suspend fun receivedData(): Flow<Bytes> = mock.receivedData()
        override suspend fun send(data: Bytes) {
            mock.send(data)
            when (data[0].toInt()) {
                0x01 -> mock.simulateReceive(
                    Bytes.of(0x05, 1, 22, 22) + contactsKey(0xF0) + le32(0) + le32(0) + Bytes.of(0, 0, 0, 0) +
                        le32(915_000) + le32(125_000) + Bytes.of(7, 5) + Bytes.utf8("Recovery test"),
                )
                0x09 -> { writes += data; pending.addLast(data) }
                0x04 -> {
                    contactRequests += data
                    mock.simulateReceive(Bytes.of(0x02) + le32((reportedTotal ?: rows.size).toLong()))
                    rows.values.forEach { mock.simulateReceive(Bytes.of(0x03) + it) }
                    mock.simulateReceive(Bytes.of(0x04) + le32(100))
                }
            }
        }

        suspend fun accept(reply: Boolean = true) {
            val command = pending.removeFirst()
            val packet = command.slice(1, 144) + le32(100)
            val contact = assertNotNull(Parsers.parseContactData(packet))
            rows[contact.publicKey] = packet
            if (reply) mock.simulateReceive(Bytes.of(0x00))
        }

        suspend fun reject(code: Int) {
            pending.removeFirst()
            mock.simulateReceive(Bytes.of(0x01, code))
        }

        suspend fun replyOK() = mock.simulateReceive(Bytes.of(0x00))

        fun contact(publicKey: Bytes) = rows[publicKey]?.let(Parsers::parseContactData)
    }

    private companion object {
        fun le32(value: Long): Bytes = Bytes(ByteArray(4) { (value ushr (8 * it)).toByte() })
    }
}
