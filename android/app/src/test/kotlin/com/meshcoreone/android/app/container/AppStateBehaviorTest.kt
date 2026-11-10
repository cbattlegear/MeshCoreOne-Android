// PortedFrom: MC1Tests/State/MessageEventStreamTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/BatteryMonitoringTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/AppStateRegionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/AppState/LifecycleTransitionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/State/SystemPairingSetupAppStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.state.AppStatePlaceholder
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.app.state.MessageEventStream
import com.meshcoreone.android.app.state.PairingFailureKind
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.runtime.LinkFailure
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Runs everything scheduled, background-scope work included (`advanceUntilIdle` ignores background-only work). */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settleVirtual() {
    testScheduler.advanceTimeBy(2_000)
    testScheduler.runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateBehaviorTest {
    // region MessageEventStreamTests

    @OriginalCase("MessageEventStreamTests::Single consumer receives an event sent via send(_:)()")
    @Test fun singleConsumerReceivesEvent() = runTest {
        val stream = MessageEventStream()
        val event = MessageEvent.MessageStatusResolved(UUID.randomUUID(), MessageStatus.SENT)
        val received = async(start = CoroutineStart.UNDISPATCHED) { stream.events().first() }
        stream.send(event)
        assertEquals(event, received.await())
    }

    @OriginalCase("MessageEventStreamTests::Multiple consumers each receive the same event()")
    @Test fun multipleConsumersReceiveTheSameEvent() = runTest {
        val stream = MessageEventStream()
        val event = MessageEvent.HeardRepeatRecorded(UUID.randomUUID(), 3)
        val first = async(start = CoroutineStart.UNDISPATCHED) { stream.events().first() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { stream.events().first() }
        assertEquals(2, stream.subscriberCount)
        stream.send(event)
        assertEquals(event, first.await())
        assertEquals(event, second.await())
    }

    @OriginalCase("MessageEventStreamTests::Events sent across consumer task restarts are not dropped when the consumer is held by a long-lived task()")
    @Test fun longLivedConsumerKeepsEveryEvent() = runTest {
        val stream = MessageEventStream()
        val first = MessageEvent.MessageStatusResolved(UUID.randomUUID(), MessageStatus.SENT)
        val second = MessageEvent.MessageStatusResolved(UUID.randomUUID(), MessageStatus.DELIVERED)
        val collected = mutableListOf<MessageEvent>()
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.events().collect { event -> collected.add(event) }
        }
        stream.send(first)
        stream.send(second)
        runCurrent()
        assertEquals<List<MessageEvent>>(listOf(first, second), collected)
        consumer.cancel()
    }

    @OriginalCase("MessageEventStreamTests::subscriberCount reflects active subscriptions()")
    @Test fun subscriberCountReflectsActiveSubscriptions() = runTest {
        val stream = MessageEventStream()
        assertEquals(0, stream.subscriberCount)
        val firstTask = launch(start = CoroutineStart.UNDISPATCHED) { stream.events().collect { } }
        assertEquals(1, stream.subscriberCount)
        val secondTask = launch(start = CoroutineStart.UNDISPATCHED) { stream.events().collect { } }
        assertEquals(2, stream.subscriberCount)
        firstTask.cancel(); secondTask.cancel()
        firstTask.join(); secondTask.join()
        stream.send(MessageEvent.MessageFailed(UUID.randomUUID()))
        assertEquals(0, stream.subscriberCount)
    }

    // endregion

    // region BatteryMonitoringTests

    @OriginalCase("BatteryMonitoringTests::deviceBattery is nil by default()")
    @Test fun batteryNullByDefault() = runTest { assertNull(appStateOf(backgroundScope).batteryMonitor.deviceBattery) }

    @OriginalCase("BatteryMonitoringTests::activeBatteryOCVArray returns liIon default when no device connected()")
    @Test fun liIonDefaultWithoutDevice() = runTest {
        val state = appStateOf(backgroundScope)
        assertEquals(OCVPreset.LI_ION.ocvArray, state.batteryMonitor.activeBatteryOcvArray(state.connectedDevice))
    }

    @OriginalCase("BatteryMonitoringTests::fetchDeviceBattery is no-op when services is nil()")
    @Test fun fetchIsNoOpWithoutServices() = runTest {
        val state = appStateOf(backgroundScope)
        state.batteryMonitor.fetchDeviceBattery(state.services?.batteryServices, state.connectedDevice)
        assertNull(state.batteryMonitor.deviceBattery)
    }

    @OriginalCase("BatteryMonitoringTests::fetchDeviceBattery does not crash when called on fresh state()")
    @Test fun fetchOnFreshStateDoesNotThrow() = runTest {
        val state = appStateOf(backgroundScope)
        assertNull(state.services)
        state.batteryMonitor.fetchDeviceBattery(state.services?.batteryServices, state.connectedDevice)
        assertNull(state.batteryMonitor.deviceBattery)
    }

    @OriginalCase("BatteryMonitoringTests::deviceBattery can be set directly for testing()")
    @Test fun batteryCanBeSet() = runTest {
        val state = appStateOf(backgroundScope)
        val battery = BatteryInfo(level = 3700)
        state.batteryMonitor.deviceBattery = battery
        assertEquals(battery, state.batteryMonitor.deviceBattery)
        assertEquals(3700L, state.batteryMonitor.deviceBattery?.level)
    }

    @OriginalCase("BatteryMonitoringTests::deviceBattery can be cleared()")
    @Test fun batteryCanBeCleared() = runTest {
        val state = appStateOf(backgroundScope)
        state.batteryMonitor.deviceBattery = BatteryInfo(level = 3700)
        state.batteryMonitor.deviceBattery = null
        assertNull(state.batteryMonitor.deviceBattery)
    }

    // endregion

    // region AppStateRegionTests / AppStateEnvironmentDefaultTests

    private val region = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")

    @OriginalCase("AppStateRegionTests::regionSelection persists to UserDefaults on set()")
    @Test fun regionPersistsOnSet() = runTest {
        val store = MemoryRegionStore()
        val state = appStateOf(backgroundScope, regionStore = store)
        state.regionSelection = region
        runCurrent()
        assertEquals(listOf<RegionSelection?>(region), store.persisted)
        // The stored text decodes back to the same value (Swift decodes the stored JSON and compares).
        assertEquals(region, com.meshcoreone.android.app.state.RegionSelectionJson.decode(assertNotNull(store.storedJson)))
    }

    @OriginalCase("AppStateRegionTests::regionSelection clears UserDefaults on nil()")
    @Test fun regionClearsOnNil() = runTest {
        val store = MemoryRegionStore()
        val state = appStateOf(backgroundScope, regionStore = store)
        state.regionSelection = RegionSelection("US", RegionSelection.Source.MANUAL)
        runCurrent()
        state.regionSelection = null
        runCurrent()
        assertNull(store.storedJson)
    }

    @Test fun regionWritesPersistInOrderOnAMultiThreadedDispatcher() = runBlocking<Unit> {
        val store = MemoryRegionStore()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val state = appStateOf(scope, regionStore = store)
            val values = (1..200).map { RegionSelection("C$it", RegionSelection.Source.MANUAL) } + listOf(null)
            values.forEach { state.regionSelection = it }
            kotlinx.coroutines.withTimeout(5_000) { while (store.persisted.size < values.size) kotlinx.coroutines.delay(5) }
            assertEquals(values, store.persisted.toList(), "persisted in the order they were set, ending with the clear")
            assertNull(store.storedJson)
        } finally { scope.cancel() }
    }

    @OriginalCase("AppStateRegionTests::AppState loads persisted regionSelection on init()")
    @Test fun regionLoadsWithoutWritingBack() = runTest {
        val persisted = RegionSelection("PT", RegionSelection.Source.MANUAL)
        val store = MemoryRegionStore(com.meshcoreone.android.app.state.RegionSelectionJson.encode(persisted))
        val state = appStateOf(backgroundScope, regionStore = store)
        state.loadPersistedRegionSelection()
        assertEquals(persisted, state.regionSelection)
        runCurrent()
        assertTrue(store.persisted.isEmpty(), "loading must not rewrite the value it just read")
    }

    @OriginalCase("AppStateEnvironmentDefaultTests::environment default is a single shared placeholder()")
    @Test fun placeholderIsShared() { assertSame(AppStatePlaceholder.instance, AppStatePlaceholder.instance) }

    @OriginalCase("AppStateEnvironmentDefaultTests::placeholder is inert with no services or live transaction listener()", "platform-adaptation")
    @Test fun placeholderIsInert() {
        val placeholder = AppStatePlaceholder.instance
        assertNull(placeholder.services)
        assertEquals(0, placeholder.activeSessionJobCount)
        assertFalse(placeholder.batteryMonitor.isBootstrapActive)
        assertFalse(placeholder.batteryMonitor.isRefreshLoopActive)
        assertEquals(DeviceConnectionState.DISCONNECTED, placeholder.connectionState)
    }

    // endregion

    // region LifecycleTransitionTests

    @OriginalCase("LifecycleTransitionTests::BLE foreground waits for queued background transition()")
    @Test fun foregroundWaitsForBackground() = runTest {
        val state = appStateOf(backgroundScope)
        val events = mutableListOf<String>()
        state.bleEnterBackgroundOverride = { events += "background-start"; delay(150.milliseconds); events += "background-end" }
        state.bleBecomeActiveOverride = { events += "foreground-start"; events += "foreground-end" }
        state.handleEnterBackground()
        state.handleReturnToForeground()
        assertEquals(listOf("background-start", "background-end", "foreground-start", "foreground-end"), events)
    }

    @OriginalCase("LifecycleTransitionTests::Explicit disconnect runs the per-session teardown the loss path performs()")
    @Test fun explicitDisconnectTearsDownSession() = runTest {
        val state = appStateOf(backgroundScope)
        val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000032")),
            Generation(1),
            radio,
        )
        assertTrue(state.navigation.bindNotificationSession(radio, null, token))
        state.navigation.setNavigationReady(true)
        state.installSettingsEventsJobForTesting(backgroundScope.launch { delay(60_000) })
        state.navigation.navigateToDiscovery()
        state.navigation.stageChannelLink(ChannelLinkRequest("Channel", Bytes(byteArrayOf(1))))
        state.navigation.stageHashtag(HashtagJoinRequest("channel"))
        assertTrue(state.navigation.state.value.nodesShowingDiscovery)
        state.disconnect()
        assertFalse(state.hasSettingsEventsJob)
        assertFalse(state.navigation.state.value.nodesShowingDiscovery)
        assertNull(state.navigation.state.value.pendingChannelLink)
        assertNull(state.navigation.state.value.pendingHashtag)
        assertEquals(2L, state.navigation.state.value.generation)
    }

    @Test fun suspendedDisconnectCannotTearDownSameRadioReplacement() = runTest {
        val port = FakeConnectionPort()
        val disconnect = CompletableDeferred<Unit>()
        port.disconnectAction = { disconnect.await() }
        val state = appStateOf(backgroundScope, port = port)
        val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000033"))
        val epoch = ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000034"))
        val tokenA = SessionToken(epoch, Generation(1), radio)
        val tokenB = SessionToken(epoch, Generation(2), radio)
        assertTrue(state.navigation.bindNotificationSession(radio, null, tokenA))
        state.navigation.setNavigationReady(true)
        state.navigation.navigateToDiscovery()
        state.installSettingsEventsJobForTesting(backgroundScope.launch { delay(60_000) })

        val disconnectJob = async { state.disconnect() }
        runCurrent()

        assertTrue(state.navigation.bindNotificationSession(radio, null, tokenB))
        state.navigation.stageChannelLink(ChannelLinkRequest("Replacement", Bytes(byteArrayOf(2))))
        state.navigation.stageHashtag(HashtagJoinRequest("replacement"))
        state.navigation.setNavigationReady(true)
        port.connectionState = DeviceConnectionState.CONNECTED
        val replacement = state.navigation.state.value
        disconnect.complete(Unit)
        disconnectJob.await()

        assertEquals(replacement, state.navigation.state.value)
        assertEquals(tokenB, state.navigation.state.value.boundSessionToken)
        assertTrue(state.navigation.state.value.navigationReady)
        assertTrue(state.navigation.state.value.nodesShowingDiscovery)
        assertNotNull(state.navigation.state.value.pendingChannelLink)
        assertNotNull(state.navigation.state.value.pendingHashtag)
        assertTrue(state.hasSettingsEventsJob)
        assertEquals(DeviceConnectionState.CONNECTED, port.connectionState)
    }

    @Test fun initialDisconnectStillClearsUnboundJobsAndPendingLinks() = runTest {
        val state = appStateOf(backgroundScope)
        val job = backgroundScope.launch { delay(60_000) }
        state.installSettingsEventsJobForTesting(job)
        state.navigation.stageChannelLink(ChannelLinkRequest("Initial", Bytes(byteArrayOf(1))))
        state.navigation.stageHashtag(HashtagJoinRequest("initial"))
        assertNull(state.navigation.currentState.boundSessionToken)

        state.disconnect()

        assertTrue(job.isCancelled)
        assertFalse(state.hasSettingsEventsJob)
        assertNull(state.navigation.state.value.pendingChannelLink)
        assertNull(state.navigation.state.value.pendingHashtag)
    }

    @Test fun initialCleanupCannotCancelFirstSessionWiredAfterItsCommit() = runTest {
        val state = appStateOf(backgroundScope)
        val retiredJob = backgroundScope.launch { awaitCancellation() }
        state.installSettingsEventsJobForTesting(retiredJob)
        val navigation = state.navigation
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        val boundary = Any()
        navigation.installCommitBoundary { action ->
            val before = navigation.currentState
            synchronized(boundary, action)
            val after = navigation.currentState
            if (before.boundSessionToken == null && after.boundSessionToken == null &&
                after.generation == before.generation + 1 && paused.compareAndSet(false, true)
            ) {
                committed.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Initial cleanup was not released" }
            }
        }
        val retiring = async(Dispatchers.IO) { state.disconnect() }
        try {
            assertTrue(withContext(Dispatchers.IO) { committed.await(10, TimeUnit.SECONDS) })
            val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RadioId(UUID.randomUUID()))
            assertTrue(navigation.bindNotificationSession(token.radioId, null, token))
            navigation.stageChannelLink(ChannelLinkRequest("Replacement", Bytes(byteArrayOf(2))))
            navigation.stageHashtag(HashtagJoinRequest("replacement"))
            navigation.setNavigationReady(true)
            val replacementNavigation = navigation.currentState
            val replacementJob = backgroundScope.launch { awaitCancellation() }
            state.installSettingsEventsJobForTesting(replacementJob)
            assertTrue(retiredJob.isCancelled)
            release.countDown()
            retiring.await()

            assertEquals(token, navigation.currentState.boundSessionToken)
            assertEquals(replacementNavigation, navigation.currentState)
            assertNotNull(navigation.currentState.pendingChannelLink)
            assertNotNull(navigation.currentState.pendingHashtag)
            assertTrue(state.hasSettingsEventsJob)
            assertTrue(replacementJob.isActive)
        } finally {
            release.countDown()
            retiring.await()
            retiredJob.cancel()
        }
    }

    @Test fun cancellingDisconnectDoesNotRetireTheCurrentCollectorsOrNavigation() = runTest {
        val port = FakeConnectionPort()
        val gate = CompletableDeferred<Unit>()
        port.disconnectAction = { gate.await() }
        val state = appStateOf(backgroundScope, port = port)
        val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RadioId(UUID.randomUUID()))
        assertTrue(state.navigation.bindNotificationSession(token.radioId, null, token))
        val job = backgroundScope.launch { delay(60_000) }
        state.installSettingsEventsJobForTesting(job)
        state.navigation.stageHashtag(HashtagJoinRequest("retained"))
        val before = state.navigation.state.value

        val disconnect = async { state.disconnect() }
        runCurrent()
        disconnect.cancel()
        disconnect.join()

        assertTrue(disconnect.isCancelled)
        assertTrue(job.isActive)
        assertTrue(state.hasSettingsEventsJob)
        assertEquals(before, state.navigation.state.value)
    }

    @OriginalCase("LifecycleTransitionTests::rapid background-active bounces keep BLE transitions ordered()")
    @Test fun rapidBouncesStayOrdered() = runTest {
        val state = appStateOf(backgroundScope)
        val events = mutableListOf<String>()
        state.bleEnterBackgroundOverride = { delay(20.milliseconds); events += "background" }
        state.bleBecomeActiveOverride = { events += "foreground" }
        repeat(5) {
            state.handleEnterBackground()
            state.handleReturnToForeground()
        }
        assertEquals(10, events.size)
        for (index in events.indices step 2) {
            assertEquals("background", events[index])
            assertEquals("foreground", events[index + 1])
        }
    }

    // endregion

    // region SystemPairingSetupAppStateTests

    private fun accessory(id: UUID, name: String) = SystemPairedAccessory(id, name)
    private fun prompt(vararg items: SystemPairedAccessory) = SystemPairingSetupPrompt(items.toList())
    private val dismissed: () -> Unit = { throw DevicePairingError.Cancelled() }

    @OriginalCase("SystemPairingSetupAppStateTests::scan with two unsaved ASK accessories sets the plural prompt and does not show the picker()")
    @Test fun scanWithTwoUnsavedAccessories() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val idB = UUID.randomUUID(); val idC = UUID.randomUUID()
        port.paired += listOf(accessory(idB, "B"), accessory(idC, "C"))
        state.startDeviceScan().join()
        val pending = assertNotNull(state.connectionUI.pendingSystemPairingSetup)
        assertFalse(state.connectionUI.isBusy)
        assertEquals(listOf(idB, idC), pending.accessories.map { it.id })
        assertEquals(0, port.pickerCalls)
        assertTrue(port.shouldDeferOpportunisticReconnect)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::forgetting one of two unsaved accessories opens the picker and leaves the sibling()")
    @Test fun forgettingOneLeavesTheSibling() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val idB = UUID.randomUUID(); val idC = UUID.randomUUID()
        port.paired += listOf(accessory(idB, "B"), accessory(idC, "C"))
        port.pickerOutcomes += dismissed
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(idB, "B"))
        state.confirmSystemPairingSetup()!!.join()
        assertNull(state.connectionUI.pendingSystemPairingSetup)
        assertEquals(idB, port.lastRemoved)
        assertEquals(1, port.pickerCalls)
        assertEquals(listOf(idC), port.paired.map { it.id })
    }

    @OriginalCase("SystemPairingSetupAppStateTests::declining iOS Remove Accessory does not fail connection or open the picker()", "platform-adaptation")
    @Test fun decliningRemovalFailsNothing() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val idB = UUID.randomUUID(); val idC = UUID.randomUUID()
        port.paired += listOf(accessory(idB, "B"), accessory(idC, "C"))
        port.removeFailure = DevicePairingError.Cancelled()
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(idB, "B"), accessory(idC, "C"))
        state.confirmSystemPairingSetup()!!.join()
        assertFalse(state.connectionUI.showingConnectionFailedAlert)
        assertNull(state.connectionUI.pairingFailureKind)
        assertEquals(0, port.pickerCalls)
        assertEquals(setOf(idB, idC), port.systemAccessoriesMissingDeviceRecord().map { it.id }.toSet())
    }

    @OriginalCase("SystemPairingSetupAppStateTests::pickerRestricted after Forget does not fail connection and retries on become-active()", "platform-adaptation")
    @Test fun pickerUnavailableRetriesOnBecomeActive() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val idB = UUID.randomUUID()
        port.paired += accessory(idB, "B")
        port.pickerOutcomes += { throw DevicePairingError.PickerUnavailable() }
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(idB, "B"))
        state.confirmSystemPairingSetup()!!.join()
        assertFalse(state.connectionUI.isBusy)
        assertTrue(state.connectionUI.shouldCompleteFreshPairingOnForeground)
        assertFalse(state.connectionUI.showingConnectionFailedAlert)
        assertEquals(1, port.pickerCalls)
        assertTrue(port.shouldDeferOpportunisticReconnect)

        port.pickerOutcomes += dismissed
        state.handleBecameActive()
        runCurrent()
        testScheduler.advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(2, port.pickerCalls)
        assertFalse(state.connectionUI.isBusy)
        assertFalse(state.connectionUI.showingConnectionFailedAlert)
        assertFalse(state.connectionUI.shouldCompleteFreshPairingOnForeground)
        assertFalse(port.shouldDeferOpportunisticReconnect)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::auth failure after Forget routes through presentFreshPairingFailure()")
    @Test fun authFailureAfterForgetRoutesAsPinRejected() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val idB = UUID.randomUUID()
        port.paired += accessory(idB, "B")
        port.pickerOutcomes += { throw PairingError.ConnectionFailed(idB, LinkFailure.AuthenticationFailed(), true) }
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(idB, "B"))
        state.confirmSystemPairingSetup()!!.join()
        assertFalse(state.connectionUI.isBusy)
        assertTrue(state.connectionUI.showingConnectionFailedAlert)
        assertEquals(PairingFailureKind.PIN_REJECTED, state.connectionUI.pairingFailureKind)
        assertNotNull(state.connectionUI.connectionFailedTitle)
        assertEquals(idB, state.connectionUI.failedPairingDeviceId)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::device-selection dismiss promotes a queued setup prompt and does not open the picker()")
    @Test fun dismissPromotesQueuedPrompt() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val id = UUID.randomUUID()
        state.connectionUI.queuedSystemPairingSetup = prompt(accessory(id, "Stray"))
        state.handleDeviceSelectionSheetDismissed()
        assertEquals(listOf(id), assertNotNull(state.connectionUI.pendingSystemPairingSetup).accessories.map { it.id })
        assertNull(state.connectionUI.queuedSystemPairingSetup)
        assertEquals(0, port.pickerCalls)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::queued scan while connected presents leftover Forget without dropping the live radio()")
    @Test fun queuedScanKeepsTheLiveRadio() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val liveId = UUID.randomUUID(); val leftover = UUID.randomUUID()
        port.paired += accessory(leftover, "Leftover")
        port.connectionState = DeviceConnectionState.CONNECTED
        port.connectedDevice = deviceOf(liveId)
        state.connectionUI.queuedDeviceScanAfterSelectionDismiss = true
        state.handleDeviceSelectionSheetDismissed()
        settleVirtual()
        assertEquals(listOf(leftover), state.connectionUI.pendingSystemPairingSetup?.accessories?.map { it.id })
        assertFalse(state.connectionUI.isBusy)
        assertEquals(DeviceConnectionState.CONNECTED, port.connectionState)
        assertEquals(liveId, port.connectedDevice?.id)
        assertTrue(port.calls.none { it.startsWith("disconnect") })
        assertEquals(0, port.pickerCalls)
        state.cancelSystemPairingSetup()
        assertNull(state.connectionUI.pendingSystemPairingSetup)
        assertEquals(DeviceConnectionState.CONNECTED, port.connectionState)
        assertEquals(liveId, port.connectedDevice?.id)
        assertFalse(port.shouldDeferOpportunisticReconnect)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::setup-sheet swipe dismiss (pending already nil) ends pairing-flow deferral()")
    @Test fun swipeDismissEndsDeferral() = runTest {
        val port = FakeConnectionPort().apply { isPairingFlowActive = true }
        val state = appStateOf(backgroundScope, port)
        state.connectionUI.pendingSystemPairingSetup = null
        state.handleSystemPairingSetupSheetDismissed()
        assertNull(state.connectionUI.pendingSystemPairingSetup)
        assertFalse(port.shouldDeferOpportunisticReconnect)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::setup-sheet onDismiss with a replacement prompt keeps it()")
    @Test fun dismissWithReplacementKeepsIt() = runTest {
        val port = FakeConnectionPort().apply { isPairingFlowActive = true }
        val state = appStateOf(backgroundScope, port)
        val id = UUID.randomUUID()
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(id, "B"))
        state.handleSystemPairingSetupSheetDismissed()
        assertEquals(listOf(id), assertNotNull(state.connectionUI.pendingSystemPairingSetup).accessories.map { it.id })
        assertTrue(port.shouldDeferOpportunisticReconnect)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::device-selection dismiss does not replace a live setup prompt()")
    @Test fun dismissDoesNotReplaceLivePrompt() = runTest {
        val port = FakeConnectionPort().apply { isPairingFlowActive = true }
        val state = appStateOf(backgroundScope, port)
        val liveId = UUID.randomUUID(); val queuedId = UUID.randomUUID()
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(liveId, "Live"))
        state.connectionUI.queuedSystemPairingSetup = prompt(accessory(queuedId, "Queued"))
        state.handleDeviceSelectionSheetDismissed()
        assertEquals(listOf(liveId), assertNotNull(state.connectionUI.pendingSystemPairingSetup).accessories.map { it.id })
        assertNull(state.connectionUI.queuedSystemPairingSetup)
        assertEquals(0, port.pickerCalls)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::startDeviceScan does not replace a live setup prompt()")
    @Test fun scanDoesNotReplaceLivePrompt() = runTest {
        val port = FakeConnectionPort().apply { isPairingFlowActive = true }
        val state = appStateOf(backgroundScope, port)
        val idA = UUID.randomUUID(); val idB = UUID.randomUUID()
        port.paired += accessory(idB, "B")
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(idA, "A"))
        state.startDeviceScan().join()
        assertEquals(listOf(idA), assertNotNull(state.connectionUI.pendingSystemPairingSetup).accessories.map { it.id })
        assertEquals(0, port.pickerCalls)
        assertTrue(port.shouldDeferOpportunisticReconnect)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::became-active picker retry is skipped while setup is pending()")
    @Test fun becameActiveSkipsRetryWhilePending() = runTest {
        val port = FakeConnectionPort().apply { isPairingFlowActive = true }
        val state = appStateOf(backgroundScope, port)
        val id = UUID.randomUUID()
        port.paired += accessory(id, "B")
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(id, "B"))
        state.connectionUI.shouldShowPickerOnForeground = true
        state.handleBecameActive()
        runCurrent()
        assertTrue(state.connectionUI.shouldShowPickerOnForeground)
        assertEquals(listOf(id), state.connectionUI.pendingSystemPairingSetup?.accessories?.map { it.id })
        assertFalse(state.connectionUI.isBusy)
        assertEquals(0, port.pickerCalls)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::became-active picker retry still scans when no setup prompt is pending()")
    @Test fun becameActiveStillScansWithoutPending() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val id = UUID.randomUUID()
        port.paired += accessory(id, "B")
        state.connectionUI.shouldShowPickerOnForeground = true
        state.handleBecameActive()
        settleVirtual()
        assertFalse(state.connectionUI.shouldShowPickerOnForeground, "flag")
        assertEquals(listOf(id), state.connectionUI.pendingSystemPairingSetup?.accessories?.map { it.id })
        assertFalse(state.connectionUI.isBusy, "busy")
        assertEquals(0, port.pickerCalls)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::cancelling setup ends pairing-flow deferral()")
    @Test fun cancellingEndsDeferral() = runTest {
        val port = FakeConnectionPort().apply { isPairingFlowActive = true }
        val state = appStateOf(backgroundScope, port)
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(UUID.randomUUID(), "B"))
        state.cancelSystemPairingSetup()
        assertNull(state.connectionUI.pendingSystemPairingSetup)
        assertFalse(port.shouldDeferOpportunisticReconnect)
    }

    @OriginalCase("SystemPairingSetupAppStateTests::setup-sheet onDismiss during confirm does not end pairing-flow deferral()")
    @Test fun dismissDuringConfirmKeepsDeferral() = runTest {
        val port = FakeConnectionPort()
        val state = appStateOf(backgroundScope, port)
        val idB = UUID.randomUUID()
        port.paired += accessory(idB, "B")
        port.pickerOutcomes += dismissed
        state.connectionUI.pendingSystemPairingSetup = prompt(accessory(idB, "B"))
        val confirm = state.confirmSystemPairingSetup()!!
        state.handleSystemPairingSetupSheetDismissed()
        assertTrue(port.shouldDeferOpportunisticReconnect)
        confirm.join()
        assertFalse(state.connectionUI.isBusy)
        assertFalse(port.shouldDeferOpportunisticReconnect)
    }

    // endregion
}
