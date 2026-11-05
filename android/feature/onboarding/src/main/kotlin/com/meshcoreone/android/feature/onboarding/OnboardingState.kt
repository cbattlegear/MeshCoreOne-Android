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

enum class OnboardingStep(val rawValue: String) {
    WELCOME("welcome"), PERMISSIONS("permissions"), PAIR("pair"), REGION("region"), PRESET("preset"),
}

sealed interface OnboardingCompletionReceipt {
    data class Preset(val presetId: String, val target: OnboardingPresetTarget?) : OnboardingCompletionReceipt
    data object WithoutDevice : OnboardingCompletionReceipt
    data object Restored : OnboardingCompletionReceipt
}

internal class OnboardingCompletionPublication(val receipt: OnboardingCompletionReceipt, val revision: Long)

/** A finalizer may have committed; another decision requires an acknowledged explicit reset. */
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
    private val resetMutex = Mutex()
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
                check(settlingAttempt == null && !resetRequired) { "Onboarding requires an acknowledged reset" }
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
            check(!resetRequired) { "Onboarding reset requires acknowledgement" }
            check(!completed.value) { "Onboarding completion is awaiting exact-flow settlement" }
            check(pendingAttempt == null) { "An onboarding completion is already pending" }
            pendingAttempt = attempt
            setSettlingAttempt(attempt)
            ++completionRevision
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
                    if (settlingAttempt === attempt && !unknown) setSettlingAttempt(null)
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
                    }
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
        synchronized(completionLock) { !retired && !resetRequired && !completed.value && pendingAttempt == null }

    fun canReconcileCompletion(): Boolean = synchronized(completionLock) {
        !retired && uncertain != null && reconcilingAttempt == null
    }

    suspend fun reconcileCompletion(expectedGeneration: Long? = null): Boolean {
        val original = synchronized(completionLock) {
            check(expectedGeneration == null || lifecycle.value == expectedGeneration) {
                "Onboarding reconciliation was retired"
            }
            check(canReconcileCompletion()) { "No uncertain onboarding decision is available for readback" }
            checkNotNull(uncertain).also { reconcilingAttempt = it.attempt }
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
        synchronized(completionLock) {
            !retired && !resetRequired && value.revision == completionRevision && publication.value === value
        }

    private fun invalidateCompletion(): Long = synchronized(completionLock) {
        pendingAttempt?.reject(IllegalStateException("Onboarding attempt was retired"))
        pendingAttempt = null
        uncertain = null
        reconcilingAttempt = null
        if (settlingAttempt?.isSealed == false) setSettlingAttempt(null)
        val revision = ++completionRevision
        lifecycle.value = Math.incrementExact(lifecycle.value)
        val observers = invalidationObservers.toList()
        if (retired) invalidationObservers.clear()
        observers.forEach { it() }
        revision
    }

    suspend fun resetOnboarding() {
        val revision = synchronized(completionLock) {
            check(!retired) { "Onboarding flow was retired" }
            resetRequired = true
            invalidateCompletion()
        }
        resetMutex.withLock {
            synchronized(completionLock) {
                check(completionRevision == revision) { "Onboarding reset was superseded" }
            }
            store.persistBoolean(KEY_HAS_COMPLETED, false)
            synchronized(completionLock) {
                check(completionRevision == revision) { "Onboarding reset was superseded" }
                publication.value = null
                completed.value = false
                resetRequired = false
                setSettlingAttempt(null)
                path.value = emptyList()
            }
        }
    }

    private fun setSettlingAttempt(attempt: OnboardingCompletionAttempt?) {
        settlingAttempt = attempt
        unsettled.value = attempt != null
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
