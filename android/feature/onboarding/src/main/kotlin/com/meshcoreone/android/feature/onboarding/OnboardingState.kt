// PortedFrom: MC1/State/OnboardingState.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class OnboardingStep(val rawValue: String) {
    WELCOME("welcome"), PERMISSIONS("permissions"), PAIR("pair"), REGION("region"), PRESET("preset"),
}

sealed interface OnboardingCompletionReceipt {
    data class Preset(val presetId: String, val target: OnboardingPresetTarget?) : OnboardingCompletionReceipt
    data object WithoutDevice : OnboardingCompletionReceipt
    data object Restored : OnboardingCompletionReceipt
}

class OnboardingCompletionPublication internal constructor(
    val receipt: OnboardingCompletionReceipt,
    val revision: Long,
)

/** A finalizer may have committed; reconcile its original ID or acknowledge an explicit reset before replacing it. */
interface OnboardingCompletionOutcomeUnknown

/** Boolean persistence seam mirroring the `UserDefaults` calls the original state makes. */
interface OnboardingFlagStore {
    fun getBoolean(key: String): Boolean
    fun setBoolean(key: String, value: Boolean)

    suspend fun reconcileCompletion(
        attempt: OnboardingCompletionAttempt, requireCurrent: () -> Unit,
        onFinished: (Result<Boolean>) -> Unit,
    ) {
        val failure = UnsupportedOperationException("Onboarding completion readback is unavailable")
        onFinished(Result.failure(failure))
        throw failure
    }

    /** Waits for durable adapters to confirm the write before publishing completion. */
    suspend fun persistBoolean(key: String, value: Boolean) = setBoolean(key, value)

    suspend fun persistBoolean(key: String, value: Boolean, requireCurrent: () -> Unit) {
        requireCurrent()
        persistBoolean(key, value)
        requireCurrent()
    }

    suspend fun persistBoolean(
        key: String, value: Boolean, requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean,
    ) {
        requireCurrent()
        val attempt = OnboardingCompletionAttempt()
        check(seal(attempt)) { "Onboarding completion was retired before finalization" }
        persistBoolean(key, value)
    }

    suspend fun persistBoolean(
        key: String, value: Boolean, requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean, attempt: OnboardingCompletionAttempt,
    ) = persistBoolean(key, value, requireCurrent) { seal(attempt) }

    /** Process-owned writers notify the exact attempt even when its initiating child has been cancelled. */
    suspend fun persistBoolean(
        key: String, value: Boolean, requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean, attempt: OnboardingCompletionAttempt,
        onFinished: (Result<Unit>) -> Unit,
    ) {
        try {
            persistBoolean(key, value, requireCurrent, seal, attempt)
        } catch (failure: Exception) {
            onFinished(Result.failure(failure))
            throw failure
        }
        onFinished(Result.success(Unit))
    }
}

class InMemoryOnboardingFlagStore(initial: Map<String, Boolean> = emptyMap()) : OnboardingFlagStore {
    private val values = HashMap(initial)
    override fun getBoolean(key: String): Boolean = values[key] ?: false
    override fun setBoolean(key: String, value: Boolean) { values[key] = value }
}

enum class PermissionStatus { NOT_DETERMINED, GRANTED, DENIED }

/** Facts the original read from `ConnectionManager` to decide whether a first run is resumable. */
data class OnboardingResumeFacts(val pairedAccessoriesCount: Int, val hasLastConnectedDevice: Boolean)

