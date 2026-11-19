// PortedFrom: MC1/Views/Settings/Sections/DefaultFloodScopeSection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiText
import java.math.BigInteger
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DefaultFloodScopeState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val isApplying: Boolean = false,
    val isDiscovering: Boolean = false,
    val discoveryMessageId: Int? = null,
    val errorMessage: UiText? = null,
) {
    /** The scope the radio holds now; null is "Disabled" (unscoped). */
    val currentScope: String? get() = device?.defaultFloodScopeName
    val sortedKnownRegions: List<String> get() = DefaultFloodScopeStateHolder.sortStandard(device?.knownRegions?.toList().orEmpty())
    val enabled: Boolean get() = connectionState == DeviceConnectionState.READY && !isApplying
}

/** Default flood scope picker (firmware v11+): Disabled or one of the radio's known regions, plus region discovery. */
class DefaultFloodScopeStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
    private val discovery: RegionDiscoveryPort,
    private val deviceStore: DeviceSettingsStorePort,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(DefaultFloodScopeState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    private var discoveryJob: Job? = null
    val state: StateFlow<DefaultFloodScopeState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.connectedDevice.collect { d -> mutable.update { it.copy(device = d) } }
        }
    }

    /** `.onDisappear`: leaving the section cancels a running discovery. */
    fun stop() {
        observing?.cancel()
        observing = null
        discoveryJob?.cancel()
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    fun select(name: String?) {
        mutable.update { it.copy(isApplying = true) }
        env.scope.launch {
            try {
                val service = settingsService() ?: throw SettingsNotConnectedException()
                service.setDefaultFloodScopeVerified(name)
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isApplying = false) }
                throw cancelled
            } catch (error: Exception) {
                val message = failures.route(error) { select(name) }
                mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
            }
            mutable.update { it.copy(isApplying = false) }
        }
    }

    fun discover() {
        discoveryJob?.cancel()
        discoveryJob = env.scope.launch {
            mutable.update { it.copy(isDiscovering = true, discoveryMessageId = null) }
            try {
                val device = connection.connectedDevice.value ?: return@launch
                val outcome = discovery.discover(device.knownRegions.toList(), device.supportsAdHocRepeaterRequest)
                reportDiscovery(outcome)
            } finally {
                mutable.update { it.copy(isDiscovering = false) }
            }
        }
    }

    private fun reportDiscovery(outcome: RegionDiscoveryOutcome) {
        when (outcome) {
            RegionDiscoveryOutcome.SendFailed -> Unit
            RegionDiscoveryOutcome.NoRepeatersResponded ->
                mutable.update { it.copy(discoveryMessageId = R.string.l10n_app_chats_chats_channelinfo_region_norepeatersresponded) }
            RegionDiscoveryOutcome.ErrorLoadingRepeaters ->
                mutable.update { it.copy(discoveryMessageId = R.string.l10n_app_chats_chats_channelinfo_region_errloadingrepeaters) }
            is RegionDiscoveryOutcome.Completed -> when {
                outcome.newRegions.isEmpty() && outcome.allRepeatersTableFull ->
                    mutable.update { it.copy(discoveryMessageId = R.string.l10n_app_chats_chats_channelinfo_region_errradiocontactsfull) }
                outcome.newRegions.isEmpty() ->
                    mutable.update { it.copy(discoveryMessageId = R.string.l10n_app_chats_chats_channelinfo_region_nonewregions) }
                else -> outcome.newRegions.forEach(deviceStore::addKnownRegion)
            }
        }
    }

    companion object {
        /**
         * `localizedStandardCompare`: digit runs compare as numbers, other text with the locale collator at tertiary
         * strength, so "zone 2" sorts before "zone 10" and "e" before "é" (oracle.swift.txt).
         */
        fun sortStandard(names: List<String>, locale: Locale = Locale.getDefault()): List<String> {
            val collator = Collator.getInstance(locale).apply { strength = Collator.TERTIARY }
            val tokens = Regex("[0-9]+|[^0-9]+")
            return names.sortedWith { first, second ->
                val left = tokens.findAll(first).map { it.value }.toList()
                val right = tokens.findAll(second).map { it.value }.toList()
                var result = 0
                for (index in 0 until minOf(left.size, right.size)) {
                    val a = left[index]
                    val b = right[index]
                    result = if (a[0].isDigit() && b[0].isDigit()) BigInteger(a).compareTo(BigInteger(b)) else collator.compare(a, b)
                    if (result != 0) break
                }
                if (result == 0) left.size.compareTo(right.size) else result
            }
        }
    }
}
