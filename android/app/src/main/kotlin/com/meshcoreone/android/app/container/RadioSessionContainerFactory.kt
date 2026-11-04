// AndroidOnly: WP-303 The runtime service factory: one graph per connection generation, built only after its prerequisites exist.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ChannelDispatchAdmission
import com.meshcoreone.android.core.contracts.domain.LifecycleStage
import com.meshcoreone.android.core.runtime.FactoryOwnership
import com.meshcoreone.android.core.runtime.RuntimeServiceFactory
import com.meshcoreone.android.core.runtime.RuntimeServiceInputs
import com.meshcoreone.android.core.runtime.RuntimeServices
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
import kotlinx.coroutines.sync.Mutex

/**
 * Creates a [RadioSessionContainer] for each generation the runtime connection manager starts. The runtime calls it
 * only after the transport is open, the radio identity is resolved and the device row is built, so no service can
 * exist before a session, a verified radio id and a generation token. It publishes the live container through
 * [SessionRegistry] and withdraws it again when that container is torn down.
 */
class RadioSessionContainerFactory(
    private val environment: SessionEnvironment,
    private val registry: SessionRegistry,
    private val channelDispatchFence: ChannelDispatchFence = ChannelDispatchFence.PROCESS,
) : RuntimeServiceFactory {
    // Screen-owned history can retain an old service after the connection graph is torn down.
    private val reactionPersistenceMutex = Mutex()

    override suspend fun create(inputs: RuntimeServiceInputs, ownership: FactoryOwnership): RuntimeServices {
        val token = inputs.connection.token
        val session = inputs.connection.session
        val lease = channelDispatchFence.openSession(token, session, ChannelDispatchAdmission(token, session))
        try {
            ownership.own(LifecycleStage.STOP_SERVICES) {
                channelDispatchFence.retire(lease)
            }
            val container = RadioSessionContainer.create(
                inputs, ownership,
                environment.withLifecycle(registry),
                reactionPersistenceMutex,
                channelDispatchFence,
                lease,
            )
            registry.publish(container)
            return container
        } catch (failure: Exception) {
            channelDispatchFence.retire(lease)
            throw failure
        }
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
    private var live: RadioSessionContainer? = null
    private var createdCount = 0
    private var tornDownCount = 0

    val current: RadioSessionContainer? get() = synchronized(lock) { live }

    /** Containers created since process start (leak accounting). */
    val created: Int get() = synchronized(lock) { createdCount }
    val tornDown: Int get() = synchronized(lock) { tornDownCount }

    /** Containers created and not yet torn down. */
    val outstanding: Int get() = synchronized(lock) { createdCount - tornDownCount }

    fun publish(container: RadioSessionContainer) = synchronized(lock) {
        createdCount += 1
        live = container
    }

    fun withdraw(container: RadioSessionContainer) = synchronized(lock) {
        tornDownCount += 1
        if (live === container) live = null
    }
}
