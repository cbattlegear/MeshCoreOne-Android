// PortedFrom: MC1/Views/Map/MapFilter.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader

enum class MapFilterHost { MAIN_MAP, TRACE_PATH, NEIGHBOR_SNR }

enum class ContactMapType { CHAT, REPEATER, ROOM }

enum class MapFilterCapability { FAVORITES, DISCOVERED, CHAT, REPEATER, ROOM }

val MapFilterHost.capabilities: Set<MapFilterCapability>
    get() = when (this) {
        MapFilterHost.MAIN_MAP -> MapFilterCapability.entries.toSet()
        MapFilterHost.TRACE_PATH, MapFilterHost.NEIGHBOR_SNR ->
            setOf(MapFilterCapability.FAVORITES, MapFilterCapability.DISCOVERED)
    }

data class MapFilterState(
    val favoritesOnly: Boolean = false,
    val showDiscovered: Boolean = false,
    val showChat: Boolean = true,
    val showRepeater: Boolean = true,
    val showRoom: Boolean = true,
) {
    val effectiveShowDiscovered: Boolean get() = !favoritesOnly && showDiscovered

    fun differsFromSeed(host: MapFilterHost): Boolean {
        val defaults = seed(host)
        val capabilities = host.capabilities
        return (MapFilterCapability.FAVORITES in capabilities && favoritesOnly != defaults.favoritesOnly) ||
            (MapFilterCapability.DISCOVERED in capabilities && showDiscovered != defaults.showDiscovered) ||
            (MapFilterCapability.CHAT in capabilities && showChat != defaults.showChat) ||
            (MapFilterCapability.REPEATER in capabilities && showRepeater != defaults.showRepeater) ||
            (MapFilterCapability.ROOM in capabilities && showRoom != defaults.showRoom)
    }

    fun setFavoritesOnly(value: Boolean) = copy(favoritesOnly = value)
    fun setShowDiscovered(value: Boolean) =
        if (favoritesOnly) this else copy(showDiscovered = value)

    fun setContactType(type: ContactMapType, value: Boolean, host: MapFilterHost): MapFilterState {
        if (favoritesOnly || type.capability !in host.capabilities) return this
        val next = when (type) {
            ContactMapType.CHAT -> copy(showChat = value)
            ContactMapType.REPEATER -> copy(showRepeater = value)
            ContactMapType.ROOM -> copy(showRoom = value)
        }
        return if (next.showChat || next.showRepeater || next.showRoom) next else this
    }

    fun allows(type: ContactMapType): Boolean = favoritesOnly || when (type) {
        ContactMapType.CHAT -> showChat
        ContactMapType.REPEATER -> showRepeater
        ContactMapType.ROOM -> showRoom
    }

    fun sanitized(host: MapFilterHost): MapFilterState {
        val hasTypeCapabilities = host.capabilities.any {
            it == MapFilterCapability.CHAT ||
                it == MapFilterCapability.REPEATER ||
                it == MapFilterCapability.ROOM
        }
        if (!hasTypeCapabilities || showChat || showRepeater || showRoom) return this
        return copy(showChat = true, showRepeater = true, showRoom = true)
    }

    fun encode(): String =
        """{"favoritesOnly":$favoritesOnly,"showDiscovered":$showDiscovered,"showChat":$showChat,"showRepeater":$showRepeater,"showRoom":$showRoom}"""

    companion object {
        fun seed(host: MapFilterHost) =
            if (host == MapFilterHost.TRACE_PATH) MapFilterState(showDiscovered = true)
            else MapFilterState()

        fun decode(value: String): MapFilterState? {
            if (value.isBlank()) return null
            val fields = setOf("favoritesOnly", "showDiscovered", "showChat", "showRepeater", "showRoom")
            val booleans = mutableMapOf<String, Boolean>()
            try {
                JsonReader(StringReader(value)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name in fields) {
                            if (reader.peek() != JsonToken.BOOLEAN) return null
                            booleans[name] = reader.nextBoolean()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (reader.peek() != JsonToken.END_DOCUMENT) return null
                }
            } catch (_: IOException) {
                return null
            } catch (_: IllegalStateException) {
                return null
            }
            return MapFilterState(
                favoritesOnly = booleans["favoritesOnly"] ?: return null,
                showDiscovered = booleans["showDiscovered"] ?: return null,
                showChat = booleans["showChat"] ?: return null,
                showRepeater = booleans["showRepeater"] ?: return null,
                showRoom = booleans["showRoom"] ?: return null,
            )
        }
    }
}

private val ContactMapType.capability: MapFilterCapability
    get() = when (this) {
        ContactMapType.CHAT -> MapFilterCapability.CHAT
        ContactMapType.REPEATER -> MapFilterCapability.REPEATER
        ContactMapType.ROOM -> MapFilterCapability.ROOM
    }

object MapFilterPreferences {
    fun decodeOrSeed(raw: String, host: MapFilterHost): MapFilterState =
        (MapFilterState.decode(raw) ?: MapFilterState.seed(host)).sanitized(host)

    fun encode(state: MapFilterState, host: MapFilterHost): String =
        state.sanitized(host).encode()

    fun migrate(
        raw: String?,
        legacyShowDiscovered: Boolean?,
        host: MapFilterHost,
    ): MapFilterMigration {
        if (raw != null) {
            val decoded = MapFilterState.decode(raw)
            if (decoded == null) {
                val seed = MapFilterState.seed(host).sanitized(host)
                return MapFilterMigration(seed, seed.encode())
            }
            val sanitized = decoded.sanitized(host)
            return MapFilterMigration(sanitized, sanitized.encode().takeIf { sanitized != decoded })
        }
        val seed = MapFilterState.seed(host)
        if (host == MapFilterHost.MAIN_MAP && legacyShowDiscovered != null) {
            val migrated = seed.setShowDiscovered(legacyShowDiscovered).sanitized(host)
            return MapFilterMigration(migrated, migrated.encode())
        }
        return MapFilterMigration(seed.sanitized(host), null)
    }
}

data class MapFilterMigration(
    val state: MapFilterState,
    val replacement: String?,
)
