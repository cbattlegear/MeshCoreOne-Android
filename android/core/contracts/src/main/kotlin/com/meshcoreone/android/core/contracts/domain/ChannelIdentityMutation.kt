// AndroidOnly: WP-201 Process-owned channel identity exclusion shared by persistence and radio service producers.
package com.meshcoreone.android.core.contracts.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ChannelIdentityMutation {
    suspend fun <T> withChannelOperation(block: suspend () -> T): T
    suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T
    suspend fun <T> withChannelIdentityRestore(block: suspend () -> T): T
}

/**
 * Lock order is operation then identity. Repository writers acquire identity only; radio operations
 * own operation across protocol work and persistence. Restore owns both around its entire transaction.
 * These locks are deliberately non-reentrant. Never invoke a gated repository writer inside restore.
 */
class ChannelIdentityMutex : ChannelIdentityMutation {
    private val operation = Mutex()
    private val identity = Mutex()

    override suspend fun <T> withChannelOperation(block: suspend () -> T): T =
        operation.withLock { block() }

    override suspend fun <T> withChannelIdentityMutation(block: suspend () -> T): T =
        identity.withLock { block() }

    override suspend fun <T> withChannelIdentityRestore(block: suspend () -> T): T =
        operation.withLock { identity.withLock { block() } }

    companion object {
        val PROCESS = ChannelIdentityMutex()
    }
}
