// AndroidOnly: WP-303 The runtime service factory: one graph per connection generation, built only after its prerequisites exist.
// AndroidOnly: WP-311 Observable registry publication retires mounted contact interactions on quiet graph replacement.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.runtime.FactoryOwnership
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.RuntimeServiceFactory
import com.meshcoreone.android.core.runtime.RuntimeServiceInputs
import com.meshcoreone.android.core.runtime.RuntimeServices
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Creates a [RadioSessionContainer] for each generation the runtime connection manager starts. The runtime calls it
 * only after the transport is open, the radio identity is resolved and the device row is built, so no service can
 * exist before a session, a verified radio id and a generation token. It publishes the live container through
 * [SessionRegistry] and withdraws it again when that container is torn down.
 */
class RadioSessionContainerFactory(
    private val environment: SessionEnvironment,
    private val registry: SessionRegistry,
) : RuntimeServiceFactory {
    // Screen-owned history can retain an old service after the connection graph is torn down.
    private val reactionPersistenceMutex = Mutex()

    override suspend fun create(inputs: RuntimeServiceInputs, ownership: FactoryOwnership): RuntimeServices {
        val container = RadioSessionContainer.create(
            inputs, ownership,
            environment.withLifecycle(registry),
            reactionPersistenceMutex,
        )
        registry.publish(container)
        return container
    }

    private fun SessionEnvironment.withLifecycle(registry: SessionRegistry): SessionEnvironment {
        val downstream = onSessionLifecycle
        return SessionEnvironment(
            store, processScope, notificationDelivery, notificationPreferences, notificationStrings, appState, passwords,
            contactPreferences, runtimeState, resyncFailure, chatSendQueueProgress, syncClock, sessionClock, logSink, messagingReporter,
            object : SessionLifecycleListener {
                override fun created(container: RadioSessionContainer) = downstream.created(container)
                override fun tornDown(container: RadioSessionContainer) {
                    registry.withdraw(container)
                    downstream.tornDown(container)
                }
            },
            bootstrapDebugLog,
            messageServiceConfig = messageServiceConfig,
            chatSendQueueConfig = chatSendQueueConfig,
            onChannelQueryAccounting = onChannelQueryAccounting,
        )
    }
}

/**
 * The single place the live container is recorded. At most one container is live; publishing a new generation while
 * a previous one is still registered keeps the previous one's teardown responsible for withdrawing itself, so a
 * late teardown can never clear its successor.
 */
class SessionRegistry {
    private val lock = Any()
    private val live = MutableStateFlow<RadioSessionContainer?>(null)
    private var createdCount = 0
    private var tornDownCount = 0

    val current: RadioSessionContainer? get() = synchronized(lock) { live.value }
    val currentSessions: StateFlow<RadioSessionContainer?> = live.asStateFlow()

    /** Containers created since process start (leak accounting). */
    val created: Int get() = synchronized(lock) { createdCount }
    val tornDown: Int get() = synchronized(lock) { tornDownCount }

    /** Containers created and not yet torn down. */
    val outstanding: Int get() = synchronized(lock) { createdCount - tornDownCount }

    fun publish(container: RadioSessionContainer) = synchronized(lock) {
        createdCount += 1
        // Teardown can finish while the factory's created callback is still running.
        // Serialize this monotonic retirement check with withdraw so it cannot be undone by late publication.
        if (container.isTornDown) throw ConnectionError.FactoryOwnershipViolation()
        live.value = container
    }

    fun withdraw(container: RadioSessionContainer) = synchronized(lock) {
        tornDownCount += 1
        if (live.value === container) live.value = null
    }
}
