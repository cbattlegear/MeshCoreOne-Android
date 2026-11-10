// AndroidOnly: WP-201 Process gate ordering and exact callback scope ownership controls, not Room or radio proof.
package com.meshcoreone.android.core.contracts.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelIdentityMutationTest {
    @Test
    fun sameJobScopedWritesRetainOperationThenIdentityOrder() = runBlocking {
        val gate = ChannelIdentityMutex()
        val order = mutableListOf<String>()
        gate.withChannelOperationScope { scope ->
            gate.requireChannelOperationScope(scope)
            order += "operation"
            scope.withChannelIdentityMutation { order += "identity-room" }
            gate.requireChannelOperationScope(scope)
            order += "operation-end"
        }
        gate.withChannelIdentityMutation { order += "ordinary-room" }
        gate.withChannelIdentityRestore { order += "restore-room" }
        assertEquals(listOf("operation", "identity-room", "operation-end", "ordinary-room", "restore-room"), order)
        assertSame(ChannelIdentityMutex.PROCESS, ChannelIdentityMutex.PROCESS)
    }

    @Test
    fun ordinaryWritesAndRestoreWaitForOperationAcrossSuspension() = runBlocking {
        val gate = ChannelIdentityMutex()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelOperation {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var ordinaryRan = false
        var restoreRan = false
        val ordinary = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelIdentityMutation { ordinaryRan = true }
        }
        val restore = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelIdentityRestore { restoreRan = true }
        }
        assertFalse(ordinaryRan)
        assertFalse(restoreRan)
        release.complete(Unit)
        operation.join()
        ordinary.join()
        restore.join()
        assertTrue(ordinaryRan)
        assertTrue(restoreRan)
    }

    @Test
    fun callerImplementedAndWrongGateScopesAreRejected() = runBlocking {
        val gate = ChannelIdentityMutex()
        val foreign = object : ChannelIdentityWriteScope {
            override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T = block()
        }
        assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(foreign) }
        gate.withChannelOperationScope { scope ->
            assertFailsWith<IllegalStateException> {
                ChannelIdentityMutex().requireChannelOperationScope(scope)
            }
        }
    }

    @Test
    fun childJobAndNonCancellableCannotBorrowScope() = runBlocking {
        val gate = ChannelIdentityMutex()
        gate.withChannelOperationScope { scope ->
            async {
                assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(scope) }
                assertFailsWith<IllegalStateException> {
                    scope.withChannelIdentityMutation { throw AssertionError("Foreign child entered") }
                }
            }.await()
            withContext(NonCancellable) {
                assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(scope) }
                assertFailsWith<IllegalStateException> {
                    scope.withChannelIdentityMutation { throw AssertionError("Foreign context entered") }
                }
            }
            gate.requireChannelOperationScope(scope)
        }
    }

    @Test
    fun escapedScopeExpiresOnNormalReturnAndFailure() = runBlocking {
        val gate = ChannelIdentityMutex()
        lateinit var escaped: ChannelIdentityWriteScope
        gate.withChannelOperationScope { escaped = it }
        assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(escaped) }
        assertFailsWith<IllegalStateException> {
            escaped.withChannelIdentityMutation { throw AssertionError("Expired scope entered") }
        }
        val failure = IllegalArgumentException("original")
        val caught = assertFailsWith<IllegalArgumentException> {
            gate.withChannelOperationScope {
                escaped = it
                throw failure
            }
        }
        assertSame(failure, caught)
        assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(escaped) }
        gate.withChannelIdentityMutation { }
    }

    @Test
    fun recursionFailsInsteadOfDeadlockingOrReacquiringOrdinaryLane() = runBlocking {
        val gate = ChannelIdentityMutex()
        gate.withChannelOperationScope { scope ->
            assertFailsWith<IllegalStateException> { gate.withChannelOperation { } }
            assertFailsWith<IllegalStateException> { gate.withChannelIdentityMutation { } }
            assertFailsWith<IllegalStateException> { gate.withChannelIdentityRestore { } }
            scope.withChannelIdentityMutation {
                assertFailsWith<IllegalStateException> { scope.withChannelIdentityMutation { } }
                assertFailsWith<IllegalStateException> { gate.withChannelOperation { } }
            }
        }
    }

    @Test
    fun cancelledOwnerCannotUseScopeAndCancellationReleasesGate() = runBlocking {
        val gate = ChannelIdentityMutex()
        val entered = CompletableDeferred<Unit>()
        lateinit var escaped: ChannelIdentityWriteScope
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelOperationScope { scope ->
                escaped = scope
                entered.complete(Unit)
                coroutineContext[Job]!!.cancel()
                assertFailsWith<CancellationException> { gate.requireChannelOperationScope(scope) }
                assertFailsWith<CancellationException> { scope.withChannelIdentityMutation { error("entered") } }
            }
        }
        entered.await()
        operation.join()
        assertTrue(operation.isCancelled)
        assertFailsWith<IllegalStateException> { gate.requireChannelOperationScope(escaped) }
        gate.withChannelIdentityMutation { }
    }

    @Test
    fun cancelledWaiterDoesNotRetainEitherLane() = runBlocking {
        val gate = ChannelIdentityMutex()
        val release = CompletableDeferred<Unit>()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelOperation { release.await() }
        }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelIdentityMutation { error("cancelled waiter entered") }
        }
        waiter.cancelAndJoin()
        release.complete(Unit)
        operation.join()
        gate.withChannelIdentityRestore { }
    }
}
