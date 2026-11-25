// PortedFrom: MC1/Theme/ThemeService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Theme/ThemeServiceError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: process-owned DataStore observation; no StoreKit/entitlement or radio lifetime.
package com.meshcoreone.android.core.designsystem

import android.util.Log
import com.meshcoreone.android.core.datastore.AppearancePreferenceStore
import com.meshcoreone.android.core.datastore.AppearanceStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.model.SnapshotList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class ThemeSelection(val current: Theme, val colorSchemePreference: AppColorSchemePreference) {
    val effectiveColorScheme: ColorScheme? get() = current.effectiveColorScheme(colorSchemePreference)
}

sealed interface ThemeProblem {
    data class UnknownThemeID(val raw: String) : ThemeProblem
    data class UnknownColorScheme(val raw: String) : ThemeProblem
    data class PreferenceFailure(val failure: StorageFailure) : ThemeProblem
    data class MissingResource(val resource: String) : ThemeProblem
    data object OwnerClosed : ThemeProblem
}

class ThemeServiceFailure(val problem: ThemeProblem, cause: Throwable? = null) :
    Exception("Theme:${problem.javaClass.simpleName}", cause)

fun interface ThemeIssueReporter { fun report(failure: ThemeServiceFailure) }
object AndroidThemeIssueReporter : ThemeIssueReporter {
    override fun report(failure: ThemeServiceFailure) {
        Log.e("MeshCoreOne.Theme", failure.problem.javaClass.simpleName)
    }
}

sealed interface ThemeServiceState {
    data object Loading : ThemeServiceState
    data class Ready(val selection: ThemeSelection, val correctedKeys: SnapshotList<String>, val themeReversion: Long? = null) : ThemeServiceState
    data class Failed(val failure: ThemeServiceFailure, val previous: ThemeSelection?) : ThemeServiceState
    data class Closed(val previous: ThemeSelection?) : ThemeServiceState
}

