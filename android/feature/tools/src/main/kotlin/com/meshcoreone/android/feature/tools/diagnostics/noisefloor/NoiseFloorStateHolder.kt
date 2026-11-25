// PortedFrom: MC1/Views/Tools/NoiseFloorViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.noisefloor

import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.protocol.event.RadioStats
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsClock
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsText
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The `MeshCoreSession.getStatsRadio()` slice the noise-floor tool polls (WP-303 adapts the session). */
fun interface RadioStatsSource {
    suspend fun getStatsRadio(): RadioStats
}

/** Immutable noise-floor screen state; [statistics] is recomputed with every append. */
data class NoiseFloorState(
    val currentReading: NoiseFloorReading? = null,
    val readings: List<NoiseFloorReading> = emptyList(),
    val isPolling: Boolean = false,
    val errorMessage: String? = null,
    val statistics: NoiseFloorStatistics? = null,
) {
    val qualityLevel: NoiseFloorQuality
        get() = currentReading?.let { NoiseFloorQuality.from(it.noiseFloor) } ?: NoiseFloorQuality.UNKNOWN
}

/**
 * Polls radio stats every 1.5 s while the screen is visible (Swift `NoiseFloorViewModel`). The
 * session provider is re-read on each tick so a disconnect mid-poll surfaces immediately.
 * Confined to [scope]'s single-threaded dispatcher.
 */
class NoiseFloorStateHolder(
    private val scope: CoroutineScope,
    private val text: DiagnosticsText,
    private val clock: DiagnosticsClock = DiagnosticsClock.SYSTEM,
) {
    companion object {
        const val MAX_READINGS = 200
        val POLLING_INTERVAL: Duration = 1500.milliseconds
    }

    private val mutableState = MutableStateFlow(NoiseFloorState())
    val state: StateFlow<NoiseFloorState> = mutableState.asStateFlow()
    private var sessionProvider: () -> RadioStatsSource? = { null }
    private var pollingJob: Job? = null

    fun appendReading(reading: NoiseFloorReading) = mutableState.update { current ->
        val readings = (current.readings + reading).takeLast(MAX_READINGS)
        current.copy(
            currentReading = reading,
            readings = readings,
            statistics = NoiseFloorStatistics.of(readings),
            errorMessage = null,
        )
    }

    fun startPolling(sessionProvider: () -> RadioStatsSource?) {
        this.sessionProvider = sessionProvider
        if (pollingJob != null) return
        mutableState.update { it.copy(isPolling = true) }
        pollingJob = scope.launch {
            while (true) {
                fetchReading()
                clock.sleep(POLLING_INTERVAL)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        mutableState.update { it.copy(isPolling = false) }
    }

    private suspend fun fetchReading() {
        val session = sessionProvider() ?: run {
            mutableState.update { it.copy(errorMessage = text.string(AppToolsStrings.toolsNoiseFloorErrorDisconnected)) }
            return
        }
        val stats = try {
            session.getStatsRadio()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = text.string(AppToolsStrings.toolsNoiseFloorErrorUnableToRead)) }
            return
        }
        appendReading(NoiseFloorReading(UUID.randomUUID(), clock.now(), stats.noiseFloor, stats.lastRSSI, stats.lastSNR))
    }
}
