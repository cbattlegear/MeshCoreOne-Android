// AndroidOnly: WP-303 The runtime service factory: one graph per connection generation, built only after its prerequisites exist.
// AndroidOnly: WP-311 Observable registry publication retires mounted contact interactions on quiet graph replacement.
// AndroidOnly: WP-314 Observable registry ownership for the mounted Tools trace binding.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.SessionToken
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
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
                override fun created(container: RadioSessionContainer) {
                    registry.created()
                    downstream.created(container)
                }
                override fun tornDown(container: RadioSessionContainer) {
                    registry.withdraw(container)
                    downstream.tornDown(container)
                }
            },
            bootstrapDebugLog,
            messageServiceConfig = messageServiceConfig,
            chatSendQueueConfig = chatSendQueueConfig,
            onChannelQueryAccounting = onChannelQueryAccounting,
            channelDispatchFence = channelDispatchFence,
            pendingReactionCustody = pendingReactionCustody,
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
    private val changes = MutableStateFlow(0L)
    private val accountingLock = Any()
    private var createdCount = 0
    private var tornDownCount = 0

    val current: RadioSessionContainer? get() = slot.current
    internal val publicationIdentity get() = slot.identity
    val currentSessions: StateFlow<RadioSessionContainer?> = RegistryStateFlow(changes) { slot.current }
    val currentToken: StateFlow<SessionToken?> = RegistryStateFlow(changes) { slot.current?.token }

    internal fun <T> withNavigationCommitBoundary(action: (RadioSessionContainer?) -> T): T =
        slot.withCurrent(action)

    /** A coherent snapshot of registry publications and their completed withdrawals. */
    val counts: RuntimePublicationCounts get() = slot.counts
    val created: Int get() = synchronized(accountingLock) { createdCount }
    val tornDown: Int get() = synchronized(accountingLock) { tornDownCount }
    val outstanding: Int get() = synchronized(accountingLock) { createdCount - tornDownCount }

    internal fun created() {
        synchronized(accountingLock) { createdCount = Math.incrementExact(createdCount) }
    }

    internal fun publish(container: RadioSessionContainer, authority: RuntimePublicationAuthority): RuntimePublicationOutcome {
        val outcome = authority.publish(slot.target(container))
        if (outcome == RuntimePublicationOutcome.PUBLISHED || outcome == RuntimePublicationOutcome.ALREADY_PUBLISHED) {
            changes.update { it + 1 }
        }
        return outcome
    }

    fun withdraw(container: RadioSessionContainer) {
        val result = slot.withdraw(container)
        check(result != RuntimePublicationRetirement.DIFFERENT_REGISTRY) {
            "Session belongs to another registry"
        }
        if (result != RuntimePublicationRetirement.ALREADY_WITHDRAWN) {
            synchronized(accountingLock) { tornDownCount = Math.incrementExact(tornDownCount) }
        }
        if (result == RuntimePublicationRetirement.LIVE) changes.update { it + 1 }
    }
}

@OptIn(InternalCoroutinesApi::class)
private class RegistryStateFlow<T>(
    private val changes: StateFlow<Long>,
    private val readCurrent: () -> T,
) : StateFlow<T> {
    override val value: T get() = readCurrent()
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.map { readCurrent() }.distinctUntilChanged().collect(collector)
        error("Registry state flow completed unexpectedly")
    }
}
