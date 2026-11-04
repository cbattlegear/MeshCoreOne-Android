// AndroidOnly: WP-209 Real-session cancellation, accepted-write accounting and replacement regressions.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.parser.Parsers
import com.meshcoreone.android.core.protocol.session.ChannelWriteCancellationDisposition
import com.meshcoreone.android.core.protocol.session.ChannelWriteDispatch
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatch
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactWriteRecoveryTests {
    private val radioA = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000209"))
    private val radioB = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000210"))

    @Test
    fun stoppedDuringPreviousFrameLookupIsUnsentAndReplacementCanProceed() = runTest {
        withFixture {
            val lookup = SuspensionGate()
            val paused = object : ContactPersisting by store {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    lookup.pause()
                    return store.fetchContact(radioId, publicKey)
                }
            }
            var originalFailure: MeshCoreException? = null
            var observedDispatch: ContactWriteDispatch? = null
            val observedSession = object : ContactSessionOps by session {
                override suspend fun addContact(contact: MeshContact, dispatch: ContactWriteDispatch, onCommitted: suspend () -> Unit) {
                    observedDispatch = dispatch
                    try {
                        session.addContact(contact, dispatch, onCommitted)
                    } catch (failure: MeshCoreException) {
                        originalFailure = failure
                        throw failure
                    }
                }
            }
            val flags = PendingMarkerFlags()
            val logs = mutableListOf<Pair<ContactLogLevel, String>>()
            val producer = service(
                connection = observedSession, persistence = paused, flags = flags,
                logger = ContactServiceLogger { level, message -> logs += level to message },
            )
            val result = async {
                assertFailsWith<ContactServiceError.SessionError> {
                    producer.addOrUpdateContact(radioA, frame(1, "Unsent"))
                }
            }
            runCurrent()
            assertTrue(lookup.entered.isCompleted)
            assertNull(observedDispatch, "The previous-frame read precedes write registration")
            session.stop()
            lookup.release.complete(Unit)
            runCurrent()
            val failure = result.await()
            assertIs<MeshCoreException.SessionNotStarted>(failure.error)
            assertSame(assertNotNull(originalFailure), failure.error)
            assertSame(failure.error, failure.cause)
            assertTrue(assertNotNull(observedDispatch).definitelyUnsent)
            assertTrue(radio.writes.isEmpty())
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(flags.attempts.isEmpty(), "A pre-generation failure must not create durable uncertainty")
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertFalse(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))
            assertTrue(logs.isEmpty())

            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio), flags = flags)
            assertEquals(0L, replacement.syncContacts(radioA).contactsReceived)
            val accepted = async { replacement.addOrUpdateContact(radioA, frame(2, "Replacement")) }
            runCurrent()
            assertEquals(1, replacementRadio.writes.size)
            replacementRadio.accept()
            runCurrent()
            accepted.await()
            assertEquals("Replacement", store.fetchContact(radioA, contactsKey(2))?.name)
            assertFalse(replacement.needsContactWriteReconciliation(radioA))
            assertTrue(flags.attempts.isEmpty())
        }
    }

    @Test
    fun stoppedLookupCannotEraseEarlierOnWireUncertainty() = runTest {
        withFixture {
            val flags = PendingMarkerFlags()
            val producer = service(flags = flags)
            val first = async { producer.addOrUpdateContact(radioA, frame(1, "On wire")) }
            runCurrent()
            assertEquals(1, radio.writes.size)
            val lookup = SuspensionGate()
            val paused = object : ContactPersisting by store {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    lookup.pause()
                    return store.fetchContact(radioId, publicKey)
                }
            }
            val unsent = async {
                assertFailsWith<ContactWriteAdmissionBlocked> {
                    service(persistence = paused, flags = flags).addOrUpdateContact(radioA, frame(2, "Unsent"))
                }
            }
            runCurrent()
            assertTrue(lookup.entered.isCompleted)
            first.cancelAndJoin()
            val epoch = assertNotNull(ContactWriteRecovery.snapshot(flags, radioA))
            session.stop()
            lookup.release.complete(Unit)
            runCurrent()
            assertEquals(ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED, unsent.await().reason)
            assertSame(epoch, ContactWriteRecovery.snapshot(flags, radioA))
            val marker = "contactWriteReconciliation.${radioA.value}"
            assertEquals(listOf(marker to true), flags.attempts)
            assertTrue(flags.durableView().bool(marker))
            assertNull(store.fetchContact(radioA, contactsKey(2)))
            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio), flags = flags)
            assertRecoveryRemainsBlocked(replacement)
            assertEquals(1, radio.writes.size)
            assertTrue(replacementRadio.contactRequests.isEmpty())
            assertEquals(listOf(marker to true), flags.attempts)
        }
    }

    @Test
    fun sessionNotStartedWithoutUnsentProofStillRequiresReconciliation() = runTest {
        withFixture {
            val failure = MeshCoreException.SessionNotStarted()
            val legacy = ContactsFakeSession().apply { addContactFailure = failure }
            val flags = PendingMarkerFlags()
            val producer = service(connection = legacy, flags = flags)
            val error = assertFailsWith<ContactServiceError.SessionError> {
                producer.addOrUpdateContact(radioA, frame(1, "Unknown"))
            }
            assertSame(failure, error.error)
            assertSame(failure, error.cause)
            assertEquals(1, legacy.addContactInvocations.size)
            assertEquals(listOf("contactWriteReconciliation.${radioA.value}" to true), flags.attempts)
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            assertRecoveryRemainsBlocked(service(flags = flags))
            assertTrue(radio.writes.isEmpty())
        }
    }

    @Test
    fun queuedRemovalCancellationIsUnsentAndPreservesAnyEarlierEpoch() = runTest {
        for (priorUncertainty in listOf(false, true)) {
            withFixture {
                val row = ContactDTO.fromFrame(radioA, frame(2, "Retained"))
                store.seed(row)
                val flags = PendingMarkerFlags()
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                var observedDispatch: ChannelWriteDispatch? = null
                val observedSession = object : ContactSessionOps by session {
                    override suspend fun removeContact(publicKey: Bytes, dispatch: ChannelWriteDispatch) {
                        observedDispatch = dispatch
                        session.removeContact(publicKey, dispatch)
                    }
                }
                val producer = service(
                    connection = observedSession, flags = flags, cleanup = cleanup, coordinator = coordinator,
                )
                val subscription = producer.events()
                val events = async { subscription.events.toList() }
                val blocking = async { session.addContact(frame(2, "Retained").toMeshContact()) }
                runCurrent()
                val removal = async { producer.removeContact(radioA, row.publicKey) }
                runCurrent()
                val dispatch = assertNotNull(observedDispatch, "Removal has entered the service mutation fence")
                assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
                assertTrue(radio.removeRequests.isEmpty())
                // A late uncertainty report may arrive after removal admission; it must survive.
                if (priorUncertainty) ContactWriteRecovery.require(flags, radioA)
                val epoch = ContactWriteRecovery.snapshot(flags, radioA)
                removal.cancelAndJoin()
                assertTrue(removal.isCancelled)
                assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, dispatch.cancellationDisposition)
                radio.accept()
                runCurrent()
                blocking.await()
                assertTrue(radio.removeRequests.isEmpty(), "Cancellation fences the queued removal permanently")
                assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
                assertTrue(store.deletedContactIDs.isEmpty())
                assertTrue(cleanup.invocations.isEmpty())
                assertTrue(coordinator.notifications.isEmpty())
                assertSame(epoch, ContactWriteRecovery.snapshot(flags, radioA))
                val marker = "contactWriteReconciliation.${radioA.value}"
                assertEquals(if (priorUncertainty) listOf(marker to true) else emptyList(), flags.attempts)
                assertEquals(priorUncertainty, flags.durableView().bool(marker))
                producer.finishEvents()
                assertEquals(emptyList(), events.await())

                session.stop()
                val replacementRadio = radio.replacementView()
                val replacement = service(newSession(replacementRadio), flags = flags)
                if (priorUncertainty) {
                    assertRecoveryRemainsBlocked(replacement)
                    assertTrue(replacementRadio.contactRequests.isEmpty())
                    assertSame(epoch, ContactWriteRecovery.snapshot(flags, radioA))
                } else {
                    assertEquals(1L, replacement.syncContacts(radioA).contactsReceived)
                    replacement.removeContact(radioA, row.publicKey)
                    assertEquals(1, replacementRadio.removeRequests.size)
                    assertNull(store.fetchContact(radioA, row.publicKey))
                    assertFalse(replacement.needsContactWriteReconciliation(radioA))
                    assertTrue(flags.attempts.isEmpty())
                }
            }
        }
    }

    @Test
    fun removalCancellationAfterTransportEntryStillBlocksReplacement() = runTest {
        withFixture {
            val row = ContactDTO.fromFrame(radioA, frame(2, "Retained"))
            store.seed(row)
            val flags = PendingMarkerFlags()
            val gate = HeldRadio.RemoveHold(CompletableDeferred(), CompletableDeferred())
            radio.removeHold = gate
            var observedDispatch: ChannelWriteDispatch? = null
            val observedSession = object : ContactSessionOps by session {
                override suspend fun removeContact(publicKey: Bytes, dispatch: ChannelWriteDispatch) {
                    observedDispatch = dispatch
                    session.removeContact(publicKey, dispatch)
                }
            }
            val producer = service(connection = observedSession, flags = flags)
            val removal = async { producer.removeContact(radioA, row.publicKey) }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertEquals(1, radio.removeRequests.size)
            removal.cancelAndJoin()
            runCurrent()
            assertEquals(
                ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN,
                assertNotNull(observedDispatch).cancellationDisposition,
            )
            assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
            assertTrue(store.deletedContactIDs.isEmpty())
            assertEquals(listOf("contactWriteReconciliation.${radioA.value}" to true), flags.attempts)
            assertTrue(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))
            session.stop()
            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio), flags = flags)
            assertRecoveryRemainsBlocked(replacement)
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.removeContact(radioA, row.publicKey)
            }
            assertTrue(replacementRadio.removeRequests.isEmpty())
            assertTrue(replacementRadio.contactRequests.isEmpty())
        }
    }

    @Test
    fun legacyRemovalCancellationWithoutDispatchProofStillMarksRecovery() = runTest {
        withFixture {
            val row = ContactDTO.fromFrame(radioA, frame(2, "Retained"))
            store.seed(row)
            val failure = CancellationException("Legacy removal outcome unknown")
            val legacy = ContactsFakeSession().apply { removeContactFailure = failure }
            val flags = PendingMarkerFlags()
            val producer = service(connection = legacy, flags = flags)
            assertSame(failure, assertFailsWith<CancellationException> {
                producer.removeContact(radioA, row.publicKey)
            })
            assertEquals(listOf(row.publicKey), legacy.removeContactPublicKeys)
            assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
            assertEquals(listOf("contactWriteReconciliation.${radioA.value}" to true), flags.attempts)
            assertRecoveryRemainsBlocked(service(flags = flags))
        }
    }

    @Test
    fun unresolvedRawReplyRefusesContactOperationsWithoutCreatingRecovery() = unresolvedReplyRefusesContactOperations(raw = true)

    @Test
    fun unresolvedBatteryErrorRefusesContactOperationsAfterAdmissionWithoutCreatingRecovery() =
        unresolvedReplyRefusesContactOperations(raw = false)

    private fun unresolvedReplyRefusesContactOperations(raw: Boolean) = runTest {
        for (remove in listOf(false, true)) {
            withFixture {
                val row = ContactDTO.fromFrame(radioA, frame(2, "Retained"))
                store.seed(row)
                val flags = PendingMarkerFlags()
                val logs = mutableListOf<Pair<ContactLogLevel, String>>()
                val producer = service(
                    flags = flags,
                    logger = ContactServiceLogger { level, message -> logs += level to message },
                )
                var addDispatch: ContactWriteDispatch? = null
                var removeDispatch: ChannelWriteDispatch? = null
                val observedSession = object : ContactSessionOps by session {
                    override suspend fun addContact(contact: MeshContact, dispatch: ContactWriteDispatch, onCommitted: suspend () -> Unit) {
                        addDispatch = dispatch
                        session.addContact(contact, dispatch, onCommitted)
                    }
                    override suspend fun removeContact(publicKey: Bytes, dispatch: ChannelWriteDispatch) {
                        removeDispatch = dispatch
                        session.removeContact(publicKey, dispatch)
                    }
                }
                val observedProducer = service(
                    connection = observedSession, flags = flags,
                    logger = ContactServiceLogger { level, message -> logs += level to message },
                )
                val timedOut = async {
                    assertFailsWith<MeshCoreException.Timeout> {
                        if (raw) session.sendAndWait<Unit>(Bytes.of(0x14), timeout = 0.1) { null }
                        else session.getBattery()
                    }
                }
                runCurrent()
                advanceTimeBy(if (raw) 101 else 5_001)
                runCurrent()
                timedOut.await()
                repeat(2) {
                    val failure = assertFailsWith<ContactServiceError.SessionError> {
                        if (remove) observedProducer.removeContact(radioA, row.publicKey)
                        else observedProducer.addOrUpdateContact(radioA, frame(3, "Must not send"))
                    }
                    assertIs<MeshCoreException.ConnectionLost>(failure.error)
                    assertSame(failure.error, failure.cause)
                    if (remove) {
                        assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, assertNotNull(removeDispatch).cancellationDisposition)
                    } else {
                        val dispatch = assertNotNull(addDispatch)
                        assertTrue(dispatch.definitelyUnsent)
                        if (!raw) assertIs<MeshCoreException.ConnectionLost>(dispatch.terminalFailure)
                    }
                }
                assertTrue(radio.writes.isEmpty())
                assertTrue(radio.removeRequests.isEmpty())
                assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
                assertNull(store.fetchContact(radioA, contactsKey(3)))
                assertTrue(store.deletedContactIDs.isEmpty())
                assertTrue(flags.attempts.isEmpty())
                assertFalse(producer.needsContactWriteReconciliation(radioA))
                assertTrue(logs.isEmpty())

                session.stop()
                val replacementRadio = radio.replacementView()
                val replacement = service(newSession(replacementRadio), flags = flags)
                assertEquals(0L, replacement.syncContacts(radioA, Instant.ofEpochSecond(50)).contactsReceived)
                if (remove) {
                    replacement.removeContact(radioA, row.publicKey)
                    assertEquals(1, replacementRadio.removeRequests.size)
                    assertNull(store.fetchContact(radioA, row.publicKey))
                } else {
                    val accepted = async { replacement.addOrUpdateContact(radioA, frame(3, "Replacement")) }
                    runCurrent()
                    assertEquals(1, replacementRadio.writes.size)
                    replacementRadio.accept()
                    runCurrent()
                    accepted.await()
                    assertEquals("Replacement", store.fetchContact(radioA, contactsKey(3))?.name)
                }
                assertFalse(replacement.needsContactWriteReconciliation(radioA))
                assertTrue(flags.attempts.isEmpty())
            }
        }
    }

    @Test
    fun twoQueuedAddsRetainSerializerOrderAfterTheActiveExchangeSucceeds() = runTest {
        withFixture {
            val producer = service()
            val active = async { session.addContact(frame(1, "Raw").toMeshContact()) }
            runCurrent()
            val first = async { producer.addOrUpdateContact(radioA, frame(2, "First queued")) }
            val second = async { producer.addOrUpdateContact(radioA, frame(3, "Second queued")) }
            runCurrent()
            assertEquals(1, radio.writes.size)
            radio.accept()
            runCurrent()
            active.await()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            first.await()
            assertEquals(3, radio.writes.size)
            radio.accept()
            runCurrent()
            second.await()
            assertEquals("First queued", store.fetchContact(radioA, contactsKey(2))?.name)
            assertEquals("Second queued", store.fetchContact(radioA, contactsKey(3))?.name)
            assertFalse(producer.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun cancellationQueuedBehindAnActualExchangeIsDefinitelyUnsent() = runTest {
        withFixture {
            val blocking = async { session.addContact(frame(1, "Blocking").toMeshContact()) }
            runCurrent()
            val queued = async { service().addOrUpdateContact(radioA, frame(2, "Unsent")) }
            runCurrent()
            ContactWriteRecovery.require(preferences, radioA)
            val producer = service()
            val unrelatedState = producer.contactWriteRecoveryState(radioA)
            assertEquals(ContactWriteRecoveryReason.OPERATION_OUTCOME_UNAVAILABLE,
                assertIs<ContactWriteRecoveryState.Required>(unrelatedState).reason)
            queued.cancelAndJoin()
            assertEquals(unrelatedState, producer.contactWriteRecoveryState(radioA))
            radio.accept()
            runCurrent()
            blocking.await()
            assertEquals(1, radio.writes.size)
            assertNull(store.fetchContact(radioA, contactsKey(2)))
            assertTrue(service().needsContactWriteReconciliation(radioA))
            assertRecoveryRemainsBlocked(service())
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
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertFalse(producer.needsContactWriteReconciliation(radioB))

            val retry = async { producer.addOrUpdateContact(radioA, frame(2, "Retry")) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Retry", store.fetchContact(radioA, contactsKey(2))?.name)
        }
    }

    @Test
    fun queuedSameRadioWriteResumesAfterExactCancelledWriteCompletion() = runTest {
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

            assertEquals(2, radio.writes.size, "Exact ACK and local completion release the serialized retry")
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            assertNull(store.fetchContact(radioA, contactsKey(2)))
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            radio.accept()
            runCurrent()
            assertNull(queued.await())
            assertEquals("Queued", store.fetchContact(radioA, contactsKey(2))?.name)
            assertFalse(producer.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun lateCallerCancellationCannotRecreateCompletedOrRejectedObligations() = runTest {
        for (accepted in listOf(true, false)) withFixture {
            val reporting = SuspensionGate()
            val delayed = object : ContactSessionOps by session {
                override suspend fun addContact(
                    contact: MeshContact,
                    dispatch: ContactWriteDispatch,
                    onCommitted: suspend () -> Unit,
                ) {
                    try {
                        session.addContact(contact, dispatch, onCommitted)
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { reporting.pause() }
                        throw cancelled
                    }
                }
            }
            val flags = PendingMarkerFlags()
            val producer = service(connection = delayed, flags = flags)
            val write = async { producer.addOrUpdateContact(radioA, frame(1, "Late report")) }
            runCurrent()
            write.cancel()
            runCurrent()
            assertTrue(reporting.entered.isCompleted)
            if (accepted) radio.accept() else radio.reject(2)
            runCurrent()
            assertEquals(accepted, store.fetchContact(radioA, contactsKey(1)) != null)
            reporting.release.complete(Unit)
            write.join()
            assertTrue(write.isCancelled)
            assertEquals(ContactWriteRecoveryState.Clear(radioA), producer.contactWriteRecoveryState(radioA))
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertTrue(flags.attempts.isEmpty(), "A delayed cancellation cannot undo terminal/local proof")
        }
    }

    @Test
    fun subscriberRetryBeforeFailureCallbackCannotReregisterSettledDispatch() = runTest {
        withFixture {
            val flags = PendingMarkerFlags()
            val producer = service(flags = flags)
            var retried = false
            val observed = mutableListOf<ContactWriteRecoveryState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                producer.observeContactWriteRecoveryState(radioA).collect { value ->
                    observed += value
                    if (value is ContactWriteRecoveryState.Required &&
                        value.markerRecording == ContactWriteMarkerRecording.CLEAR_RETRY_PENDING
                    ) {
                        flags.failure = null
                        assertFalse(producer.needsContactWriteReconciliation(radioA))
                        retried = true
                    }
                }
            }
            val write = async { producer.addOrUpdateContact(radioA, frame(1, "Accepted")) }
            runCurrent()
            write.cancelAndJoin()
            flags.failure = IOException("false-marker commit failed")
            radio.accept()
            runCurrent()
            assertTrue(retried, "Retry ran from subscriber delivery before finishWrite's failure callback")
            assertEquals(ContactWriteRecoveryState.Clear(radioA), observed.last())
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertEquals(
                listOf(true, false, false),
                flags.attempts.map { it.second },
                "The delayed callback cannot record a new true marker",
            )
            collector.cancelAndJoin()
        }
    }

    @Test
    fun uncertaintySurvivesPreferenceReadFailureWithoutLosingStoredProvenance() = runTest {
        for (stored in listOf(false, true)) withFixture {
            val readFailure = IOException("marker read unavailable after dispatch")
            var failReads = false
            val flags = object : ContactPreferenceFlags by preferences {
                override fun bool(key: String): Boolean {
                    if (failReads) throw readFailure
                    return preferences.bool(key)
                }
            }
            val producer = service(flags = flags)
            val write = async { producer.addOrUpdateContact(radioA, frame(1, "Unanswered")) }
            runCurrent()
            if (stored) preferences.set("contactWriteReconciliation.${radioA.value}", true)
            failReads = true
            write.cancelAndJoin()
            val required = assertIs<ContactWriteRecoveryState.Required>(
                producer.contactWriteRecoveryState(radioA),
            )
            assertEquals(ContactWriteMarkerRecording.RETRY_PENDING, required.markerRecording)
            assertSame(readFailure, assertFailsWith<IOException> {
                producer.needsContactWriteReconciliation(radioA)
            })
            failReads = false
            assertRecoveryRemainsBlocked(service(flags = flags))
            assertTrue(preferences.bool("contactWriteReconciliation.${radioA.value}"))
            radio.accept()
            runCurrent()
            assertEquals(stored, producer.needsContactWriteReconciliation(radioA))
            assertEquals(stored, preferences.bool("contactWriteReconciliation.${radioA.value}"))
            if (stored) assertEquals(
                ContactWriteRecoveryReason.STORED_MARKER_WITHOUT_PROVENANCE,
                assertIs<ContactWriteRecoveryState.Required>(producer.contactWriteRecoveryState(radioA)).reason,
            )
        }
    }

    @Test
    fun settlingFirstWriteRetainsQueuedWriteAcrossHeldTailAndReplacementGraph() = runTest {
        withFixture {
            val tail = SuspensionGate()
            val persistence = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId,
                    frame: ContactFrame,
                    expectedFrame: ContactFrame?,
                ): ContactSaveResult? {
                    if (frame.publicKey == contactsKey(2)) tail.pause()
                    return store.saveContactPreservingNewer(radioId, frame, expectedFrame)
                }
            }
            val producer = service(persistence = persistence)
            val first = async { producer.addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            val queued = async { producer.addOrUpdateContact(radioA, frame(2, "Queued")) }
            runCurrent()
            first.cancelAndJoin()
            radio.accept()
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            assertTrue(tail.entered.isCompleted)
            queued.cancelAndJoin()
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio))
            assertRecoveryRemainsBlocked(replacement)
            assertTrue(replacementRadio.contactRequests.isEmpty())
            tail.release.complete(Unit)
            runCurrent()
            assertEquals("Queued", store.fetchContact(radioA, contactsKey(2))?.name)
            assertFalse(replacement.needsContactWriteReconciliation(radioA))
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

            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertEquals(1, radio.writes.size)
            assertEquals("Old", store.fetchContact(radioA, contactsKey(1))?.name)

            val retry = async { producer.addOrUpdateContact(radioA, frame(3, "Retry")) }
            runCurrent()
            assertEquals(2, radio.writes.size, "A fresh write may follow the exact completed ACK")
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Retry", store.fetchContact(radioA, contactsKey(3))?.name)
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
    fun lateRejectionAfterCancellationSettlesItsOwnRecoveryWithoutInventingPersistence() = runTest {
        withFixture {
            val producer = service()
            val observed = mutableListOf<ContactWriteRecoveryState>()
            val collector = backgroundScope.launch {
                producer.observeContactWriteRecoveryState(radioA).collect { observed += it }
            }
            runCurrent()
            val result = async { producer.addOrUpdateContact(radioA, frame(1, "Rejected")) }
            runCurrent()
            result.cancelAndJoin()
            runCurrent()
            assertEquals(
                ContactWriteRecoveryReason.RADIO_OUTCOME_UNCERTAIN,
                assertIs<ContactWriteRecoveryState.Required>(observed.last()).reason,
            )
            radio.reject(3)
            runCurrent()
            assertTrue(observed.any {
                it is ContactWriteRecoveryState.Required &&
                    it.reason == ContactWriteRecoveryReason.RADIO_REJECTION_SETTLEMENT_PENDING
            })
            assertIs<ContactWriteRecoveryState.Clear>(observed.last())
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertTrue(store.fetchContacts(radioA).isEmpty())
            val retry = async { producer.addOrUpdateContact(radioA, frame(1, "Retry after rejection")) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Retry after rejection", store.fetchContact(radioA, contactsKey(1))?.name)
            assertTrue(radio.contactRequests.isEmpty())
            collector.cancelAndJoin()
        }
    }

    @Test
    fun recoveryFlowTracksLateAcceptanceAcrossHeldTailAndReplacementGraph() = runTest {
        withFixture {
            val saveGate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val flags = PendingMarkerFlags()
            val held = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult? {
                    entered.complete(Unit)
                    saveGate.await()
                    return store.saveContactPreservingNewer(radioId, frame, expectedFrame)
                }
            }
            val producer = service(persistence = held, flags = flags)
            val originalStates = mutableListOf<ContactWriteRecoveryState>()
            val originalCollector = backgroundScope.launch {
                producer.observeContactWriteRecoveryState(radioA).collect { originalStates += it }
            }
            runCurrent()
            assertEquals(ContactWriteRecoveryState.Clear(radioA), originalStates.single())

            val first = async { producer.addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            first.cancelAndJoin()
            runCurrent()
            val recorded = assertIs<ContactWriteRecoveryState.Required>(originalStates.last())
            assertEquals(ContactWriteRecoveryReason.RADIO_OUTCOME_UNCERTAIN, recorded.reason)
            assertEquals(ContactWriteMarkerRecording.SET_SUCCEEDED, recorded.markerRecording)

            producer.finishEvents()
            val replacement = service(persistence = held, flags = flags)
            val replacementStates = mutableListOf<ContactWriteRecoveryState>()
            val replacementCollector = backgroundScope.launch {
                replacement.observeContactWriteRecoveryState(radioA).collect { replacementStates += it }
            }
            runCurrent()
            assertEquals(originalStates.last(), replacementStates.single())

            val extraStates = mutableListOf<ContactWriteRecoveryState>()
            val extraCollector = backgroundScope.launch {
                replacement.observeContactWriteRecoveryState(radioA).collect { extraStates += it }
            }
            runCurrent()
            assertEquals(replacementStates.single(), extraStates.single())
            extraCollector.cancelAndJoin()
            assertTrue(replacement.needsContactWriteReconciliation(radioA))

            radio.accept()
            runCurrent()
            assertTrue(entered.isCompleted)
            for (states in listOf(originalStates, replacementStates)) {
                val heldTail = assertIs<ContactWriteRecoveryState.Required>(states.last())
                assertEquals(ContactWriteRecoveryReason.RADIO_ACCEPTED_RECONCILIATION_REQUIRED, heldTail.reason)
                assertEquals(ContactWriteLocalCompletion.PENDING, heldTail.localCompletion)
            }

            saveGate.complete(Unit)
            runCurrent()
            for (states in listOf(originalStates, replacementStates)) {
                assertIs<ContactWriteRecoveryState.Clear>(states.last())
                assertTrue(states.any {
                    it is ContactWriteRecoveryState.Required &&
                        it.reason == ContactWriteRecoveryReason.RADIO_ACCEPTED_RECONCILIATION_REQUIRED &&
                        it.localCompletion == ContactWriteLocalCompletion.COMPLETE
                })
            }
            assertEquals(
                ContactWriteRecoveryReason.RADIO_OUTCOME_UNCERTAIN,
                assertIs<ContactWriteRecoveryState.Required>(extraStates.last()).reason,
                "Unsubscribing one holder neither clears state nor ends other subscriptions",
            )
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            assertFalse(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))
            originalCollector.cancelAndJoin()
            replacementCollector.cancelAndJoin()
        }
    }

    @Test
    fun recoveryObservationDoesNotRetryFailedMarkerWrites() = runTest {
        withFixture {
            val initialFailure = IOException("marker write failed")
            val flags = PendingMarkerFlags().apply { failure = initialFailure }
            val producer = service(flags = flags)
            val observed = mutableListOf<ContactWriteRecoveryState>()
            val collector = backgroundScope.launch {
                producer.observeContactWriteRecoveryState(radioA).collect { observed += it }
            }
            runCurrent()

            val first = async { producer.addOrUpdateContact(radioA, frame(1, "Retry marker")) }
            runCurrent()
            first.cancelAndJoin()
            runCurrent()
            val pendingMarker = assertIs<ContactWriteRecoveryState.Required>(observed.last())
            assertEquals(ContactWriteMarkerRecording.RETRY_PENDING, pendingMarker.markerRecording)
            assertEquals(
                listOf("contactWriteReconciliation.${radioA.value}" to true),
                flags.attempts,
            )
            assertEquals(pendingMarker, producer.contactWriteRecoveryState(radioA))
            assertEquals(1, flags.attempts.size, "A snapshot must not retry a failed true-marker write")

            flags.failure = null
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            runCurrent()
            assertEquals(
                ContactWriteMarkerRecording.SET_SUCCEEDED,
                assertIs<ContactWriteRecoveryState.Required>(observed.last()).markerRecording,
            )

            val clearFailure = IOException("marker clear failed")
            flags.failure = clearFailure
            radio.accept()
            runCurrent()
            val pendingClear = assertIs<ContactWriteRecoveryState.Required>(observed.last())
            assertEquals(ContactWriteRecoveryReason.MARKER_CLEAR_PENDING, pendingClear.reason)
            assertEquals(ContactWriteMarkerRecording.CLEAR_RETRY_PENDING, pendingClear.markerRecording)
            val attemptsBeforeObservation = flags.attempts.size
            assertEquals(pendingClear, producer.contactWriteRecoveryState(radioA))
            assertEquals(attemptsBeforeObservation, flags.attempts.size, "Observation must not retry a failed false-marker write")

            flags.failure = null
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            runCurrent()
            assertIs<ContactWriteRecoveryState.Clear>(observed.last())
            collector.cancelAndJoin()
        }
    }

    @Test
    fun persistedUnknownMarkerIsReadOnlyAndPreferenceReadFailuresPropagate() = runTest {
        withFixture {
            val flags = PendingMarkerFlags()
            flags.set("contactWriteReconciliation.${radioA.value}", true)
            val attemptsBeforeObservation = flags.attempts.toList()
            val producer = service(flags = flags)
            val stored = assertIs<ContactWriteRecoveryState.Required>(
                producer.contactWriteRecoveryState(radioA),
            )
            assertEquals(ContactWriteRecoveryReason.STORED_MARKER_WITHOUT_PROVENANCE, stored.reason)
            assertEquals(ContactWriteMarkerRecording.LOADED_FLAG_UNVERIFIED, stored.markerRecording)
            assertEquals(attemptsBeforeObservation, flags.attempts)

            val observed = mutableListOf<ContactWriteRecoveryState>()
            val collector = backgroundScope.launch {
                producer.observeContactWriteRecoveryState(radioA).collect { observed += it }
            }
            runCurrent()
            assertEquals(stored, observed.single())
            collector.cancelAndJoin()

            val readFailure = IOException("preference read failed")
            val unreadable = object : ContactPreferenceFlags {
                override fun bool(key: String): Boolean = throw readFailure
                override fun set(key: String, value: Boolean): Unit = error("No write expected")
            }
            val unreadableService = service(flags = unreadable)
            assertSame(readFailure, assertFailsWith<IOException> {
                unreadableService.contactWriteRecoveryState(radioB)
            })
            var flowFailure: IOException? = null
            try {
                unreadableService.observeContactWriteRecoveryState(radioB).collect {}
            } catch (failure: IOException) {
                flowFailure = failure
            }
            assertSame(readFailure, flowFailure)
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
    fun localSaveFailureReportsRadioAcceptanceButUnprovedSyncCannotRepairIt() = runTest {
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
            assertRecoveryRemainsBlocked(replacement)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(replacementRadio.contactRequests.isEmpty())
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
            val replacement = service()
            assertTrue(replacement.needsContactWriteReconciliation(radioA))
            val recovery = assertIs<ContactWriteRecoveryState.Required>(
                replacement.contactWriteRecoveryState(radioA),
            )
            assertEquals(ContactWriteRecoveryReason.RADIO_ACCEPTED_RECONCILIATION_REQUIRED, recovery.reason)
            assertEquals(ContactWriteLocalCompletion.PENDING, recovery.localCompletion)
            assertTrue(diagnostics.any { it is com.meshcoreone.android.core.protocol.session.SessionDiagnostic.BackgroundFailure })
            assertRecoveryRemainsBlocked(replacement)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
        }
    }

    @Test
    fun cancelledAcceptedPersistenceTailBlocksUntilExactLocalCompletionThenAllowsRetry() = runTest {
        withFixture {
            val saveGate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val flags = PendingMarkerFlags()
            val held = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult? {
                    entered.complete(Unit)
                    saveGate.await()
                    return store.saveContactPreservingNewer(radioId, frame, expectedFrame)
                }
            }
            val first = async { service(persistence = held, flags = flags).addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            radio.accept()
            runCurrent()
            assertTrue(entered.isCompleted)
            first.cancelAndJoin()
            val second = async {
                try {
                    service(flags = flags).addOrUpdateContact(radioA, frame(1, "Second", 2u))
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
                service(flags = flags).syncContacts(radioA, null)
            }
            assertTrue(radio.contactRequests.isEmpty(), "A sync cannot start during the accepted write's local tail")
            assertTrue(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))
            saveGate.complete(Unit)
            runCurrent()
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            assertFalse(service(flags = flags).needsContactWriteReconciliation(radioA))
            assertEquals(listOf(
                "contactWriteReconciliation.${radioA.value}" to true,
                "contactWriteReconciliation.${radioA.value}" to false,
            ), flags.attempts)
            assertFalse(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))

            val retry = async { service(flags = flags).addOrUpdateContact(radioA, frame(1, "Second", 2u)) }
            runCurrent()
            assertEquals(2, radio.writes.size)
            radio.accept()
            runCurrent()
            retry.await()
            assertEquals("Second", store.fetchContact(radioA, contactsKey(1))?.name)
        }
    }

    @Test
    fun exactCompletionDoesNotClearAConcurrentNewerOrUnownedRecoveryEpoch() = runTest {
        for (newerDispatch in listOf<ContactWriteDispatch?>(null, ContactWriteDispatch())) {
            withFixture {
                val saveGate = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val flags = PendingMarkerFlags()
                val held = object : ContactPersisting by store {
                    override suspend fun saveContactPreservingNewer(
                        radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                    ): ContactSaveResult? {
                        entered.complete(Unit)
                        saveGate.await()
                        return store.saveContactPreservingNewer(radioId, frame, expectedFrame)
                    }
                }
                val first = async {
                    service(persistence = held, flags = flags).addOrUpdateContact(radioA, frame(1, "First"))
                }
                runCurrent()
                radio.accept()
                runCurrent()
                assertTrue(entered.isCompleted)
                first.cancelAndJoin()
                assertTrue(service(flags = flags).needsContactWriteReconciliation(radioA))
                val priorEpoch = ContactWriteRecovery.snapshot(flags, radioA)

                ContactWriteRecovery.require(flags, radioA, newerDispatch)
                val newerEpoch = ContactWriteRecovery.snapshot(flags, radioA)
                assertNotNull(newerEpoch)
                assertNotSame(priorEpoch, newerEpoch, "The newer marker advances the recovery epoch")
                saveGate.complete(Unit)
                runCurrent()

                assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
                assertSame(newerEpoch, ContactWriteRecovery.snapshot(flags, radioA))
                val replacement = service(flags = flags)
                assertTrue(replacement.needsContactWriteReconciliation(radioA))
                val observed = assertIs<ContactWriteRecoveryState.Required>(
                    replacement.contactWriteRecoveryState(radioA),
                )
                assertEquals(
                    if (newerDispatch == null) ContactWriteRecoveryReason.OPERATION_OUTCOME_UNAVAILABLE
                    else ContactWriteRecoveryReason.RADIO_OUTCOME_UNCERTAIN,
                    observed.reason,
                )
                assertEquals(ContactWriteLocalCompletion.PARTIAL, observed.localCompletion)
                assertEquals(
                    listOf(
                        "contactWriteReconciliation.${radioA.value}" to true,
                        "contactWriteReconciliation.${radioA.value}" to true,
                    ),
                    flags.attempts,
                )
                assertRecoveryRemainsBlocked(service(flags = flags))
                assertEquals(1, radio.writes.size)
            }
        }
    }

    @Test
    fun failedDurableClearRetainsRecoveryUntilTheFalseMarkerCanBeCommitted() = runTest {
        withFixture {
            val flags = PendingMarkerFlags()
            val producer = service(flags = flags)
            val first = async { producer.addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            first.cancelAndJoin()
            assertTrue(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))

            val clearFailure = IOException("recovery marker clear failed")
            flags.failure = clearFailure
            radio.accept()
            runCurrent()
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            assertTrue(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))
            assertTrue(flags.attempts.contains("contactWriteReconciliation.${radioA.value}" to false))
            assertSame(clearFailure, assertFailsWith<IOException> {
                ContactWriteRecovery.snapshot(flags, radioA)
            })

            flags.failure = null
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertFalse(flags.durableView().bool("contactWriteReconciliation.${radioA.value}"))
            assertEquals(
                "contactWriteReconciliation.${radioA.value}" to false,
                flags.attempts.last(),
            )
            assertEquals(1, radio.writes.size)
        }
    }

    @Test
    fun greaterAndEqualTimestampReplacementMetadataSurvivesOlderCompletion() = runTest {
        for (modified in listOf(1u, 2u)) {
            withFixture {
                val producer = service()
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
                assertRecoveryRemainsBlocked(producer)
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
            val markerAttempts = mutableListOf<Boolean>()
            val flags = object : ContactPreferenceFlags by preferences {
                override fun set(key: String, value: Boolean) {
                    if (key.startsWith("contactWriteReconciliation.")) markerAttempts += value
                    if (failCommits) throw diskFailure
                    preferences.set(key, value)
                }
            }
            val logs = mutableListOf<Pair<ContactLogLevel, String>>()
            val producer = ContactService(
                session, store, store, null, null, flags,
                logger = ContactServiceLogger { level, message -> logs += level to message },
            )
            val write = async { producer.addOrUpdateContact(radioA, frame(1, "Accepted")) }
            runCurrent()
            assertEquals(1, radio.writes.size)
            write.cancelAndJoin()
            assertTrue(write.isCancelled)
            assertEquals(listOf(true), markerAttempts)
            assertFalse(preferences.bool("contactWriteReconciliation.${radioA.value}"))
            assertSame(diskFailure, assertFailsWith<IOException> {
                producer.needsContactWriteReconciliation(radioA)
            })
            session.stop()
            runCurrent()
            assertEquals(listOf(true, true, true), markerAttempts, "Snapshot and unknown terminal callback retry failed recording")
            assertFalse(preferences.bool("contactWriteReconciliation.${radioA.value}"))
            assertEquals(2, logs.count { it == (ContactLogLevel.WARNING to "Contact recovery marker could not be persisted") })
            val replacement = service(flags = flags)
            assertSame(diskFailure, assertFailsWith<IOException> { replacement.syncContacts(radioA, null) })
            failCommits = false
            assertRecoveryRemainsBlocked(replacement)
            assertEquals(List(5) { true }, markerAttempts, "Guarded access records the marker but never clears it")
            assertTrue(preferences.bool("contactWriteReconciliation.${radioA.value}"))
        }
    }

    @Test
    fun lateTerminalCallbackRecordsTrueBeforeClearingSettledMarkerAfterStorageRecovers() = runTest {
        withFixture {
            val diskFailure = IOException("first true-marker commit failed")
            var failCommits = true
            val markerAttempts = mutableListOf<Boolean>()
            val flags = object : ContactPreferenceFlags by preferences {
                override fun set(key: String, value: Boolean) {
                    if (key.startsWith("contactWriteReconciliation.")) markerAttempts += value
                    if (failCommits) throw diskFailure
                    preferences.set(key, value)
                }
            }
            val freshViewBeforeCommit = object : ContactPreferenceFlags by preferences {}
            val logs = mutableListOf<Pair<ContactLogLevel, String>>()
            val producer = ContactService(
                session, store, store, null, null, flags,
                logger = ContactServiceLogger { level, message -> logs += level to message },
            )
            val write = async { producer.addOrUpdateContact(radioA, frame(1, "Accepted after recovery")) }
            runCurrent()
            write.cancelAndJoin()
            assertEquals(listOf(true), markerAttempts)
            assertFalse(freshViewBeforeCommit.bool("contactWriteReconciliation.${radioA.value}"))
            assertSame(diskFailure, assertFailsWith<IOException> {
                producer.needsContactWriteReconciliation(radioA)
            })
            assertEquals(1, logs.count { it == (ContactLogLevel.WARNING to "Contact recovery marker could not be persisted") })

            failCommits = false
            radio.accept()
            runCurrent()
            assertEquals(listOf(true, true, true, false), markerAttempts)
            val freshViewAfterCommit = object : ContactPreferenceFlags by preferences {}
            assertFalse(freshViewAfterCommit.bool("contactWriteReconciliation.${radioA.value}"))
            assertEquals("Accepted after recovery", store.fetchContact(radioA, contactsKey(1))?.name)
            assertTrue(write.isCancelled)
            assertEquals(1, logs.count { it == (ContactLogLevel.WARNING to "Contact recovery marker could not be persisted") })
            assertFalse(service(flags = flags).needsContactWriteReconciliation(radioA))
            assertFalse(service(flags = freshViewAfterCommit).needsContactWriteReconciliation(radioA))
            assertEquals(listOf(true, true, true, false), markerAttempts)
            assertEquals(1, radio.writes.size)
            assertTrue(radio.contactRequests.isEmpty())
        }
    }

    @Test
    fun failedMarkerReportsRetryUntilSuccessThenDeduplicateTheSameDispatch() = runTest {
        withFixture {
            val diskFailure = IOException("true-marker commit failed")
            var failCommits = true
            val markerAttempts = mutableListOf<Boolean>()
            val flags = object : ContactPreferenceFlags by preferences {
                override fun set(key: String, value: Boolean) {
                    markerAttempts += value
                    if (failCommits) throw diskFailure
                    preferences.set(key, value)
                }
            }
            val dispatch = ContactWriteDispatch()
            repeat(2) {
                assertSame(diskFailure, assertFailsWith<IOException> {
                    ContactWriteRecovery.require(flags, radioA, dispatch)
                })
                assertSame(diskFailure, assertFailsWith<IOException> {
                    service(flags = flags).needsContactWriteReconciliation(radioA)
                })
                assertFalse(preferences.bool("contactWriteReconciliation.${radioA.value}"))
            }
            assertEquals(List(4) { true }, markerAttempts)
            failCommits = false
            ContactWriteRecovery.require(flags, radioA, dispatch)
            val recordedEpoch = assertNotNull(ContactWriteRecovery.snapshot(flags, radioA))
            repeat(2) { ContactWriteRecovery.require(flags, radioA, dispatch) }
            assertSame(recordedEpoch, ContactWriteRecovery.snapshot(flags, radioA))
            assertEquals(List(5) { true }, markerAttempts, "Only successful recording deduplicates reports")
            val freshView = object : ContactPreferenceFlags by preferences {}
            assertTrue(freshView.bool("contactWriteReconciliation.${radioA.value}"))
            assertRecoveryRemainsBlocked(service(flags = flags))
            assertRecoveryRemainsBlocked(service(flags = freshView))
            assertEquals(List(5) { true }, markerAttempts)
            assertTrue(radio.writes.isEmpty())
            assertTrue(radio.contactRequests.isEmpty())
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
    fun ordinaryFullSyncFenceBlocksWritesUntilSnapshotApplicationFinishes() = runTest {
        withFixture {
            val old = async { service().addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            radio.accept()
            runCurrent()
            old.await()
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
    fun removeContactWaitsForAnOlderSnapshotThenDeletesThePersistedRow() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            val snapshotPersistence = PausedContactPersistence(store)
            val snapshotGate = SuspensionGate()
            snapshotPersistence.batchSaveGate = snapshotGate
            val snapshotService = service(persistence = snapshotPersistence)
            val snapshot = async { snapshotService.syncContacts(radioA, null) }
            runCurrent()
            assertTrue(snapshotGate.entered.isCompleted, "The complete radio response has reached batch persistence")
            assertEquals(1, radio.completedSnapshots)

            val removal = async { producer.removeContact(radioA, contactsKey(1)) }
            runCurrent()
            assertTrue(radio.removeRequests.isEmpty(), "Removal waits after protocol completion until snapshot persistence")
            assertNotNull(store.fetchContact(radioA, contactsKey(1)))

            snapshotGate.release.complete(Unit)
            runCurrent()
            snapshot.await()
            removal.await()

            assertEquals(1, radio.removeRequests.size)
            assertNull(radio.contact(contactsKey(1)))
            assertNull(store.fetchContact(radioA, contactsKey(1)))
        }
    }

    @Test
    fun snapshotWaitsForDeviceAndLocalRemovalBeforeFetching() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            val deletePersistence = PausedContactPersistence(store)
            val deleteGate = SuspensionGate()
            deletePersistence.deleteGate = deleteGate
            val deleteService = service(persistence = deletePersistence)
            val removal = async { deleteService.removeContact(radioA, contactsKey(1)) }
            runCurrent()
            assertTrue(deleteGate.entered.isCompleted, "The device removal ACK completed before local deletion")
            assertEquals(1, radio.completedRemovals)

            val snapshot = async { deleteService.syncContacts(radioA, null) }
            runCurrent()
            assertTrue(radio.contactRequests.isEmpty(), "The snapshot cannot start during the removal transaction")
            assertNotNull(store.fetchContact(radioA, contactsKey(1)))

            deleteGate.release.complete(Unit)
            runCurrent()
            removal.await()
            snapshot.await()

            assertEquals(1, radio.contactRequests.size)
            assertNull(radio.contact(contactsKey(1)))
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertEquals(1, radio.completedSnapshots)
        }
    }

    @Test
    fun localOnlyRemovalWaitsForAnOlderSnapshotApplication() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()
            val contact = assertNotNull(store.fetchContact(radioA, contactsKey(1)))

            val snapshotPersistence = PausedContactPersistence(store)
            val snapshotGate = SuspensionGate()
            snapshotPersistence.batchSaveGate = snapshotGate
            val snapshotService = service(persistence = snapshotPersistence)
            val snapshot = async { snapshotService.syncContacts(radioA, null) }
            runCurrent()
            assertTrue(snapshotGate.entered.isCompleted)

            val removal = async { producer.removeLocalContact(EntityKey(radioA, contact.id), contact.publicKey) }
            runCurrent()
            assertFalse(removal.isCompleted)
            assertEquals(0, radio.removeRequests.size, "Local-only recovery never sends another device removal")

            snapshotGate.release.complete(Unit)
            runCurrent()
            snapshot.await()
            removal.await()

            assertNull(store.fetchContact(radioA, contact.publicKey))
            assertEquals(0, radio.removeRequests.size)
        }
    }

    @Test
    fun protectedVContactRemovalsDoNotRejectOrInvalidateActiveAndQueuedWrites() = runTest {
        withFixture {
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            store.seed(ContactDTO(radioId = radioA, publicKey = vKey, name = "V-contact", lastHeardTimestamp = null))
            val producer = service()
            val events = producer.events()
            val collectedEvents = async { events.events.toList() }

            val active = async { producer.addOrUpdateContact(radioA, frame(2, "Active")) }
            runCurrent()
            val queued = async { producer.addOrUpdateContact(radioA, frame(3, "Queued")) }
            runCurrent()
            assertEquals(1, radio.writes.size)

            producer.removeContact(radioA, vKey)
            producer.removeLocalContact(EntityKey(radioA, assertNotNull(store.fetchContact(radioA, vKey)).id), vKey)

            assertEquals(1, radio.writes.size, "Protected no-ops cannot invalidate the queued Add")
            assertTrue(radio.removeRequests.isEmpty())
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertNotNull(store.fetchContact(radioA, vKey))

            radio.accept()
            runCurrent()
            active.await()
            assertEquals(2, radio.writes.size, "The queued Add remains dispatchable after protected no-ops")
            radio.accept()
            runCurrent()
            queued.await()

            producer.finishEvents()
            assertEquals(emptyList(), collectedEvents.await())
            assertNotNull(store.fetchContact(radioA, vKey))
        }
    }

    @Test
    fun protectedVContactRemovalsDoNotWaitForAnUnrelatedSnapshotFence() = runTest {
        withFixture {
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            store.seed(ContactDTO(radioId = radioA, publicKey = vKey, name = "V-contact", lastHeardTimestamp = null))
            val producer = service()
            val snapshotPersistence = PausedContactPersistence(store)
            val snapshotGate = SuspensionGate()
            snapshotPersistence.batchSaveGate = snapshotGate
            val snapshotService = service(persistence = snapshotPersistence)
            val snapshot = async { snapshotService.syncContacts(radioA, null) }
            runCurrent()
            assertTrue(snapshotGate.entered.isCompleted)

            kotlinx.coroutines.withTimeout(1) {
                producer.removeContact(radioA, vKey)
                producer.removeLocalContact(
                    EntityKey(radioA, assertNotNull(store.fetchContact(radioA, vKey)).id),
                    vKey,
                )
            }

            assertTrue(radio.removeRequests.isEmpty())
            assertNotNull(store.fetchContact(radioA, vKey))
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            snapshotGate.release.complete(Unit)
            snapshot.await()
            assertEquals(1, radio.completedSnapshots)
        }
    }

    @Test
    fun snapshotFenceRemainsHeldThroughCleanupAndEmitsDeletionAfterCleanupCompletes() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val coordinator = ContactsFakeSyncCoordinator()
            val cleanup = ContactCleanupCoordinator(
                contacts = store,
                rooms = store,
                syncCoordinator = coordinator,
                notificationService = object : ContactCleanupNotifications {
                    override suspend fun removeDeliveredNotifications(contactId: UUID) {
                        cleanupEntered.complete(Unit)
                        releaseCleanup.await()
                    }

                    override suspend fun updateBadgeCount() = Unit
                },
                remoteNodeService = object : ContactCleanupRemoteSessions {
                    override suspend fun removeSession(id: UUID, publicKey: Bytes) = Unit
                },
                radioId = radioA,
            )
            val deleteService = service(coordinator = coordinator, cleanup = cleanup)
            val events = deleteService.events()
            val observedEvents = mutableListOf<ContactServiceEvent>()
            val nodeDeletedObserved = CompletableDeferred<ContactServiceEvent>()
            val collectedEvents = async {
                events.events.collect { event ->
                    observedEvents += event
                    if (event == ContactServiceEvent.NodeDeleted) nodeDeletedObserved.complete(event)
                }
                observedEvents
            }
            val removal = async { deleteService.removeContact(radioA, contactsKey(1)) }
            runCurrent()

            assertTrue(cleanupEntered.isCompleted, "The device ACK and local delete precede cleanup")
            assertEquals(1, radio.completedRemovals)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            val snapshot = async { deleteService.syncContacts(radioA, null) }
            runCurrent()
            assertEquals(0, radio.completedSnapshots, "Sync must wait through cleanup after the ACK")
            assertFalse(nodeDeletedObserved.isCompleted, "NodeDeleted must follow cleanup completion")

            releaseCleanup.complete(Unit)
            runCurrent()
            assertTrue(nodeDeletedObserved.isCompleted, "NodeDeleted must be delivered after cleanup")
            assertEquals(ContactServiceEvent.NodeDeleted, nodeDeletedObserved.await())
            removal.await()
            snapshot.await()
            assertEquals(1, radio.completedSnapshots)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertEquals(listOf("conversationsChanged", "contactsChanged"), coordinator.notifications)

            deleteService.finishEvents()
            assertEquals(
                listOf(
                    ContactServiceEvent.NodeDeleted,
                    ContactServiceEvent.SyncProgress(received = 0, total = 0),
                    ContactServiceEvent.SyncProgress(received = 0, total = 0),
                ),
                collectedEvents.await(),
            )
        }
    }

    @Test
    fun cleanupCancellationAfterAcknowledgedRemovalKeepsRecoveryFenceAndEmitsNoSuccessEvent() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            val coordinator = ContactsFakeSyncCoordinator()
            val cleanup = ContactCleanupCoordinator(
                contacts = store,
                rooms = store,
                syncCoordinator = coordinator,
                notificationService = object : ContactCleanupNotifications {
                    override suspend fun removeDeliveredNotifications(contactId: UUID) {
                        throw CancellationException("cleanup cancelled after device acknowledgement")
                    }

                    override suspend fun updateBadgeCount() = Unit
                },
                remoteNodeService = object : ContactCleanupRemoteSessions {
                    override suspend fun removeSession(id: UUID, publicKey: Bytes) = Unit
                },
                radioId = radioA,
            )
            val deleteService = service(cleanup = cleanup)
            val events = deleteService.events()
            val collectedEvents = async { events.events.toList() }

            assertFailsWith<CancellationException> { deleteService.removeContact(radioA, contactsKey(1)) }
            assertEquals(1, radio.completedRemovals)
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(deleteService.needsContactWriteReconciliation(radioA))
            assertFailsWith<ContactWriteAdmissionBlocked> {
                deleteService.addOrUpdateContact(radioA, frame(2, "Blocked by recovery"))
            }
            deleteService.finishEvents()
            assertEquals(emptyList(), collectedEvents.await())
        }
    }

    @Test
    fun cancellationWhileWaitingForSnapshotFenceDoesNotClaimADeviceRemoval() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            val snapshotEntered = CompletableDeferred<Unit>()
            val releaseSnapshot = CompletableDeferred<Unit>()
            radio.snapshotHold = HeldRadio.SnapshotHold(snapshotEntered, releaseSnapshot)
            val snapshot = async { producer.syncContacts(radioA, null) }
            runCurrent()
            assertTrue(snapshotEntered.isCompleted)

            val removal = async {
                try {
                    kotlinx.coroutines.withTimeout(1) { producer.removeContact(radioA, contactsKey(1)) }
                    null
                } catch (failure: Exception) {
                    failure
                }
            }
            runCurrent()
            assertTrue(radio.removeRequests.isEmpty())
            advanceTimeBy(1)
            runCurrent()

            assertIs<kotlinx.coroutines.TimeoutCancellationException>(removal.await())
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            assertTrue(radio.removeRequests.isEmpty())
            assertNotNull(store.fetchContact(radioA, contactsKey(1)))

            releaseSnapshot.complete(Unit)
            snapshot.await()
        }
    }

    @Test
    fun cancellationAfterRemoveDispatchRetainsLocalRowAndRequiresReconciliation() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            val removalEntered = CompletableDeferred<Unit>()
            val releaseRemoval = CompletableDeferred<Unit>()
            radio.removeHold = HeldRadio.RemoveHold(removalEntered, releaseRemoval)
            val removal = async { producer.removeContact(radioA, contactsKey(1)) }
            runCurrent()
            assertTrue(removalEntered.isCompleted)

            removal.cancelAndJoin()

            assertTrue(producer.needsContactWriteReconciliation(radioA))
            assertNotNull(store.fetchContact(radioA, contactsKey(1)))
            assertEquals(1, radio.removeRequests.size)
        }
    }

    @Test
    fun acknowledgedRemoveWithLocalDeleteFailureDoesNotReturnSuccess() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            val diskFailure = IOException("contact delete failed")
            val failingPersistence = object : ContactPersisting by store {
                override suspend fun deleteContact(contact: com.meshcoreone.android.core.contracts.domain.EntityKey) {
                    throw diskFailure
                }
            }
            val removal = service(persistence = failingPersistence)
            assertSame(diskFailure, assertFailsWith<IOException> {
                removal.removeContact(radioA, contactsKey(1))
            })

            assertNull(radio.contact(contactsKey(1)))
            assertNotNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(removal.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun uncertainRemoveTransportFailureKeepsTheLocalRowAndRequiresReconciliation() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Present")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()

            radio.removeFailure = IOException("remove response lost")
            val failure = runCatching { producer.removeContact(radioA, contactsKey(1)) }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(1, radio.removeRequests.size)
            assertNotNull(store.fetchContact(radioA, contactsKey(1)))
            assertTrue(producer.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun activeContactWritePreventsDeviceRemovalFromOvertakingItsAck() = runTest {
        withFixture {
            val producer = service()
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "In flight")) }
            runCurrent()
            assertEquals(1, radio.writes.size)

            assertFailsWith<ContactServiceError.SyncInterrupted> {
                producer.removeContact(radioA, contactsKey(1))
            }

            assertTrue(radio.removeRequests.isEmpty())
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            add.cancelAndJoin()
            radio.accept()
            runCurrent()
            assertFalse(producer.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun retiredUnknownWriteBlocksBothDeletionPathsBeforeAndAfterUnacknowledgedRadioAcceptance() = runTest {
        withFixture {
            val newer = ContactDTO.fromFrame(radioA, frame(1, "Newer local", 200u))
            store.seed(newer)
            val write = backgroundScope.async {
                try {
                    service().addOrUpdateContact(radioA, frame(1, "Older pending", 1u))
                    null
                } catch (error: ContactServiceError.SessionError) {
                    error
                }
            }
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertIs<MeshCoreException.Timeout>(assertNotNull(write.await()).error)
            session.stop()

            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio))
            val events = replacement.events()
            val collectedEvents = async { events.events.toList() }
            val key = EntityKey(radioA, newer.id)

            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.removeContact(radioA, newer.publicKey)
            }
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.removeLocalContact(key, newer.publicKey)
            }
            assertEquals(newer, store.fetchContact(radioA, newer.publicKey))
            assertTrue(replacementRadio.removeRequests.isEmpty())
            assertTrue(replacement.needsContactWriteReconciliation(radioA))

            radio.accept(reply = false)
            runCurrent()
            assertEquals("Older pending", radio.contact(newer.publicKey)?.advertisedName)
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.removeContact(radioA, newer.publicKey)
            }
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.removeLocalContact(key, newer.publicKey)
            }
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.syncContacts(radioA, null)
            }
            assertEquals(newer, store.fetchContact(radioA, newer.publicKey))
            assertTrue(replacementRadio.removeRequests.isEmpty())
            assertTrue(replacementRadio.contactRequests.isEmpty())
            assertTrue(replacement.needsContactWriteReconciliation(radioA))
            replacement.finishEvents()
            assertEquals(emptyList(), collectedEvents.await())
        }
    }

    @Test
    fun protectedRemovalsRemainNoOpsWithRetiredUnknownWriteProvenance() = runTest {
        withFixture {
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            val protected = ContactDTO(radioId = radioA, publicKey = vKey, name = "V-contact", lastHeardTimestamp = null)
            store.seed(protected)
            val write = backgroundScope.async {
                try {
                    service().addOrUpdateContact(radioA, frame(2, "Unknown"))
                    null
                } catch (error: ContactServiceError.SessionError) {
                    error
                }
            }
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertIs<MeshCoreException.Timeout>(assertNotNull(write.await()).error)
            session.stop()

            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio))
            val events = replacement.events()
            val collectedEvents = async { events.events.toList() }
            replacement.removeContact(radioA, vKey)
            replacement.removeLocalContact(EntityKey(radioA, protected.id), vKey)

            assertEquals(protected, store.fetchContact(radioA, vKey))
            assertTrue(replacementRadio.removeRequests.isEmpty())
            assertEquals(1, radio.writes.size)
            assertTrue(replacement.needsContactWriteReconciliation(radioA))
            replacement.finishEvents()
            assertEquals(emptyList(), collectedEvents.await())
        }
    }

    @Test
    fun acknowledgedFinishedWriteWithLocalFailureCannotAuthorizeRemovalWhileRecoveryIsMarked() = runTest {
        withFixture {
            val original = ContactDTO.fromFrame(radioA, frame(1, "Local"))
            store.seed(original)
            val failing = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult = throw IOException("accepted write local save failed")
            }
            val write = backgroundScope.async {
                try {
                    service(persistence = failing).addOrUpdateContact(radioA, frame(1, "Accepted", 2u))
                    null
                } catch (error: ContactWriteCompletionFailed) {
                    error
                }
            }
            runCurrent()
            radio.accept()
            runCurrent()
            assertNotNull(write.await())
            assertEquals("Accepted", radio.contact(original.publicKey)?.advertisedName)

            val coordinator = ContactsFakeSyncCoordinator()
            val producer = service(coordinator = coordinator)
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            val events = producer.events()
            val collectedEvents = async { events.events.toList() }
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                producer.removeContact(radioA, original.publicKey)
            }
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                producer.removeLocalContact(EntityKey(radioA, original.id), original.publicKey)
            }

            assertEquals(0, radio.completedRemovals)
            assertTrue(radio.removeRequests.isEmpty())
            assertNotNull(radio.contact(original.publicKey))
            assertEquals(original, store.fetchContact(radioA, original.publicKey))
            assertTrue(coordinator.notifications.isEmpty())
            assertTrue(producer.needsContactWriteReconciliation(radioA), "Deletion is not full-radio recovery")
            producer.finishEvents()
            assertEquals(emptyList(), collectedEvents.await())
        }
    }

    @Test
    fun uncertainRemovalBlocksFurtherMutationsWithSuccessfulOrFailedMarkerRecording() = runTest {
        for (failMarker in listOf(false, true)) {
            withFixture {
                val original = ContactDTO.fromFrame(radioA, frame(1, "Keep local"))
                store.seed(original)
                val markerAttempts = mutableListOf<Boolean>()
                val markerFailure = IOException("remove recovery marker commit failed")
                val flags = object : ContactPreferenceFlags by preferences {
                    override fun set(key: String, value: Boolean) {
                        markerAttempts += value
                        if (failMarker) throw markerFailure
                        preferences.set(key, value)
                    }
                }
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val producer = service(flags = flags, cleanup = cleanup, coordinator = coordinator)
                val events = producer.events()
                val collectedEvents = async { events.events.toList() }
                radio.removeFailure = IOException("uncertain removal transport failure")
                assertNotNull(runCatching { producer.removeContact(radioA, original.publicKey) }.exceptionOrNull())
                assertEquals(1, radio.removeRequests.size)
                assertEquals(listOf(true), markerAttempts)
                assertEquals(!failMarker, preferences.bool("contactWriteReconciliation.${radioA.value}"))
                if (failMarker) {
                    assertSame(markerFailure, assertFailsWith<IOException> {
                        producer.needsContactWriteReconciliation(radioA)
                    })
                } else assertTrue(producer.needsContactWriteReconciliation(radioA))

                radio.removeFailure = null
                suspend fun assertMutationBlocked(operation: suspend () -> Unit) {
                    if (failMarker) assertSame(markerFailure, assertFailsWith<IOException> { operation() })
                    else assertFailsWith<ContactServiceError.SyncInterrupted> { operation() }
                }
                repeat(2) {
                    assertMutationBlocked {
                        producer.removeContact(radioA, original.publicKey)
                    }
                    assertMutationBlocked {
                        producer.removeLocalContact(EntityKey(radioA, original.id), original.publicKey)
                    }
                }
                if (failMarker) {
                    for (since in listOf(null, Instant.ofEpochSecond(50))) {
                        assertSame(markerFailure, assertFailsWith<IOException> { producer.syncContacts(radioA, since) })
                    }
                    assertSame(markerFailure, assertFailsWith<IOException> {
                        producer.addOrUpdateContact(radioA, frame(4, "Blocked"))
                    })
                } else assertRecoveryRemainsBlocked(producer)
                assertEquals(1, radio.removeRequests.size, "No second command after marker-only uncertainty")
                assertEquals(original, store.fetchContact(radioA, original.publicKey))
                assertTrue(cleanup.invocations.isEmpty())
                assertTrue(coordinator.notifications.isEmpty())
                assertEquals(List(if (failMarker) 9 else 1) { true }, markerAttempts, "Only failed true markers are retried")

                if (!failMarker) {
                    val freshFlags = object : ContactPreferenceFlags by preferences {}
                    val fresh = service(flags = freshFlags, cleanup = cleanup, coordinator = coordinator)
                    assertTrue(freshFlags.bool("contactWriteReconciliation.${radioA.value}"))
                    assertFailsWith<ContactServiceError.SyncInterrupted> {
                        fresh.removeContact(radioA, original.publicKey)
                    }
                    assertFailsWith<ContactServiceError.SyncInterrupted> {
                        fresh.removeLocalContact(EntityKey(radioA, original.id), original.publicKey)
                    }
                }

                val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
                val protected = ContactDTO(radioId = radioA, publicKey = vKey, name = "V-contact", lastHeardTimestamp = null)
                store.seed(protected)
                producer.removeContact(radioA, vKey)
                producer.removeLocalContact(EntityKey(radioA, protected.id), vKey)
                assertEquals(protected, store.fetchContact(radioA, vKey))
                assertEquals(1, radio.removeRequests.size)
                assertTrue(cleanup.invocations.isEmpty())
                producer.finishEvents()
                assertEquals(emptyList(), collectedEvents.await())

                val other = ContactDTO.fromFrame(radioB, frame(3, "Other radio"))
                store.seed(other)
                producer.removeLocalContact(EntityKey(radioB, other.id), other.publicKey)
                assertNull(store.fetchContact(radioB, other.publicKey))
                assertEquals(1, cleanup.invocations.size)
                assertFalse(producer.needsContactWriteReconciliation(radioB))
                if (failMarker) {
                    assertSame(markerFailure, assertFailsWith<IOException> {
                        producer.needsContactWriteReconciliation(radioA)
                    })
                } else assertTrue(producer.needsContactWriteReconciliation(radioA))
                assertEquals(original, store.fetchContact(radioA, original.publicKey))
            }
        }
    }

    @Test
    fun cleanMarkerStatePermitsNormalRemovalAfterProtectedNoOps() = runTest {
        withFixture {
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            val protected = ContactDTO(radioId = radioA, publicKey = vKey, name = "V-contact", lastHeardTimestamp = null)
            store.seed(protected)
            val cleanup = ContactsRecordingCleanup()
            val producer = service(cleanup = cleanup)
            producer.removeContact(radioA, vKey)
            producer.removeLocalContact(EntityKey(radioA, protected.id), vKey)
            assertTrue(radio.removeRequests.isEmpty())
            assertTrue(cleanup.invocations.isEmpty())
            assertEquals(protected, store.fetchContact(radioA, vKey))
            val add = async { producer.addOrUpdateContact(radioA, frame(1, "Remove normally")) }
            runCurrent()
            radio.accept()
            runCurrent()
            add.await()
            producer.removeContact(radioA, contactsKey(1))
            assertEquals(1, radio.completedRemovals)
            assertNull(radio.contact(contactsKey(1)))
            assertNull(store.fetchContact(radioA, contactsKey(1)))
            assertEquals(1, cleanup.invocations.size)
            assertFalse(producer.needsContactWriteReconciliation(radioA))
        }
    }

    @Test
    fun cancelledLateAckPersistenceBlocksDeletesAndSnapshotWithoutOverwritingNewerMetadata() = runTest {
        withFixture {
            val original = ContactDTO.fromFrame(radioA, frame(1, "Original"))
            store.seed(original)
            val saveGate = SuspensionGate()
            val held = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult? {
                    saveGate.pause()
                    return store.saveContactPreservingNewer(radioId, frame, expectedFrame)
                }
            }
            var navigated = false
            val caller = async {
                service(persistence = held).addOrUpdateContact(radioA, frame(1, "Late older", 2u))
                navigated = true
            }
            runCurrent()
            caller.cancelAndJoin()
            radio.accept()
            runCurrent()
            assertTrue(saveGate.entered.isCompleted, "The ACK precedes the held local completion")
            assertEquals("Late older", radio.contact(original.publicKey)?.advertisedName)

            val newer = original.updating(frame(1, "Newer local", 200u))
            store.seed(newer)
            val replacementRadio = radio.replacementView()
            val replacement = service(newSession(replacementRadio))
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.removeContact(radioA, original.publicKey)
            }
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.removeLocalContact(EntityKey(radioA, original.id), original.publicKey)
            }
            assertFailsWith<ContactServiceError.SyncInterrupted> {
                replacement.syncContacts(radioA, null)
            }
            assertTrue(replacementRadio.removeRequests.isEmpty())
            assertTrue(replacementRadio.contactRequests.isEmpty())
            assertEquals(newer, store.fetchContact(radioA, original.publicKey))

            saveGate.release.complete(Unit)
            runCurrent()
            assertEquals(newer, store.fetchContact(radioA, original.publicKey))
            assertTrue(replacement.needsContactWriteReconciliation(radioA))
            assertFalse(navigated)
            assertTrue(caller.isCancelled)
            assertTrue(diagnostics.any {
                it is com.meshcoreone.android.core.protocol.session.SessionDiagnostic.BackgroundFailure
            })
        }
    }

    @Test
    fun unresolvedWriteRejectsSyncUntilAckThenReleasesQueuedWrite() = runTest {
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

            assertEquals(2, radio.writes.size, "The queued write resumes after exact ACK and local completion")
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)

            radio.accept()
            runCurrent()
            assertNull(queued.await())
            assertFalse(producer.needsContactWriteReconciliation(radioA))
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

    @Test
    fun equalCountUnprovedFetchCannotMutatePruneClearRecoveryOrEnableWritesAcrossRestart() = runTest {
        withFixture {
            var markerClears = 0
            val observedFlags = object : ContactPreferenceFlags by preferences {
                override fun set(key: String, value: Boolean) {
                    if (key == "contactWriteReconciliation.${radioA.value}" && !value) markerClears++
                    preferences.set(key, value)
                }
            }
            val producer = service(flags = observedFlags)
            val caller = async { producer.addOrUpdateContact(radioA, frame(1, "Radio older", 1u)) }
            runCurrent()
            caller.cancelAndJoin()
            val newer = ContactDTO.fromFrame(radioA, frame(1, "Local newer", 200u))
            store.seed(newer)
            radio.accept()
            runCurrent()
            val orphan = ContactDTO.fromFrame(radioA, frame(2, "Must not prune", 200u))
            store.seed(orphan)

            val unproved = session.getContactsReportingTotal(null)
            assertEquals(1L, unproved.reportedTotal)
            assertEquals(1, unproved.contacts.size)
            val fetchesBeforeGuard = radio.contactRequests.size
            var batchSaves = 0
            var deletes = 0
            val observedPersistence = object : ContactPersisting by store {
                override suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long {
                    batchSaves++
                    return store.batchSaveContacts(radioId, frames)
                }

                override suspend fun deleteContact(contact: EntityKey) {
                    deletes++
                    store.deleteContact(contact)
                }
            }
            val coordinator = ContactsFakeSyncCoordinator()
            val guarded = service(persistence = observedPersistence, coordinator = coordinator, flags = observedFlags)
            val events = guarded.events()
            val collectedEvents = async { events.events.toList() }
            assertRecoveryRemainsBlocked(guarded)
            assertEquals(fetchesBeforeGuard, radio.contactRequests.size, "Recovery must fail before another fetch")
            assertEquals(0, batchSaves)
            assertEquals(0, deletes)
            assertEquals(0, markerClears)
            assertEquals(newer, store.fetchContact(radioA, newer.publicKey))
            assertEquals(orphan, store.fetchContact(radioA, orphan.publicKey))
            assertTrue(preferences.bool("contactWriteReconciliation.${radioA.value}"))
            assertTrue(coordinator.notifications.isEmpty())
            guarded.finishEvents()
            assertEquals(emptyList(), collectedEvents.await())

            session.stop()
            val restartedFlags = ContactsFakePreferences().apply {
                set("contactWriteReconciliation.${radioA.value}", true)
            }
            val replacementRadio = radio.replacementView()
            val restarted = service(
                connection = newSession(replacementRadio),
                persistence = observedPersistence,
                flags = restartedFlags,
            )
            assertRecoveryRemainsBlocked(restarted)
            assertTrue(replacementRadio.contactRequests.isEmpty())
            assertEquals(0, batchSaves)
            assertEquals(0, deletes)
            assertEquals(newer, store.fetchContact(radioA, newer.publicKey))
            assertEquals(orphan, store.fetchContact(radioA, orphan.publicKey))
            assertTrue(restartedFlags.bool("contactWriteReconciliation.${radioA.value}"))
            assertEquals(1, radio.writes.size)
        }
    }

    @Test
    fun ordinaryFullAndIncrementalSyncRemainAvailableWithoutRecovery() = runTest {
        for (since in listOf(null, Instant.ofEpochSecond(50))) {
            withFixture {
                val producer = service()
                val add = async { producer.addOrUpdateContact(radioA, frame(1, "Radio contact")) }
                runCurrent()
                radio.accept()
                runCurrent()
                add.await()
                val orphan = ContactDTO.fromFrame(radioA, frame(2, "Local only"))
                store.seed(orphan)

                val result = producer.syncContacts(radioA, since)
                assertEquals(1L, result.contactsReceived)
                assertEquals(since != null, result.isIncremental)
                assertEquals("Radio contact", store.fetchContact(radioA, contactsKey(1))?.name)
                if (since == null) assertNull(store.fetchContact(radioA, orphan.publicKey))
                else assertEquals(orphan, store.fetchContact(radioA, orphan.publicKey))
                assertFalse(producer.needsContactWriteReconciliation(radioA))
                assertEquals(1, radio.contactRequests.size)
            }
        }
    }

    @Test
    fun nextGuardedAccessRecordsFailedRemovalMarkerWithoutAnotherExchange() = runTest {
        for (access in listOf("snapshot", "sync", "add", "remove", "local", "dispatch")) {
            withFixture {
                val row = ContactDTO.fromFrame(radioA, frame(2, "Retained"))
                store.seed(row)
                val flags = PendingMarkerFlags()
                val markerFailure = IOException("Marker disk write failed")
                flags.failure = markerFailure
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val producer = service(flags = flags, cleanup = cleanup, coordinator = coordinator)
                val subscription = producer.events()
                val events = async { subscription.events.toList() }
                val transportFailure = MarkerRecordingFailure("Removal outcome unknown")
                radio.removeFailure = transportFailure
                assertSame(transportFailure, assertFailsWith<IOException> {
                    producer.removeContact(radioA, row.publicKey)
                })
                assertTrue(transportFailure.suppressed.any { it === markerFailure })
                val marker = "contactWriteReconciliation.${radioA.value}"
                assertTrue(flags.bool(marker), "Failed commit can already be visible in memory")
                assertFalse(flags.durableView().bool(marker), "In-memory true is not durable recording")
                assertEquals(listOf(marker to true), flags.attempts)
                flags.failure = null
                radio.removeFailure = null
                when (access) {
                    "snapshot" -> assertTrue(producer.needsContactWriteReconciliation(radioA))
                    "sync" -> assertFailsWith<ContactServiceError.SyncInterrupted> { producer.syncContacts(radioA, null) }
                    "add" -> assertEquals(
                        ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED,
                        assertFailsWith<ContactWriteAdmissionBlocked> {
                            producer.addOrUpdateContact(radioA, frame(3, "Must not send"))
                        }.reason,
                    )
                    "remove" -> assertFailsWith<ContactServiceError.SyncInterrupted> {
                        producer.removeContact(radioA, row.publicKey)
                    }
                    "local" -> assertFailsWith<ContactServiceError.SyncInterrupted> {
                        producer.removeLocalContact(EntityKey(radioA, row.id), row.publicKey)
                    }
                    else -> assertEquals(
                        ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED,
                        assertFailsWith<ContactWriteAdmissionBlocked> {
                            ContactWriteRecovery.beforeDispatch(flags, radioA, ContactWriteDispatch())
                        }.reason,
                    )
                }
                assertEquals(List(2) { marker to true }, flags.attempts)
                assertTrue(flags.durableView().bool(marker))
                val epoch = assertNotNull(ContactWriteRecovery.snapshot(flags, radioA))
                assertSame(epoch, ContactWriteRecovery.snapshot(flags, radioA))
                assertRecoveryRemainsBlocked(producer)
                assertEquals(List(2) { marker to true }, flags.attempts, "Successful recording ends access retries")
                assertEquals(1, radio.removeRequests.size)
                assertTrue(radio.writes.isEmpty())
                assertTrue(radio.contactRequests.isEmpty())
                assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
                assertTrue(store.deletedContactIDs.isEmpty())
                assertTrue(cleanup.invocations.isEmpty())
                assertTrue(coordinator.notifications.isEmpty())
                producer.finishEvents()
                assertEquals(emptyList(), events.await())
            }
        }
    }

    @Test
    fun queuedMarkerRetryPreservesIdenticalRecordingFailure() = queuedMarkerRetryFailure(distinctFailure = false)

    @Test
    fun queuedMarkerRetrySuppressesDistinctRecordingFailure() = queuedMarkerRetryFailure(distinctFailure = true)

    private fun queuedMarkerRetryFailure(distinctFailure: Boolean) = runTest {
        withFixture {
            val markerFailure = MarkerRecordingFailure("Pending marker retry failed")
            val recordingFailure = if (distinctFailure) MarkerRecordingFailure("Service marker retry failed") else markerFailure
            val backing = PendingMarkerFlags()
            var failMarker = true
            val flags = object : ContactPreferenceFlags by backing {
                override fun set(key: String, value: Boolean) {
                    // Cancellation and its terminal callback precede the queued dispatch and catch.
                    backing.failure = if (!failMarker) null
                        else if (backing.attempts.size < 4) markerFailure else recordingFailure
                    backing.set(key, value)
                }
            }
            val logs = mutableListOf<Pair<ContactLogLevel, String>>()
            val coordinator = ContactsFakeSyncCoordinator()
            val producer = service(
                flags = flags,
                coordinator = coordinator,
                logger = ContactServiceLogger { level, message -> logs += level to message },
            )
            val first = async { producer.addOrUpdateContact(radioA, frame(1, "First")) }
            runCurrent()
            val queued = async {
                assertFailsWith<IOException> { producer.addOrUpdateContact(radioA, frame(2, "Queued")) }
            }
            runCurrent()
            assertEquals(1, radio.writes.size)
            assertTrue(backing.attempts.isEmpty(), "Both writes registered before recovery became pending")

            first.cancelAndJoin()
            assertEquals(1, backing.attempts.size)
            radio.accept()
            runCurrent()

            assertSame(markerFailure, queued.await())
            assertEquals(
                if (distinctFailure) listOf(recordingFailure) else emptyList(),
                markerFailure.suppressed.toList(),
            )
            val marker = "contactWriteReconciliation.${radioA.value}"
            assertEquals(List(5) { marker to true }, backing.attempts)
            assertTrue(backing.bool(marker))
            assertFalse(backing.durableView().bool(marker))
            assertEquals(3, logs.count { it == (ContactLogLevel.WARNING to "Contact recovery marker could not be persisted") })
            assertEquals(3, logs.count { it == (ContactLogLevel.WARNING to "Contact state requires a complete radio contact sync") })
            assertEquals(1, radio.writes.size, "The queued write never reaches the transport")
            assertEquals("First", store.fetchContact(radioA, contactsKey(1))?.name)
            assertNull(store.fetchContact(radioA, contactsKey(2)))
            assertEquals(listOf("contactsChanged"), coordinator.notifications)

            failMarker = false
            val recovered = service(flags = flags)
            assertFalse(recovered.needsContactWriteReconciliation(radioA))
            assertEquals(List(6) { marker to true } + (marker to false), backing.attempts)
            assertFalse(backing.durableView().bool(marker))
            assertEquals(ContactWriteRecoveryState.Clear(radioA), recovered.contactWriteRecoveryState(radioA))
            assertEquals(1, radio.writes.size)
            assertTrue(radio.contactRequests.isEmpty())
        }
    }

    @Test
    fun continuedNextAccessFailurePropagatesAndIsIsolatedFromOtherRadio() = runTest {
        withFixture {
            val row = ContactDTO.fromFrame(radioA, frame(2, "Retained"))
            store.seed(row)
            val flags = PendingMarkerFlags()
            val failure = IOException("Still cannot record")
            flags.failure = failure
            val producer = service(flags = flags)
            assertSame(failure, assertFailsWith<IOException> { ContactWriteRecovery.require(flags, radioA) })
            val marker = "contactWriteReconciliation.${radioA.value}"
            val accesses: List<suspend () -> Unit> = listOf(
                { producer.needsContactWriteReconciliation(radioA); Unit },
                { producer.syncContacts(radioA, null); Unit },
                { producer.addOrUpdateContact(radioA, frame(3, "Blocked")) },
                { producer.removeContact(radioA, row.publicKey) },
                { producer.removeLocalContact(EntityKey(radioA, row.id), row.publicKey) },
                { ContactWriteRecovery.beforeDispatch(flags, radioA, ContactWriteDispatch()) },
            )
            repeat(2) {
                accesses.forEach { access -> assertSame(failure, assertFailsWith<IOException> { access() }) }
            }
            assertEquals(List(13) { marker to true }, flags.attempts)
            assertTrue(flags.bool(marker))
            assertFalse(flags.durableView().bool(marker))
            assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
            assertTrue(store.deletedContactIDs.isEmpty())
            assertTrue(radio.writes.isEmpty())
            assertTrue(radio.contactRequests.isEmpty())
            assertTrue(radio.removeRequests.isEmpty())

            val other = ContactDTO.fromFrame(radioB, frame(4, "Other radio"))
            store.seed(other)
            assertFalse(producer.needsContactWriteReconciliation(radioB))
            producer.removeLocalContact(EntityKey(radioB, other.id), other.publicKey)
            assertNull(store.fetchContact(EntityKey(radioB, other.id)))
            assertEquals(List(13) { marker to true }, flags.attempts, "Other radio cannot retry this radio's marker")
            flags.failure = null
            assertTrue(producer.needsContactWriteReconciliation(radioA))
            assertEquals(List(14) { marker to true }, flags.attempts)
            assertTrue(flags.durableView().bool(marker))
        }
    }

    @Test
    fun accessRetryDeduplicatesDispatchReportsAndDoesNotRemarkAfterMatchingClear() = runTest {
        withFixture {
            val flags = PendingMarkerFlags()
            val marker = "contactWriteReconciliation.${radioA.value}"
            ContactWriteRecovery.require(flags, radioA)
            val oldEpoch = assertNotNull(ContactWriteRecovery.snapshot(flags, radioA))
            flags.failure = IOException("New marker commit failed")
            val dispatch = ContactWriteDispatch()
            assertFailsWith<IOException> { ContactWriteRecovery.require(flags, radioA, dispatch) }
            assertFalse(ContactWriteRecovery.reconciled(flags, radioA, oldEpoch), "Older epoch cannot clear pending uncertainty")
            assertEquals(List(2) { marker to true }, flags.attempts)
            flags.failure = null
            val current = assertNotNull(ContactWriteRecovery.snapshot(flags, radioA))
            assertFalse(current === oldEpoch)
            repeat(2) { ContactWriteRecovery.require(flags, radioA, dispatch) }
            assertSame(current, ContactWriteRecovery.snapshot(flags, radioA))
            assertEquals(List(3) { marker to true }, flags.attempts, "Access success deduplicates pending dispatch reports")
            // Internal marker CAS only: this is not a service reconciliation permit.
            assertTrue(ContactWriteRecovery.reconciled(flags, radioA, current))
            assertEquals(List(3) { marker to true } + (marker to false), flags.attempts)
            assertFalse(flags.durableView().bool(marker))
            assertNull(ContactWriteRecovery.snapshot(flags, radioA))
            ContactWriteRecovery.withSyncFence(flags, radioA) { assertNull(ContactWriteRecovery.snapshot(flags, radioA)) }
            val cleanDispatch = ContactWriteDispatch()
            ContactWriteRecovery.registerWrite(flags, radioA, cleanDispatch)
            ContactWriteRecovery.beforeDispatch(flags, radioA, cleanDispatch)
            ContactWriteRecovery.abandonWrite(flags, radioA, cleanDispatch)
            assertEquals(List(3) { marker to true } + (marker to false), flags.attempts, "Cleared pending state must not re-mark")
        }
    }

    @Test
    fun unknownSelfIdentityBlocksBothRemovalPathsWithoutEffects() = runTest {
        val identities = listOf(
            null,
            contactsDevice(radioB),
            contactsDevice(radioA, Bytes.EMPTY),
            contactsDevice(radioA, Bytes(ByteArray(31))),
            contactsDevice(radioA, Bytes(ByteArray(33))),
        )
        val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(1)))
        val cases = identities.flatMap { identity -> listOf(contactsKey(2), vKey).map { identity to it } } +
            listOf(0, 31, 33).map { contactsDevice(radioA) to Bytes(ByteArray(it)) }
        for ((identity, publicKey) in cases) {
            withFixture {
                val row = ContactDTO(radioId = radioA, publicKey = publicKey, name = "Retained", lastHeardTimestamp = null)
                store.seed(row)
                val deviceRole = object : DevicePersisting by store {
                    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? = identity
                }
                var markerAttempts = 0
                val flags = object : ContactPreferenceFlags by preferences {
                    override fun set(key: String, value: Boolean) {
                        markerAttempts++
                        preferences.set(key, value)
                    }
                }
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val warnings = mutableListOf<String>()
                val producer = service(
                    devices = deviceRole, flags = flags, cleanup = cleanup, coordinator = coordinator,
                    logger = ContactServiceLogger { level, message ->
                        if (level == ContactLogLevel.WARNING) warnings += message
                    },
                )
                assertNoDeletionEffects(producer, cleanup, coordinator) {
                    assertFailsWith<ContactServiceError.SyncInterrupted> { producer.removeContact(radioA, publicKey) }
                    assertFailsWith<ContactServiceError.SyncInterrupted> {
                        producer.removeLocalContact(EntityKey(radioA, row.id), publicKey)
                    }
                }
                assertEquals(0, markerAttempts, "An identity refusal is not a radio uncertainty")
                assertEquals(2, warnings.size)
            }
        }
    }

    @Test
    fun failedSelfIdentityLookupPropagatesAndAllowsRetry() = runTest {
        for (failure in listOf(IOException("Identity unavailable"), CancellationException("Lookup cancelled"))) {
            withFixture {
                val row = ContactDTO(radioId = radioA, publicKey = contactsKey(2), name = "Retry", lastHeardTimestamp = null)
                store.seed(row)
                store.fetchDeviceFailure = failure
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val producer = service(cleanup = cleanup, coordinator = coordinator)
                assertNoDeletionEffects(producer, cleanup, coordinator) {
                    assertSame(failure, assertFailsWith<Exception> { producer.removeContact(radioA, row.publicKey) })
                    assertSame(failure, assertFailsWith<Exception> {
                        producer.removeLocalContact(EntityKey(radioA, row.id), row.publicKey)
                    })
                }
                store.fetchDeviceFailure = null
                service(cleanup = cleanup, coordinator = coordinator).removeContact(radioA, row.publicKey)
                assertEquals(1, radio.completedRemovals)
                assertNull(store.fetchContact(EntityKey(radioA, row.id)))
                assertEquals(1, cleanup.invocations.size)
                assertEquals(listOf("contactsChanged"), coordinator.notifications)
            }
        }
    }

    @Test
    fun localRemovalRequiresActualMatchingStoredContact() = runTest {
        for (protected in listOf(false, true)) {
            withFixture {
                val publicKey = if (protected) assertNotNull(VContactIdentity.publicKey(contactsKey(1))) else contactsKey(2)
                val row = ContactDTO(radioId = radioA, publicKey = publicKey, name = "Retained", lastHeardTimestamp = null)
                store.seed(row)
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val producer = service(cleanup = cleanup, coordinator = coordinator)
                assertNoDeletionEffects(producer, cleanup, coordinator) {
                    val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(1)))
                    for (supplied in listOf(contactsKey(3), if (protected) contactsKey(2) else vKey)) {
                        assertFailsWith<ContactServiceError.SyncInterrupted> {
                            producer.removeLocalContact(EntityKey(radioA, row.id), supplied)
                        }
                    }
                    assertFailsWith<ContactServiceError.ContactNotFound> {
                        producer.removeLocalContact(EntityKey(radioA, UUID.randomUUID()), publicKey)
                    }
                    val failure = IOException("Contact lookup failed")
                    store.fetchContactByKeyFailure = failure
                    assertSame(failure, assertFailsWith<IOException> {
                        producer.removeLocalContact(EntityKey(radioA, row.id), publicKey)
                    })
                    val cancelled = CancellationException("Contact lookup cancelled")
                    store.fetchContactByKeyFailure = cancelled
                    assertSame(cancelled, assertFailsWith<CancellationException> {
                        producer.removeLocalContact(EntityKey(radioA, row.id), publicKey)
                    })
                    store.fetchContactByKeyFailure = null
                }
            }
        }
    }

    @Test
    fun localIdentityIsRecheckedBeforeMutation() = runTest {
        for (change in listOf("key", "radio", "id", "missing", "failure")) {
            withFixture {
                val row = ContactDTO(radioId = radioA, publicKey = contactsKey(2), name = "Retained", lastHeardTimestamp = null)
                store.seed(row)
                val failure = IOException("Second contact lookup failed")
                var reads = 0
                val persistence = object : ContactPersisting by store {
                    override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                        if (++reads == 1) return row
                        return when (change) {
                            "key" -> row.copy(publicKey = assertNotNull(VContactIdentity.publicKey(contactsKey(1))))
                            "radio" -> row.copy(radioId = radioB)
                            "id" -> row.copy(id = UUID.randomUUID())
                            "missing" -> null
                            else -> throw failure
                        }
                    }
                }
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val producer = service(persistence = persistence, cleanup = cleanup, coordinator = coordinator)
                assertNoDeletionEffects(producer, cleanup, coordinator) {
                    val error = assertFailsWith<Exception> {
                        producer.removeLocalContact(EntityKey(radioA, row.id), row.publicKey)
                    }
                    when (change) {
                        "missing" -> assertIs<ContactServiceError.ContactNotFound>(error)
                        "failure" -> assertSame(failure, error)
                        else -> assertIs<ContactServiceError.SyncInterrupted>(error)
                    }
                }
                assertEquals(2, reads, "A stale early lookup cannot authorize deletion")
            }
        }
    }

    @Test
    fun selfIdentityIsRecheckedBeforeRemoteAndLocalMutation() = runTest {
        for (local in listOf(false, true)) for (change in listOf("missing", "invalid", "failure")) {
            withFixture {
                val row = ContactDTO(radioId = radioA, publicKey = contactsKey(2), name = "Retained", lastHeardTimestamp = null)
                store.seed(row)
                val failure = IOException("Second identity lookup failed")
                var reads = 0
                val deviceRole = object : DevicePersisting by store {
                    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? {
                        if (++reads == 1) return contactsDevice(radioId)
                        return when (change) {
                            "missing" -> null
                            "invalid" -> contactsDevice(radioId, Bytes.EMPTY)
                            else -> throw failure
                        }
                    }
                }
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val producer = service(devices = deviceRole, cleanup = cleanup, coordinator = coordinator)
                assertNoDeletionEffects(producer, cleanup, coordinator) {
                    val error = assertFailsWith<Exception> {
                        if (local) producer.removeLocalContact(EntityKey(radioA, row.id), row.publicKey)
                        else producer.removeContact(radioA, row.publicKey)
                    }
                    if (change == "failure") assertSame(failure, error)
                    else assertIs<ContactServiceError.SyncInterrupted>(error)
                }
                assertEquals(2, reads)
            }
        }
    }

    @Test
    fun cancellationWhileResolvingIdentityDoesNotDispatchRemoval() = runTest {
        for (local in listOf(false, true)) {
            withFixture {
                val row = ContactDTO(radioId = radioA, publicKey = contactsKey(2), name = "Retained", lastHeardTimestamp = null)
                store.seed(row)
                val gate = SuspensionGate()
                val deviceRole = object : DevicePersisting by store {
                    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? {
                        gate.pause()
                        return store.fetchDevice(radioId)
                    }
                }
                val cleanup = ContactsRecordingCleanup()
                val coordinator = ContactsFakeSyncCoordinator()
                val producer = service(devices = deviceRole, cleanup = cleanup, coordinator = coordinator)
                assertNoDeletionEffects(producer, cleanup, coordinator) {
                    val removal = async {
                        if (local) producer.removeLocalContact(EntityKey(radioA, row.id), row.publicKey)
                        else producer.removeContact(radioA, row.publicKey)
                    }
                    runCurrent()
                    assertTrue(gate.entered.isCompleted)
                    removal.cancelAndJoin()
                    assertFailsWith<CancellationException> { removal.await() }
                    gate.release.complete(Unit)
                }
            }
        }
    }

    @Test
    fun notFoundStillAllowsVerifiedLocalFallback() = runTest {
        withFixture {
            val row = ContactDTO(radioId = radioA, publicKey = contactsKey(2), name = "Orphan", lastHeardTimestamp = null)
            store.seed(row)
            radio.removeErrorCode = 2
            val cleanup = ContactsRecordingCleanup()
            val coordinator = ContactsFakeSyncCoordinator()
            val producer = service(cleanup = cleanup, coordinator = coordinator)
            val subscription = producer.events()
            val events = async { subscription.events.toList() }
            assertFailsWith<ContactServiceError.ContactNotFound> { producer.removeContact(radioA, row.publicKey) }
            assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
            assertTrue(cleanup.invocations.isEmpty())
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            producer.removeLocalContact(EntityKey(radioA, row.id), row.publicKey)
            assertEquals(1, radio.removeRequests.size, "Local fallback sends no second device command")
            assertEquals(0, radio.completedRemovals, "NOT_FOUND is not a successful radio deletion")
            assertNull(store.fetchContact(EntityKey(radioA, row.id)))
            assertEquals(
                listOf(ContactsRecordingCleanup.Invocation(EntityKey(radioA, row.id), ContactCleanupReason.DELETED, row.publicKey)),
                cleanup.invocations,
            )
            assertEquals(listOf("contactsChanged"), coordinator.notifications)
            producer.finishEvents()
            assertEquals(listOf(ContactServiceEvent.NodeDeleted), events.await())
        }
    }

    @Test
    fun unknownIdentityDoesNotInvalidateActiveOrQueuedWrites() = runTest {
        withFixture {
            val producer = service()
            val row = ContactDTO(radioId = radioA, publicKey = contactsKey(4), name = "Retained", lastHeardTimestamp = null)
            store.seed(row)
            val active = async { producer.addOrUpdateContact(radioA, frame(2, "Active")) }
            runCurrent()
            val queued = async { producer.addOrUpdateContact(radioA, frame(3, "Queued")) }
            runCurrent()
            assertEquals(1, radio.writes.size)
            store.fetchDeviceFailure = IOException("Unknown self")
            val cleanup = ContactsRecordingCleanup()
            val coordinator = ContactsFakeSyncCoordinator()
            val deletion = service(cleanup = cleanup, coordinator = coordinator)
            assertNoDeletionEffects(deletion, cleanup, coordinator) {
                assertFailsWith<IOException> { deletion.removeContact(radioA, row.publicKey) }
                assertFailsWith<IOException> { deletion.removeLocalContact(EntityKey(radioA, row.id), row.publicKey) }
            }
            store.fetchDeviceFailure = null
            radio.accept()
            runCurrent()
            active.await()
            assertEquals(2, radio.writes.size, "Identity refusal must leave the queued Add dispatchable")
            radio.accept()
            runCurrent()
            queued.await()
            assertEquals(row, store.fetchContact(EntityKey(radioA, row.id)))
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
            connection: ContactSessionOps = session,
            persistence: ContactPersisting = store,
            coordinator: ContactSyncCoordinating? = null,
            cleanup: ContactCleanupHandling? = null,
            flags: ContactPreferenceFlags = preferences,
            devices: DevicePersisting = store,
            logger: ContactServiceLogger = ContactServiceLogger.NONE,
        ) = ContactService(connection, devices, persistence, coordinator, cleanup, flags, logger = logger)

        suspend fun assertNoDeletionEffects(
            producer: ContactService,
            cleanup: ContactsRecordingCleanup,
            coordinator: ContactsFakeSyncCoordinator,
            operation: suspend () -> Unit,
        ) {
            val before = store.contacts
            val deleted = store.deletedContactIDs
            val removeCount = radio.removeRequests.size
            val subscription = producer.events()
            val events = scope.async { subscription.events.toList() }
            operation()
            assertEquals(before, store.contacts)
            assertEquals(deleted, store.deletedContactIDs)
            assertEquals(removeCount, radio.removeRequests.size)
            assertTrue(cleanup.invocations.isEmpty())
            assertTrue(coordinator.notifications.isEmpty())
            assertFalse(producer.needsContactWriteReconciliation(radioA))
            producer.finishEvents()
            assertEquals(emptyList(), events.await())
        }

        suspend fun assertRecoveryRemainsBlocked(producer: ContactService) {
            for (since in listOf(null, Instant.ofEpochSecond(50))) {
                assertFailsWith<ContactServiceError.SyncInterrupted> { producer.syncContacts(radioA, since) }
                assertTrue(producer.needsContactWriteReconciliation(radioA))
            }
            assertEquals(
                ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED,
                assertFailsWith<ContactWriteAdmissionBlocked> {
                    producer.addOrUpdateContact(radioA, frame(0x70, "Blocked retry"))
                }.reason,
            )
        }

        suspend fun close() { sessions.asReversed().forEach { it.stop() } }
    }

    private class HeldRadio(private val shared: SharedRadio = SharedRadio()) : MeshTransport {
        private val mock = MockTransport()
        val writes: MutableList<Bytes> get() = shared.writes
        val contactRequests = mutableListOf<Bytes>()
        val removeRequests = mutableListOf<Bytes>()
        var snapshotHold: SnapshotHold? = null
        var removeHold: RemoveHold? = null
        var removeFailure: Exception? = null
        var removeErrorCode: Int? = null
        var completedSnapshots = 0
        var completedRemovals = 0
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
                    completedSnapshots++
                }
                0x0F -> {
                    removeRequests += data
                    removeFailure?.let { throw it }
                    removeErrorCode?.let {
                        mock.simulateReceive(Bytes.of(0x01, it))
                        return
                    }
                    removeHold?.let {
                        it.entered.complete(Unit)
                        it.release.await()
                    }
                    shared.rows.remove(data.slice(1, 33))
                    mock.simulateReceive(Bytes.of(0x00))
                    completedRemovals++
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

        class RemoveHold(
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

    private class MarkerRecordingFailure(message: String) : IOException(message), CopyableThrowable<MarkerRecordingFailure> {
        // Keep the injected identity across serializer await, even with stacktrace recovery enabled.
        override fun createCopy(): MarkerRecordingFailure? = null
    }

    private class PendingMarkerFlags : ContactPreferenceFlags {
        private val memory = mutableMapOf<String, Boolean>()
        private val durable = mutableMapOf<String, Boolean>()
        val attempts = mutableListOf<Pair<String, Boolean>>()
        var failure: IOException? = null

        override fun bool(key: String): Boolean = memory[key] ?: false

        override fun set(key: String, value: Boolean) {
            attempts += key to value
            memory[key] = value
            failure?.let { throw it }
            durable[key] = value
        }

        fun durableView(): ContactPreferenceFlags = object : ContactPreferenceFlags {
            override fun bool(key: String): Boolean = durable[key] ?: false
            override fun set(key: String, value: Boolean): Unit = error("Read-only durable test view")
        }
    }

    private class SuspensionGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun pause() {
            entered.complete(Unit)
            release.await()
        }
    }

    private class PausedContactPersistence(
        private val delegate: ContactPersisting,
    ) : ContactPersisting by delegate {
        var batchSaveGate: SuspensionGate? = null
        var deleteGate: SuspensionGate? = null

        override suspend fun batchSaveContacts(
            radioId: RadioId,
            frames: SnapshotList<ContactFrame>,
        ): Long {
            batchSaveGate?.pause()
            return delegate.batchSaveContacts(radioId, frames)
        }

        override suspend fun deleteContact(contact: EntityKey) {
            deleteGate?.pause()
            delegate.deleteContact(contact)
        }
    }

    private companion object {
        fun le32(value: Long): Bytes = Bytes(ByteArray(4) { (value ushr (8 * it)).toByte() })
    }
}
