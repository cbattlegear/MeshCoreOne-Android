// AndroidOnly: WP-214 Inject failures around the actual atomic Room selection boundary.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import java.util.UUID

internal fun ReactionQueuePersisting.aroundSelection(
    around: suspend (ClassifiedReactionWork, ClosedRange<UInt>, suspend () -> ResolvedReactionQueue) -> ResolvedReactionQueue,
): ReactionQueuePersisting {
    val journal = this
    return object : ReactionQueuePersisting by journal {
        override suspend fun resolveReactionTarget(
            expectedVersion: UUID?, version: UUID, occurrence: EntityKey, timestampWindow: ClosedRange<UInt>, limit: Long,
            cachedTarget: () -> ReactionTargetBinding?,
        ): ResolvedReactionQueue {
            val work = journal.fetchReactionQueue().entries.single {
                it.radioId == occurrence.radioId && it.occurrenceID == occurrence.id
            }
            return around(work, timestampWindow) {
                journal.resolveReactionTarget(expectedVersion, version, occurrence, timestampWindow, limit, cachedTarget)
            }
        }
    }
}
