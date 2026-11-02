// AndroidOnly: WP-302 Separate the navigation commit from observable publication across lifecycle boundaries.
package com.meshcoreone.android.app.navigation

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class NavigationStateStore(initial: NavigationState) {
    private val current = AtomicReference(initial)
    private val published = MutableStateFlow(initial)
    val state = published.asStateFlow()
    val value: NavigationState get() = current.get()

    fun compareAndSet(expected: NavigationState, next: NavigationState, publish: Boolean = true): Boolean {
        while (true) {
            val actual = current.get()
            if (actual != expected) return false
            val updated = if (actual == next) actual else next.copy(
                commitRevision = Math.incrementExact(actual.commitRevision),
            )
            if (current.compareAndSet(actual, updated)) {
                if (publish) publish()
                return true
            }
        }
    }

    fun update(transform: (NavigationState) -> NavigationState) {
        while (true) {
            val previous = value
            if (compareAndSet(previous, transform(previous))) return
        }
    }

    fun publish() {
        published.update { previous ->
            val latest = value
            if (latest.commitRevision > previous.commitRevision) latest else previous
        }
    }
}
