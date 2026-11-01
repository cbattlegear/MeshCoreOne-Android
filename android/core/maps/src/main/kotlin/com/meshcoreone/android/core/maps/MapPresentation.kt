// AndroidOnly: WP-312 Provider-neutral lifecycle and UI state seam shared by the approved MapLibre adapter.
package com.meshcoreone.android.core.maps

import kotlinx.coroutines.flow.StateFlow

sealed interface MapAvailability {
    data object ProviderApprovalRequired : MapAvailability
    data class Available(val catalog: MapProviderCatalog) : MapAvailability
    data class Failed(val issue: MapIssue) : MapAvailability
}

sealed interface MapIssue {
    data object NetworkUnavailable : MapIssue
    data object LocationPermissionDenied : MapIssue
    data object SnapshotUnsupported : MapIssue
    data object OfflineRegionUnsupported : MapIssue
    data class ProviderFailure(val code: String) : MapIssue
}

data class MapPresentationState(
    val availability: MapAvailability = MapAvailability.ProviderApprovalRequired,
    val style: MapStyle = MapStyle.STANDARD,
    val camera: MapCamera? = null,
    val points: List<MapMarker> = emptyList(),
    val lines: List<MapLine> = emptyList(),
    val isOffline: Boolean = false,
    val isInteractive: Boolean = true,
    val showsUserLocation: Boolean = false,
    val issue: MapIssue? = null,
    val isDark: Boolean = false,
) {
    val canRender: Boolean get() = availability is MapAvailability.Available
    val attribution: List<MapProviderAttribution>
        get() = (availability as? MapAvailability.Available)
            ?.catalog
            ?.layers
            ?.get(style)
            ?.attribution
            .orEmpty()
}

interface SharedMapController {
    val state: StateFlow<MapPresentationState>
    fun setCamera(camera: MapCamera)
    fun setStyle(style: MapStyle)
    fun setOffline(offline: Boolean)
    fun replaceContent(points: List<MapMarker>, lines: List<MapLine>)
    fun onStart()
    fun onStop()
}

interface SharedMapAdapter {
    val availability: StateFlow<MapAvailability>
    fun onStart()
    fun onStop()
}
