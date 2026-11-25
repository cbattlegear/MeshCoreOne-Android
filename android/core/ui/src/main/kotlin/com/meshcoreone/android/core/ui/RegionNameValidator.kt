// PortedFrom: MC1/Views/Components/RegionNameValidator.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.model.ProtocolLimits

sealed interface RegionValidationError {
    data object Empty : RegionValidationError
    data object InvalidCharacters : RegionValidationError
    data class TooLong(val maxBytes: Int) : RegionValidationError
    data object Duplicate : RegionValidationError
}

object RegionNameValidator {
    fun normalized(name: String): String = name.trim {
        it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt()
    }

    fun validate(name: String, existingRegions: Collection<String>): RegionValidationError? {
        val trimmed = normalized(name)
        if (trimmed.isEmpty()) return RegionValidationError.Empty
        if (trimmed.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' }) {
            return RegionValidationError.InvalidCharacters
        }
        val maximum = ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES
        if (trimmed.toByteArray(Charsets.UTF_8).size > maximum) return RegionValidationError.TooLong(maximum)
        if (trimmed in existingRegions) return RegionValidationError.Duplicate
        return null
    }
}

val String.isPrivateRegion: Boolean get() = startsWith("$")
