// PortedFrom: MC1Services/Sources/MC1Services/Services/NotificationService.swift@db14559b39d32322b06477c6ae676112f583db50
// Neutral delivery seam between WP-215 notification policy (core:services) and the WP-401 platform adapter.
package com.meshcoreone.android.core.contracts.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** Swift `NotificationCategory`: which actions a posted notification offers. */
enum class NotificationCategory(val rawValue: String) {
    DIRECT_MESSAGE("DIRECT_MESSAGE"),
    CHANNEL_MESSAGE("CHANNEL_MESSAGE"),
    ROOM_MESSAGE("ROOM_MESSAGE"),
    REACTION("REACTION"),
    LOW_BATTERY("LOW_BATTERY"),
}

/** Swift `NotificationAction`: the identifiers of the actions a category offers. */
enum class NotificationAction(val rawValue: String) {
    REPLY("REPLY_ACTION"),
    MARK_READ("MARK_READ_ACTION"),
    DISMISS("DISMISS_ACTION"),
}

/**
 * Typed replacement for the Swift `userInfo` dictionary. The delivery adapter stores it with the
 * posted notification and hands it back in [DeliveredNotification] and [NotificationResponse].
 * Conversation identifiers carry their radio ([EntityKey]/[RadioId]) because Android rows are
 * partitioned by radio, so an action can never be resolved against another radio's rows.
 */
sealed interface NotificationPayload {
    /** `type: directMessage` with `contactID` and `messageID`. */
    data class DirectMessage(val contact: EntityKey, val messageID: UUID) : NotificationPayload

    /** `type: channelMessage` with `channelIndex`, `radioID` and `messageID`. */
    data class ChannelMessage(val radioId: RadioId, val channelIndex: UByte, val messageID: UUID) : NotificationPayload

    /** `type: roomMessage` with `roomName`, `sessionID` and `messageID`. */
    data class RoomMessage(val roomName: String, val session: EntityKey, val messageID: UUID) : NotificationPayload

    /** `type: newContact` with `contactID`. */
    data class NewContact(val contact: EntityKey) : NotificationPayload

    /** `type: reaction` with `messageID` and either `contactID` or `channelIndex` plus `radioID`. */
    data class Reaction(
        val messageID: UUID,
        val contact: EntityKey?,
        val channelIndex: UByte?,
        val radioId: RadioId?,
    ) : NotificationPayload

    /** `type: lowBattery` with `batteryPercentage`. */
    data class LowBattery(val batteryPercentage: Long) : NotificationPayload

    /** `type: quickReplyFailed` with `contactID`. */
    data class QuickReplyFailed(val contact: EntityKey) : NotificationPayload

    /** `type: channelQuickReplyFailed` with `channelIndex` and `radioID`. */
    data class ChannelQuickReplyFailed(val radioId: RadioId, val channelIndex: UByte) : NotificationPayload
}

/**
 * One notification to deliver (Swift `UNNotificationRequest` plus its content). [badge] is the
 * badge number to show with it, or null to leave the badge alone; [threadIdentifier] groups
 * notifications of one conversation.
 */
data class NotificationRequest(
    val id: NotificationId,
    val category: NotificationCategory?,
    val title: String,
    val body: String,
    val soundEnabled: Boolean,
    val badge: Long?,
    val threadIdentifier: String?,
    val payload: NotificationPayload,
)

// Native adaptation (WP-215): synchronous notification effect admission and retirement fencing.
/**
 * Synchronous final-side-effect admission for one notification wiring generation.
 *
 * A caller may use this to gate one bounded, non-suspending platform mutation. Never hold a lease
 * across a query, debounce, callback or any other suspension.
 */