/** Onboarding completion flag (persisted) and navigation path; plain state holder, no Android types. */
class OnboardingState(private val store: OnboardingFlagStore) {
    private val completionLock = Any()
    private var pendingAttempt: OnboardingCompletionAttempt? = null
    private var settlingAttempt: OnboardingCompletionAttempt? = null
    private val unsettled = MutableStateFlow(false)
    val unsettledCompletionFlow: StateFlow<Boolean> = unsettled.asStateFlow()
    private data class UncertainCompletion(
        val attempt: OnboardingCompletionAttempt, val receipt: OnboardingCompletionReceipt, val revision: Long,
    )
    private var uncertain: UncertainCompletion? = null
    private var reconcilingAttempt: OnboardingCompletionAttempt? = null
    private val reconciliationReady = MutableStateFlow(false)
    val reconciliationReadyFlow: StateFlow<Boolean> = reconciliationReady.asStateFlow()
    private val completionGate = MutableStateFlow(0L)
    /** Advances after every completion-gate mutation so mounted actions re-read non-observable gate inputs. */
    val completionGateFlow: StateFlow<Long> = completionGate.asStateFlow()
    private val resetMutex = Mutex()
    private var settlingResets = 0
    private var completionRevision = 0L
    private var retired = false
    private var resetRequired = false
    private val invalidationObservers = mutableListOf<() -> Unit>()
    private val lifecycle = MutableStateFlow(0L)
    val lifecycleGeneration: StateFlow<Long> = lifecycle.asStateFlow()
    private val completed = MutableStateFlow(store.getBoolean(KEY_HAS_COMPLETED))
    private val path = MutableStateFlow<List<OnboardingStep>>(emptyList())
    private val publication = MutableStateFlow(
        if (completed.value) OnboardingCompletionPublication(OnboardingCompletionReceipt.Restored, completionRevision) else null,
    )
    val completionReceipt: OnboardingCompletionReceipt? get() = publication.value?.receipt
    val hasUnsettledCompletion: Boolean get() = unsettled.value
    internal val completionPublication = publication.asStateFlow()

    val hasCompletedOnboardingFlow: StateFlow<Boolean> = completed.asStateFlow()
    val onboardingPathFlow: StateFlow<List<OnboardingStep>> = path.asStateFlow()

    var hasCompletedOnboarding: Boolean
        get() = completed.value
        set(value) {
            synchronized(completionLock) {
                check(!retired) { "Onboarding flow was retired" }
                check(settlingAttempt == null && !resetRequired && settlingResets == 0) {
                    "Onboarding requires an acknowledged reset"
                }
                invalidateCompletion()
                store.setBoolean(KEY_HAS_COMPLETED, value)
                publication.value = if (value) {
                    OnboardingCompletionPublication(OnboardingCompletionReceipt.Restored, completionRevision)
                } else null
                completed.value = value
                resetRequired = false
            }
        }

    val onboardingPath: List<OnboardingStep> get() = path.value

    fun append(step: OnboardingStep) = path.update { it + step }
    fun replacePath(steps: List<OnboardingStep>) { path.value = steps.toList() }
    fun pop(): Boolean {
        if (path.value.isEmpty()) return false
        path.update { it.dropLast(1) }
        return true
    }

    suspend fun completeOnboarding(requireCurrent: () -> Unit = {}) =
        completeOnboardingWithSeal(requireCurrent) { it.trySeal() }

    suspend fun completeOnboardingWithSeal(
        requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean,
    ) = completeOnboardingWithReceipt(OnboardingCompletionReceipt.WithoutDevice, requireCurrent, seal)

