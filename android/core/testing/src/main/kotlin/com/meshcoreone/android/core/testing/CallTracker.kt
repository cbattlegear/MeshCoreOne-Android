// PortedFrom: MeshCore/Tests/MeshCoreTestSupport/CallTracker.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// SPDX-License-Identifier: MIT
package com.meshcoreone.android.core.testing

import java.util.concurrent.atomic.AtomicLong

class CallTracker {
    private val count = AtomicLong()

    val wasCalled: Boolean
        get() = count.get() > 0

    val callCount: Long
        get() = count.get()

    fun markCalled() {
        count.updateAndGet { Math.addExact(it, 1L) }
    }
}
