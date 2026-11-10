// AndroidOnly: WP-201 Process-owned channel operation exclusion; not connection, storage or receive-origin authority.
package com.meshcoreone.android.core.contracts.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ChannelIdentityMutation {
    suspend fun <T> withChannelOperation(block: suspend () -> T): T
    suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T
    suspend fun <T> withChannelIdentityRestore(block: suspend () -> T): T
    suspend fun <T> withChannelOperationScope(block: suspend (ChannelIdentityWriteScope) -> T): T
    suspend fun requireChannelOperationScope(scope: ChannelIdentityWriteScope)
}

interface ChannelIdentityWriteScope {
    suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T
}

/**
 * Lock order is operation, identity, then the consumer's Room transaction. Ordinary writers and
 * restore take both locks. An operation-scoped facade takes only identity through its exact scope.
 * Locks are non-reentrant. This process-local gate neither fences raw SQL nor authenticates ingress.
 */
class ChannelIdentityMutex : ChannelIdentityMutation {
    private val operation = Mutex()
    private val identity = Mutex()

    override suspend fun <T> withChannelOperation(block: suspend () -> T): T =
        operationLock(block)

    override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T =
        operationLock {
            val owner = activeJob()
            identity.withLock(owner) {
                check(activeJob() === owner) { "Channel identity Job changed while acquiring the gate" }
                block()
            }
        }

    override suspend fun <T> withChannelIdentityRestore(block: suspend () -> T): T =
        withChannelIdentityMutation(block)

    override suspend fun <T> withChannelOperationScope(block: suspend (ChannelIdentityWriteScope) -> T): T =
        operationLock {
            val scope = OwnedIdentityScope(this, identity, activeJob())
            try {
                block(scope)
            } finally {
                scope.close()
            }
        }

    override suspend fun requireChannelOperationScope(scope: ChannelIdentityWriteScope) {
        check(scope is OwnedIdentityScope) { "Channel operation scope was not minted by this gate" }
        scope.requireOwner(this)
    }

    private suspend fun <T> operationLock(block: suspend () -> T): T {
        val owner = activeJob()
        return operation.withLock(owner) {
            check(activeJob() === owner) { "Channel operation Job changed while acquiring the gate" }
            block()
        }
    }

    companion object {
        val PROCESS = ChannelIdentityMutex()
    }
}

private suspend fun activeJob(): Job {
    val context = currentCoroutineContext()
    context.ensureActive()
    return checkNotNull(context[Job]) { "Channel operation requires an owned Job" }.also {
        check(it !== NonCancellable) { "NonCancellable cannot own channel authority" }
    }
}

private class OwnedIdentityScope(
    private val gate: ChannelIdentityMutex,
    private val identity: Mutex,
    private val owner: Job,
) : ChannelIdentityWriteScope {
    @Volatile private var active = true

    override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T {
        requireOwner(gate)
        return identity.withLock(owner) {
            requireOwner(gate)
            block()
        }
    }

    fun close() { active = false }

    suspend fun requireOwner(expectedGate: ChannelIdentityMutex) {
        val current = activeJob()
        check(active && gate === expectedGate && current === owner && owner.isActive) {
            "Channel mutation scope is expired or belongs to a different gate or Job"
        }
    }
}
