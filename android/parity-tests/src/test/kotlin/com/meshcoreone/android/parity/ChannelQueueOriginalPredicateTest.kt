// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ChannelQueueOriginalPredicateTest {
    @Test fun rawErrorRetainsOneOccupantBoundAttemptWithoutAChannelQuery() = runTest {
        withChannelQueueFixture(0, MessageServiceConfig(
            poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01))) { f ->
            f.startDrain()
            f.queue.awaitDrainCompletion()

            f.assertUncertainAttempt(f.pendingA, expectedAttemptCount = 1)
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(1, f.queue.queuedEnvelopeCount)
            assertEquals(1, f.sends(3).size)
            assertEquals(listOf(Bytes.of(31, 0)), f.sends(31))
            assertTrue(f.queries.isEmpty())
            assertTrue(f.failures("park", f.messageA.id).isEmpty())
            assertTrue(f.failures("terminal", f.messageA.id).isEmpty())
            assertOriginalChannelDeviceError2(f.failures("channelRetained", f.pendingA.id).single())
        }
    }

    @Test fun uncertainRawErrorDoesNotReplayAtTheParkDeadlineOrTransportWake() = runTest {
        withChannelQueueFixture(0) { f ->
            f.startDrain()
            f.queue.awaitDrainCompletion()
            val originalFailure = f.failures("channelRetained", f.pendingA.id).single()
            assertOriginalChannelDeviceError2(originalFailure)

            advanceTimeBy(31_000)
            runCurrent()
            f.assertUncertainAttempt(f.pendingA, expectedAttemptCount = 1)
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(1, f.sends(3).size)
            assertEquals(listOf(Bytes.of(31, 0)), f.sends(31))
            assertTrue(f.queries.isEmpty())
            assertEquals(1, f.queue.queuedEnvelopeCount)
            assertTrue(f.failures("park", f.messageA.id).isEmpty())

            f.queue.transportDidOpen()
            runCurrent()
            f.queue.awaitDrainCompletion()

            f.assertUncertainAttempt(f.pendingA, expectedAttemptCount = 1)
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(1, f.sends(3).size)
            assertEquals(listOf(Bytes.of(31, 0)), f.sends(31))
            assertTrue(f.queries.isEmpty())
            assertEquals(1, f.queue.queuedEnvelopeCount)
            assertOriginalChannelDeviceError2(originalFailure)
        }
    }

    @Test fun rawErrorDoesNotQueryOrRetireWhenALaterChannelReadWouldBeUnconfigured() = runTest {
        withChannelQueueFixture(7) { f ->
            f.startDrain()
            f.queue.awaitDrainCompletion()

            f.assertUncertainAttempt(f.pendingA, expectedAttemptCount = 8)
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(MessageStatus.PENDING, f.messageA.status)
            assertEquals(1, f.queue.queuedEnvelopeCount)
            assertEquals(1, f.sends(3).size)
            assertEquals(listOf(Bytes.of(31, 0)), f.sends(31))
            assertEquals(1, f.channelInfoReadCount)
            assertTrue(f.queries.isEmpty())
            assertTrue(f.queryResults.isEmpty())
            assertTrue(f.failures("terminal", f.messageA.id).isEmpty())
            assertOriginalChannelDeviceError2(f.failures("channelRetained", f.pendingA.id).single())
        }
    }
}
