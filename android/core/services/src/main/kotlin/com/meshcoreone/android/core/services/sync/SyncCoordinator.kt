// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lock-confined mutable state of one [SyncCoordinator] (the Swift actor's stored properties). */
internal class SyncCoordinatorState {
    /** Guard against concurrent sync execution; checked and set under the lock without suspending. */
    var isSyncInProgress = false

    /** Bumped on every claim and on disconnect; a release only clears the claim it still owns. */
    var syncClaimGeneration = 0L

    /** True while an advert delta sync holds [isSyncInProgress]; full sync waits instead of skipping. */
    var advertContactSyncActive = false

    /** True while a user-initiated contact refresh holds the radio pipeline. */
    var manualContactSyncActive = false

    /** Waiters held by `waitForAdvertContactSync` until the advert claim clears, by waiter id. */
    val advertSyncWaiters = LinkedHashMap<Long, CompletableDeferred<Unit>>()
    var nextAdvertSyncWaiterID = 0L

    /** Optional override for the advert claim wait bound (test hook). */
    var advertContactSyncWaitTimeoutOverride: Duration? = null

    /** Radio whose full contact fetch (`since == null`) last completed. */
    var fullContactSyncCompletedRadioID: RadioId? = null

    /** Radio that already spent its one invalid-watermark recovery full fetch. */
    var invalidWatermarkRecoveryRadioID: RadioId? = null

    /** Radio for which the exhausted-recovery notice was already logged once. */
    var invalidWatermarkRecoveryExhaustedLoggedRadioID: RadioId? = null

    /** Blocked names (contacts + channel senders) keyed by canonical form, mapped to the first original. */
    var blockedNames: Map<String, String> = emptyMap()

    /** Channel indices received for slots with no local channel in this connection session. */
    var unresolvedChannelIndices: Set<UByte> = emptySet()
    var lastUnresolvedChannelSummaryAt: Instant? = null

    var onCleanChannelSync: (suspend (RadioId) -> Unit)? = null
    var onChannelSyncAttempted: (suspend (RadioId) -> Unit)? = null
    var onSyncActivityStarted: (suspend () -> Unit)? = null
    var onSyncActivityEnded: (suspend (Boolean) -> Unit)? = null
    var onPhaseChanged: ((SyncPhase?) -> Unit)? = null

    /** Whether onSyncActivityEnded already ran for the current sync cycle. */
    var hasEndedSyncActivity = true

    var suppressionWatchdogJob: Job? = null
    var discoveryEventsJob: Job? = null

    /** Test seam: replaces `performResync` (Swift `#if DEBUG performResyncOverride`). */
    var performResyncOverride: (suspend (RadioId, SyncDependencies) -> Boolean)? = null
}

/**
 * Coordinates data synchronization between a MeshCore device and the local database.
 *
 * Owns handler wiring (before event monitoring starts), the event-monitoring lifecycle, full sync
 * (contacts, channels, messages) and UI refresh notifications. The Swift actor becomes lock-confined
 * state: every check-and-set happens inside one `synchronized` block that never suspends, and suspension
 * points interleave exactly where the actor's awaits did. Background work (suppression watchdog,
 * discovery monitoring) runs in the injected [scope]; all timing goes through [clock].
 */
