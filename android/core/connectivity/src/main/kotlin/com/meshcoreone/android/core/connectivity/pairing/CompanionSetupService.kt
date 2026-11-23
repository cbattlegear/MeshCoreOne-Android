// PortedFrom: MC1Services/Sources/MC1Services/Services/AccessorySetupKitService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/AccessorySetupKitServicing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity.pairing

import com.meshcoreone.android.core.connectivity.ConnectivityClock
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.contracts.domain.errors.CompanionSetupFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One CompanionDeviceManager association owned by this app. */
data class CompanionAssociation(
    val associationId: Int,
    val deviceId: UUID,
    val address: String,
    val displayName: String,
) {
    val endpoint: BluetoothEndpoint get() = BluetoothEndpoint(deviceId, address, associationId.takeIf { it >= 0 })
}

sealed class CompanionSetupError(message: String) : Exception(message), SourceServiceFaultCarrier {
    override val sourceServiceFault: CompanionSetupFault
        get() = when (this) {
            is SessionNotActive -> CompanionSetupFault.SessionNotActive
            is SessionInvalidated -> CompanionSetupFault.SessionInvalidated
            is PickerDismissed -> CompanionSetupFault.PickerDismissed
            is PickerRestricted -> CompanionSetupFault.PickerRestricted
            is PickerAlreadyActive -> CompanionSetupFault.PickerAlreadyActive
            is PairingFailed -> CompanionSetupFault.PairingFailed(reason)
            is NoBluetoothIdentifier -> CompanionSetupFault.NoBluetoothIdentifier
            is DiscoveryTimeout -> CompanionSetupFault.DiscoveryTimeout
            is ConnectionFailed -> CompanionSetupFault.ConnectionFailed
            is UserCancelled -> CompanionSetupFault.UserCancelled
        }

    class SessionNotActive : CompanionSetupError("Bluetooth is not ready.")
    class SessionInvalidated : CompanionSetupError("Companion session ended unexpectedly.")
    class PickerDismissed : CompanionSetupError("Device selection was cancelled.")
    class PickerRestricted : CompanionSetupError("Cannot show device picker.")
    class PickerAlreadyActive : CompanionSetupError("Device picker is already showing.")
    class PairingFailed(val reason: String) : CompanionSetupError("Pairing failed: $reason")
    class NoBluetoothIdentifier : CompanionSetupError("Selected device does not support Bluetooth connection.")
    class DiscoveryTimeout : CompanionSetupError("No devices found.")
    class ConnectionFailed : CompanionSetupError("Could not connect to the device.")
    /** Parity with a declined removal confirmation; CompanionDeviceManager itself never asks. */
    class UserCancelled : CompanionSetupError("Removal was cancelled.")
}

interface CompanionSetupDelegate {
    fun companionSetupDidRemoveAccessory(deviceId: UUID)
    fun companionSetupDidFailPairing(deviceId: UUID)
}

/** Test-injection seam over the companion association service (AccessorySetupKitServicing). */
interface CompanionSetupServicing {
    val pairedAccessories: List<CompanionAssociation>
    val isSessionActive: Boolean
    var delegate: CompanionSetupDelegate?
    suspend fun activateSession()
    suspend fun showPicker(): UUID
    suspend fun removeAccessory(accessory: CompanionAssociation)
    suspend fun renameAccessory(accessory: CompanionAssociation)
    fun accessory(deviceId: UUID): CompanionAssociation?
    fun invalidateSession()
}

/** Results of one chooser request, delivered by the platform gateway on any thread. */
interface CompanionAssociationEvents {
    /** The chooser must be launched by the foreground UI host; [requestId] comes back with its result. */
    fun onChooserPending(chooser: Any, requestId: Long)
    fun onAssociationCreated(association: CompanionAssociation)
    /** The advertised name became known after creation (API 31-33 activity result). */
    fun onAssociationNamed(deviceId: UUID, name: String)
    fun onDismissed()
    fun onFailure(error: CompanionSetupError, failedAssociation: CompanionAssociation? = null)
}

/** CompanionDeviceManager boundary. Association never opens GATT and never starts a service. */
interface CompanionDeviceGateway {
    val isSupported: Boolean
    fun associations(): List<CompanionAssociation>
    /** Each request is correlated by id (see [ChooserRequests]); a late result never reaches a newer request. */
    fun associate(events: CompanionAssociationEvents)
    /** Removes the system bond where the platform allows it (API 36+), then the association. */
    suspend fun disassociate(association: CompanionAssociation)
    /** Presence observation binds `CompanionDeviceService` when the radio is near; it is not a connection. */
    fun observePresence(association: CompanionAssociation, observe: Boolean)
}

