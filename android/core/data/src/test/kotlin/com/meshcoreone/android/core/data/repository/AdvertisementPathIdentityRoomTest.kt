// AndroidOnly: WP-209 Real Room path-response ambiguity, roster replacement and atomic recency controls.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactPersisting
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactResolution
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.session.AdvertisingSessionOps
import com.meshcoreone.android.core.protocol.session.SessionEventStreaming
import com.meshcoreone.android.core.runtime.EventBroadcaster
import com.meshcoreone.android.core.services.contacts.AdvertisementClock
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.AdvertisementLogSink
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvertisementPathIdentityRoomTest : RepositoryTest() {
    private val prefix = Bytes.fromHex("112233445566")
    private fun publicKey(tail: Int) = prefix + Bytes(ByteArray(26) { tail.toByte() })
    private fun row(tail: Int = 1, radio: RadioId = RADIO_A, id: UUID = UUID.randomUUID()) =
        contact(radio, id, publicKey(tail), "Peer $tail").copy(
            flags = 1u, lastModified = 101u, lastAdvertTimestamp = 99u,
            outPathLength = 1u, outPath = Bytes.of(0x10), lastHeardTimestamp = 50u,
        )
    private fun response() = PathInfo(prefix, 0x41u, Bytes.of(0xAB, 0xCD), 1u, Bytes.of(0xEF))

    @Test fun reversedInsertionOrderCannotSelectEitherCollidingLocalContactOrTouchDiscover() = runTest(scheduler) {
        for (reverse in listOf(false, true)) {
            val first = row()
            val second = row(2)
            val foreign = row(3, RADIO_B)
            (if (reverse) listOf(second, first) else listOf(first, second)).forEach { store.saveContact(it) }
            store.saveContact(foreign)
            withFixture {
                emitResponse()
                assertEquals(first, saved(first))
                assertEquals(second, saved(second))
                assertEquals(foreign, saved(foreign))
                assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
                assertEquals(0, intercepted.pathCalls)
                assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
                assertTrue(logs.any { it.first == DebugLogLevel.WARNING && "ambiguous" in it.second })
            }
            store.deleteContact(EntityKey(RADIO_A, first.id))
            store.deleteContact(EntityKey(RADIO_A, second.id))
            store.deleteContact(EntityKey(RADIO_B, foreign.id))
        }
    }

    @Test fun uniquePathUsesEncodedHashLengthAndPhoneClockWithoutReplacingContactMetadata() = runTest(scheduler) {
        val original = row().copy(
            name = "New name", nickname = "Private nickname", isFavorite = true, unreadCount = 7,
            latitude = 12.5, longitude = 20.5, lastModified = 900u, avatarImageData = Bytes.of(0x99),
        )
        val foreign = original.copy(radioId = RADIO_B, name = "Foreign")
        store.saveContact(original)
        store.saveContact(foreign)
        withFixture {
            emitResponse()
            val expected = original.copy(
                outPathLength = response().outPathLength, outPath = response().outPath,
                lastHeardTimestamp = AT.epochSecond.toUInt(),
            )
            assertEquals(expected, saved(original))
            assertEquals(foreign, saved(foreign))
            val discovered = store.fetchDiscoveredNodes(RADIO_A).single()
            assertEquals(original.publicKey, discovered.publicKey)
            assertEquals(AT, discovered.lastHeard)
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
            assertTrue(logs.none { it.first == DebugLogLevel.ERROR })
        }
    }

    @Test fun deletionAfterLookupCannotResurrectOrStampTheRemovedContact() = runTest(scheduler) {
        val original = row()
        store.saveContact(original)
        withFixture {
            val hold = intercepted.holdLookup()
            emitResponse(complete = false)
            assertTrue(hold.entered.isCompleted)
            store.deleteContact(EntityKey(RADIO_A, original.id))
            hold.release.complete(Unit)
            runCurrent()
            assertNull(saved(original))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
            assertTrue(logs.any { it.first == DebugLogLevel.WARNING && "changed" in it.second })
        }
    }

    @Test fun sameKeyReplacementWithANewIdentityDoesNotReceiveTheOldPathOrHeardStamp() = runTest(scheduler) {
        exerciseReplacement(sameId = false, sameKey = true)
    }

    @Test fun sameIdReplacementWithADifferentKeyDoesNotReceiveTheOldPathOrHeardStamp() = runTest(scheduler) {
        exerciseReplacement(sameId = true, sameKey = false)
    }

    @Test fun sameIdAndKeyDeletionRecreationInvalidatesTheProcessLocalObservation() = runTest(scheduler) {
        exerciseReplacement(sameId = true, sameKey = true)
    }

    @Test fun aNewerMetadataSaveAfterLookupIsNotOverwrittenByTheOlderObservation() = runTest(scheduler) {
        val original = row()
        store.saveContact(original)
        withFixture {
            val hold = intercepted.holdLookup()
            emitResponse(complete = false)
            assertTrue(hold.entered.isCompleted)
            val newer = original.copy(name = "Newer metadata", lastModified = 909u, latitude = 12.5)
            store.saveContact(newer)
            hold.release.complete(Unit)
            runCurrent()
            assertEquals(newer, saved(newer))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
            assertTrue(logs.any { it.first == DebugLogLevel.WARNING && "changed" in it.second })
        }
    }

    private suspend fun TestScope.exerciseReplacement(sameId: Boolean, sameKey: Boolean) {
        val original = row()
        store.saveContact(original)
        withFixture {
            val hold = intercepted.holdLookup()
            emitResponse(complete = false)
            assertTrue(hold.entered.isCompleted)
            store.deleteContact(EntityKey(RADIO_A, original.id))
            val replacement = row(
                tail = if (sameKey) 1 else 2, id = if (sameId) original.id else UUID.randomUUID(),
            ).copy(name = "Replacement", lastModified = 909u)
            store.saveContact(replacement)
            hold.release.complete(Unit)
            runCurrent()
            assertEquals(replacement, saved(replacement))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
            assertTrue(logs.any { it.first == DebugLogLevel.WARNING && "changed" in it.second })
        }
    }

    @Test fun aCollisionAddedAfterLookupPreventsBothPathAndLastHeardMutation() = runTest(scheduler) {
        val first = row()
        store.saveContact(first)
        withFixture {
            val hold = intercepted.holdLookup()
            emitResponse(complete = false)
            assertTrue(hold.entered.isCompleted)
            val second = row(2)
            store.saveContact(second)
            hold.release.complete(Unit)
            runCurrent()
            assertEquals(first, saved(first))
            assertEquals(second, saved(second))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
            assertTrue(logs.any { it.first == DebugLogLevel.WARNING && "ambiguous" in it.second })
        }
    }

    @Test fun absentContactKeepsRawPathResponseWithoutInventingContactOrDiscoverRows() = runTest(scheduler) {
        withFixture {
            emitResponse()
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(0, intercepted.pathCalls)
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
            assertTrue(logs.any { it.first == DebugLogLevel.INFO && "no matching" in it.second })
        }
    }

    @Test fun aReadFailureIsLoggedAsFailureNotNoMatchAndStillPreservesTheRawResponse() = runTest(scheduler) {
        val original = row()
        store.saveContact(original)
        withFixture {
            intercepted.lookupFailure = IOException("controlled prefix read failure")
            emitResponse()
            assertEquals(original, saved(original))
            assertEquals(0, intercepted.pathCalls)
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
            assertEquals(1, logs.count { it.first == DebugLogLevel.ERROR && "controlled prefix read failure" in it.second })
            assertTrue(logs.none { "no matching" in it.second })
        }
    }

    @Test fun realDiscoverInsertFailureRollsBackPathAndHeardTogetherAndIsNotReportedAsNoMatch() = runTest(scheduler) {
        val original = row()
        store.saveContact(original)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_path_discover BEFORE INSERT ON discovered_nodes " +
                "BEGIN SELECT RAISE(FAIL, 'controlled path discover failure'); END",
        )
        try {
            withFixture {
                emitResponse()
                assertEquals(original, saved(original))
                assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
                assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
                assertEquals(1, logs.count { it.first == DebugLogLevel.ERROR })
                assertTrue(logs.none { "no matching" in it.second })
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_path_discover")
        }
    }

    @Test fun anOlderPhoneClockDoesNotRegressContactRecencyOrRadioWatermark() = runTest(scheduler) {
        val original = row().copy(lastHeardTimestamp = AT.plusSeconds(10).epochSecond.toUInt())
        store.saveContact(original)
        withFixture {
            emitResponse()
            assertEquals(
                original.copy(outPathLength = response().outPathLength, outPath = response().outPath),
                saved(original),
            )
            assertEquals(AT, store.fetchDiscoveredNodes(RADIO_A).single().lastHeard)
            assertEquals(listOf(AdvertisementEvent.PathDiscoveryResponse(response())), events)
        }
    }

    @Test fun aSecondCollidingAdvertDuringGetContactPreventsMaterializationEntirely() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            val hold = source.holdContact()
            val materializing = async { service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A) }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            advert(2)
            hold.release.complete(Unit)
            assertEquals(LocalDirectContactResolution.Ambiguous, materializing.await())
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(0, intercepted.materializeCalls)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun aDifferentLocalKeyInsertedDuringGetContactIsNotOverwrittenOrAttributed() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            val hold = source.holdContact()
            val materializing = async { service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A) }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            val competing = row(2)
            store.saveContact(competing)
            hold.release.complete(Unit)
            assertEquals(LocalDirectContactResolution.Ambiguous, materializing.await())
            assertEquals(listOf(competing), store.fetchContacts(RADIO_A).toList())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(0, intercepted.materializeCalls)
        }
    }

    @Test fun aSameKeyLocalRowInsertedDuringGetContactKeepsAllItsNewerMetadata() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            val hold = source.holdContact()
            val materializing = async { service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A) }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            val newer = row().copy(name = "Newer", lastModified = 500u, isFavorite = true)
            store.saveContact(newer)
            hold.release.complete(Unit)
            assertEquals(newer, assertIs<LocalDirectContactResolution.Unique>(materializing.await()).contact)
            assertEquals(newer, saved(newer))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(0, intercepted.materializeCalls)
        }
    }

    @Test fun aDifferentLocalKeyInsertedAtTheConditionalInsertBoundaryStaysAmbiguous() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            val hold = intercepted.holdMaterialize()
            val materializing = async { service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A) }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            val competing = row(2)
            store.saveContact(competing)
            hold.release.complete(Unit)
            assertEquals(LocalDirectContactResolution.Ambiguous, materializing.await())
            assertEquals(listOf(competing), store.fetchContacts(RADIO_A).toList())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(1, intercepted.materializeCalls)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun aReturnedContactWithAnotherFullKeyIsStaleNotMaterialized() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(2)
            assertEquals(
                LocalDirectContactResolution.Stale,
                service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A),
            )
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(0, intercepted.materializeCalls)
            assertTrue(logs.any { it.first == DebugLogLevel.WARNING && "different full key" in it.second })
        }
    }

    @Test fun deletingAPendingKeyDuringGetContactDoesNotResurrectIt() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            val hold = source.holdContact()
            val materializing = async { service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A) }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            source.emit(MeshEvent.ContactDeleted(publicKey(1)))
            runCurrent()
            hold.release.complete(Unit)
            assertEquals(LocalDirectContactResolution.Stale, materializing.await())
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertEquals(0, intercepted.materializeCalls)
        }
    }

    @Test fun stoppingTheMonitorDuringGetContactCannotMaterializeItsOldPendingAdmission() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            val hold = source.holdContact()
            val materializing = async { service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A) }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            service.stopEventMonitoring()
            service.startEventMonitoring(RADIO_B)
            runCurrent()
            hold.release.complete(Unit)
            assertEquals(LocalDirectContactResolution.Stale, materializing.await())
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchContacts(RADIO_B).isEmpty())
            assertEquals(0, intercepted.materializeCalls)
        }
    }

    @Test fun cancelingGetContactPropagatesWithoutInsertingAndDoesNotPoisonTheNextAdmission() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            val hold = source.holdContact()
            val materializing = async { service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A) }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            materializing.cancelAndJoin()
            assertTrue(materializing.isCancelled)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertEquals(0, intercepted.materializeCalls)
            hold.release.complete(Unit)
            assertIs<LocalDirectContactResolution.Unique>(
                service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A),
            )
        }
    }

    @Test fun aMaterializationStoreFailurePropagatesInsteadOfBecomingNotFound() = runTest(scheduler) {
        withFixture {
            advert(1)
            source.contact = meshContact(1)
            intercepted.materializeFailure = IOException("controlled materialization failure")
            val failure = assertFailsWith<IOException> {
                service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A)
            }
            assertEquals("controlled materialization failure", failure.message)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @Test fun sameFullKeyInLocalAndPendingIsOneCandidateButAnotherPendingFullKeyIsAmbiguous() = runTest(scheduler) {
        val original = row()
        store.saveContact(original)
        withFixture {
            advert(1)
            val observed = (store as LocalDirectContactPersisting).classifyLocalDirectContact(RADIO_A, prefix)
            service.withPendingAdvertContactOperation(RADIO_A, prefix, observed) { admission ->
                assertEquals(original.copy(lastHeardTimestamp = AT.epochSecond.toUInt()),
                    assertIs<LocalDirectContactResolution.Unique>(admission.resolution).contact)
            }
            advert(2)
            service.withPendingAdvertContactOperation(RADIO_A, prefix, observed) { admission ->
                assertEquals(LocalDirectContactResolution.Ambiguous, admission.resolution)
                val orphan = message(contactID = null, direction = MessageDirection.INCOMING).copy(senderKeyPrefix = prefix)
                val saved = admission.saveIncomingDirectMessage(orphan)
                assertEquals(LocalDirectContactResolution.Ambiguous, saved.resolution)
                assertNull(saved.message.contactID)
                assertNull(store.fetchMessage(EntityKey(RADIO_A, saved.message.id))?.contactID)
            }
        }
    }

    @Test fun originallyAmbiguousOrStaleObservationsStayUnresolvedEvenWhenOnlyOneCandidateRemains() = runTest(scheduler) {
        store.saveContact(row())
        withFixture {
            for (observed in listOf(LocalDirectContactResolution.Ambiguous, LocalDirectContactResolution.Stale)) {
                service.withPendingAdvertContactOperation(RADIO_A, prefix, observed) { admission ->
                    assertEquals(observed, admission.resolution)
                }
            }
        }
    }

    @Test fun aReplacedRadioCannotUseTheNewRadiosPendingKeysToAdmitAnOldRadioCandidate() = runTest(scheduler) {
        val original = row()
        store.saveContact(original)
        withFixture {
            advert(1)
            val observed = (store as LocalDirectContactPersisting).classifyLocalDirectContact(RADIO_A, prefix)
            service.stopEventMonitoring()
            service.startEventMonitoring(RADIO_B)
            runCurrent()
            advert(2)
            assertEquals(
                LocalDirectContactResolution.Stale,
                service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A),
            )
            service.withPendingAdvertContactOperation(RADIO_A, prefix, observed) { admission ->
                assertEquals(LocalDirectContactResolution.Stale, admission.resolution)
                val orphan = message(contactID = null, direction = MessageDirection.INCOMING).copy(senderKeyPrefix = prefix)
                val saved = admission.saveIncomingDirectMessage(orphan)
                assertEquals(LocalDirectContactResolution.Stale, saved.resolution)
                assertNull(saved.message.contactID)
            }
            assertEquals(0, intercepted.materializeCalls)
            assertTrue(source.requestedContacts.isEmpty())
        }
    }

    @Test fun aStoppedMonitorCannotAdmitOrMaterializeAnExistingLocalCandidate() = runTest(scheduler) {
        store.saveContact(row())
        withFixture {
            val observed = (store as LocalDirectContactPersisting).classifyLocalDirectContact(RADIO_A, prefix)
            service.stopEventMonitoring()
            assertEquals(
                LocalDirectContactResolution.Stale,
                service.materializeLocalDirectContactForPendingAdvert(prefix, RADIO_A),
            )
            service.withPendingAdvertContactOperation(RADIO_A, prefix, observed) { admission ->
                assertEquals(LocalDirectContactResolution.Stale, admission.resolution)
            }
            assertTrue(source.requestedContacts.isEmpty())
        }
    }

    @Test fun pendingGateKeepsNewAdvertRecordingOutsideTheWholeConsumerAdmission() = runTest(scheduler) {
        val original = row()
        store.saveContact(original)
        withFixture {
            advert(1)
            val observed = (store as LocalDirectContactPersisting).classifyLocalDirectContact(RADIO_A, prefix)
            val hold = holdOperation()
            val operation = async {
                service.withPendingAdvertContactOperation(RADIO_A, prefix, observed) { admission ->
                    assertIs<LocalDirectContactResolution.Unique>(admission.resolution)
                    hold.await()
                }
            }
            runCurrent()
            assertTrue(hold.entered.isCompleted)
            val before = source.handled
            source.emit(MeshEvent.Advertisement(publicKey(2)))
            runCurrent()
            assertEquals(before, source.handled, "A pending mutation must wait until the consumer scope releases")
            operation.cancelAndJoin()
            runCurrent()
            assertEquals(before + 1, source.handled, "Canceling a callback must release the pending gate")
            service.withPendingAdvertContactOperation(RADIO_A, prefix, observed) { admission ->
                assertEquals(LocalDirectContactResolution.Ambiguous, admission.resolution)
            }
        }
    }

    private fun meshContact(tail: Int) = MeshContact(
        publicKey(tail).hexString, publicKey(tail), ContactType.CHAT, ContactFlags(0u),
        0u, Bytes.EMPTY, "Radio $tail", Instant.ofEpochSecond(99), 0.0, 0.0, Instant.ofEpochSecond(101),
    )

    private suspend fun saved(contact: ContactDTO) = store.fetchContact(EntityKey(contact.radioId, contact.id))

    private suspend fun TestScope.withFixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try {
            fixture.start()
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Hold {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() {
            entered.complete(Unit)
            release.await()
        }
    }

    private class InterceptedStore(private val actual: PersistenceStoreProtocol) :
        PersistenceStoreProtocol by actual, LocalDirectContactPersisting by (actual as LocalDirectContactPersisting) {
        private val direct = actual as LocalDirectContactPersisting
        private var lookupHold: Hold? = null
        private var materializeHold: Hold? = null
        var lookupFailure: Exception? = null
        var materializeFailure: Exception? = null
        var pathCalls = 0
        var materializeCalls = 0
        fun holdLookup() = Hold().also { lookupHold = it }
        fun holdMaterialize() = Hold().also { materializeHold = it }
        fun releaseHolds() {
            lookupHold?.release?.complete(Unit)
            materializeHold?.release?.complete(Unit)
        }
        override suspend fun classifyLocalDirectContact(radioId: RadioId, prefix: Bytes): LocalDirectContactResolution {
            lookupFailure?.let { throw it }
            val observed = direct.classifyLocalDirectContact(radioId, prefix)
            lookupHold?.await()
            return observed
        }
        override suspend fun applyLocalDirectPathIfCurrent(
            radioId: RadioId, prefix: Bytes, observed: LocalDirectContactResolution,
            outPathLength: UByte, outPath: Bytes, heardAt: Instant,
        ): LocalDirectContactResolution {
            pathCalls++
            return direct.applyLocalDirectPathIfCurrent(radioId, prefix, observed, outPathLength, outPath, heardAt)
        }
        override suspend fun materializeLocalDirectContactIfAbsent(
            radioId: RadioId, prefix: Bytes, frame: ContactFrame, heardAt: Instant,
        ): LocalDirectContactResolution {
            materializeCalls++
            materializeFailure?.let { throw it }
            materializeHold?.await()
            return direct.materializeLocalDirectContactIfAbsent(radioId, prefix, frame, heardAt)
        }
    }

    private inner class Fixture(private val test: TestScope) {
        val source = Source()
        val intercepted = InterceptedStore(store)
        val events = mutableListOf<AdvertisementEvent>()
        val logs = mutableListOf<Pair<DebugLogLevel, String>>()
        private val operationHolds = mutableListOf<Hold>()
        private var listener: Job? = null
        val service = AdvertisementService(
            source, source, intercepted, test.backgroundScope,
            clock = object : AdvertisementClock {
                override fun now(): Duration = scheduler.currentTime.milliseconds
                override fun wallNow(): Instant = AT
                override suspend fun sleepUntil(deadline: Duration) {
                    if (deadline > now()) delay(deadline - now())
                }
            },
            logSink = AdvertisementLogSink { _, level, message -> logs += level to message },
        )
        fun start() {
            val stream = service.events()
            listener = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.toList(events) }
            service.startEventMonitoring(RADIO_A)
            test.runCurrent()
        }
        fun emitResponse(complete: Boolean = true) {
            source.emit(MeshEvent.PathResponse(response()))
            test.runCurrent()
            if (complete) assertEquals(1, source.handled)
        }
        fun advert(tail: Int) {
            val before = source.handled
            source.emit(MeshEvent.Advertisement(publicKey(tail)))
            test.runCurrent()
            assertEquals(before + 1, source.handled)
        }
        fun holdOperation() = Hold().also { operationHolds += it }
        suspend fun close() {
            intercepted.releaseHolds()
            source.releaseHolds()
            operationHolds.forEach { it.release.complete(Unit) }
            service.stopEventMonitoring()
            service.finishEvents()
            listener?.cancelAndJoin()
        }
    }

    private class Source : AdvertisingSessionOps, SessionEventStreaming {
        private val broadcaster = EventBroadcaster<MeshEvent>()
        var handled = 0
        var contact: MeshContact? = null
        val requestedContacts = mutableListOf<Bytes>()
        private var contactHold: Hold? = null
        fun holdContact() = Hold().also { contactHold = it }
        fun releaseHolds() { contactHold?.release?.complete(Unit) }
        override val connectionState: Flow<ConnectionState> = flowOf(ConnectionState.Connected)
        override fun events(): Flow<MeshEvent> = events(EventFilter { true })
        override fun events(filter: EventFilter): Flow<MeshEvent> {
            val subscription = broadcaster.subscribe()
            return flow {
                subscription.events.filter(filter::matches).collect {
                    emit(it)
                    handled++
                }
            }
        }
        fun emit(event: MeshEvent) = broadcaster.yield(event)
        override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): Nothing = unexpected()
        override suspend fun sendAdvertisement(flood: Boolean): Nothing = unexpected()
        override suspend fun setName(name: String): Nothing = unexpected()
        override suspend fun setCoordinates(latitude: Double, longitude: Double): Nothing = unexpected()
        override suspend fun getContact(publicKey: Bytes): MeshContact? {
            requestedContacts += publicKey
            contactHold?.await()
            return contact
        }
        private fun unexpected(): Nothing = error("Path response handling must not issue radio commands")
    }
}
