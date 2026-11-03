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

    @Test
    fun queuedSameRadioWriteRechecksRecoveryAtTheSerializedDispatchBoundary() = runTest {
        withFixture {
            val producer = service()
            val first = async { producer.addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            val queued = async {
                try {
                    producer.addOrUpdateContact(radioA, frame(2, "Queued"))
                    null
                } catch (blocked: ContactWriteAdmissionBlocked) {
                    blocked
                }
            }
            runCurrent()
            assertEquals(1, radio.writes.size)

            first.cancelAndJoin()
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            radio.accept()
            runCurrent()

            assertEquals(ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED, assertNotNull(queued.await()).reason)
            assertEquals(1, radio.writes.size, "A queued retry cannot dispatch after the first write becomes uncertain")
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            assertNull(store.fetchContact(radioA, contactsKey(2)))

            producer.syncContacts(radioA, null)
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            val retry = async { producer.addOrUpdateContact(radioA, frame(2, "Queued")) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Queued", store.fetchContact(radioA, contactsKey(2))?.name)
        }
    }

    @Test
    fun successfulCompletionReleasesAdmissionForTheNextSameRadioWrite() = runTest {
        withFixture {
            val producer = service()
            val first = async { producer.addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            radio.accept()
            runCurrent()
            first.await()

            val second = async { producer.addOrUpdateContact(radioA, frame(2, "Second")) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            second.await()
            assertFalse(producer.needsContactWriteReconciliation(radioA))
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
            val replacement = frame(if (sameKey) 1 else 2, "Replacement", modified = 200u)
            val next = async {
                try {
                    producer.addOrUpdateContact(radioA, replacement)
                    null
                } catch (blocked: ContactWriteAdmissionBlocked) {
                    blocked
                }
            }
            runCurrent()
            assertEquals(1, radio.writes.size)
            radio.accept()
            runCurrent()
            assertEquals("Old", store.fetchContact(radioA, contactsKey(1))?.name)
            assertEquals(ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED, assertNotNull(next.await()).reason)
            assertEquals(1, radio.writes.size)
            assertFalse(oldAcknowledged)

            producer.syncContacts(radioA, null)
            val retry = async { producer.addOrUpdateContact(radioA, replacement) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Replacement", store.fetchContact(radioA, replacement.publicKey)?.name)
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
    fun unansweredRadioWriteRemainsBlockedAcrossReplacementWithoutDrainProof() = runTest {
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
            advanceTimeBy(5_001)
            runCurrent()
            assertIs<MeshCoreException.Timeout>(assertNotNull(result.await()).error)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            session.stop()
            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio))
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.syncContacts(radioA, Instant.ofEpochSecond(50))
            }
            assertTrue(replacement.needsContactWriteReconciliation(radioA))
            assertTrue(replacementRadio.contactRequests.isEmpty(), "An unresolved old write blocks snapshot capture")
            assertEquals(
                ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED,
                assertFailsWith<ContactWriteAdmissionBlocked> {
                    replacement.addOrUpdateContact(radioA, frame(2, "Must wait"))
                }.reason,
            )
            assertEquals(1, radio.writes.size)

            radio.accept(reply = false)
            assertEquals("Uncertain", radio.contact(contactsKey(1))?.advertisedName)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.syncContacts(radioA, null)
            }
            assertTrue(replacement.needsContactWriteReconciliation(radioA))
            assertTrue(replacementRadio.contactRequests.isEmpty(), "A later snapshot is still not drain proof")
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
            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio))
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.syncContacts(radioA, null)
            }
            assertTrue(replacementRadio.contactRequests.isEmpty(), "A second session cannot snapshot an active write")

            radio.accept()
            runCurrent()
            val failure = assertNotNull(result.await())
            assertSame(diskFailure, failure.cause)
            assertEquals(radioA, failure.radioId)
            assertEquals(contactsKey(1), failure.publicKey)
            assertNotNull(radio.contact(contactsKey(1)))
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(producer.needsContactWriteReconciliation(radioA))
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
    fun cancelledAcceptedPersistenceTailBlocksAnotherWriteUntilReconciliation() = runTest {
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
            val second = async {
                try {
                    service().addOrUpdateContact(radioA, frame(1, "Second", 2u))
                    null
                } catch (blocked: ContactWriteAdmissionBlocked) {
                    blocked
                }
            }
            runCurrent()
            assertEquals(1, radio.writes.size)
            assertEquals(
                ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED,
                assertNotNull(second.await()).reason,
            )
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                service().syncContacts(radioA, null)
            }
            assertTrue(radio.contactRequests.isEmpty(), "A sync cannot start during the accepted write's local tail")
            saveGate.complete(Unit)
            runCurrent()
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            service().syncContacts(radioA, null)
            val retry = async { service().addOrUpdateContact(radioA, frame(1, "Second", 200u)) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Second", store.fetchContact(radioA, contactsKey(1))?.name)
        }
    }

    @Test
    fun greaterAndEqualTimestampReplacementMetadataSurvivesOlderCompletion() = runTest {
        withFixture {
            val producer = service()
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
                producer.syncContacts(radioA, null)
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
            val replacementRadio = radio.replacementView()
            val replacementService = service(newSession(replacementRadio))
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacementService.syncContacts(radioA, Instant.ofEpochSecond(50))
            }
            assertTrue(replacementService.needsContactWriteReconciliation(radioA))
            assertTrue(
                replacementRadio.contactRequests.isEmpty(),
                "A full snapshot is not proof that the old write drained",
            )
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertEquals("Radio B", store.fetchContact(radioB, contactsKey(1))?.name)
        }
    }

    @Test
    fun fullSyncFenceBlocksWritesUntilSnapshotApplicationAndMarkerClearFinish() = runTest {
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
            val newer = async {
                try {
                    service().addOrUpdateContact(radioA, frame(2, "Second"))
                    null
                } catch (blocked: ContactWriteAdmissionBlocked) {
                    blocked
                }
            }
            runCurrent()
            assertEquals(
                ContactWriteAdmissionBlockReason.SYNC_IN_PROGRESS,
                assertNotNull(newer.await()).reason,
            )
            assertEquals(1, radio.writes.size, "The snapshot cannot be followed by a write before its local tail")
            gate.complete(Unit)
            runCurrent()
            snapshot.await()
            assertFalse(service().needsContactWriteReconciliation(radioA))

            val retry = async { service().addOrUpdateContact(radioA, frame(2, "Second")) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Second", store.fetchContact(radioA, contactsKey(2))?.name)
        }
    }

    @Test
    fun unresolvedWriteRejectsReplacementSyncBeforeSnapshotAndKeepsQueuedWriteBlocked() = runTest {
        withFixture {
            val producer = service()
            val first = async { producer.addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            val queued = async {
                try {
                    producer.addOrUpdateContact(radioA, frame(2, "Queued"))
                    null
                } catch (blocked: ContactWriteAdmissionBlocked) {
                    blocked
                }
            }
            runCurrent()
            assertEquals(1, radio.writes.size)

            first.cancelAndJoin()
            assertFailsWith<ContactServiceError.SyncInterrupted> { producer.syncContacts(radioA, null) }
            assertTrue(radio.contactRequests.isEmpty())
            radio.accept()
            runCurrent()

            assertEquals(
                ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED,
                assertNotNull(queued.await()).reason,
            )
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            assertEquals(1, radio.writes.size)
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)

            val snapshot = producer.syncContacts(radioA, null)
            assertFalse(snapshot.isIncremental)
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            val retry = async { producer.addOrUpdateContact(radioA, frame(2, "Queued")) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Queued", store.fetchContact(radioA, contactsKey(2))?.name)
        }
    }

    @Test
    fun snapshotFenceRejectsQueuedCrossSessionWriteAfterOmittedSnapshotCapture() = runTest {
        withFixture {
            val snapshotEntered = CompletableDeferred<Unit>()
            val releaseSnapshot = CompletableDeferred<Unit>()
            radio.snapshotHold = HeldRadio.SnapshotHold(snapshotEntered, releaseSnapshot)
            val heldRead = async { session.getContactsReportingTotal(null) }
            runCurrent()
            assertTrue(snapshotEntered.isCompleted)

            val queuedWrite = async {
                try {
                    service(connection = session).addOrUpdateContact(radioA, frame(2, "Queued"))
                    null
                } catch (blocked: ContactWriteAdmissionBlocked) {
                    blocked
                }
            }
            runCurrent()

            val replacementRadio = radio.replacementView()
            val replacementSession = newSession(replacementRadio)
            val batchSaveEntered = CompletableDeferred<Unit>()
            val capturedFrames = CompletableDeferred<SnapshotList<ContactFrame>>()
            val releaseBatchSave = CompletableDeferred<Unit>()
            val heldPersistence = object : ContactPersisting by store {
                override suspend fun batchSaveContacts(
                    radioId: RadioId,
                    frames: SnapshotList<ContactFrame>,
                ): Long {
                    batchSaveEntered.complete(Unit)
                    capturedFrames.complete(frames)
                    releaseBatchSave.await()
                    return store.batchSaveContacts(radioId, frames)
                }
            }
            val snapshot = async {
                service(connection = replacementSession, persistence = heldPersistence).syncContacts(radioA, null)
            }
            runCurrent()
            assertTrue(batchSaveEntered.isCompleted, "The replacement captured the snapshot before local application")
            assertTrue(replacementRadio.contactRequests.isNotEmpty())
            assertEquals(0, capturedFrames.await().size, "B captured a snapshot that omitted A's queued contact")

            releaseSnapshot.complete(Unit)
            runCurrent()
            heldRead.await()
            assertEquals(
                ContactWriteAdmissionBlockReason.SYNC_IN_PROGRESS,
                assertNotNull(queuedWrite.await()).reason,
            )
            assertTrue(radio.writes.isEmpty(), "The queued write cannot reach the radio after snapshot capture")

            releaseBatchSave.complete(Unit)
            snapshot.await()
            assertTrue(store.fetchContacts(radioA).isEmpty())
            assertFalse(service(connection = replacementSession).needsContactWriteReconciliation(radioA))

            val retry = async {
                service(connection = replacementSession).addOrUpdateContact(radioA, frame(2, "Retry"))
            }
            runCurrent()
            assertEquals(1, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Retry", store.fetchContact(radioA, contactsKey(2))?.name)
        }
    }

    @Test
    fun persistedRecoveryWithoutDispatchProvenanceDoesNotAutoReconcile() = runTest {
        withFixture {
            val restoredPreferences = ContactsFakePreferences().apply {
                set("contactWriteReconciliation.${radioA.value}", true)
            }
            val restored = service(flags = restoredPreferences)
            assertTrue(restored.needsContactWriteReconciliation(radioA))
            assertFailsWith<ContactServiceError.SyncInterrupted> { restored.syncContacts(radioA, null) }
            assertTrue(restored.needsContactWriteReconciliation(radioA))
            assertTrue(radio.contactRequests.isEmpty())
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

    private class HeldRadio(private val shared: SharedRadio = SharedRadio()) : MeshTransport {
        private val mock = MockTransport()
        val writes: MutableList<Bytes> get() = shared.writes
        val contactRequests = mutableListOf<Bytes>()
        var snapshotHold: SnapshotHold? = null
        var reportedTotal: Int?
            get() = shared.reportedTotal
            set(value) { shared.reportedTotal = value }

        fun replacementView() = HeldRadio(shared)

        override suspend fun connect() = mock.connect()
        // The radio-side pending queue intentionally survives link teardown.
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
                0x09 -> { shared.writes += data; shared.pending.addLast(PendingWrite(data, mock)) }
                0x04 -> {
                    contactRequests += data
                    snapshotHold?.let {
                        it.entered.complete(Unit)
                        it.release.await()
                    }
                    mock.simulateReceive(Bytes.of(0x02) + le32((shared.reportedTotal ?: shared.rows.size).toLong()))
                    shared.rows.values.forEach { mock.simulateReceive(Bytes.of(0x03) + it) }
                    mock.simulateReceive(Bytes.of(0x04) + le32(100))
                }
            }
        }

        suspend fun accept(reply: Boolean = true) {
            val pending = shared.pending.removeFirst()
            val command = pending.command
            val packet = command.slice(1, 144) + le32(100)
            val contact = assertNotNull(Parsers.parseContactData(packet))
            shared.rows[contact.publicKey] = packet
            if (reply) pending.owner.simulateReceive(Bytes.of(0x00))
        }

        suspend fun reject(code: Int) {
            shared.pending.removeFirst().owner.simulateReceive(Bytes.of(0x01, code))
        }

        suspend fun replyOK() = mock.simulateReceive(Bytes.of(0x00))

        fun contact(publicKey: Bytes) = shared.rows[publicKey]?.let(Parsers::parseContactData)

        class SnapshotHold(
            val entered: CompletableDeferred<Unit>,
            val release: CompletableDeferred<Unit>,
        )

        private class SharedRadio {
            val pending = ArrayDeque<PendingWrite>()
            val rows = linkedMapOf<Bytes, Bytes>()
            val writes = mutableListOf<Bytes>()
            var reportedTotal: Int? = null
        }

        private data class PendingWrite(val command: Bytes, val owner: MockTransport)
    }

    private companion object {
        fun le32(value: Long): Bytes = Bytes(ByteArray(4) { (value ushr (8 * it)).toByte() })
    }
}
