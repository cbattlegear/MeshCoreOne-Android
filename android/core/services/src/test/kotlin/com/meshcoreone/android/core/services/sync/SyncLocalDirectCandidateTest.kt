// AndroidOnly: WP-214 Nonactivated local direct receive composition controls.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactAdmission
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactResolution
import com.meshcoreone.android.core.contracts.domain.LocalDirectMessageEnvelope
import com.meshcoreone.android.core.contracts.domain.LocalDirectMessagePolling
import com.meshcoreone.android.core.contracts.domain.LocalDirectMessageSaveResult
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CandidatePolling : MockMessagePollingService(), LocalDirectMessagePolling {
    var candidateHandler: (suspend (LocalDirectMessageEnvelope) -> Unit)? = null

    override suspend fun setLocalDirectMessageHandler(handler: suspend (LocalDirectMessageEnvelope) -> Unit) {
        candidateHandler = handler
        capturedContactMessageHandler = null
    }
}

private class CandidateAdverts(
    legacy: SyncAdvertisementServicing = FakeAdvertisementService(),
) : SyncAdvertisementServicing by legacy, SyncLocalDirectAdvertisementServicing {
    var admitted: LocalDirectContactResolution? = null
    var admissionFailure: Exception? = null
    var beforeSave: suspend (MessageDTO) -> Unit = {}
    val materializations = mutableListOf<Pair<RadioId, Bytes>>()
    val observations = mutableListOf<LocalDirectContactResolution>()
    val saved = mutableListOf<MessageDTO>()
    var scopesExited = 0

    override suspend fun materializeContactForPendingAdvert(
        prefix: Bytes,
        radioId: RadioId,
    ): ContactDTO? {
        materializations += radioId to prefix
        error("Candidate delivery must not materialize a contact")
    }

    override suspend fun <T> withPendingAdvertContactOperation(
        radioId: RadioId,
        prefix: Bytes,
        observed: LocalDirectContactResolution,
        block: suspend (LocalDirectContactAdmission) -> T,
    ): T {
        observations += observed
        admissionFailure?.let { throw it }
        val admission = object : LocalDirectContactAdmission {
            override val resolution = admitted ?: observed

            override suspend fun saveIncomingDirectMessage(dto: MessageDTO): LocalDirectMessageSaveResult {
                assertEquals(radioId, dto.radioId)
                assertEquals(prefix, dto.senderKeyPrefix)
                beforeSave(dto)
                val existing = saved.firstOrNull { it.radioId == dto.radioId && it.id == dto.id }
                if (existing != null) return LocalDirectMessageSaveResult(existing, resolution, false)
                saved += dto
                return LocalDirectMessageSaveResult(dto, resolution, true)
            }
        }
        return try {
            block(admission)
        } finally {
            scopesExited++
        }
    }
}

private fun candidateSession(radioId: RadioId) =
    SessionToken(ProcessEpoch(UUID(0, 1)), Generation(1), radioId)

class SyncLocalDirectCandidateTest {
    @TestFactory
    fun candidateComposition(): List<DynamicTest> = listOf(
        nativeCase("normal wiring stays legacy until candidate composition is explicitly installed") {
            val radioId = newRadio()
            val polling = CandidatePolling()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId), polling = polling)
            val adverts = CandidateAdverts()
            val dependencies = services.dependencies().copy(advertisementService = adverts)
            val coordinator = coordinator()
            coordinator.wireMessageHandlers(dependencies, radioId)
            assertNotNull(polling.capturedContactMessageHandler)
            assertNull(polling.candidateHandler)

