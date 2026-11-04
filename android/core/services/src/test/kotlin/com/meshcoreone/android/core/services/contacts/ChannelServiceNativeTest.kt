// AndroidOnly: WP-209 native cancellation, concurrency, retry-timing and error-mapping cases for the ChannelService port.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ChannelServiceNativeTest {
    @TestFactory
    fun concurrencyAndCancellation(): List<DynamicTest> = channelsNativeCases(
        "a sync in progress rejects concurrent syncs and retries, then releases the guard" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            val gate = CompletableDeferred<Unit>()
            session.script(0u, { gate.await(); ChannelInfo(0u, "ch0", channelsSecret(0xAB)) })
            val service = channelServiceFor(session, ChannelsInMemoryStore(), radioId, clock = ChannelsRecordingClock())
            val first = async { service.syncChannels(radioId, 1u, usePipelinedRead = false) }
            channelsWaitUntil("first sync should be reading slot 0") { session.recordedGetChannelIndices.isNotEmpty() }

            assertFailsWith<ChannelServiceError.SyncAlreadyInProgress> { service.syncChannels(radioId, 1u, usePipelinedRead = true) }
            assertFailsWith<ChannelServiceError.SyncAlreadyInProgress> { service.retryFailedChannels(radioId, SnapshotList.of(0u)) }
            assertFailsWith<ChannelServiceError.SyncAlreadyInProgress> { service.retryFailedChannels(radioId, SnapshotList.empty()) }

            gate.complete(Unit)
            assertEquals(1, first.await().channelsSynced)
            assertEquals(0, service.syncChannels(radioId, 1u, usePipelinedRead = false).channelsSynced)
        },
        "cancelling a sync mid-read propagates cancellation unclassified, persists nothing and frees the guard" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            session.script(0u, { awaitCancellation() })
            val store = ChannelsInMemoryStore()
            val service = channelServiceFor(session, store, radioId, clock = ChannelsRecordingClock())
            val sync = async { service.syncChannels(radioId, 3u, usePipelinedRead = false) }
            channelsWaitUntil("sync should be reading slot 0") { session.recordedGetChannelIndices.isNotEmpty() }

            sync.cancel()
            sync.join()

            assertTrue(sync.isCancelled)
            assertEquals(listOf<UByte>(0u), session.recordedGetChannelIndices, "no later slot is read after cancellation")
            assertTrue(store.fetchChannels(radioId).isEmpty())
            assertEquals(0, service.syncChannels(radioId, 1u, usePipelinedRead = false).channelsSynced)
        },
        "cancelling during timeout backoff is not swallowed as a sync error" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            session.script(0u, { throw MeshCoreException.Timeout() })
            val sleeping = AtomicBoolean(false)
            val clock = ChannelServiceClock { sleeping.set(true); awaitCancellation() }
            val service = channelServiceFor(session, ChannelsInMemoryStore(), radioId, clock = clock)
            val sync = async { service.syncChannels(radioId, 2u, usePipelinedRead = false) }
            channelsWaitUntil("fetch should be backing off") { sleeping.get() }

            sync.cancel()
            sync.join()

            assertTrue(sync.isCancelled)
            assertEquals(listOf<UByte>(0u), session.recordedGetChannelIndices)
        },
        "cancelling a retry during its initial delay frees the guard" to {
            val radioId = channelsRadioId()
            val sleeping = AtomicBoolean(false)
            val radioId = channelsRadioId()
            val service = channelServiceFor(
                ChannelsMockSession(), ChannelsInMemoryStore(), radioId,
                clock = ChannelServiceClock { sleeping.set(true); awaitCancellation() },
            )
            val retry = async { service.retryFailedChannels(radioId, SnapshotList.of(1u)) }
            channelsWaitUntil("retry should be waiting") { sleeping.get() }
            retry.cancel()
            retry.join()
            assertTrue(retry.isCancelled)
            assertEquals(0, service.syncChannels(radioId, 1u, usePipelinedRead = false).channelsSynced)
        },
    )

    @TestFactory
    fun fetchAndClassification(): List<DynamicTest> = channelsNativeCases(
        "fetchChannel retries timeouts with jittered exponential backoff then reports the timeout" to {
            val session = ChannelsMockSession()
            session.script(0u, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() })
            val clock = ChannelsRecordingClock()
            val service = channelServiceFor(session, ChannelsInMemoryStore(), clock = clock, random = Random(SEED))
            val expected = Random(SEED).let { listOf(500 + it.nextInt(-100, 101), 1000 + it.nextInt(-100, 101)) }.map { it.milliseconds }

            val failure = assertFailsWith<ChannelServiceError.SessionError> { service.fetchChannel(0u) }

            assertIs<MeshCoreException.Timeout>(failure.error)
            assertEquals(expected, clock.sleeps)
            assertTrue(clock.sleeps.all { it in 400.milliseconds..1100.milliseconds })
            assertEquals(listOf<UByte>(0u, 0u, 0u), session.recordedGetChannelIndices)
        },
        "a timeout followed by a reply recovers after one backoff" to {
            val session = ChannelsMockSession()
            session.script(4u, { throw MeshCoreException.Timeout() }, { ChannelInfo(4u, "four", channelsSecret(0x44)) })
            val clock = ChannelsRecordingClock()
            val service = channelServiceFor(session, ChannelsInMemoryStore(), clock = clock)
            assertEquals(ChannelInfo(4u, "four", channelsSecret(0x44)), service.fetchChannel(4u))
            assertEquals(1, clock.sleeps.size)
        },
        "not-found replies read as unconfigured; mismatched indices and other device errors classify like the source" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            session.script(0u, { throw MeshCoreException.DeviceError(2u) })
            session.script(1u, { ChannelInfo(2u, "wrong", channelsSecret(0x22)) })
            session.script(2u, { throw MeshCoreException.DeviceError(6u) })
            session.script(3u, { ChannelInfo(3u, "", channelsSecret(0x33)) })
            session.script(4u, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() })
            val store = ChannelsInMemoryStore()
            val service = channelServiceFor(session, store, radioId, clock = ChannelsRecordingClock())

            val result = service.syncChannels(radioId, 5u, usePipelinedRead = false)

            assertEquals(1, result.channelsSynced, "the empty-name non-zero-secret slot is configured")
            assertEquals(
                listOf(
                    ChannelSyncError(1u, ChannelSyncErrorType.Unknown, "Invalid channel index."),
                    ChannelSyncError(2u, ChannelSyncErrorType.DeviceError(6u), "Device returned error code 6"),
                    ChannelSyncError(4u, ChannelSyncErrorType.Timeout, "Request timed out"),
                ),
                result.errors.toList(),
            )
            assertEquals(listOf<UByte>(4u), result.retryableIndices.toList())
            assertEquals(listOf<UByte>(3u), store.fetchChannels(radioId).map { it.index })
        },
        "platform transport failures count toward the breaker only through the injected classifier" to {
            val radioId = channelsRadioId()
            val unclassified = ChannelsMockSession().apply { stubbedGetChannelError = IllegalStateException("ble link dropped") }
            val plain = channelServiceFor(unclassified, ChannelsInMemoryStore(), radioId, clock = ChannelsRecordingClock())
            val plainResult = plain.syncChannels(radioId, 6u, usePipelinedRead = false)
            assertEquals(List(6) { ChannelSyncErrorType.Unknown }, plainResult.errors.map { it.errorType })

            val classified = ChannelsMockSession().apply { stubbedGetChannelError = IllegalStateException("ble link dropped") }
            val service = channelServiceFor(
                classified, ChannelsInMemoryStore(), radioId, clock = ChannelsRecordingClock(),
                transportClassifier = { if (it is IllegalStateException) ChannelSyncErrorType.TransportError else null },
            )
            val result = service.syncChannels(radioId, 6u, usePipelinedRead = false)
            assertEquals(List(3) { ChannelSyncErrorType.TransportError } + List(3) { ChannelSyncErrorType.CircuitBreaker }, result.errors.map { it.errorType })
            assertEquals(listOf<UByte>(0u, 1u, 2u), classified.recordedGetChannelIndices)
        },
        "a failed batch persist reports database errors for every configured slot" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            session.setStubbedChannels(mapOf(1.toUByte() to ChannelInfo(1u, "one", channelsSecret(0x11))))
            val store = ChannelsInMemoryStore().apply { batchSaveFailure = IllegalStateException("disk full") }
            val capture = ChannelsSlotCapture()
            val cache = ChannelsDecryptionCapture()
            val service = channelServiceFor(session, store, radioId, cache, ChannelsRecordingClock())
            service.setSlotOccupantChangedHandler(capture.handler)

            val result = service.syncChannels(radioId, 2u, usePipelinedRead = true)

            assertEquals(ChannelSyncResult(0, SnapshotList.of(ChannelSyncError(1u, ChannelSyncErrorType.DatabaseError, "Batch persist failed: disk full"))), result)
            assertTrue(capture.received.isEmpty())
            assertTrue(cache.indexUpdates.isEmpty())
            assertTrue(store.fetchChannels(radioId).isEmpty())
        },
    )

    @TestFactory
    fun retries(): List<DynamicTest> = channelsNativeCases(
        "retryFailedChannels waits, upserts recovered slots only, and opens its breaker after two failures" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            store.saveChannel(radioId, ChannelInfo(3u, "three", channelsSecret(0x11)))
            store.saveChannel(radioId, ChannelInfo(6u, "six", channelsSecret(0x66)))
            val session = ChannelsMockSession()
            session.setStubbedChannels(mapOf(3.toUByte() to ChannelInfo(3u, "threeB", channelsSecret(0x99))))
            repeat(3) { session.script(1u, { throw MeshCoreException.Timeout() }) }
            repeat(3) { session.script(2u, { throw MeshCoreException.Timeout() }) }
            val clock = ChannelsRecordingClock()
            val capture = ChannelsSlotCapture()
            val cache = ChannelsDecryptionCapture()
            val service = channelServiceFor(session, store, radioId, cache, clock, Random(SEED))
            service.setSlotOccupantChangedHandler(capture.handler)

            val result = service.retryFailedChannels(radioId, SnapshotList.of(3u, 1u, 2u, 4u, 5u))

            assertEquals(1, result.channelsSynced)
            assertEquals(
                listOf(1u, 2u, 4u, 5u).map { it.toUByte() } to
                    listOf(ChannelSyncErrorType.Timeout, ChannelSyncErrorType.Timeout, ChannelSyncErrorType.CircuitBreaker, ChannelSyncErrorType.CircuitBreaker),
                result.errors.map { it.index } to result.errors.map { it.errorType },
            )
            assertEquals("Skipped due to retry circuit breaker", result.errors.last().description)
            assertEquals(500.milliseconds, clock.sleeps.first())
            assertEquals(listOf(setOf<UByte>(3u)), capture.received)
            assertEquals(listOf(listOf<UByte>(3u, 6u)), cache.indexUpdates)
            assertEquals(listOf("threeB", "six"), store.fetchChannels(radioId).map { it.name }, "retry never deletes or prunes")
        },
        "retryFailedChannels with no indices returns immediately and recovering nothing skips the persist" to {
            val radioId = channelsRadioId()
            val clock = ChannelsRecordingClock()
            val cache = ChannelsDecryptionCapture()
            val service = channelServiceFor(
                ChannelsMockSession(), ChannelsInMemoryStore(), radioId, cache, clock,
            )
            assertEquals(ChannelSyncResult(0, SnapshotList.empty()), service.retryFailedChannels(radioId, SnapshotList.empty()))
            assertTrue(clock.sleeps.isEmpty())
            assertEquals(ChannelSyncResult(0, SnapshotList.empty()), service.retryFailedChannels(radioId, SnapshotList.of(2u)))
            assertTrue(cache.indexUpdates.isEmpty())
        },
    )

    @TestFactory
    fun writesAndLocalReads(): List<DynamicTest> = channelsNativeCases(
        "writes truncate names to 31 UTF-8 bytes on grapheme boundaries, validate secrets and map session errors" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            val service = channelServiceFor(session, ChannelsInMemoryStore(), radioId, clock = ChannelsRecordingClock())
            service.setChannel(radioId, 1u, "a".repeat(40), "pass")
            service.setChannel(radioId, 2u, "b".repeat(29) + "🔐", "pass")
            assertEquals(listOf("a".repeat(31), "b".repeat(29)), session.recordedSetChannels.map { it.name })
            assertEquals(ChannelService.hashSecret("pass"), session.recordedSetChannels.first().secret)

            assertFailsWith<ChannelServiceError.SecretHashingFailed> { service.setChannelWithSecret(radioId, 3u, "x", Bytes(ByteArray(15))) }
            assertEquals(2, session.recordedSetChannels.size, "an invalid secret never reaches the radio")

            session.stubbedSetChannelError = MeshCoreException.DeviceError(6u)
            val failure = assertFailsWith<ChannelServiceError.SessionError> { service.setChannel(radioId, 4u, "y", "z") }
            assertIs<MeshCoreException.DeviceError>(failure.error)
            assertFailsWith<ChannelServiceError.SessionError> { service.clearChannel(radioId, 1u) }
        },
        "setupPublicChannel writes the well-known slot 0 key and hasPublicChannel reflects storage" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            val cache = ChannelsDecryptionCapture()
            val service = channelServiceFor(session, ChannelsInMemoryStore(), radioId, cache, ChannelsRecordingClock())
            assertTrue(service.hasRxLogServiceWired)
            assertFalse(service.hasPublicChannel(radioId))
            service.setupPublicChannel(radioId)
            val written = session.recordedSetChannels.single()
            assertEquals(0.toUByte(), written.index)
            assertEquals("Public", written.name)
            assertEquals("8b3387e9c5cdea6ac9e5edbaa115cd72", written.secret.hexString)
            assertTrue(service.hasPublicChannel(radioId))
            assertEquals(listOf(listOf<UByte>(0u)), cache.indexUpdates)
        },
        "clearChannelMessages wipes history and resets preview and unread counters but keeps the channel" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            val id = store.saveChannel(radioId, ChannelInfo(2u, "two", channelsSecret(0x22)))
            store.updateChannelLastMessage(EntityKey(radioId, id), Instant.EPOCH)
            store.incrementChannelUnreadCount(EntityKey(radioId, id))
            store.incrementChannelUnreadMentionCount(EntityKey(radioId, id))
            store.saveTestMessage(radioId, 2u, "hello")
            val service = channelServiceFor(ChannelsMockSession(), store, radioId, clock = ChannelsRecordingClock())
            assertEquals(listOf<UByte>(2u), service.getActiveChannels(radioId).map { it.index })

            service.clearChannelMessages(radioId, 2u)

            val channel = service.getChannel(radioId, 2u)
            assertEquals("two", channel?.name)
            assertNull(channel?.lastMessageDate)
            assertEquals(0L to 0L, channel?.unreadCount to channel?.unreadMentionCount)
            assertTrue(store.fetchMessages(radioId, 2u).isEmpty())
            assertTrue(service.getActiveChannels(radioId).isEmpty())
        },
        "clearing an unstored slot wipes its messages and still notifies" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            store.saveTestMessage(radioId, 7u, "orphan")
            val session = ChannelsMockSession()
            val capture = ChannelsSlotCapture()
            val service = channelServiceFor(session, store, radioId, clock = ChannelsRecordingClock())
            service.setSlotOccupantChangedHandler(capture.handler)
            service.clearChannel(radioId, 7u)
            assertTrue(store.fetchMessages(radioId, 7u).isEmpty())
            assertEquals(listOf(setOf<UByte>(7u)), capture.received)
            assertEquals(ChannelsMockSession.SetChannelInvocation(7u, "", Bytes(ByteArray(16))), session.recordedSetChannels.single())
        },
        "the channel service test double records invocations and replays stubs" to {
            val radioId = channelsRadioId()
            val mock = ChannelsMockChannelService()
            mock.stubbedSyncChannelsResult = Result.success(ChannelSyncResult(4, SnapshotList.empty()))
            mock.stubbedRetryResult = Result.failure(ChannelServiceError.SyncAlreadyInProgress())
            assertEquals(4, mock.syncChannels(radioId, 8u).channelsSynced)
            assertFailsWith<ChannelServiceError.SyncAlreadyInProgress> { mock.retryFailedChannels(radioId, SnapshotList.of(1u)) }
            assertEquals(listOf(ChannelsMockChannelService.SyncChannelsInvocation(radioId, 8u, false)), mock.syncChannelsInvocations)
            assertEquals(listOf(ChannelsMockChannelService.RetryInvocation(radioId, listOf(1u))), mock.retryInvocations)
            mock.reset()
            assertTrue(mock.syncChannelsInvocations.isEmpty() && mock.retryInvocations.isEmpty())
        },
        "exportChannelURI percent-encodes like URLComponents with literal plus rewritten" to {
            val uri = ChannelService.exportChannelURI("#a+b c/é", channelsSecret(0), com.meshcoreone.android.core.model.ChannelFloodScope.Region(""))
            assertEquals("meshcore://channel/add?name=%23a%2Bb%20c/%C3%A9&secret=00000000000000000000000000000000", uri)
        },
    )

    private companion object {
        const val SEED = 209
    }
}
