// PortedFrom: MC1/Views/Tools/TracePath/SavedPathsViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/SavedPathDetailViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.TracePathRunDTO
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live providers for the saved-path list; `null` mirrors a disconnected state (calls are no-ops). */
interface SavedPathsDependencies {
    fun sessionToken(): SessionToken? = null
    fun isOriginatingSessionCurrent(generation: Int, token: SessionToken?): Boolean = true
    fun savedPaths(): TracePathPersisting?
    fun connectedDevice(): DeviceDTO?
}

/** Copy for saved-path store failures. */
interface SavedPathsStrings {
    val loadFailed: String
    val renameFailed: String
    val deleteFailed: String
}

enum class SavedPathsError { LOAD, RENAME, DELETE }

data class SavedPathsState(
    val savedPaths: List<SavedTracePathDTO> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val error: SavedPathsError? = null,
)

/** The saved-paths sheet (source `SavedPathsViewModel`). Call from the main thread. */
class SavedPathsStateHolder(
    private val strings: SavedPathsStrings,
    private val diagnostics: TraceDiagnostics,
) {
    private val mutableState = MutableStateFlow(SavedPathsState())
    val state: StateFlow<SavedPathsState> = mutableState.asStateFlow()
    private var deps: SavedPathsDependencies? = null
    private var active = true
    private var revision = 0L
    private var mutationRevision = 0L
    private val deletedPaths = mutableSetOf<EntityKey>()
    private var sessionToken: SessionToken? = null
    private var originatingGeneration: Int? = null

    fun configure(
        dependencies: SavedPathsDependencies,
        originatingToken: SessionToken? = dependencies.sessionToken(),
        generation: Int? = null,
    ) {
        deps = dependencies
        sessionToken = originatingToken
        originatingGeneration = generation
    }

    fun retireConnection() {
        active = false
        revision += 1
        mutableState.value = SavedPathsState()
    }

    fun isCurrentPath(path: SavedTracePathDTO): Boolean =
        isCurrentConnection(path.radioId) &&
            mutableState.value.savedPaths.any { it.id == path.id && it.radioId == path.radioId }

    fun isCurrentConnection(radioId: RadioId): Boolean {
        val deviceRadio = deps?.connectedDevice()?.radioId
        val token = sessionToken
        val generation = originatingGeneration
        return active && deviceRadio == radioId && (token == null || token.radioId == radioId) &&
            deps?.sessionToken() == token &&
            (generation == null || deps?.isOriginatingSessionCurrent(generation, token) == true)
    }

    fun clearRenameError() {
        mutableState.update {
            if (it.error == SavedPathsError.RENAME) it.copy(errorMessage = null, error = null) else it
        }
    }

    private fun isCurrent(operation: Long, radioId: RadioId): Boolean =
        operation == revision && isCurrentConnection(radioId)

    suspend fun loadSavedPaths() = loadSavedPaths(null)

    private suspend fun loadSavedPaths(mutation: Long?) {
        if (!active) return
        val radioId = deps?.connectedDevice()?.radioId ?: return
        if (!isCurrentConnection(radioId)) return
        val store = deps?.savedPaths() ?: return
        val operation = ++revision
        mutableState.update {
            val clearError = mutation == null || mutation == mutationRevision
            it.copy(
                isLoading = true,
                errorMessage = if (clearError) null else it.errorMessage,
                error = if (clearError) null else it.error,
            )
        }
        try {
            val paths = store.fetchSavedTracePaths(radioId)
            if (isCurrent(operation, radioId)) mutableState.update {
                it.copy(savedPaths = paths.filterNot { path -> EntityKey(path.radioId, path.id) in deletedPaths })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("loadSavedPaths", error)
            if (isCurrent(operation, radioId) && (mutation == null || mutation == mutationRevision)) {
                mutableState.update { it.copy(errorMessage = strings.loadFailed, error = SavedPathsError.LOAD) }
            }
        } finally {
            if (isCurrent(operation, radioId)) mutableState.update { it.copy(isLoading = false) }
        }
    }

    /** Renames, then reloads the whole list. */
    suspend fun renamePath(path: SavedTracePathDTO, newName: String): Boolean {
        if (!isCurrentConnection(path.radioId)) return false
        val store = deps?.savedPaths()
        val operation = ++mutationRevision
        try {
            checkNotNull(store) { "Saved path storage unavailable" }
            store.updateSavedTracePathName(EntityKey(path.radioId, path.id), newName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("renamePath", error)
            if (isCurrentConnection(path.radioId) && operation == mutationRevision) {
                mutableState.update { it.copy(errorMessage = strings.renameFailed, error = SavedPathsError.RENAME) }
            }
            return false
        }
        if (isCurrentConnection(path.radioId)) loadSavedPaths(operation)
        return isCurrentConnection(path.radioId)
    }

    /** Deletes and drops the row locally without a reload. */
    suspend fun deletePath(path: SavedTracePathDTO): Boolean {
        if (!isCurrentConnection(path.radioId)) return false
        val store = deps?.savedPaths() ?: return false
        val operation = ++mutationRevision
        try {
            store.deleteSavedTracePath(EntityKey(path.radioId, path.id))
            if (isCurrentConnection(path.radioId)) {
                deletedPaths += EntityKey(path.radioId, path.id)
                mutableState.update { state ->
                    state.copy(
                        savedPaths = state.savedPaths.filterNot { it.id == path.id && it.radioId == path.radioId },
                        errorMessage = if (operation == mutationRevision) null else state.errorMessage,
                        error = if (operation == mutationRevision) null else state.error,
                    )
                }
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("deletePath", error)
            if (isCurrentConnection(path.radioId) && operation == mutationRevision) {
                mutableState.update { it.copy(errorMessage = strings.deleteFailed, error = SavedPathsError.DELETE) }
            }
            return false
        }
    }
}

/** Run history of one saved path (source `SavedPathDetailViewModel`). */
enum class SavedPathDetailError { LOAD, NOT_FOUND, STALE_SESSION }

data class SavedPathDetailState(
    val savedPath: SavedTracePathDTO,
    val isLoading: Boolean = false,
    val error: SavedPathDetailError? = null,
) {
    /** Newest first. */
    val sortedRuns: List<TracePathRunDTO> get() = savedPath.runs.sortedByDescending { it.date }
    val successfulRuns: List<TracePathRunDTO> get() = sortedRuns.filter { it.success }
    val showsChart: Boolean get() = successfulRuns.size >= 2
    val bestRoundTrip: Long? get() = successfulRuns.minOfOrNull { it.roundTripMs }
    val averageRoundTrip: Long? get() = savedPath.averageRoundTripMs
    val successRateText: String get() = "${savedPath.successRate}%"

    /** Bytes per hop from when the path was saved (1, 2 or 4). */
    val hashSize: Long get() = savedPath.hashSize
}

class SavedPathDetailStateHolder(savedPath: SavedTracePathDTO, private val diagnostics: TraceDiagnostics) {
    private val mutableState = MutableStateFlow(SavedPathDetailState(savedPath))
    val state: StateFlow<SavedPathDetailState> = mutableState.asStateFlow()
    private var store: (() -> TracePathPersisting?)? = null
    private var isCurrent: () -> Boolean = { true }
    private var active = true
    private var revision = 0L

    /** Both providers are checked live, including after a suspended read. */
    fun configure(isCurrent: () -> Boolean = { true }, savedPaths: () -> TracePathPersisting?) {
        store = savedPaths
        this.isCurrent = isCurrent
    }

    fun retireConnection() {
        active = false
        revision += 1
        mutableState.update { it.copy(isLoading = false, error = SavedPathDetailError.STALE_SESSION) }
    }

    suspend fun refresh() {
        if (!active) return
        val operation = ++revision
        if (!isCurrent()) {
            mutableState.update { it.copy(isLoading = false, error = SavedPathDetailError.STALE_SESSION) }
            return
        }
        mutableState.update { it.copy(isLoading = true, error = null) }
        val path = mutableState.value.savedPath
        fun canPublish(): Boolean = active && operation == revision
        try {
            val persisting = checkNotNull(store?.invoke()) { "Saved path storage unavailable" }
            val updated = persisting.fetchSavedTracePath(EntityKey(path.radioId, path.id))
            if (canPublish() && isCurrent()) {
                check(updated == null || (updated.id == path.id && updated.radioId == path.radioId)) {
                    "Saved path identity changed"
                }
                mutableState.update {
                    if (updated == null) it.copy(error = SavedPathDetailError.NOT_FOUND)
                    else it.copy(savedPath = updated)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("refreshSavedPath", error)
            if (canPublish()) mutableState.update { it.copy(error = SavedPathDetailError.LOAD) }
        } finally {
            if (canPublish()) mutableState.update {
                it.copy(isLoading = false, error = if (isCurrent()) it.error else SavedPathDetailError.STALE_SESSION)
            }
        }
    }
}
