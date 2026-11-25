// PortedFrom: MC1/Views/Map/MapAppearance.swift@5e980a53e077c45a1270170612fdf5dae97792ff
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
