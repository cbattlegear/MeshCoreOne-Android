// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorChannelSkipTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.model.snapshot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Original SyncCoordinatorChannelSkipTests: skip window and clean-channel callback. */
class SyncCoordinatorChannelSkipTests {
    private val suite = "SyncCoordinatorChannelSkipTests"
    private fun case(name: String, body: suspend SyncTestScope.() -> Unit) = syncCase(suite, name, body)

    /** Runs one full sync with [config] and returns the channel service it used. */
    private suspend fun SyncTestScope.runSync(
        config: ChannelSyncConfig = ChannelSyncConfig.NONE,
        channels: MockChannelService = MockChannelService(),
        forceFullSync: Boolean = false,
        appState: MockAppStateProvider? = null,
        coordinator: SyncCoordinator = coordinator(),
    ): MockChannelService {
        val radioId = newRadio()
        val store = SyncInMemoryStore.createTestDataStore(radioId)
        coordinator.performFullSync(
            radioId, store, MockContactService(), channels, MockMessagePollingService(), appState,
            forceFullSync = forceFullSync, channelSyncConfig = config,
        )
        return channels
    }

    private fun errors(vararg values: ChannelSyncError) = values.toList().snapshot()

    @TestFactory
    fun cases(): List<DynamicTest> = listOf(
        case("Channels skipped when lastCleanChannelSync is recent and skip window > 0") {
            val channels = runSync(ChannelSyncConfig(30.seconds, lastCleanChannelSync = clock.now()))
            assertTrue(channels.syncChannelsInvocations.isEmpty(), "Channel sync should be skipped when clean sync completed recently")
        },
        case("Channels sync when lastCleanChannelSync is nil") {
            val channels = runSync(ChannelSyncConfig(30.seconds))
            assertEquals(1, channels.syncChannelsInvocations.size)
        },
        case("Channels skipped when last attempted channel sync is recent") {
            val channels = runSync(ChannelSyncConfig(30.seconds, lastAttemptedChannelSync = clock.now()))
            assertTrue(channels.syncChannelsInvocations.isEmpty(), "Channel sync should be skipped after a recent partial attempt")
        },
        case("Channels sync when lastCleanChannelSync is expired (outside window)") {
            val channels = runSync(ChannelSyncConfig(30.seconds, lastCleanChannelSync = clock.now().minusSeconds(60)))
            assertEquals(1, channels.syncChannelsInvocations.size)
        },
        case("forceFullSync bypasses channel skip") {
            val channels = runSync(ChannelSyncConfig(30.seconds, lastCleanChannelSync = clock.now()), forceFullSync = true)
            assertEquals(1, channels.syncChannelsInvocations.size)
        },
        case("Zero skip window disables skip") {
            val channels = runSync(ChannelSyncConfig(lastCleanChannelSync = clock.now()))
            assertEquals(1, channels.syncChannelsInvocations.size)
        },
        case("Callback fires on clean channel phase (zero errors)") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val channels = MockChannelService().apply { stubbedSyncChannelsResult = Result.success(ChannelSyncResult(8)) }
            val tracker = CallTracker()
            coordinator.setCleanChannelSyncCallback { id ->
                assertEquals(radioId, id)
                tracker.markCalled()
            }
            coordinator.performFullSync(radioId, store, MockContactService(), channels, MockMessagePollingService())
            assertTrue(tracker.wasCalled, "onCleanChannelSync should fire when channel phase is clean")
        },
        case("Callback fires when initial sync fails but retry recovers") {
            val coordinator = coordinator()
            val tracker = CallTracker()
            coordinator.setCleanChannelSyncCallback { tracker.markCalled() }
            runSync(
                coordinator = coordinator,
                channels = MockChannelService().apply {
                    stubbedSyncChannelsResult = Result.success(ChannelSyncResult(7, errors(ChannelSyncError(2u, ChannelSyncErrorType.Timeout, "timeout"))))
                    stubbedRetryResult = Result.success(ChannelSyncResult(1))
                },
            )
            assertTrue(tracker.wasCalled, "onCleanChannelSync should fire when retry recovers all errors")
        },
        case("Callback does not fire when channel sync has errors after retries") {
            val coordinator = coordinator()
            val tracker = CallTracker()
            coordinator.setCleanChannelSyncCallback { tracker.markCalled() }
            runSync(
                coordinator = coordinator,
                channels = MockChannelService().apply {
                    stubbedSyncChannelsResult = Result.success(ChannelSyncResult(7, errors(ChannelSyncError(2u, ChannelSyncErrorType.Timeout, "timeout"))))
                    stubbedRetryResult = Result.success(ChannelSyncResult(0, errors(ChannelSyncError(2u, ChannelSyncErrorType.Timeout, "still failing"))))
                },
            )
            assertFalse(tracker.wasCalled, "onCleanChannelSync should not fire when errors remain after retry")
        },
        case("Callback does not fire with mixed retryable and non-retryable errors even when retry succeeds") {
            val coordinator = coordinator()
            val tracker = CallTracker()
            coordinator.setCleanChannelSyncCallback { tracker.markCalled() }
            runSync(
                coordinator = coordinator,
                channels = MockChannelService().apply {
                    stubbedSyncChannelsResult = Result.success(
                        ChannelSyncResult(
                            6,
                            errors(
                                ChannelSyncError(5u, ChannelSyncErrorType.DeviceError(3u), "device error"),
                                ChannelSyncError(10u, ChannelSyncErrorType.Timeout, "timeout"),
                            ),
                        ),
                    )
                    stubbedRetryResult = Result.success(ChannelSyncResult(1))
                },
            )
            assertFalse(tracker.wasCalled, "onCleanChannelSync must not fire when non-retryable errors remain unresolved")
        },
        case("Callback does not fire when channels are skipped") {
            val coordinator = coordinator()
            val tracker = CallTracker()
            coordinator.setCleanChannelSyncCallback { tracker.markCalled() }
            runSync(ChannelSyncConfig(30.seconds, lastCleanChannelSync = clock.now()), coordinator = coordinator)
            assertFalse(tracker.wasCalled, "onCleanChannelSync should not fire when channels are skipped")
        },
        case("Callback does not fire when initial sync is clean but channels skipped in background") {
            val coordinator = coordinator()
            val tracker = CallTracker()
            coordinator.setCleanChannelSyncCallback { tracker.markCalled() }
            runSync(appState = MockAppStateProvider(false), coordinator = coordinator)
            assertFalse(tracker.wasCalled, "onCleanChannelSync should not fire when channels are skipped in background")
        },
        case("Post-sync diagnostics still run when channels are skipped") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val channels = MockChannelService()
            val rxLog = FakeRxLogService()
            coordinator.performFullSync(
                radioId, store, MockContactService(), channels, MockMessagePollingService(), rxLogService = rxLog,
                channelSyncConfig = ChannelSyncConfig(30.seconds, lastCleanChannelSync = clock.now()),
            )
            assertTrue(channels.syncChannelsInvocations.isEmpty(), "Channel sync should be skipped")
            assertEquals(SyncState.Synced, coordinator.state, "Sync should complete successfully when channels are skipped")
            assertTrue("fetchChannels" in store.calls, "post-sync diagnostics read the stored channels")
            assertEquals(1, rxLog.channelUpdates, "RX log channel cache still refreshes from the store")
        },
    )
}
