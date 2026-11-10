// AndroidOnly: WP-201 Connection admission accounting, identity and revocation boundary regressions.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.contracts.domain.ChannelDispatchAdmission
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import org.junit.jupiter.api.Test

class ChannelDispatchAdmissionTest {
    private fun token() = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RadioId(UUID.randomUUID()))

    @Test fun tokenValuesAndExactConnectionReferenceMustBothMatch() {
        data class EqualConnection(val value: Int)
        val token = token()
        val connection = EqualConnection(1)
        val admission = ChannelDispatchAdmission(token, connection)
        assertSame(token, admission.token)
        assertSame(connection, admission.connectionIdentity)
        assertNull(admission.tryAcquire(token, EqualConnection(1)))
        for (different in listOf(
            token.copy(epoch = ProcessEpoch(UUID.randomUUID())),
            token.copy(generation = Generation(2)),
            token.copy(radioId = RadioId(UUID.randomUUID())),
        )) assertNull(admission.tryAcquire(different, connection))
        assertEquals(0L, admission.inFlightCount)
        val permit = assertNotNull(admission.tryAcquire(token.copy(), connection))
        assertEquals(1L, admission.inFlightCount)
        permit.complete()
        assertEquals(0L, admission.inFlightCount)
    }

    @Test fun revokeBeforeAcquirePermanentlyRejectsNewAdmission() {
        val token = token()
        val connection = Any()
        val admission = ChannelDispatchAdmission(token, connection)
        admission.revoke()
        repeat(3) {
            admission.revoke()
            assertTrue(admission.isRevoked)
            assertNull(admission.tryAcquire(token, connection))
            assertEquals(0L, admission.inFlightCount)
        }

        @Test fun pauseRejectsNewAdmissionWithoutDiscardingTheAdmittedDrain() {
            val token = token()
            val connection = Any()
            val admission = ChannelDispatchAdmission(token, connection)
            val permit = assertNotNull(admission.tryAcquire(token, connection))
            admission.pause()
            assertTrue(admission.isPaused)
            assertFalse(admission.isRevoked)
            assertEquals(1L, admission.inFlightCount)
            assertNull(admission.tryAcquire(token, connection))
            permit.complete()
            assertEquals(0L, admission.inFlightCount)
            admission.resume()
            assertFalse(admission.isPaused)
            assertNotNull(admission.tryAcquire(token, connection)).complete()
        }

        @Test fun resumeCannotUndoRevocationDuringAnIdentityPause() {
            val token = token()
            val connection = Any()
            val admission = ChannelDispatchAdmission(token, connection)
            admission.pause()
            admission.revoke()
            admission.resume()
            assertTrue(admission.isRevoked)
            assertNull(admission.tryAcquire(token, connection))
            assertEquals(0L, admission.inFlightCount)
        }
    }

    @Test fun revokeRetainsAdmittedAccountingAndCompletionNeverReactivatesIt() {
        val token = token()
        val connection = Any()
        val admission = ChannelDispatchAdmission(token, connection)
        val first = assertNotNull(admission.tryAcquire(token, connection))
        val second = assertNotNull(admission.tryAcquire(token, connection))
        admission.revoke()
        assertTrue(admission.isRevoked)
        assertEquals(2L, admission.inFlightCount)
        first.complete()
        first.complete()
        assertEquals(1L, admission.inFlightCount)
        assertNull(admission.tryAcquire(token, connection))
        second.complete()
        second.complete()
        assertEquals(0L, admission.inFlightCount)
        assertTrue(admission.isRevoked)
        assertNull(admission.tryAcquire(token, connection))
    }

    @Test fun concurrentlyCompletingTheSamePermitDecrementsExactlyOnce() {
        val token = token()
        val connection = Any()
        val admission = ChannelDispatchAdmission(token, connection)
        val permit = assertNotNull(admission.tryAcquire(token, connection))
        val other = assertNotNull(admission.tryAcquire(token, connection))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CyclicBarrier(8)
            val completions = List(8) {
                executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    repeat(10) { permit.complete() }
                }
            }
            completions.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1L, admission.inFlightCount)
            other.complete()
            assertEquals(0L, admission.inFlightCount)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun acquireRacingRevocationIsEitherRejectedOrRemainsAccountedUntilCompletion() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(64) {
                val token = token()
                val connection = Any()
                val admission = ChannelDispatchAdmission(token, connection)
                val start = CyclicBarrier(3)
                val acquired = executor.submit(Callable {
                    start.await(5, TimeUnit.SECONDS)
                    admission.tryAcquire(token, connection)
                })
                val revoked = executor.submit {
                    start.await(5, TimeUnit.SECONDS)
                    admission.revoke()
                }
                start.await(5, TimeUnit.SECONDS)
                val permit = acquired.get(10, TimeUnit.SECONDS)
                revoked.get(10, TimeUnit.SECONDS)
                assertTrue(admission.isRevoked)
                assertNull(admission.tryAcquire(token, connection))
                assertEquals(if (permit == null) 0L else 1L, admission.inFlightCount)
                permit?.complete()
                assertEquals(0L, admission.inFlightCount)
                assertTrue(admission.isRevoked)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun countOverflowFailsClosedWithoutWrappingIntoRevocationOrLosingCount() {
        val token = token()
        val connection = Any()
        val admission = ChannelDispatchAdmission(token, connection)
        val field = ChannelDispatchAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
        val state = field.get(admission) as AtomicLong
        state.set(Long.MAX_VALUE - 1)
        val permit = assertNotNull(admission.tryAcquire(token, connection))
        assertEquals(Long.MAX_VALUE, admission.inFlightCount)
        assertFalse(admission.isRevoked)
        assertNull(admission.tryAcquire(token, connection))
        admission.revoke()
        assertTrue(admission.isRevoked)
        assertEquals(Long.MAX_VALUE, admission.inFlightCount)
        permit.complete()
        assertEquals(Long.MAX_VALUE - 1, admission.inFlightCount)
        assertTrue(admission.isRevoked)
        assertNull(admission.tryAcquire(token, connection))
    }
}