/** The foreground UI host that can present the system chooser; absent while backgrounded. */
fun interface CompanionChooserHost {
    fun launch(chooser: Any, requestId: Long)
}

/**
 * Durable record of this app's associations (`deviceId -> advertised name`). CDM only stores a
 * display name for self-managed associations, and offers no removal callback before API 36, so the
 * record supplies names and the baseline for detecting Settings removals across process death.
 */
interface AssociationRecordStore {
    fun records(): Map<UUID, String>
    fun remember(deviceId: UUID, name: String)
    fun forget(deviceId: UUID)
}

class InMemoryAssociationRecordStore : AssociationRecordStore {
    private val lock = Any()
    private val records = linkedMapOf<UUID, String>()
    override fun records(): Map<UUID, String> = synchronized(lock) { records.toMap() }
    override fun remember(deviceId: UUID, name: String) { synchronized(lock) { records[deviceId] = name } }
    override fun forget(deviceId: UUID) { synchronized(lock) { records.remove(deviceId) } }
}

/**
 * Companion association session (AccessorySetupKitService). Activation snapshots the app's
 * associations; the picker is the CDM chooser; a cancelled picker whose association still
 * completes is disassociated immediately, mirroring ASK's orphaned-accessory cleanup.
 */
class CompanionSetupService(
    private val gateway: CompanionDeviceGateway,
    private val scope: CoroutineScope,
    private val clock: ConnectivityClock,
    private val diagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
    chooserHost: CompanionChooserHost? = null,
    private val records: AssociationRecordStore = InMemoryAssociationRecordStore(),
) : CompanionSetupServicing {
    private val lock = Any()
    private val associationOperations = Mutex()
    private var paired: List<CompanionAssociation> = emptyList()
    // A provisional callback is not a bond claim until its awaiting caller receives the selection.
    private var protectedAssociations: List<CompanionAssociation> = emptyList()
    private var active = false
    private var picker: CancellableContinuation<UUID>? = null
    private var pickerAdmission: Any? = null
    private var pickerOutcome = "cancelled"
    private var pickerPresentedAt: Duration? = null
    private var host: CompanionChooserHost? = chooserHost

    override var delegate: CompanionSetupDelegate? = null
    override val pairedAccessories: List<CompanionAssociation> get() = synchronized(lock) { paired }
    override val isSessionActive: Boolean get() = synchronized(lock) { active }
    val lastPickerOutcome: String get() = synchronized(lock) { pickerOutcome }

    /** Attach/detach the resumed activity able to launch the chooser IntentSender. */
    fun setChooserHost(value: CompanionChooserHost?) { synchronized(lock) { host = value } }

    override suspend fun activateSession() {
        if (synchronized(lock) { active }) return
        if (!gateway.isSupported) throw CompanionSetupError.SessionNotActive()
        synchronized(lock) { active = true }
        reconcile(gateway.associations(), observeAll = true)
    }

    /**
     * Re-reads associations (foreground, presence `AssociationsChanged`). Associations removed outside
     * the app (Settings) are reported once to the delegate, like ASK `accessoryRemoved`, including
     * removals that happened while the process was dead (diffed against the durable record).
     */
    fun refreshAssociations() {
        if (!synchronized(lock) { active }) return
        reconcile(gateway.associations(), observeAll = false)
    }

    private fun reconcile(current: List<CompanionAssociation>, observeAll: Boolean) {
        val known = records.records()
        val named = current.map { association -> withRecordedName(association, known[association.deviceId]) }
        val (removed, added) = synchronized(lock) {
            val previous = paired.map { it.deviceId }.toSet() + known.keys
            val gone = previous.filter { id -> named.none { it.deviceId == id } }
            val new = named.filter { it.deviceId !in previous }
            paired = named
            protectedAssociations = if (observeAll) named else protectedAssociations.filter { protected ->
                named.any { sameAssociation(it, protected) }
            }
            gone to new
        }
        named.forEach { records.remember(it.deviceId, it.displayName) }
        (if (observeAll) named else added).forEach { observe(it, true) }
        removed.forEach { id ->
            records.forget(id)
            notify("refresh.removed") { delegate?.companionSetupDidRemoveAccessory(id) }
        }
    }

    private fun withRecordedName(association: CompanionAssociation, recorded: String?): CompanionAssociation =
        if (association.displayName == CompanionDiscoveryCriteria.DEFAULT_ACCESSORY_NAME && recorded != null)
            association.copy(displayName = recorded) else association

    override suspend fun showPicker(): UUID {
        val admission = Any()
        synchronized(lock) {
            if (!active) throw CompanionSetupError.SessionNotActive()
            if (pickerAdmission != null) throw CompanionSetupError.PickerAlreadyActive()
            if (host == null) throw CompanionSetupError.PickerRestricted()
            pickerAdmission = admission
        }
        try {
            return associationOperations.withLock {
                synchronized(lock) {
                    if (!active) throw CompanionSetupError.SessionNotActive()
                    if (host == null) throw CompanionSetupError.PickerRestricted()
                }
                lateinit var events: Events
                val selected = suspendCancellableCoroutine<UUID> { pending ->
                    synchronized(lock) {
                        picker = pending
                        pickerOutcome = "presented"
                        pickerPresentedAt = clock.elapsed
                    }
                    events = Events(pending)
                    pending.invokeOnCancellation { handlePickerCancellation(pending, events) }
                    try {
                        gateway.associate(events)
                    } catch (failure: Exception) {
                        diagnostics.report("companion.associate", failure)
                        resumePicker(pending, Result.failure(CompanionSetupError.PickerRestricted()), "pickerRestricted")
                    }
                }
                synchronized(lock) {
                    val association = checkNotNull(events.selectedAssociation)
                    protectedAssociations = protectedAssociations.filterNot { sameAssociation(it, association) } + association
                }
                selected
            }
        } finally {
            synchronized(lock) { if (pickerAdmission === admission) pickerAdmission = null }
        }
    }

    override suspend fun removeAccessory(accessory: CompanionAssociation) {
        associationOperations.withLock {
            if (!synchronized(lock) { active }) throw CompanionSetupError.SessionNotActive()
            observe(accessory, false)
            gateway.disassociate(accessory)
            records.forget(accessory.deviceId)
            val current = gateway.associations()
            val known = records.records()
            synchronized(lock) {
                paired = current.map { withRecordedName(it, known[it.deviceId]) }
                protectedAssociations = protectedAssociations.filter { protected ->
                    current.any { sameAssociation(it, protected) }
                }
            }
        }
    }

    /** CompanionDeviceManager has no system rename surface; callers hide the action. */
    override suspend fun renameAccessory(accessory: CompanionAssociation) {
        if (!synchronized(lock) { active }) throw CompanionSetupError.SessionNotActive()
    }

    override fun accessory(deviceId: UUID): CompanionAssociation? =
        synchronized(lock) { paired.firstOrNull { it.deviceId == deviceId } }

    override fun invalidateSession() {
        val pending = synchronized(lock) {
            active = false
            paired = emptyList()
            protectedAssociations = emptyList()
            picker.also { picker = null }
        }
        pending?.resumeWith(Result.failure(CompanionSetupError.SessionInvalidated()))
    }

    private inner class Events(private val pending: CancellableContinuation<UUID>) : CompanionAssociationEvents {
        /** Set when the awaiting coroutine was cancelled while this chooser request was outstanding. */
        @Volatile var abandoned = false
        var selectedAssociation: CompanionAssociation? = null
            private set
        private val orphanRemovals = mutableSetOf<Pair<Int, UUID>>()

        override fun onChooserPending(chooser: Any, requestId: Long) {
            // The host is read at launch time: the activity may have changed since showPicker started.
            val current = synchronized(lock) { if (abandoned) return else host }
            if (current == null) {
                resumePicker(pending, Result.failure(CompanionSetupError.PickerRestricted()), "pickerRestricted")
                return
            }
            try { current.launch(chooser, requestId) }
            catch (failure: Exception) {
                diagnostics.report("companion.chooser", failure)
                resumePicker(pending, Result.failure(CompanionSetupError.PickerRestricted()), "pickerRestricted")
            }
        }

        override fun onAssociationCreated(association: CompanionAssociation) {
            val current = try { gateway.associations() } catch (failure: Exception) {
                diagnostics.report("companion.associations", failure); listOf(association)
            }
            val accepted = synchronized(lock) {
                if (abandoned) {
                    if (pickerAdmission == null && pickerOutcome == "cancelled") pickerOutcome = "orphanedAfterCancellation"
                    false
                } else {
                    if (picker !== pending) return
                    selectedAssociation = association
                    records.remember(association.deviceId, association.displayName)
                    val known = records.records()
                    val named = current.map { withRecordedName(it, known[it.deviceId]) }
                    paired = if (named.any { sameAssociation(it, association) }) named else named + association
                    observe(association, true)
                    true
                }
            }
            if (!accepted) {
                removeOrphan(association)
                return
            }
            diagnostics.report(CompanionLogFormatter.selectionMessage(association.displayName, association.deviceId, elapsed()), null)
            resumePicker(pending, Result.success(association.deviceId), "selected")
        }

        fun removeOrphan(association: CompanionAssociation) {
            if (!synchronized(lock) { orphanRemovals.add(association.associationId to association.deviceId) }) return
            scope.launch {
                associationOperations.withLock {
                    val claimed = synchronized(lock) {
                        protectedAssociations.any {
                            it.deviceId == association.deviceId || it.address.equals(association.address, ignoreCase = true)
                        }
                    }
                    if (claimed) return@withLock
                    try {
                        observe(association, false)
                        gateway.disassociate(association)
                        records.forget(association.deviceId)
                        synchronized(lock) { paired = gateway.associations() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        diagnostics.report("companion.orphanRemoval", failure)
                    }
                }
            }
        }

        override fun onAssociationNamed(deviceId: UUID, name: String) {
            if (name.isBlank()) return
            synchronized(lock) {
                if (abandoned) return
                records.remember(deviceId, name)
                paired = paired.map { if (it.deviceId == deviceId) it.copy(displayName = name) else it }
            }
        }

        override fun onDismissed() {
            val outcome = synchronized(lock) { pickerOutcome }
            diagnostics.report(
                CompanionLogFormatter.dismissalMessage(outcome, pairedAccessories.size, elapsed(),
                    CompanionDiscoveryCriteria.usesFilteredDiscovery),
                null,
            )
            synchronized(lock) { pickerPresentedAt = null }
            resumePicker(pending, Result.failure(CompanionSetupError.PickerDismissed()), "cancelled")
        }

        override fun onFailure(error: CompanionSetupError, failedAssociation: CompanionAssociation?) {
            if (synchronized(lock) { abandoned || picker !== pending }) return
            if (failedAssociation != null && error is CompanionSetupError.PairingFailed) {
                notify("pairingFailed") { delegate?.companionSetupDidFailPairing(failedAssociation.deviceId) }
                if (accessory(failedAssociation.deviceId) != null) {
                    scope.launch {
                        try { removeAccessory(failedAssociation) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { diagnostics.report("companion.failedRemoval", failure) }
                    }
                }
            }
            val outcome = when (error) {
                is CompanionSetupError.DiscoveryTimeout -> "discoveryTimeout"
                is CompanionSetupError.PickerRestricted -> "pickerRestricted"
                is CompanionSetupError.PairingFailed -> "pairingFailed"
                else -> "connectionFailed"
            }
            resumePicker(pending, Result.failure(error), outcome)
        }
    }

    private fun resumePicker(pending: CancellableContinuation<UUID>, result: Result<UUID>, outcome: String) {
        val owned = synchronized(lock) {
            if (picker !== pending) false
            else {
                picker = null
                pickerOutcome = outcome
                true
            }
        }
        if (owned) pending.resumeWith(result)
    }

    /**
     * The awaiting coroutine was cancelled; CompanionDeviceManager has no programmatic chooser
     * dismissal, so a selection the user still completes is disassociated as an orphan.
     */
    private fun handlePickerCancellation(pending: CancellableContinuation<UUID>, events: Events) {
        val selected = synchronized(lock) {
            events.abandoned = true
            if (picker === pending) {
                picker = null
                pickerOutcome = "cancelled"
            }
            if (events.selectedAssociation != null) pickerOutcome = "cancelledAfterSelection"
            events.selectedAssociation
        }
        selected?.let(events::removeOrphan)
    }

    private fun sameAssociation(a: CompanionAssociation, b: CompanionAssociation): Boolean =
        a.associationId == b.associationId && a.deviceId == b.deviceId

    private fun observe(association: CompanionAssociation, enabled: Boolean) {
        try { gateway.observePresence(association, enabled) }
        catch (failure: Exception) { diagnostics.report("companion.observePresence", failure) }
    }

    private fun elapsed(): Duration? = synchronized(lock) { pickerPresentedAt }?.let { clock.elapsed - it }

    private inline fun notify(operation: String, action: () -> Unit) {
        try { action() } catch (failure: Exception) { diagnostics.report("companion.delegate.$operation", failure) }
    }
}
