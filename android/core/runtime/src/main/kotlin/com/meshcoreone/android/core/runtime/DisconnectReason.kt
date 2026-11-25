// PortedFrom: MC1Services/Sources/MC1Services/Connection/DisconnectReason.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

enum class RuntimeDisconnectReason(val rawValue: String, val clearsIntent: Boolean) {
    USER_INITIATED("user initiated disconnect", true),
    STATUS_MENU_DISCONNECT_TAP("status menu disconnect tapped", true),
    SWITCHING_DEVICE("switching to new device", true),
    FACTORY_RESET("device factory reset", true),
    WIFI_ADDRESS_CHANGE("WiFi address changed", false),
    RESYNC_FAILED("resync failed after 3 attempts", false),
    FORGET_DEVICE("user forgot device", true),
    DEVICE_REMOVED_FROM_SETTINGS("device removed from iOS Settings", true),
    PAIRING_FAILED("device pairing failed", false),
    WIFI_RECONNECT_PREP("preparing for WiFi reconnect", false),
}
