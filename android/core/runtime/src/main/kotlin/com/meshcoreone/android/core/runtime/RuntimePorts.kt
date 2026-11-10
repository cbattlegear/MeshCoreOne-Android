// PortedFrom: MC1Services/Sources/MC1Services/ServiceContainer.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: narrow injected process, physical-link and generation-service roles.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.SessionInputs
import com.meshcoreone.android.core.contracts.domain.SessionServices
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.lang.ref.WeakReference
import java.util.UUID

data class PlatformLinkState(
    val connected: Boolean,
    val autoReconnecting: Boolean,
    val bluetoothPoweredOff: Boolean,
    val connectedDeviceId: UUID?,
    val phase: String,
    val systemConnected: Boolean,
)

/** Android CDM/permissions/background hosting belongs to WP-206/303, not this JVM module. */
interface ConnectionPlatform {
    val hasSystemPairingRegistry: Boolean
    val registryActive: Boolean
    suspend fun activate()
    suspend fun foreground(active: Boolean)
    suspend fun state(target: ConnectionTarget): PlatformLinkState
    suspend fun isRegistered(deviceId: UUID): Boolean
    suspend fun targetForDevice(deviceId: UUID): ConnectionTarget?
    suspend fun adoptSystemLink(target: ConnectionTarget): Boolean
    fun classifyFailure(failure: Throwable): LinkFailure?
}

interface ProcessRuntimeMaintenance {
    suspend fun warmUp()
    suspend fun initializeDevicePreferences(device: DeviceDTO)
}

data class LinkCallbacks(
    val onDisconnected: (Throwable?) -> Unit,
    val onAutoReconnecting: (String) -> Unit,
    val onReconnected: () -> Unit,
    val onBondRefreshed: () -> Unit,
)

interface RuntimeLink {
    val transport: MeshTransport
    val type: TransportType
    fun register(callbacks: LinkCallbacks): AutoCloseable
    suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform)
    suspend fun setSessionLive(token: SessionToken?)
    suspend fun recordBondVerification(deviceId: UUID, at: java.time.Instant)
    suspend fun clearBondVerification(deviceId: UUID)
    suspend fun mayRefreshBond(deviceId: UUID): Boolean
}

/** A simulator generation owns a placeholder transport and seeds its real process store without a radio handshake. */
interface RuntimeSimulatorLink : RuntimeLink {
    suspend fun connectSimulator(): DeviceDTO
    suspend fun seedSimulator()
}

/** Creation allocates only; register() installs handlers before start can activate a physical link. */
fun interface RuntimeLinkFactory { fun create(target: ConnectionTarget): RuntimeLink }

sealed interface RuntimeSyncResult {
    data object Usable : RuntimeSyncResult
    data class Failed(val cause: Throwable) : RuntimeSyncResult
}

data class RuntimeServiceCallbacks(
    val cleanChannelSync: suspend () -> Unit,
    val channelSyncAttempted: suspend () -> Unit,
    val beginIdentityImport: suspend (kotlinx.coroutines.Deferred<Unit>) -> Unit,
    val reconcileIdentity: suspend (kotlinx.coroutines.Deferred<Unit>) -> RadioId?,
    val recoverIdentity: suspend (kotlinx.coroutines.Deferred<Unit>) -> Unit,
    val identityImportAdmission: suspend (kotlinx.coroutines.Deferred<Unit>) -> com.meshcoreone.android.core.contracts.domain.ChannelDispatchAdmission,
    val finishIdentityImport: suspend (Boolean) -> Unit,
)

data class RuntimeServiceInputs(
    val connection: SessionInputs<MeshCoreSession>,
    val device: DeviceDTO,
    val callbacks: RuntimeServiceCallbacks,
    val transportType: TransportType,
    val publication: RuntimePublicationAuthority,
)

/** Implemented by real WPs 208-218 at WP-303; no concrete or no-op graph is constructed here. */
interface RuntimeServices : SessionServices {
    /** Finish the admitted channel query's accounting/persistence while this generation's token is still live. */
    suspend fun awaitChannelQueryAccounting()
    /** Graphs with channel reads retain the originating correlation cause while they retire. */
    suspend fun awaitChannelQueryAccounting(failure: MeshCoreException.ConnectionLost?) =
        awaitChannelQueryAccounting()
    suspend fun hydrate()
    suspend fun initialSync(forceFullSync: Boolean): RuntimeSyncResult
    suspend fun remoteDisconnected(): Set<UUID>
    suspend fun reauthenticate(sessionIds: Set<UUID>)
    suspend fun resetSyncState()
    suspend fun ensureListeners()
}

interface RuntimePublishableServices : RuntimeServices {
    val publicationFence: RuntimePublicationFence
    val publicationSession: MeshCoreSession
}

class RuntimePublicationFence private constructor(
    graph: RuntimePublishableServices,
    internal val registry: RuntimePublicationRegistryIdentity,
) {
    private val graph = WeakReference(graph)
    val monitor = Any()
    internal var closed = false
    internal var published = false
    internal var withdrawn = false

    val isClosed: Boolean get() = synchronized(monitor) { closed }

    fun close() = synchronized(monitor) { closed = true }

    internal fun belongsTo(candidate: RuntimePublishableServices): Boolean = graph.get() === candidate

    companion object {
        internal fun create(graph: RuntimePublishableServices, registry: RuntimePublicationRegistryIdentity) =
            RuntimePublicationFence(graph, registry)
    }
}

class RuntimePublicationRegistryIdentity internal constructor()

data class RuntimePublicationCounts(val created: Int, val tornDown: Int) {
    val outstanding: Int get() = created - tornDown
}

