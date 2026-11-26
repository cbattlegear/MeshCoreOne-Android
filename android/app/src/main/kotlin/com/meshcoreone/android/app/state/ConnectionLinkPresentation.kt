// AndroidOnly: WP-206 Same-radio link choices and captured switch state compared with current upstream.
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.ui.WiFiAddressValidation
import java.util.UUID

internal data class ConnectionLinkWiFiDial(val host: String, val port: UShort) {
    val displayHost: String? get() = WiFiAddressValidation.normalizedHost(host).takeIf { it.isNotEmpty() }
    val canDial: Boolean get() = displayHost != null

    companion object {
        fun capture(
            live: TransportType?,
            saved: ConnectionMethod.WiFi?,
            reportedHost: String?,
        ): ConnectionLinkWiFiDial? {
            val savedDial = saved?.let {
                val host = WiFiAddressValidation.normalizedHost(it.host)
                if (WiFiAddressValidation.isValidHost(host) && WiFiAddressValidation.isValidPort(it.port.toString())) {
                    ConnectionLinkWiFiDial(host, it.port)
                } else null
            }
            if (live == TransportType.WIFI) return savedDial
            if (savedDial != null) return savedDial
            val host = reportedHost?.let(WiFiAddressValidation::normalizedHost) ?: return null
            return if (WiFiAddressValidation.isValidHost(host)) ConnectionLinkWiFiDial(host, 5000u) else null
        }
    }
}

internal data class ConnectionLinkSwitch(
    val radioId: RadioId,
    val deviceId: UUID,
    val checked: TransportType,
    val target: TransportType?,
    val token: UUID,
    val bluetoothDeviceId: UUID?,
    val wifi: ConnectionLinkWiFiDial?,
) {
    sealed interface Completion {
        data object Ignored : Completion
        data object Cleared : Completion
        data class Held(val record: ConnectionLinkSwitch) : Completion
    }

    fun complete(token: UUID, threw: Boolean, live: TransportType?, connectedRadioId: RadioId?): Completion {
        if (token != this.token || target == null) return Completion.Ignored
        if (!threw && live == target && connectedRadioId == radioId) return Completion.Cleared
        if (connectedRadioId != null && connectedRadioId != radioId) return Completion.Cleared
        return Completion.Held(copy(target = null))
    }

    companion object {
        fun start(
            existing: ConnectionLinkSwitch?,
            target: TransportType,
            live: TransportType?,
            radioId: RadioId?,
            deviceId: UUID?,
            bluetoothDeviceId: UUID?,
            wifi: ConnectionLinkWiFiDial?,
            token: UUID,
        ): ConnectionLinkSwitch? {
            if (existing?.target != null || live == target ||
                target == TransportType.SIMULATOR || live == TransportType.SIMULATOR ||
                (existing != null && radioId != null && existing.radioId != radioId)
            ) return null
            val base = existing ?: ConnectionLinkSwitch(
                radioId ?: return null, deviceId ?: return null, live ?: return null,
                null, token, bluetoothDeviceId, wifi,
            )
            val canDial = when (target) {
                TransportType.BLUETOOTH -> base.bluetoothDeviceId != null
                TransportType.WIFI -> base.wifi?.canDial == true
                TransportType.SIMULATOR -> false
            }
            return if (canDial) base.copy(target = target, token = token) else null
        }
    }
}

internal object ConnectionLinkRows {
    enum class WiFiSupport { UNKNOWN, SUPPORTED, UNAVAILABLE }
    data class Row(val link: TransportType, val address: String?, val canSwitch: Boolean)
    data class Snapshot(val rows: List<Row>, val checked: TransportType?, val target: TransportType?)

    fun make(
        live: TransportType?,
        bluetoothDeviceId: UUID?,
        wifiSupport: WiFiSupport,
        wifiAssociated: Boolean,
        wifi: ConnectionLinkWiFiDial?,
        switch: ConnectionLinkSwitch?,
    ): Snapshot {
        if (switch != null) {
            val rows = buildList {
                if (switch.checked == TransportType.BLUETOOTH || switch.bluetoothDeviceId != null ||
                    switch.target == TransportType.BLUETOOTH
                ) add(Row(TransportType.BLUETOOTH, null, switch.bluetoothDeviceId != null && live != TransportType.BLUETOOTH))
                if (switch.checked == TransportType.WIFI || switch.wifi != null || switch.target == TransportType.WIFI) {
                    add(Row(TransportType.WIFI, switch.wifi?.displayHost, switch.wifi?.canDial == true && live != TransportType.WIFI))
                }
            }
            return Snapshot(rows, switch.checked, switch.target)
        }
        val rows = buildList {
            if (live == TransportType.BLUETOOTH || bluetoothDeviceId != null) {
                add(Row(TransportType.BLUETOOTH, null, bluetoothDeviceId != null && live != TransportType.BLUETOOTH))
            }
            if (live == TransportType.WIFI || wifiSupport == WiFiSupport.SUPPORTED) {
                add(Row(TransportType.WIFI, wifi?.displayHost, live != TransportType.WIFI && wifiAssociated && wifi?.canDial == true))
            }
        }
        return Snapshot(rows, live, null)
    }
}
