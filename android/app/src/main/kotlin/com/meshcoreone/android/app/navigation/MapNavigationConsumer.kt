// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: Extracted map-callback forwarding only, not a ChatViewModel or completed chat feature.
package com.meshcoreone.android.app.navigation

enum class MapNavigationOutcome { FORWARDED, UNAVAILABLE }

class MapNavigationConsumer {
    var onNavigateToMap: ((MapFocusRequest) -> Unit)? = null

    fun navigateToMap(coordinate: MapFocusRequest): MapNavigationOutcome {
        val sink = onNavigateToMap ?: return MapNavigationOutcome.UNAVAILABLE
        sink(coordinate)
        return MapNavigationOutcome.FORWARDED
    }
}
