// PortedFrom: MC1/Views/Settings/Sections/DiagnosticsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsState(
    val isExporting: Boolean = false,
    val showingClearLogsAlert: Boolean = false,
    /** The exported log file as a shareable content URI; the UI hands it to the share sheet and clears it. */
    val exportedFile: String? = null,
    val errorMessage: UiText? = null,
)

/** Debug-log export and clear. Clearing is destructive, so it only runs after the confirmation dialog. */
class DiagnosticsStateHolder(private val env: SettingsEnvironment, private val port: DiagnosticsPort) {
    private val mutable = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutable.asStateFlow()

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun requestClearLogs() = mutable.update { it.copy(showingClearLogsAlert = true) }
    fun dismissClearLogsAlert() = mutable.update { it.copy(showingClearLogsAlert = false) }
    fun onExportShared() = mutable.update { it.copy(exportedFile = null) }

    fun exportLogs() {
        if (mutable.value.isExporting) return
        mutable.update { it.copy(isExporting = true) }
        env.scope.launch {
            try {
                val file = port.createExportFile()
                mutable.update {
                    if (file != null) it.copy(exportedFile = file)
                    else it.copy(errorMessage = UiText.Resource(AppSettingsStrings.diagnosticsErrorExportFailed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(errorMessage = UiText.Resource(AppSettingsStrings.diagnosticsErrorExportFailed)) }
            } finally {
                mutable.update { it.copy(isExporting = false) }
            }
        }
    }

    fun confirmClearLogs() {
        mutable.update { it.copy(showingClearLogsAlert = false) }
        env.scope.launch {
            try {
                port.clearDebugLogs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(errorMessage = env.describe(error)) }
            }
        }
    }
}
