// PortedFrom: MC1/Views/PathEditing/HopCodeParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/PathEditing/HopCodeClassification.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/PathEditing/CodeInputResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of WP-311's bulk hop-code parser; trace owns the uncapped caller and the
// power-of-two width inference (TracePathViewModel.inferredTraceHashMode).
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.protocol.bytes.Bytes

/** Outcome of one parsed code. */
sealed interface HopCodeStatus {
    /** Valid, resolvable and within the cap; carries the prebuilt hop. */
    data class WillAdd(val hop: TracePathHop) : HopCodeStatus
    data object AlreadyInPath : HopCodeStatus
    data object NotFound : HopCodeStatus
    data object InvalidFormat : HopCodeStatus
    data object PathFull : HopCodeStatus
}

/** One de-duplicated, uppercased code and its status. */
data class HopCodeClassification(val code: String, val status: HopCodeStatus) {
    val id: String get() = code
    val willBeAdded: Boolean get() = status is HopCodeStatus.WillAdd
}

/** Full public key and display name a hash prefix resolved to. */
data class ResolvedHopCode(val publicKey: Bytes, val name: String?)

/** Result of adding comma-separated codes. Immutable; built with [plus] helpers. */
data class CodeInputResult(
    val added: List<String> = emptyList(),
    val notFound: List<String> = emptyList(),
    val alreadyInPath: List<String> = emptyList(),
    val invalidFormat: List<String> = emptyList(),
) {
    val hasErrors: Boolean get() = notFound.isNotEmpty() || alreadyInPath.isNotEmpty() || invalidFormat.isNotEmpty()

    /** Invalid, then not-found, then already-in-path, joined with `" · "`; `null` without errors. */
    fun errorMessage(strings: TracePathStrings): String? {
        if (!hasErrors) return null
        val parts = ArrayList<String>()
        if (invalidFormat.isNotEmpty()) parts += strings.codeInvalidFormat(invalidFormat.joinToString(", "))
        if (notFound.isNotEmpty()) parts += strings.codeNotFound(notFound.joinToString(", "))
        if (alreadyInPath.isNotEmpty()) parts += strings.codeAlreadyInPath(alreadyInPath.joinToString(", "))
        return parts.joinToString(" · ")
    }
}

object HopCodeParser {
    /**
     * Classify each code without mutating any path. A code must be exactly `hashSize * 2` ASCII hex
     * digits; codes are trimmed of `.whitespaces`, uppercased and de-duplicated in input order.
     */
    fun classify(
        input: String,
        hashSize: Int,
        existingHashes: Set<Bytes>,
        remainingCapacity: Int?,
        resolve: (Bytes) -> ResolvedHopCode?,
    ): List<HopCodeClassification> {
        val seen = HashSet<String>()
        val uniqueCodes = SwiftText.splitOnComma(input)
            .map { SwiftText.uppercased(SwiftText.trimWhitespaces(it)) }
            .filter { it.isNotEmpty() }
            .filter { seen.add(SwiftText.canonicalKey(it)) }
        val pathHashes = HashSet(existingHashes)
        var added = 0
        return uniqueCodes.map { code ->
            val hashData = parseHex(code, hashSize)
            when {
                hashData == null -> HopCodeClassification(code, HopCodeStatus.InvalidFormat)
                hashData in pathHashes -> HopCodeClassification(code, HopCodeStatus.AlreadyInPath)
                else -> {
                    val resolved = resolve(hashData)
                    when {
                        resolved == null -> HopCodeClassification(code, HopCodeStatus.NotFound)
                        remainingCapacity != null && added >= remainingCapacity ->
                            HopCodeClassification(code, HopCodeStatus.PathFull)
                        else -> {
                            added += 1
                            pathHashes += hashData
                            val hop = TracePathHop(hashData, resolved.publicKey, resolved.name)
                            HopCodeClassification(code, HopCodeStatus.WillAdd(hop))
                        }
                    }
                }
            }
        }
    }

    /** `UInt8(_, radix: 16)` per Character pair; fullwidth digits pass `isHexDigit` but fail the parse. */
    private fun parseHex(code: String, hashSize: Int): Bytes? {
        val characters = SwiftText.characters(code)
        if (characters.size.toLong() != hashSize.toLong() * 2 || !characters.all(SwiftText::isHexDigit)) return null
        val bytes = ByteArray(hashSize)
        for (index in 0 until hashSize) {
            val high = SwiftText.asciiHexValue(characters[index * 2].codePointAt(0)) ?: return null
            val low = SwiftText.asciiHexValue(characters[index * 2 + 1].codePointAt(0)) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return Bytes(bytes)
    }
}

object TraceHashModes {
    /** Trace hop widths the firmware accepts, in bytes (power-of-two encoding). */
    private val validTraceHashSizes = setOf(1, 2, 4)

    /**
     * The single trace hash mode (0/1/2 for 1/2/4 bytes) implied by a comma-separated paste, or
     * `null` for empty, non-hex, odd-length, mixed-width or non-power-of-two input. Fullwidth hex
     * digits count here, as Swift `Character.isHexDigit` does.
     */
    fun inferredTraceHashMode(input: String): UByte? {
        val tokens = SwiftText.splitOnComma(input).map(SwiftText::trimWhitespaces).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var width: Int? = null
        for (token in tokens) {
            val characters = SwiftText.characters(token)
            if (characters.size % 2 != 0 || !characters.all(SwiftText::isHexDigit)) return null
            val bytes = characters.size / 2
            if (width != null && width != bytes) return null
            width = bytes
        }
        val resolved = width ?: return null
        if (resolved !in validTraceHashSizes) return null
        return Integer.numberOfTrailingZeros(resolved).toUByte()
    }

    /**
     * `1 << mode`. Firmware defines modes 0..2; a mode of 31 or more (where Swift would overshift
     * and later trap on a zero stride) saturates instead, so chunking never strides by zero.
     */
    fun hashSize(mode: UByte): Int {
        val shift = mode.toInt()
        return if (shift >= 31) Int.MAX_VALUE else 1 shl shift
    }
}
