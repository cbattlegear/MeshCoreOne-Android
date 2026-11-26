// PortedFrom: MC1/Views/Map/MapFilter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror pending WP-312's core:maps MapFilterHost/MapFilterState. The UserDefaults
// preference plumbing (storageKey, Codable storage string, MapFilterPreferences) stays with WP-312.
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.protocol.model.ContactType

/** A filter control a map surface offers (Swift `MapFilterCapabilities` option). */
enum class MapFilterCapability {
    FAVORITES, DISCOVERED, CHAT, REPEATER, ROOM;

    companion object {
        val ALL: Set<MapFilterCapability> = entries.toSet()
        val TYPES: Set<MapFilterCapability> = setOf(CHAT, REPEATER, ROOM)
    }
}

/** Map surface that owns an independent filter preference (Swift `MapFilterHost`). */
enum class MapFilterHost(val capabilities: Set<MapFilterCapability>) {
    MAIN_MAP(MapFilterCapability.ALL),
    TRACE_PATH(setOf(MapFilterCapability.FAVORITES, MapFilterCapability.DISCOVERED)),
    NEIGHBOR_SNR(setOf(MapFilterCapability.FAVORITES, MapFilterCapability.DISCOVERED)),
    ;

    val includesTypes: Boolean get() = capabilities.any { it in MapFilterCapability.TYPES }
}

/**
 * Swift `MapFilterState`. Swift exposes the fields `private(set)` with mutating setters; here every
 * `with...` setter returns a new state and applies the same freeze rules (discovered and type toggles
 * freeze under Favorites; the last enabled type cannot be switched off).
 */
data class MapFilterState(
    val favoritesOnly: Boolean = false,
    val showDiscovered: Boolean = false,
    val showChat: Boolean = true,
    val showRepeater: Boolean = true,
    val showRoom: Boolean = true,
) {
    /** Discovered layer for pin algebra (the stored field stays frozen under Favorites). */
    val effectiveShowDiscovered: Boolean get() = !favoritesOnly && showDiscovered

    private val hasAtLeastOneEnabledType: Boolean get() = showChat || showRepeater || showRoom

    /** True when this state differs from the host seed on a control the host offers. */
    fun differsFromSeed(host: MapFilterHost): Boolean {
        val caps = host.capabilities
        val defaults = seed(host)
        return (MapFilterCapability.FAVORITES in caps && favoritesOnly != defaults.favoritesOnly) ||
            (MapFilterCapability.DISCOVERED in caps && showDiscovered != defaults.showDiscovered) ||
            (MapFilterCapability.CHAT in caps && showChat != defaults.showChat) ||
            (MapFilterCapability.REPEATER in caps && showRepeater != defaults.showRepeater) ||
            (MapFilterCapability.ROOM in caps && showRoom != defaults.showRoom)
    }

    fun withFavoritesOnly(value: Boolean): MapFilterState = copy(favoritesOnly = value)

    /** No-op while Favorites is on. */
    fun withShowDiscovered(value: Boolean): MapFilterState = if (favoritesOnly) this else copy(showDiscovered = value)

    fun withShowChat(value: Boolean, host: MapFilterHost): MapFilterState =
        withType(host) { copy(showChat = value) }

    fun withShowRepeater(value: Boolean, host: MapFilterHost): MapFilterState =
        withType(host) { copy(showRepeater = value) }

    fun withShowRoom(value: Boolean, host: MapFilterHost): MapFilterState =
        withType(host) { copy(showRoom = value) }

    private inline fun withType(host: MapFilterHost, change: MapFilterState.() -> MapFilterState): MapFilterState {
        if (favoritesOnly || !host.includesTypes) return this
        val candidate = change()
        return if (candidate.hasAtLeastOneEnabledType) candidate else this
    }

    /** Main Map type-axis gate. Favorites bypasses type filtering. */
    fun allowsContactType(type: ContactType): Boolean = favoritesOnly || when (type) {
        ContactType.CHAT -> showChat
        ContactType.REPEATER -> showRepeater
        ContactType.ROOM -> showRoom
        ContactType.SENSOR, ContactType.UNKNOWN -> false
    }

    /** Re-enables every type when a type-capable host would otherwise show none. */
    fun sanitized(host: MapFilterHost): MapFilterState =
        if (host.includesTypes && !hasAtLeastOneEnabledType) {
            copy(showChat = true, showRepeater = true, showRoom = true)
        } else {
            this
        }

    companion object {
        fun seed(host: MapFilterHost): MapFilterState = when (host) {
            MapFilterHost.TRACE_PATH -> MapFilterState(showDiscovered = true)
            MapFilterHost.MAIN_MAP, MapFilterHost.NEIGHBOR_SNR -> MapFilterState()
        }
    }
}
