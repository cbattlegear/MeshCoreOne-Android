// PortedFrom: MC1Tests/Helpers/TestHelpers.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.testing

import java.util.concurrent.atomic.AtomicReference

class MutableBox<T>(initialValue: T) {
    private val storage = AtomicReference(initialValue)

    var value: T
        get() = storage.get()
        set(value) = storage.set(value)
}
