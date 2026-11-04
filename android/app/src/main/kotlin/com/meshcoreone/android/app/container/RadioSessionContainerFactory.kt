// AndroidOnly: WP-303 The runtime service factory: one graph per connection generation, built only after its prerequisites exist.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.runtime.FactoryOwnership
import com.meshcoreone.android.core.runtime.RuntimePublicationAuthority
import com.meshcoreone.android.core.runtime.RuntimePublicationCounts
import com.meshcoreone.android.core.runtime.RuntimePublicationOutcome
import com.meshcoreone.android.core.runtime.RuntimePublicationRetirement
import com.meshcoreone.android.core.runtime.RuntimePublicationSlot
import com.meshcoreone.android.core.runtime.RuntimeServiceFactory
import com.meshcoreone.android.core.runtime.RuntimeServiceInputs
import com.meshcoreone.android.core.runtime.RuntimeServices
import kotlinx.coroutines.CancellationException
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
) : RuntimeServiceFactory {
    // Screen-owned history can retain an old service after the connection graph is torn down.
    private val reactionPersistenceMutex = Mutex()

    override suspend fun create(inputs: RuntimeServiceInputs, ownership: FactoryOwnership): RuntimeServices {
        val container = RadioSessionContainer.create(
            inputs, ownership,
            environment.withLifecycle(registry),
            reactionPersistenceMutex,
        )
        return when (val outcome = registry.publish(container, inputs.publication)) {
            RuntimePublicationOutcome.PUBLISHED, RuntimePublicationOutcome.ALREADY_PUBLISHED -> container
            RuntimePublicationOutcome.STALE_OWNER -> throw CancellationException("Session publication owner is stale")
            else -> throw IllegalStateException("Session publication rejected: $outcome")
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
    private val slot = RuntimePublicationSlot<RadioSessionContainer>()

    val current: RadioSessionContainer? get() = slot.current
    internal val publicationIdentity get() = slot.identity

    internal fun <T> withNavigationCommitBoundary(action: (RadioSessionContainer?) -> T): T =
        slot.withCurrent(action)

    /** A coherent snapshot of registry publications and their completed withdrawals. */
    val counts: RuntimePublicationCounts get() = slot.counts
    val created: Int get() = counts.created
    val tornDown: Int get() = counts.tornDown

    /** Published containers not yet torn down, including a displaced predecessor. */
    val outstanding: Int get() = counts.outstanding

    internal fun publish(container: RadioSessionContainer, authority: RuntimePublicationAuthority): RuntimePublicationOutcome =
        authority.publish(slot.target(container))

    fun withdraw(container: RadioSessionContainer) {
        check(slot.withdraw(container) != RuntimePublicationRetirement.DIFFERENT_REGISTRY) {
            "Session belongs to another registry"
        }
    }
}
