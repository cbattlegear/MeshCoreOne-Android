// AndroidOnly: WP-209 native cancellation, concurrency, retry-timing and error-mapping cases for the ChannelService port.
// AndroidOnly: WP-405 Allocation-through-save and uncertain-outcome exclusion regressions.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ChannelServiceNativeTest {
    @TestFactory
    fun allocationThroughSave(): List<DynamicTest> = channelsCases("WP-405 allocation",
        "two accepted joins refresh allocation after the first actual acknowledgement and save" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val ack = CompletableDeferred<Unit>()
            val session = object : ChannelSessionOps by base {
                override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
                    base.setChannel(index, name, secret)
                    if (name == "#test") ack.await()
                }
            }
            val store = ChannelsInMemoryStore()
            val service = initializedAllocationService(radio, session, store)
            val first = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 4u, "#test") }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                service.createChannelWithSecret(radio, 4u, "Private", channelsSecret(0x22))
            }
            assertEquals(1, base.recordedSetChannels.size)
            assertTrue(store.fetchChannels(radio).isEmpty(), "A is held before save, not after commit")
            assertFalse(second.isCompleted)
            ack.complete(Unit)
            assertEquals(1u, first.await().index)
            assertEquals(2u, second.await().index)
            assertEquals(listOf("#test", "Private"), store.fetchChannels(radio).map { it.name })
            assertEquals(listOf<UByte>(1u, 2u), base.recordedSetChannels.map { it.index })
        },
        "allocation cannot pass a known acknowledgement while its local save is still held" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val rows = ChannelsInMemoryStore()
            val save = CompletableDeferred<Unit>()
            val store = object : ChannelPersisting by rows {
                override suspend fun saveChannel(radioId: com.meshcoreone.android.core.model.RadioId, info: ChannelInfo): java.util.UUID {
                    if (info.name == "A") save.await()
                    return rows.saveChannel(radioId, info)
                }
            }
            val service = initializedAllocationService(radio, base, store)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                service.createChannelWithSecret(radio, 3u, "A", channelsSecret(0x11))
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 3u, "#B") }
            assertEquals(1, base.recordedSetChannels.size, "No B dispatch before A is persisted")
            assertTrue(rows.fetchChannels(radio).isEmpty())
            save.complete(Unit)
            assertEquals(1u, first.await().index)
            assertEquals(2u, second.await().index)
        },
        "explicit-index writes participate in the allocation boundary" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val ack = CompletableDeferred<Unit>()
            val session = object : ChannelSessionOps by base {
                override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
                    base.setChannel(index, name, secret)
                    if (name == "Explicit") ack.await()
                }
            }
            val rows = ChannelsInMemoryStore()
            val service = initializedAllocationService(radio, session, rows)
            val first = async(start = CoroutineStart.UNDISPATCHED) { service.setChannel(radio, 1u, "Explicit", "key") }
            val second = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 3u, "#B") }
            assertEquals(1, base.recordedSetChannels.size)
            ack.complete(Unit)
            first.await()
            assertEquals(2u, second.await().index)
            assertEquals("Explicit", rows.fetchChannel(radio, 1u)?.name)
        },
        "sync reads cannot capture a pre-write snapshot and later prune the accepted allocation" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val ack = CompletableDeferred<Unit>()
            val session = object : ChannelSessionOps by base {
                override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
                    base.setChannel(index, name, secret)
                    ack.await()
                    base.setStubbedChannels(mapOf(index to ChannelInfo(index, name, secret)))
                }
            }
            val rows = ChannelsInMemoryStore()
            val service = initializedAllocationService(radio, session, rows)
            val previousReads = base.recordedGetChannelIndices
            val first = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 3u, "#test") }
            val sync = async(start = CoroutineStart.UNDISPATCHED) { service.syncChannels(radio, 3u, false) }
            assertEquals(previousReads, base.recordedGetChannelIndices)
            assertFalse(sync.isCompleted)
            assertFailsWith<ChannelServiceError.SyncAlreadyInProgress> { service.syncChannels(radio, 3u, false) }
            ack.complete(Unit)
            val accepted = first.await()
            assertEquals(1, sync.await().channelsSynced)
            assertEquals(accepted.id, rows.fetchChannel(radio, 1u)?.id)
        },
        "clear and subsequent allocation serialize deletion before reusing the slot" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val ack = CompletableDeferred<Unit>()
            val session = object : ChannelSessionOps by base {
                override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
                    base.setChannel(index, name, secret)
                    if (name.isEmpty()) ack.await()
                }
            }
            val rows = ChannelsInMemoryStore()
            base.setStubbedChannels(mapOf(1.toUByte() to ChannelInfo(1u, "Old", channelsSecret(0x11))))
            rows.saveChannel(radio, ChannelInfo(1u, "Old", channelsSecret(0x11)))
            val service = initializedAllocationService(radio, session, rows)
            val clear = async(start = CoroutineStart.UNDISPATCHED) { service.clearChannel(radio, 1u) }
            val next = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 2u, "#test") }
            assertEquals(1, base.recordedSetChannels.size)
            assertEquals("Old", rows.fetchChannel(radio, 1u)?.name)
            ack.complete(Unit)
            clear.await()
            assertEquals(1u, next.await().index)
            assertEquals("#test", rows.fetchChannel(radio, 1u)?.name)
        },
        "cancelling a queued consent dispatches nothing and does not quarantine the preceding successful write" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val ack = CompletableDeferred<Unit>()
            val session = object : ChannelSessionOps by base {
                override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
                    base.setChannel(index, name, secret)
                    if (name == "A") ack.await()
                }
            }
            val service = initializedAllocationService(radio, session, ChannelsInMemoryStore())
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                service.createChannelWithSecret(radio, 4u, "A", channelsSecret(0x11))
            }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 4u, "#cancelled") }
            cancelled.cancelAndJoin()
            assertEquals(1, base.recordedSetChannels.size)
            ack.complete(Unit)
            first.await()
            assertEquals(2u, service.joinHashtagChannel(radio, 4u, "#next").index)
            assertEquals(listOf("A", "#next"), base.recordedSetChannels.map { it.name })
        },
        "caller cancellation after attempted dispatch preserves cancellation and blocks unsafe slot reuse and sync" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val session = object : ChannelSessionOps by base {
                override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
                    base.setChannel(index, name, secret)
                    awaitCancellation()
                }
            }
            val rows = ChannelsInMemoryStore()
            val service = initializedAllocationService(radio, session, rows)
            val first = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 3u, "#test") }
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            val failure = assertFailsWith<ChannelMutationError.ReconciliationRequired> {
                service.createChannelWithSecret(radio, 3u, "B", channelsSecret(0x22))
            }
            assertIs<CancellationException>(failure.cause)
            assertFailsWith<ChannelMutationError.ReconciliationRequired> { service.syncChannels(radio, 3u, false) }
            assertFailsWith<ChannelMutationError.ReconciliationRequired> { service.clearChannel(radio, 1u) }
            assertTrue(rows.fetchChannels(radio).isEmpty())
            assertEquals(1, base.recordedSetChannels.size)
        },
        "a successful acknowledgement with failed local save remains an explicit failure and blocks later allocation" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val rows = ChannelsInMemoryStore()
            val diskFailure = IllegalStateException("disk full")
            val store = object : ChannelPersisting by rows {
                override suspend fun saveChannel(radioId: com.meshcoreone.android.core.model.RadioId, info: ChannelInfo): java.util.UUID =
                    throw diskFailure
            }
            val service = initializedAllocationService(radio, base, store)
            assertEquals(diskFailure, assertFailsWith<IllegalStateException> { service.joinHashtagChannel(radio, 3u, "#test") })
            assertEquals(diskFailure, assertFailsWith<ChannelMutationError.ReconciliationRequired> {
                service.joinHashtagChannel(radio, 3u, "#next")
            }.cause)
            assertEquals(1, base.recordedSetChannels.size)
            assertTrue(rows.fetchChannels(radio).isEmpty())
        },
        "a definitive negative device acknowledgement permits a subsequent fresh allocation" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession().apply { stubbedSetChannelError = MeshCoreException.DeviceError(6u) }
            val service = initializedAllocationService(radio, base, ChannelsInMemoryStore())
            assertFailsWith<ChannelServiceError.SessionError> { service.joinHashtagChannel(radio, 3u, "#failed") }
            base.stubbedSetChannelError = null
            assertEquals(1u, service.joinHashtagChannel(radio, 3u, "#test").index)
        },
        "full capacity is a typed allocation failure but an existing hashtag still reuses its row without writing" to {
            val radio = channelsRadioId()
            val rows = ChannelsInMemoryStore()
            rows.saveChannel(radio, ChannelInfo(1u, "#Ops", channelsSecret(0x11)))
            val base = ChannelsMockSession()
            base.setStubbedChannels(mapOf(1.toUByte() to ChannelInfo(1u, "#Ops", channelsSecret(0x11))))
            val service = initializedAllocationService(radio, base, rows)
            assertFailsWith<ChannelMutationError.NoAvailableSlots> {
                service.createChannelWithSecret(radio, 2u, "Full", channelsSecret(0x22))
            }
            assertEquals("#Ops", service.joinHashtagChannel(radio, 2u, "#ops").name)
            assertTrue(base.recordedSetChannels.isEmpty())
        },
        "occupant callbacks may initiate another mutation without lock reentrancy and cache publication stays fresh" to {
            val radio = channelsRadioId()
            val rows = ChannelsInMemoryStore()
            rows.saveChannel(radio, ChannelInfo(1u, "Old", channelsSecret(0x11)))
            val cache = ChannelsDecryptionCapture()
            val base = ChannelsMockSession()
            base.setStubbedChannels(mapOf(1.toUByte() to ChannelInfo(1u, "Old", channelsSecret(0x11))))
            val service = initializedAllocationService(radio, base, rows, cache)
            service.setSlotOccupantChangedHandler { _, slots ->
                assertEquals(setOf<UByte>(1u), slots.toSet())
                service.joinHashtagChannel(radio, 4u, "#nested")
            }
            service.setChannelWithSecret(radio, 1u, "Changed", channelsSecret(0x22))
            assertEquals(listOf("Changed", "#nested"), rows.fetchChannels(radio).map { it.name })
            assertEquals(listOf(setOf<UByte>(1u, 2u), setOf<UByte>(1u, 2u)), cache.indexUpdates.takeLast(2).map { it.toSet() })
        },
        "uninitialized and partial or circuit-breaker skipped radio slots are not free-slot authority" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession().apply { stubbedGetChannelError = MeshCoreException.Timeout() }
            val rows = ChannelsInMemoryStore()
            val service = ChannelService(base, rows, null, ChannelsRecordingClock())
            assertEquals(listOf<UByte>(1u, 2u, 3u),
                assertFailsWith<ChannelMutationError.SyncIncomplete> { service.joinHashtagChannel(radio, 4u, "#test") }
                    .unresolvedIndices.toList())
            base.stubbedGetChannelError = MeshCoreException.Timeout()
            val result = service.syncChannels(radio, 6u, false)
            assertTrue(result.circuitBreakerAborted)
            assertFailsWith<ChannelMutationError.SyncIncomplete> { service.joinHashtagChannel(radio, 6u, "#test") }
            assertTrue(base.recordedSetChannels.isEmpty())
        },
        "partial-sync unknown slot refuses a join before delayed retry and joins wait for retry read plus batch commit" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            base.script(1u, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() })
            val rows = ChannelsInMemoryStore()
            val retryRead = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var holdRetry = false
            val store = object : ChannelPersisting by rows {
                override suspend fun batchSaveChannels(
                    radioId: RadioId, configured: SnapshotList<ChannelInfo>, unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
                ): SnapshotList<com.meshcoreone.android.core.model.ChannelDTO> {
                    if (holdRetry) {
                        retryRead.complete(Unit)
                        release.await()
                    }
                    return rows.batchSaveChannels(radioId, configured, unconfiguredIndices, pruneBeyond)
                }
            }
            val service = ChannelService(base, store, null, ChannelsRecordingClock())
            assertEquals(listOf<UByte>(1u), service.syncChannels(radio, 4u, false).retryableIndices.toList())
            assertNull(rows.fetchChannel(radio, 1u))
            base.script(1u, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() }, { throw MeshCoreException.Timeout() })
            assertFailsWith<ChannelMutationError.SyncIncomplete> { service.joinHashtagChannel(radio, 4u, "#too-early") }
            base.setStubbedChannels(mapOf(1.toUByte() to ChannelInfo(1u, "Radio occupant", channelsSecret(0x33))))
            holdRetry = true
            val retry = async(start = CoroutineStart.UNDISPATCHED) { service.retryFailedChannels(radio, SnapshotList.of(1u)) }
            retryRead.await()
            val join = async(start = CoroutineStart.UNDISPATCHED) { service.joinHashtagChannel(radio, 4u, "#accepted") }
            assertFalse(join.isCompleted)
            assertTrue(base.recordedSetChannels.isEmpty(), "Retry's radio snapshot must commit before allocation")
            release.complete(Unit)
            assertEquals(1, retry.await().channelsSynced)
            assertEquals(2u, join.await().index)
            assertEquals("Radio occupant", rows.fetchChannel(radio, 1u)?.name)
            assertEquals("#accepted", rows.fetchChannel(radio, 2u)?.name)
        },
        "retry starting after a successful join sees that joined occupant instead of restoring an old read" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            val rows = ChannelsInMemoryStore()
            val service = initializedAllocationService(radio, base, rows)
            val accepted = service.joinHashtagChannel(radio, 4u, "#test")
            rows.saveTestMessage(radio, accepted.index, "Keep this message")
            base.setStubbedChannels(mapOf(accepted.index to ChannelInfo(accepted.index, accepted.name, accepted.secret)))
            assertEquals(1, service.retryFailedChannels(radio, SnapshotList.of(accepted.index)).channelsSynced)
            assertEquals(accepted.id, rows.fetchChannel(radio, accepted.index)?.id)
            assertEquals(listOf("Keep this message"), rows.fetchMessages(radio, accepted.index).map { it.text })
        },
        "a new service with a skipped initial channel phase establishes authority on consent instead of remaining blocked" to {
            val radio = channelsRadioId()
            val base = ChannelsMockSession()
            base.setStubbedChannels(mapOf(1.toUByte() to ChannelInfo(1u, "Radio occupant", channelsSecret(0x33))))
            val rows = ChannelsInMemoryStore()
            val service = ChannelService(base, rows, null)
            assertTrue(base.recordedGetChannelIndices.isEmpty())
            val accepted = service.joinHashtagChannel(radio, 3u, "#test")
            assertEquals(listOf<UByte>(0u, 1u, 2u), base.recordedGetChannelIndices)
            assertEquals(2u, accepted.index)
            assertEquals("Radio occupant", rows.fetchChannel(radio, 1u)?.name)
            assertEquals(listOf<UByte>(2u), base.recordedSetChannels.map { it.index })
        },
    )

    private suspend fun initializedAllocationService(
        radio: RadioId, session: ChannelSessionOps, store: ChannelPersisting, cache: ChannelDecryptionCache? = null,
    ): ChannelService = ChannelService(session, store, cache).also { it.syncChannels(radio, 4u, false) }

    @TestFactory
    fun concurrencyAndCancellation(): List<DynamicTest> = channelsNativeCases(
        "a sync in progress rejects concurrent syncs and retries, then releases the guard" to {
            val radioId = channelsRadioId()
            val session = ChannelsMockSession()
            val gate = CompletableDeferred<Unit>()
            session.script(0u, { gate.await(); ChannelInfo(0u, "ch0", channelsSecret(0xAB)) })
            val service = ChannelService(session, ChannelsInMemoryStore(), null, ChannelsRecordingClock())
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
            val service = ChannelService(session, store, null, ChannelsRecordingClock())
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
            val service = ChannelService(session, ChannelsInMemoryStore(), null, clock)
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
            val service = ChannelService(ChannelsMockSession(), ChannelsInMemoryStore(), null, ChannelServiceClock { sleeping.set(true); awaitCancellation() })
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
            val service = ChannelService(session, ChannelsInMemoryStore(), null, clock, Random(SEED))
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
            val service = ChannelService(session, ChannelsInMemoryStore(), null, clock)
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
            val service = ChannelService(session, store, null, ChannelsRecordingClock())

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
            val plain = ChannelService(unclassified, ChannelsInMemoryStore(), null, ChannelsRecordingClock())
            val plainResult = plain.syncChannels(radioId, 6u, usePipelinedRead = false)
            assertEquals(List(6) { ChannelSyncErrorType.Unknown }, plainResult.errors.map { it.errorType })

            val classified = ChannelsMockSession().apply { stubbedGetChannelError = IllegalStateException("ble link dropped") }
            val service = ChannelService(
                classified, ChannelsInMemoryStore(), null, ChannelsRecordingClock(),
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
            val service = ChannelService(session, store, cache, ChannelsRecordingClock())
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
            val service = ChannelService(session, store, cache, clock, Random(SEED))
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
            val service = ChannelService(ChannelsMockSession(), ChannelsInMemoryStore(), cache, clock)
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
            val service = ChannelService(session, ChannelsInMemoryStore(), null, ChannelsRecordingClock())
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
            val service = ChannelService(session, ChannelsInMemoryStore(), cache, ChannelsRecordingClock())
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
            val service = ChannelService(ChannelsMockSession(), store, null, ChannelsRecordingClock())
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
            val service = ChannelService(session, store, null, ChannelsRecordingClock())
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
