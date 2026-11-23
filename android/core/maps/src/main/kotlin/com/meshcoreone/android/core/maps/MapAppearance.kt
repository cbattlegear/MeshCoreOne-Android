// PortedFrom: MC1/Views/Map/MapAppearance.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import androidx.compose.runtime.Composable
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme

enum class MapColorPreference(val rawValue: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromRaw(rawValue: String): MapColorPreference =
            entries.firstOrNull { it.rawValue == rawValue } ?: run {
                java.util.logging.Logger.getLogger("com.meshcoreone.android.MapPreferences")
                    .warning("Unknown map appearance value; using system without rewriting stored data")
                SYSTEM
            }
    }
}

@Composable
fun effectiveAppThemeIsDark(): Boolean =
    LocalMeshTheme.current.frame.colorScheme == ColorScheme.DARK

fun resolvedMapIsDark(preference: MapColorPreference, systemIsDark: Boolean): Boolean =
    when (preference) {
        MapColorPreference.SYSTEM -> systemIsDark
        MapColorPreference.LIGHT -> false
        MapColorPreference.DARK -> true
    }