class SyncCoordinator(
    internal val scope: CoroutineScope,
    internal val clock: SyncClock = SyncClock.SYSTEM,
    internal val logSink: SyncLogSink = SyncLogSink.NONE,
) {
    @PublishedApi internal val lock = Any()

    @PublishedApi internal val confined = SyncCoordinatorState()

    internal inline fun <T> locked(block: SyncCoordinatorState.() -> T): T = synchronized(lock) { confined.block() }

    internal fun log(level: DebugLogLevel, message: String) = logSink.emit(level, SyncLogSink.CATEGORY, message)

    internal suspend inline fun <T> contain(label: String, fallback: T, block: () -> T): T =
        containing(logSink, SyncLogSink.CATEGORY, label, fallback, block)

    // MARK: - Observable state (Swift @MainActor properties)

    private val stateValue = MutableStateFlow<SyncState>(SyncState.Idle)
    private val contactsVersionValue = MutableStateFlow(0L)
    private val conversationsVersionValue = MutableStateFlow(0L)
    private val lastSyncDateValue = MutableStateFlow<Instant?>(null)

    /** Current sync state. */
    val state: SyncState get() = stateValue.value
    val stateFlow: StateFlow<SyncState> = stateValue.asStateFlow()

    /** Incremented when contacts data changes. */
    val contactsVersion: Long get() = contactsVersionValue.value

    /** Incremented when conversations data changes. */
    val conversationsVersion: Long get() = conversationsVersionValue.value

    /** Last successful sync date. */
    val lastSyncDate: Instant? get() = lastSyncDateValue.value

    /** Multicast broadcaster for data-change and incoming-message events. */
    val dataEventBroadcaster = SyncDataEventBroadcaster()

    /** Number of advert-claim waiters currently parked (test visibility). */
    internal val advertSyncWaiterCount: Int get() = locked { advertSyncWaiters.size }

    /** Whether a sync claim is held (test visibility). */
    internal val isSyncInProgress: Boolean get() = locked { isSyncInProgress }

    // MARK: - Callbacks

    /** Installs the clean-channel-sync callback (`ConnectionManager.wireCleanChannelSyncCallback`). */
    fun setCleanChannelSyncCallback(callback: suspend (RadioId) -> Unit) = locked { onCleanChannelSync = callback }

    /** Installs the channel-sync-attempted callback. */
    fun setChannelSyncAttemptedCallback(callback: suspend (RadioId) -> Unit) = locked { onChannelSyncAttempted = callback }

    /** Sets callbacks for sync activity tracking (UI pill); only contacts and channels phases use them. */
    fun setSyncActivityCallbacks(
        onStarted: suspend () -> Unit,
        onEnded: suspend (succeeded: Boolean) -> Unit,
        onPhaseChanged: (SyncPhase?) -> Unit,
    ) = locked {
        onSyncActivityStarted = onStarted
        onSyncActivityEnded = onEnded
        this.onPhaseChanged = onPhaseChanged
    }

    /** Test seam for `performResync`. */
    internal fun setPerformResyncOverride(override: suspend (RadioId, SyncDependencies) -> Boolean) =
        locked { performResyncOverride = override }

    // MARK: - State setters

    internal fun setState(newState: SyncState) {
        val callback = locked { onPhaseChanged }
        stateValue.value = newState
        val phase = (newState as? SyncState.Syncing)?.progress?.phase
        try {
            callback?.invoke(phase)
        } catch (failure: Exception) {
            log(DebugLogLevel.ERROR, "onPhaseChanged threw: ${failure.syncDescription()}")
        }
    }

    internal fun setLastSyncDate(date: Instant) {
        lastSyncDateValue.value = date
    }

    // MARK: - Data events

    /** A fresh multicast stream of data-change and incoming-message events, registered at call time. */
    fun dataEvents(): Flow<SyncDataEvent> = dataEventBroadcaster.subscribe()

    /** Ends every [dataEvents] subscriber's collection (`ServiceContainer.tearDown`). */
    fun finishDataEvents() = dataEventBroadcaster.finish()

    // MARK: - Sync activity tracking

    internal suspend fun callSyncActivityStarted() {
        val callback = locked { onSyncActivityStarted } ?: return
        contain("onSyncActivityStarted", Unit) { callback() }
    }

    internal suspend fun callSyncActivityEnded(succeeded: Boolean) {
        val callback = locked { onSyncActivityEnded } ?: return
        contain("onSyncActivityEnded", Unit) { callback(succeeded) }
    }

    /** Calls onSyncActivityEnded at most once per sync cycle (guards disconnect-mid-sync double calls). */
    internal suspend fun endSyncActivityOnce(succeeded: Boolean = false) {
        val shouldEnd = locked {
            if (hasEndedSyncActivity) false else {
                hasEndedSyncActivity = true
                true
            }
        }
        if (!shouldEnd) return
        log(DebugLogLevel.INFO, "[Sync] Calling onSyncActivityEnded (succeeded: $succeeded)")
        callSyncActivityEnded(succeeded)
    }

    /** Opens the resync loop's outer activity bracket so the "Syncing" pill stays across retries. */
    suspend fun beginResyncActivity() = callSyncActivityStarted()

    /** Closes the resync loop's outer bracket; only success triggers the "Ready" toast. */
    suspend fun endResyncActivity(succeeded: Boolean) = callSyncActivityEnded(succeeded)

    // MARK: - Notification suppression watchdog

    internal fun startSuppressionWatchdog(notificationService: SyncNotificationServicing) {
        val watchdog = scope.launch(start = CoroutineStart.LAZY) {
            clock.sleep(SUPPRESSION_WATCHDOG_TIMEOUT)
            val isSuppressing = contain("isSuppressingNotifications", false) { notificationService.isSuppressingNotifications() }
            if (!isSuppressing) return@launch
            log(DebugLogLevel.WARNING, "[Sync] Notification suppression watchdog fired after 120s - force clearing")
            contain("setSuppressingNotifications", Unit) { notificationService.setSuppressingNotifications(false) }
        }
        val previous = locked { suppressionWatchdogJob.also { suppressionWatchdogJob = watchdog } }
        previous?.cancel()
        watchdog.start()
    }

    internal fun cancelSuppressionWatchdog() {
        locked { suppressionWatchdogJob.also { suppressionWatchdogJob = null } }?.cancel()
    }

    /** Whether a suppression watchdog is armed (test visibility). */
    internal val hasSuppressionWatchdog: Boolean get() = locked { suppressionWatchdogJob?.isActive == true }

    // MARK: - Notifications

    /** Notify that contacts data changed (triggers UI refresh). */
    fun notifyContactsChanged() {
        val previous = contactsVersionValue.value
        log(DebugLogLevel.INFO, "notifyContactsChanged: version $previous → ${previous + 1}")
        contactsVersionValue.update { it + 1 }
        dataEventBroadcaster.yield(SyncDataEvent.ContactsChanged)
    }

    /** Notify that conversations data changed (triggers UI refresh). */
    fun notifyConversationsChanged() {
        conversationsVersionValue.update { it + 1 }
        dataEventBroadcaster.yield(SyncDataEvent.ConversationsChanged)
    }

    // MARK: - Blocked contacts cache

    /** Refresh the blocked names cache from the data store (contacts + channel senders). */
    suspend fun refreshBlockedContactsCache(radioId: RadioId, dataStore: ContactPersisting) {
        val names = try {
            val blockedContacts = dataStore.fetchBlockedContacts(radioId)
            val blockedSenders = dataStore.fetchBlockedChannelSenders(radioId)
            val result = LinkedHashMap<String, String>()
            (blockedContacts.map { it.name } + blockedSenders.map { it.name })
                .forEach { result.putIfAbsent(SyncSwiftText.canonical(it), it) }
            result
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.ERROR, "Failed to refresh blocked names cache: ${failure.syncDescription()}")
            emptyMap()
        }
        locked { blockedNames = names }
        log(DebugLogLevel.DEBUG, "Refreshed blocked names cache: ${names.size} entries")
    }

    /** Whether a sender name is blocked (Swift `Set<String>` membership, canonical equivalence). */
    fun isBlockedSender(name: String?): Boolean {
        if (name == null) return false
        val key = SyncSwiftText.canonical(name)
        return locked { key in blockedNames }
    }

    /** A snapshot of blocked sender names for synchronous filtering. */
    fun blockedSenderNames(): Set<String> = locked { blockedNames.values.toSet() }

    /**
     * Deletes channel messages from blocked senders still in the store. Runs on every connection; after
     * the first pass the deletes match nothing. Failures are ignored (Swift `try?`).
     */
    internal suspend fun deleteBlockedSenderMessages(radioId: RadioId, dataStore: PersistenceStoreProtocol) {
        val names = locked { blockedNames.values.toList() }
        for (name in names) {
            try {
                dataStore.deleteChannelMessages(name, radioId)
            } catch (failure: Exception) {
                failure.rethrowIfCallerCancelled()
                // Swift try?
            }
        }
    }

    companion object {
        /** Maximum acceptable time in the future for a sender timestamp (5 minutes). */
        const val TIMESTAMP_TOLERANCE_FUTURE_SECONDS = 5.0 * 60

        /** Maximum acceptable time in the past for a sender timestamp (6 months). */
        private const val TIMESTAMP_TOLERANCE_PAST_SECONDS = 6.0 * 30 * 24 * 60 * 60

        /** Watchdog that force-clears notification suppression. */
        val SUPPRESSION_WATCHDOG_TIMEOUT: Duration = 120.seconds

        /** Timestamp window size (seconds) for matching reactions to messages. */
        const val REACTION_TIMESTAMP_WINDOW_SECONDS: UInt = 300u

        /** Minimum interval between unresolved-channel summary log lines. */
        const val UNRESOLVED_CHANNEL_SUMMARY_INTERVAL_SECONDS = 60.0

        /**
         * Corrects invalid timestamps from senders with broken clocks: more than 5 minutes in the future
         * or more than 6 months in the past (relative to [receiveTime]) becomes the receive time. The
         * caller keeps the original for RX-log correlation and dedup.
         */
        fun correctTimestampIfNeeded(timestamp: UInt, receiveTime: Instant): TimestampCorrection {
            val receiveSeconds = receiveTime.secondsSinceEpoch()
            val timestampSeconds = timestamp.toDouble()
            val isTooFarInFuture = timestampSeconds > receiveSeconds + TIMESTAMP_TOLERANCE_FUTURE_SECONDS
            val isTooFarInPast = timestampSeconds < receiveSeconds - TIMESTAMP_TOLERANCE_PAST_SECONDS
            if (isTooFarInFuture || isTooFarInPast) {
                // Check the truncated integer without Double rounding at the UInt32 boundary.
                val truncatedSeconds = receiveTime.epochSecond +
                    if (receiveTime.epochSecond < 0 && receiveTime.nano > 0) 1L else 0L
                if (truncatedSeconds !in 0L..UInt.MAX_VALUE.toLong()) {
                    return TimestampCorrection(timestamp, false, TimestampRangeError(receiveTime))
                }
                return TimestampCorrection(truncatedSeconds.toUInt(), true)
            }
            return TimestampCorrection(timestamp, false)
        }

        /**
         * The persisted sort date for an incoming message: live messages sort by receive time; backlog
         * drained during sync sorts by the drain anchor so the batch lands as one contiguous block.
         */
        fun sortDate(context: DeliveryContext, receiveTime: Instant): Instant = when (context) {
            DeliveryContext.Live -> receiveTime
            is DeliveryContext.InitialSync -> context.anchor
        }
    }
}

/** Swift `Date.timeIntervalSince1970`. */
internal fun Instant.secondsSinceEpoch(): Double = epochSecond.toDouble() + nano / 1_000_000_000.0

/**
 * Swift `UInt32(date.timeIntervalSince1970)`: truncates toward zero. Swift traps outside `0...UInt32.max`;
 * Kotlin clamps to that range instead.
 */
internal fun Instant.uint32Seconds(): UInt {
    val seconds = secondsSinceEpoch()
    return when {
        seconds <= 0.0 -> 0u
        seconds >= UInt.MAX_VALUE.toDouble() -> UInt.MAX_VALUE
        else -> seconds.toLong().toUInt()
    }
}