            coordinator.wireLocalDirectCandidateForTest(dependencies, candidateSession(radioId))
            assertNotNull(polling.candidateHandler)
            assertNull(polling.capturedContactMessageHandler)
            assertNotNull(polling.capturedChannelMessageHandler)
            assertNotNull(polling.capturedSignedMessageHandler)
            assertNotNull(polling.capturedCLIMessageHandler)
        },
        nativeCase("all observations enter admission unchanged without materializing a contact") {
            val radioId = newRadio()
            val polling = CandidatePolling()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId), polling = polling)
            val adverts = CandidateAdverts()
            val contact = testContact(radioId, "Candidate", key(0x31))
            val unique = LocalDirectContactResolution.Unique(contact, UUID(0, 9))
            val session = candidateSession(radioId)
            coordinator().wireLocalDirectCandidateForTest(
                services.dependencies().copy(advertisementService = adverts), session,
            )
            val handler = checkNotNull(polling.candidateHandler)
            val message = contactMessage(contact.publicKey.prefix(6), "same text", clock.now())
            val observations = listOf(
                LocalDirectContactResolution.NotFound,
                LocalDirectContactResolution.Ambiguous,
                LocalDirectContactResolution.Stale,
                unique,
            )
            observations.forEachIndexed { index, resolution ->
                handler(LocalDirectMessageEnvelope(UUID(0, index.toLong() + 1), session, message, DeliveryContext.Live, resolution))
            }

            assertTrue(adverts.materializations.isEmpty())
            assertEquals(observations, adverts.observations)
            assertEquals(4, adverts.saved.size)
            assertEquals(4, adverts.saved.map { it.id }.toSet().size)
            assertTrue(adverts.saved.all { it.text == "same text" && it.deduplicationKey == null })
            assertTrue(adverts.saved.take(3).all { it.contactID == null })
            assertEquals(contact.id, adverts.saved.last().contactID)

            adverts.admitted = LocalDirectContactResolution.Ambiguous
            handler(LocalDirectMessageEnvelope(UUID(0, 5), session, message, DeliveryContext.Live, unique))
            assertEquals(unique, adverts.observations.last())
            assertNull(adverts.saved.last().contactID)
        },
        nativeCase("candidate saves original reaction-shaped text without contact side effects") {
            val radioId = newRadio()
            val polling = CandidatePolling()
            val store = SyncInMemoryStore.createTestDataStore(radioId, nodeName = "Me")
            val contact = testContact(radioId, "Candidate", key(0x32))
            store.saveContact(contact)
            val services = SyncTestServices(store, polling = polling)
            val adverts = CandidateAdverts()
            val dmParser = object : SyncReactionParsing by noReactionParsing {
                override fun parseDM(text: String) = SyncParsedDMReaction("heart", "hash")
            }
            val dependencies = services.dependencies(codecs = fakeCodecs.copy(reactionParser = dmParser))
                .copy(advertisementService = adverts)
            val coordinator = coordinator()
            val session = candidateSession(radioId)
            coordinator.wireLocalDirectCandidateForTest(dependencies, session)
            val received = ValueTracker<SyncDataEvent>()
            val events = coordinator.dataEvents()
            val collector = launch { events.collect { received.record(it) } }
            val occurrence = UUID(0, 30)
            val text = "reaction-shaped @[Me] \uD83C\uDF0D"
            val message = contactMessage(contact.publicKey.prefix(6), text, Instant.ofEpochSecond(1000))
            checkNotNull(polling.candidateHandler)(
                LocalDirectMessageEnvelope(
                    occurrence, session, message, DeliveryContext.Live,
                    LocalDirectContactResolution.Unique(contact, UUID(0, 10)),
                ),
            )
            settle()

            val saved = adverts.saved.single()
            assertEquals(occurrence, saved.id)
            assertEquals(text, saved.text)
            assertEquals(contact.id, saved.contactID)
            assertTrue(saved.timestampCorrected)
            assertEquals(1000u, saved.senderTimestamp)
            assertNull(saved.deduplicationKey)
            assertTrue(saved.containsSelfMention)
            assertFalse("fetchMessageByDeduplicationKey" in store.calls)
            assertFalse("touchContactHeard" in store.calls)
            assertFalse("saveMessage" in store.calls)
            assertTrue(services.reactions.indexed.isEmpty())
            assertTrue(services.reactions.queued.isEmpty())
            assertTrue(services.notifications.posts.isEmpty())
            assertEquals(0, services.notifications.badgeUpdates)
            assertEquals(0L, coordinator.conversationsVersion)
            assertTrue(received.values.isEmpty())
            collector.cancel()
        },
        nativeCase("candidate does not infer a route from a colliding RX log") {
            for (timestampMatches in listOf(true, false)) {
                val radioId = newRadio()
                val polling = CandidatePolling()
                val store = SyncInMemoryStore.createTestDataStore(radioId)
                val services = SyncTestServices(store, polling = polling)
                val adverts = CandidateAdverts()
                val session = candidateSession(radioId)
                val prefix = key(0x36).prefix(6)
                val stamp = clock.now().epochSecond.toUInt()
                val unrelated = groupTextRX(
                    radioId, 0u, if (timestampMatches) stamp else stamp - 1u,
                    Bytes.of(0x11, 0x22), clock.now(), regionScope = "unrelated",
                ).copy(
                    channelIndex = null,
                    payloadType = PayloadType.TEXT_MESSAGE,
                    routeType = RouteType.DIRECT,
                    packetPayload = Bytes.of(0x99, prefix[0].toInt()),
                )
                store.saveRxLogEntry(unrelated)
                val legacyMatch = if (timestampMatches) {
                    store.findRxLogEntry(radioId, null, stamp)
                } else {
                    store.findRxLogEntryBySenderPrefix(radioId, prefix[0], clock.now().minusSeconds(30))
                }
                assertEquals(unrelated.id, legacyMatch?.id)
                store.calls.clear()
                coordinator().wireLocalDirectCandidateForTest(
                    services.dependencies().copy(advertisementService = adverts), session,
                )
                val message = contactMessage(prefix, "keep wire metadata", clock.now())
                    .copy(pathLength = 255u, snr = -9.25)
                checkNotNull(polling.candidateHandler)(
                    LocalDirectMessageEnvelope(
                        UUID(0, if (timestampMatches) 31 else 32), session, message,
                        DeliveryContext.Live, LocalDirectContactResolution.Ambiguous,
                    ),
                )

                val saved = adverts.saved.single()
                assertEquals("keep wire metadata", saved.text)
                assertEquals(prefix, saved.senderKeyPrefix)
                assertEquals(255u.toUByte(), saved.pathLength)
                assertEquals(message.snr, saved.snr)
                assertNull(saved.pathNodes)
                assertNull(saved.routeType)
                assertNull(saved.regionScope)
                assertTrue(saved.regionScopeMatches.isEmpty())
                assertFalse("findRxLogEntry" in store.calls)
                assertFalse("findRxLogEntryBySenderPrefix" in store.calls)
                assertFalse("touchContactHeard" in store.calls)
                assertTrue(adverts.materializations.isEmpty())
                assertTrue(services.notifications.posts.isEmpty())
                assertTrue(services.reactions.indexed.isEmpty())
                assertTrue(services.reactions.queued.isEmpty())
            }
        },
        nativeCase("the same occurrence can retry but distinct equal messages reach admission separately") {
            val radioId = newRadio()
            val polling = CandidatePolling()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId), polling = polling)
            val adverts = CandidateAdverts()
            val session = candidateSession(radioId)
            coordinator().wireLocalDirectCandidateForTest(
                services.dependencies().copy(advertisementService = adverts), session,
            )
            val handler = checkNotNull(polling.candidateHandler)
            val message = contactMessage(key(0x33).prefix(6), "identical", clock.now())
            val first = LocalDirectMessageEnvelope(UUID(0, 40), session, message, DeliveryContext.Live, LocalDirectContactResolution.Ambiguous)
            handler(first)
            handler(first)
            handler(first.copy(occurrenceID = UUID(0, 41)))

            assertEquals(listOf(UUID(0, 40), UUID(0, 41)), adverts.saved.map { it.id })
            assertEquals(3, adverts.scopesExited)
            assertTrue(adverts.materializations.isEmpty())
        },
        nativeCase("mismatched radio or generation is rejected before admission") {
            val radioId = newRadio()
            val polling = CandidatePolling()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId), polling = polling)
            val adverts = CandidateAdverts()
            val session = candidateSession(radioId)
            coordinator().wireLocalDirectCandidateForTest(
                services.dependencies().copy(advertisementService = adverts), session,
            )
            val handler = checkNotNull(polling.candidateHandler)
            val message = contactMessage(key(0x34).prefix(6), "stale", clock.now())
            for (other in listOf(session.copy(generation = Generation(2)), session.copy(radioId = newRadio()))) {
                assertFailsWith<IllegalArgumentException> {
                    handler(LocalDirectMessageEnvelope(UUID(0, 50), other, message, DeliveryContext.Live, LocalDirectContactResolution.NotFound))
                }
            }
            assertTrue(adverts.materializations.isEmpty())
            assertTrue(adverts.observations.isEmpty())
            assertTrue(adverts.saved.isEmpty())
        },
        nativeCase("storage failure propagates and cancellation exits the candidate scope") {
            val radioId = newRadio()
            val polling = CandidatePolling()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId), polling = polling)
            val adverts = CandidateAdverts()
            val session = candidateSession(radioId)
            coordinator().wireLocalDirectCandidateForTest(
                services.dependencies().copy(advertisementService = adverts), session,
            )
            val handler = checkNotNull(polling.candidateHandler)
            val message = contactMessage(key(0x35).prefix(6), "preserve", clock.now())
            val envelope = LocalDirectMessageEnvelope(UUID(0, 60), session, message, DeliveryContext.Live, LocalDirectContactResolution.Ambiguous)
            adverts.admissionFailure = IllegalStateException("advert admission unavailable")
            val advertFailure = assertFailsWith<IllegalStateException> {
                handler(envelope.copy(resolution = LocalDirectContactResolution.NotFound))
            }
            assertEquals("advert admission unavailable", advertFailure.message)
            assertEquals(listOf<LocalDirectContactResolution>(LocalDirectContactResolution.NotFound), adverts.observations)
            assertTrue(adverts.materializations.isEmpty())
            adverts.admissionFailure = null
            adverts.beforeSave = { throw IllegalStateException("store unavailable") }
            val failure = assertFailsWith<IllegalStateException> { handler(envelope) }
            assertEquals("store unavailable", failure.message)
            assertEquals(1, adverts.scopesExited)
            assertTrue(adverts.saved.isEmpty())

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            adverts.beforeSave = {
                entered.complete(Unit)
                release.await()
            }
            val delivery = launch { handler(envelope) }
            entered.await()
            assertFalse(delivery.isCompleted)
            delivery.cancelAndJoin()
            release.complete(Unit)
            assertTrue(delivery.isCancelled)
            assertEquals(2, adverts.scopesExited)
            assertTrue(adverts.saved.isEmpty())
            assertTrue(services.notifications.posts.isEmpty())
        },
        nativeCase("missing opt-in capabilities fail before installing a handler") {
            val radioId = newRadio()
            val session = candidateSession(radioId)
            val ordinary = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            assertFailsWith<IllegalStateException> {
                coordinator().wireLocalDirectCandidateForTest(ordinary.dependencies(), session)
            }

            val polling = CandidatePolling()
            val noAdverts = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId), polling = polling)
            assertFailsWith<IllegalStateException> {
                coordinator().wireLocalDirectCandidateForTest(noAdverts.dependencies(), session)
            }
            assertNull(polling.candidateHandler)
        },
    )
}
