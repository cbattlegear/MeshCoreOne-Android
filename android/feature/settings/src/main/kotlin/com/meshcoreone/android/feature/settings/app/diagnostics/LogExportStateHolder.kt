// PortedFrom: MC1/Views/Settings/Sections/DiagnosticsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: the share sheet becomes a system "save as" (SAF) through LogDocumentPort.
package com.meshcoreone.android.feature.settings.app.diagnostics

import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsState(
    val isExporting: Boolean = false,
    val errorMessage: String? = null,
    /** Display name of the file the last export was saved as; shown once, then dismissed. */
    val savedFileName: String? = null,
)

class LogExportStateHolder(
    private val dependencies: LogExportDependencies,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutableState

    /** Builds the export text and asks the user where to save it; ignored while an export is running. */
    fun exportLogs(): Job? {
        if (mutableState.value.isExporting) return null
        mutableState.update { it.copy(isExporting = true, errorMessage = null, savedFileName = null) }
        return scope.launch {
            try {
                val text = LogExportFormatter.format(dependencies.data.snapshot(), zone)
                when (val outcome = dependencies.documents.saveText(LogExportFormatter.fileName(clock.instant(), zone), text)) {
                    is LogSaveOutcome.Saved -> mutableState.update { it.copy(savedFileName = outcome.displayName) }
                    LogSaveOutcome.Cancelled -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dependencies.diagnostics.report("Failed to create export file", failure)
                mutableState.update { it.copy(errorMessage = dependencies.strings.exportFailed()) }
            } finally {
                mutableState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun clearLogs(): Job = scope.launch {
        try {
            dependencies.data.clearLogs()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dependencies.diagnostics.report("Failed to clear debug logs", failure)
            mutableState.update { it.copy(errorMessage = dependencies.strings.genericFailure(failure)) }
        }
    }

    fun dismissError() = mutableState.update { it.copy(errorMessage = null) }

    fun dismissSaved() = mutableState.update { it.copy(savedFileName = null) }
}