class ThemeService private constructor(
    private val preferences: PreferenceStore,
    processScope: CoroutineScope,
    private val reporter: ThemeIssueReporter,
) {
    private val appearance = AppearancePreferenceStore(preferences)
    private val lifetime = SupervisorJob(requireNotNull(processScope.coroutineContext[Job]) { "A process-owned parent Job is required" })
    private val scope = CoroutineScope(processScope.coroutineContext + lifetime)
    private val operation = Mutex()
    private val closeOperation = Mutex()
    private val closed = AtomicBoolean(false)
    private var closeCompleted = false
    private var previous: ThemeSelection? = null
    private var observation: Job? = null
    private var observationGeneration = 0L
    private var reversionSequence = 0L
    private var pendingReversion: Long? = null
    private val mutableState = MutableStateFlow<ThemeServiceState>(ThemeServiceState.Loading)
    val state: StateFlow<ThemeServiceState> = mutableState.asStateFlow()
    val availableToCurrentUser: SnapshotList<Theme> get() = ThemeRegistry.allThemes

    init {
        lifetime.invokeOnCompletion {
            closed.set(true)
            val current = mutableState.value
            val finalSelection = when (current) {
                is ThemeServiceState.Ready -> current.selection
                is ThemeServiceState.Failed -> current.previous
                is ThemeServiceState.Closed -> current.previous
                ThemeServiceState.Loading -> null
            }
            mutableState.value = ThemeServiceState.Closed(finalSelection)
        }
    }

    private fun requireOpen() {
        if (closed.get() || !lifetime.isActive) throw failure(ThemeProblem.OwnerClosed)
    }

    private fun failure(problem: ThemeProblem, cause: Throwable? = null): ThemeServiceFailure =
        ThemeServiceFailure(problem, cause).also(reporter::report)

    private suspend fun resolve(): ThemeServiceState.Ready {
        val result = appearance.resolveStoredSelection()
        val theme = ThemeRegistry.theme(result.selection.themeID)
            ?: throw failure(ThemeProblem.MissingResource(result.selection.themeID))
        val preference = AppColorSchemePreference.fromRawValue(result.selection.colorSchemePreference)
            ?: throw failure(ThemeProblem.UnknownColorScheme(result.selection.colorSchemePreference))
        return ThemeServiceState.Ready(ThemeSelection(theme, preference), result.correctedKeys)
    }

    private suspend fun refreshLocked(announceReversion: Boolean = true): ThemeServiceState.Ready {
        requireOpen()
        val ready = try {
            resolve()
        } catch (problem: StorageFailure) {
            val typed = failure(ThemeProblem.PreferenceFailure(problem), problem)
            if (!closed.get()) mutableState.value = ThemeServiceState.Failed(typed, previous)
            throw typed
        } catch (problem: ThemeResourceFailure) {
            val typed = failure(ThemeProblem.MissingResource(problem.resource), problem)
            if (!closed.get()) mutableState.value = ThemeServiceState.Failed(typed, previous)
            throw typed
        }
        requireOpen()
        if (announceReversion && previous != null && previous?.current?.id != ready.selection.current.id &&
            ready.selection.current.id == ThemeId.DEFAULT
        ) {
            pendingReversion = Math.incrementExact(reversionSequence).also { reversionSequence = it }
        }
        previous = ready.selection
        return ready.copy(themeReversion = pendingReversion).also { mutableState.value = it }
    }

    suspend fun refreshFromPreferences(): ThemeServiceState.Ready {
        currentCoroutineContext().ensureActive()
        return operation.withLock {
            refreshLocked().also { startObservationLocked() }
        }
    }

    private fun startObservationLocked() {
        if (observation?.isActive == true) return
        val generation = Math.incrementExact(observationGeneration).also { observationGeneration = it }
        observation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                preferences.snapshots.collect { snapshot ->
                    val rawTheme = snapshot[AppearanceStorageKey.selectedThemeID]
                    val rawScheme = snapshot[AppearanceStorageKey.appColorSchemePreference]
                    operation.withLock {
                        val current = previous
                        if (current == null || current.current.id.rawValue != rawTheme ||
                            current.colorSchemePreference.rawValue != rawScheme
                        ) refreshLocked()
                    }
                }
            } catch (problem: StorageFailure) {
                val typed = failure(ThemeProblem.PreferenceFailure(problem), problem)
                operation.withLock {
                    if (!closed.get() && generation == observationGeneration) {
                        observation = null
                        mutableState.value = ThemeServiceState.Failed(typed, previous)
                    }
                }
            } catch (problem: ThemeServiceFailure) {
                operation.withLock {
                    if (!closed.get() && generation == observationGeneration) {
                        observation = null
                        mutableState.value = ThemeServiceState.Failed(problem, previous)
                    }
                }
            }
        }
    }

    suspend fun setCurrent(id: ThemeId) {
        currentCoroutineContext().ensureActive()
        operation.withLock {
            requireOpen()
            try {
                appearance.setTheme(id.rawValue)
                refreshLocked(announceReversion = false)
                startObservationLocked()
            } catch (problem: StorageFailure) {
                val typed = failure(ThemeProblem.PreferenceFailure(problem), problem)
                if (!closed.get()) mutableState.value = ThemeServiceState.Failed(typed, previous)
                throw typed
            }
        }
    }

    suspend fun setCurrent(raw: String) {
        currentCoroutineContext().ensureActive()
        val id = ThemeId.fromRawValue(raw) ?: throw failure(ThemeProblem.UnknownThemeID(raw))
        setCurrent(id)
    }

    suspend fun setColorSchemePreference(preference: AppColorSchemePreference) {
        currentCoroutineContext().ensureActive()
        operation.withLock {
            requireOpen()
            try {
                appearance.setColorScheme(preference.rawValue)
                refreshLocked()
                startObservationLocked()
            } catch (problem: StorageFailure) {
                val typed = failure(ThemeProblem.PreferenceFailure(problem), problem)
                if (!closed.get()) mutableState.value = ThemeServiceState.Failed(typed, previous)
                throw typed
            }
        }
    }

    suspend fun claimThemeReversion(version: Long): Boolean {
        currentCoroutineContext().ensureActive()
        return operation.withLock {
            // Closing is an expected terminal announcement outcome, not a failed preference operation.
            if (closed.get() || !lifetime.isActive) return@withLock false
            if (pendingReversion != version) return@withLock false
            pendingReversion = null
            val current = mutableState.value
            if (current is ThemeServiceState.Ready) mutableState.value = current.copy(themeReversion = null)
            true
        }
    }

    suspend fun close() {
        withContext(NonCancellable) {
            closeOperation.withLock {
                if (!closeCompleted) {
                    closed.set(true)
                    lifetime.cancelAndJoin()
                    operation.withLock {
                        mutableState.value = ThemeServiceState.Closed(previous)
                        closeCompleted = true
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
    }

    companion object {
        suspend fun create(
            preferences: PreferenceStore,
            processScope: CoroutineScope,
            reporter: ThemeIssueReporter = AndroidThemeIssueReporter,
        ): ThemeService {
            processScope.coroutineContext.ensureActive()
            val service = ThemeService(preferences, processScope, reporter)
            try {
                service.refreshFromPreferences()
            } catch (failure: ThemeServiceFailure) {
                service.lifetime.cancelAndJoin()
                throw failure
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                service.lifetime.cancel()
                throw cancelled
            }
            return service
        }
    }
}