enum class RuntimePublicationOutcome {
    PUBLISHED, ALREADY_PUBLISHED, STALE_OWNER, CLOSED_GRAPH, INVALID_TARGET, DIFFERENT_REGISTRY, DISPLACED, WITHDRAWN,
}

enum class RuntimePublicationRetirement {
    NEVER_PUBLISHED, LIVE, DISPLACED, ALREADY_WITHDRAWN, DIFFERENT_REGISTRY,
}

class RuntimePublicationTarget<T : RuntimePublishableServices> private constructor(
    internal val slot: RuntimePublicationSlot<T>,
    internal val graph: T,
    internal val session: MeshCoreSession,
    internal val token: SessionToken,
    internal val fence: RuntimePublicationFence,
) {
    companion object {
        internal fun <T : RuntimePublishableServices> create(
            slot: RuntimePublicationSlot<T>,
            graph: T,
            session: MeshCoreSession,
            token: SessionToken,
            fence: RuntimePublicationFence,
        ) = RuntimePublicationTarget(slot, graph, session, token, fence)
    }
}

/**
 * One process-owned registry cell. The authority is the only publisher; every reader and retirement
 * uses the same monitor so navigation never observes a partially committed graph.
 */
class RuntimePublicationSlot<T : RuntimePublishableServices> {
    internal val monitor = Any()
    val identity = RuntimePublicationRegistryIdentity()
    internal var live: T? = null
    internal var created = 0
    internal var tornDown = 0

    val current: T? get() = synchronized(monitor) { live }
    val counts: RuntimePublicationCounts get() = synchronized(monitor) { RuntimePublicationCounts(created, tornDown) }

    fun <R> withCurrent(action: (T?) -> R): R = synchronized(monitor) { action(live) }

    fun target(graph: T): RuntimePublicationTarget<T> {
        val token = graph.token
        val session = graph.publicationSession
        val fence = graph.publicationFence
        require(fence.belongsTo(graph)) { "Publication fence belongs to another graph" }
        require(fence.registry === identity) { "Publication fence belongs to another registry" }
        return RuntimePublicationTarget.create(this, graph, session, token, fence)
    }

    fun withdraw(graph: T): RuntimePublicationRetirement {
        val fence = graph.publicationFence
        require(fence.belongsTo(graph)) { "Publication fence belongs to another graph" }
        val never = RuntimePublicationRetirement.NEVER_PUBLISHED
        val liveRetirement = RuntimePublicationRetirement.LIVE
        val displaced = RuntimePublicationRetirement.DISPLACED
        val already = RuntimePublicationRetirement.ALREADY_WITHDRAWN
        val different = RuntimePublicationRetirement.DIFFERENT_REGISTRY
        if (fence.registry !== identity) return different
        synchronized(monitor) {
            synchronized(fence.monitor) {
                if (fence.withdrawn) return already
                if (!fence.published) {
                    fence.withdrawn = true
                    return never
                }
                val result = if (live === graph) liveRetirement else displaced
                val nextTornDown = Math.incrementExact(tornDown)
                fence.withdrawn = true
                tornDown = nextTornDown
                if (result == liveRetirement) live = null
                return result
            }
        }
    }
}

class RuntimePublicationAuthority internal constructor(
    internal val manager: ConnectionManager,
    internal val owner: ConnectionManager.RadioGeneration,
    internal val session: MeshCoreSession,
    internal val token: SessionToken,
    internal val ownership: FactoryOwnership,
    internal val registry: RuntimePublicationRegistryIdentity?,
) {
    fun fence(graph: RuntimePublishableServices): RuntimePublicationFence =
        RuntimePublicationFence.create(graph, checkNotNull(registry) { "No publication registry is bound" })

    fun <T : RuntimePublishableServices> publish(target: RuntimePublicationTarget<T>): RuntimePublicationOutcome {
        val graph = target.graph
        if (registry == null || target.slot.identity !== registry || target.fence.registry !== registry ||
            target.session !== session || target.token != token || graph.token != token ||
            graph.publicationSession !== session || graph.publicationFence !== target.fence ||
            !target.fence.belongsTo(graph)
        ) return RuntimePublicationOutcome.INVALID_TARGET
        if (!manager.isPublicationAuthorityCurrent(this)) return RuntimePublicationOutcome.STALE_OWNER
        try {
            ownership.verifyReturned(graph)
        } catch (failure: ConnectionError.FactoryOwnershipViolation) {
            if (!manager.isPublicationAuthorityCurrent(this)) return RuntimePublicationOutcome.STALE_OWNER
            throw failure
        }
        return manager.publishNavigationService(this, target)
    }
}

fun interface RuntimeServiceFactory {
    suspend fun create(inputs: RuntimeServiceInputs, ownership: FactoryOwnership): RuntimeServices
}

data class ConnectionObserver(
    val onServicesAvailable: suspend (SessionToken) -> Unit,
    val onConnectionLost: suspend () -> Unit,
    val onAutoReconnectStarted: suspend () -> Unit,
    val onDeviceSynced: suspend (SessionToken) -> Unit,
    val onAuthenticationFailure: (UUID) -> Unit,
    val onLastDeviceCleared: () -> Unit,
)

sealed interface RuntimeDiagnostic {
    data class OperationSubmitted(val revision: Long) : RuntimeDiagnostic
    data class Failure(val operation: String, val cause: Throwable) : RuntimeDiagnostic
    data class StaleCallback(val operation: String, val generation: Long) : RuntimeDiagnostic
    data class Teardown(val report: com.meshcoreone.android.core.contracts.domain.TeardownReport) : RuntimeDiagnostic
}

fun interface RuntimeIssueReporter { fun report(diagnostic: RuntimeDiagnostic) }
