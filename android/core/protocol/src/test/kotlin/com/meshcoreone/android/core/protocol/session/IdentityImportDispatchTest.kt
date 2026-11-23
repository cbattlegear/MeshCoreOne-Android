// AndroidOnly: WP-107 Observe private-key transport attempts without retaining secret material.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityImportDispatchTest {
    @Test fun queuedCancellationDoesNotReportAnAttempt() = runTest {
        val f = fixture()
        start(f)
        val preceding = checkedRequest { f.session.factoryReset() }
        runCurrent()
        var attempted = false
        val events = mutableListOf<DeviceWriteEvent>()
        val queued = checkedRequest {
            withContext(DeviceWriteObserver { _, event -> events += event }) {
                f.session.importPrivateKey(filled(0x33, 64), { attempted = true }, {})
            }
        }
        runCurrent()
        queued.cancel()
        runCurrent()
        assertFalse(attempted)
        assertEquals(emptyList(), events)
        assertEquals(2, f.transport.sent.size)
        f.transport.ok()
        runCurrent()
        preceding.await()
        f.session.stop()
    }

    @Test fun lostReplyReportsAttemptButNotCommit() = runTest {
        val f = fixture(SessionConfiguration(defaultTimeout = 0.2, clientIdentifier = "MCore"))
        start(f)
        var attempts = 0
        var commits = 0
        val request = checkedRequest {
            f.session.importPrivateKey(filled(0x33, 64), { attempts++ }, { commits++ })
        }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(0, commits)
        advanceTimeBy(200)
        runCurrent()
        assertFailsWith<MeshCoreException.Timeout> { request.await() }
        assertEquals(0, commits)
        f.session.stop()
    }

    @Test fun acknowledgedWriteReportsAttemptBeforeCommit() = runTest {
        val f = fixture()
        start(f)
        val events = mutableListOf<String>()
        val request = checkedRequest {
            f.session.importPrivateKey(filled(0x33, 64), { events += "attempt" }, { events += "commit" })
        }
        runCurrent()
        f.transport.ok()
        runCurrent()
        f.transport.receive(selfPacket())
        runCurrent()
        request.await()
        assertEquals(listOf("attempt", "commit"), events)
        f.session.stop()
    }

    @Test fun observerDistinguishesRejectedAndUnansweredWrites() = runTest {
        val f = fixture(SessionConfiguration(defaultTimeout = 0.2, clientIdentifier = "MCore"))
        start(f)
        val events = mutableListOf<DeviceWriteEvent>()
        val observer = DeviceWriteObserver { _, event -> events += event }
        val rejected = checkedRequest { withContext(observer) { f.session.setName("Changed") } }
        runCurrent()
        assertEquals(listOf(DeviceWriteEvent.ATTEMPTED), events)
        f.transport.error(1)
        runCurrent()
        assertFailsWith<MeshCoreException.DeviceError> { rejected.await() }
        assertEquals(listOf(DeviceWriteEvent.ATTEMPTED, DeviceWriteEvent.REJECTED), events)
        val pending = checkedRequest { withContext(observer) { f.session.setName("Changed again") } }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertFailsWith<MeshCoreException.Timeout> { pending.await() }
        assertEquals(DeviceWriteEvent.ATTEMPTED, events.last())
        f.session.stop()
    }

    @Test fun lateAcknowledgementIsReportedAfterCallerCancellation() = runTest {
        val f = fixture()
        start(f)
        val events = mutableListOf<DeviceWriteEvent>()
        val observer = DeviceWriteObserver { _, event -> events += event }
        val request = checkedRequest { withContext(observer) { f.session.setName("Changed") } }
        runCurrent()
        request.cancel()
        runCurrent()
        assertEquals(listOf(DeviceWriteEvent.ATTEMPTED), events)
        assertFalse(request.isCompleted)
        f.transport.ok()
        runCurrent()
        assertEquals(listOf(DeviceWriteEvent.ATTEMPTED, DeviceWriteEvent.ACCEPTED), events)
        assertTrue(request.isCompleted)
        f.session.stop()
    }
}
