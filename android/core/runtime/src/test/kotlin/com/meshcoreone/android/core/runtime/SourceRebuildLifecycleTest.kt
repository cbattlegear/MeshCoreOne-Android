// PortedFrom: MC1Services/Tests/MC1ServicesTests/ReconnectRebuildLifecycleTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: retained GATT stream renewal requires an actual fresh physical generation.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRebuildLifecycleTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("ReconnectRebuildLifecycleTests", "reconnect rebuild surfaces connected while the sync window is still open") {
            withFixture {
                connect(); manager.teardownSessionForReconnect()
                val gate = CompletableDeferred<Unit>()
                createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 1) gate.await() } } }
                val rebuild = backgroundScope.async { manager.rebuildSession(platform.target.deviceId) }
                runCurrent(); assertEquals(DeviceConnectionState.CONNECTED, manager.connectionState)
                assertEquals(1, radios.last().collectors); assertEquals(0, radios.first().collectors)
                gate.complete(Unit); runCurrent(); rebuild.await(); assertEquals(DeviceConnectionState.READY, manager.connectionState)
            }
        },
        original("ReconnectRebuildLifecycleTests", "bond loss during an active reconnect surfaces guided recovery and re-pairs") {
            withFixture {
                connect(); val callbacks = links.single().callbacks!!
                callbacks.onAutoReconnecting("lost"); runCurrent()
                assertEquals(platform.target.deviceId, manager.reconnectionCoordinator.reconnectingDeviceId)
                callbacks.onDisconnected(LinkFailure.AuthenticationFailed()); runCurrent()
                assertEquals(listOf(platform.target.deviceId), authFailures)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState); assertNull(manager.activeReconnectDeviceId)
                manager.connect(platform.target, false, true)
                assertEquals(DeviceConnectionState.READY, manager.connectionState); assertEquals(2, radios.size)
                assertEquals(1, radios.first().closes); assertEquals(1, services.last().monitoringStarts)
            }
        },
        original("ReconnectRebuildLifecycleTests", "a stale-device disconnect during reconnect is ignored and preserves the cycle") {
            withFixture {
                connect(); val stale = links.single().callbacks!!; val currentTarget = target()
                manager.connect(currentTarget); links.last().callbacks!!.onAutoReconnecting("current lost"); runCurrent()
                stale.onDisconnected(LinkFailure.AuthenticationFailed()); runCurrent()
                assertTrue(authFailures.isEmpty()); assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
                assertEquals(currentTarget.deviceId, manager.reconnectionCoordinator.reconnectingDeviceId)
            }
        },
        original("ReconnectRebuildLifecycleTests", "rebuild after a stopped predecessor completes its handshake over a refreshed stream") {
            withFixture {
                connect(); val firstToken = services.single().token
                manager.teardownSessionForReconnect(); assertEquals(0, radios.first().collectors); assertEquals(0, radios.first().closes)
                manager.rebuildSession(platform.target.deviceId)
                assertEquals(1, radios.first().closes); assertEquals(2, radios.sumOf { radio -> radio.frames.count { it[0].toInt() == 1 } })
                assertTrue(services.last().token.generation.value > firstToken.generation.value)
                assertEquals(firstToken.radioId, services.last().token.radioId)
                assertTrue(radios.all { it.maximumCollectors == 1 })
            }
        },
        original("ReconnectRebuildLifecycleTests", "renewDataStream declines when the machine is not connected") {
            withFixture {
                connect(); manager.teardownSessionForReconnect()
                val foreign = MeshCoreSession(radios.single(), coroutineContext = backgroundScope.coroutineContext)
                val failure = assertFailsWith<MeshCoreException.ConnectionLost> { foreign.start() }
                requiredCause<SessionCorrelationException.RetainedTransport>(failure)
                assertEquals(1, radios.single().frames.count { it[0].toInt() == 1 })
                assertEquals(0, radios.single().closes); assertEquals(0, radios.single().collectors)
            }
        },
        original("ReconnectRebuildLifecycleTests", "RSSI keepalive survives a data stream renewal") {
            withFixture {
                connect(); val oldLink = links.single(); val oldRadio = radios.single()
                assertNotNull(oldLink.live)
                manager.teardownSessionForReconnect(); assertNull(oldLink.live)
                assertTrue(oldRadio.isConnected()); assertEquals(0, oldRadio.closes)
                manager.rebuildSession(platform.target.deviceId)
                assertNotNull(links.last().live); assertEquals(services.last().token, links.last().live)
                assertEquals(1, oldRadio.closes); assertEquals(1, radios.last().collectors)
            }
        },
        original("ReconnectRebuildLifecycleTests", "a renewed stream delivers subsequent data in order") {
            withFixture {
                connect(); manager.teardownSessionForReconnect(); manager.rebuildSession(platform.target.deviceId)
                val session = services.last().inputs.connection.session; val received = mutableListOf<Int>()
                val subscription = session.eventsTracked()
                val reader = backgroundScope.launch { subscription.stream.collect { event ->
                    if (event is com.meshcoreone.android.core.protocol.event.MeshEvent.Battery) received += event.info.level.toInt()
                } }
                radios.last().receive(Bytes.of(12, 1, 0)); radios.last().receive(Bytes.of(12, 2, 0)); radios.last().receive(Bytes.of(12, 3, 0))
                runCurrent(); assertEquals(listOf(1, 2, 3), received)
                session.finishEvents(subscription.id); reader.join(); assertEquals(1, radios.first().closes)
            }
        },
    )
}
