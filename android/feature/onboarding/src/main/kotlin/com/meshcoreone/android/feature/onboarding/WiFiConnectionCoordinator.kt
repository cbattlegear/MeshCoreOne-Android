// PortedFrom: MC1/Views/Onboarding/WiFiConnectionSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.WiFiAddressValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WiFiSheetState(
    val ipAddress: String = "",
    val port: String = DEFAULT_PORT,
    val isConnecting: Boolean = false,
    val error: UiText? = null,
) {
    val isValidInput: Boolean get() = WiFiAddressValidation.isValidHost(ipAddress) && WiFiAddressValidation.isValidPort(port)
    val canConnect: Boolean get() = isValidInput && !isConnecting

    companion object { const val DEFAULT_PORT = "5000" }
}

class WiFiConnectionCoordinator(
    private val scope: CoroutineScope,
    private val wifi: OnboardingWiFiPort,
    private val onConnected: () -> Unit,
) {
    private val mutable = MutableStateFlow(WiFiSheetState())
    val state: StateFlow<WiFiSheetState> = mutable.asStateFlow()

    fun setAddress(value: String) = mutable.update { it.copy(ipAddress = value) }
    fun setPort(value: String) = mutable.update { it.copy(port = value) }

    /** Fresh sheet each time it opens; an in-flight connect keeps its state. */
    fun reset() { if (!mutable.value.isConnecting) mutable.value = WiFiSheetState() }

    fun connect() {
        val current = mutable.value
        if (current.isConnecting) return
        val portNumber = current.port.toIntOrNull()?.takeIf { it in 0..65535 }
        if (portNumber == null) {
            mutable.update { it.copy(error = UiText.Resource(O.wifiConnectionErrorInvalidPort)) }
            return
        }
        mutable.update { it.copy(isConnecting = true, error = null) }
        scope.launch {
            val outcome = try {
                wifi.connect(WiFiAddressValidation.normalizedHost(current.ipAddress), portNumber)
            } catch (cancel: CancellationException) {
                mutable.update { it.copy(isConnecting = false) }
                throw cancel
            } catch (failure: Exception) {
                OnboardingConnectOutcome.Failed(UiText.Verbatim(failure.message.orEmpty()))
            }
            when (outcome) {
                OnboardingConnectOutcome.Connected -> {
                    mutable.update { it.copy(isConnecting = false) }
                    onConnected()
                }
                is OnboardingConnectOutcome.Failed -> mutable.update { it.copy(isConnecting = false, error = outcome.message) }
                else -> mutable.update { it.copy(isConnecting = false) }
            }
        }
    }
}