class NotificationPostPermit private constructor(
    private val parent: NotificationPostPermit?,
) {
    constructor() : this(null)

    private val lock = Any()
    private var active = true
    private var inFlight = 0
    private var quiescent = CompletableFuture.completedFuture(Unit)

    val isActive: Boolean
        get() = parent?.isActive != false && synchronized(lock) { active }

    fun childPermit(): NotificationPostPermit = NotificationPostPermit(this)

    /**
     * Retires this owner and returns a caller-owned future that completes after its admitted effects drain.
     * Await the future outside registry/owner locks before publishing a replacement graph.
     */
    fun close(): CompletableFuture<Unit> {
        val completion = synchronized(lock) {
            active = false
            quiescent
        }
        return completion.thenApply { Unit }
    }

    /**
     * Atomically retires this root permit and removes its current recipient. Publishers must use
     * [compareAndSetRootStateWhileActive] so capture cannot race a late publication.
     */
    fun <T : Any> closeAndCapture(recipient: AtomicReference<T?>): RetirementCapture<T> {
        requireRoot()
        val (captured, completion) = synchronized(lock) {
            active = false
            recipient.getAndSet(null) to quiescent
        }
        return RetirementCapture(captured, completion.thenApply { Unit })
    }

    /**
     * Serializes a publisher CAS with root admission and retirement. This accepts no arbitrary callback.
     */
    fun <T : Any> compareAndSetRootStateWhileActive(
        state: AtomicReference<T?>,
        expected: T?,
        update: T?,
    ): NotificationPostAdmission<Boolean> {
        requireRoot()
        return synchronized(lock) {
            if (!active) NotificationPostAdmission.Revoked
            else NotificationPostAdmission.Admitted(state.compareAndSet(expected, update))
        }
    }

    /** Acquires the root-to-leaf permit chain, or returns null when any owner has retired. */
    fun tryAcquire(): Lease? {
        val chain = permitChain()
        if (!acquire(chain, 0)) return null
        return Lease(chain)
    }

    fun <T> runIfActive(action: () -> T): NotificationPostAdmission<T> {
        val lease = tryAcquire() ?: return NotificationPostAdmission.Revoked
        return try {
            NotificationPostAdmission.Admitted(lease.runSynchronous(action))
        } finally {
            lease.close()
        }
    }

    /**
     * Refuses a blocking self-drain from this permit's active synchronous action extent.
     * Call only immediately before a blocking wait; suspending waits may let the producer release its lease.
     */
    fun requireOutsideOperation() {
        val active = activeLeaseInvocations.get() ?: return
        if (active.any { invocation -> invocation.permits.any { it === this } }) {
            throw NotificationPostSelfDrainException()
        }
    }

    private fun permitChain(): List<NotificationPostPermit> =
        generateSequence(this) { it.parent }.toList().asReversed()

    private fun requireRoot() {
        check(parent == null) { "Root publication transitions require the root permit" }
    }

    private fun acquire(chain: List<NotificationPostPermit>, index: Int): Boolean =
        synchronized(chain[index].lock) {
            val permit = chain[index]
            if (!permit.active) {
                false
            } else if (index == chain.lastIndex) {
                chain.forEach {
                    if (it.inFlight++ == 0) it.quiescent = CompletableFuture()
                }
                true
            } else {
                acquire(chain, index + 1)
            }
        }

    private fun releaseAdmission() {
        val completion = synchronized(lock) {
            check(inFlight > 0) { "Notification permit admission released more than once" }
            inFlight--
            if (inFlight == 0) quiescent else null
        }
        completion?.complete(Unit)
    }

    class Lease internal constructor(
        internal val permits: List<NotificationPostPermit>,
    ) : AutoCloseable {
        private val lock = Any()
        private var closed = false

        /**
         * Tracks this exact lease only while [action] runs synchronously. The caller still owns [close],
         * which remains safe from another thread and must run outside any owner lock.
         */
        fun <T> runSynchronous(action: () -> T): T {
            synchronized(lock) {
                check(!closed) { "Cannot run an action with a closed notification permit lease" }
                retainAdmission(permits)
            }
            val invocation = LeaseInvocation(permits)
            val stack = activeLeaseInvocations.get()
                ?: ArrayList<LeaseInvocation>().also { activeLeaseInvocations.set(it) }
            stack += invocation
            try {
                return action()
            } finally {
                try {
                    val removed = stack.removeAt(stack.lastIndex)
                    check(removed === invocation) { "Notification permit invocation stack was corrupted" }
                    if (stack.isEmpty()) activeLeaseInvocations.remove()
                } finally {
                    releaseAdmissions(permits)
                }
            }
        }

        override fun close() {
            val shouldRelease = synchronized(lock) {
                if (closed) false else {
                    closed = true
                    true
                }
            }
            if (shouldRelease) releaseAdmissions(permits)
        }
    }

    private class LeaseInvocation(val permits: List<NotificationPostPermit>)

    private companion object {
        val activeLeaseInvocations = ThreadLocal<MutableList<LeaseInvocation>>()

        fun retainAdmission(permits: List<NotificationPostPermit>) {
            permits.forEach { permit ->
                synchronized(permit.lock) {
                    if (permit.inFlight++ == 0) permit.quiescent = CompletableFuture()
                }
            }
        }

        fun releaseAdmissions(permits: List<NotificationPostPermit>) {
            permits.asReversed().forEach { it.releaseAdmission() }
        }
    }

    data class RetirementCapture<T : Any>(
        val recipient: T?,
        val drained: CompletableFuture<Unit>,
    )
}

