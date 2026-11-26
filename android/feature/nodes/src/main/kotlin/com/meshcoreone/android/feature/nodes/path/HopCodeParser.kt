// PortedFrom: MC1/Views/PathEditing/HopCodeParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.hoppicker.SwiftText

/** A node a hash prefix resolves to: its full public key and display name. */
data class ResolvedHop(val publicKey: Bytes, val name: String?)

/**
 * Pure parser for the comma-separated hex-code bulk-add entry shared by the contact path editor and
 * the trace path builder; the single source of truth for both the preview and the actual add.
 */
object HopCodeParser {
    private const val SEPARATOR = ","
    private const val HEX_RADIX = 16

    /**
     * Classify each code in [input] without mutating any path. A code must be exactly
     * `hashSize * 2` hex digits; resolvable codes beyond [remainingCapacity] (null = unlimited)
     * become [HopCodeStatus.PathFull].
     */
    fun classify(
        input: String,
        hashSize: Int,
        existingHashes: Set<Bytes>,
        remainingCapacity: Int?,
        resolve: (Bytes) -> ResolvedHop?,
    ): List<HopCodeClassification> {
        val codes = split(input).map { SwiftText.uppercased(SwiftText.trimmingWhitespaces(it)) }.filter { it.isNotEmpty() }
        // Swift `Set<String>` de-duplicates canonically equivalent codes.
        val seen = HashSet<String>()
        val uniqueCodes = codes.filter { seen.add(SwiftText.canonical(it)) }
        val pathHashes = existingHashes.toHashSet()
        var added = 0
        return uniqueCodes.map { code ->
            val hashData = parseHex(code, hashSize) ?: return@map HopCodeClassification(code, HopCodeStatus.InvalidFormat)
            if (hashData in pathHashes) return@map HopCodeClassification(code, HopCodeStatus.AlreadyInPath)
            val resolved = resolve(hashData) ?: return@map HopCodeClassification(code, HopCodeStatus.NotFound)
            if (remainingCapacity != null && added >= remainingCapacity) {
                return@map HopCodeClassification(code, HopCodeStatus.PathFull)
            }
            added += 1
            pathHashes += hashData
            HopCodeClassification(code, HopCodeStatus.WillAdd(PathHop(hashData, resolved.publicKey, resolved.name)))
        }
    }

    /** `input.split(separator: ",")`: splits on whole `Character`s and omits empty pieces. */
    private fun split(input: String): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (character in SwiftText.characters(input)) {
            if (character == SEPARATOR) {
                if (current.isNotEmpty()) pieces += current.toString()
                current.setLength(0)
            } else {
                current.append(character)
            }
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    /** Parse [code] into exactly [hashSize] bytes; fullwidth digits pass the digit check but not `UInt8(_:radix:)`. */
    private fun parseHex(code: String, hashSize: Int): Bytes? {
        val characters = SwiftText.characters(code)
        if (characters.size != hashSize * 2 || !characters.all(SwiftText::isHexDigit)) return null
        val bytes = ByteArray(hashSize)
        for (index in 0 until hashSize) {
            val high = Character.digit(characters[index * 2].single().asciiOnly() ?: return null, HEX_RADIX)
            val low = Character.digit(characters[index * 2 + 1].single().asciiOnly() ?: return null, HEX_RADIX)
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return Bytes(bytes)
    }

    private fun Char.asciiOnly(): Char? = takeIf { it.code < 0x80 }
}
