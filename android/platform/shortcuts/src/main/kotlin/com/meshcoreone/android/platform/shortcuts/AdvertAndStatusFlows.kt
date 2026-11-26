// PortedFrom: MC1/Intents/SendAdvertIntent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Intents/StatusQueryIntent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState

sealed interface AdvertResult {
    data class Sent(val message: String) : AdvertResult
    data object Declined : AdvertResult
}

/**
 * Advert flow. Zero-hop (the default, direct neighbours only) runs from the user's own shortcut
 * tap; a flood advert propagates across the whole mesh so it requires explicit confirmation.
 */
class AdvertFlow(
    private val radio: ShortcutRadioPort,
    private val confirmation: ShortcutConfirmationPort,
    private val text: ShortcutText,
) {
    suspend fun execute(reach: AdvertReach): AdvertResult {
        if (!radio.connectionState.isOperational) throw ShortcutException(ShortcutError.NOT_CONNECTED)
        if (reach.sendsFlood && !confirmation.confirm(ConfirmationRequest.SendAdvert(reach, text.advertConfirm(reach)))) {
            return AdvertResult.Declined
        }
        if (!radio.connectionState.isOperational) throw ShortcutException(ShortcutError.NOT_CONNECTED)
        try {
            radio.sendAdvert(reach.sendsFlood)
        } catch (error: ShortcutException) {
            throw error
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ShortcutException(ShortcutError.ADVERT_FAILED, error)
        }
        return AdvertResult.Sent(text.advertSent(reach))
    }
}

/** Read-only, cached-only status line; it never touches the radio and never sends anything. */
object StatusFlow {
    fun dialog(status: RadioStatusSnapshot?, text: ShortcutText): String {
        if (status == null) return text.statusNotReady
        val state = status.connectionState
        if (state.isConnected) {
            val name = status.connectedNodeName ?: status.lastConnectedName ?: text.statusRadioFallbackName
            val percent = status.batteryPercent ?: return text.statusConnectedNoBattery(name)
            return text.statusConnectedWithBattery(name, percent)
        }
        if (state == DeviceConnectionState.CONNECTING) {
            return text.statusConnecting(status.lastConnectedName ?: text.statusRadioFallbackName)
        }
        val name = status.lastConnectedName ?: return text.statusDisconnectedUnknown
        return text.statusDisconnectedNamed(name)
    }
}