sealed interface NotificationPostAdmission<out T> {
    data class Admitted<T>(val value: T) : NotificationPostAdmission<T>
    data object Revoked : NotificationPostAdmission<Nothing>
}

class NotificationPostSelfDrainException :
    IllegalStateException("Cannot await notification permit drain from an active operation")

enum class NotificationPostRejection {
    OWNER_RETIRED,
    PERMISSION_DENIED,
    UNSUPPORTED,
    AUTHORIZATION_CHECK_FAILED,
    REQUEST_BUILD_FAILED,
}

/** Result of the actual platform post attempt; Posted is not durable delivery. */
sealed interface NotificationPostAttempt {
    data class Posted(val id: NotificationId) : NotificationPostAttempt
    data class RejectedBeforeDispatch(
        val reason: NotificationPostRejection,
        val cause: Exception? = null,
        val capability: String? = null,
    ) : NotificationPostAttempt
    data class Uncertain(val failure: Exception) : NotificationPostAttempt
}

/** Opaque identity of one submitted platform post, distinct from its logical notification ID. */
data class DeliveredPostIdentity(val value: UUID)

enum class SnapshotRemovalOutcome { REMOVED, SUPERSEDED, UNKNOWN_IDENTITY }

/** A notification still shown by the platform. [payload] is null for notifications this policy did not post. */
data class DeliveredNotification(
    val id: NotificationId,
    val payload: NotificationPayload?,
    val postIdentity: DeliveredPostIdentity? = null,
)

/** The user's interaction with a posted notification; a null [action] is the default tap. */
data class NotificationResponse(val payload: NotificationPayload, val action: NotificationAction?, val userText: String?)

/** Platform permission state for posting notifications. */
enum class NotificationAuthorizationStatus { NOT_DETERMINED, DENIED, AUTHORIZED }

/** One action button; the text-input titles are set only for an inline-reply action. */
data class NotificationActionDefinition(
    val action: NotificationAction,
    val title: String,
    val textInputButtonTitle: String?,
    val textInputPlaceholder: String?,
)

/** The actions offered by one [NotificationCategory]. */
data class NotificationCategoryDefinition(
    val category: NotificationCategory,
    val actions: SnapshotList<NotificationActionDefinition>,
)

/**
 * Platform delivery (Swift `UNUserNotificationCenter`). Implemented by the WP-401 process adapter.
 * Members may throw; the WP-215 policy contains every failure so notification problems never
 * block messaging.
 */
interface NotificationDeliveryPort {
    suspend fun authorizationStatus(): NotificationAuthorizationStatus
    suspend fun requestAuthorization(): Boolean
    suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>)
    suspend fun post(request: NotificationRequest): NotificationPostResult
    /**
     * Permit-aware reaction post. Implementations must acquire the permit only around the final
     * synchronous platform mutation and report its observed outcome.
     */
    suspend fun post(
        request: NotificationRequest,
        permit: NotificationPostPermit,
    ): NotificationPostAdmission<NotificationPostAttempt> =
        throw UnsupportedOperationException("Permit-aware notification posts are unsupported")
    suspend fun setBadgeCount(count: Long)
    /** The permit must gate the final synchronous badge mutation, not any preceding suspension. */
    suspend fun setBadgeCount(count: Long, permit: NotificationPostPermit): NotificationPostAdmission<Unit> =
        throw UnsupportedOperationException("Permit-aware badge updates are unsupported")
    suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification>
    suspend fun removeDelivered(ids: SnapshotList<NotificationId>)
    /** Removes only the exact posts captured by this snapshot; unknown identities never fall back to logical IDs. */
    suspend fun removeDeliveredSnapshot(
        selected: SnapshotList<DeliveredNotification>,
    ): SnapshotList<SnapshotRemovalOutcome> =
        throw UnsupportedOperationException("Snapshot-conditioned notification removal is unsupported")
    /**
     * The permit must cover the complete synchronous cancel batch, acquired only after any delivered-list query.
     */
    suspend fun removeDeliveredSnapshot(
        selected: SnapshotList<DeliveredNotification>,
        permit: NotificationPostPermit,
    ): NotificationPostAdmission<SnapshotList<SnapshotRemovalOutcome>> =
        throw UnsupportedOperationException("Permit-aware snapshot removal is unsupported")
}