    suspend fun completeOnboardingWithReceipt(
        receipt: OnboardingCompletionReceipt,
        requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean,
    ) {
        val attempt = OnboardingCompletionAttempt()
        var published = false
        val revision = synchronized(completionLock) {
            check(!retired) { "Onboarding flow was retired" }
            check(!resetRequired && settlingResets == 0) { "Onboarding reset requires acknowledgement" }
            check(!completed.value) { "Onboarding completion is awaiting exact-flow settlement" }
            check(pendingAttempt == null) { "An onboarding completion is already pending" }
            pendingAttempt = attempt
            val reserved = nextCompletionRevision()
            setSettlingAttempt(attempt)
            reserved
        }
        val caller = currentCoroutineContext()
        val validate = {
            caller.ensureActive()
            requireCurrent()
            synchronized(completionLock) {
                check(pendingAttempt === attempt) { "Onboarding attempt was retired" }
            }
        }
        try {
            validate()
            store.persistBoolean(KEY_HAS_COMPLETED, true, validate, seal, attempt) { result ->
                synchronized(completionLock) {
                    val unknown = result.exceptionOrNull() is OnboardingCompletionOutcomeUnknown
                    if (pendingAttempt === attempt) {
                        if (unknown) {
                            resetRequired = true
                            uncertain = UncertainCompletion(attempt, receipt, revision)
                        } else pendingAttempt = null
                        if (result.isSuccess) {
                            completed.value = true
                            if (completionRevision == revision && !retired && !resetRequired) {
                                published = true
                                publication.value = OnboardingCompletionPublication(receipt, revision)
                            }
                        }
                        refreshReconciliationReady()
                    }
                    if (settlingAttempt === attempt && !unknown) setSettlingAttempt(null)
                }
            }
            synchronized(completionLock) {
                check(published) { "Onboarding attempt was retired" }
            }
        } finally {
            synchronized(completionLock) {
                attempt.reject(IllegalStateException("Onboarding attempt ended before sealing"))
                if (!attempt.isSealed && pendingAttempt === attempt) pendingAttempt = null
                if (!attempt.isSealed && settlingAttempt === attempt) setSettlingAttempt(null)
            }
        }
    }

    fun retireCompletion() {
        synchronized(completionLock) {
            retired = true
            invalidateCompletion()
        }
    }

    fun canStartCompletion(): Boolean =
        synchronized(completionLock) {
            !retired && !resetRequired && settlingResets == 0 && !completed.value && pendingAttempt == null
        }

    fun canReconcileCompletion(): Boolean = synchronized(completionLock) {
        !retired && settlingResets == 0 && uncertain != null && reconcilingAttempt == null
    }

    suspend fun reconcileCompletion(expectedGeneration: Long? = null): Boolean {
        val original = synchronized(completionLock) {
            check(expectedGeneration == null || lifecycle.value == expectedGeneration) {
                "Onboarding reconciliation was retired"
            }
            check(canReconcileCompletion()) { "No uncertain onboarding decision is available for readback" }
            checkNotNull(uncertain).also {
                reconcilingAttempt = it.attempt
                refreshReconciliationReady()
            }
        }
        var resolved: Boolean? = null
        val validate = {
            synchronized(completionLock) {
                check(!retired && uncertain === original && completionRevision == original.revision) {
                    "Onboarding reconciliation was retired"
                }
            }
        }
        store.reconcileCompletion(original.attempt, validate) { result ->
            synchronized(completionLock) {
                if (reconcilingAttempt === original.attempt) reconcilingAttempt = null
                if (uncertain === original && !retired && completionRevision == original.revision) {
                    result.getOrNull()?.let { committed ->
                        pendingAttempt = null
                        uncertain = null
                        resetRequired = false
                        resolved = committed
                        if (committed) {
                            completed.value = true
                            publication.value = OnboardingCompletionPublication(original.receipt, original.revision)
                        }
                        setSettlingAttempt(null)
                    }
                } else if (result.isSuccess && settlingAttempt === original.attempt) {
                    setSettlingAttempt(null)
                }
                refreshReconciliationReady()
            }
        }
        return synchronized(completionLock) { checkNotNull(resolved) { "Onboarding reconciliation was retired" } }
    }

    internal fun <T> withLifecycle(block: () -> T): T = synchronized(completionLock) { block() }

    internal fun onInvalidation(observer: () -> Unit) = synchronized(completionLock) {
        check(!retired) { "Onboarding flow was retired" }
        invalidationObservers += observer
    }

    internal fun ownsCompletion(value: OnboardingCompletionPublication): Boolean =
        synchronized(completionLock) { isCurrentCompletion(value) }

    /**
     * Commits a consumer action only while this exact, durably acknowledged publication is current.
     * The synchronous navigation action runs under the publication lock; it must not perform I/O or call user code.
     */
    fun commitCompletion(value: OnboardingCompletionPublication, commit: () -> Boolean): Boolean =
        synchronized(completionLock) {
            if (!isCurrentCompletion(value)) return@synchronized false
            commit()
        }

