// PortedFrom: MC1/Views/Tools/TracePath/SavedPathsViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/TracePath/SavedPathDetailViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
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
    fun savedPaths(): TracePathPersisting?
    fun connectedDevice(): DeviceDTO?
}

/** Copy for saved-path store failures. */
interface SavedPathsStrings {
    val loadFailed: String
    val renameFailed: String
    val deleteFailed: String
}

data class SavedPathsState(
    val savedPaths: List<SavedTracePathDTO> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
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

    fun configure(dependencies: SavedPathsDependencies) {
        deps = dependencies
    }

    fun retireConnection() {
        active = false
        revision += 1
        mutableState.value = SavedPathsState()
    }

    fun isCurrentPath(path: SavedTracePathDTO): Boolean =
        isCurrentConnection(path.radioId) &&
            mutableState.value.savedPaths.any { it.id == path.id && it.radioId == path.radioId }

    fun isCurrentConnection(radioId: RadioId): Boolean =
        active && deps?.connectedDevice()?.radioId == radioId

    private fun isCurrent(operation: Long, radioId: RadioId): Boolean =
        operation == revision && isCurrentConnection(radioId)

    suspend fun loadSavedPaths() {
        if (!active) return
        val radioId = deps?.connectedDevice()?.radioId ?: return
        val store = deps?.savedPaths() ?: return
        val operation = ++revision
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val paths = store.fetchSavedTracePaths(radioId)
            if (isCurrent(operation, radioId)) mutableState.update { it.copy(savedPaths = paths.toList()) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("loadSavedPaths", error)
            if (isCurrent(operation, radioId)) mutableState.update { it.copy(errorMessage = strings.loadFailed) }
        } finally {
            if (isCurrent(operation, radioId)) mutableState.update { it.copy(isLoading = false) }
        }
    }

    /** Renames, then reloads the whole list. */
    suspend fun renamePath(path: SavedTracePathDTO, newName: String) {
        val operation = revision
        if (!isCurrent(operation, path.radioId)) return
        val store = deps?.savedPaths() ?: return
        try {
            store.updateSavedTracePathName(EntityKey(path.radioId, path.id), newName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("renamePath", error)
            if (isCurrent(operation, path.radioId)) mutableState.update { it.copy(errorMessage = strings.renameFailed) }
            return
        }
        if (isCurrent(operation, path.radioId)) loadSavedPaths()
    }

    /** Deletes and drops the row locally without a reload. */
    suspend fun deletePath(path: SavedTracePathDTO): Boolean {
        val operation = revision
        if (!isCurrent(operation, path.radioId)) return false
        val store = deps?.savedPaths() ?: return false
        try {
            store.deleteSavedTracePath(EntityKey(path.radioId, path.id))
            if (isCurrentConnection(path.radioId)) {
                revision += 1
                mutableState.update { state ->
                    state.copy(
                        savedPaths = state.savedPaths.filterNot { it.id == path.id && it.radioId == path.radioId },
                        isLoading = false,
                    )
                }
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("deletePath", error)
            if (isCurrent(operation, path.radioId)) mutableState.update { it.copy(errorMessage = strings.deleteFailed) }
            return false
        }
    }
}

/** Run history of one saved path (source `SavedPathDetailViewModel`). */
data class SavedPathDetailState(val savedPath: SavedTracePathDTO, val isLoading: Boolean = false) {
    /** Newest first. */
    val sortedRuns: List<TracePathRunDTO> get() = savedPath.runs.sortedByDescending { it.date }
    val successfulRuns: List<TracePathRunDTO> get() = sortedRuns.filter { it.success }
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

    /** The provider is read live; `null` mirrors a disconnected state. */
    fun configure(savedPaths: () -> TracePathPersisting?) {
        store = savedPaths
    }

    suspend fun refresh() {
        val persisting = store?.invoke() ?: return
        mutableState.update { it.copy(isLoading = true) }
        val path = mutableState.value.savedPath
        try {
            persisting.fetchSavedTracePath(EntityKey(path.radioId, path.id))?.let { updated ->
                mutableState.update { it.copy(savedPath = updated) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnostics.failure("refreshSavedPath", error)
        } finally {
            mutableState.update { it.copy(isLoading = false) }
        }
    }
}
