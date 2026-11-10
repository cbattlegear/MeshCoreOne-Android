// AndroidOnly: WP-501 Real-consumer diagnostic for uncertain channel sends; not source query/park parity.
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
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
class ChannelQueryCorrelationDiagnosticTest {
    @Test fun rawErrorRetainsUncertainAttemptWithoutQueryOrReplayAfterWake() = runTest {
        withChannelQueueFixture(7) { f ->
            f.startDrain()
            f.queue.awaitDrainCompletion()

            val rawFailure = f.failures("channelRetained", f.pendingA.id).single()
            assertOriginalChannelDeviceError2(rawFailure)
            f.assertUncertainAttempt(f.pendingA, expectedAttemptCount = 8)
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(MessageStatus.PENDING, f.messageA.status)
            assertEquals(1, f.queue.queuedEnvelopeCount)
            assertEquals(1, f.sends(3).size)
            assertEquals(listOf(Bytes.of(31, 0)), f.sends(31))
            assertEquals(1, f.channelInfoReadCount)
            assertTrue(f.queries.isEmpty())
            assertTrue(f.queryResults.isEmpty())
            assertTrue(f.failures("channelQuery", f.messageA.id).isEmpty())
            assertTrue(f.failures("park", f.messageA.id).isEmpty())
            assertTrue(f.failures("terminal", f.messageA.id).isEmpty())

            f.queue.transportDidOpen()
            runCurrent()
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
            assertOriginalChannelDeviceError2(rawFailure)
        }
    }
}
