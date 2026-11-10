// AndroidOnly: WP-302 Separate the navigation commit from observable publication across lifecycle boundaries.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.model.snapshotMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal data class NavigationEntryIdentity(
    private val owner: Any, val entryId: Long, val birthRevision: Long,
)

internal class NavigationStateStore(initial: NavigationState) {
    class Publication {
        internal val released = AtomicBoolean(false)
    }

    data class Snapshot internal constructor(
        val revision: Long,
        val value: NavigationState,
        internal val pending: List<Publication>,
        internal val activeEntryEpoch: Long = 0,
        internal val guardedRadioCleanupGeneration: Long? = null,
        internal val handoffs: ChatContactHandoffs = ChatContactHandoffs(),
        internal val hostAttached: Boolean = false,
        internal val entryBirthRevisions: Map<Long, Long> = emptyMap(),
    )

    // Flow publication does not prove that a UI frame rendered this revision.
    data class PublishedSnapshot internal constructor(
        val revision: Long,
        val value: NavigationState,
        internal val owner: Any,
        internal val activeEntryEpoch: Long,
        private val entryBirthRevisions: Map<Long, Long>,
    ) {
        internal fun entryIdentity(entryId: Long): NavigationEntryIdentity? =
            entryBirthRevisions[entryId]?.let { NavigationEntryIdentity(owner, entryId, it) }
    }

    private val owner = Any()
    private val current = AtomicReference(
        Snapshot(0, initial, emptyList(), entryBirthRevisions = entryBirthRevisions(initial, emptyMap(), 0)),
    )
    private val published = MutableStateFlow(current.get())
    val publishedSnapshots: StateFlow<PublishedSnapshot> = object : StateFlow<PublishedSnapshot> {
        override val value: PublishedSnapshot
            get() = published.value.let {
                PublishedSnapshot(it.revision, it.value, owner, it.activeEntryEpoch, it.entryBirthRevisions)
            }
        override val replayCache: List<PublishedSnapshot> get() = listOf(value)

        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<PublishedSnapshot>): Nothing =
            published.collect(object : FlowCollector<Snapshot> {
                override suspend fun emit(value: Snapshot) {
                    collector.emit(
                        PublishedSnapshot(
                            value.revision, value.value, owner, value.activeEntryEpoch, value.entryBirthRevisions,
                        ),
                    )
                }
            })
    }
    val state: StateFlow<NavigationState> = object : StateFlow<NavigationState> {
        override val value: NavigationState get() = published.value.value
        override val replayCache: List<NavigationState> get() = listOf(value)

        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<NavigationState>): Nothing {
            var first = true
            var previous: NavigationState? = null
            return published.collect(object : FlowCollector<Snapshot> {
                override suspend fun emit(value: Snapshot) {
                    if (first || previous != value.value) {
                        first = false
                        previous = value.value
                        collector.emit(value.value)
                    }
                }
            })
        }
    }
    val value: NavigationState get() = current.get().value

    fun snapshot(): Snapshot = current.get()
    fun owns(snapshot: PublishedSnapshot): Boolean = snapshot.owner === owner
    fun publishedHandoffs(): ChatContactHandoffs = published.value.handoffs

    fun compareAndSet(
        expected: Snapshot,
        next: NavigationState,
        deferred: Publication? = null,
        forceRevision: Boolean = false,
        handoffs: ChatContactHandoffs = expected.handoffs,
        markGuardedRadioCleanup: Boolean = false,
        markHostAttached: Boolean = false,
    ): Boolean {
        // A guarded radio switch and its first binding each advance the navigation generation.
        val cleanupGeneration = if (markGuardedRadioCleanup) expected.value.generation else {
            expected.guardedRadioCleanupGeneration?.takeIf {
                it < Long.MAX_VALUE && (next.generation == it + 1 ||
                    it < Long.MAX_VALUE - 1 && next.generation == it + 2)
            }
        }
        val hostAttached = expected.hostAttached || markHostAttached
        val updated = if (!forceRevision && expected.value == next && expected.handoffs == handoffs &&
            expected.guardedRadioCleanupGeneration == cleanupGeneration
        ) {
            if (hostAttached == expected.hostAttached) expected else expected.copy(hostAttached = hostAttached)
        } else {
            val revision = Math.incrementExact(expected.revision)
            Snapshot(
                revision = revision,
                value = next,
                // Later commits must not expose a guarded commit before its lifecycle boundary exits.
                pending = expected.pending.filterNot { it.released.get() } + listOfNotNull(deferred),
                activeEntryEpoch = if (
                    expected.value.selectedTab == next.selectedTab &&
                    expected.value.activeStack.last().id == next.activeStack.last().id
                ) expected.activeEntryEpoch else Math.incrementExact(expected.activeEntryEpoch),
                guardedRadioCleanupGeneration = cleanupGeneration,
                handoffs = handoffs,
                hostAttached = hostAttached,
                entryBirthRevisions = if (expected.value.stacks === next.stacks) expected.entryBirthRevisions
                    else entryBirthRevisions(next, expected.entryBirthRevisions, revision),
            )
        }
        if (!current.compareAndSet(expected, updated)) return false
        if (deferred == null) publish()
        return true
    }

    fun attachHostOnce(transform: (NavigationState) -> NavigationState) {
        while (true) {
            val previous = snapshot()
            if (previous.hostAttached) return
            if (compareAndSet(previous, transform(previous.value), markHostAttached = true)) return
        }
    }

    fun release(publication: Publication) {
        check(publication.released.compareAndSet(false, true)) { "Navigation publication already released" }
        publish()
    }

    fun update(transform: (NavigationState) -> NavigationState) {
        while (true) {
            val previous = snapshot()
            if (compareAndSet(previous, transform(previous.value))) return
        }
    }

    private fun publish() {
        published.update { previous ->
            val latest = snapshot()
            if (latest.revision > previous.revision && latest.pending.all { it.released.get() }) latest else previous
        }
    }

    private fun entryBirthRevisions(
        state: NavigationState, previous: Map<Long, Long>, revision: Long,
    ): Map<Long, Long> = state.stacks.values.flatten().associate {
        it.id to (previous[it.id] ?: revision)
    }.snapshotMap()
}
