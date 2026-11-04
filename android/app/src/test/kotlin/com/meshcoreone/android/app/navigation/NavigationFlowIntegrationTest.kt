// PortedFrom: MC1Tests/AppState/NavigationCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Real graph, Room, platform notification receiver and mounted adaptive consumers.
package com.meshcoreone.android.app.container

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.AppDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.deeplinks.PendingExternalRoute
import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.app.state.AccessibilityAnnouncer
import com.meshcoreone.android.app.state.AppSession
import com.meshcoreone.android.app.state.AppStatePlatform
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.BluetoothAddress
import com.meshcoreone.android.core.contracts.domain.BluetoothPairingHandle
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.MonitoringOptions
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.TeardownReport
import com.meshcoreone.android.core.contracts.notifications.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.runtime.*
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.chats.list.ChatLinkEnvironment
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import com.meshcoreone.android.feature.chats.list.ContactLinkResolvedTarget
import com.meshcoreone.android.feature.chats.list.ContactLinkResult
import com.meshcoreone.android.feature.chats.list.ContactLinkTargetCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkHandoffReceipt
import com.meshcoreone.android.feature.chats.list.ContactLinkHandoffOutcome
import com.meshcoreone.android.feature.nodes.deps.NodesDataStore
import com.meshcoreone.android.feature.nodes.deps.NodesContactService
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.lang.management.ManagementFactory
import java.util.UUID
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@Config(sdk = [31, 37], qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationFlowIntegrationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val executionName = TestName()
    private lateinit var host: ActivityController<ComponentActivity>
    private val width = mutableStateOf(390.dp)

    @Before fun mountActivity() {
        emitNavigationExecutionBinding(javaClass.name, executionName.methodName, Build.VERSION.SDK_INT.toString())
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    private class PublicationTestGraph(
        val inputs: RuntimeServiceInputs,
        private val slot: RuntimePublicationSlot<PublicationTestGraph>,
    ) : RuntimePublishableServices {
        override val token: SessionToken = inputs.connection.token
        override val publicationSession: MeshCoreSession = inputs.connection.session
        override val publicationFence = inputs.publication.fence(this)
        var retirement: RuntimePublicationRetirement? = null

        override suspend fun awaitChannelQueryAccounting() = Unit
        override suspend fun hydrate() = Unit
        override suspend fun startMonitoring(options: MonitoringOptions) = Unit
        override suspend fun initialSync(forceFullSync: Boolean) = RuntimeSyncResult.Usable
        override suspend fun remoteDisconnected(): Set<UUID> = emptySet()
        override suspend fun reauthenticate(sessionIds: Set<UUID>) = Unit
        override suspend fun resetSyncState() = Unit
        override suspend fun ensureListeners() = Unit
        override suspend fun tearDown(): TeardownReport {
            publicationFence.close()
            retirement = slot.withdraw(this)
            return TeardownReport(SnapshotList.empty())
        }
    }

    private class PublicationTestSimulator(
        private val device: DeviceDTO,
        private val store: RoomPersistenceStore,
    ) : RuntimeSimulatorLink {
        private val link = HarnessLink(FirmwareRadio(device.publicKey), TransportType.SIMULATOR, ConnectionTarget.Simulator)
        override val transport get() = link.transport
        override val type get() = TransportType.SIMULATOR
        override fun register(callbacks: LinkCallbacks): AutoCloseable = link.register(callbacks)
        override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) =
            link.configure(capabilities, platform)
        override suspend fun setSessionLive(token: SessionToken?) = link.setSessionLive(token)
        override suspend fun recordBondVerification(deviceId: UUID, at: Instant) = link.recordBondVerification(deviceId, at)
        override suspend fun clearBondVerification(deviceId: UUID) = link.clearBondVerification(deviceId)
        override suspend fun mayRefreshBond(deviceId: UUID): Boolean = link.mayRefreshBond(deviceId)
        override suspend fun connectSimulator(): DeviceDTO = device
        override suspend fun seedSimulator() { store.saveDevice(device) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class PublicationTestFixture(
        private val test: TestScope,
        private val store: RoomPersistenceStore,
        val slot: RuntimePublicationSlot<PublicationTestGraph> = RuntimePublicationSlot(),
        epoch: ProcessEpoch = ProcessEpoch(UUID.randomUUID()),
        platform: ConnectionPlatform = RuntimePlatformAdapter(newConnectivity()),
    ) {
        val clock = SchedulerClock { test.testScheduler.currentTime }
        val lastConnection = LastConnectionStore(MemoryConnectionPreferences(), clock)
        val links = HarnessLinks { key(7) }
        val graphs = CopyOnWriteArrayList<PublicationTestGraph>()
        val simulatorId = UUID.randomUUID()
        val simulatorDevice = DeviceDTO(
            id = simulatorId, radioId = RadioId(simulatorId), publicKey = key(7), nodeName = "Publication simulator",
        )
        val deviceId = UUID.randomUUID()
        val target = ConnectionTarget.Bluetooth(
            BluetoothPairingHandle(BluetoothAddress(syntheticAddress(deviceId)), null), deviceId,
        )
        var onFactory: suspend (RuntimeServiceInputs, PublicationTestGraph) -> Unit = { inputs, graph ->
            check(inputs.publication.publish(slot.target(graph)) == RuntimePublicationOutcome.PUBLISHED)
        }
        val manager = ConnectionManager(
            devices = store,
            rooms = store,
            contacts = store,
            maintenance = object : ProcessRuntimeMaintenance {
                override suspend fun warmUp() = Unit
                override suspend fun initializeDevicePreferences(device: DeviceDTO) = Unit
            },
            lastConnection = lastConnection,
            platform = platform,
            linkFactory = RuntimeLinkFactory { requested ->
                if (requested == ConnectionTarget.Simulator) PublicationTestSimulator(simulatorDevice, store)
                else links.create(requested)
            },
            serviceFactory = RuntimeServiceFactory { inputs, ownership ->
                val graph = PublicationTestGraph(inputs, slot)
                ownership.register(graph)
                graphs += graph
                onFactory(inputs, graph)
                graph
            },
            observer = ConnectionObserver(
                onServicesAvailable = {}, onConnectionLost = {}, onAutoReconnectStarted = {},
                onDeviceSynced = {}, onAuthenticationFailure = {}, onLastDeviceCleared = {},
            ),
            reporter = RuntimeIssueReporter {},
            clock = clock,
            context = test.backgroundScope.coroutineContext,
            configuration = SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
            jitter = { 0.0 },
            epoch = epoch,
            simulatorDeviceId = simulatorId,
            publicationRegistry = slot.identity,
        )

        fun eventually(message: String, virtualBudgetMillis: Long = 30_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            var advanced = 0L
            while (System.nanoTime() < deadline) {
                test.testScheduler.runCurrent()
                if (condition()) return
                if (advanced < virtualBudgetMillis) {
                    test.testScheduler.advanceTimeBy(5)
                    advanced += 5
                }
                test.testScheduler.runCurrent()
                if (condition()) return
                Thread.sleep(2)
            }
            throw AssertionError("Timed out waiting for $message (state=${manager.connectionState})")
        }
    }

    private fun awaitBlockedOnLock(
        publisherThread: AtomicReference<Thread>,
        holderThread: AtomicReference<Thread>,
        ownerClass: Class<*>,
        methodPrefix: String,
    ) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val publisher = publisherThread.get()
            val holder = holderThread.get()
            if (publisher != null && holder != null) {
                val info = threads.getThreadInfo(publisher.id, Int.MAX_VALUE)
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == holder.id &&
                    info.stackTrace.any {
                        it.className == ownerClass.name && it.methodName.startsWith(methodPrefix)
                    }
                ) return
            }
            Thread.sleep(2)
        }
        throw AssertionError("Thread did not block on the held lock in ${ownerClass.name}.$methodPrefix")
    }

    private fun combineFailures(previous: Throwable?, next: Throwable): Throwable {
        if (previous == null) return next
        if (previous !== next) previous.addSuppressed(next)
        return previous
    }

    private class TrackingDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queued = java.util.ArrayDeque<Runnable>()
        val dispatched = AtomicInteger()
        val completed = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) {
                queued.addLast(block)
                dispatched.incrementAndGet()
            }
        }

        fun runCurrent() {
            while (true) {
                val next = synchronized(lock) {
                    if (queued.isEmpty()) null else queued.removeFirst()
                } ?: return
                try {
                    next.run()
                } finally {
                    completed.incrementAndGet()
                }
            }
        }
    }

    private suspend fun finishContactCancellationTest(
        primaryFailure: Throwable?,
        routingJob: Job,
        failures: ConcurrentLinkedQueue<Throwable>,
        container: AppContainer,
        vararg workers: Pair<Thread, FutureTask<*>>,
        extraCleanup: suspend () -> Unit = {},
    ) = withContext(NonCancellable) {
        var cleanupFailure: Throwable? = null
        for ((thread, task) in workers) {
            if (thread.state == Thread.State.NEW) continue
            try {
                thread.join(10_000)
                check(!thread.isAlive) { "${thread.name} did not terminate" }
                task.get(1, TimeUnit.SECONDS)
            } catch (caught: Throwable) {
                cleanupFailure = combineFailures(cleanupFailure, caught)
            }
        }
        try {
            extraCleanup()
        } catch (caught: Throwable) {
            cleanupFailure = combineFailures(cleanupFailure, caught)
        }
        try {
            withContext(Dispatchers.IO) { withTimeout(10_000) { routingJob.cancelAndJoin() } }
        } catch (caught: Throwable) {
            cleanupFailure = combineFailures(cleanupFailure, caught)
        }
        try {
            container.close()
        } catch (caught: Throwable) {
            cleanupFailure = combineFailures(cleanupFailure, caught)
        }
        while (true) {
            val caught = failures.poll() ?: break
            cleanupFailure = combineFailures(cleanupFailure, caught)
        }
        val cleanup = cleanupFailure ?: return@withContext
        if (primaryFailure == null) throw cleanup
        if (primaryFailure !== cleanup) primaryFailure.addSuppressed(cleanup)
    }

    @After fun destroyActivity() { host.pause().stop().destroy() }

    private fun mount(
        h: ContainerHarness,
        nodes: NodesFeatureDependencies = h.container.nodesFeature,
        motionScale: Float = 0f,
    ) = compose.runOnUiThread {
        host.get().setContent {
            Box(Modifier.width(width.value).height(1000.dp)) {
                MeshCoreTheme(motionScale = motionScale) {
                    NativeNavigationShell(
                        h.container.navigation, chats = h.container.chatsFeature, nodes = nodes,
                    )
                }
            }
        }
    }

    private fun unmount() = compose.runOnUiThread { host.get().setContent {} }

    private suspend fun connect(h: ContainerHarness, target: ConnectionTarget = h.target()) {
        h.manager.connect(target)
        h.settle()
        h.assertReady()
        h.eventually("real graph finishes its initial contact/channel synchronization") {
            h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced &&
                h.container.navigation.state.value.navigationReady
        }
    }

    private fun await(h: ContainerHarness, condition: () -> Boolean) = compose.waitUntil(10_000) {
        h.settle()
        condition()
    }

    private fun confirmationEnabled(): Boolean {
        val nodes = compose.onAllNodesWithTag("contact-link-confirm").fetchSemanticsNodes()
        return nodes.size == 1 && !nodes.single().config.contains(SemanticsProperties.Disabled)
    }

    private suspend fun assertMountedDirectNotification(h: ContainerHarness, contact: ContactDTO) {
        mount(h)
        assertTrue(h.container.handleNotificationResponse(NotificationResponse(
            NotificationPayload.DirectMessage(EntityKey(contact.radioId, contact.id), UUID.randomUUID()), null, null,
        )))
        compose.waitForIdle()
        val state = h.container.navigation.state.value
        assertEquals(AppTab.CHATS, state.selectedTab)
        assertEquals(contact, assertIs<ChatSelection.Direct>(state.chatsSelectedRoute).contact)
        compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
    }

    @Test fun productionNotificationCallbacksReachMountedDirectChannelDiscoveryAndContactDetails() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Notification peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = store.fetchContact(radio, key(20))!!
            val channel = ChannelDTO(radioId = radio, index = 3u, name = "Notification channel")
            store.saveChannel(channel)
            mount(h)
            val service = assertNotNull(h.sessions.current).notificationService
            val callback = assertNotNull(service.onNotificationTapped)
            val generation = h.container.navigation.state.value.generation
            h.appState.wireServicesIfConnected()
            assertSame(callback, service.onNotificationTapped, "Rewire must retain a single binding")
            assertEquals(generation, h.container.navigation.state.value.generation)

            val message = UUID.randomUUID()
            for (payload in listOf(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), message),
                NotificationPayload.ChannelMessage(radio, channel.index, message),
            )) {
                assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
                compose.waitForIdle()
                val state = h.container.navigation.state.value
                assertEquals(AppTab.CHATS, state.selectedTab)
                compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
                when (payload) {
                    is NotificationPayload.DirectMessage -> {
                        assertNull(state.pendingChatContact, "The mounted consumer acknowledges the delivery")
                        assertEquals(contact, assertIs<ChatSelection.Direct>(state.chatsSelectedRoute).contact)
                        assertEquals(ChatSelection.Direct(contact), state.chatsSelectedRoute)
                    }
                    is NotificationPayload.ChannelMessage -> {
                        assertEquals(channel, assertIs<ChatSelection.Channel>(state.chatsSelectedRoute).channel)
                        assertEquals(ChatSelection.Channel(channel), state.chatsSelectedRoute)
                    }
                    else -> error("Only chat payloads are in this matrix")
                }
            }
            for (manual in listOf(true, false)) {
                h.container.connectionPort.updateDevice(assertNotNull(h.appState.connectedDevice).copy(manualAddContacts = manual))
                h.eventually("manual contact-add preference reaches the connected device") {
                    h.appState.connectedDevice?.manualAddContacts == manual
                }
                assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                    NotificationPayload.NewContact(EntityKey(radio, contact.id)), null, null,
                )))
                compose.waitForIdle()
                val state = h.container.navigation.state.value
                assertEquals(AppTab.NODES, state.selectedTab)
                compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
                if (manual) {
                    assertEquals(NavigationDestination.Discovery, state.activeStack.last().destination)
                    assertTrue(state.pendingDiscoveryNavigation)
                } else {
                    assertEquals(contact.id, state.pendingContactDetail?.id)
                    assertEquals(NavigationDestination.ContactDetail(contact), state.activeStack.last().destination)
                }
            }
        } finally { unmount(); h.container.close() }
    }

    @Test fun staleNotificationConfigureCannotRetireReplacementGraphBinding() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        val oldSession = AtomicReference<AppSession?>()
        val platform = object : AppStatePlatform {
            override fun configureNotificationNavigation(session: AppSession, connectedDevice: () -> DeviceDTO?) {
                if (session === oldSession.get() && hold.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release stale configure" }
                }
            }
        }
        val h = ContainerHarness(this, store, platform = platform)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Replacement notification peer") }
        var staleTask: kotlinx.coroutines.Job? = null
        try {
            val target = h.target()
            connect(h, target)
            val first = assertNotNull(h.sessions.current)
            oldSession.set(first)
            hold.set(true)
            val stale = async(Dispatchers.IO) { h.appState.wireServicesIfConnected() }
            staleTask = stale
            h.eventually("old configure pauses after its entry check") { entered.count == 0L }

            h.manager.disconnect()
            h.settle()
            connect(h, target)
            val replacement = assertNotNull(h.sessions.current)
            assertNotSame(first, replacement)
            assertNotEquals(first.token, replacement.token)
            assertEquals(first.token.radioId, replacement.token.radioId)
            val callback = assertNotNull(replacement.notificationService.onNotificationTapped)
            val before = h.container.navigation.state.value
            assertTrue(before.navigationReady)
            assertEquals(replacement.token, before.boundSessionToken)

            release.countDown()
            stale.await()
            val after = h.container.navigation.state.value
            assertSame(callback, replacement.notificationService.onNotificationTapped)
            assertTrue(after.navigationReady)
            assertEquals(before.generation, after.generation)
            assertEquals(before.selectedTab, after.selectedTab)
            assertEquals(before.stacks, after.stacks)
            assertEquals(replacement.token, after.boundSessionToken)
            assertMountedDirectNotification(h, assertNotNull(store.fetchContact(replacement.token.radioId, key(20))))
        } finally {
            release.countDown()
            staleTask?.cancelAndJoin()
            unmount()
            h.container.close()
        }
    }

    @Test fun staleNotificationLossCannotRetireReplacementGraphBinding() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        val lostAnnouncement = UiText.Resource(AppLocalizableStrings.accessibilityConnectionDeviceConnectionLost)
        val announcements = AccessibilityAnnouncer { text ->
            if (text == lostAnnouncement && hold.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release stale loss" }
            }
        }
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val h = ContainerHarness(
            this, store, runtimeContext = runtimeScope.coroutineContext, accessibility = announcements,
        )
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Replacement loss peer") }
        var staleTask: kotlinx.coroutines.Job? = null
        try {
            val target = h.target()
            connect(h, target)
            val first = assertNotNull(h.sessions.current)
            val firstCallback = assertNotNull(first.notificationService.onNotificationTapped)
            val firstGeneration = h.container.navigation.state.value.generation

            val disconnected = async(Dispatchers.IO) { h.manager.disconnect() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!disconnected.isCompleted && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue(disconnected.isCompleted, "Real graph teardown must finish before the held loss")
            disconnected.await()
            assertNull(h.sessions.current)
            assertSame(firstCallback, first.notificationService.onNotificationTapped)
            assertEquals(first.token, h.container.navigation.currentState.boundSessionToken)

            hold.set(true)
            val stale = async(Dispatchers.IO) { h.appState.wireServicesIfConnected() }
            staleTask = stale
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Loss must pause after capturing the old owner")
            assertEquals(firstGeneration + 1, h.container.navigation.currentState.generation)
            assertNull(h.container.navigation.currentState.boundSessionToken)

            connect(h, target)
            val replacement = assertNotNull(h.sessions.current)
            assertNotSame(first, replacement)
            assertNotEquals(first.token, replacement.token)
            assertEquals(first.token.radioId, replacement.token.radioId)
            val callback = assertNotNull(replacement.notificationService.onNotificationTapped)
            val before = h.container.navigation.state.value
            assertTrue(before.navigationReady)
            assertEquals(replacement.token, before.boundSessionToken)

            release.countDown()
            stale.await()
            val after = h.container.navigation.state.value
            assertSame(callback, replacement.notificationService.onNotificationTapped)
            assertTrue(after.navigationReady)
            assertEquals(before.generation, after.generation)
            assertEquals(before.selectedTab, after.selectedTab)
            assertEquals(before.stacks, after.stacks)
            assertEquals(replacement.token, after.boundSessionToken)
            assertSame(replacement.syncCoordinator, h.appState.syncCoordinator)
            assertMountedDirectNotification(h, assertNotNull(store.fetchContact(replacement.token.radioId, key(20))))

            val currentLoss = async(Dispatchers.IO) { h.manager.disconnect() }
            val currentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!currentLoss.isCompleted && System.nanoTime() < currentDeadline) Thread.sleep(2)
            assertTrue(currentLoss.isCompleted, "Replacement graph teardown must finish before loss reconciliation")
            currentLoss.await()
            assertSame(callback, replacement.notificationService.onNotificationTapped)
            h.appState.wireServicesIfConnected()
            assertNull(replacement.notificationService.onNotificationTapped)
            assertFalse(h.container.navigation.currentState.navigationReady)
        } finally {
            release.countDown()
            staleTask?.cancelAndJoin()
            unmount()
            try { h.container.close() } finally { runtimeScope.cancel() }
        }
    }

    @Test fun staleSessionWiringCannotReplaceTheReadyGraphsCollectors() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        val oldSession = AtomicReference<AppSession?>()
        val platform = object : AppStatePlatform {
            override fun onSessionWired(session: AppSession, device: DeviceDTO?) {
                if (session === oldSession.get() && hold.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Stale session wiring was not released" }
                }
            }
        }
        val h = ContainerHarness(this, store, platform = platform)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Replacement collector peer") }
        val events = CopyOnWriteArrayList<MessageEvent>()
        val subscription = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { events += it }
        }
        var staleTask: kotlinx.coroutines.Job? = null
        try {
            val target = h.target()
            connect(h, target)
            val first = assertNotNull(h.sessions.current)
            oldSession.set(first)
            hold.set(true)
            val stale = async(Dispatchers.IO) { h.appState.wireServicesIfConnected() }
            staleTask = stale
            h.eventually("old wiring pauses before collector installation") { entered.count == 0L }

            h.manager.disconnect()
            h.settle()
            connect(h, target)
            val replacement = assertNotNull(h.sessions.current)
            assertNotEquals(first.token, replacement.token)
            assertEquals(first.token.radioId, replacement.token.radioId)
            h.eventually("replacement collectors are subscribed") {
                replacement.syncCoordinator.dataEventBroadcaster.subscriberCount >= 2 &&
                    h.appState.activeSessionJobCount > 0 && h.appState.messageEventDispatcher.activeJobCount == 6
            }
            val appJobs = h.appState.activeSessionJobCount
            val dispatcherJobs = h.appState.messageEventDispatcher.activeJobCount
            val callback = assertNotNull(replacement.notificationService.onNotificationTapped)
            val before = h.container.navigation.state.value

            release.countDown()
            stale.await()
            h.settle()
            assertSame(replacement.syncCoordinator, h.appState.syncCoordinator)
            assertEquals(appJobs, h.appState.activeSessionJobCount)
            assertEquals(dispatcherJobs, h.appState.messageEventDispatcher.activeJobCount)
            assertSame(callback, replacement.notificationService.onNotificationTapped)
            assertEquals(before.generation, h.container.navigation.state.value.generation)
            assertEquals(replacement.token, h.container.navigation.state.value.boundSessionToken)

            val version = h.appState.contactsVersion.value
            replacement.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ContactsChanged)
            h.eventually("replacement app collector receives contacts") { h.appState.contactsVersion.value > version }
            val contact = assertNotNull(store.fetchContact(replacement.token.radioId, key(20)))
            val message = MessageDTO(
                radioId = replacement.token.radioId, contactID = contact.id,
                text = "replacement collector", timestamp = 1u, createdAt = EPOCH,
            )
            replacement.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.DirectMessageReceived(message, contact))
            h.eventually("replacement dispatcher receives a direct message") {
                events.filterIsInstance<MessageEvent.DirectMessageReceived>().any { it.message.id == message.id }
            }
            assertMountedDirectNotification(h, contact)
        } finally {
            release.countDown()
            staleTask?.cancelAndJoin()
            subscription.cancelAndJoin()
            unmount()
            h.container.close()
        }
    }

    @Test fun normallyCompletedSyncStreamDoesNotAbortTheRestOfSessionWiring() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            session.syncCoordinator.dataEventBroadcaster.finish()
            h.settle()
            session.notificationService.onQuickReply = null

            h.appState.wireServicesIfConnected()

            assertSame(session.syncCoordinator, h.appState.syncCoordinator)
            assertNotNull(session.notificationService.onQuickReply)
            assertNotNull(session.notificationService.onNotificationTapped)
        } finally {
            h.container.close()
        }
    }

    @Test fun disconnectedRoomNotificationFromChatsRootShowsAuthenticationWithoutOpeningTheRoom() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = key(40), name = "Root room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 3,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            val navigation = h.container.navigation
            mount(h)
            h.settle()
            compose.waitForIdle()
            val previous = navigation.state.value.stacks.getValue(AppTab.CHATS)
            assertEquals(AppTab.CHATS, navigation.state.value.selectedTab)
            assertEquals(1, previous.size)
            assertNull(navigation.state.value.chatsSelectedRoute)
            val payload = NotificationPayload.RoomMessage(room.name, EntityKey(radio, room.id), UUID.randomUUID())

            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
            await(h) { compose.onAllNodesWithTag("room-authentication-cancel").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("room-authentication-sheet").assertIsDisplayed()
            assertEquals(room.id, navigation.state.value.pendingRoomAuthentication?.id)
            assertEquals(previous, navigation.state.value.stacks.getValue(AppTab.CHATS))
            assertNull(navigation.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            compose.onNodeWithTag("room-authentication-cancel").performClick()
            await(h) { navigation.state.value.pendingRoomAuthentication == null }
            assertEquals(previous, navigation.state.value.stacks.getValue(AppTab.CHATS))
            assertNull(navigation.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
        } finally {
            unmount()
            h.container.close()
        }
    }

    @Test fun disconnectedRoomNotificationKeepsThePreviousChatAndUnreadStateAcrossResizeAndCancel() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Prior peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val peer = assertNotNull(store.fetchContact(radio, key(20)))
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = key(40), name = "Resize room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 3,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            val n = h.container.navigation
            n.navigateToChat(peer)
            mount(h)
            h.settle()
            compose.waitForIdle()
            val previous = n.state.value.stacks.getValue(AppTab.CHATS)
            val payload = NotificationPayload.RoomMessage(room.name, EntityKey(radio, room.id), UUID.randomUUID())

            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
            await(h) { compose.onAllNodesWithTag("room-authentication-cancel").fetchSemanticsNodes().size == 1 }
            val first = n.state.value
            val firstId = assertNotNull(first.roomAuthenticationDeliveryId)
            assertEquals(previous, first.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(peer), first.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            compose.runOnUiThread { width.value = 950.dp }
            await(h) { n.state.value.widthDp >= 950f }
            compose.onNodeWithTag("room-authentication-sheet").assertIsDisplayed()
            assertEquals(firstId, n.state.value.roomAuthenticationDeliveryId)
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            await(h) { compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("room-authentication-password").performTextInput("first attempt")
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
            val replacementId = assertNotNull(n.state.value.roomAuthenticationDeliveryId)
            assertNotEquals(firstId, replacementId)
            assertFalse(n.dismissRoomAuthentication(firstId))
            await(h) {
                compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.EditableText)?.text == ""
            }
            val sheet = compose.runOnIdle {
                assertNotNull(ShadowDialog.getLatestDialog()).also { assertTrue(it.isShowing) }
            }
            repeat(2) {
                compose.runOnIdle {
                    if (sheet.isShowing) {
                        sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                        sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    }
                }
                compose.waitForIdle()
                assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            }
            await(h) { n.state.value.pendingRoomAuthentication == null }
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(peer), n.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
        } finally {
            compose.runOnUiThread { width.value = 390.dp }
            unmount()
            h.container.close()
        }
    }

    @Test fun mountedRoomListAuthenticatesBeforeOpeningAndSurfacesLoginFailure() = runTest {
        val h = ContainerHarness(this, store)
        val roomKey = key(40)
        val allowLogin = java.util.concurrent.atomic.AtomicBoolean(false)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Prior peer")
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x1a -> listOf(
                        Bytes.of(0x06, 0, 0xaa, 0xbb, 0xcc, 0xdd) + le32(1_000),
                        Bytes.of(if (allowLogin.get()) 0x85 else 0x86, 1) + roomKey.prefix(6),
                    )
                    0x1b -> listOf(Bytes.of(1, 2))
                    else -> null
                }
            }
        }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val peer = assertNotNull(store.fetchContact(radio, key(20)))
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = roomKey, name = "List room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 4,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                outPathLength = 0u, lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            val n = h.container.navigation
            n.navigateToChat(peer)
            compose.runOnUiThread { width.value = 950.dp }
            mount(h)
            await(h) { compose.onAllNodesWithContentDescription("List room", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val previous = n.state.value.stacks.getValue(AppTab.CHATS)
            compose.onNodeWithContentDescription("List room", substring = true).performClick()
            await(h) { compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().size == 1 }
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(4L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
            compose.onNodeWithTag("room-authentication-password").performTextInput("test password")
            compose.onNodeWithTag("room-authentication-submit").performClick()
            await(h) { compose.onAllNodesWithTag("room-authentication-error").fetchSemanticsNodes().size == 1 }
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(4L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            allowLogin.set(true)
            compose.onNodeWithTag("room-authentication-submit").performClick()
            await(h) { (n.state.value.chatsSelectedRoute as? ChatSelection.Room)?.session?.isConnected == true }
            await(h) { n.state.value.pendingRoomAuthentication == null }
            val roomEntry = n.state.value.activeStack.last()
            await(h) { compose.onAllNodesWithTag("entry:${roomEntry.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("entry:${roomEntry.id}").assertIsDisplayed()
            h.eventually("mounted room detail marks messages read after authentication") {
                kotlinx.coroutines.runBlocking { store.fetchRemoteNodeSession(EntityKey(radio, room.id))?.unreadCount == 0L }
            }
            assertTrue(h.links.radios.last().sent.any { it[0].toInt() == 0x1a })
            assertTrue(n.back())
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(peer), n.state.value.chatsSelectedRoute)
        } finally {
            compose.runOnUiThread { width.value = 390.dp }
            unmount()
            h.container.close()
        }
    }

    @Test fun cachedRoomAuthenticationWaitsForReadyAndCanRetry() = runTest {
        val roomKey = key(40)
        val roomName = "Syncing room"
        val h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
        )
        h.links.configureRadio = { radio ->
            radio.knownContacts += Bytes.of(3) + roomKey + Bytes.of(3, 0, 0) + Bytes(ByteArray(64)) +
                Bytes.utf8(roomName).paddedOrTruncated(32) + le32(1) + le32(0) + le32(0) + le32(1)
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x04 -> listOf(Bytes.of(2) + le32(1))
                    0x1a -> listOf(
                        Bytes.of(0x06, 0, 0xaa, 0xbb, 0xcc, 0xdd) + le32(1_000),
                        Bytes.of(0x85, 1) + roomKey.prefix(6),
                    )
                    0x1b -> listOf(Bytes.of(1, 2))
                    else -> null
                }
            }
        }
        val connecting = backgroundScope.async { h.manager.connect(h.target()) }
        try {
            h.eventually("session is bound while contact sync is held") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.boundSessionToken == h.sessions.current?.token &&
                    h.sessions.current != null && h.links.radios.last().sent.any { it[0].toInt() == 0x04 }
            }
            val initialSession = assertNotNull(h.sessions.current)
            val radio = initialSession.token.radioId
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = roomKey, name = roomName,
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 3,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = roomKey, name = roomName, typeRawValue = 3u,
                outPathLength = 0u, lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            mount(h)
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                compose.onAllNodesWithContentDescription(roomName, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(roomName, substring = true).performClick()
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("room-authentication-password").performTextInput("test password")
            compose.onNodeWithTag("room-authentication-submit").performClick()
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                compose.onAllNodesWithTag("room-authentication-error").fetchSemanticsNodes().size == 1
            }
            val firmware = h.links.radios.last()
            assertEquals(DeviceConnectionState.SYNCING, h.manager.connectionState)
            assertTrue(firmware.sent.none { it[0].toInt() == 0x1a || it[0].toInt() == 0x1b })
            assertEquals(room.id, h.container.navigation.state.value.pendingRoomAuthentication?.id)
            assertNull(h.container.navigation.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            firmware.push(firmware.knownContacts.single())
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("held contact sync reaches READY") { h.manager.connectionState == DeviceConnectionState.READY }
            connecting.await()
            assertSame(initialSession, h.sessions.current)
            compose.onNodeWithTag("room-authentication-submit").performClick()
            await(h) { (h.container.navigation.state.value.chatsSelectedRoute as? ChatSelection.Room)?.session?.isConnected == true }
            assertTrue(firmware.sent.any { it[0].toInt() == 0x1a })
            assertNull(h.container.navigation.state.value.pendingRoomAuthentication)
        } finally {
            connecting.cancelAndJoin()
            unmount()
            h.container.close()
        }
    }

    @Test fun staleRoomAuthenticationCannotUseASameRadioSuccessor() = runTest {
        val h = ContainerHarness(this, store)
        val target = h.target()
        try {
            connect(h, target)
            val first = assertNotNull(h.sessions.current)
            val room = RemoteNodeSessionDTO(
                radioId = first.token.radioId, publicKey = key(40), name = "Stale room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false,
            )
            val contact = ContactDTO(
                radioId = room.radioId, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                lastHeardTimestamp = null,
            )
            h.container.navigation.requestRoomAuthentication(room)
            val staged = h.container.navigation.state.value
            assertEquals(first.token, staged.roomAuthenticationSessionToken)

            h.manager.disconnect()
            h.settle()
            connect(h, target)
            val successor = assertNotNull(h.sessions.current)
            assertEquals(first.token.radioId, successor.token.radioId)
            assertNotEquals(first.token, successor.token)
            val feature = h.container.chatsFeature
            val isCurrent = feature.captureNavigationSession(staged.roomAuthenticationSessionToken)
            val dependencies = feature.roomForAuthentication(room, staged.roomAuthenticationSessionToken, isCurrent)
            assertFalse(isCurrent())
            val failure = assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException> {
                dependencies.service.join(contact, "secret", false)
            }
            assertEquals(com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError.NotConnected, failure.error)
            assertTrue(h.links.radios.last().sent.none { it[0].toInt() == 0x1a })
        } finally { h.container.close() }
    }

    @Test fun rewiringSameSessionRestoresTheRoomAuthenticationOwner() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val navigation = h.container.navigation
            navigation.clearPerRadioSelection()
            assertNull(navigation.state.value.boundSessionToken)
            h.appState.wireServicesIfConnected()
            h.settle()
            assertSame(session, h.sessions.current)
            assertEquals(session.token, navigation.state.value.boundSessionToken)

            val room = RemoteNodeSessionDTO(
                radioId = session.token.radioId, publicKey = key(40), name = "Rewired room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false,
            )
            navigation.requestRoomAuthentication(room)
            val token = navigation.state.value.roomAuthenticationSessionToken
            assertEquals(session.token, token)
            assertTrue(h.container.chatsFeature.captureNavigationSession(token)())
        } finally { h.container.close() }
    }

    @Test fun aTapWhoseLookupOutlivesItsSessionIsNotAcknowledgedAndCanReachTheReplacement() = runTest {
        var hold = false
        var heldKey: EntityKey? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ContactDTO?>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                if (hold && key == heldKey) {
                    entered.complete(Unit)
                    return release.await()
                }
                return store.fetchContact(key)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Retired tap target") }
        try {
            val target = h.target()
            connect(h, target)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            heldKey = EntityKey(radio, contact.id)
            val response = NotificationResponse(
                NotificationPayload.DirectMessage(assertNotNull(heldKey), UUID.randomUUID()), null, null,
            )
            hold = true
            val pending = async { h.container.handleNotificationResponse(response) }
            entered.await()
            hold = false
            h.manager.disconnect()
            h.eventually("retired notification graph is withdrawn") {
                h.sessions.current == null && !h.container.navigation.state.value.navigationReady
            }
            val beforeCompletion = h.container.navigation.state.value
            release.complete(contact)
            assertFalse(pending.await(), "A stale navigation outcome must leave the tap unacknowledged")
            assertEquals(beforeCompletion, h.container.navigation.state.value)

            connect(h, target)
            assertTrue(h.container.handleNotificationResponse(response))
            assertEquals(ChatSelection.Direct(contact), h.container.navigation.state.value.chatsSelectedRoute)
        } finally {
            release.complete(null)
            h.container.close()
        }
    }

    @Test fun cancellationAfterARealNotificationCommitDoesNotReplayTheQueuedTap() = runTest {
        verifyNotificationCommitWins(observerCancels = true)
    }

    @Test fun failedObserverAfterARealNotificationCommitDoesNotReplayTheQueuedTap() = runTest {
        verifyNotificationCommitWins(observerCancels = false)
    }

    private suspend fun TestScope.verifyNotificationCommitWins(observerCancels: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Committed tap target") }
        var attempts = 0
        var binding: NotificationNavigationBinding? = null
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val session = assertNotNull(h.sessions.current)
            val observed = CompletableDeferred<NavigationOutcome>()
            val cancellation = CompletableDeferred<kotlinx.coroutines.CancellationException>()
            val acknowledgement = CompletableDeferred<Boolean>()
            binding = NotificationNavigationBinding(
                session.notificationService, h.container.navigation, PersistenceNavigationLookup(store),
                connectedDevice = { h.appState.connectedDevice },
                onOutcome = {
                    observed.complete(it)
                    if (observerCancels) throw kotlinx.coroutines.CancellationException("Observer cancelled after navigation")
                    throw IllegalStateException("Observer failed after navigation")
                },
                isCurrentSession = { h.container.isCurrentSession(session) },
            )
            val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
                attempts++
                try {
                    h.container.handleNotificationResponse(response).also { acknowledgement.complete(it) }
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    cancellation.complete(failure)
                    throw failure
                }
            }
            manager.cancelAll()
            delivery.registerCategories(SnapshotList.empty())
            delivery.post(NotificationRequest(
                NotificationId("wp302-committed-tap"), NotificationCategory.DIRECT_MESSAGE, contact.name, "Committed tap",
                false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()),
            ))
            manager.activeNotifications.single().notification.contentIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            h.eventually("the real tap commits before the observer exits") {
                if (observerCancels) cancellation.isCompleted else acknowledgement.isCompleted
            }
            assertEquals(NavigationOutcome.Navigated, observed.await())
            if (observerCancels) {
                assertEquals("Observer cancelled after navigation", cancellation.await().message)
            } else {
                assertTrue(acknowledgement.await())
            }
            val state = h.container.navigation.state.value
            assertEquals(ChatSelection.Direct(contact), state.chatsSelectedRoute)
            assertEquals(1, attempts)

            testScheduler.runCurrent()
            delivery.retryPendingActions()
            h.settle()
            assertEquals(1, attempts, "A committed tap must not be delivered again")
            assertEquals(state.activeStack, h.container.navigation.state.value.activeStack)
        } finally {
            binding?.close()
            manager.cancelAll()
            h.container.close()
        }
    }

    @Test fun currentSessionOptionalCustomAndActionCallbacksKeepTheirExistingSemantics() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val session = assertNotNull(h.sessions.current)
            val contact = EntityKey(radio, UUID.randomUUID())
            val message = UUID.randomUUID()
            val payload = NotificationPayload.DirectMessage(contact, message)
            val tap = NotificationResponse(payload, null, null)
            val original = session.notificationService.onNotificationTapped

            session.notificationService.onNotificationTapped = null
            assertTrue(h.container.handleNotificationResponse(tap))
            var taps = 0
            session.notificationService.onNotificationTapped = { assertEquals(contact, it); taps++ }
            assertTrue(h.container.handleNotificationResponse(tap))
            assertEquals(1, taps)
            session.notificationService.onNotificationTapped = { throw IllegalStateException("custom callback failed") }
            assertTrue(h.container.handleNotificationResponse(tap))
            session.notificationService.onNotificationTapped = {
                throw kotlinx.coroutines.CancellationException("custom callback cancelled")
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> { h.container.handleNotificationResponse(tap) }
            session.notificationService.onNotificationTapped = original

            val calls = mutableListOf<String>()
            session.notificationService.onQuickReply = { key, text ->
                assertEquals(contact, key)
                calls += "reply:$text"
            }
            session.notificationService.onMarkAsRead = { key, id ->
                assertEquals(contact, key)
                assertEquals(message, id)
                calls += "read"
            }
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, NotificationAction.REPLY, "hello")))
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, NotificationAction.MARK_READ, null)))
            assertTrue(h.container.handleNotificationResponse(
                NotificationResponse(NotificationPayload.LowBattery(5), null, null),
            ))
            assertEquals(listOf("reply:hello", "read"), calls)
        } finally { h.container.close() }
    }

    @Test fun reactionNotificationAnchorsReachActualMountedTimelinesAndSurviveWindowChanges() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Reaction peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = store.fetchContact(radio, key(20))!!
            val channel = ChannelDTO(radioId = radio, index = 1u, name = "Reaction channel")
            store.saveChannel(channel)
            mount(h)
            for (isChannel in listOf(false, true)) {
                val message = MessageDTO(
                    radioId = radio, contactID = if (isChannel) null else contact.id,
                    channelIndex = if (isChannel) channel.index else null,
                    text = "Anchored reaction ${if (isChannel) "channel" else "direct"}",
                    timestamp = 100u, createdAt = EPOCH, direction = MessageDirection.INCOMING,
                )
                store.saveMessage(message)
                assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                    NotificationPayload.Reaction(message.id, if (isChannel) null else EntityKey(radio, contact.id),
                        if (isChannel) channel.index else null, if (isChannel) radio else null),
                    null, null,
                )))
                assertEquals(message.id, h.container.navigation.state.value.pendingScrollToMessageID)
                await(h) {
                    compose.onAllNodesWithTag("timeline-bubble:${message.id}", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
                val stack = h.container.navigation.state.value.activeStack
                val selection = h.container.navigation.state.value.chatsSelectedRoute
                for (dp in listOf(600, 744, 780, 834, 390)) {
                    compose.runOnIdle { width.value = dp.dp }
                    compose.onNodeWithTag(if (dp < 600) "navigation-hidden" else "navigation-rail").assertIsDisplayed()
                    assertEquals(stack, h.container.navigation.state.value.activeStack)
                    assertEquals(selection, h.container.navigation.state.value.chatsSelectedRoute)
                    compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
                }
                compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertEquals(stack.dropLast(1), h.container.navigation.state.value.activeStack)
            }
        } finally { unmount(); h.container.close() }
    }

    @Test fun animatedOutgoingChatCannotConsumeAReplacementReactionWhileTheIncomingHistoryIsHeld() = runTest {
        for ((windowWidth, expectedIndex) in listOf(390 to 1u.toUByte(), 834 to 2u.toUByte())) {
            val olderRead = CompletableDeferred<Unit>()
            val releaseOlder = CompletableDeferred<Unit>()
            val holdOlder = AtomicBoolean(false)
            val persistence = object : PersistenceStoreProtocol by store {
                override suspend fun fetchMessages(
                    radioId: RadioId, channelIndex: UByte, limit: Long, offset: Long,
                ): SnapshotList<MessageDTO> {
                    if (holdOlder.get() && channelIndex == expectedIndex && offset > 0) {
                        olderRead.complete(Unit)
                        releaseOlder.await()
                    }
                    return store.fetchMessages(radioId, channelIndex, limit, offset)
                }
            }
            val h = ContainerHarness(this, store, persistenceOverride = persistence)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Outgoing reaction peer") }
            try {
                connect(h)
                val radio = assertNotNull(h.appState.currentRadioId)
                val contact = assertNotNull(store.fetchContact(radio, key(20)))
                val channel = ChannelDTO(radioId = radio, index = expectedIndex, name = "Incoming reaction channel")
                store.saveChannel(channel)
                val outgoingMessage = MessageDTO(
                    radioId = radio, contactID = contact.id, text = "Outgoing conversation",
                    timestamp = 10u, createdAt = EPOCH, direction = MessageDirection.INCOMING,
                )
                store.saveMessage(outgoingMessage)
                val messages = (0 until 80).map { index ->
                    MessageDTO(
                        radioId = radio, channelIndex = channel.index, text = "Incoming history $index",
                        timestamp = (1000 + index).toUInt(), createdAt = EPOCH.plusSeconds(index.toLong()),
                        sortDate = EPOCH.plusSeconds(index.toLong()), direction = MessageDirection.INCOMING,
                    ).also { store.saveMessage(it) }
                }
                val target = messages.first()
                val navigation = h.container.navigation
                navigation.navigateToChat(contact)
                val outgoingEntry = navigation.state.value.activeStack.last().id
                compose.runOnUiThread { width.value = windowWidth.dp }
                mount(h, motionScale = 1f)
                await(h) {
                    compose.onAllNodesWithTag("timeline-bubble:${outgoingMessage.id}", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }

                compose.mainClock.autoAdvance = false
                holdOlder.set(true)
                val reaction = NotificationResponse(
                    NotificationPayload.Reaction(target.id, null, channel.index, radio), null, null,
                )
                assertTrue(h.container.handleNotificationResponse(reaction))
                val first = assertNotNull(navigation.state.value.pendingMessageScroll)
                assertNotEquals(outgoingEntry, first.entryId)
                repeat(6) {
                    compose.mainClock.advanceTimeByFrame()
                    h.settle()
                }
                await(h) { olderRead.isCompleted }
                compose.onNodeWithTag("entry:$outgoingEntry", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("entry:${first.entryId}", useUnmergedTree = true).assertExists()
                assertEquals(first, navigation.state.value.pendingMessageScroll)
                compose.onNode(isDialog()).assertDoesNotExist()

                assertTrue(h.container.handleNotificationResponse(reaction))
                val replacement = assertNotNull(navigation.state.value.pendingMessageScroll)
                assertEquals(first.entryId, replacement.entryId)
                assertEquals(first.messageId, replacement.messageId)
                assertNotEquals(first.deliveryId, replacement.deliveryId)
                assertFalse(navigation.consumePendingMessageScroll(first.entryId, first))
                assertEquals(replacement, navigation.state.value.pendingMessageScroll)

                holdOlder.set(false)
                releaseOlder.complete(Unit)
                compose.mainClock.autoAdvance = true
                await(h) {
                    navigation.state.value.pendingMessageScroll == null &&
                        compose.onAllNodesWithTag("timeline-bubble:${target.id}", useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("timeline-bubble:${target.id}", useUnmergedTree = true).assertIsDisplayed()
                assertEquals(ChatSelection.Channel(channel), navigation.state.value.chatsSelectedRoute)
                compose.onNode(isDialog()).assertDoesNotExist()
            } finally {
                holdOlder.set(false)
                releaseOlder.complete(Unit)
                compose.mainClock.autoAdvance = true
                unmount()
                h.container.close()
            }
        }
    }

    @Test fun actualPlatformTapSurvivesSyncBeyondTheRetryWindowAndReachesTheMountedDestinationExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        val readinessSignals = AtomicInteger()
        val attempts = AtomicInteger()
        val accepted = CopyOnWriteArrayList<UUID>()
        val syncRequested = CompletableDeferred<Unit>()
        val timeRequested = CompletableDeferred<Unit>()
        val h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
            notificationNavigationReady = { readinessSignals.incrementAndGet(); delivery.retryPendingActions() },
        )
        delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
            attempts.incrementAndGet()
            val handled = h.container.handleNotificationResponse(response)
            if (handled) accepted += (response.payload as NotificationPayload.DirectMessage).messageID
            handled
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Cold notification peer")
            radio.respond = { frame ->
                when (frame[0].toInt()) {
                    0x04 -> {
                        syncRequested.complete(Unit)
                        listOf(Bytes.of(2) + le32(1))
                    }
                    0x05 -> {
                        timeRequested.complete(Unit)
                        emptyList()
                    }
                    else -> null
                }
            }
        }
        try {
            val started = h.container.start()
            h.eventually("production activation completes") { started.isCompleted }
            assertFalse(started.isCancelled)
            manager.cancelAll()
            val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
            val contact = ContactDTO(
                radioId = radio, publicKey = key(20), name = "Cold notification peer", lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            val messageId = UUID.randomUUID()
            delivery.registerCategories(SnapshotList.empty())
            delivery.post(NotificationRequest(
                NotificationId("wp302-cold"), NotificationCategory.DIRECT_MESSAGE, contact.name, "Cold tap",
                false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), messageId),
            ))
            manager.activeNotifications.single().notification.contentIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            testScheduler.runCurrent()
            assertNull(h.sessions.current)
            assertEquals(1, h.container.navigation.state.value.activeStack.size, "No destination before real readiness")
            mount(h)
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("callbacks installed while initial contact sync is held") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.navigationReady &&
                    syncRequested.isCompleted
            }
            testScheduler.advanceTimeBy(16_000)
            testScheduler.runCurrent()
            assertEquals(DeviceConnectionState.SYNCING, h.manager.connectionState)
            assertNull(h.container.navigation.state.value.pendingChatContact)
            val attemptsAfterRetryWindow = attempts.get()
            assertTrue(attemptsAfterRetryWindow >= 60)
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(attemptsAfterRetryWindow, attempts.get(), "The capped retry must already have stopped")
            val beforeReady = readinessSignals.get()
            val firmware = h.links.radios.last()
            firmware.push(firmware.knownContacts.single())
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("the READY edge retries while the time response is held") {
                h.manager.connectionState == DeviceConnectionState.READY && readinessSignals.get() > beforeReady &&
                    timeRequested.isCompleted
            }
            assertFalse(connecting.isCompleted, "The later onDeviceSynced callback must still be blocked")
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                val state = h.container.navigation.state.value
                (state.chatsSelectedRoute as? ChatSelection.Direct)?.contact?.id == contact.id &&
                    accepted.contains(messageId) &&
                    compose.onAllNodesWithTag("entry:${state.activeStack.last().id}")
                        .fetchSemanticsNodes().isNotEmpty()
            }
            val state = h.container.navigation.state.value
            val synchronizedContact = assertNotNull(store.fetchContact(radio, contact.publicKey))
            assertEquals(contact.id, synchronizedContact.id, "Sync must preserve the notification's original target")
            compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
            assertEquals(synchronizedContact, assertIs<ChatSelection.Direct>(state.chatsSelectedRoute).contact)
            assertEquals(listOf(messageId), accepted.toList())
            assertFalse(connecting.isCompleted, "Navigation must precede the held time response")
            firmware.push(Bytes.of(9) + le32(EPOCH.epochSecond))
            connecting.await()
            val stack = state.activeStack
            val attemptsAfterDelivery = attempts.get()
            delivery.retryPendingActions()
            h.settle()
            assertEquals(stack, h.container.navigation.state.value.activeStack)
            assertEquals(listOf(messageId), accepted.toList())
            assertEquals(attemptsAfterDelivery, attempts.get())
        } finally {
            h.links.radios.lastOrNull()?.push(Bytes.of(9) + le32(EPOCH.epochSecond))
            manager.cancelAll()
            unmount()
            h.container.close()
        }
    }

    @Test fun sealedChatOfferIsSupersededBeforeMountedAcceptanceDespiteRepeatedParentCancellation() = runTest {
        for (window in listOf(390.dp, 1080.dp)) {
            val h = ContainerHarness(this, store)
            val feature = h.container.chatsFeature
            val origin = ChatContactMount()
            val parent = SupervisorJob(backgroundScope.coroutineContext[Job])
            val routingScope = CoroutineScope(backgroundScope.coroutineContext + parent)
            val offered = CompletableDeferred<ContactLinkHandoffReceipt>()
            val underlying = feature.linksForMount(origin)
            val environment = object : ChatLinkEnvironment by underlying {
                override fun completeResolvedContactLookup(
                    context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                    existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                ): ContactLinkTargetCompletion = underlying.completeResolvedContactLookup(
                    context, target, link, existing, publications, cancellation,
                ).also { if (it is ContactLinkTargetCompletion.Offered) offered.complete(it.receipt) }
            }
            try {
                connect(h)
                feature.registerContactMount(origin)
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Sealed delivery", key(30), ContactType.REPEATER)
                assertTrue(ChatLinkRouter(environment, routingScope).route(uri))
                h.eventually("real router offers an exact delivery without a mounted acceptor") { offered.isCompleted }
                val receipt = offered.await()
                assertNull(h.container.navigation.currentState.pendingContactLink)
                feature.sealContactMount(origin)
                parent.cancel()
                parent.cancel()
                feature.sealContactMount(origin)
                h.eventually("all lookup children quiesce after repeated cancellation") { parent.isCompleted }
                assertEquals(ContactLinkHandoffOutcome.Superseded, receipt.awaitOutcome())
                // A delayed effect cannot reopen the sealed incarnation.
                feature.registerContactMount(origin)
                compose.runOnUiThread { width.value = window }
                mount(h)
                h.settle()
                compose.waitForIdle()
                assertNull(h.container.navigation.currentState.pendingContactLink)
                assertNull(h.container.navigation.currentState.pendingContactDetail)
                val afterSeal = h.container.navigation.currentState
                assertTrue(ChatLinkRouter(environment, routingScope).route(uri))
                h.settle()
                assertEquals(afterSeal, h.container.navigation.currentState)
                assertTrue(h.links.radios.all { radio -> radio.sent.none { it[0].toInt() == 0x09 } })
            } finally {
                parent.cancelAndJoin()
                unmount()
                h.container.close()
            }
        }
    }

    @Test fun mountedChatAcceptanceSurvivesItsOwnDisposalAndLaterNavigationWithoutContradictoryReceipt() = runTest {
        for (window in listOf(390.dp, 1080.dp)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Accepted destination") }
            val origin = ChatContactMount()
            val disposed = AtomicBoolean()
            val offered = CompletableDeferred<ContactLinkHandoffReceipt>()
            val feature = h.container.chatsFeature
            val underlying = feature.linksForMount(origin)
            val environment = object : ChatLinkEnvironment by underlying {
                override fun completeResolvedContactLookup(
                    context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                    existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                ): ContactLinkTargetCompletion = underlying.completeResolvedContactLookup(
                    context, target, link, existing, publications, cancellation,
                ).also { if (it is ContactLinkTargetCompletion.Offered) offered.complete(it.receipt) }
            }
            try {
                connect(h)
                val navigation = h.container.navigation
                val radio = assertNotNull(h.appState.currentRadioId)
                val saved = assertNotNull(store.fetchContact(radio, key(20)))
                navigation.navigateToChat(saved)
                compose.runOnUiThread {
                    width.value = window
                    host.get().setContent {
                        val state by navigation.state.collectAsState()
                        if (state.selectedTab == AppTab.CHATS) DisposableEffect(origin) {
                            feature.registerContactMount(origin)
                            onDispose {
                                feature.sealContactMount(origin)
                                disposed.set(true)
                            }
                        }
                        Box(Modifier.width(width.value).height(1000.dp)) {
                            MeshCoreTheme(motionScale = 0f) {
                                NativeNavigationShell(navigation, chats = feature, nodes = h.container.nodesFeature)
                            }
                        }
                    }
                }
                compose.waitForIdle()
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Untrusted alias", key(20), ContactType.ROOM)
                assertTrue(ChatLinkRouter(environment, backgroundScope).route(uri))
                await(h) { disposed.get() && navigation.currentState.pendingContactDetail?.id == saved.id }
                val receipt = offered.await()
                assertEquals(
                    ContactLinkHandoffOutcome.Accepted(ContactLinkCompletion.Navigated), receipt.awaitOutcome(),
                )
                val accepted = navigation.currentState
                assertEquals(saved, accepted.selectedContact)
                compose.onNodeWithTag("entry:${accepted.activeStack.last().id}").assertIsDisplayed()
                feature.sealContactMount(origin)
                navigation.navigateToMap(51.5, -0.1)
                val newer = navigation.currentState
                feature.sealContactMount(origin)
                h.settle()
                compose.waitForIdle()
                assertEquals(newer, navigation.currentState)
                assertEquals(
                    ContactLinkHandoffOutcome.Accepted(ContactLinkCompletion.Navigated), receipt.awaitOutcome(),
                )
                assertTrue(h.links.radios.all { firmware -> firmware.sent.none { it[0].toInt() == 0x09 } })
            } finally {
                unmount()
                h.container.close()
            }
        }
    }

    @Test fun mountedConsumerAcceptsOnlyTheNewestIdenticalContactOfferAndRetainsItsMetadata() = runTest {
        val h = ContainerHarness(this, store)
        val feature = h.container.chatsFeature
        val origin = ChatContactMount()
        val receipts = CopyOnWriteArrayList<ContactLinkHandoffReceipt>()
        val underlying = feature.linksForMount(origin)
        val environment = object : ChatLinkEnvironment by underlying {
            override fun completeResolvedContactLookup(
                context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
            ): ContactLinkTargetCompletion = underlying.completeResolvedContactLookup(
                context, target, link, existing, publications, cancellation,
            ).also { if (it is ContactLinkTargetCompletion.Offered) receipts += it.receipt }
        }
        try {
            connect(h)
            feature.registerContactMount(origin)
            val uri = h.container.nodesFeature.uriCodec.exportContactUri("Identical replacement", key(30), ContactType.REPEATER)
            val router = ChatLinkRouter(environment, backgroundScope)
            assertTrue(router.route(uri))
            h.eventually("first exact delivery is offered") { receipts.size == 1 }
            assertTrue(router.route(uri))
            h.eventually("identical replacement has a separate receipt") { receipts.size == 2 }
            assertNotSame(receipts[0], receipts[1])
            assertNull(h.container.navigation.currentState.pendingContactLink)
            mount(h)
            await(h) { h.container.navigation.currentState.pendingContactLink != null }
            assertEquals(ContactLinkHandoffOutcome.Superseded, receipts[0].awaitOutcome())
            assertEquals(
                ContactLinkHandoffOutcome.Accepted(ContactLinkCompletion.ConfirmationStaged),
                receipts[1].awaitOutcome(),
            )
            val pending = assertNotNull(h.container.navigation.currentState.pendingContactLink)
            assertEquals("Identical replacement", pending.name)
            assertEquals(key(30), pending.publicKey)
            assertEquals(ContactType.REPEATER, pending.type)
            val accepted = h.container.navigation.currentState
            feature.sealContactMount(origin)
            feature.acceptContactOffers()
            h.settle()
            assertEquals(accepted, h.container.navigation.currentState)
            assertTrue(h.links.radios.all { firmware -> firmware.sent.none { it[0].toInt() == 0x09 } })
        } finally {
            feature.sealContactMount(origin)
            unmount()
            h.container.close()
        }
    }

    @Test fun actualAppChatsContentOwnerRegistersAndSealsRealMessageLinkDeliveriesAcrossMountedAcceptance() = runTest {
        for (windowWidth in listOf(390, 834)) for (acceptFirst in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = {
                it.knownContacts += contactFrame(key(20), "Actual source conversation")
                it.knownContacts += contactFrame(key(21), "Actual linked destination")
            }
            val consumerEnabled = mutableStateOf(false)
            try {
                connect(h)
                val navigation = h.container.navigation
                val feature = h.container.chatsFeature
                val nodes = h.container.nodesFeature
                val radio = assertNotNull(h.sessions.current).token.radioId
                val source = assertNotNull(store.fetchContact(radio, key(20)))
                val target = if (acceptFirst) key(21) else key(30)
                val uri = nodes.uriCodec.exportContactUri("Actual body claim", target, ContactType.REPEATER)
                val message = MessageDTO(
                    radioId = radio, contactID = source.id, text = uri,
                    timestamp = 100u, createdAt = EPOCH, direction = MessageDirection.INCOMING,
                )
                store.saveMessage(message)
                navigation.navigateToChat(source)
                compose.runOnUiThread {
                    width.value = windowWidth.dp
                    host.get().setContent {
                        Box(Modifier.width(width.value).height(1000.dp)) {
                            MeshCoreTheme(motionScale = 0f) {
                                NativeNavigationShell(
                                    navigation, chats = if (consumerEnabled.value) feature else null, nodes = nodes,
                                    content = { destination, navigate ->
                                        if (destination is NavigationDestination.Chat ||
                                            destination == NavigationDestination.Root(AppTab.CHATS)
                                        ) {
                                            AppChatsContent(feature, destination, navigation)
                                        } else {
                                            com.meshcoreone.android.feature.nodes.NodesEntry(
                                                com.meshcoreone.android.core.contracts.FeatureRoute(
                                                    com.meshcoreone.android.core.contracts.FeatureId.NODES,
                                                ),
                                                navigate, nodes,
                                                (destination as? NavigationDestination.ContactDetail)?.let {
                                                    com.meshcoreone.android.feature.nodes.NodesDestination.Detail(it.contact)
                                                } ?: com.meshcoreone.android.feature.nodes.NodesDestination.List,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                await(h) {
                    compose.onAllNodesWithTag("timeline-bubble:${message.id}", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                val body = compose.onNode(
                    hasAnyAncestor(hasTestTag("timeline-bubble:${message.id}")) and
                        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
                    useUnmergedTree = true,
                )
                val action = body.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
                compose.runOnIdle { assertTrue(action.action()) }
                h.eventually("actual AppChatsContent router offers its body link") {
                    navigation.pendingChatContactOffers().size == 1
                }
                val offer = navigation.pendingChatContactOffers().single()
                assertEquals(target, offer.link.publicKey)
                assertEquals(ContactType.REPEATER, offer.link.type)
                assertEquals(radio, offer.target.radioId)
                assertFalse(offer.mount.registrationSealed.get())
                val terminal = backgroundScope.async { offer.receipt.awaitOutcome() }
                h.settle()
                assertFalse(terminal.isCompleted, "The actual source must offer, not navigate before shell acceptance")
                assertEquals(ChatSelection.Direct(source), navigation.currentState.chatsSelectedRoute)
                assertNull(navigation.currentState.pendingContactLink)
                assertNull(navigation.currentState.pendingContactDetail)

                if (acceptFirst) {
                    compose.runOnIdle { consumerEnabled.value = true }
                    compose.waitForIdle()
                    h.eventually("the real shell accepts and actual source disposes") {
                        terminal.isCompleted && offer.mount.registrationSealed.get()
                    }
                    assertEquals(
                        ContactLinkHandoffOutcome.Accepted(ContactLinkCompletion.Navigated), terminal.await(),
                    )
                    val saved = assertNotNull(store.fetchContact(radio, target))
                    assertEquals(saved.id, navigation.state.value.selectedContact?.id)
                    assertEquals(AppTab.NODES, navigation.state.value.selectedTab)
                    compose.onAllNodesWithText(saved.displayName).onFirst().assertIsDisplayed()
                    compose.onNodeWithTag("navigation-back").performClick()
                    compose.waitForIdle()
                    assertEquals(1, navigation.state.value.activeStack.size)
                    assertEquals(
                        ContactLinkHandoffOutcome.Accepted(ContactLinkCompletion.Navigated),
                        offer.receipt.awaitOutcome(),
                    )
                } else {
                    compose.onNodeWithTag("navigation-back").performClick()
                    compose.waitForIdle()
                    h.eventually("actual source disposal seals its unaccepted exact offer") {
                        terminal.isCompleted && offer.mount.registrationSealed.get()
                    }
                    assertEquals(ContactLinkHandoffOutcome.Superseded, terminal.await())
                    val afterDisposal = navigation.currentState
                    compose.runOnIdle { consumerEnabled.value = true }
                    compose.waitForIdle()
                    h.settle()
                    assertEquals(afterDisposal, navigation.currentState)
                    assertNull(navigation.state.value.pendingContactLink)
                    assertNull(navigation.state.value.pendingContactDetail)
                    assertNull(store.fetchContact(radio, target))
                }
                assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
            } finally {
                unmount()
                h.container.close()
            }
        }
    }

    @Test fun exactChatTerminalReceiptWaitsForEveryPublicationGateAndSurvivesConflatedNavigation() = runTest {
        for (acceptFirst in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Published proof") }
            val feature = h.container.chatsFeature
            val origin = ChatContactMount()
            val offered = CompletableDeferred<ContactLinkHandoffReceipt>()
            val held = ContactLinkPublications()
            val underlying = feature.linksForMount(origin)
            val environment = object : ChatLinkEnvironment by underlying {
                override fun completeResolvedContactLookup(
                    context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                    existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                ): ContactLinkTargetCompletion = underlying.completeResolvedContactLookup(
                    context, target, link, existing, publications, cancellation,
                ).also { if (it is ContactLinkTargetCompletion.Offered) offered.complete(it.receipt) }
            }
            try {
                connect(h)
                val navigation = h.container.navigation
                feature.registerContactMount(origin)
                h.container.captureContactLinkContext(key(31), held)
                val before = navigation.state.value
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Published proof", key(20), ContactType.CHAT)
                assertTrue(ChatLinkRouter(environment, backgroundScope).route(uri))
                h.eventually("delivery is offered behind the unrelated predecessor gate") { offered.isCompleted }
                val receipt = offered.await()
                val terminal = backgroundScope.async { receipt.awaitOutcome() }
                if (acceptFirst) {
                    mount(h)
                    h.eventually("mounted consumer accepts the raw exact proposal") {
                        navigation.currentState.pendingContactDetail?.publicKey == key(20)
                    }
                } else {
                    feature.sealContactMount(origin)
                }
                // Even another handoff release cannot make an unpublished terminal a receipt.
                feature.registerContactMount(ChatContactMount())
                navigation.navigateToMap(51.5, -0.1)
                val latest = navigation.currentState
                h.settle()
                assertEquals(before, navigation.state.value)
                assertFalse(terminal.isCompleted, "Raw terminal proof cannot settle before the predecessor publishes")
                held.release()
                h.eventually("any predecessor release wakes the process-owned terminal drain") { terminal.isCompleted }
                assertEquals(
                    if (acceptFirst) ContactLinkHandoffOutcome.Accepted(ContactLinkCompletion.Navigated)
                    else ContactLinkHandoffOutcome.Superseded,
                    terminal.await(),
                )
                assertEquals(AppTab.MAP, navigation.currentState.selectedTab)
                assertEquals(AppTab.MAP, navigation.state.value.selectedTab)
                assertEquals(latest.stacks, navigation.currentState.stacks)
                feature.sealContactMount(origin)
                h.settle()
                assertEquals(terminal.await(), receipt.awaitOutcome())
            } finally {
                held.release()
                feature.sealContactMount(origin)
                unmount()
                h.container.close()
            }
        }
    }

    @Test fun queuedTapRetriesWhenNavigationReadinessIsRestoredAfterTheReadyEdge() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val signalDispatcher = TrackingDispatcher()
        val readinessSignals = AtomicInteger()
        val attempts = AtomicInteger()
        val accepted = CopyOnWriteArrayList<UUID>()
        val syncRequested = CompletableDeferred<Unit>()
        val timeRequested = CompletableDeferred<Unit>()
        lateinit var delivery: AndroidMessagingNotificationDelivery
        val h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
            notificationReadyDispatcher = signalDispatcher,
            notificationNavigationReady = { readinessSignals.incrementAndGet(); delivery.retryPendingActions() },
        )
        delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
            attempts.incrementAndGet()
            val handled = h.container.handleNotificationResponse(response)
            if (handled) accepted += (response.payload as NotificationPayload.DirectMessage).messageID
            handled
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Late binding peer")
            radio.respond = { frame ->
                when (frame[0].toInt()) {
                    0x04 -> {
                        syncRequested.complete(Unit)
                        listOf(Bytes.of(2) + le32(1))
                    }
                    0x05 -> {
                        timeRequested.complete(Unit)
                        emptyList()
                    }
                    else -> null
                }
            }
        }
        try {
            val started = h.container.start()
            h.eventually("production activation completes") { started.isCompleted }
            assertFalse(started.isCancelled)
            manager.cancelAll()
            val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
            val contact = ContactDTO(
                radioId = radio, publicKey = key(20), name = "Late binding peer", lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            val messageId = UUID.randomUUID()
            delivery.registerCategories(SnapshotList.empty())
            delivery.post(NotificationRequest(
                NotificationId("wp302-late-navigation-ready"), NotificationCategory.DIRECT_MESSAGE,
                contact.name, "Queued tap", false, null, null,
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), messageId),
            ))
            manager.activeNotifications.single().notification.contentIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            testScheduler.runCurrent()
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("navigation callbacks are installed while sync is held") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.navigationReady &&
                    syncRequested.isCompleted
            }
            testScheduler.advanceTimeBy(16_000)
            testScheduler.runCurrent()
            val attemptsAfterRetryWindow = attempts.get()
            assertTrue(attemptsAfterRetryWindow >= 60)
            testScheduler.advanceTimeBy(500)
            testScheduler.runCurrent()
            assertEquals(attemptsAfterRetryWindow, attempts.get())
            val navigation = h.container.navigation
            navigation.setNavigationReady(false)
            assertFalse(navigation.state.value.navigationReady)
            val beforeReady = readinessSignals.get()
            val firmware = h.links.radios.last()
            firmware.push(firmware.knownContacts.single())
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("READY retry is queued before the navigation rewire") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    timeRequested.isCompleted && signalDispatcher.dispatched.get() >= 1
            }
            signalDispatcher.runCurrent()
            assertTrue(signalDispatcher.completed.get() >= 1)
            assertEquals(beforeReady, readinessSignals.get(), "An unready navigation binding cannot signal retry")
            assertNull(navigation.state.value.pendingChatContact)
            assertFalse(connecting.isCompleted)

            h.appState.onSessionStateChanged()
            assertTrue(navigation.state.value.navigationReady)
            assertTrue(readinessSignals.get() > beforeReady)
            mount(h)
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                val state = navigation.state.value
                (state.chatsSelectedRoute as? ChatSelection.Direct)?.contact?.id == contact.id &&
                    accepted.contains(messageId) &&
                    compose.onAllNodesWithTag("entry:${state.activeStack.last().id}")
                        .fetchSemanticsNodes().isNotEmpty()
            }
            val state = navigation.state.value
            compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
            assertEquals(listOf(messageId), accepted.toList())
            assertFalse(connecting.isCompleted, "Rebinding must retry before the time response")
            firmware.push(Bytes.of(9) + le32(EPOCH.epochSecond))
            connecting.await()
            delivery.retryPendingActions()
            h.settle()
            assertEquals(listOf(messageId), accepted.toList())
            assertEquals(state.activeStack, navigation.state.value.activeStack)
        } finally {
            h.links.radios.lastOrNull()?.push(Bytes.of(9) + le32(EPOCH.epochSecond))
            manager.cancelAll()
            unmount()
            val closing = async { h.container.close() }
            h.eventually("process cleanup drains the readiness dispatcher") {
                signalDispatcher.runCurrent()
                closing.isCompleted
            }
            closing.await()
        }
    }

    @Test fun queuedReadyRetryRejectsTheRetiredGenerationWhenItsReplacementIsReady() = runTest {
        val signalDispatcher = TrackingDispatcher()
        val signals = CopyOnWriteArrayList<Triple<DeviceConnectionState, SessionToken?, Long>>()
        val requestGates = CopyOnWriteArrayList<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>()
        lateinit var h: ContainerHarness
        h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
            notificationReadyDispatcher = signalDispatcher,
            notificationNavigationReady = {
                val snapshot = h.manager.snapshot.value
                signals += Triple(snapshot.state, snapshot.token, h.container.navigation.currentState.generation)
            },
        )
        h.links.configureRadio = { radio ->
            val syncRequested = CompletableDeferred<Unit>()
            val timeRequested = CompletableDeferred<Unit>()
            requestGates += syncRequested to timeRequested
            radio.respond = { frame ->
                when (frame[0].toInt()) {
                    0x04 -> {
                        syncRequested.complete(Unit)
                        listOf(Bytes.of(2) + le32(0))
                    }
                    0x05 -> {
                        timeRequested.complete(Unit)
                        emptyList()
                    }
                    else -> null
                }
            }
        }
        try {
            val target = h.target()
            val first = backgroundScope.async { h.manager.connect(target) }
            h.eventually("first session installs navigation during its held sync") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.navigationReady &&
                    requestGates.getOrNull(0)?.first?.isCompleted == true
            }
            val firstSession = assertNotNull(h.sessions.current)
            val firstGeneration = h.container.navigation.currentState.generation
            val firstRadio = h.links.radios.last()
            firstRadio.push(Bytes.of(4) + le32(0))
            h.eventually("first session is READY before process observation starts") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    requestGates[0].second.isCompleted
            }
            assertEquals(0, signalDispatcher.dispatched.get())
            val started = h.container.start()
            h.eventually("the already-READY initial snapshot queues a retry") {
                started.isCompleted && signalDispatcher.dispatched.get() >= 1
            }
            assertFalse(started.isCancelled)
            assertFalse(first.isCompleted)

            val disconnect = backgroundScope.async { h.manager.disconnect() }
            h.eventually("first session disconnects before its queued retry") {
                h.manager.connectionState == DeviceConnectionState.DISCONNECTED && disconnect.isCompleted
            }
            disconnect.await()
            assertFailsWith<CancellationException> { first.await() }

            val replacement = backgroundScope.async { h.manager.connect(target) }
            h.eventually("replacement session installs its own navigation") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.sessions.current?.token != firstSession.token &&
                    h.container.navigation.state.value.navigationReady &&
                    h.links.radios.size == 2 && requestGates.getOrNull(1)?.first?.isCompleted == true
            }
            val nextSession = assertNotNull(h.sessions.current)
            val nextGeneration = h.container.navigation.currentState.generation
            assertNotEquals(firstSession.token, nextSession.token)
            assertTrue(nextGeneration > firstGeneration)
            val signalsBeforeReady = signals.size
            val nextRadio = h.links.radios.last()
            nextRadio.push(Bytes.of(4) + le32(0))
            h.eventually("replacement READY retry is queued beside the stale one") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    requestGates[1].second.isCompleted && signalDispatcher.dispatched.get() >= 2
            }
            assertFalse(replacement.isCompleted)
            assertEquals(signalsBeforeReady, signals.size)
            signalDispatcher.runCurrent()
            h.eventually("both queued readiness checks finish") { signalDispatcher.completed.get() >= 2 }
            assertEquals(signalsBeforeReady + 1, signals.size, "Only the replacement may request a retry")
            assertEquals(Triple(DeviceConnectionState.READY, nextSession.token, nextGeneration), signals.last())
            assertFalse(replacement.isCompleted)
            nextRadio.push(Bytes.of(9) + le32(EPOCH.epochSecond))
            replacement.await()
        } finally {
            h.links.radios.lastOrNull()?.push(Bytes.of(9) + le32(EPOCH.epochSecond))
            val closing = async { h.container.close() }
            h.eventually("process cleanup drains queued readiness checks") {
                signalDispatcher.runCurrent()
                closing.isCompleted
            }
            closing.await()
        }
    }

    @Test fun actualReadyDuringTheFinalRetryDelayDeliversTheQueuedTapOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        var readinessSignals = 0
        val routeAttempts = mutableListOf<Long>()
        val h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
            notificationNavigationReady = { readinessSignals++; delivery.retryPendingActions() },
        )
        delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
            routeAttempts += testScheduler.currentTime
            h.container.handleNotificationResponse(response)
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Final delay peer")
            radio.respond = { frame -> if (frame[0].toInt() == 0x04) listOf(Bytes.of(2) + le32(1)) else null }
        }
        try {
            manager.cancelAll()
            val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
            val contact = ContactDTO(
                radioId = radio, publicKey = key(20), name = "Final delay peer", lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            delivery.registerCategories(SnapshotList.empty())
            delivery.post(NotificationRequest(
                NotificationId("wp302-final-delay"), NotificationCategory.DIRECT_MESSAGE, contact.name, "Queued tap",
                false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()),
            ))
            manager.activeNotifications.single().notification.contentIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            testScheduler.runCurrent()
            val firstAttempt = routeAttempts.single()
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("callbacks installed while initial contact sync is held") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.navigationReady &&
                    h.links.radios.last().sent.any { it[0].toInt() == 0x04 }
            }
            val lastAttempt = firstAttempt + 14_750
            assertTrue(testScheduler.currentTime <= lastAttempt)
            testScheduler.advanceTimeBy(lastAttempt - testScheduler.currentTime)
            testScheduler.runCurrent()
            assertEquals(60, routeAttempts.size)
            assertEquals(DeviceConnectionState.SYNCING, h.manager.connectionState)
            assertNull(h.container.navigation.state.value.pendingChatContact)

            val beforeReady = readinessSignals
            val firmware = h.links.radios.last()
            firmware.push(firmware.knownContacts.single())
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("held sync completes during the final retry delay", virtualBudgetMillis = 100) {
                h.manager.connectionState == DeviceConnectionState.READY && readinessSignals > beforeReady
            }
            val retryDeadline = firstAttempt + 15_000
            assertTrue(testScheduler.currentTime < retryDeadline, "READY must occur during the final delay")
            assertEquals(60, routeAttempts.size, "No new drain starts before the old owner finishes")
            testScheduler.advanceTimeBy(retryDeadline - testScheduler.currentTime)
            testScheduler.runCurrent()
            connecting.await()

            mount(h)
            await(h) {
                (h.container.navigation.state.value.chatsSelectedRoute as? ChatSelection.Direct)?.contact?.id == contact.id
            }
            val state = h.container.navigation.state.value
            val synchronizedContact = assertNotNull(store.fetchContact(radio, contact.publicKey))
            assertEquals(contact.id, synchronizedContact.id)
            assertEquals(synchronizedContact, assertIs<ChatSelection.Direct>(state.chatsSelectedRoute).contact)
            compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
            assertEquals(61, routeAttempts.size)
            val stack = state.activeStack
            delivery.retryPendingActions()
            h.settle()
            assertEquals(61, routeAttempts.size)
            assertEquals(stack, h.container.navigation.state.value.activeStack)
        } finally {
            manager.cancelAll()
            unmount()
            h.container.close()
        }
    }

    @Test fun externalAndChatLookupResultsCannotCrossARealRadioSwitchOrNavigateAnOldSavedContact() = runTest {
        val deviceA = UUID(1, 1)
        val deviceB = UUID(2, 2)
        val h = ContainerHarness(this, store, identity = { target ->
            if ((target as ConnectionTarget.Bluetooth).deviceId == deviceA) key(7) else key(8)
        })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Original identity") }
        try {
            for (fromChat in listOf(false, true)) for (found in listOf(false, true)) {
                connect(h, h.target(deviceA))
                val radioA = assertNotNull(h.appState.currentRadioId)
                val original = assertNotNull(store.fetchContact(radioA, key(20)))
                val n = h.container.navigation
                n.clearFailure()
                val entered = CompletableDeferred<RadioId>()
                val lookup = CompletableDeferred<ContactDTO?>()
                val chatRejected = CompletableDeferred<Unit>()
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Original claim", key(20), ContactType.ROOM)
                val external = AppDeepLinkRouter(object : AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, n) {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        entered.complete(radioId)
                        return lookup.await()
                    }
                })
                val chat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        entered.complete(radioId)
                        return lookup.await()
                    }
                }, backgroundScope, com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ ->
                    if (message == "Contact-link lookup context is no longer current") chatRejected.complete(Unit)
                })
                val routing = if (fromChat) {
                    assertTrue(chat.route(uri))
                    null
                } else async { external.routeExternal(uri) }
                assertEquals(radioA, entered.await())
                h.manager.disconnect()
                h.settle()
                connect(h, h.target(deviceB))
                assertNotEquals(radioA, h.appState.currentRadioId)
                n.stageContactLink(ContactLinkRequest("New radio claim", key(31), ContactType.REPEATER))
                n.selectTab(AppTab.MAP)
                val replacement = assertNotNull(n.state.value.pendingContactLink)
                val stack = n.state.value.activeStack
                val beforeCompletion = n.state.value
                lookup.complete(if (found) original else null)
                if (routing != null) assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, routing.await())
                else h.eventually("stale chat producer explicitly rejects completion") { chatRejected.isCompleted }
                assertEquals(beforeCompletion, n.state.value)
                assertSame(replacement, n.state.value.pendingContactLink)
                assertEquals(stack, n.state.value.activeStack)
                assertEquals(AppTab.MAP, n.state.value.selectedTab)
                assertNull(n.state.value.pendingContactDetail)
                assertNull(store.fetchContact(assertNotNull(h.appState.currentRadioId), key(31)))
                h.manager.disconnect()
                h.settle()
            }
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { h.container.close() }
    }

    @Test fun contactProducersCaptureTheCommittedGenerationBeforeItsPublication() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Published contact") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            val previousGeneration = navigation.state.value.generation
            val holdingBinding = ThreadLocal<Boolean>()
            navigation.installCommitBoundary { action ->
                h.manager.withNavigationCommitBoundary {
                    h.sessions.withNavigationCommitBoundary { current ->
                        if (current == null) action() else current.withNavigationCommitBoundary(action)
                    }
                }
                if (holdingBinding.get() == true && navigation.currentState.generation == previousGeneration + 1) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Publication was not released" }
                }
            }
            val binding = async(Dispatchers.IO) {
                holdingBinding.set(true)
                try {
                    navigation.bindNotificationSession(radio, h.appState.connectedDevice?.publicKey, session.token) {
                        h.container.isCurrentSession(session)
                    }
                } finally {
                    holdingBinding.remove()
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "The session commit must precede its publication")
            val currentGeneration = navigation.currentState.generation
            assertEquals(previousGeneration + 1, currentGeneration)
            assertEquals(previousGeneration, navigation.state.value.generation)

            val externalEnvironment = ContainerDeepLinkEnvironment(h.container, navigation)
            val chatEnvironment = h.container.chatsFeature.list.links
            val externalCapture = ContactLinkPublications().use {
                externalEnvironment.captureContactLinkContext(key(20), it)
            }
            assertEquals(currentGeneration, assertIs<CapturedContactLinkContext>(externalCapture).lookup.generation)
            val chatCapture = ContactLinkPublications().use {
                chatEnvironment.captureContactLinkContext(key(20), it)
            }
            assertEquals(currentGeneration, assertIs<CapturedContactLinkContext>(chatCapture).lookup.generation)
            val savedUri = h.container.nodesFeature.uriCodec.exportContactUri("Saved claim", key(20), ContactType.ROOM)
            val external = async(Dispatchers.IO) { AppDeepLinkRouter(externalEnvironment).routeExternal(savedUri) }
            assertEquals(DeepLinkRouteOutcome.Navigated, external.await())
            assertEquals(contact.id, navigation.currentState.selectedContact?.id)

            val unknownKey = key(30)
            val pendingUri = h.container.nodesFeature.uriCodec.exportContactUri("New claim", unknownKey, ContactType.REPEATER)
            val chat = ChatLinkRouter(chatEnvironment, backgroundScope)
            assertTrue(chat.route(pendingUri))
            h.eventually("chat link stages against the committed, unpublished generation") {
                navigation.currentState.pendingContactLink?.publicKey == unknownKey
            }
            val pending = assertNotNull(navigation.currentState.pendingContactLink)
            assertEquals("New claim", pending.name)
            assertEquals(ContactType.REPEATER, pending.type)
            assertEquals(radio, pending.targetRadioId)
            assertEquals(currentGeneration, pending.generation)
            assertEquals(previousGeneration, navigation.state.value.generation)

            release.countDown()
            assertTrue(binding.await())
            assertEquals(navigation.currentState, navigation.state.value)
            assertEquals(pending, navigation.state.value.pendingContactLink)
        } finally {
            release.countDown()
            h.container.close()
        }
    }

    @Test fun newerContactLookupsAndDirectStagingSupersedeOlderInFlightLookups() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = {
            it.knownContacts += contactFrame(key(20), "Older contact")
            it.knownContacts += contactFrame(key(21), "Newer contact")
        }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val oldContact = assertNotNull(store.fetchContact(radio, key(20)))
            val newContact = assertNotNull(store.fetchContact(radio, key(21)))
            val navigation = h.container.navigation
            for ((olderFromChat, newerRoute) in listOf(
                false to "chat", true to "external", false to "direct", true to "direct",
            )) for (olderFound in listOf(false, true)) {
                navigation.clearPendingLinks()
                navigation.clearFailure()
                val current = assertNotNull(h.sessions.current)
                assertTrue(navigation.bindNotificationSession(radio, current.navigationPublicKey, current.token) {
                    h.container.isCurrentSession(current)
                })
                val entered = CompletableDeferred<RadioId>()
                val release = CompletableDeferred<ContactDTO?>()
                val rejected = CompletableDeferred<Unit>()
                suspend fun fetch(radioId: RadioId, publicKey: Bytes): ContactDTO? =
                    if (publicKey == key(20)) {
                        entered.complete(radioId)
                        release.await()
                    } else store.fetchContact(radioId, publicKey)
                val external = AppDeepLinkRouter(object :
                    AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, navigation) {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = fetch(radioId, publicKey)
                })
                val chat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = fetch(radioId, publicKey)
                }, backgroundScope, com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ ->
                    if (message == "Contact-link lookup context is no longer current") rejected.complete(Unit)
                })
                val oldUri = h.container.nodesFeature.uriCodec.exportContactUri("Older claim", key(20), ContactType.ROOM)
                val older = if (olderFromChat) {
                    assertTrue(chat.route(oldUri))
                    null
                } else async { external.routeExternal(oldUri) }
                assertEquals(radio, entered.await())

                val newKey = if (newerRoute == "external") key(21) else key(30)
                val newUri = h.container.nodesFeature.uriCodec.exportContactUri("Newer claim", newKey, ContactType.REPEATER)
                when (newerRoute) {
                    "chat" -> {
                        assertTrue(chat.route(newUri))
                        h.eventually("newer chat contact confirmation is staged") {
                            navigation.state.value.pendingContactLink?.publicKey == newKey
                        }
                    }
                    "external" -> assertEquals(DeepLinkRouteOutcome.Navigated, external.routeExternal(newUri))
                    else -> navigation.stageContactLink(
                        ContactLinkRequest("Newer claim", newKey, ContactType.REPEATER), radio,
                    )
                }
                if (newerRoute == "external") {
                    assertEquals(newContact, navigation.state.value.pendingContactDetail)
                } else {
                    val staged = assertNotNull(navigation.state.value.pendingContactLink)
                    assertEquals("Newer claim", staged.name)
                    assertEquals(ContactType.REPEATER, staged.type)
                    assertEquals(newKey, staged.publicKey)
                    assertEquals(radio, staged.targetRadioId)
                }
                val beforeCompletion = navigation.state.value
                release.complete(if (olderFound) oldContact else null)
                if (older != null) assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, older.await())
                else h.eventually("older chat contact lookup is rejected") { rejected.isCompleted }
                assertEquals(beforeCompletion, navigation.state.value, "The older lookup must not change navigation")
            }
        } finally { h.container.close() }
    }

    @Test fun selfContactFromEitherProducerSupersedesAHeldLookupFromEitherProducer() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Older contact") }
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val oldContact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            for (olderFromChat in listOf(false, true)) for (selfFromChat in listOf(false, true)) {
                for (olderFound in listOf(false, true)) {
                    navigation.clearPendingLinks()
                    assertTrue(navigation.bindNotificationSession(radio, session.navigationPublicKey, session.token) {
                        h.container.isCurrentSession(session)
                    })
                    navigation.navigateToMap(51.5, -0.1)
                    val entered = CompletableDeferred<RadioId>()
                    val release = CompletableDeferred<ContactDTO?>()
                    val rejected = CompletableDeferred<Unit>()
                    val reads = AtomicInteger()
                    suspend fun fetch(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        reads.incrementAndGet()
                        check(publicKey == key(20)) { "Self-contact must not be fetched" }
                        entered.complete(radioId)
                        return release.await()
                    }
                    val external = AppDeepLinkRouter(object :
                        AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, navigation) {
                        override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = fetch(radioId, publicKey)
                    })
                    val chat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                        override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = fetch(radioId, publicKey)
                    }, backgroundScope, com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ ->
                        if (message == "Contact-link lookup context is no longer current") rejected.complete(Unit)
                    })
                    val oldUri = h.container.nodesFeature.uriCodec.exportContactUri("Older claim", key(20), ContactType.ROOM)
                    val older = if (olderFromChat) {
                        assertTrue(chat.route(oldUri))
                        null
                    } else async { external.routeExternal(oldUri) }
                    assertEquals(radio, entered.await())
                    val selfUri = h.container.nodesFeature.uriCodec.exportContactUri(
                        "Self", session.navigationPublicKey, ContactType.CHAT,
                    )
                    if (selfFromChat) assertTrue(chat.route(selfUri))
                    else assertEquals(DeepLinkRouteOutcome.IgnoredSelfContact, external.routeExternal(selfUri))
                    assertEquals(1, reads.get())
                    assertEquals(if (selfFromChat) AppTab.MAP else AppTab.CHATS, navigation.state.value.selectedTab)
                    val beforeOlderCompletion = navigation.state.value
                    release.complete(if (olderFound) oldContact else null)
                    if (older != null) assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, older.await())
                    else h.eventually("older chat lookup is rejected after self contact") { rejected.isCompleted }
                    assertEquals(beforeOlderCompletion, navigation.state.value)
                    assertNull(navigation.state.value.pendingContactLink)
                    assertNull(navigation.state.value.pendingContactDetail)
                }
            }
        } finally { h.container.close() }
    }

    @Test fun newerChatContactPreventsOlderExternalSelfContactFromChangingSelection() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val navigation = h.container.navigation
            navigation.navigateToMap(51.5, -0.1)
            val newKey = key(30)
            val newUri = h.container.nodesFeature.uriCodec.exportContactUri("Newer claim", newKey, ContactType.REPEATER)
            val chat = ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope)
            val base = ContainerDeepLinkEnvironment(h.container, navigation)
            val external = AppDeepLinkRouter(object : AppDeepLinkEnvironment by base {
                override fun captureContactLinkContext(publicKey: Bytes, publications: ContactLinkPublications) =
                    base.captureContactLinkContext(publicKey, publications).also {
                        if (publicKey == session.navigationPublicKey) assertTrue(chat.route(newUri))
                    }

                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? =
                    error("Self-contact must not be fetched")
            })
            val selfUri = h.container.nodesFeature.uriCodec.exportContactUri(
                "Older self claim", session.navigationPublicKey, ContactType.CHAT,
            )
            assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, external.routeExternal(selfUri))
            h.eventually("newer chat contact confirmation is staged") {
                navigation.state.value.pendingContactLink?.publicKey == newKey
            }
            val pending = assertNotNull(navigation.state.value.pendingContactLink)
            assertEquals("Newer claim", pending.name)
            assertEquals(ContactType.REPEATER, pending.type)
            assertEquals(session.token.radioId, pending.targetRadioId)
            assertEquals(AppTab.MAP, navigation.state.value.selectedTab)
            assertNull(navigation.state.value.pendingContactDetail)
        } finally { h.container.close() }
    }

    @Test fun coldContactDeliverySurvivesTheInitialHandshakeAndRequiresMountedConsent() = runTest {
        val h = ContainerHarness(this, store)
        val handshake = CompletableDeferred<Unit>()
        val chatScheduler = TestCoroutineScheduler()
        val deferredChat = CoroutineScope(SupervisorJob() + StandardTestDispatcher(chatScheduler))
        h.links.configureRadio = { radio -> radio.connectGate = handshake }
        try {
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("physical handshake is held before radio assignment") { h.links.radios.isNotEmpty() }
            val n = h.container.navigation
            val router = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n))
            val pending = PendingExternalRoute(router::routeExternal)
            val uri = h.container.nodesFeature.uriCodec.exportContactUri("Handshake claim", key(30), ContactType.ROOM)
            val delivery = PendingExternalRoute.Delivery("wp302-handshake", uri)
            pending.markReady()
            assertEquals(
                PendingExternalRoute.SubmitOutcome.Routed(DeepLinkRouteOutcome.ConfirmationStaged),
                pending.submit(delivery),
            )
            val unbound = assertNotNull(n.state.value.pendingContactLink)
            assertNull(unbound.targetRadioId)
            val chatUri = h.container.nodesFeature.uriCodec.exportContactUri("Queued handshake claim", key(31), ContactType.REPEATER)
            assertTrue(ChatLinkRouter(h.container.chatsFeature.list.links, deferredChat).route(chatUri))
            mount(h)
            await(h) { confirmationEnabled() }
            handshake.complete(Unit)
            connecting.await()
            h.settle()
            h.assertReady()
            val rebound = assertNotNull(n.state.value.pendingContactLink)
            assertEquals(h.appState.currentRadioId, rebound.targetRadioId)
            assertEquals(n.state.value.generation, rebound.generation)
            assertEquals(unbound.name, rebound.name)
            assertEquals(unbound.publicKey, rebound.publicKey)
            assertEquals(unbound.type, rebound.type)
            assertNotEquals(unbound.deliveryId, rebound.deliveryId)
            assertFalse(n.completeContactLink(unbound))
            assertNull(pending.snapshot())
            assertEquals(PendingExternalRoute.SubmitOutcome.Duplicate, pending.submit(delivery))
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-name").assertTextEquals("Handshake claim")
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
            assertNull(store.fetchContact(assertNotNull(rebound.targetRadioId), key(30)))
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            chatScheduler.runCurrent()
            val queued = assertNotNull(n.state.value.pendingContactLink)
            assertEquals(key(31), queued.publicKey)
            assertEquals(rebound.targetRadioId, queued.targetRadioId)
            assertEquals(n.state.value.generation, queued.generation)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-name").assertTextEquals("Queued handshake claim")
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { deferredChat.cancel(); unmount(); h.container.close() }
    }

    @Test fun preConnectionContactLookupSurvivesRetryWithinTheFirstConnectionSubmission() = runTest {
        val h = ContainerHarness(this, store)
        val chatScheduler = TestCoroutineScheduler()
        val deferredChat = CoroutineScope(SupervisorJob() + StandardTestDispatcher(chatScheduler))
        var attempts = 0
        h.links.configureRadio = { radio ->
            attempts++
            if (attempts == 1) radio.connectFailure = IllegalStateException("First attempt failed")
        }
        try {
            val n = h.container.navigation
            val uri = h.container.nodesFeature.uriCodec.exportContactUri("Retry claim", key(31), ContactType.REPEATER)
            assertTrue(ChatLinkRouter(h.container.chatsFeature.list.links, deferredChat).route(uri))
            assertNull(n.state.value.pendingContactLink)
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("the first submission succeeds after retry", virtualBudgetMillis = 30_000) {
                connecting.isCompleted
            }
            connecting.await()
            h.settle()
            h.assertReady()
            assertTrue(attempts > 1)

            chatScheduler.runCurrent()
            val staged = assertNotNull(n.state.value.pendingContactLink)
            assertEquals("Retry claim", staged.name)
            assertEquals(key(31), staged.publicKey)
            assertEquals(ContactType.REPEATER, staged.type)
            assertEquals(h.appState.currentRadioId, staged.targetRadioId)
            assertEquals(n.state.value.generation, staged.generation)
        } finally {
            deferredChat.cancel()
            h.container.close()
        }
    }

    @Test fun pendingSubmissionCapturesSurviveUnexposedFactoryFailureAndSuccessfulPendingClear() = runTest {
        val saving = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveDevice(device: DeviceDTO) {
                saving.complete(Unit)
                releaseSave.await()
                store.saveDevice(device)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val created = AtomicInteger()
        h.lifecycle.onCreated = {
            if (created.incrementAndGet() == 1) throw IllegalStateException("Before registry publication")
        }
        val target = h.target()
        val idleStamp = h.manager.captureNavigationLifecycle()
        val connecting = async(start = CoroutineStart.UNDISPATCHED) { h.manager.connect(target) }
        var duplicate: Deferred<Unit>? = null
        try {
            assertEquals(target.deviceId, h.manager.activeConnectionAttemptDeviceId)
            assertTrue(h.links.radios.isEmpty(), "Capture must precede the first generation")
            val pendingStamp = h.manager.captureNavigationLifecycle()
            val context = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(30), it)
            }
            assertIs<ContactLinkLookupResolution.Current>(h.container.resolveContactLinkContext(context, key(30)))
            val coalesced = async(start = CoroutineStart.UNDISPATCHED) { h.manager.connect(target) }
            duplicate = coalesced
            val duplicateStamp = h.manager.captureNavigationLifecycle()
            assertTrue(h.links.radios.isEmpty())

            h.eventually("the retry publishes its graph before device persistence", virtualBudgetMillis = 30_000) {
                saving.isCompleted && h.sessions.current != null
            }
            val graph = assertNotNull(h.sessions.current)
            assertEquals(2, created.get())
            assertTrue(h.lifecycle.created.first().isTornDown)
            assertEquals(RuntimePublicationCounts(1, 0), h.sessions.counts)
            for (stamp in listOf(idleStamp, pendingStamp, duplicateStamp)) {
                assertTrue(h.manager.isInitialNavigationSession(stamp, graph.token, h.sessions.publicationIdentity, graph))
            }
            val firstTarget = assertIs<ContactLinkLookupResolution.Current>(
                h.container.resolveContactLinkContext(context, key(30)),
            )
            assertEquals(graph.token.radioId, firstTarget.radioId)

            releaseSave.complete(Unit)
            h.eventually("both callers complete the same submission", virtualBudgetMillis = 30_000) {
                connecting.isCompleted && coalesced.isCompleted
            }
            connecting.await()
            coalesced.await()
            h.settle()
            h.assertReady()
            assertSame(graph, h.sessions.current)
            for (stamp in listOf(idleStamp, pendingStamp, duplicateStamp)) {
                assertTrue(h.manager.isInitialNavigationSession(stamp, graph.token, h.sessions.publicationIdentity, graph))
            }
            val resolved = assertIs<ContactLinkLookupResolution.Current>(
                h.container.resolveContactLinkContext(context, key(30)),
            )
            assertEquals(ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.ConfirmationStaged),
                ContactLinkPublications().use {
                    h.container.completeResolvedContactLookup(
                        context, resolved, ContactLinkRequest("Pending claim", key(30), ContactType.REPEATER),
                        null, false, it,
                    )
                })
            assertEquals(graph.token.radioId, assertNotNull(h.container.navigation.currentState.pendingContactLink).targetRadioId)
        } finally {
            releaseSave.complete(Unit)
            duplicate?.cancelAndJoin()
            connecting.cancelAndJoin()
            h.container.close()
        }
    }

    @Test fun publishedGraphRemainsFirstExposureWhenItsFactoryThrowsAndRetryReplacesIt() = runTest {
        val f = PublicationTestFixture(this, store)
        var firstOwnerStamp: ConnectionManager.NavigationLifecycleStamp? = null
        val replacementEntered = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        f.onFactory = { inputs, graph ->
            val target = f.slot.target(graph)
            if (f.graphs.size > 1) {
                replacementEntered.complete(Unit)
                releaseReplacement.await()
            }
            assertEquals(RuntimePublicationOutcome.PUBLISHED, inputs.publication.publish(target))
            assertEquals(RuntimePublicationOutcome.ALREADY_PUBLISHED, inputs.publication.publish(target))
            if (f.graphs.size == 1) {
                firstOwnerStamp = f.manager.captureNavigationLifecycle()
                assertTrue(f.manager.isInitialNavigationSession(
                    assertNotNull(firstOwnerStamp), graph.token, f.slot.identity, graph,
                ))
                throw IllegalStateException("Factory failed after publishing its graph")
            }
        }
        val idleStamp = f.manager.captureNavigationLifecycle()
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            assertEquals(f.target.deviceId, f.manager.activeConnectionAttemptDeviceId)
            assertTrue(f.graphs.isEmpty())
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the replacement owner enters its factory") { replacementEntered.isCompleted }
            assertNull(f.slot.current)
            val replacementStamp = f.manager.captureNavigationLifecycle()
            assertTrue(f.manager.isNavigationLifecycleCurrent(replacementStamp))
            releaseReplacement.complete(Unit)
            f.eventually("the submission retries after the published factory failure") { work.isCompleted }
            work.await()
            val first = f.graphs.first()
            val replacement = f.graphs.last()
            assertNotSame(first, replacement)
            assertEquals(2, f.graphs.size)
            assertEquals(RuntimePublicationRetirement.LIVE, first.retirement)
            assertTrue(first.publicationFence.isClosed)
            assertSame(replacement, f.slot.current)
            assertEquals(RuntimePublicationCounts(2, 1), f.slot.counts)
            for (stamp in listOf(idleStamp, pendingStamp, assertNotNull(firstOwnerStamp))) {
                assertFalse(f.manager.isInitialNavigationSession(stamp, replacement.token, f.slot.identity, replacement))
            }
            assertTrue(f.manager.isInitialNavigationSession(
                replacementStamp, replacement.token, f.slot.identity, replacement,
            ))
            val fresh = f.manager.captureNavigationLifecycle()
            assertTrue(f.manager.isInitialNavigationSession(fresh, replacement.token, f.slot.identity, replacement))
        } finally {
            releaseReplacement.complete(Unit)
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun cancelledUnpublishedOwnerCannotPublishOrRetargetItsCapture() = runTest {
        val f = PublicationTestFixture(this, store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onFactory = { _, _ ->
            entered.complete(Unit)
            release.await()
        }
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("factory is held before registry publication") { entered.isCompleted }
            val unexposed = f.graphs.single()
            val authority = unexposed.inputs.publication
            val target = f.slot.target(unexposed)
            val wrongSlot = RuntimePublicationSlot<PublicationTestGraph>()
            assertFailsWith<IllegalArgumentException> { wrongSlot.target(unexposed) }
            assertEquals(RuntimePublicationRetirement.DIFFERENT_REGISTRY, wrongSlot.withdraw(unexposed))
            assertNull(f.slot.current)
            assertEquals(RuntimePublicationCounts(0, 0), f.slot.counts)
            work.cancel()
            f.eventually("the cancelled owner tears down its unexposed graph") {
                work.isCompleted && unexposed.retirement != null
            }
            assertEquals(RuntimePublicationRetirement.NEVER_PUBLISHED, unexposed.retirement)
            assertEquals(RuntimePublicationCounts(0, 0), f.slot.counts)
            assertEquals(RuntimePublicationOutcome.STALE_OWNER, authority.publish(target))
            assertFalse(f.manager.isNavigationLifecycleCurrent(pendingStamp))

            f.onFactory = { inputs, graph ->
                assertEquals(RuntimePublicationOutcome.PUBLISHED, inputs.publication.publish(f.slot.target(graph)))
            }
            val replacementWork = backgroundScope.async { f.manager.connect(f.target) }
            f.eventually("a new submission publishes after cancellation") { replacementWork.isCompleted }
            replacementWork.await()
            val replacement = assertNotNull(f.slot.current)
            assertNotSame(unexposed, replacement)
            assertFalse(f.manager.isInitialNavigationSession(pendingStamp, replacement.token, f.slot.identity, replacement))
            assertTrue(f.manager.isInitialNavigationSession(
                f.manager.captureNavigationLifecycle(), replacement.token, f.slot.identity, replacement,
            ))
        } finally {
            release.complete(Unit)
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun failedSubmissionDoesNotBecomeAnUnboundCaptureForTheNextSubmission() = runTest {
        val f = PublicationTestFixture(this, store)
        f.onFactory = { _, _ -> throw IllegalStateException("Factory failed before publication") }
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { f.manager.connect(f.target) }
        }
        try {
            val failedStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the first submission reaches terminal failure") { work.isCompleted }
            assertIs<IllegalStateException>(work.await().exceptionOrNull())
            assertTrue(f.graphs.isNotEmpty())
            assertTrue(f.graphs.all { it.retirement == RuntimePublicationRetirement.NEVER_PUBLISHED })
            assertEquals(RuntimePublicationCounts(0, 0), f.slot.counts)
            assertFalse(f.manager.isNavigationLifecycleCurrent(failedStamp))

            f.onFactory = { inputs, graph ->
                assertEquals(RuntimePublicationOutcome.PUBLISHED, inputs.publication.publish(f.slot.target(graph)))
            }
            val replacementWork = backgroundScope.async { f.manager.connect(f.target) }
            f.eventually("the later submission publishes a new graph") { replacementWork.isCompleted }
            replacementWork.await()
            val replacement = assertNotNull(f.slot.current)
            assertFalse(f.manager.isInitialNavigationSession(failedStamp, replacement.token, f.slot.identity, replacement))
            assertTrue(f.manager.isInitialNavigationSession(
                f.manager.captureNavigationLifecycle(), replacement.token, f.slot.identity, replacement,
            ))
        } finally {
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun duplicateAndDisplacedGraphRetirementUseOneSlotAndRejectCrossManagerStamps() = runTest {
        val slot = RuntimePublicationSlot<PublicationTestGraph>()
        val epoch = ProcessEpoch(UUID.randomUUID())
        val firstManager = PublicationTestFixture(this, store, slot, epoch)
        val secondManager = PublicationTestFixture(this, store, slot, epoch)
        try {
            val firstStamp = firstManager.manager.captureNavigationLifecycle()
            val firstWork = backgroundScope.async { firstManager.manager.connect(firstManager.target) }
            firstManager.eventually("the first manager publishes") { firstWork.isCompleted }
            firstWork.await()
            val first = assertNotNull(slot.current)
            val authority = first.inputs.publication
            val target = slot.target(first)
            assertEquals(RuntimePublicationOutcome.ALREADY_PUBLISHED, authority.publish(target))
            assertEquals(RuntimePublicationCounts(1, 0), slot.counts)
            val wrongSlot = RuntimePublicationSlot<PublicationTestGraph>()
            assertFailsWith<IllegalArgumentException> { wrongSlot.target(first) }
            assertEquals(RuntimePublicationRetirement.DIFFERENT_REGISTRY, wrongSlot.withdraw(first))
            assertEquals(RuntimePublicationCounts(0, 0), wrongSlot.counts)

            val secondWork = backgroundScope.async { secondManager.manager.connect(secondManager.target) }
            secondManager.eventually("the second manager replaces the shared live graph") { secondWork.isCompleted }
            secondWork.await()
            val second = assertNotNull(slot.current)
            assertNotSame(first, second)
            assertEquals(first.token, second.token, "Equal epoch, generation and radio do not identify a manager")
            assertEquals(RuntimePublicationCounts(2, 0), slot.counts)
            assertEquals(RuntimePublicationOutcome.DISPLACED, authority.publish(target))
            assertFalse(firstManager.manager.isInitialNavigationSession(firstStamp, first.token, slot.identity, first))
            assertFalse(secondManager.manager.isInitialNavigationSession(firstStamp, second.token, slot.identity, second))
            assertEquals(RuntimePublicationRetirement.DISPLACED, slot.withdraw(first))
            assertEquals(RuntimePublicationRetirement.ALREADY_WITHDRAWN, slot.withdraw(first))
            assertSame(second, slot.current)
            assertEquals(RuntimePublicationCounts(2, 1), slot.counts)
            assertEquals(RuntimePublicationOutcome.WITHDRAWN, authority.publish(target))
            firstManager.manager.close()
            assertEquals(RuntimePublicationRetirement.ALREADY_WITHDRAWN, first.retirement)
            assertSame(second, slot.current)
            assertEquals(RuntimePublicationCounts(2, 1), slot.counts)
            assertEquals(RuntimePublicationOutcome.STALE_OWNER, authority.publish(target))
        } finally {
            firstManager.manager.close()
            secondManager.manager.close()
        }
        assertNull(slot.current)
        assertEquals(RuntimePublicationCounts(2, 2), slot.counts)
    }

    @Test fun graphClosedBeforePublicationLeavesNoReceiptAndTheRetryCanPublish() = runTest {
        val f = PublicationTestFixture(this, store)
        f.onFactory = { inputs, graph ->
            val target = f.slot.target(graph)
            if (f.graphs.size == 1) {
                graph.publicationFence.close()
                assertEquals(RuntimePublicationOutcome.CLOSED_GRAPH, inputs.publication.publish(target))
                assertNull(f.slot.current)
                assertEquals(RuntimePublicationCounts(0, 0), f.slot.counts)
                throw IllegalStateException("Graph closed before publication")
            }
            assertEquals(RuntimePublicationOutcome.PUBLISHED, inputs.publication.publish(target))
        }
        val idleStamp = f.manager.captureNavigationLifecycle()
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the unexposed closed graph is retried") { work.isCompleted }
            work.await()
            assertEquals(RuntimePublicationRetirement.NEVER_PUBLISHED, f.graphs.first().retirement)
            assertEquals(RuntimePublicationCounts(1, 0), f.slot.counts)
            val published = assertNotNull(f.slot.current)
            assertTrue(f.manager.isInitialNavigationSession(idleStamp, published.token, f.slot.identity, published))
            assertTrue(f.manager.isInitialNavigationSession(pendingStamp, published.token, f.slot.identity, published))
        } finally {
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun withdrawingAnUnpublishedGraphDoesNotCountItOrAllowItsPublication() = runTest {
        val f = PublicationTestFixture(this, store)
        f.onFactory = { inputs, graph ->
            val target = f.slot.target(graph)
            if (f.graphs.size == 1) {
                assertEquals(RuntimePublicationRetirement.NEVER_PUBLISHED, f.slot.withdraw(graph))
                assertEquals(RuntimePublicationRetirement.ALREADY_WITHDRAWN, f.slot.withdraw(graph))
                assertEquals(RuntimePublicationOutcome.WITHDRAWN, inputs.publication.publish(target))
                assertEquals(RuntimePublicationCounts(0, 0), f.slot.counts)
                throw IllegalStateException("Graph was withdrawn before publication")
            }
            assertEquals(RuntimePublicationOutcome.PUBLISHED, inputs.publication.publish(target))
        }
        val idleStamp = f.manager.captureNavigationLifecycle()
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the submission retries after an unexposed withdrawal") { work.isCompleted }
            work.await()
            assertEquals(RuntimePublicationRetirement.ALREADY_WITHDRAWN, f.graphs.first().retirement)
            assertEquals(RuntimePublicationCounts(1, 0), f.slot.counts)
            val graph = assertNotNull(f.slot.current)
            assertTrue(f.manager.isInitialNavigationSession(idleStamp, graph.token, f.slot.identity, graph))
            assertTrue(f.manager.isInitialNavigationSession(pendingStamp, graph.token, f.slot.identity, graph))
        } finally {
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun graphClosureWhilePublicationWaitsForTheSlotCannotExposeTheClosedGraph() = runTest {
        val f = PublicationTestFixture(this, store)
        val slotHeld = CountDownLatch(1)
        val releaseSlot = CountDownLatch(1)
        val holderThread = AtomicReference<Thread>()
        val publisherThread = AtomicReference<Thread>()
        val entered = CompletableDeferred<PublicationTestGraph>()
        val result = CompletableDeferred<RuntimePublicationOutcome>()
        val holder = async(Dispatchers.IO) {
            f.slot.withCurrent {
                holderThread.set(Thread.currentThread())
                slotHeld.countDown()
                check(releaseSlot.await(10, TimeUnit.SECONDS)) { "Publication slot was not released" }
            }
        }
        assertTrue(slotHeld.await(10, TimeUnit.SECONDS))
        f.onFactory = { inputs, graph ->
            if (f.graphs.size == 1) {
                val target = f.slot.target(graph)
                entered.complete(graph)
                result.complete(withContext(Dispatchers.IO) {
                    publisherThread.set(Thread.currentThread())
                    inputs.publication.publish(target)
                })
                throw IllegalStateException("Closed graph cannot be published")
            }
            assertEquals(RuntimePublicationOutcome.PUBLISHED, inputs.publication.publish(f.slot.target(graph)))
        }
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the first graph enters its factory") { entered.isCompleted }
            val first = entered.await()
            awaitBlockedOnLock(publisherThread, holderThread, ConnectionManager::class.java, "publishNavigationService")
            first.publicationFence.close()
            releaseSlot.countDown()
            f.eventually("the closed graph is rejected and its retry succeeds") { result.isCompleted && work.isCompleted }
            assertEquals(RuntimePublicationOutcome.CLOSED_GRAPH, result.await())
            work.await()
            holder.await()
            assertEquals(RuntimePublicationRetirement.NEVER_PUBLISHED, first.retirement)
            assertEquals(RuntimePublicationCounts(1, 0), f.slot.counts)
            val replacement = assertNotNull(f.slot.current)
            assertTrue(f.manager.isInitialNavigationSession(pendingStamp, replacement.token, f.slot.identity, replacement))
        } finally {
            releaseSlot.countDown()
            holder.cancelAndJoin()
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun cancellationWhilePublicationWaitsForTheSlotRejectsBeforeAnyExposure() = runTest {
        val f = PublicationTestFixture(this, store)
        val slotHeld = CountDownLatch(1)
        val releaseSlot = CountDownLatch(1)
        val holderThread = AtomicReference<Thread>()
        val publisherThread = AtomicReference<Thread>()
        val entered = CompletableDeferred<PublicationTestGraph>()
        val result = CompletableDeferred<RuntimePublicationOutcome>()
        val holder = async(Dispatchers.IO) {
            f.slot.withCurrent {
                holderThread.set(Thread.currentThread())
                slotHeld.countDown()
                check(releaseSlot.await(10, TimeUnit.SECONDS)) { "Publication slot was not released" }
            }
        }
        assertTrue(slotHeld.await(10, TimeUnit.SECONDS))
        f.onFactory = { inputs, graph ->
            val target = f.slot.target(graph)
            entered.complete(graph)
            withContext(Dispatchers.IO) {
                publisherThread.set(Thread.currentThread())
                result.complete(inputs.publication.publish(target))
            }
            throw CancellationException("Publication owner was cancelled")
        }
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the graph enters its factory") { entered.isCompleted }
            val graph = entered.await()
            awaitBlockedOnLock(publisherThread, holderThread, ConnectionManager::class.java, "publishNavigationService")
            work.cancel()
            releaseSlot.countDown()
            f.eventually("the cancelled publication is rejected and retired") {
                result.isCompleted && work.isCompleted && graph.retirement != null
            }
            assertEquals(RuntimePublicationOutcome.STALE_OWNER, result.await())
            assertEquals(RuntimePublicationRetirement.NEVER_PUBLISHED, graph.retirement)
            assertEquals(RuntimePublicationCounts(0, 0), f.slot.counts)
            assertNull(f.slot.current)
            assertFalse(f.manager.isNavigationLifecycleCurrent(pendingStamp))
        } finally {
            releaseSlot.countDown()
            holder.cancelAndJoin()
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun simulatorPublicationCarriesTheSameFirstSubmissionWitness() = runTest {
        val f = PublicationTestFixture(this, store)
        f.onFactory = { inputs, graph ->
            assertEquals(TransportType.SIMULATOR, inputs.transportType)
            assertEquals(RuntimePublicationOutcome.PUBLISHED, inputs.publication.publish(f.slot.target(graph)))
        }
        val idleStamp = f.manager.captureNavigationLifecycle()
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            f.manager.connect(ConnectionTarget.Simulator)
        }
        try {
            assertTrue(f.manager.isSimulatorConnection)
            assertTrue(f.graphs.isEmpty())
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the simulator publishes its graph") { work.isCompleted }
            work.await()
            val graph = assertNotNull(f.slot.current)
            assertEquals(RadioId(f.simulatorId), graph.token.radioId)
            assertEquals(RuntimePublicationCounts(1, 0), f.slot.counts)
            for (stamp in listOf(idleStamp, pendingStamp)) {
                assertTrue(f.manager.isInitialNavigationSession(stamp, graph.token, f.slot.identity, graph))
            }
        } finally {
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun failedSubmissionOwnedAdoptionFallsThroughBeforeTheFirstExposure() = runTest {
        lateinit var f: PublicationTestFixture
        val adoptionEntered = CompletableDeferred<Unit>()
        val releaseAdoption = CompletableDeferred<Unit>()
        val platform = object : ConnectionPlatform by RuntimePlatformAdapter(newConnectivity()) {
            override suspend fun state(target: ConnectionTarget) = PlatformLinkState(
                connected = false, autoReconnecting = false, bluetoothPoweredOff = false,
                connectedDeviceId = f.deviceId, phase = "idle", systemConnected = true,
            )
            override suspend fun adoptSystemLink(target: ConnectionTarget): Boolean {
                adoptionEntered.complete(Unit)
                releaseAdoption.await()
                return false
            }
        }
        f = PublicationTestFixture(this, store, platform = platform)
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        f.lastConnection.persist(f.deviceId, radio, "System-connected radio")
        val idleStamp = f.manager.captureNavigationLifecycle()
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the submitted adoption route is active") { adoptionEntered.isCompleted }
            val adoptionStamp = f.manager.captureNavigationLifecycle()
            assertTrue(f.manager.isNavigationLifecycleCurrent(adoptionStamp))
            releaseAdoption.complete(Unit)
            f.eventually("a failed adoption falls through to a fresh radio generation") { work.isCompleted }
            work.await()
            assertTrue(f.links.links.size >= 2, "The adoption route and connection use distinct owners")
            val graph = assertNotNull(f.slot.current)
            assertEquals(RuntimePublicationCounts(1, 0), f.slot.counts)
            assertTrue(f.manager.isInitialNavigationSession(idleStamp, graph.token, f.slot.identity, graph))
            assertTrue(f.manager.isInitialNavigationSession(pendingStamp, graph.token, f.slot.identity, graph))
            assertFalse(f.manager.isInitialNavigationSession(adoptionStamp, graph.token, f.slot.identity, graph))
            assertTrue(f.manager.isInitialNavigationSession(
                f.manager.captureNavigationLifecycle(), graph.token, f.slot.identity, graph,
            ))
        } finally {
            releaseAdoption.complete(Unit)
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun successfulAdoptionDoesNotAuthorizeItsLaterIndependentRebuild() = runTest {
        lateinit var f: PublicationTestFixture
        val platform = object : ConnectionPlatform by RuntimePlatformAdapter(newConnectivity()) {
            override suspend fun targetForDevice(deviceId: UUID): ConnectionTarget = f.target
            override suspend fun state(target: ConnectionTarget) = PlatformLinkState(
                connected = false, autoReconnecting = false, bluetoothPoweredOff = false,
                connectedDeviceId = f.deviceId, phase = "idle", systemConnected = true,
            )
            override suspend fun adoptSystemLink(target: ConnectionTarget): Boolean = true
        }
        f = PublicationTestFixture(this, store, platform = platform)
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        store.saveDevice(DeviceDTO(id = f.deviceId, radioId = radio, publicKey = key(7), nodeName = "Adopted radio"))
        f.lastConnection.persist(f.deviceId, radio, "Adopted radio")
        val idleStamp = f.manager.captureNavigationLifecycle()
        val work = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { f.manager.connect(f.target) }
        try {
            val pendingStamp = f.manager.captureNavigationLifecycle()
            f.eventually("the system adoption finishes without publishing a graph") { work.isCompleted }
            work.await()
            assertNull(f.slot.current)
            val adoptionStamp = f.manager.captureNavigationLifecycle()
            assertTrue(f.manager.isNavigationLifecycleCurrent(adoptionStamp))
            assertNotNull(f.links.links.single().callbacks).onReconnected()
            f.eventually("the adopted route independently rebuilds its graph") {
                f.slot.current != null && f.manager.connectionState == DeviceConnectionState.READY
            }
            val graph = assertNotNull(f.slot.current)
            for (stamp in listOf(idleStamp, pendingStamp, adoptionStamp)) {
                assertFalse(f.manager.isInitialNavigationSession(stamp, graph.token, f.slot.identity, graph))
            }
            assertTrue(f.manager.isInitialNavigationSession(
                f.manager.captureNavigationLifecycle(), graph.token, f.slot.identity, graph,
            ))
        } finally {
            work.cancelAndJoin()
            f.manager.close()
        }
    }

    @Test fun restoredRouteRebuildCannotSatisfyAnUnsubmittedCapture() = runTest {
        lateinit var f: PublicationTestFixture
        val platform = object : ConnectionPlatform by RuntimePlatformAdapter(newConnectivity()) {
            override suspend fun targetForDevice(deviceId: UUID): ConnectionTarget = f.target
            override suspend fun state(target: ConnectionTarget) = PlatformLinkState(
                connected = false, autoReconnecting = true, bluetoothPoweredOff = false,
                connectedDeviceId = f.deviceId, phase = "idle", systemConnected = false,
            )
        }
        f = PublicationTestFixture(this, store, platform = platform)
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        store.saveDevice(DeviceDTO(id = f.deviceId, radioId = radio, publicKey = key(7), nodeName = "Restored radio"))
        f.lastConnection.persist(f.deviceId, radio, "Restored radio")
        val idleStamp = f.manager.captureNavigationLifecycle()
        try {
            val activating = backgroundScope.async { f.manager.activate() }
            f.eventually("the restoration route is registered") { activating.isCompleted }
            activating.await()
            assertNull(f.slot.current)
            assertFalse(f.manager.isNavigationLifecycleCurrent(idleStamp))
            assertEquals(1, f.links.links.size)
            assertNotNull(f.links.links.single().callbacks).onReconnected()
            f.eventually("the restored route rebuilds its own graph") {
                f.slot.current != null && f.manager.connectionState == DeviceConnectionState.READY
            }
            val graph = assertNotNull(f.slot.current)
            assertTrue(f.links.links.size >= 2)
            assertFalse(f.manager.isInitialNavigationSession(idleStamp, graph.token, f.slot.identity, graph))
            assertTrue(f.manager.isInitialNavigationSession(
                f.manager.captureNavigationLifecycle(), graph.token, f.slot.identity, graph,
            ))
        } finally {
            f.manager.close()
        }
    }

    @Test fun interleavedNotificationDestinationsRetireEarlierChatHandoffsBeforeMountedEffectsRun() = runTest {
        val h = ContainerHarness(this, store)
        var navigationSubscription: kotlinx.coroutines.Job? = null
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Interleaved peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val channel = ChannelDTO(radioId = radio, index = 3u, name = "Interleaved channel")
            store.saveChannel(channel)
            val n = h.container.navigation
            val retainedRoom = RemoteNodeSessionDTO(
                radioId = radio, publicKey = key(40), name = "Retained room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
            )
            n.navigateToRoom(retainedRoom)
            h.settle()
            val listNavigation = h.container.chatsFeature.list.navigation
            navigationSubscription = listNavigation.pendingRoomSession.launchIn(backgroundScope)
            h.settle()
            assertEquals(retainedRoom, listNavigation.pendingRoomSession.value)
            n.navigateToContactDetail(contact)
            h.settle()
            assertEquals(retainedRoom, n.state.value.pendingRoomSession, "Plain contact navigation preserves Room context")
            assertNull(listNavigation.pendingRoomSession.value, "The inactive Chats list cannot consume Room context")
            assertTrue(n.back())
            n.selectTab(AppTab.CHATS)
            val firstMessage = UUID.randomUUID()
            val latestMessage = UUID.randomUUID()
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), firstMessage), null, null,
            )))
            assertEquals(contact, n.state.value.pendingChatContact)
            assertNull(n.state.value.pendingRoomSession, "A newer notification replaces the earlier delivery handoff")
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.ChannelMessage(radio, channel.index, UUID.randomUUID()), null, null,
            )))
            assertNull(n.state.value.pendingChatContact)
            assertEquals(channel, n.state.value.pendingChannel)
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), latestMessage), null, null,
            )))
            assertNull(n.state.value.pendingChannel)
            assertEquals(contact, n.state.value.pendingChatContact)
            assertNull(n.state.value.pendingScrollToMessageID, "Only reaction taps request a message anchor")
            val chatsStack = n.state.value.activeStack
            h.container.connectionPort.updateDevice(assertNotNull(h.appState.connectedDevice).copy(manualAddContacts = true))
            h.eventually("interleaved discovery uses the actual manual-add device preference") {
                h.appState.connectedDevice?.manualAddContacts == true
            }
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.NewContact(EntityKey(radio, contact.id)), null, null,
            )))
            assertEquals(AppTab.NODES, n.state.value.selectedTab)
            assertNull(n.state.value.pendingChatContact)
            assertNull(n.state.value.pendingChannel)
            assertNull(n.state.value.pendingRoomSession)
            mount(h)
            h.settle()
            compose.waitForIdle()
            assertEquals(AppTab.NODES, n.state.value.selectedTab)
            assertEquals(NavigationDestination.Discovery, n.state.value.activeStack.last().destination)
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
            assertEquals(chatsStack, n.state.value.stacks.getValue(AppTab.CHATS))
            compose.runOnUiThread { n.selectTab(AppTab.CHATS) }
            h.settle()
            compose.waitForIdle()
            assertEquals(chatsStack, n.state.value.activeStack)
            assertEquals(contact, assertIs<ChatSelection.Direct>(n.state.value.chatsSelectedRoute).contact)
            compose.onNodeWithTag("entry:${chatsStack.last().id}").assertIsDisplayed()
            assertTrue(n.back())
            assertEquals(channel, assertIs<ChatSelection.Channel>(n.state.value.chatsSelectedRoute).channel)
        } finally { navigationSubscription?.cancelAndJoin(); unmount(); h.container.close() }
    }

    @Test fun retiredDisconnectCannotCancelCollectorsWiredAfterItsNavigationCommit() = runTest {
        val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
        val h = ContainerHarness(this, store)
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<MessageEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { events += it }
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Replacement peer")
            radio.respond = { command ->
                if (command.size > 1 && command[0] == 0x1f.toUByte() && command[1] == 0.toUByte()) {
                    listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) + secret)
                } else null
            }
        }
        try {
            connect(h)
            val retired = assertNotNull(h.sessions.current)
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            val n = h.container.navigation
            n.installCommitBoundary { action ->
                val before = n.currentState
                h.manager.withNavigationCommitBoundary {
                    h.sessions.withNavigationCommitBoundary { current ->
                        if (current == null) action() else current.withNavigationCommitBoundary(action)
                    }
                }
                val after = n.currentState
                if (before.boundSessionToken == retired.token && after.boundSessionToken == null &&
                    after.generation == before.generation + 1 && paused.compareAndSet(false, true)
                ) {
                    committed.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Retiring cleanup was not released" }
                }
            }

            val disconnect = async(Dispatchers.IO) { h.appState.disconnect() }
            h.eventually("retiring navigation cleanup commits before job cancellation") { committed.count == 0L }
            assertFalse(disconnect.isCompleted)
            val reconnect = async { h.manager.connect(h.target(deviceId)) }
            h.eventually("replacement graph wires before retiring cleanup resumes") {
                val current = h.sessions.current
                reconnect.isCompleted && current != null && current !== retired &&
                    h.manager.connectionState == DeviceConnectionState.READY &&
                    n.currentState.boundSessionToken == current.token && n.currentState.navigationReady
            }
            reconnect.await()
            val replacement = assertNotNull(h.sessions.current)
            assertEquals(retired.token.radioId, replacement.token.radioId)
            assertNotEquals(retired.token, replacement.token)
            replacement.channelService.syncChannels(replacement.token.radioId, 1u, usePipelinedRead = false)
            h.eventually("replacement sync and message collectors are subscribed") {
                replacement.syncCoordinator.dataEventBroadcaster.subscriberCount >= 2 &&
                    h.appState.activeSessionJobCount > 0 && h.appState.messageEventDispatcher.activeJobCount > 0
            }
            val sessionJobs = h.appState.activeSessionJobCount
            val messageJobs = h.appState.messageEventDispatcher.activeJobCount

            release.countDown()
            h.eventually("retiring disconnect completes") { disconnect.isCompleted }
            disconnect.await()
            h.settle()
            assertEquals(replacement.token, n.currentState.boundSessionToken)
            assertEquals(sessionJobs, h.appState.activeSessionJobCount)
            assertEquals(messageJobs, h.appState.messageEventDispatcher.activeJobCount)
            val contactsVersion = h.appState.contactsVersion.value
            replacement.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ContactsChanged)
            h.eventually("replacement contact collector still receives events") {
                h.appState.contactsVersion.value > contactsVersion
            }

            val radio = h.links.radios.last()
            val previousResponse = radio.respond
            val frames = ConcurrentLinkedQueue(listOf(
                Bytes.of(0x11, 20, 0, 0, 0, 1, 0) +
                    le32(EPOCH.epochSecond) + Bytes.utf8("Alice: collector-after-replacement"),
            ))
            radio.respond = { command ->
                if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a))
                else previousResponse(command)
            }
            try {
                assertEquals(1L, replacement.messagePollingService.pollAllMessages())
            } finally {
                radio.respond = previousResponse
            }
            h.eventually("replacement radio message reaches the app event stream") {
                events.filterIsInstance<MessageEvent.ChannelMessageReceived>().any {
                    it.message.radioId == replacement.token.radioId &&
                        it.message.text == "collector-after-replacement"
                }
            }

            val contact = assertNotNull(store.fetchContact(replacement.token.radioId, key(20)))
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(replacement.token.radioId, contact.id), UUID.randomUUID()),
                null, null,
            )))
            mount(h)
            await(h) { (n.state.value.chatsSelectedRoute as? ChatSelection.Direct)?.contact?.id == contact.id }
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
        } finally {
            release.countDown()
            eventCollector.cancelAndJoin()
            unmount()
            h.container.close()
        }
    }

    @Test fun unboundNavigationDisconnectStillRetiresCurrentSessionCollectors() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val current = assertNotNull(h.sessions.current)
            assertTrue(h.appState.activeSessionJobCount > 0)
            assertTrue(h.appState.messageEventDispatcher.activeJobCount > 0)
            h.container.navigation.manuallyDisconnect()
            assertNull(h.container.navigation.currentState.boundSessionToken)
            assertEquals(current.token, h.sessions.current?.token)

            h.appState.disconnect()
            h.eventually("unbound navigation disconnect retires the captured session collectors") {
                h.appState.activeSessionJobCount == 0 && h.appState.messageEventDispatcher.activeJobCount == 0
            }
        } finally {
            h.container.close()
        }
    }

    @Test fun sameRadioSessionReplacementRetiresCapturedAndInFlightCallbacksWithoutLosingSelection() = runTest {
        val uiScheduler = TestCoroutineScheduler()
        val mainScope = CoroutineScope(backgroundScope.coroutineContext + uiScheduler + StandardTestDispatcher(uiScheduler))
        var hold = false
        var holdReplacement = false
        var heldKey: EntityKey? = null
        val entered = CompletableDeferred<Unit>()
        val lookup = CompletableDeferred<ContactDTO?>()
        val replacementHandshake = CompletableDeferred<Unit>()
        val retiredTransport = CompletableDeferred<Unit>()
        val contactLookupRelease = CompletableDeferred<Unit>()
        val contactReads = java.util.concurrent.atomic.AtomicInteger()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                if (hold && key == heldKey) {
                    entered.complete(Unit)
                    return lookup.await()
                }
                return store.fetchContact(key)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence, mainScope = mainScope)
        h.links.configureRadio = { radio ->
            if (holdReplacement) radio.connectGate = replacementHandshake
            radio.knownContacts += contactFrame(key(20), "Retired callback target")
            radio.knownContacts += contactFrame(key(21), "Retained selection")
        }
        try {
            val started = h.container.start()
            h.eventually("production activation completes") {
                uiScheduler.runCurrent()
                started.isCompleted
            }
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("activated graph finishes real initial synchronization") {
                uiScheduler.runCurrent()
                connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.navigation.state.value.navigationReady
            }
            connecting.await()
            val radio = assertNotNull(h.appState.currentRadioId)
            val oldSession = assertNotNull(h.sessions.current)
            val oldCallback = assertNotNull(oldSession.notificationService.onNotificationTapped)
            val oldGeneration = h.container.navigation.state.value.generation
            val target = assertNotNull(store.fetchContact(radio, key(20)))
            heldKey = EntityKey(radio, target.id)
            val selected = assertNotNull(store.fetchContact(radio, key(21)))
            val n = h.container.navigation
            val conversation = ChatConversationID.dm(radio, selected.id)
            h.draftStore.setDraft("saved before rebuild", conversation)
            n.navigateToChat(selected)
            mount(h)
            val composerMatcher = hasSetTextAction() and hasContentDescription(
                host.get().getString(com.meshcoreone.android.core.l10n.generated.AppChatsStrings.chatsInputAccessibilityLabel),
                substring = true,
            )
            h.eventually("retained composer mounts") {
                uiScheduler.runCurrent()
                compose.waitForIdle()
                compose.onAllNodes(composerMatcher and hasText("saved before rebuild")).fetchSemanticsNodes().size == 1
            }
            val composer = compose.onNode(composerMatcher).assertTextEquals("saved before rebuild")
            composer.performClick().performTextReplacement("typing during rebuild")
            val composerId = composer.fetchSemanticsNode().id
            h.draftStore.setDraft("independent persisted draft", conversation)
            val stack = n.state.value.activeStack
            hold = true
            val inFlight = async { oldCallback(EntityKey(radio, target.id)) }
            entered.await()
            suspend fun heldContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                val found = store.fetchContact(radioId, publicKey)
                contactReads.incrementAndGet()
                contactLookupRelease.await()
                return found
            }
            val external = AppDeepLinkRouter(object : AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, n) {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = heldContact(radioId, publicKey)
            })
            val chat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = heldContact(radioId, publicKey)
            }, backgroundScope)
            val urls = listOf(20, 30).map {
                h.container.nodesFeature.uriCodec.exportContactUri("Retired contact lookup", key(it), ContactType.REPEATER)
            }
            val externalRoutes = urls.map { url -> async { external.routeExternal(url) } }
            urls.forEach { assertTrue(chat.route(it)) }
            h.eventually("both producers hold found and absent contact lookups") { contactReads.get() == 4 }
            hold = false
            holdReplacement = true
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            h.links.radios.first().disconnectGate = retiredTransport
            val rebuilding = async { h.manager.rebuildSession(deviceId) }
            h.eventually("graph withdrawn while physical teardown keeps the actual DISCONNECTED edge current") {
                h.sessions.current == null && h.links.radios.first().disconnecting &&
                    h.manager.connectionState == DeviceConnectionState.DISCONNECTED
            }
            uiScheduler.runCurrent()
            h.eventually("activated withdrawal retirement runs after the disconnected edge") {
                uiScheduler.runCurrent()
                oldSession.notificationService.onNotificationTapped == null && !n.state.value.navigationReady
            }
            assertEquals(stack, n.state.value.activeStack)
            assertEquals(ChatSelection.Direct(selected), n.state.value.chatsSelectedRoute)
            assertEquals(composerId, composer.fetchSemanticsNode().id)
            composer.assertIsFocused().assertTextEquals("typing during rebuild")
            assertEquals("independent persisted draft", h.draftStore.draft(conversation))
            retiredTransport.complete(Unit)
            h.eventually("retired graph withdrawn while replacement handshake is held") {
                h.links.radios.size == 2 && h.sessions.current == null
            }
            h.eventually("retired navigation binding is detached during the held handshake") {
                oldSession.notificationService.onNotificationTapped == null && !n.state.value.navigationReady
            }
            assertNull(oldSession.notificationService.onNotificationTapped)
            assertFalse(n.state.value.navigationReady)
            assertEquals(oldGeneration, n.state.value.generation)
            val replacementGap = n.state.value
            assertFalse(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, target.id), UUID.randomUUID()), null, null,
            )), "The production dispatcher must retain the response until the new graph is ready")
            lookup.complete(target)
            contactLookupRelease.complete(Unit)
            testScheduler.runCurrent()
            inFlight.await()
            externalRoutes.forEach { assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, it.await()) }
            testScheduler.runCurrent()
            oldCallback(EntityKey(radio, target.id))
            assertEquals(replacementGap, n.state.value, "Retired callbacks cannot commit before the next binding")
            assertEquals(stack, n.state.value.activeStack)
            replacementHandshake.complete(Unit)
            h.eventually("replacement runtime publishes before its queued navigation binding") {
                val replacement = h.sessions.current
                replacement != null && replacement !== oldSession &&
                    h.manager.snapshot.value.token == replacement.token
            }
            val replacement = assertNotNull(h.sessions.current)
            assertNotEquals(replacement.token, n.currentState.boundSessionToken)
            assertEquals(oldGeneration, n.currentState.generation)
            val beforeMixedCapture = n.currentState
            val captured = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(30), it)
            }
            assertEquals(ContactLinkCaptureDisposition.StaleContext, captured.disposition)
            val mixedReads = AtomicInteger()
            val rejectedCaptures = CopyOnWriteArrayList<String>()
            val mixedChat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    mixedReads.incrementAndGet()
                    return store.fetchContact(radioId, publicKey)
                }
            }, backgroundScope, com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ ->
                rejectedCaptures += message
            })
            assertTrue(mixedChat.route(urls.last()))
            testScheduler.runCurrent()
            assertEquals(0, mixedReads.get(), "A mixed initiating context must not access the store")
            assertEquals(listOf("Contact-link initiating context is not current"), rejectedCaptures)
            assertEquals(beforeMixedCapture, n.currentState)
            h.eventually("activated replacement becomes ready") {
                uiScheduler.runCurrent()
                rebuilding.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    n.state.value.navigationReady
            }
            rebuilding.await()
            h.settle()
            h.assertReady()
            assertNotSame(oldSession, h.sessions.current)
            assertNull(oldSession.notificationService.onNotificationTapped)
            assertTrue(n.state.value.generation > oldGeneration)
            assertEquals(stack, n.state.value.activeStack)
            assertEquals(ChatSelection.Direct(selected), n.state.value.chatsSelectedRoute)
            uiScheduler.runCurrent()
            compose.waitForIdle()
            assertEquals(composerId, composer.fetchSemanticsNode().id)
            composer.assertIsFocused().assertTextEquals("typing during rebuild")
            assertEquals("independent persisted draft", h.draftStore.draft(conversation))
            oldCallback(EntityKey(radio, target.id))
            assertEquals(stack, n.state.value.activeStack)
            assertEquals(ChatSelection.Direct(selected), n.state.value.chatsSelectedRoute)
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, target.id), UUID.randomUUID()), null, null,
            )))
            compose.waitForIdle()
            assertEquals(ChatSelection.Direct(target), n.state.value.chatsSelectedRoute)
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
        } finally {
            retiredTransport.complete(Unit)
            replacementHandshake.complete(Unit)
            contactLookupRelease.complete(Unit)
            lookup.complete(null)
            unmount()
            val closing = async { h.container.close() }
            h.eventually("activated process cleanup drains the controlled UI queue") {
                uiScheduler.runCurrent()
                closing.isCompleted
            }
            closing.await()
        }
    }

    @Test fun capturedContactRetryValidationKeepsGraphAndRevisionIdentity() = runTest {
        val h = ContainerHarness(this, store)
        val target = h.target()
        try {
            connect(h, target)
            val first = assertNotNull(h.sessions.current)
            val navigation = h.container.navigation
            val original = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(20), it)
            }
            val afterCapture = navigation.currentState
            repeat(2) { assertTrue(h.container.isContactLinkContextCurrent(original, key(20))) }
            assertFalse(h.container.isContactLinkContextCurrent(original, key(21)))
            assertSame(afterCapture, navigation.currentState)
            assertSame(afterCapture, navigation.state.value)

            val newer = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(20), it)
            }
            val afterNewerCapture = navigation.currentState
            assertFalse(h.container.isContactLinkContextCurrent(original, key(20)))
            assertTrue(h.container.isContactLinkContextCurrent(newer, key(20)))
            assertSame(afterNewerCapture, navigation.currentState)

            h.manager.disconnect()
            h.settle()
            connect(h, target)
            val replacement = assertNotNull(h.sessions.current)
            assertNotSame(first, replacement)
            assertNotEquals(first.token, replacement.token)
            assertEquals(first.token.radioId, replacement.token.radioId)
            val beforeStaleCheck = navigation.currentState
            assertFalse(h.container.isContactLinkContextCurrent(newer, key(20)))
            assertSame(beforeStaleCheck, navigation.currentState)
            val fresh = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(20), it)
            }
            assertTrue(h.container.isContactLinkContextCurrent(fresh, key(20)))
        } finally { h.container.close() }
    }

    @Test fun capturedContactResolutionRechecksTheFirstGraphBeforeCommittingAbsence() = runTest {
        val h = ContainerHarness(this, store)
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        val saved = ContactDTO(
            radioId = radio, publicKey = key(20), name = "First binding saved peer", lastHeardTimestamp = null,
        )
        h.links.configureRadio = { it.knownContacts += contactFrame(saved.publicKey, saved.name) }
        try {
            store.saveDevice(DeviceDTO(id = radio.value, radioId = radio, publicKey = key(7), nodeName = "Saved radio"))
            store.saveContact(saved)
            assertNull(h.container.connectionPort.lastConnectedDeviceId)
            val captured = ContactLinkPublications().use {
                h.container.captureContactLinkContext(saved.publicKey, it)
            }
            val unbound = assertIs<ContactLinkLookupResolution.Current>(
                h.container.resolveContactLinkContext(captured, saved.publicKey),
            )
            assertNull(unbound.radioId)
            assertNull(unbound.selfPublicKey)
            connect(h)
            assertEquals(radio, assertNotNull(h.sessions.current).token.radioId)
            val navigation = h.container.navigation
            val before = navigation.currentState
            val link = ContactLinkRequest("Link alias", saved.publicKey, ContactType.REPEATER)
            assertEquals(ContactLinkResolvedCompletion.TargetChanged, ContactLinkPublications().use {
                h.container.completeResolvedContactLookup(captured, unbound, link, null, false, it)
            })
            assertSame(before, navigation.currentState)
            assertSame(before, navigation.state.value)
            val resolved = assertIs<ContactLinkLookupResolution.Current>(
                h.container.resolveContactLinkContext(captured, saved.publicKey),
            )
            assertEquals(radio, resolved.radioId)
            assertEquals(key(7), resolved.selfPublicKey)
            val existing = assertNotNull(store.fetchContact(radio, saved.publicKey))
            assertEquals(ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.Navigated), ContactLinkPublications().use {
                h.container.completeResolvedContactLookup(captured, resolved, link, existing, false, it)
            })
            assertEquals(existing, navigation.currentState.selectedContact)
            assertNull(navigation.currentState.pendingContactLink)
        } finally { h.container.close() }
    }

    @Test fun chatContactLookupRereadsRealStoreWhenFirstGraphBindsBeforeCompletion() = runTest {
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        for (found in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reads = CopyOnWriteArrayList<Pair<RadioId, Bytes>>()
            val diagnostics = CopyOnWriteArrayList<String>()
            val targetChanges = AtomicInteger()
            val completion = CompletableDeferred<ContactLinkCompletion>()
            val routingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val requestedKey = if (found) key(20) else key(31)
            val saved = ContactDTO(
                radioId = radio, publicKey = requestedKey, name = "Stored first-graph peer", lastHeardTimestamp = null,
            )
            if (found) h.links.configureRadio = { it.knownContacts += contactFrame(saved.publicKey, saved.name) }
            try {
                store.saveDevice(DeviceDTO(id = radio.value, radioId = radio, publicKey = key(7), nodeName = "Saved radio"))
                if (found) store.saveContact(saved)
                val base = h.container.chatsFeature.list.links
                val environment = object : ChatLinkEnvironment by base {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        reads += radioId to publicKey
                        return store.fetchContact(radioId, publicKey)
                    }

                    override fun completeResolvedContactLookup(
                        context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                        existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                    ): ContactLinkTargetCompletion {
                        if (target.radioId == null) {
                            assertNull(existing)
                            entered.countDown()
                            check(release.await(30, TimeUnit.SECONDS)) { "Unbound completion was not released" }
                        }
                        val result = base.completeResolvedContactLookup(
                            context, target, link, existing, publications, cancellation,
                        )
                        when (result) {
                            ContactLinkTargetCompletion.TargetChanged -> targetChanges.incrementAndGet()
                            is ContactLinkTargetCompletion.Completed -> completion.complete(result.outcome)
                            is ContactLinkTargetCompletion.Offered -> error("This unmounted test must not offer a handoff")
                        }
                        return result
                    }
                }
                val link = h.container.nodesFeature.uriCodec.exportContactUri(
                    "Unbound claim", requestedKey, ContactType.REPEATER,
                )
                assertTrue(ChatLinkRouter(environment, routingScope,
                    com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ -> diagnostics += message },
                ).route(link))
                assertTrue(entered.await(10, TimeUnit.SECONDS), "The unbound target must reach completion first")
                val connecting = backgroundScope.async { h.manager.connect(h.target(radio.value)) }
                h.eventually("the first graph binds while the old target is held", virtualBudgetMillis = 30_000) {
                    connecting.isCompleted && h.container.navigation.currentState.boundSessionToken != null
                }
                connecting.await()
                release.countDown()
                h.eventually("chat lookup finishes against the bound graph") { completion.isCompleted }
                h.eventually("the contact navigation commit is published") {
                    h.container.navigation.state.value == h.container.navigation.currentState
                }
                assertEquals(1, targetChanges.get())
                assertEquals(listOf(radio to requestedKey), reads.toList())
                assertTrue(diagnostics.isEmpty())
                val state = h.container.navigation.currentState
                if (found) {
                    assertEquals(ContactLinkCompletion.Navigated, completion.await())
                    assertEquals(store.fetchContact(radio, requestedKey), state.selectedContact)
                    assertNull(state.pendingContactLink)
                } else {
                    assertEquals(ContactLinkCompletion.ConfirmationStaged, completion.await())
                    val pending = assertNotNull(state.pendingContactLink)
                    assertEquals("Unbound claim", pending.name)
                    assertEquals(requestedKey, pending.publicKey)
                    assertEquals(ContactType.REPEATER, pending.type)
                    assertEquals(radio, pending.targetRadioId)
                    assertEquals(state.generation, pending.generation)
                }
                mount(h)
                compose.waitForIdle()
                if (found) compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
                else compose.onNodeWithTag("contact-link-confirmation").assertIsDisplayed()
            } finally {
                release.countDown()
                routingScope.cancel()
                unmount()
                h.container.close()
            }
        }
    }

    @Test fun cancelledMountedChatLookupWaitingForLifecycleBoundaryCannotOfferOrNavigate() = runTest {
        for (known in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Mounted cancellation peer") }
            val feature = h.container.chatsFeature
            val origin = ChatContactMount()
            val fetchEntered = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()
            val boundaryHeld = CountDownLatch(1)
            val releaseBoundary = CountDownLatch(1)
            val holderThread = AtomicReference<Thread>()
            val publisherThread = AtomicReference<Thread>()
            val offered = AtomicInteger()
            val diagnostics = CopyOnWriteArrayList<String>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val routingJob = SupervisorJob()
            val controlJob = SupervisorJob()
            val exceptionHandler = CoroutineExceptionHandler { _, failure -> failures.add(failure) }
            val routingScope = CoroutineScope(routingJob + Dispatchers.IO + exceptionHandler)
            val controlScope = CoroutineScope(controlJob + Dispatchers.IO + exceptionHandler)
            var holder: Deferred<Unit>? = null
            var failure: Throwable? = null
            try {
                connect(h)
                feature.registerContactMount(origin)
                mount(h)
                val navigation = h.container.navigation
                val underlying = feature.linksForMount(origin)
                val environment = object : ChatLinkEnvironment by underlying {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        fetchEntered.complete(Unit)
                        releaseFetch.await()
                        return store.fetchContact(radioId, publicKey)
                    }

                    override fun completeResolvedContactLookup(
                        context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                        existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                    ): ContactLinkTargetCompletion {
                        publisherThread.set(Thread.currentThread())
                        return underlying.completeResolvedContactLookup(
                            context, target, link, existing, publications, cancellation,
                        ).also { if (it is ContactLinkTargetCompletion.Offered) offered.incrementAndGet() }
                    }
                }
                val publicKey = if (known) key(20) else key(31)
                val uri = h.container.nodesFeature.uriCodec.exportContactUri(
                    "Mounted cancellation claim", publicKey, ContactType.REPEATER,
                )
                val router = ChatLinkRouter(environment, routingScope,
                    com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ -> diagnostics += message },
                )
                assertTrue(router.route(uri))
                fetchEntered.await()
                val before = navigation.currentState
                val holding = async(Dispatchers.IO) {
                    h.manager.withNavigationCommitBoundary {
                        holderThread.set(Thread.currentThread())
                        boundaryHeld.countDown()
                        check(releaseBoundary.await(30, TimeUnit.SECONDS)) { "Lifecycle boundary was not released" }
                    }
                }
                holder = holding
                assertTrue(boundaryHeld.await(10, TimeUnit.SECONDS))
                releaseFetch.complete(Unit)
                awaitBlockedOnLock(
                    publisherThread, holderThread, ConnectionManager::class.java, "withNavigationCommitBoundary",
                )
                routingJob.cancel()
                releaseBoundary.countDown()
                holding.await()
                routingJob.join()
                h.settle()
                compose.waitForIdle()
                assertEquals(0, offered.get(), "Cancellation before the offer commit cannot transfer ownership")
                assertTrue(navigation.pendingChatContactOffers().isEmpty())
                assertEquals(before, navigation.currentState)
                assertNull(navigation.currentState.pendingContactLink)
                assertNull(navigation.currentState.pendingContactDetail)
                assertFalse(origin.registrationSealed.get(), "The source remains mounted throughout cancellation")
                assertTrue(diagnostics.isEmpty())

                assertTrue(ChatLinkRouter(environment, controlScope).route(uri))
                await(h) {
                    if (known) navigation.state.value.pendingContactDetail?.publicKey == publicKey
                    else navigation.state.value.pendingContactLink?.publicKey == publicKey
                }
                assertEquals(1, offered.get(), "A fresh active delivery still reaches the mounted consumer")
                if (known) {
                    assertEquals(store.fetchContact(assertNotNull(h.appState.currentRadioId), publicKey),
                        navigation.state.value.pendingContactDetail)
                } else {
                    val pending = assertNotNull(navigation.state.value.pendingContactLink)
                    assertEquals("Mounted cancellation claim", pending.name)
                    assertEquals(ContactType.REPEATER, pending.type)
                    assertEquals(h.appState.currentRadioId, pending.targetRadioId)
                }
                assertTrue(h.links.radios.all { radio -> radio.sent.none { it[0].toInt() == 0x09 } })
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                releaseFetch.complete(Unit)
                releaseBoundary.countDown()
                unmount()
                feature.sealContactMount(origin)
                finishContactCancellationTest(failure, routingJob, failures, h.container, extraCleanup = {
                    controlJob.cancelAndJoin()
                    holder?.let { work -> withContext(Dispatchers.IO) { withTimeout(10_000) { work.await() } } }
                })
            }
        }
    }

    @Test fun cancelledChatContactLookupWaitingForTheCommitBoundaryCannotMutateNavigation() = runTest {
        val h = ContainerHarness(this, store)
        val fetchEntered = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val slotHeld = CountDownLatch(1)
        val releaseSlot = CountDownLatch(1)
        val holderThread = AtomicReference<Thread>()
        val publisherThread = AtomicReference<Thread>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val routingJob = SupervisorJob()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val routingScope = CoroutineScope(routingJob + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            failures.add(failure)
        })
        var holder: Deferred<Unit>? = null
        var failure: Throwable? = null
        try {
            connect(h)
            val base = h.container.chatsFeature.list.links
            val environment = object : ChatLinkEnvironment by base {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    fetchEntered.complete(Unit)
                    releaseFetch.await()
                    return store.fetchContact(radioId, publicKey)
                }

                override fun completeResolvedContactLookup(
                    context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                    existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                ): ContactLinkTargetCompletion {
                    publisherThread.set(Thread.currentThread())
                    return base.completeResolvedContactLookup(context, target, link, existing, publications, cancellation)
                }
            }
            val link = h.container.nodesFeature.uriCodec.exportContactUri(
                "Cancelled claim", key(31), ContactType.REPEATER,
            )
            assertTrue(ChatLinkRouter(environment, routingScope,
                com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ -> diagnostics += message },
            ).route(link))
            fetchEntered.await()
            val before = h.container.navigation.currentState
            val holding = async(Dispatchers.IO) {
                h.manager.withNavigationCommitBoundary {
                    holderThread.set(Thread.currentThread())
                    slotHeld.countDown()
                    check(releaseSlot.await(30, TimeUnit.SECONDS)) { "Commit boundary was not released" }
                }
            }
            holder = holding
            assertTrue(slotHeld.await(10, TimeUnit.SECONDS))
            releaseFetch.complete(Unit)
            awaitBlockedOnLock(publisherThread, holderThread, ConnectionManager::class.java, "withNavigationCommitBoundary")
            routingJob.cancel()
            releaseSlot.countDown()
            holding.await()
            routingJob.join()
            assertEquals(before, h.container.navigation.currentState)
            assertNull(h.container.navigation.currentState.pendingContactLink)
            assertNull(h.container.navigation.currentState.pendingContactDetail)
            assertTrue(diagnostics.isEmpty())
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            releaseFetch.complete(Unit)
            releaseSlot.countDown()
            finishContactCancellationTest(failure, routingJob, failures, h.container, extraCleanup = {
                holder?.let { work -> withContext(Dispatchers.IO) { withTimeout(10_000) { work.await() } } }
            })
        }
    }

    @Test fun parentCancelDuringSiblingTraversalDoesNotFenceRealContactPublication() = runTest {
        for (cancelChild in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            val completionEntered = CountDownLatch(1)
            val releaseCompletion = CountDownLatch(1)
            val siblingEntered = CountDownLatch(1)
            val releaseSibling = CountDownLatch(1)
            val routingJob = SupervisorJob()
            val lookupJob = AtomicReference<Job>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val routingScope = CoroutineScope(routingJob + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
                failures.add(failure)
            })
            val sibling = Job(routingJob)
            sibling.invokeOnCompletion {
                siblingEntered.countDown()
                check(releaseSibling.await(30, TimeUnit.SECONDS)) { "Sibling cancellation was not released" }
            }
            val firstCancel = FutureTask { routingJob.cancel() }
            val repeatCancel = FutureTask { routingJob.cancel() }
            val firstThread = Thread(firstCancel, "contact-parent-cancel").apply { isDaemon = true }
            val repeatThread = Thread(repeatCancel, "contact-repeat-parent-cancel").apply { isDaemon = true }
            val result = AtomicReference<ContactLinkTargetCompletion>()
            var failure: Throwable? = null
            try {
                connect(h)
                val base = h.container.chatsFeature.list.links
                val environment = object : ChatLinkEnvironment by base {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        lookupJob.set(checkNotNull(currentCoroutineContext()[Job]))
                        return base.fetchContact(radioId, publicKey)
                    }

                    override fun completeResolvedContactLookup(
                        context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                        existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                    ): ContactLinkTargetCompletion {
                        completionEntered.countDown()
                        check(releaseCompletion.await(30, TimeUnit.SECONDS)) { "Contact completion was not released" }
                        return base.completeResolvedContactLookup(
                            context, target, link, existing, publications, cancellation,
                        ).also(result::set)
                    }
                }
                val link = h.container.nodesFeature.uriCodec.exportContactUri(
                    "Delayed parent", key(31), ContactType.REPEATER,
                )
                assertTrue(ChatLinkRouter(environment, routingScope).route(link))
                assertTrue(completionEntered.await(10, TimeUnit.SECONDS), "Contact completion was not reached")
                val child = checkNotNull(lookupJob.get())
                val before = h.container.navigation.currentState
                assertEquals(before, h.container.navigation.state.value)
                firstThread.start()
                assertTrue(siblingEntered.await(10, TimeUnit.SECONDS), "Parent did not enter the earlier child")
                assertTrue(routingJob.isCancelled)
                assertTrue(child.isActive, "Lookup child must not have received parent cancellation yet")
                assertFalse(firstCancel.isDone)
                repeatThread.start()
                repeatCancel.get(10, TimeUnit.SECONDS)
                assertFalse(firstCancel.isDone)
                assertTrue(child.isActive)
                if (cancelChild) {
                    child.cancel()
                    child.cancel()
                    assertTrue(child.isCancelled)
                }
                releaseCompletion.countDown()
                withContext(Dispatchers.IO) { withTimeout(10_000) { child.join() } }
                if (cancelChild) {
                    assertNull(result.get())
                    assertEquals(before, h.container.navigation.currentState)
                    assertEquals(before, h.container.navigation.state.value)
                } else {
                    assertEquals(
                        ContactLinkTargetCompletion.Completed(ContactLinkCompletion.ConfirmationStaged),
                        result.get(),
                    )
                    val current = h.container.navigation.currentState
                    assertEquals("Delayed parent", assertNotNull(current.pendingContactLink).name)
                    assertEquals(current, h.container.navigation.state.value)
                }
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                releaseCompletion.countDown()
                releaseSibling.countDown()
                finishContactCancellationTest(
                    failure, routingJob, failures, h.container, firstThread to firstCancel, repeatThread to repeatCancel,
                )
            }
        }
    }

    @Test fun cancelledAfterContactCasStillPublishesTheDeferredDestination() = runTest {
        for (found in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            val committed = CountDownLatch(1)
            val releasePublication = CountDownLatch(1)
            val routingJob = SupervisorJob()
            val lookupJob = AtomicReference<Job>()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val routingScope = CoroutineScope(routingJob + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
                failures.add(failure)
            })
            val result = AtomicReference<ContactLinkTargetCompletion>()
            var failure: Throwable? = null
            try {
                connect(h)
                val radio = checkNotNull(h.container.navigation.currentState.radioId)
                val requestedKey = if (found) key(20) else key(31)
                if (found) store.saveContact(ContactDTO(
                    radioId = radio, publicKey = requestedKey, name = "Stored publication peer", lastHeardTimestamp = null,
                ))
                val base = h.container.chatsFeature.list.links
                val environment = object : ChatLinkEnvironment by base {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        lookupJob.set(checkNotNull(currentCoroutineContext()[Job]))
                        return base.fetchContact(radioId, publicKey)
                    }

                    override fun completeResolvedContactLookup(
                        context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                        existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                    ): ContactLinkTargetCompletion {
                        val outcome = base.completeResolvedContactLookup(
                            context, target, link, existing, publications, cancellation,
                        )
                        result.set(outcome)
                        committed.countDown()
                        check(releasePublication.await(30, TimeUnit.SECONDS)) { "Contact publication was not released" }
                        return outcome
                    }
                }
                val link = h.container.nodesFeature.uriCodec.exportContactUri(
                    "Deferred publication", requestedKey, ContactType.REPEATER,
                )
                assertTrue(ChatLinkRouter(environment, routingScope).route(link))
                assertTrue(committed.await(10, TimeUnit.SECONDS), "Contact CAS was not reached")
                val child = checkNotNull(lookupJob.get())
                val navigation = h.container.navigation
                val beforePublication = navigation.state.value
                val raw = navigation.currentState
                assertNotEquals(beforePublication, raw)
                if (found) {
                    assertEquals(ContactLinkTargetCompletion.Completed(ContactLinkCompletion.Navigated), result.get())
                    assertEquals(requestedKey, raw.selectedContact?.publicKey)
                    assertNull(beforePublication.selectedContact)
                } else {
                    assertEquals(
                        ContactLinkTargetCompletion.Completed(ContactLinkCompletion.ConfirmationStaged),
                        result.get(),
                    )
                    assertEquals("Deferred publication", assertNotNull(raw.pendingContactLink).name)
                    assertNull(beforePublication.pendingContactLink)
                }
                routingJob.cancel()
                child.cancel()
                routingJob.cancel()
                child.cancel()
                assertEquals(beforePublication, navigation.state.value)
                releasePublication.countDown()
                withContext(Dispatchers.IO) { withTimeout(10_000) { child.join() } }
                h.eventually("the already-committed contact destination is published") {
                    navigation.state.value == navigation.currentState
                }
                if (found) assertEquals(requestedKey, navigation.state.value.selectedContact?.publicKey)
                else assertEquals("Deferred publication", navigation.state.value.pendingContactLink?.name)
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                releasePublication.countDown()
                finishContactCancellationTest(failure, routingJob, failures, h.container)
            }
        }
    }

    @Test fun externalContactLinksUseTheFirstGraphStoreBeforeLastConnectionPublication() = runTest {
        val saving = CompletableDeferred<Unit>()
        val publication = CompletableDeferred<Unit>()
        val reads = CopyOnWriteArrayList<Pair<RadioId, Bytes>>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveDevice(device: DeviceDTO) {
                saving.complete(Unit)
                publication.await()
                store.saveDevice(device)
            }

            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                reads.add(radioId to publicKey)
                return store.fetchContact(radioId, publicKey)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        val saved = ContactDTO(
            radioId = radio, publicKey = key(20), name = "First graph saved peer", lastHeardTimestamp = null,
        )
        h.links.configureRadio = { it.knownContacts += contactFrame(saved.publicKey, saved.name) }
        try {
            store.saveDevice(DeviceDTO(id = radio.value, radioId = radio, publicKey = key(7), nodeName = "Saved radio"))
            store.saveContact(saved)
            val persisted = assertNotNull(store.fetchContact(radio, saved.publicKey))
            assertNull(h.container.connectionPort.lastConnectedDeviceId)
            val connecting = async { h.manager.connect(h.target(radio.value)) }
            h.eventually("first graph exists while device persistence and preference publication are held") {
                saving.isCompleted && h.sessions.current != null && h.manager.snapshot.value.token == null
            }
            val session = assertNotNull(h.sessions.current)
            assertEquals(radio, session.token.radioId)
            assertNull(h.container.connectionPort.lastConnectedDeviceId)
            assertNull(h.appState.offlineDataStore)
            assertNull(h.appState.connectedDevice)
            val navigation = h.container.navigation
            assertNull(navigation.state.value.radioId)
            val pending = PendingExternalRoute(
                AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))::routeExternal,
            )
            pending.markReady()
            mount(h)
            val accounted = mutableSetOf<String>()
            for (windowWidth in listOf(390, 834)) {
                compose.runOnIdle { width.value = windowWidth.dp }
                val found = PendingExternalRoute.Delivery(
                    "saved-before-preference-$windowWidth",
                    h.container.nodesFeature.uriCodec.exportContactUri(
                        "Untrusted alias", saved.publicKey, ContactType.ROOM,
                    ),
                )
                val readsBefore = reads.size
                assertEquals(
                    DeepLinkRouteOutcome.Navigated,
                    assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.submit(found)).outcome,
                )
                assertTrue(reads.drop(readsBefore).contains(radio to saved.publicKey))
                accounted += found.id
                val state = navigation.state.value
                assertEquals(AppTab.NODES, state.selectedTab)
                assertEquals(NavigationDestination.ContactDetail(persisted), state.activeStack.last().destination)
                assertNull(state.pendingContactLink)
                assertEquals<Set<String>>(accounted, pending.consumedDeliveryIds())
                compose.waitForIdle()
                compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
                compose.onAllNodesWithText(persisted.displayName).onFirst().assertIsDisplayed()
                assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(found))
                navigation.back()

                val request = ContactLinkRequest("Unknown first-graph peer", key(30), ContactType.REPEATER)
                val absent = PendingExternalRoute.Delivery(
                    "absent-before-preference-$windowWidth",
                    h.container.nodesFeature.uriCodec.exportContactUri(request.name, request.publicKey, request.type),
                )
                assertEquals(
                    DeepLinkRouteOutcome.ConfirmationStaged,
                    assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.submit(absent)).outcome,
                )
                accounted += absent.id
                val staged = assertNotNull(navigation.state.value.pendingContactLink)
                assertEquals(
                    request.copy(
                        targetRadioId = radio, generation = navigation.state.value.generation,
                        deliveryId = staged.deliveryId,
                    ),
                    staged,
                )
                assertEquals<Set<String>>(accounted, pending.consumedDeliveryIds())
                compose.waitForIdle()
                compose.onNodeWithTag("contact-link-confirmation").assertIsDisplayed()
                compose.onNodeWithTag("contact-link-cancel").performClick()
                assertNull(navigation.state.value.pendingContactLink)
                assertNull(store.fetchContact(radio, request.publicKey))
                assertSame(session, h.sessions.current)
                assertNull(h.manager.snapshot.value.token)
                assertNull(h.container.connectionPort.lastConnectedDeviceId)
            }
            assertTrue(h.links.radios.all { firmware -> firmware.sent.none { it[0].toInt() == 0x09 } })
            publication.complete(Unit)
            h.eventually("first graph completes device and preference publication") { connecting.isCompleted }
            connecting.await()
            h.settle()
            h.assertReady()
            assertSame(session, h.sessions.current)
        } finally {
            publication.complete(Unit)
            unmount()
            h.container.close()
        }
    }

    @Test fun contactLinksFromBothProducersSurviveProvisionalFirstGraphBeforeTokenPublication() = runTest {
        val publication = CompletableDeferred<Unit>()
        val saving = CompletableDeferred<Unit>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveDevice(device: DeviceDTO) {
                saving.complete(Unit)
                publication.await()
                store.saveDevice(device)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        val saved = ContactDTO(
            radioId = radio, publicKey = key(20), name = "Persisted provisional peer", lastHeardTimestamp = null,
        )
        h.links.configureRadio = { firmware -> firmware.knownContacts += contactFrame(saved.publicKey, saved.name) }
        try {
            store.saveDevice(DeviceDTO(id = radio.value, radioId = radio, publicKey = key(7), nodeName = "Cached radio"))
            store.saveContact(saved)
            val persisted = assertNotNull(store.fetchContact(radio, saved.publicKey))
            LastConnectionStore(h.preferences, SystemRuntimeClock()).persist(radio.value, radio, "Cached radio")
            h.container.connectionPort.refreshLastConnection()
            h.settle()
            val connecting = async { h.manager.connect(h.target(radio.value)) }
            h.eventually("real graph published before suspended device persistence completes") {
                saving.isCompleted && h.sessions.current != null && h.manager.snapshot.value.token == null
            }
            val provisional = assertNotNull(h.sessions.current)
            val n = h.container.navigation
            assertNull(n.state.value.radioId)
            assertEquals(radio, h.appState.currentRadioId)
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n))
            val chat = ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope)
            mount(h)
            for (fromChat in listOf(false, true)) {
                val foundUri = h.container.nodesFeature.uriCodec.exportContactUri("Untrusted alias", saved.publicKey, ContactType.ROOM)
                if (fromChat) {
                    assertTrue(chat.route(foundUri))
                    h.eventually("chat producer navigates persisted identity during provisional publication") {
                        n.state.value.activeStack.last().destination == NavigationDestination.ContactDetail(persisted)
                    }
                } else {
                    val found = async { external.routeExternal(foundUri) }
                    h.eventually("external saved lookup completes during provisional publication") { found.isCompleted }
                    assertEquals(DeepLinkRouteOutcome.Navigated, found.await())
                }
                compose.waitForIdle()
                assertEquals(NavigationDestination.ContactDetail(persisted), n.state.value.activeStack.last().destination)
                compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
                n.back()
                val request = ContactLinkRequest("Provisional ${if (fromChat) "chat" else "external"}", key(30), ContactType.REPEATER)
                val absentUri = h.container.nodesFeature.uriCodec.exportContactUri(request.name, request.publicKey, request.type)
                if (fromChat) {
                    assertTrue(chat.route(absentUri))
                    h.eventually("chat producer stages provisional confirmation") { n.state.value.pendingContactLink != null }
                } else {
                    val absent = async { external.routeExternal(absentUri) }
                    h.eventually("external absent lookup completes during provisional publication") { absent.isCompleted }
                    assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, absent.await())
                }
                val pending = assertNotNull(n.state.value.pendingContactLink)
                assertEquals(
                    request.copy(targetRadioId = radio, generation = n.state.value.generation, deliveryId = pending.deliveryId),
                    pending,
                )
                compose.waitForIdle()
                compose.onNodeWithTag("contact-link-confirmation").assertIsDisplayed()
                compose.onNodeWithTag("contact-link-cancel").performClick()
                assertNull(n.state.value.pendingContactLink)
                assertSame(provisional, h.sessions.current)
                assertNull(h.manager.snapshot.value.token)
            }
            publication.complete(Unit)
            h.eventually("provisional first graph becomes the same published ready graph") { connecting.isCompleted }
            connecting.await()
            h.settle()
            h.assertReady()
            assertSame(provisional, h.sessions.current)
            assertNull(n.state.value.pendingContactLink)
            assertTrue(h.links.radios.all { firmware -> firmware.sent.none { it[0].toInt() == 0x09 } })
        } finally {
            publication.complete(Unit)
            unmount()
            h.container.close()
        }
    }

    @Test fun retiredImportCannotNavigateAfterCommittedRowLookupDuringReplacementHandshake() = runTest {
        val importedKey = key(30)
        val importedName = "Committed retired import"
        val importedLookup = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val replacementHandshake = CompletableDeferred<Unit>()
        var holdLookup = false
        var replacing = false
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                val found = store.fetchContact(radioId, publicKey)
                if (holdLookup && publicKey == importedKey && found != null) {
                    importedLookup.complete(Unit)
                    releaseLookup.await()
                }
                return found
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(key(21), "Retained contact")
            firmware.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
            if (replacing) {
                firmware.connectGate = replacementHandshake
                firmware.knownContacts += contactFrame(importedKey, importedName)
            }
        }
        try {
            connect(h)
            val n = h.container.navigation
            val radio = assertNotNull(h.appState.currentRadioId)
            val selected = assertNotNull(store.fetchContact(radio, key(21)))
            n.navigateToContactDetail(selected)
            mount(h)
            n.stageContactLink(ContactLinkRequest(importedName, importedKey, ContactType.CHAT))
            val pending = assertNotNull(n.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            holdLookup = true
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { importedLookup.isCompleted }
            assertNotNull(store.fetchContact(radio, importedKey), "The ACK-backed write really committed")
            replacing = true
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            val rebuilding = async { h.manager.rebuildSession(deviceId) }
            h.eventually("import's old graph withdraws before replacement handshake") {
                h.links.radios.size == 2 && h.sessions.current == null
            }
            releaseLookup.complete(Unit)
            compose.waitForIdle()
            h.eventually("retired import reports disconnection instead of navigating") {
                compose.onAllNodesWithTag("contact-link-error").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(pending.deliveryId, n.state.value.pendingContactLink?.deliveryId)
            assertEquals(selected.id, n.state.value.selectedContact?.id)
            replacementHandshake.complete(Unit)
            rebuilding.await()
            h.settle()
            h.assertReady()
            assertNotEquals(pending.deliveryId, n.state.value.pendingContactLink?.deliveryId)
            assertEquals(selected.id, n.state.value.selectedContact?.id)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            assertEquals(selected.id, n.state.value.selectedContact?.id)
        } finally {
            releaseLookup.complete(Unit)
            replacementHandshake.complete(Unit)
            unmount()
            h.container.close()
        }
    }

    @Test fun retiredImportReportsDisconnectionAfterAckBeforeRowLookup() = runTest {
        val acknowledged = CompletableDeferred<Unit>()
        val releaseAcknowledgement = CompletableDeferred<Unit>()
        val replacementHandshake = CompletableDeferred<Unit>()
        var replacing = false
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { firmware ->
            firmware.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
            if (replacing) firmware.connectGate = replacementHandshake
        }
        try {
            connect(h)
            val actual = h.container.nodesFeature
            val realService = assertNotNull(actual.session.contactService())
            val delayedService = object : NodesContactService by realService {
                override suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame) {
                    realService.addOrUpdateContact(radioId, contact)
                    acknowledged.complete(Unit)
                    releaseAcknowledgement.await()
                }
            }
            val dependencies = NodesFeatureDependencies(
                object : NodesSession by actual.session {
                    override fun contactService() = delayedService
                },
                actual.uriCodec, actual.timeouts, actual.messages, actual.announcer,
                actual.preferences, actual.clock, actual.locale,
            )
            val navigation = h.container.navigation
            mount(h, dependencies)
            navigation.stageContactLink(ContactLinkRequest("Acknowledged import", key(30), ContactType.CHAT))
            val pending = assertNotNull(navigation.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            h.eventually("import has a real ACK before replacement") { acknowledged.isCompleted }
            replacing = true
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            val rebuilding = async { h.manager.rebuildSession(deviceId) }
            h.eventually("old graph withdraws before replacement handshake") {
                h.links.radios.size == 2 && h.sessions.current == null
            }
            releaseAcknowledgement.complete(Unit)
            compose.waitForIdle()
            h.eventually("retired ACK-backed import reports disconnection") {
                compose.onAllNodesWithTag("contact-link-error").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(pending.deliveryId, navigation.state.value.pendingContactLink?.deliveryId)
            assertNull(navigation.state.value.pendingContactDetail)
            replacementHandshake.complete(Unit)
            rebuilding.await()
            h.settle()
            h.assertReady()
        } finally {
            releaseAcknowledgement.complete(Unit)
            replacementHandshake.complete(Unit)
            unmount()
            h.container.close()
        }
    }

    @Test fun actualConnectionLossRejectsPreLossLookupButFreshOfflineLinksStillReachMountedDestinations() = runTest {
        for (knownBeforeLoss in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Retained offline peer") }
            val entered = CompletableDeferred<Unit>()
            val releaseLookup = CompletableDeferred<Unit>()
            var oldRoute: kotlinx.coroutines.Deferred<DeepLinkRouteOutcome>? = null
            try {
                connect(h)
                val oldGraph = assertNotNull(h.sessions.current)
                val radio = oldGraph.token.radioId
                val saved = assertNotNull(store.fetchContact(radio, key(20)))
                val navigation = h.container.navigation
                val base = ContainerDeepLinkEnvironment(h.container, navigation)
                val oldRouter = AppDeepLinkRouter(object : AppDeepLinkEnvironment by base {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        entered.complete(Unit)
                        releaseLookup.await()
                        return base.fetchContact(radioId, publicKey)
                    }
                })
                val codec = h.container.nodesFeature.uriCodec
                val oldUri = codec.exportContactUri(
                    "Pre-loss claim", if (knownBeforeLoss) key(20) else key(30), ContactType.REPEATER,
                )
                val routing = backgroundScope.async(Dispatchers.Default) { oldRouter.routeExternal(oldUri) }
                oldRoute = routing
                h.eventually("initiating live graph lookup is suspended", virtualBudgetMillis = 0) { entered.isCompleted }
                assertNotNull(h.links.links.last().callbacks).onDisconnected(IllegalStateException("actual link loss"))
                h.eventually("actual loss retires both graph and navigation binding", virtualBudgetMillis = 0) {
                    h.manager.connectionState == DeviceConnectionState.DISCONNECTED &&
                        h.sessions.current == null && navigation.currentState.boundSessionToken == null
                }
                assertTrue(oldGraph.isTornDown)
                val offline = h.manager.captureNavigationLifecycle()
                assertFalse(h.manager.isNavigationLifecycleCurrent(offline), "Historical graph exposure is not first binding")
                assertTrue(h.manager.isOfflineNavigationLifecycleCurrent(offline))
                val afterLoss = navigation.currentState
                releaseLookup.complete(Unit)
                h.eventually("old lookup finishes without navigating or staging", virtualBudgetMillis = 0) {
                    routing.isCompleted
                }
                assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, routing.await())
                assertEquals(afterLoss, navigation.currentState)

                val fresh = AppDeepLinkRouter(base)
                assertEquals(
                    DeepLinkRouteOutcome.Navigated,
                    fresh.routeExternal(codec.exportContactUri("Fresh alias", saved.publicKey, ContactType.ROOM)),
                )
                assertEquals(saved.id, navigation.state.value.selectedContact?.id)
                assertEquals(radio, navigation.state.value.selectedContact?.radioId)
                mount(h)
                compose.waitForIdle()
                compose.onAllNodesWithText(saved.displayName).onFirst().assertIsDisplayed()
                val unknownUri = codec.exportContactUri("Fresh offline confirmation", key(31), ContactType.REPEATER)
                assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, fresh.routeExternal(unknownUri))
                val pending = assertNotNull(navigation.state.value.pendingContactLink)
                assertEquals(radio, pending.targetRadioId)
                assertEquals(ContactType.REPEATER, pending.type)
                compose.waitForIdle()
                compose.onNodeWithTag("contact-link-confirmation").assertIsDisplayed()
                compose.onNodeWithTag("contact-link-cancel").performClick()
                assertNull(navigation.currentState.pendingContactLink)
                assertNull(store.fetchContact(radio, key(31)))

                navigation.clearPendingContactDetailNavigation()
                assertNull(navigation.currentState.pendingContactDetail)
                assertTrue(ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope).route(
                    codec.exportContactUri("Fresh chat alias", saved.publicKey, ContactType.CHAT),
                ))
                h.eventually("fresh chat link resolves the cached contact after actual loss", virtualBudgetMillis = 0) {
                    navigation.state.value.pendingContactDetail?.id == saved.id
                }
                assertNull(h.sessions.current)
                assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
                connect(h)
                assertFalse(h.manager.isOfflineNavigationLifecycleCurrent(offline), "Offline authority cannot follow reconnection")
            } finally {
                releaseLookup.complete(Unit)
                oldRoute?.cancelAndJoin()
                unmount()
                h.container.close()
            }
        }
    }

    @Test fun savedOfflineContactDestinationSurvivesCompatibleInitialAssignmentButNotAnotherRadio() = runTest {
        for (compatible in listOf(true, false)) {
            val h = ContainerHarness(this, store, identity = { if (compatible) key(7) else key(8) })
            val deviceId = DeviceIdentity.deriveUUID(key(7))
            val radio = RadioId(deviceId)
            val saved = ContactDTO(
                radioId = radio, publicKey = key(20), name = "Saved offline identity", lastHeardTimestamp = null,
            )
            val handshake = CompletableDeferred<Unit>()
            h.links.configureRadio = { firmware ->
                firmware.connectGate = handshake
                firmware.knownContacts += contactFrame(key(20), saved.name)
            }
            try {
                store.saveDevice(DeviceDTO(id = deviceId, radioId = radio, publicKey = key(7), nodeName = "Cached radio"))
                store.saveContact(saved)
                val persisted = assertNotNull(store.fetchContact(radio, saved.publicKey))
                LastConnectionStore(h.preferences, SystemRuntimeClock()).persist(deviceId, radio, "Cached radio")
                h.container.connectionPort.refreshLastConnection()
                val targetId = if (compatible) deviceId else DeviceIdentity.deriveUUID(key(8))
                val connecting = async { h.manager.connect(h.target(targetId)) }
                h.eventually("handshake held with actual persisted offline radio") { h.links.radios.isNotEmpty() }
                val n = h.container.navigation
                assertNull(n.state.value.radioId)
                assertEquals(radio, h.appState.currentRadioId)
                val router = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n))
                val pending = PendingExternalRoute(router::routeExternal)
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Claimed alias", saved.publicKey, ContactType.ROOM)
                pending.markReady()
                assertEquals(
                    PendingExternalRoute.SubmitOutcome.Routed(DeepLinkRouteOutcome.Navigated),
                    pending.submit(PendingExternalRoute.Delivery("wp302-saved-offline", uri)),
                )
                val stack = n.state.value.activeStack
                assertEquals(persisted.id, n.state.value.selectedContact?.id)
                mount(h)
                compose.onNodeWithTag("entry:${stack.last().id}").assertIsDisplayed()
                handshake.complete(Unit)
                connecting.await()
                h.settle()
                h.assertReady()
                compose.waitForIdle()
                assertNull(pending.snapshot())
                assertNull(n.state.value.pendingContactLink)
                if (compatible) {
                    assertEquals(stack, n.state.value.activeStack)
                    assertEquals(persisted.id, n.state.value.selectedContact?.id)
                    compose.onNodeWithTag("entry:${stack.last().id}").assertIsDisplayed()
                } else {
                    assertNotEquals(radio, n.state.value.radioId)
                    assertNull(n.state.value.selectedContact)
                    assertEquals(1, n.state.value.activeStack.size)
                }
                assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
            } finally { unmount(); h.container.close() }
        }
    }

    @Test fun identicalDeliveriesReplaceMountedHoldersAndCancelHeldImportsWithoutLateAcknowledgement() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x09) emptyList() else null }
        }
        try {
            connect(h)
            mount(h)
            val n = h.container.navigation
            val raw = ContactLinkRequest("Identical claim", key(30), ContactType.REPEATER)
            n.stageContactLink(raw)
            val first = assertNotNull(n.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            val firmware = h.links.radios.last()
            h.eventually("real import waits for the held radio ACK") {
                firmware.sent.count { it[0].toInt() == 0x09 } == 1
            }
            compose.runOnUiThread { n.stageContactLink(raw) }
            val second = assertNotNull(n.state.value.pendingContactLink)
            assertNotEquals(first.deliveryId, second.deliveryId)
            assertFalse(n.completeContactLink(first), "Old dismissal must not clear an identical replacement")
            compose.waitForIdle()
            firmware.push(Bytes.of(0))
            await(h) { confirmationEnabled() }
            assertSame(second, n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingContactDetail, "The disposed import cannot navigate after a late ACK")
            compose.runOnUiThread {
                n.clearPendingContactLink()
                n.stageContactLink(raw)
            }
            val third = assertNotNull(n.state.value.pendingContactLink)
            assertNotEquals(second.deliveryId, third.deliveryId)
            assertFalse(n.completeContactLink(second), "An unobserved clear/restage must retire the old holder")
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink, "The current identical replacement remains dismissible")
            assertNull(n.state.value.pendingContactDetail)
            assertEquals(1, firmware.sent.count { it[0].toInt() == 0x09 })

            firmware.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
            compose.runOnUiThread {
                n.stageContactLink(raw)
                n.clearPendingContactLink()
                n.stageContactLink(raw)
            }
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { n.state.value.pendingContactLink == null }
            assertEquals(key(30), n.state.value.pendingContactDetail?.publicKey)
        } finally { unmount(); h.container.close() }
    }

    @Test fun mountedRetryMarksEveryLoadBusyAndCannotOverwriteANewerResult() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Retry saved identity") }
        try {
            connect(h)
            val actual = h.container.nodesFeature
            val saved = assertNotNull(store.fetchContact(assertNotNull(h.appState.currentRadioId), key(20)))
            var attempts = 0
            val entered = CompletableDeferred<Unit>()
            val retry = CompletableDeferred<ContactDTO?>()
            val delayed = object : NodesDataStore by assertNotNull(actual.session.servicesDataStore()) {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    attempts++
                    if (attempts == 1) throw IllegalStateException("Temporary lookup failure")
                    if (retry.isCompleted) return store.fetchContact(radioId, publicKey)
                    check(attempts == 2) { "An overlapping retry reached persistence" }
                    entered.complete(Unit)
                    return retry.await()
                }
            }
            val dependencies = NodesFeatureDependencies(
                object : NodesSession by actual.session {
                    override fun servicesDataStore() = delayed
                },
                actual.uriCodec, actual.timeouts, actual.messages, actual.announcer,
                actual.preferences, actual.clock, actual.locale,
            )
            val n = h.container.navigation
            n.stageContactLink(ContactLinkRequest("Retry claim", key(20), ContactType.ROOM))
            mount(h, dependencies)
            await(h) { compose.onAllNodesWithTag("contact-link-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { entered.isCompleted }
            compose.onNodeWithTag("contact-link-confirm").assertIsNotEnabled().performClick()
            compose.waitForIdle()
            assertEquals(2, attempts)
            retry.complete(saved)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-error").assertDoesNotExist()
            compose.onNodeWithTag("contact-link-name").assertTextEquals(saved.displayName)
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { n.state.value.pendingContactLink == null }
            assertEquals(saved.id, n.state.value.selectedContact?.id)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { unmount(); h.container.close() }
    }

    @Test fun aSavedContactLinkRetiresTheMountedPendingImportItReplaces() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Saved destination")
            radio.respond = { frame -> if (frame[0].toInt() == 0x09) emptyList() else null }
        }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val saved = assertNotNull(store.fetchContact(radio, key(20)))
            val n = h.container.navigation
            mount(h)
            n.stageContactLink(ContactLinkRequest("Retired pending import", key(30), ContactType.ROOM))
            val retired = assertNotNull(n.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            val firmware = h.links.radios.last()
            h.eventually("old mounted import waits for radio ACK") { firmware.sent.any { it[0].toInt() == 0x09 } }
            val uri = h.container.nodesFeature.uriCodec.exportContactUri("Claimed saved alias", key(20), ContactType.ROOM)
            assertEquals(
                DeepLinkRouteOutcome.Navigated,
                AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n)).routeExternal(uri),
            )
            assertNull(n.state.value.pendingContactLink)
            compose.waitForIdle()
            compose.onNodeWithTag("contact-link-confirmation").assertDoesNotExist()
            firmware.push(Bytes.of(0))
            h.settle()
            assertFalse(n.completeContactLink(retired))
            assertEquals(saved.id, n.state.value.selectedContact?.id)
            assertEquals(saved.id, assertIs<NavigationDestination.ContactDetail>(
                n.state.value.activeStack.last().destination,
            ).contact.id)
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
        } finally { unmount(); h.container.close() }
    }

    @Test fun closedGenerationCallbacksCannotNavigateOrClearANewRadioRequest() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Generation peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = store.fetchContact(radio, key(20))!!
            val service = assertNotNull(h.sessions.current).notificationService
            val stale = assertNotNull(service.onNotificationTapped)
            h.manager.disconnect()
            h.settle()
            assertNull(service.onNotificationTapped)
            assertFalse(h.container.navigation.state.value.navigationReady)
            connect(h)
            val before = h.container.navigation.state.value
            stale(EntityKey(radio, contact.id))
            assertEquals(before, h.container.navigation.state.value)
            assertNotNull(h.sessions.current?.notificationService?.onNotificationTapped)
        } finally { h.container.close() }
    }

    @Test fun externalAndChatContactLinksRequireMountedConsentAndImportExactMetadataIntoRoom() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
        }
        try {
            connect(h)
            mount(h)
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            val chat = ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope)
            for ((index, type) in ContactType.entries.withIndex()) {
                val publicKey = key(30 + index)
                val name = "Claimed ${type.name}"
                val uri = h.container.nodesFeature.uriCodec.exportContactUri(name, publicKey, type)
                if (index % 2 == 0) external.routeExternal(uri) else assertTrue(chat.route(uri))
                testScheduler.runCurrent()
                val staged = assertNotNull(navigation.state.value.pendingContactLink)
                assertEquals(name, staged.name)
                assertEquals(publicKey, staged.publicKey)
                assertEquals(type, staged.type)
                assertEquals(h.appState.currentRadioId, staged.targetRadioId)
                assertEquals(navigation.state.value.generation, staged.generation)
                await(h) {
                    compose.onAllNodesWithTag("contact-link-confirmation").fetchSemanticsNodes().isNotEmpty() &&
                        confirmationEnabled()
                }
                compose.onNodeWithTag("contact-link-public-key").assertTextEquals(publicKey.uppercaseHexString(" "))
                val firmware = h.links.radios.last()
                val writes = firmware.sent.count { it[0].toInt() == 0x09 }
                assertNull(store.fetchContact(staged.targetRadioId!!, publicKey), "URI receipt must not import")
                compose.runOnIdle { width.value = 834.dp }
                assertSame(staged, navigation.state.value.pendingContactLink)
                compose.onNodeWithTag("contact-link-confirm").performClick()
                await(h) { navigation.state.value.pendingContactLink == null }
                val added = assertNotNull(store.fetchContact(staged.targetRadioId, publicKey))
                assertEquals(name, added.name)
                assertEquals(type, added.type)
                assertEquals(PacketBuilder.FLOOD_PATH_SENTINEL, added.outPathLength)
                assertEquals(writes + 1, firmware.sent.count { it[0].toInt() == 0x09 })
                assertEquals(added.id, navigation.state.value.pendingContactDetail?.id)
                assertEquals(AppTab.NODES, navigation.state.value.selectedTab)
                compose.onNodeWithTag("entry:${navigation.state.value.activeStack.last().id}").assertIsDisplayed()
            }
        } finally { unmount(); h.container.close() }
    }

    @Test fun mountedCancellationAndOfflineSavedContactViewNeverWriteOrAcknowledgeReplacementLinks() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved identity") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val existing = store.fetchContact(radio, key(20))!!
            mount(h)
            val n = h.container.navigation
            n.stageContactLink(ContactLinkRequest("Cancelled claim", key(30), ContactType.REPEATER))
            val cancelled = assertNotNull(n.state.value.pendingContactLink)
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            assertNull(store.fetchContact(radio, key(30)))
            n.stageContactLink(ContactLinkRequest("Replacement claim", key(31), ContactType.ROOM))
            val replacement = n.state.value.pendingContactLink
            assertFalse(n.completeContactLink(cancelled))
            assertSame(replacement, n.state.value.pendingContactLink)
            n.clearPendingLinks()
            compose.waitForIdle()
            compose.onNodeWithTag("contact-link-confirmation").assertDoesNotExist()
            assertNull(n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingChannelLink)
            assertNull(n.state.value.pendingHashtag)
            h.manager.disconnect()
            h.settle()
            n.stageContactLink(ContactLinkRequest("Claimed alias", existing.publicKey, ContactType.ROOM), radio)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-name").assertTextEquals(existing.displayName)
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { n.state.value.pendingContactLink == null }
            assertEquals(existing.id, n.state.value.pendingContactDetail?.id)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { unmount(); h.container.close() }
    }

    @Test fun cancelledContactImportDoesNotNavigateOrConsumeIdenticalReplacementWhenLateAckPersists() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x09) emptyList() else null }
        }
        val cancelledWrites = ConcurrentLinkedQueue<Bytes>()
        try {
            connect(h)
            val radioId = assertNotNull(h.appState.currentRadioId)
            val firmware = h.links.radios.last()
            val actual = h.container.nodesFeature
            val service = assertNotNull(actual.session.contactService())
            val observingService = object : NodesContactService by service {
                override suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame) {
                    try {
                        service.addOrUpdateContact(radioId, contact)
                    } catch (cancelled: CancellationException) {
                        cancelledWrites.add(contact.publicKey)
                        throw cancelled
                    }
                }
            }
            val dependencies = NodesFeatureDependencies(
                object : NodesSession by actual.session {
                    override fun contactService() = observingService
                },
                actual.uriCodec, actual.timeouts, actual.messages, actual.announcer,
                actual.preferences, actual.clock, actual.locale,
            )
            mount(h, dependencies)
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            val chat = ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope)
            val publicKey = key(30)
            val name = "Late acknowledgement peer"
            val type = ContactType.CHAT
            val uri = actual.uriCodec.exportContactUri(name, publicKey, type)

            assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, external.routeExternal(uri))
            val first = assertNotNull(navigation.state.value.pendingContactLink)
            assertEquals(radioId, first.targetRadioId)
            assertEquals(navigation.state.value.generation, first.generation)
            assertEquals(name, first.name)
            assertEquals(publicKey, first.publicKey)
            assertEquals(type, first.type)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            h.eventually("contact write reaches the radio", virtualBudgetMillis = 100) {
                firmware.sent.count { it[0].toInt() == 0x09 } == 1
            }
            assertNull(store.fetchContact(radioId, publicKey))
            compose.onNodeWithTag("contact-link-cancel").performClick()
            h.eventually("cancelled confirmation leaves the real contact service", virtualBudgetMillis = 100) {
                publicKey in cancelledWrites
            }
            assertNull(navigation.state.value.pendingContactLink)
            assertNull(store.fetchContact(radioId, publicKey))

            assertTrue(chat.route(uri))
            h.eventually("identical chat link reaches a new confirmation", virtualBudgetMillis = 100) {
                navigation.state.value.pendingContactLink?.publicKey == publicKey
            }
            val replacement = assertNotNull(navigation.state.value.pendingContactLink)
            assertNotEquals(assertNotNull(first.deliveryId), assertNotNull(replacement.deliveryId))
            assertEquals(radioId, replacement.targetRadioId)
            assertEquals(first.generation, replacement.generation)
            assertEquals(name, replacement.name)
            assertEquals(type, replacement.type)
            h.eventually("replacement confirmation is mounted", virtualBudgetMillis = 100) {
                compose.onAllNodesWithTag("contact-link-confirmation").fetchSemanticsNodes().isNotEmpty()
            }
            val beforeAcknowledgement = navigation.state.value
            firmware.knownContacts += contactFrame(publicKey, name, actual.clock.wallNow.epochSecond)
            firmware.push(Bytes.of(0))
            val persisted = backgroundScope.async {
                var contact = store.fetchContact(radioId, publicKey)
                while (contact == null) {
                    delay(1)
                    contact = store.fetchContact(radioId, publicKey)
                }
                contact
            }
            h.eventually("late acknowledgement persists the real Room row", virtualBudgetMillis = 100) {
                persisted.isCompleted
            }
            val added = assertNotNull(persisted.await())
            assertEquals(radioId, added.radioId)
            assertEquals(publicKey, added.publicKey)
            assertEquals(name, added.name)
            assertEquals(type, added.type)
            assertEquals(PacketBuilder.FLOOD_PATH_SENTINEL, added.outPathLength)
            assertEquals(beforeAcknowledgement, navigation.state.value)
            assertFalse(navigation.completeContactLink(first))
            assertEquals(replacement, navigation.state.value.pendingContactLink)
            compose.onNodeWithTag("contact-link-confirmation").assertIsDisplayed()
            assertEquals(1, firmware.sent.count { it[0].toInt() == 0x09 })

            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(navigation.state.value.pendingContactLink)
            val sync = backgroundScope.async { service.syncContactsForRefresh(radioId) }
            h.eventually("complete radio contact sync finishes") { sync.isCompleted }
            sync.await()
            val reloaded = assertNotNull(store.fetchContact(radioId, publicKey))
            assertEquals(added.id, reloaded.id)
            assertEquals(DeepLinkRouteOutcome.Navigated, external.routeExternal(uri))
            await(h) { navigation.state.value.selectedContact?.id == reloaded.id }
            assertEquals(AppTab.NODES, navigation.state.value.selectedTab)
            assertEquals(NavigationDestination.ContactDetail(reloaded), navigation.state.value.activeStack.last().destination)
            compose.onNodeWithTag("entry:${navigation.state.value.activeStack.last().id}").assertIsDisplayed()
            assertEquals(1, firmware.sent.count { it[0].toInt() == 0x09 })
        } finally { unmount(); h.container.close() }
    }

    @Test fun cancelledAndStaleContactLookupsCannotMutateOrNavigateTheReplacementRequest() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Held lookup peer") }
        try {
            connect(h)
            val actual = h.container.nodesFeature
            val radio = assertNotNull(h.appState.currentRadioId)
            val existing = store.fetchContact(radio, key(20))!!
            val entered = CompletableDeferred<Unit>()
            val held = CompletableDeferred<ContactDTO?>()
            val backing = actual.session.servicesDataStore()!!
            val delayed = object : NodesDataStore by backing {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    entered.complete(Unit)
                    return held.await()
                }
            }
            val dependencies = NodesFeatureDependencies(
                object : NodesSession by actual.session {
                    override fun servicesDataStore() = delayed
                },
                actual.uriCodec, actual.timeouts, actual.messages, actual.announcer,
                actual.preferences, actual.clock, actual.locale,
            )
            val n = h.container.navigation
            n.stageContactLink(ContactLinkRequest("Held claim", key(20), ContactType.CHAT))
            val old = n.state.value.pendingContactLink!!
            val holder = ContactLinkConfirmation(dependencies, n, old)
            val job = async { holder.load() }
            entered.await()
            job.cancelAndJoin()
            assertNull(holder.state.value.failure, "Cancellation is not a lookup failure or fake success")
            assertFalse(holder.state.value.loading)
            assertSame(old, n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingContactDetail)

            val staleHolder = ContactLinkConfirmation(dependencies, n, old)
            val staleJob = async { staleHolder.load() }
            testScheduler.runCurrent()
            n.stageContactLink(ContactLinkRequest("Replacement", key(31), ContactType.REPEATER))
            val replacement = n.state.value.pendingContactLink
            held.complete(existing)
            staleJob.await()
            assertFalse(staleHolder.confirm())
            assertSame(replacement, n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingContactDetail)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { h.container.close() }
    }

    @Test fun teardownBetweenValidityAndCommitRejectsContactLookupAndNotificationAcknowledgement() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Commit boundary peer") }
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        var binding: NotificationNavigationBinding? = null
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            val generation = navigation.state.value.generation
            val captured = h.container.captureContactLinkSession(generation)
            val contactChecks = AtomicInteger()
            val notificationChecks = AtomicInteger()
            fun pauseAfterCheck(checks: AtomicInteger, pauseAt: Int, valid: () -> Boolean): Boolean {
                val result = valid()
                if (checks.incrementAndGet() == pauseAt) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Retirement did not release the commit check" }
                }
                return result
            }
            binding = NotificationNavigationBinding(
                session.notificationService, navigation, PersistenceNavigationLookup(store),
                connectedDevice = { h.appState.connectedDevice }, onOutcome = {},
                isCurrentSession = {
                    pauseAfterCheck(notificationChecks, 2) { h.container.isCurrentSession(session) }
                },
            )
            val lookup = async(Dispatchers.IO) {
                navigation.completeContactLookup(
                    ContactLinkRequest("Stale contact", contact.publicKey, ContactType.CHAT),
                    contact, radio, generation, radio,
                ) { pauseAfterCheck(contactChecks, 1, captured) }
            }
            val tap = async(Dispatchers.IO) {
                h.container.handleNotificationResponse(NotificationResponse(
                    NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()), null, null,
                ))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Both routes must pause after their validity checks")

            val disconnect = async { h.manager.disconnect() }
            h.eventually("teardown completes for the captured session") { disconnect.isCompleted }
            disconnect.await()
            assertNull(h.manager.snapshot.value.token)
            val afterRetirement = navigation.state.value
            release.countDown()
            assertFalse(lookup.await())
            assertFalse(tap.await(), "A stale notification must remain unacknowledged")
            assertEquals(afterRetirement, navigation.state.value)
        } finally {
            release.countDown()
            binding?.close()
            h.container.close()
        }
    }

    @Test fun queuedTapSurvivesRetirementBetweenItsValidityCheckAndCommit() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        val h = ContainerHarness(this, store, notificationNavigationReady = { delivery.retryPendingActions() })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Queued retirement target") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = CompletableDeferred<Unit>()
        val accepted = CopyOnWriteArrayList<UUID>()
        var binding: NotificationNavigationBinding? = null
        try {
            delivery = AndroidMessagingNotificationDelivery(context, dispatcherScope) { response ->
                val handled = h.container.handleNotificationResponse(response)
                val message = (response.payload as NotificationPayload.DirectMessage).messageID
                if (handled) accepted += message else rejected.complete(Unit)
                handled
            }
            val target = h.target()
            connect(h, target)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val checks = AtomicInteger()
            binding = NotificationNavigationBinding(
                session.notificationService, h.container.navigation, PersistenceNavigationLookup(store),
                connectedDevice = { h.appState.connectedDevice }, onOutcome = {},
                isCurrentSession = {
                    val valid = h.container.isCurrentSession(session)
                    if (checks.incrementAndGet() == 2) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Retirement did not release the queued tap" }
                    }
                    valid
                },
            )
            manager.cancelAll()
            delivery.registerCategories(SnapshotList.empty())
            val first = UUID.randomUUID()
            suspend fun enqueue(id: String, message: UUID) {
                manager.cancelAll()
                delivery.post(NotificationRequest(
                    NotificationId(id), NotificationCategory.DIRECT_MESSAGE, contact.name, "Queued tap",
                    false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), message),
                ))
                manager.activeNotifications.single().notification.contentIntent.send()
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            enqueue("wp302-retired-tap", first)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Tap must pause after its pre-commit validity check")
            val disconnect = async { h.manager.disconnect() }
            h.eventually("the tap's session retires before its commit") { disconnect.isCompleted }
            disconnect.await()
            h.settle()
            val afterRetirement = h.container.navigation.state.value
            release.countDown()
            h.eventually("the dispatcher receives the stale rejection") { rejected.isCompleted }
            assertEquals(afterRetirement, h.container.navigation.state.value)
            assertTrue(accepted.isEmpty())

            connect(h, target)
            h.eventually("the retained tap reaches the replacement session") { accepted.contains(first) }
            assertEquals(ChatSelection.Direct(contact), h.container.navigation.state.value.chatsSelectedRoute)
            delivery.retryPendingActions()
            val second = UUID.randomUUID()
            enqueue("wp302-retirement-sentinel", second)
            h.eventually("a later queued tap completes after the retained tap") { accepted.contains(second) }
            assertEquals(listOf(first, second), accepted.toList())
        } finally {
            release.countDown()
            binding?.close()
            dispatcherScope.cancel()
            manager.cancelAll()
            h.container.close()
        }
    }

    @Test fun queuedMarkReadWaitsForItsActionForwarder() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        val h = ContainerHarness(this, store, notificationNavigationReady = { delivery.retryPendingActions() })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Queued action target") }
        val rejected = CompletableDeferred<Unit>()
        val accepted = CopyOnWriteArrayList<UUID>()
        val forwarded = CopyOnWriteArrayList<Pair<EntityKey, UUID>>()
        try {
            delivery = AndroidMessagingNotificationDelivery(context, dispatcherScope) { response ->
                val handled = h.container.handleNotificationResponse(response)
                val message = (response.payload as NotificationPayload.DirectMessage).messageID
                if (handled) accepted += message else rejected.complete(Unit)
                handled
            }
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val key = EntityKey(radio, contact.id)
            session.notificationService.onMarkAsRead = null
            delivery.registerCategories(SnapshotList.of(NotificationCategoryDefinition(
                NotificationCategory.DIRECT_MESSAGE,
                SnapshotList.of(NotificationActionDefinition(NotificationAction.MARK_READ, "Mark read", null, null)),
            )))
            suspend fun enqueue(id: String, message: UUID) {
                manager.cancelAll()
                delivery.post(NotificationRequest(
                    NotificationId(id), NotificationCategory.DIRECT_MESSAGE, contact.name, "Queued action",
                    false, null, null, NotificationPayload.DirectMessage(key, message),
                ))
                manager.activeNotifications.single().notification.actions.single().actionIntent.send()
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }

            val first = UUID.randomUUID()
            enqueue("wp302-missing-action", first)
            h.eventually("the dispatcher retains the action while its forwarder is missing") { rejected.isCompleted }
            assertTrue(accepted.isEmpty())
            session.notificationService.onMarkAsRead = { target, message -> forwarded += target to message }
            delivery.retryPendingActions()
            h.eventually("the retained action reaches the installed forwarder") { accepted.contains(first) }
            val second = UUID.randomUUID()
            enqueue("wp302-action-sentinel", second)
            h.eventually("a later action drains after the retained action") { accepted.contains(second) }
            assertEquals(listOf(first, second), accepted.toList())
            assertEquals(listOf(key to first, key to second), forwarded.toList())
        } finally {
            dispatcherScope.cancel()
            manager.cancelAll()
            h.container.close()
        }
    }

    @Test fun contactCommitWinsWhenTeardownStartsInsideItsSerializedCommit() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Commit winner") }
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            val generation = navigation.state.value.generation
            val captured = h.container.captureContactLinkSession(generation)
            val checks = AtomicInteger()
            val route = async(Dispatchers.IO) {
                navigation.completeContactLookup(
                    ContactLinkRequest("Winner", contact.publicKey, ContactType.CHAT),
                    contact, radio, generation, radio,
                ) {
                    val result = captured()
                    if (checks.incrementAndGet() == 2) {
                        inside.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Commit boundary was not released" }
                    }
                    result
                }
            }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val disconnectStarted = CountDownLatch(1)
            val disconnect = async(Dispatchers.IO) {
                disconnectStarted.countDown()
                h.manager.disconnect()
            }
            assertTrue(disconnectStarted.await(10, TimeUnit.SECONDS))
            assertEquals(session.token, h.manager.snapshot.value.token)
            release.countDown()
            assertTrue(route.await())
            h.eventually("disconnect completes after the committed route") { disconnect.isCompleted }
            disconnect.await()
            assertEquals(contact.id, navigation.state.value.selectedContact?.id)
        } finally {
            release.countDown()
            h.container.close()
        }
    }

    @Test fun retiredConfirmationCannotOpenItsLoadedContactButCanStillBeDismissed() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val heldStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchConnectedRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
                return store.fetchConnectedRemoteNodeSessions(radioId)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = heldStore)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Retired confirmation") }
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            navigation.stageContactLink(ContactLinkRequest("Claim", contact.publicKey, ContactType.ROOM), radio)
            val link = assertNotNull(navigation.state.value.pendingContactLink)
            val confirmation = ContactLinkConfirmation(h.container.nodesFeature, navigation, link)
            confirmation.load()
            assertEquals(contact, confirmation.state.value.existing)

            val disconnect = async { h.manager.disconnect() }
            h.eventually("retired generation enters held cleanup") { cleanupEntered.isCompleted }
            assertFalse(session.isTornDown)
            assertFalse(h.container.isCurrentSession(session))
            val before = navigation.state.value
            assertFalse(confirmation.confirm())
            assertEquals(before, navigation.state.value)
            assertTrue(confirmation.dismiss())
            assertNull(navigation.state.value.pendingContactLink)
            assertNull(navigation.state.value.pendingContactDetail)
            releaseCleanup.complete(Unit)
            h.eventually("held cleanup finishes") { disconnect.isCompleted }
            disconnect.await()
        } finally {
            releaseCleanup.complete(Unit)
            h.container.close()
        }
    }

    @Test fun postReadyFailureWithHeldCleanupRejectsTheRetiredGraphDespiteItsReadySnapshot() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val heldStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchConnectedRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
                return store.fetchConnectedRemoteNodeSessions(radioId)
            }
        }
        lateinit var h: ContainerHarness
        var captured: (() -> Boolean)? = null
        var callback: (suspend (EntityKey) -> Unit)? = null
        var generation = -1L
        var failed = false
        h = ContainerHarness(this, store, persistenceOverride = heldStore, notificationNavigationReady = {
            if (!failed && h.manager.snapshot.value.state == DeviceConnectionState.READY) {
                failed = true
                val session = checkNotNull(h.sessions.current)
                generation = h.container.navigation.state.value.generation
                captured = h.container.captureContactLinkSession(generation)
                callback = checkNotNull(session.notificationService.onNotificationTapped)
                throw IllegalStateException("Failure after READY publication")
            }
        })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Held cleanup peer") }
        val connecting = async {
            try {
                h.manager.connect(h.target())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                Unit
            }
        }
        try {
            h.eventually("failed READY generation enters held disconnect cleanup") { cleanupEntered.isCompleted }
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            assertEquals(DeviceConnectionState.READY, h.manager.snapshot.value.state)
            assertEquals(session.token, h.manager.snapshot.value.token)
            assertFalse(session.isTornDown)
            assertFalse(h.container.isCurrentSession(session))
            val navigation = h.container.navigation
            val before = navigation.state.value
            assertFalse(navigation.completeContactLookup(
                ContactLinkRequest("Retired", contact.publicKey, ContactType.CHAT), contact,
                radio, generation, radio, assertNotNull(captured),
            ))
            val receipt = NotificationNavigationReceipt()
            withContext(receipt) { assertNotNull(callback)(EntityKey(radio, contact.id)) }
            assertEquals(NavigationOutcome.Failed(NavigationFailure.StaleGeneration), receipt.outcome)
            assertFalse(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()), null, null,
            )))
            assertEquals(before, navigation.state.value)
        } finally {
            releaseCleanup.complete(Unit)
            connecting.cancelAndJoin()
            h.container.close()
        }
    }
}
