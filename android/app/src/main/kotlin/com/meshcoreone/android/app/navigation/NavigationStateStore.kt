// AndroidOnly: WP-302 Separate the navigation commit from observable publication across lifecycle boundaries.
package com.meshcoreone.android.app.navigation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
    )

    // Flow publication does not prove that a UI frame rendered this revision.
    data class PublishedSnapshot internal constructor(
        val revision: Long,
        val value: NavigationState,
        internal val owner: Any,
        internal val activeEntryEpoch: Long,
    )

    private val owner = Any()
    private val current = AtomicReference(Snapshot(0, initial, emptyList()))
    private val published = MutableStateFlow(current.get())
    val publishedSnapshots: StateFlow<PublishedSnapshot> = object : StateFlow<PublishedSnapshot> {
        override val value: PublishedSnapshot
            get() = published.value.let { PublishedSnapshot(it.revision, it.value, owner, it.activeEntryEpoch) }
        override val replayCache: List<PublishedSnapshot> get() = listOf(value)

        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<PublishedSnapshot>): Nothing =
            published.collect(object : FlowCollector<Snapshot> {
                override suspend fun emit(value: Snapshot) {
                    collector.emit(PublishedSnapshot(value.revision, value.value, owner, value.activeEntryEpoch))
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
    ): Boolean {
        // A guarded radio switch and its first binding each advance the navigation generation.
        val cleanupGeneration = if (markGuardedRadioCleanup) expected.value.generation else {
            expected.guardedRadioCleanupGeneration?.takeIf {
                it < Long.MAX_VALUE && (next.generation == it + 1 ||
                    it < Long.MAX_VALUE - 1 && next.generation == it + 2)
            }
        }
        val updated = if (!forceRevision && expected.value == next && expected.handoffs == handoffs &&
            expected.guardedRadioCleanupGeneration == cleanupGeneration
        ) expected else Snapshot(
            revision = Math.incrementExact(expected.revision),
            value = next,
            // Later commits must not expose a guarded commit before its lifecycle boundary exits.
            pending = expected.pending.filterNot { it.released.get() } + listOfNotNull(deferred),
            activeEntryEpoch = if (
                expected.value.selectedTab == next.selectedTab &&
                expected.value.activeStack.last().id == next.activeStack.last().id
            ) expected.activeEntryEpoch else Math.incrementExact(expected.activeEntryEpoch),
            guardedRadioCleanupGeneration = cleanupGeneration,
            handoffs = handoffs,
        )
        if (!current.compareAndSet(expected, updated)) return false
        if (deferred == null) publish()
        return true
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
}
