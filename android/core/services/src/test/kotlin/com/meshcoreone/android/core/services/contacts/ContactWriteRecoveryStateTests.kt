// AndroidOnly: WP-209 Read-only uncertainty presentation through real service/session paths.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactWriteRecoveryStateTests {
    private val radioA = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000209"))
    private val radioB = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000210"))

    @Test
    fun cancelledSentWriteReportsUncertaintyWithoutRetrySendOrNavigationAndLateAckIsNotUnknown() = runTest {
        withFixture {
            val producer = service()
            val cached = ContactDTO.fromFrame(radioA, frame("Cached", 2))
            store.seed(cached)
            var navigated = false
            val delivery = async {
                producer.addOrUpdateContact(radioA, frame("Sent"))
                navigated = true
            }
            runCurrent()
            assertEquals(ContactWriteRecoveryState.Clear(radioA), producer.contactWriteRecoveryState(radioA))
            delivery.cancelAndJoin()
            val uncertain = ContactWriteRecoveryState.Required(
                radioA, ContactWriteRecoveryReason.RADIO_OUTCOME_UNCERTAIN,
                ContactWriteMarkerRecording.SET_SUCCEEDED,
            )
            assertEquals(uncertain, producer.contactWriteRecoveryState(radioA))
            assertEquals(cached, producer.getContact(radioA, cached.publicKey))
            val attempts = flags.attempts.toList()
            val sent = mock.sentData
            repeat(3) { assertEquals(uncertain, service().contactWriteRecoveryState(radioA)) }
            assertEquals(attempts, flags.attempts)
            assertEquals(sent, mock.sentData)
            mock.simulateOK()
            runCurrent()
            assertEquals(
                ContactWriteRecoveryState.Clear(radioA),
                producer.contactWriteRecoveryState(radioA),
            )
            assertEquals("Sent", producer.getContact(radioA, contactsKey(1))?.name)
            assertFalse(navigated)
            assertTrue(delivery.isCancelled)
            assertEquals(1, mock.sentData.count { it[0].toInt() == 0x09 })
        }
    }

    @Test
    fun failedMarkerVisibleInMemoryStaysPendingAndObservationDoesNotRetryOrRotateEpoch() = runTest {
        withFixture {
            flags.failSet = true
            assertFailsWith<IOException> { ContactWriteRecovery.require(flags, radioA) }
            assertTrue(flags.bool(marker(radioA)))
            assertFalse(flags.durable[marker(radioA)] ?: false)
            val attempts = flags.attempts.toList()
            val producer = service()
            repeat(3) {
                assertEquals(
                    ContactWriteRecoveryState.Required(
                        radioA, ContactWriteRecoveryReason.OPERATION_OUTCOME_UNAVAILABLE,
                        ContactWriteMarkerRecording.RETRY_PENDING,
                    ),
                    producer.contactWriteRecoveryState(radioA),
                )
            }
            assertEquals(attempts, flags.attempts)
            flags.failSet = false
            val epoch = ContactWriteRecovery.snapshot(flags, radioA)
            val committedAttempts = flags.attempts.toList()
            repeat(3) {
                assertEquals(
                    ContactWriteMarkerRecording.SET_SUCCEEDED,
                    assertIs<ContactWriteRecoveryState.Required>(producer.contactWriteRecoveryState(radioA)).markerRecording,
                )
            }
            assertSame(epoch, ContactWriteRecovery.snapshot(flags, radioA))
            assertEquals(committedAttempts, flags.attempts)
            assertTrue(flags.durable[marker(radioA)] == true)
            assertTrue(flags.attempts.all { it.second })
        }
    }

    @Test
    fun restoredFlagHasNoInventedOutcomeOrDurabilityAndMarkedSyncDoesNotFetch() = runTest {
        withFixture {
            flags.memory[marker(radioA)] = true
            val producer = service()
            val expected = ContactWriteRecoveryState.Required(
                radioA, ContactWriteRecoveryReason.STORED_MARKER_WITHOUT_PROVENANCE,
                ContactWriteMarkerRecording.LOADED_FLAG_UNVERIFIED,
            )
            assertEquals(expected, producer.contactWriteRecoveryState(radioA))
            assertTrue(flags.attempts.isEmpty())
            for (since in listOf(null, Instant.ofEpochSecond(50))) {
                assertFailsWith<ContactServiceError.SyncInterrupted> { producer.syncContacts(radioA, since) }
                assertEquals(expected, producer.contactWriteRecoveryState(radioA))
            }
            assertTrue(flags.attempts.isEmpty())
            assertEquals(0, mock.sentData.count { it[0].toInt() == 0x04 })
        }
    }

    @Test
    fun observationDoesNotTurnAnUnreadableFlagIntoClear() = runTest {
        withFixture {
            val failure = IOException("preference read failed")
            flags.readFailure = failure
            assertSame(failure, assertFailsWith<IOException> { service().contactWriteRecoveryState(radioA) })
            assertTrue(flags.attempts.isEmpty())
        }
    }

    @Test
    fun cachedContactsAndAnIndependentRadioRemainUsableWhileThisRadioIsRequired() = runTest {
        withFixture {
            val cached = ContactDTO.fromFrame(radioA, frame("Cached"))
            store.seed(cached)
            ContactWriteRecovery.require(flags, radioA)
            val producer = service()
            assertEquals(cached, producer.getContact(radioA, cached.publicKey))
            assertEquals(ContactWriteRecoveryState.Clear(radioB), producer.contactWriteRecoveryState(radioB))
            val other = async { producer.addOrUpdateContact(radioB, frame("Other radio")) }
            runCurrent()
            mock.simulateOK()
            runCurrent()
            other.await()
            assertEquals("Other radio", producer.getContact(radioB, contactsKey(1))?.name)
            assertEquals(cached, producer.getContact(radioA, cached.publicKey))
            assertIs<ContactWriteRecoveryState.Required>(producer.contactWriteRecoveryState(radioA))
            assertEquals(ContactWriteRecoveryState.Clear(radioB), producer.contactWriteRecoveryState(radioB))
            val sent = mock.sentData
            assertFailsWith<ContactWriteAdmissionBlocked> { producer.addOrUpdateContact(radioA, frame("Retry")) }
            assertEquals(sent, mock.sentData)
        }
    }

    @Test
    fun acceptedLocalSaveFailureIsDistinguishedButCannotMaskAnUnattributedObligation() = runTest {
        withFixture {
            val persistence = object : ContactPersisting by store {
                override suspend fun saveContactPreservingNewer(
                    radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
                ): ContactSaveResult? = throw IOException("local save failed")
            }
            val producer = service(persistence)
            val write = async {
                assertFailsWith<ContactWriteCompletionFailed> { producer.addOrUpdateContact(radioA, frame("Accepted")) }
            }
            runCurrent()
            mock.simulateOK()
            runCurrent()
            write.await()
            assertEquals(
                ContactWriteRecoveryReason.RADIO_ACCEPTED_RECONCILIATION_REQUIRED,
                assertIs<ContactWriteRecoveryState.Required>(producer.contactWriteRecoveryState(radioA)).reason,
            )
            ContactWriteRecovery.require(flags, radioA)
            assertEquals(
                ContactWriteRecoveryReason.OPERATION_OUTCOME_UNAVAILABLE,
                assertIs<ContactWriteRecoveryState.Required>(producer.contactWriteRecoveryState(radioA)).reason,
            )
            assertEquals(1, mock.sentData.count { it[0].toInt() == 0x09 })
        }
    }

    private fun frame(name: String, key: Int = 1) = ContactFrame(
        contactsKey(key), ContactType.CHAT, 0u, 255u, Bytes.EMPTY, name, 0u, 0.0, 0.0, 1u,
    )

    private fun marker(radioId: RadioId) = "contactWriteReconciliation.${radioId.value}"

    private suspend fun TestScope.withFixture(body: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try {
            fixture.session.start()
            fixture.body()
        } finally {
            fixture.session.stop()
        }
    }

    private inner class Fixture(scope: TestScope) {
        val flags = RecordingFlags()
        val store = ContactsFakeStore(listOf(contactsDevice(radioA), contactsDevice(radioB)))
        val mock = MockTransport()
        private val transport = object : MeshTransport by mock {
            override suspend fun send(data: Bytes) {
                mock.send(data)
                if (data[0].toInt() == 0x01) {
                    fun le32(value: Long) = Bytes(ByteArray(4) { (value ushr (8 * it)).toByte() })
                    mock.simulateReceive(
                        Bytes.of(0x05, 1, 22, 22) + contactsKey(0xF0) + le32(0) + le32(0) +
                            Bytes.of(0, 0, 0, 0) + le32(915_000) + le32(125_000) + Bytes.of(7, 5) +
                            Bytes.utf8("Recovery observation"),
                    )
                }
            }
        }
        private val clock = object : SessionClock {
            override val now: Duration get() = scope.testScheduler.currentTime.milliseconds
            override val wallClock: Clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC)
            override suspend fun sleepFor(duration: Duration) = delay(duration)
        }
        val session = MeshCoreSession(transport, clock = clock, coroutineContext = scope.backgroundScope.coroutineContext)
        fun service(persistence: ContactPersisting = store) =
            ContactService(session, store, persistence, null, null, flags)
    }

    private class RecordingFlags : ContactPreferenceFlags {
        val memory = mutableMapOf<String, Boolean>()
        val durable = mutableMapOf<String, Boolean>()
        val attempts = mutableListOf<Pair<String, Boolean>>()
        var failSet = false
        var readFailure: IOException? = null
        override fun bool(key: String): Boolean {
            readFailure?.let { throw it }
            return memory[key] ?: false
        }
        override fun set(key: String, value: Boolean) {
            attempts += key to value
            memory[key] = value
            if (failSet) throw IOException("marker disk write failed")
            durable[key] = value
        }
    }
}
