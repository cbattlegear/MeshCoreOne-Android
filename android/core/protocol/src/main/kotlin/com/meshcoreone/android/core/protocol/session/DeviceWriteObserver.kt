// AndroidOnly: WP-107 Observe command dispatch and terminal replies without retaining command payloads.
package com.meshcoreone.android.core.protocol.session

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class DeviceWriteEvent { ATTEMPTED, ACCEPTED, REJECTED }

class DeviceWriteObserver(val onEvent: (UUID, DeviceWriteEvent) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DeviceWriteObserver>
}
