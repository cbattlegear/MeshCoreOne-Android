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
    private val completed = MutableStateFlow(store.getBoolean(KEY_HAS_COMPLETED))
    private val path = MutableStateFlow<List<OnboardingStep>>(emptyList())
    @Volatile
    var completionReceipt: OnboardingCompletionReceipt? =
        if (completed.value) OnboardingCompletionReceipt.Restored else null
        private set

    val hasCompletedOnboardingFlow: StateFlow<Boolean> = completed.asStateFlow()
    val onboardingPathFlow: StateFlow<List<OnboardingStep>> = path.asStateFlow()

    var hasCompletedOnboarding: Boolean
        get() = completed.value
        set(value) {
            store.setBoolean(KEY_HAS_COMPLETED, value)
            completionReceipt = if (value) OnboardingCompletionReceipt.Restored else null
            completed.value = value
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
        val caller = currentCoroutineContext()
        val validate = {
            caller.ensureActive()
            requireCurrent()
            completionReceipt = receipt
        }
        validate()
        store.persistBoolean(KEY_HAS_COMPLETED, true, validate, seal)
        completed.value = true
    }

    suspend fun resetOnboarding() {
        store.persistBoolean(KEY_HAS_COMPLETED, false)
        completionReceipt = null
        completed.value = false
        path.value = emptyList()
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
