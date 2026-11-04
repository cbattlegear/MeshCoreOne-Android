// PortedFrom: MC1/State/OnboardingState.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class OnboardingStep(val rawValue: String) {
    WELCOME("welcome"), PERMISSIONS("permissions"), PAIR("pair"), REGION("region"), PRESET("preset"),
}

sealed interface OnboardingCompletionReceipt {
    data class Preset(val presetId: String, val target: OnboardingPresetTarget?) : OnboardingCompletionReceipt
    data object WithoutDevice : OnboardingCompletionReceipt
    data object Restored : OnboardingCompletionReceipt
}

internal class OnboardingCompletionPublication(val receipt: OnboardingCompletionReceipt, val revision: Long)

/** Boolean persistence seam mirroring the `UserDefaults` calls the original state makes. */
interface OnboardingFlagStore {
    fun getBoolean(key: String): Boolean
    fun setBoolean(key: String, value: Boolean)

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
    private var completionRevision = 0L
    private var retired = false
    private val completed = MutableStateFlow(store.getBoolean(KEY_HAS_COMPLETED))
    private val path = MutableStateFlow<List<OnboardingStep>>(emptyList())
    private val publication = MutableStateFlow(
        if (completed.value) OnboardingCompletionPublication(OnboardingCompletionReceipt.Restored, completionRevision) else null,
    )
    val completionReceipt: OnboardingCompletionReceipt? get() = publication.value?.receipt
    internal val completionPublication = publication.asStateFlow()

    val hasCompletedOnboardingFlow: StateFlow<Boolean> = completed.asStateFlow()
    val onboardingPathFlow: StateFlow<List<OnboardingStep>> = path.asStateFlow()

    var hasCompletedOnboarding: Boolean
        get() = completed.value
        set(value) {
            synchronized(completionLock) {
                check(!retired) { "Onboarding flow was retired" }
                invalidateCompletion()
                store.setBoolean(KEY_HAS_COMPLETED, value)
                publication.value = if (value) {
                    OnboardingCompletionPublication(OnboardingCompletionReceipt.Restored, completionRevision)
                } else null
                completed.value = value
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
        synchronized(completionLock) {
            check(!retired) { "Onboarding flow was retired" }
            check(!completed.value) { "Onboarding completion is awaiting exact-flow settlement" }
            completionRevision++
            pendingAttempt?.reject(IllegalStateException("A newer onboarding attempt started"))
            pendingAttempt = attempt
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
            store.persistBoolean(KEY_HAS_COMPLETED, true, validate, seal, attempt)
            synchronized(completionLock) {
                check(pendingAttempt === attempt) { "Onboarding attempt was retired" }
                pendingAttempt = null
                publication.value = OnboardingCompletionPublication(receipt, completionRevision)
                completed.value = true
            }
        } finally {
            synchronized(completionLock) {
                attempt.reject(IllegalStateException("Onboarding attempt ended before sealing"))
                if (pendingAttempt === attempt) pendingAttempt = null
            }
        }
    }

    fun retireCompletion() {
        synchronized(completionLock) {
            retired = true
            invalidateCompletion()
        }
    }

    fun canStartCompletion(): Boolean = synchronized(completionLock) { !retired && !completed.value }

    internal fun ownsCompletion(value: OnboardingCompletionPublication): Boolean =
        synchronized(completionLock) {
            !retired && value.revision == completionRevision && publication.value === value
        }

    private fun invalidateCompletion(): Long = synchronized(completionLock) {
        pendingAttempt?.reject(IllegalStateException("Onboarding attempt was retired"))
        pendingAttempt = null
        ++completionRevision
    }

    suspend fun resetOnboarding() {
        val revision = synchronized(completionLock) {
            check(!retired) { "Onboarding flow was retired" }
            invalidateCompletion()
        }
        store.persistBoolean(KEY_HAS_COMPLETED, false)
        synchronized(completionLock) {
            check(completionRevision == revision) { "Onboarding reset was superseded" }
            publication.value = null
            completed.value = false
            path.value = emptyList()
        }
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
