// AndroidOnly: WP-317 Bind every PairingFlow start to its actual fallback-discovery lifetime.
package com.meshcoreone.android.app.state

import kotlinx.coroutines.Job

interface PairingDiscoveryLifecycle {
    fun begin(discoveryJob: Job, onSelectionCommitted: () -> Unit): Long?
    fun end(generation: Long)
}