    private fun isCurrentCompletion(value: OnboardingCompletionPublication): Boolean =
        !retired && !resetRequired && settlingResets == 0 && settlingAttempt == null && uncertain == null &&
            value.revision == completionRevision && publication.value === value && completed.value &&
            store.getBoolean(KEY_HAS_COMPLETED)

    private fun nextCompletionRevision(): Long {
        completionRevision = Math.incrementExact(completionRevision)
        return completionRevision
    }

    private fun invalidateCompletion(): Long = synchronized(completionLock) {
        val revision = nextCompletionRevision()
        pendingAttempt?.reject(IllegalStateException("Onboarding attempt was retired"))
        pendingAttempt = null
        uncertain = null
        reconcilingAttempt = null
        if (settlingAttempt?.isSealed == false) setSettlingAttempt(null)
        refreshReconciliationReady()
        lifecycle.value = Math.incrementExact(lifecycle.value)
        val observers = invalidationObservers.toList()
        if (retired) invalidationObservers.clear()
        observers.forEach { it() }
        revision
    }

    suspend fun resetOnboarding() {
        val (revision, supersededAttempt) = synchronized(completionLock) {
            check(!retired) { "Onboarding flow was retired" }
            resetRequired = true
            settlingResets++
            val attempt = settlingAttempt
            val reserved = invalidateCompletion()
            unsettled.value = true
            reserved to attempt
        }
        // An admitted false write retains owner custody until its actual storage result, even if its caller cancels.
        withContext(NonCancellable) {
            try {
                resetMutex.withLock {
                    synchronized(completionLock) {
                        check(!retired && completionRevision == revision) { "Onboarding reset was superseded" }
                    }
                    store.persistBoolean(KEY_HAS_COMPLETED, false)
                    synchronized(completionLock) {
                        if (settlingAttempt === supersededAttempt) setSettlingAttempt(null)
                        check(!retired && completionRevision == revision) { "Onboarding reset was superseded" }
                        publication.value = null
                        completed.value = false
                        resetRequired = false
                        setSettlingAttempt(null)
                        path.value = emptyList()
                    }
                }
            } finally {
                synchronized(completionLock) {
                    settlingResets--
                    unsettled.value = settlingAttempt != null || settlingResets > 0
                    refreshReconciliationReady()
                }
            }
        }
    }

    private fun setSettlingAttempt(attempt: OnboardingCompletionAttempt?) {
        settlingAttempt = attempt
        unsettled.value = attempt != null || settlingResets > 0
        completionGate.update { it + 1 }
    }

    private fun refreshReconciliationReady() {
        reconciliationReady.value = !retired && settlingResets == 0 && uncertain != null && reconcilingAttempt == null
        completionGate.update { it + 1 }
    }

    /**
     * Starting path pushed above the welcome root. `pairedAccessoriesCount` is 0 where no system
     * pairing registry exists, so `hasLastConnectedDevice` (set only after a real connect) also
     * counts as the "this install connected a radio" signal.
     */
    fun suggestedStartingPath(
        facts: OnboardingResumeFacts,
        locationStatus: PermissionStatus,
        notificationStatus: PermissionStatus,
        regionAlreadySet: Boolean,
    ): List<OnboardingStep> {
        if (hasCompletedOnboarding) return emptyList()
        if (!(facts.pairedAccessoriesCount > 0 || facts.hasLastConnectedDevice)) return emptyList()
        val handled = locationStatus != PermissionStatus.NOT_DETERMINED &&
            notificationStatus != PermissionStatus.NOT_DETERMINED
        if (!handled) return listOf(OnboardingStep.PERMISSIONS)
        val last = if (regionAlreadySet) OnboardingStep.PRESET else OnboardingStep.REGION
        return listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, last)
    }

    companion object {
        /** Same key as `AppStorageKey.hasCompletedOnboarding` so a restored backup keeps meaning. */
        const val KEY_HAS_COMPLETED = "hasCompletedOnboarding"
    }
}
