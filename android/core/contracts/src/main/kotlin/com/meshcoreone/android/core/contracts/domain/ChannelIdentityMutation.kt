// AndroidOnly: WP-201 Process-owned channel identity exclusion shared by persistence and radio service producers.
package com.meshcoreone.android.core.contracts.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

interface ChannelIdentityMutation {
    suspend fun <T> withChannelOperation(block: suspend () -> T): T
    suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T
    suspend fun <T> withChannelIdentityRestore(block: suspend () -> T): T
    suspend fun <T> withChannelOperationScope(block: suspend (ChannelIdentityWriteScope) -> T): T =
        throw UnsupportedOperationException("Owned channel mutation scopes are not implemented by this gate")
    suspend fun requireChannelOperationScope(scope: ChannelIdentityWriteScope): Unit =
        throw UnsupportedOperationException("Owned channel mutation scopes cannot be validated by this gate")
}

interface ChannelIdentityWriteScope {
    suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T
}

/**
 * Lock order is operation then identity. Ordinary repository writes and restore own both.
 * Radio producers use an explicit operation scope whose repository writes acquire identity only.
 * Never invoke an ordinary gated repository writer inside either owned scope.
 */
class ChannelIdentityMutex : ChannelIdentityMutation {
    private val operation = Mutex()
    private val identity = Mutex()

    override suspend fun <T> withChannelOperation(block: suspend () -> T): T =
        operationLock(block)

    override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T =
        identity.withLock(currentJob()) { block() }

    override suspend fun <T> withChannelIdentityRestore(block: suspend () -> T): T =
        operationLock { identity.withLock(currentJob()) { block() } }

    override suspend fun <T> withChannelOperationScope(block: suspend (ChannelIdentityWriteScope) -> T): T =
        operationLock {
            val owner = currentJob()
            val scope = OwnedIdentityScope(this, identity, owner)
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

    private suspend fun <T> operationLock(block: suspend () -> T): T =
        operation.withLock(currentJob()) { block() }

    private suspend fun currentJob(): Job =
        checkNotNull(currentCoroutineContext()[Job]) { "Channel operation requires an owned Job" }

    companion object {
        val PROCESS = ChannelIdentityMutex()
    }
}

private class OwnedIdentityScope(
    private val gate: ChannelIdentityMutation,
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

    suspend fun requireOwner(expectedGate: ChannelIdentityMutation) {
        check(active && gate === expectedGate && currentCoroutineContext()[Job] === owner) {
            "Channel mutation scope is expired or belongs to a different gate or Job"
        }
    }
}
