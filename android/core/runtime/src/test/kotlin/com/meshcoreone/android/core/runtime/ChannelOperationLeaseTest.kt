// AndroidOnly: WP-209 Actual manager-issued channel lease binding, retirement, callback ownership and drain.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelOperationLeaseTest {
    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("rebuild callback reconnect entries cannot mutate its live owner or its ready replacement") {
            for (replace in listOf(false, true)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                val replacement = target()
                var checked = false
                onAvailable = {
                    if (!checked) {
                        checked = true
                        if (replace) manager.connect(replacement)
                        val currentId = if (replace) replacement.deviceId else id
                        val currentServices = services.last()
                        val currentRadio = radios.last()
                        assertFailsWith<IllegalStateException> { manager.teardownSessionForReconnect() }
                        assertFailsWith<IllegalStateException> { manager.handleReconnectionFailure() }
                        assertFailsWith<IllegalStateException> { manager.rebuildSession(currentId) }
                        if (replace) for (requireComplete in listOf(false, true)) {
                            val failure = assertFailsWith<IllegalStateException> {
                                manager.disconnectTransport(requireComplete)
                            }
                            assertEquals("A retired rebuild callback cannot claim another lifecycle operation", failure.message)
                        }
                        assertEquals(currentId, manager.connectedDevice?.id)
                        assertEquals(0, currentServices.teardowns)
                        assertEquals(0, currentRadio.closes)
                    }
                }
                if (replace) {
                    assertFailsWith<kotlinx.coroutines.CancellationException> {
                        manager.reconnectionCoordinator.handleReconnectionComplete(id)
                    }
                } else {
                    manager.reconnectionCoordinator.handleReconnectionComplete(id)
                }
                assertTrue(checked)
                assertEquals(if (replace) replacement.deviceId else id, manager.connectedDevice?.id)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertNotNull(manager.captureDeviceWrite())
                currentCoroutineContext().ensureActive()
            }
        },
        nativeCase("direct rebuild callback refuses reconnect teardown before moving its owner to retained") {
            withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                var rejected = false
                onAvailable = {
                    val failure = assertFailsWith<IllegalStateException> {
                        manager.teardownSessionForReconnect()
                    }
                    assertEquals("A rebuild callback cannot synchronously start reconnect teardown", failure.message)
                    assertEquals(0, services.last().teardowns)
                    rejected = true
                }
                manager.reconnectionCoordinator.handleReconnectionComplete(id)
                assertTrue(rejected)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertNotNull(manager.captureDeviceWrite())
                currentCoroutineContext().ensureActive()
            }
        },
        nativeCase("retired rebuild callback cannot join externally claimed retained generation teardown") {
            for (requireComplete in listOf(false, true)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                var teardown: Job? = null
                var rejected = false
                onAvailable = {
                    teardown = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        manager.teardownSessionForReconnect()
                    }
                    assertFalse(assertNotNull(teardown).isCompleted)
                    val failure = assertFailsWith<IllegalStateException> {
                        manager.disconnectTransport(requireComplete)
                    }
                    assertEquals("A retired rebuild callback cannot claim another lifecycle operation", failure.message)
                    assertEquals(0, services.last().teardowns)
                    rejected = true
                }
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    manager.reconnectionCoordinator.handleReconnectionComplete(id)
                }
                assertNotNull(teardown).join()
                assertTrue(rejected)
                assertEquals(1, services.last().teardowns)
                assertNull(manager.captureDeviceWrite())
                currentCoroutineContext().ensureActive()
            }
        },
        nativeCase("same-device rebuild intent suspended before commit cannot overwrite external disconnect") {
            withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var held = false
                onAvailable = {
                    preferences.beforeUpdate = {
                        if (!held) {
                            held = true
                            entered.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                        }
                    }
                    manager.connect(target(id))
                }
                val rebuilding = test.backgroundScope.launch {
                    assertFailsWith<kotlinx.coroutines.CancellationException> {
                        manager.reconnectionCoordinator.handleReconnectionComplete(id)
                    }
                }
                try {
                    entered.await()
                    val retirement = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        manager.disconnect()
                    }
                    test.runCurrent()
                    assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                    assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
                    assertFalse(retirement.isCompleted)
                    release.complete(Unit)
                    rebuilding.join()
                    retirement.join()
                    assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                    assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
                    assertNull(manager.captureDeviceWrite())
                    assertEquals(2, services.size)
                } finally {
                    release.complete(Unit)
                    preferences.beforeUpdate = {}
                    rebuilding.cancelAndJoin()
                }
            }
        },
        nativeCase("retired rebuild callback cannot queue lifecycle work behind its external retirement") {
            for (mode in listOf("disconnect", "replacement", "same-device")) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                var externalRetirement: Job? = null
                var rejected = false
                onAvailable = {
                    externalRetirement = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        manager.disconnect()
                    }
                    assertFalse(assertNotNull(externalRetirement).isCompleted)
                    val failure = assertFailsWith<IllegalStateException> {
                        when (mode) {
                            "disconnect" -> manager.disconnect()
                            "replacement" -> manager.connect(target())
                            else -> manager.connect(target(id))
                        }
                    }
                    assertEquals("A retired rebuild callback cannot claim another lifecycle operation", failure.message)
                    assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                    assertEquals(2, services.size)
                    assertEquals(0, services.last().teardowns)
                    rejected = true
                }
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    manager.reconnectionCoordinator.handleReconnectionComplete(id)
                }
                assertNotNull(externalRetirement).join()
                assertTrue(rejected)
                assertEquals(2, services.size)
                assertEquals(1, services.last().teardowns)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
                assertNull(manager.captureDeviceWrite())
                currentCoroutineContext().ensureActive()
            }
        },
        nativeCase("actual rebuild callback refuses waiting on independently submitted shutdown") {
            withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                var shutdownCaller: Job? = null
                var checked = false
                onAvailable = {
                    shutdownCaller = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        manager.close()
                    }
                    assertFalse(assertNotNull(shutdownCaller).isCompleted)
                    val failure = assertFailsWith<IllegalStateException> { manager.awaitShutdown() }
                    assertEquals("A rebuild callback cannot await shutdown draining its own scope", failure.message)
                    assertEquals(0, services.last().teardowns)
                    checked = true
                }
                val rebuilding = test.backgroundScope.launch {
                    try {
                        manager.reconnectionCoordinator.handleReconnectionComplete(id)
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
                rebuilding.join()
                assertNotNull(shutdownCaller).join()
                assertTrue(checked)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(1, services.last().teardowns)
                assertNull(manager.captureDeviceWrite())
            }
        },
        nativeCase("actual rebuild callback refuses synchronous close and shutdown wait before mutation") {
            withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                var checked = false
                onAvailable = {
                    val closeFailure = assertFailsWith<IllegalStateException> { manager.close() }
                    assertEquals("A rebuild callback cannot synchronously shut down its own runtime", closeFailure.message)
                    val waitFailure = assertFailsWith<IllegalStateException> { manager.awaitShutdown() }
                    assertEquals("A rebuild callback cannot await shutdown draining its own scope", waitFailure.message)
                    assertEquals(0, services.last().teardowns)
                    assertEquals(0, radios.last().closes)
                    checked = true
                }
                manager.reconnectionCoordinator.handleReconnectionComplete(id)
                assertTrue(checked)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertNotNull(manager.captureDeviceWrite())
                currentCoroutineContext().ensureActive()
            }
        },
        nativeCase("concurrent rebuild descendants cannot create circular synchronous retirement chains") {
            for (replace in listOf(false, true)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                var refusals = 0
                onAvailable = {
                    val callbackScope = CoroutineScope(currentCoroutineContext())
                    val children = List(2) {
                        callbackScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            assertFailsWith<IllegalStateException> {
                                if (replace) manager.connect(target()) else manager.disconnect()
                            }
                            refusals++
                        }
                    }
                    children.forEach { it.join() }
                    assertEquals(2, refusals)
                    assertEquals(0, services.last().teardowns)
                    manager.disconnect()
                }
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    manager.reconnectionCoordinator.handleReconnectionComplete(id)
                }
                assertEquals(2, refusals)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(1, services.last().teardowns)
                currentCoroutineContext().ensureActive()
            }
        },
        nativeCase("reparented rebuild callbacks refuse retirement before detaching or memoizing teardown") {
            for (replace in listOf(false, true)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                var rejected = false
                onAvailable = {
                    val reparented = Job()
                    try {
                        for (parent in listOf(NonCancellable, reparented)) withContext(parent) {
                            assertFailsWith<IllegalStateException> {
                                if (replace) manager.connect(target()) else manager.disconnect()
                            }
                        }
                    } finally {
                        reparented.cancel()
                    }
                    rejected = true
                    assertEquals(0, services.last().teardowns)
                    assertEquals(0, radios.last().closes)
                }
                manager.reconnectionCoordinator.handleReconnectionComplete(id)
                assertTrue(rejected)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertNotNull(manager.captureDeviceWrite())
                assertEquals(2, services.size)
            }
        },
        nativeCase("registration-time healthy reconnect observes original restoration authority before readiness") {
            val fixture = RuntimeFixture(this, dispatcher = Dispatchers.Unconfined)
            try {
                fixture.platform.state = fixture.platform.state.copy(
                    autoReconnecting = true, connectedDeviceId = fixture.platform.target.deviceId,
                )
                var registered = false
                fixture.onRegistration = { callbacks ->
                    if (!registered) {
                        registered = true
                        callbacks.onReconnected()
                    }
                }
                fixture.manager.connect(fixture.platform.target)
                runCurrent()
                assertTrue(registered)
                assertEquals(DeviceConnectionState.READY, fixture.manager.connectionState)
                val captured = assertNotNull(fixture.manager.captureDeviceWrite())
                assertEquals(captured.second, fixture.manager.withDeviceWrite(captured.first, captured.second.id) {
                    captured.second
                })
                assertEquals(1, fixture.services.size)
            } finally {
                fixture.close()
            }
        },
        nativeCase("reentrant rebuild retirement drains held structured siblings before teardown or replacement") {
            for (replace in listOf(false, true)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                val cleaning = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val lateCleaning = CompletableDeferred<Unit>()
                val lateRelease = CompletableDeferred<Unit>()
                val replacement = target()
                var called = false
                onAvailable = {
                    if (!called) {
                        called = true
                        val callbackScope = CoroutineScope(currentCoroutineContext())
                        callbackScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try { CompletableDeferred<Unit>().await() }
                            finally {
                                withContext(NonCancellable) {
                                    cleaning.complete(Unit)
                                    release.await()
                                    callbackScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        try { CompletableDeferred<Unit>().await() }
                                        finally {
                                            withContext(NonCancellable) {
                                                lateCleaning.complete(Unit)
                                                lateRelease.await()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (replace) manager.connect(replacement) else manager.disconnect()
                    }
                }
                val rebuilding = test.backgroundScope.launch {
                    assertFailsWith<kotlinx.coroutines.CancellationException> {
                        manager.reconnectionCoordinator.handleReconnectionComplete(id)
                    }
                    currentCoroutineContext().ensureActive()
                }
                try {
                    cleaning.await()
                    assertEquals(2, services.size)
                    assertEquals(0, services[1].teardowns)
                    assertEquals(0, radios[1].closes)
                    assertFalse(rebuilding.isCompleted)
                    release.complete(Unit)
                    lateCleaning.await()
                    assertEquals(2, services.size)
                    assertEquals(0, services[1].teardowns)
                    assertFalse(rebuilding.isCompleted)
                    lateRelease.complete(Unit)
                    rebuilding.join()
                    assertFalse(rebuilding.isCancelled)
                    assertEquals(1, services[1].teardowns)
                    if (replace) {
                        assertEquals(replacement.deviceId, manager.connectedDevice?.id)
                        assertEquals(DeviceConnectionState.READY, manager.connectionState)
                        assertNotNull(manager.captureDeviceWrite())
                    } else {
                        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                    }
                } finally {
                    release.complete(Unit)
                    lateRelease.complete(Unit)
                    rebuilding.cancelAndJoin()
                }
            }
        },
        nativeCase("actual rebuild service callback can disconnect or replace its own generation") {
            for (replace in listOf(false, true)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                val replacement = target()
                var called = false
                onAvailable = {
                    if (!called) {
                        called = true
                        if (replace) manager.connect(replacement) else manager.disconnect()
                    }
                }
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    manager.reconnectionCoordinator.handleReconnectionComplete(id)
                }
                assertTrue(called)
                assertEquals(1, services[1].teardowns)
                assertEquals(1, radios[1].closes)
                if (replace) {
                    assertEquals(replacement.deviceId, manager.connectedDevice?.id)
                    assertEquals(DeviceConnectionState.READY, manager.connectionState)
                    assertNotNull(manager.captureDeviceWrite())
                } else {
                    assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                    assertNull(manager.captureDeviceWrite())
                }
            }
        },
        nativeCase("cancelled original adoption cannot revive through a retained reconnect callback") {
            withFixture {
                last.persist(platform.target.deviceId, RadioId(UUID.randomUUID()), "Stored")
                platform.state = platform.state.copy(systemConnected = true)
                platform.adoptionSucceeds = true
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val delegate = StandardTestDispatcher(test.testScheduler)
                val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
                var hold = false
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        if (hold) queued += context to block else delegate.dispatch(context, block)
                    }
                }
                platform.onAdopt = { entered.complete(Unit); release.await() }
                val caller = test.backgroundScope.launch(dispatcher) {
                    manager.connect(platform.target, true, true)
                }
                try {
                    entered.await()
                    val callback = assertNotNull(links.single().callbacks)
                    callback.onReconnected()
                    test.runCurrent()
                    hold = true
                    caller.cancel()
                    release.complete(Unit)
                    test.runCurrent()
                    assertTrue(services.isEmpty())
                    assertNull(manager.captureDeviceWrite())
                    hold = false
                    queued.toList().also { queued.clear() }.forEach { (context, block) ->
                        delegate.dispatch(context, block)
                    }
                    caller.join()
                    callback.onReconnected()
                    test.runCurrent()
                    assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                    assertNull(manager.captureDeviceWrite())
                    assertTrue(services.isEmpty())
                    assertEquals(1, links.single().callbackClosures)
                } finally {
                    hold = false
                    queued.toList().also { queued.clear() }.forEach { (context, block) ->
                        delegate.dispatch(context, block)
                    }
                    release.complete(Unit)
                    caller.cancelAndJoin()
                }
            }
        },
        nativeCase("external retirement cancels only the owned rebuild scope and unblocks its successor") {
            for (replace in listOf(false, true)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                val entered = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                onFactory = { service, _ ->
                    service.beforeSync = {
                        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                            entered.complete(Unit)
                            try { CompletableDeferred<Unit>().await() }
                            finally {
                                withContext(NonCancellable) {
                                    cleaning.complete(Unit)
                                    release.await()
                                }
                            }
                        }
                    }
                }
                var callerContinued = false
                val rebuilding = test.backgroundScope.launch {
                    try {
                        manager.reconnectionCoordinator.handleReconnectionComplete(id)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        currentCoroutineContext().ensureActive()
                        callerContinued = true
                    }
                }
                var retiring: Job? = null
                try {
                    entered.await()
                    test.runCurrent()
                    assertNotNull(manager.captureDeviceWrite())
                    onFactory = { _, _ -> }
                    retiring = test.backgroundScope.launch {
                        if (replace) manager.connect(target()) else manager.disconnect()
                    }
                    cleaning.await()
                    assertFalse(retiring.isCompleted)
                    assertFalse(rebuilding.isCancelled)
                    assertNull(manager.captureDeviceWrite())
                    release.complete(Unit)
                    retiring.join()
                    rebuilding.join()
                    assertTrue(callerContinued)
                    if (!replace) connect()
                    assertEquals(DeviceConnectionState.READY, manager.connectionState)
                    assertNotNull(manager.captureDeviceWrite())
                } finally {
                    release.complete(Unit)
                    retiring?.cancelAndJoin()
                    rebuilding.cancelAndJoin()
                }
            }
        },
        nativeCase("late diagnostic failure preserves successful restoration completed by a duplicate") {
            val fixture = RuntimeFixture(this, dispatcher = Dispatchers.Unconfined)
            var duplicate: Job? = null
            try {
                fixture.last.persist(fixture.platform.target.deviceId, RadioId(UUID.randomUUID()), "Stored")
                fixture.platform.state = fixture.platform.state.copy(systemConnected = true)
                fixture.platform.adoptionSucceeds = true
                val diagnostic = IllegalStateException("late adoption diagnostic")
                fixture.onSubmission = {
                    duplicate = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        fixture.manager.connect(fixture.platform.target, true, true)
                    }
                    assertTrue(assertNotNull(duplicate).isCompleted)
                    assertFalse(assertNotNull(duplicate).isCancelled)
                    assertEquals(DeviceConnectionState.CONNECTING, fixture.manager.connectionState)
                    throw diagnostic
                }
                assertSame(diagnostic, assertFailsWith<IllegalStateException> {
                    fixture.manager.connect(fixture.platform.target, true, true)
                })
                assertEquals(1, fixture.links.size)
                assertEquals(0, fixture.links.single().callbackClosures)
                assertEquals(DeviceConnectionState.CONNECTING, fixture.manager.connectionState)
                assertNull(fixture.manager.captureDeviceWrite())
                fixture.onSubmission = {}
                fixture.links.single().callbacks!!.onReconnected()
                runCurrent()
                assertEquals(DeviceConnectionState.READY, fixture.manager.connectionState)
                val captured = assertNotNull(fixture.manager.captureDeviceWrite())
                assertEquals(captured.second, fixture.manager.withDeviceWrite(captured.first, captured.second.id) {
                    captured.second
                })
            } finally {
                duplicate?.cancelAndJoin()
                fixture.close()
            }
        },
        nativeCase("successful adoption route remains unbound until its actual reconnect establishes a graph") {
            withFixture {
                last.persist(platform.target.deviceId, RadioId(UUID.randomUUID()), "Stored")
                platform.state = platform.state.copy(systemConnected = true)
                platform.adoptionSucceeds = true
                manager.connect(platform.target, true, true)
                assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
                assertNull(manager.captureDeviceWrite())
                assertTrue(services.isEmpty())
                links.single().callbacks!!.onReconnected()
                test.runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                val captured = assertNotNull(manager.captureDeviceWrite())
                assertEquals(captured.second, manager.withDeviceWrite(captured.first, captured.second.id) {
                    captured.second
                })
                val inputs = services.single().inputs.connection
                var admitted = false
                manager.withChannelOperationScope { scope ->
                    manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                        admitted = true
                    }
                }
                assertTrue(admitted)
            }
        },
        nativeCase("completed await fast path settles before a blocked completion listener and caller return") {
            for (diagnosticThrows in listOf(false, true)) {
            val fixture = RuntimeFixture(this, dispatcher = Dispatchers.Unconfined)
            val diagnosticFailure = IllegalStateException("late submission diagnostic")
            val childRelease = CompletableDeferred<Unit>()
            var finisher: Thread? = null
            var caller: Job? = null
            try {
                val monitor = ConnectionManager::class.java.getDeclaredField("lock").let {
                    it.isAccessible = true
                    it.get(fixture.manager)
                }
                fixture.onFactory = { service, _ ->
                    service.beforeSync = {
                        CoroutineScope(currentCoroutineContext()).launch(
                            Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED,
                        ) { childRelease.await() }
                    }
                }
                fixture.onSubmission = {
                    val work = ConnectionManager::class.java.getDeclaredField("pending").let {
                        it.isAccessible = true
                        it.get(fixture.manager) as kotlinx.coroutines.Deferred<*>
                    }
                    work.start()
                    assertFalse(work.isCompleted)
                    finisher = Thread { childRelease.complete(Unit) }.also { it.start() }
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                    while (!work.isCompleted && System.nanoTime() < deadline) Thread.yield()
                    assertTrue(work.isCompleted && !work.isCancelled)
                    if (diagnosticThrows) throw diagnosticFailure
                }
                var returned = false
                synchronized(monitor) {
                caller = backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    if (diagnosticThrows) {
                        assertSame(diagnosticFailure, assertFailsWith<IllegalStateException> {
                            fixture.manager.connect(fixture.platform.target)
                        })
                    } else {
                        fixture.manager.connect(fixture.platform.target)
                    }
                    returned = true
                    CompletableDeferred<Unit>().await()
                }
                    assertTrue(returned)
                    // The finisher cannot run the settlement listener until this monitor is released.
                    assertTrue(assertNotNull(finisher).isAlive)
                    val captured = assertNotNull(fixture.manager.captureDeviceWrite())
                    assertNotNull(caller).cancel()
                    assertEquals(captured, fixture.manager.captureDeviceWrite())
                }
                assertNotNull(finisher).join(5_000)
                assertFalse(assertNotNull(finisher).isAlive)
                assertNotNull(caller).cancelAndJoin()
                val captured = assertNotNull(fixture.manager.captureDeviceWrite())
                assertEquals(captured.second, fixture.manager.withDeviceWrite(captured.first, captured.second.id) {
                    captured.second
                })
                val inputs = fixture.services.single().inputs.connection
                var admitted = false
                fixture.manager.withChannelOperationScope { scope ->
                    fixture.manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                        admitted = true
                    }
                }
                assertTrue(admitted)
            } finally {
                childRelease.complete(Unit)
                finisher?.join(5_000)
                caller?.cancelAndJoin()
                fixture.close()
            }
            }
        },
        nativeCase("cancellation before successful work settlement retires rather than stranding the graph") {
            withFixture {
                val delegate = StandardTestDispatcher(test.testScheduler)
                val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
                var holdOwner = false
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        if (holdOwner) queued += context to block else delegate.dispatch(context, block)
                    }
                }
                val hydrating = CompletableDeferred<Unit>()
                val hydrateRelease = CompletableDeferred<Unit>()
                val childRelease = CompletableDeferred<Unit>()
                var owner: Job? = null
                onFactory = { service, _ ->
                    service.beforeHydrate = {
                        hydrating.complete(Unit)
                        hydrateRelease.await()
                    }
                    service.beforeSync = {
                        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                            childRelease.await()
                        }
                    }
                }
                owner = test.backgroundScope.launch(dispatcher) { manager.connect(platform.target) }
                var duplicate: Job? = null
                try {
                    hydrating.await()
                    duplicate = test.backgroundScope.launch { manager.connect(platform.target) }
                    test.runCurrent()
                    holdOwner = true
                    hydrateRelease.complete(Unit)
                    test.runCurrent()
                    val captured = assertNotNull(manager.captureDeviceWrite())
                    val generation = ConnectionManager::class.java.getDeclaredField("active").let {
                        it.isAccessible = true
                        assertNotNull(it.get(manager))
                    }
                    val work = generation.javaClass.getDeclaredField("operation").let {
                        it.isAccessible = true
                        it.get(generation) as Job
                    }
                    assertNotNull(owner).cancel()
                    assertTrue(queued.isNotEmpty())
                    childRelease.complete(Unit)
                    test.runCurrent()
                    assertTrue(work.isCompleted && !work.isCancelled)
                    assertTrue(assertNotNull(owner).isCancelled)
                    assertTrue(duplicate.isCompleted && duplicate.isCancelled)
                    assertNull(manager.captureDeviceWrite())
                    assertNull(manager.withDeviceWrite(captured.first, captured.second.id) {
                        fail("Work completed only after original authority was cancelled")
                    })
                } finally {
                    holdOwner = false
                    queued.toList().also { queued.clear() }.forEach { (context, block) ->
                        delegate.dispatch(context, block)
                    }
                    hydrateRelease.complete(Unit)
                    childRelease.complete(Unit)
                    assertNotNull(owner).cancelAndJoin()
                    duplicate?.cancelAndJoin()
                }
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertNull(manager.captureDeviceWrite())
                assertEquals(1, services.single().teardowns)
            }
        },
        nativeCase("successful shared work settles before a delayed owner continuation can be cancelled") {
            for (succeed in listOf(true, false)) withFixture {
                val delegate = StandardTestDispatcher(test.testScheduler)
                val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
                var holdOwner = false
                val ownerDispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        if (holdOwner) queued += context to block else delegate.dispatch(context, block)
                    }
                }
                val hydrating = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                onFactory = { service, _ ->
                    service.beforeHydrate = {
                        hydrating.complete(Unit)
                        release.await()
                    }
                }
                val owner = test.backgroundScope.launch(ownerDispatcher) { manager.connect(platform.target) }
                var duplicate: Job? = null
                try {
                    hydrating.await()
                    assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
                    duplicate = test.backgroundScope.launch { manager.connect(platform.target) }
                    test.runCurrent()
                    assertFalse(duplicate.isCompleted)
                    assertEquals(1, services.size)
                    holdOwner = true
                    if (succeed) {
                        release.complete(Unit)
                        test.runCurrent()
                        assertTrue(duplicate.isCompleted && !duplicate.isCancelled)
                        assertFalse(owner.isCompleted)
                        assertTrue(queued.isNotEmpty())
                        val captured = assertNotNull(manager.captureDeviceWrite())
                        val inputs = services.single().inputs.connection
                        owner.cancel()
                        assertEquals(captured, manager.captureDeviceWrite())
                        assertEquals(captured.second, manager.withDeviceWrite(captured.first, captured.second.id) {
                            captured.second
                        })
                        var admitted = false
                        manager.withChannelOperationScope { scope ->
                            manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                                admitted = true
                            }
                        }
                        assertTrue(admitted)
                    } else {
                        owner.cancel()
                        assertNull(manager.captureDeviceWrite())
                    }
                } finally {
                    holdOwner = false
                    queued.toList().also { queued.clear() }.forEach { (context, block) ->
                        delegate.dispatch(context, block)
                    }
                    if (!succeed) {
                        test.runCurrent()
                        release.complete(Unit)
                    }
                    owner.cancelAndJoin()
                    if (!succeed && duplicate != null) {
                        duplicate.join()
                        assertTrue(duplicate.isCancelled)
                    }
                    duplicate?.cancelAndJoin()
                    release.complete(Unit)
                }
                if (!succeed) {
                    assertNull(manager.captureDeviceWrite())
                    assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                }
            }
        },
        nativeCase("settled connection and rebuild outlive later cancellation of unrelated caller work") {
            for (rebuild in listOf(false, true)) withFixture {
                if (rebuild) {
                    connect()
                    manager.reconnectionCoordinator.handleEnteringAutoReconnect(assertNotNull(manager.connectedDevice).id)
                }
                val returned = CompletableDeferred<Unit>()
                val caller = test.backgroundScope.launch {
                    if (rebuild) {
                        manager.reconnectionCoordinator.handleReconnectionComplete(platform.target.deviceId)
                    } else {
                        manager.connect(platform.target)
                    }
                    returned.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                try {
                    returned.await()
                    val captured = assertNotNull(manager.captureDeviceWrite())
                    val inputs = services.last().inputs.connection
                    caller.cancelAndJoin()
                    assertEquals(DeviceConnectionState.READY, manager.connectionState)
                    assertEquals(captured, manager.captureDeviceWrite())
                    assertEquals(0, services.last().teardowns)
                    assertEquals(captured.second, manager.withDeviceWrite(captured.first, captured.second.id) {
                        captured.second
                    })
                    var admitted = false
                    manager.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            admitted = true
                        }
                    }
                    assertTrue(admitted)
                } finally {
                    caller.cancelAndJoin()
                }
            }
        },
        nativeCase("success marker cannot admit writes after caller cancellation while work children finish") {
            for (rebuild in listOf(false, true)) withFixture {
                if (rebuild) {
                    connect()
                    manager.reconnectionCoordinator.handleEnteringAutoReconnect(assertNotNull(manager.connectedDevice).id)
                }
                val entered = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                onFactory = { service, _ ->
                    service.beforeSync = {
                        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                            entered.complete(Unit)
                            try {
                                CompletableDeferred<Unit>().await()
                            } finally {
                                withContext(NonCancellable) {
                                    cleaning.complete(Unit)
                                    release.await()
                                }
                            }
                        }
                    }
                }
                val connecting = test.backgroundScope.launch {
                    if (rebuild) manager.reconnectionCoordinator.handleReconnectionComplete(platform.target.deviceId)
                    else manager.connect(platform.target)
                }
                try {
                    entered.await()
                    test.runCurrent()
                    val owner = ConnectionManager::class.java.getDeclaredField("active").let {
                        it.isAccessible = true
                        assertNotNull(it.get(manager))
                    }
                    assertTrue(owner.javaClass.getDeclaredField("establishmentCompleted").let {
                        it.isAccessible = true
                        it.getBoolean(owner)
                    })
                    assertFalse(connecting.isCompleted)
                    val captured = assertNotNull(manager.captureDeviceWrite())
                    connecting.cancel()
                    assertNull(manager.captureDeviceWrite())
                    assertNull(manager.withDeviceWrite(captured.first, captured.second.id) {
                        fail("Success marker masked cancelled initiating authority")
                    })
                    cleaning.await()
                    assertEquals(0, services.last().teardowns)
                    assertTrue(checkNotNull(services.last().inputs.connection.scope.coroutineContext[Job]).isActive)
                    assertNull(manager.captureDeviceWrite())
                    assertNull(manager.withDeviceWrite(captured.first, captured.second.id) {
                        fail("Success marker masked cancelled work held in child cleanup")
                    })
                    manager.withChannelOperationScope { scope ->
                        val inputs = services.last().inputs.connection
                        assertFailsWith<IllegalStateException> {
                            manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                                fail("Success marker admitted a cancelled channel operation")
                            }
                        }
                    }
                } finally {
                    release.complete(Unit)
                    connecting.cancelAndJoin()
                }
            }
        },
        nativeCase("successful rebuilt graph refuses fresh admission after reconnect cycle supersession") {
            withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                val rebuilding = test.backgroundScope.launch {
                    manager.reconnectionCoordinator.handleReconnectionComplete(id)
                }
                rebuilding.join()
                assertTrue(rebuilding.isCompleted && !rebuilding.isCancelled)
                val captured = assertNotNull(manager.captureDeviceWrite())
                assertEquals(captured.second, manager.withDeviceWrite(captured.first, id) { captured.second })
                // Starts a genuine successor cycle without first detaching the exposed graph.
                manager.reconnectionCoordinator.restartTimeout(id)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(0, services.last().teardowns)
                assertNull(manager.captureDeviceWrite())
                assertNull(manager.withDeviceWrite(captured.first, id) {
                    fail("Completed establishment ignored its superseded reconnect cycle")
                })
                manager.withChannelOperationScope { scope ->
                    val inputs = services.last().inputs.connection
                    assertFailsWith<IllegalStateException> {
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            fail("Completed rebuild admitted a superseded channel operation")
                        }
                    }
                }
            }
        },
        nativeCase("cancelled connection establishment cannot admit writes while its sync cleanup is held") {
            withFixture {
                val entered = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                onFactory = { service, _ ->
                    service.beforeSync = {
                        entered.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } finally {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                release.await()
                            }
                        }
                    }
                }
                val connecting = test.backgroundScope.launch { manager.connect(platform.target) }
                try {
                    entered.await()
                    val captured = assertNotNull(manager.captureDeviceWrite())
                    connecting.cancel()
                    // The initiating Job is cancelled before its separately owned work processes cancellation.
                    assertNull(manager.captureDeviceWrite())
                    assertNull(manager.withDeviceWrite(captured.first, captured.second.id) {
                        fail("Cancelled initiating caller admitted a write")
                    })
                    cleaning.await()
                    assertTrue(checkNotNull(services.single().inputs.connection.scope.coroutineContext[Job]).isActive)
                    assertEquals(0, services.single().teardowns)
                    assertNull(manager.captureDeviceWrite())
                    assertNull(manager.withDeviceWrite(captured.first, captured.second.id) {
                        fail("Cancelled connection cleanup admitted a write")
                    })
                    manager.withChannelOperationScope { scope ->
                        val inputs = services.single().inputs.connection
                        assertFailsWith<IllegalStateException> {
                            manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                                fail("Cancelled establishment admitted a channel operation")
                            }
                        }
                    }
                } finally {
                    release.complete(Unit)
                    connecting.cancelAndJoin()
                }
            }
        },
        nativeCase("cancelled or superseded automatic rebuild cannot admit writes before generation cleanup") {
            for (cancelCaller in listOf(true, false)) withFixture {
                connect()
                val id = assertNotNull(manager.connectedDevice).id
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(id)
                val entered = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                val syncRelease = CompletableDeferred<Unit>()
                val cleanupRelease = CompletableDeferred<Unit>()
                onFactory = { service, _ ->
                    service.beforeSync = {
                        entered.complete(Unit)
                        try {
                            syncRelease.await()
                        } finally {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                cleanupRelease.await()
                            }
                        }
                    }
                }
                val rebuilding = test.backgroundScope.launch {
                    manager.reconnectionCoordinator.handleReconnectionComplete(id)
                }
                try {
                    entered.await()
                    val captured = assertNotNull(manager.captureDeviceWrite())
                    if (cancelCaller) rebuilding.cancel() else manager.reconnectionCoordinator.clearReconnectingDevice()
                    assertNull(manager.captureDeviceWrite())
                    assertNull(manager.withDeviceWrite(captured.first, captured.second.id) {
                        fail("Revoked rebuild claim admitted a write")
                    })
                    if (cancelCaller) cleaning.await()
                    assertEquals(2, services.size)
                    assertEquals(0, services.last().teardowns)
                    assertTrue(checkNotNull(services.last().inputs.connection.scope.coroutineContext[Job]).isActive)
                } finally {
                    syncRelease.complete(Unit)
                    cleanupRelease.complete(Unit)
                    rebuilding.cancelAndJoin()
                }
            }
        },
        nativeCase("completed healthy connection and rebuild remain eligible after their initiating calls return") {
            withFixture {
                val connecting = test.backgroundScope.launch { manager.connect(platform.target) }
                connecting.join()
                assertTrue(connecting.isCompleted && !connecting.isCancelled)
                val first = assertNotNull(manager.captureDeviceWrite())
                assertEquals(first.second, manager.withDeviceWrite(first.first, first.second.id) { first.second })
                manager.reconnectionCoordinator.handleEnteringAutoReconnect(first.second.id)
                val rebuilding = test.backgroundScope.launch {
                    manager.reconnectionCoordinator.handleReconnectionComplete(first.second.id)
                }
                rebuilding.join()
                assertTrue(rebuilding.isCompleted && !rebuilding.isCancelled)
                val rebuilt = assertNotNull(manager.captureDeviceWrite())
                assertNotEquals(first.first, rebuilt.first)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(rebuilt.second, manager.withDeviceWrite(rebuilt.first, rebuilt.second.id) { rebuilt.second })
            }
        },
        nativeCase("inline cleanup never resumes teardown while the generation monitor is held") {
            for (deviceWrite in listOf(true, false)) {
                val fixture = RuntimeFixture(this, dispatcher = Dispatchers.Unconfined)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var operation: Job? = null
                var retirement: kotlinx.coroutines.Deferred<com.meshcoreone.android.core.contracts.domain.TeardownReport>? = null
                try {
                    fixture.connect()
                    val monitor = ConnectionManager::class.java.getDeclaredField("lock").let {
                        it.isAccessible = true
                        it.get(fixture.manager)
                    }
                    var teardownObserved = false
                    fixture.services.single().beforeTeardown = {
                        assertFalse(Thread.holdsLock(monitor))
                        teardownObserved = true
                    }
                    fixture.radios.single().beforeClose = { assertFalse(Thread.holdsLock(monitor)) }
                    val inputs = fixture.services.single().inputs.connection
                    operation = backgroundScope.launch(Dispatchers.Unconfined) {
                        if (deviceWrite) {
                            fixture.manager.withDeviceWrite(inputs.token, assertNotNull(fixture.manager.connectedDevice).id) {
                                entered.complete(Unit)
                                release.await()
                            }
                        } else {
                            fixture.manager.withChannelOperationScope { scope ->
                                fixture.manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                                    entered.complete(Unit)
                                    release.await()
                                }
                            }
                        }
                    }
                    entered.await()
                    retirement = backgroundScope.async(Dispatchers.Unconfined) { fixture.manager.disconnect() }
                    assertFalse(retirement.isCompleted)
                    release.complete(Unit)
                    operation.join()
                    assertTrue(retirement.await().isComplete)
                    assertTrue(teardownObserved)
                } finally {
                    release.complete(Unit)
                    operation?.cancelAndJoin()
                    retirement?.cancelAndJoin()
                    fixture.close()
                }
            }
        },
        nativeCase("an atomic device capture cannot borrow a same-device successor token") {
            withFixture {
                assertNull(manager.captureDeviceWrite())
                connect()
                val captured = assertNotNull(manager.captureDeviceWrite())
                assertEquals(services.single().inputs.connection.token, captured.first)
                assertEquals(manager.connectedDevice, captured.second)
                assertTrue(manager.disconnect().isComplete)
                assertNull(manager.captureDeviceWrite())
                connect()
                val successor = assertNotNull(manager.captureDeviceWrite())
                assertEquals(captured.second.id, successor.second.id)
                assertNotEquals(captured.first, successor.first)
                assertNull(manager.withDeviceWrite(captured.first, captured.second.id) {
                    fail("Original capture acquired the same-device successor")
                })
                assertEquals(successor.second, manager.withDeviceWrite(successor.first, successor.second.id) {
                    assertNotNull(manager.connectedDevice)
                })
            }
        },
        nativeCase("device writes require the exact live generation and finish before unrelated retirement") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                val device = assertNotNull(manager.connectedDevice)
                assertEquals("live", manager.withDeviceWrite(inputs.token, device.id) { "live" })
                assertNull(manager.withDeviceWrite(
                    inputs.token.copy(generation = Generation(inputs.token.generation.value + 1)), device.id,
                ) { fail("Foreign generation admitted") })
                assertNull(manager.withDeviceWrite(inputs.token, UUID.randomUUID()) { fail("Foreign device admitted") })
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val outcomes = mutableListOf<String>()
                val write = test.backgroundScope.async {
                    manager.withDeviceWrite(inputs.token, device.id) {
                        entered.complete(Unit)
                        release.await()
                        outcomes += "write committed"
                        "saved"
                    }
                }
                entered.await()
                val retirement = test.backgroundScope.async {
                    manager.disconnect().also { outcomes += "retirement terminal" }
                }
                try {
                    test.runCurrent()
                    assertFalse(retirement.isCompleted)
                    assertEquals(0, services.single().teardowns)
                    assertNull(manager.withDeviceWrite(inputs.token, device.id) { fail("Retired generation admitted") })
                    assertEquals(1, services.size, "No successor has written a newer device revision")
                    release.complete(Unit)
                    assertEquals("saved", write.await())
                    assertTrue(retirement.await().isComplete)
                    assertEquals(listOf("write committed", "retirement terminal"), outcomes)
                    assertEquals(1, services.single().teardowns)
                    assertNull(manager.withDeviceWrite(inputs.token, device.id) { fail("Completed retirement admitted") })
                } finally {
                    release.complete(Unit)
                    write.cancelAndJoin()
                    retirement.cancelAndJoin()
                }
            }
        },
        nativeCase("device write drain prevents successor installation and nested self retirement") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                val device = assertNotNull(manager.connectedDevice)
                manager.withDeviceWrite(inputs.token, device.id) {
                    assertFailsWith<IllegalStateException> { manager.disconnect() }
                    withContext(NonCancellable) {
                        assertFailsWith<IllegalStateException> { manager.close() }
                        assertFailsWith<IllegalStateException> { manager.awaitShutdown() }
                        assertFailsWith<IllegalStateException> { manager.disconnectTransport() }
                    }
                    coroutineScope {
                        async {
                            assertFailsWith<IllegalStateException> { manager.teardownSessionForReconnect() }
                            assertFailsWith<IllegalStateException> { manager.rebuildSession(device.id) }
                            assertFailsWith<IllegalStateException> { manager.handleReconnectionFailure() }
                        }.await()
                    }
                    assertFailsWith<IllegalStateException> {
                        manager.withDeviceWrite(inputs.token, device.id) { fail("Nested write admitted") }
                    }
                    assertEquals(DeviceConnectionState.READY, manager.connectionState)
                    assertEquals(0, services.single().teardowns)
                }
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val write = test.backgroundScope.launch {
                    manager.withDeviceWrite(inputs.token, device.id) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                val successor = test.backgroundScope.async {
                    manager.connect(com.meshcoreone.android.core.contracts.domain.ConnectionTarget.WiFi("localhost", 5000u))
                }
                try {
                    test.runCurrent()
                    assertFalse(successor.isCompleted)
                    assertEquals(1, services.size)
                    release.complete(Unit)
                    write.join()
                    successor.await()
                    assertEquals(2, services.size)
                    assertNotEquals(inputs.token, services.last().inputs.connection.token)
                    assertNull(manager.withDeviceWrite(inputs.token, device.id) { fail("Old token admitted after replacement") })
                } finally {
                    release.complete(Unit)
                    write.cancelAndJoin()
                    successor.cancelAndJoin()
                }
            }
        },
        nativeCase("device write failures and awaited cancellation rollback release the retirement drain") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                val device = assertNotNull(manager.connectedDevice)
                assertFailsWith<IllegalArgumentException> {
                    manager.withDeviceWrite(inputs.token, device.id) { throw IllegalArgumentException("write failed") }
                }
                val entered = CompletableDeferred<Unit>()
                val rollback = CompletableDeferred<Unit>()
                var rolledBack = false
                val write = test.backgroundScope.launch {
                    manager.withDeviceWrite(inputs.token, device.id) {
                        try {
                            entered.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        } finally {
                            withContext(NonCancellable) {
                                rollback.await()
                                rolledBack = true
                            }
                        }
                    }
                }
                entered.await()
                write.cancel()
                val retirement = test.backgroundScope.async { manager.disconnect() }
                try {
                    test.runCurrent()
                    assertFalse(rolledBack)
                    assertFalse(retirement.isCompleted)
                    assertEquals(0, services.single().teardowns)
                    rollback.complete(Unit)
                    write.join()
                    assertTrue(retirement.await().isComplete)
                    assertTrue(rolledBack)
                    assertEquals(1, services.single().teardowns)
                } finally {
                    rollback.complete(Unit)
                    write.cancelAndJoin()
                    retirement.cancelAndJoin()
                }
            }
        },
        nativeCase("retirement drains both channel and device operations in either completion order") {
            for (deviceFirst in listOf(true, false)) withFixture {
                connect()
                val inputs = services.single().inputs.connection
                val device = assertNotNull(manager.connectedDevice)
                val deviceEntered = CompletableDeferred<Unit>()
                val channelEntered = CompletableDeferred<Unit>()
                val deviceRelease = CompletableDeferred<Unit>()
                val channelRelease = CompletableDeferred<Unit>()
                val writing = test.backgroundScope.launch {
                    manager.withDeviceWrite(inputs.token, device.id) {
                        deviceEntered.complete(Unit)
                        deviceRelease.await()
                    }
                }
                val channel = test.backgroundScope.launch {
                    manager.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            channelEntered.complete(Unit)
                            channelRelease.await()
                        }
                    }
                }
                deviceEntered.await()
                channelEntered.await()
                val retirement = test.backgroundScope.async { manager.disconnect() }
                try {
                    test.runCurrent()
                    assertFalse(retirement.isCompleted)
                    if (deviceFirst) {
                        deviceRelease.complete(Unit)
                        writing.join()
                    } else {
                        channelRelease.complete(Unit)
                        channel.join()
                    }
                    test.runCurrent()
                    assertFalse(retirement.isCompleted)
                    assertEquals(0, services.single().teardowns)
                    deviceRelease.complete(Unit)
                    channelRelease.complete(Unit)
                    writing.join()
                    channel.join()
                    assertTrue(retirement.await().isComplete)
                    assertEquals(1, services.single().teardowns)
                } finally {
                    deviceRelease.complete(Unit)
                    channelRelease.complete(Unit)
                    writing.cancelAndJoin()
                    channel.cancelAndJoin()
                    retirement.cancelAndJoin()
                }
            }
        },
        nativeCase("channel lease requires the actual current token connection radio and process scope") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                val otherSession = MeshCoreSession(TestRadio(), coroutineContext = test.backgroundScope.coroutineContext)
                try {
                    manager.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) { lease ->
                            var executed = false
                            lease.withChannelIdentityMutation(inputs.token.radioId) { executed = true }
                            assertTrue(executed)
                        }
                        for ((token, session, radio) in listOf(
                            Triple(inputs.token.copy(generation = Generation(inputs.token.generation.value + 1)), inputs.session, inputs.token.radioId),
                            Triple(inputs.token, otherSession, inputs.token.radioId),
                            Triple(inputs.token, inputs.session, RadioId(UUID.randomUUID())),
                        )) {
                            assertFailsWith<IllegalStateException> {
                                manager.withChannelOperationLease(token, session, radio, scope) { fail("Wrong binding admitted") }
                            }
                        }
                    }
                    ChannelIdentityMutex().withChannelOperationScope { foreign ->
                        assertFailsWith<IllegalStateException> {
                            manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, foreign) {
                                fail("Foreign process gate admitted")
                            }
                        }
                    }
                } finally {
                    otherSession.stop()
                }
            }
        },
        nativeCase("lease cannot escape its callback borrow a child Job or recursively retire itself") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                lateinit var escaped: ConnectionManager.ChannelOperationLease
                manager.withChannelOperationScope { scope ->
                    manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) { lease ->
                        escaped = lease
                        test.backgroundScope.async {
                            assertFailsWith<IllegalStateException> {
                                lease.withChannelIdentityMutation(inputs.token.radioId) { fail("Foreign Job admitted") }
                            }
                        }.await()
                        assertFailsWith<IllegalStateException> { manager.disconnect() }
                        assertFailsWith<IllegalStateException> { manager.close() }
                        assertFailsWith<IllegalStateException> { manager.teardownSessionForReconnect() }
                        assertFailsWith<IllegalStateException> { manager.rebuildSession(platform.target.deviceId) }
                        assertFailsWith<IllegalStateException> { manager.disconnectTransport() }
                        assertFailsWith<IllegalStateException> { manager.disconnectTransport(requireComplete = true) }
                        assertFailsWith<IllegalStateException> { manager.handleReconnectionFailure() }
                        assertFailsWith<IllegalStateException> { manager.awaitShutdown() }
                        assertEquals(DeviceConnectionState.READY, manager.connectionState)
                        assertEquals(0, services.single().teardowns)
                        assertFailsWith<IllegalStateException> {
                            manager.withChannelOperationScope { fail("Operation lane reacquired") }
                        }
                        withContext(NonCancellable) {
                            assertFailsWith<IllegalStateException> { manager.close() }
                            assertFailsWith<IllegalStateException> { manager.disconnectTransport() }
                            assertFailsWith<IllegalStateException> { manager.awaitShutdown() }
                        }
                        coroutineScope {
                            async {
                                assertFailsWith<IllegalStateException> { manager.teardownSessionForReconnect() }
                                assertFailsWith<IllegalStateException> { manager.rebuildSession(platform.target.deviceId) }
                                assertFailsWith<IllegalStateException> { manager.handleReconnectionFailure() }
                            }.await()
                        }
                        assertEquals(DeviceConnectionState.READY, manager.connectionState)
                        assertEquals(0, services.single().teardowns)
                        lease.withChannelIdentityMutation(inputs.token.radioId) { Unit }
                        assertFailsWith<IllegalStateException> {
                            manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                                fail("Recursive lease admitted")
                            }
                        }
                    }
                    assertFailsWith<IllegalStateException> {
                        escaped.withChannelIdentityMutation(inputs.token.radioId) { fail("Expired lease admitted") }
                    }
                }
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertTrue(manager.disconnect().isComplete)
                assertEquals(1, services.single().teardowns)
            }
        },
        nativeCase("an internal constructor is not authority without the managers private issued registry") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                manager.withChannelOperationScope { scope ->
                    val forgedGeneration = ConnectionManager.RadioGeneration(
                        inputs.token.generation.value, platform.target, 0,
                        test.backgroundScope.coroutineContext, checkNotNull(currentCoroutineContext()[Job]),
                    )
                    try {
                        forgedGeneration.token = inputs.token
                        forgedGeneration.session = inputs.session
                        val forged = ConnectionManager.ChannelOperationLease(
                            manager, forgedGeneration, inputs.token, inputs.session, scope,
                            checkNotNull(currentCoroutineContext()[Job]),
                        )
                        assertFailsWith<IllegalStateException> {
                            forged.withChannelIdentityMutation(inputs.token.radioId) { fail("Unregistered lease admitted") }
                        }
                    } finally {
                        forgedGeneration.job.cancelAndJoin()
                    }
                }
            }
        },
        nativeCase("disconnect revokes new admissions but drains the admitted identity callback before teardown") {
            withFixture {
                connect()
                val handle = services.single()
                val inputs = handle.inputs.connection
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val effects = mutableListOf<String>()
                val operation = test.backgroundScope.launch {
                    manager.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) { lease ->
                            lease.withChannelIdentityMutation(inputs.token.radioId) {
                                entered.complete(Unit)
                                release.await()
                                effects += "admitted operation terminal"
                            }
                        }
                    }
                }
                entered.await()
                val disconnect = test.backgroundScope.async {
                    manager.disconnect().also { effects += "disconnect terminal" }
                }
                test.runCurrent()
                assertFalse(disconnect.isCompleted)
                assertEquals(0, handle.teardowns)
                assertTrue(radios.single().isConnected())
                release.complete(Unit)
                operation.join()
                disconnect.await()
                assertEquals(listOf("admitted operation terminal", "disconnect terminal"), effects)
                assertEquals(1, handle.teardowns)
                manager.withChannelOperationScope { scope ->
                    assertFailsWith<IllegalStateException> {
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            fail("Retired session admitted")
                        }
                    }
                }
            }
        },
        nativeCase("replacement cannot install a successor until an old admitted operation reaches terminal outcome") {
            withFixture {
                connect()
                val old = services.single().inputs.connection
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val held = test.backgroundScope.launch {
                    manager.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(old.token, old.session, old.token.radioId, scope) { lease ->
                            lease.withChannelIdentityMutation(old.token.radioId) {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    }
                }
                entered.await()
                val replacement = test.backgroundScope.async {
                    manager.connect(com.meshcoreone.android.core.contracts.domain.ConnectionTarget.WiFi("localhost", 5000u))
                }
                test.runCurrent()
                assertFalse(replacement.isCompleted)
                assertEquals(1, services.size)
                release.complete(Unit)
                held.join()
                replacement.await()
                val fresh = services.last().inputs.connection
                assertNotEquals(old.token, fresh.token)
                assertNotSame(old.session, fresh.session)
                manager.withChannelOperationScope { scope ->
                    assertFailsWith<IllegalStateException> {
                        manager.withChannelOperationLease(old.token, old.session, old.token.radioId, scope) {
                            fail("Replaced session admitted")
                        }
                    }
                    manager.withChannelOperationLease(fresh.token, fresh.session, fresh.token.radioId, scope) { lease ->
                        lease.withChannelIdentityMutation(fresh.token.radioId) { Unit }
                    }
                }
            }
        },
        nativeCase("throw and cancellation release only their actual permits so retirement can finish") {
            withFixture {
                connect()
                val inputs = services.single().inputs.connection
                manager.withChannelOperationScope { scope ->
                    assertFailsWith<IllegalArgumentException> {
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            throw IllegalArgumentException("operation failure")
                        }
                    }
                }
                val entered = CompletableDeferred<Unit>()
                val cancelled = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.withChannelOperationScope { scope ->
                        manager.withChannelOperationLease(inputs.token, inputs.session, inputs.token.radioId, scope) {
                            entered.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                    }
                }
                entered.await()
                cancelled.cancelAndJoin()
                val report = manager.disconnect()
                assertTrue(report.isComplete)
                assertEquals(1, services.single().teardowns)
            }
        },
    )
}
