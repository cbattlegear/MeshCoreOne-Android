// PortedFrom: MC1/Views/Components/RegionNameValidator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror: WP-304 owns the original in core:ui (PR #33, unmerged). See docs/android/deviations/WP-313.md.
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText

/** Validates region names before they are added to a node's region list. */
object RegionNameValidator {
    sealed interface ValidationError {
        data object Empty : ValidationError
        data object InvalidCharacters : ValidationError
        data class TooLong(val maxBytes: Int) : ValidationError
        data object Duplicate : ValidationError
    }

    /**
     * Swift checks every `Character` is ASCII and a letter, number or "-"; for ASCII that is exactly
     * `[A-Za-z0-9-]` per UTF-16 unit (any non-ASCII scalar fails either way).
     */
    fun validate(name: String, existingRegions: List<String>): ValidationError? {
        val trimmed = RemoteSwiftText.trimWhitespaces(name)
        if (trimmed.isEmpty()) return ValidationError.Empty
        if (!trimmed.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' }) {
            return ValidationError.InvalidCharacters
        }
        val maxBytes = ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES
        if (trimmed.toByteArray(Charsets.UTF_8).size > maxBytes) return ValidationError.TooLong(maxBytes)
        if (existingRegions.contains(trimmed)) return ValidationError.Duplicate
        return null
    }

    fun isValid(name: String, existingRegions: List<String>): Boolean = validate(name, existingRegions) == null
}

/** Whether this region name represents a private region (prefixed with "$"). */
val String.isPrivateRegion: Boolean get() = startsWith("$")
