// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ContactShareUtilities.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.regex.Pattern

/**
 * Utilities for the MeshCore contact share token format: `<publicKeyHex:type:name>`.
 *
 * - `publicKeyHex` is the 32-byte public key as 64 hex characters (uppercase on emit,
 *   case-insensitive on parse).
 * - `type` is a [ContactType] raw value (1 chat, 2 repeater, 3 room).
 * - `name` is the node's advertised name. `>` is the reserved terminator and is stripped from the
 *   name on emit; the name may legitimately contain `:`.
 *
 * Field handling follows Swift `Character` (extended grapheme cluster) semantics: the terminator is
 * stripped, the delimiters dropped and the interior split per grapheme, so a `:` or `>` fused with a
 * combining mark is not a delimiter.
 */
object ContactShareUtilities {
    /** Marks the start of a share token. */
    private const val TOKEN_OPEN = "<"

    /** Marks the end of a share token and is reserved out of names. */
    private const val TOKEN_CLOSE = ">"

    /** Separates the public key, type, and name fields inside a token. */
    private const val FIELD_SEPARATOR = ":"

    /**
     * Splitting the interior keeps the public key and type as their own fields while letting the
     * name retain any internal `:` characters.
     */
    private const val INTERIOR_MAX_SPLITS = 2

    /** Number of fields a well-formed token interior splits into. */
    private const val EXPECTED_FIELD_COUNT = 3

    /** Regex pattern for a contact share token: `<64-hex:digits:name>`. */
    const val SHARE_TOKEN_PATTERN: String = "<[0-9a-fA-F]{64}:\\d+:[^>]+>"

    /**
     * Pre-compiled regex for token matching. `\d` must match any Unicode decimal digit, as ICU's
     * `NSRegularExpression` does. It is compiled as the explicit `\p{Nd}` rather than with
     * `UNICODE_CHARACTER_CLASS`, which is not portable to Android's ICU-backed `java.util.regex`.
     */
    val shareTokenRegex: Pattern = Pattern.compile(SHARE_TOKEN_PATTERN.replace("\\d", UNICODE_DIGIT))

    /** Unicode decimal digit, the same class on the JVM and on Android. */
    private const val UNICODE_DIGIT = "\\p{Nd}"

    private val graphemes: Pattern = Pattern.compile("\\X")

    /**
     * Formats a contact into a share token.
     * @param publicKey The contact's 32-byte public key (rendered as uppercase hex).
     * @param type The contact type.
     * @param name The contact's advertised name; any `>` characters are stripped.
     * @return A `<publicKeyHex:type:name>` token.
     */
    fun formatShare(publicKey: Bytes, type: ContactType, name: String): String {
        val sanitizedName = graphemesOf(name).filter { it != TOKEN_CLOSE }.joinToString("")
        return TOKEN_OPEN + publicKey.uppercaseHexString() + FIELD_SEPARATOR + type.rawValue.toString() +
            FIELD_SEPARATOR + sanitizedName + TOKEN_CLOSE
    }

    /**
     * Parses the first contact share token found in the input.
     * @return The recovered [ContactResult], or null if the first token is absent or invalid.
     */
    fun parseShare(token: String): ContactResult? {
        val matcher = shareTokenRegex.matcher(token)
        if (!matcher.find()) return null
        return contactResult(matcher.group())
    }

    /** Extracts every contact share token from the input text, in the order they appear. */
    fun extractShares(text: String): List<ContactResult> {
        val matcher = shareTokenRegex.matcher(text)
        val results = mutableListOf<ContactResult>()
        while (matcher.find()) {
            contactResult(matcher.group())?.let(results::add)
        }
        return results.toList()
    }

    /** Validates a regex-matched token (including delimiters) and builds a [ContactResult]. */
    private fun contactResult(matched: String): ContactResult? {
        val interior = graphemesOf(matched).drop(1).dropLast(1)
        val fields = splitFields(interior)
        if (fields.size != EXPECTED_FIELD_COUNT) return null

        val publicKey = applicationBytesFromHex(fields[0])
        if (publicKey == null || publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) return null

        val typeValue = parseSwiftInt(fields[1]) ?: return null
        if (typeValue !in 0L..UByte.MAX_VALUE.toLong()) return null
        val contactType = ContactType.fromRawValue(typeValue.toInt().toUByte()) ?: return null

        val name = fields[2]
        if (name.isEmpty()) return null

        return ContactResult(name, publicKey, contactType)
    }

    /** Swift `split(separator:maxSplits:omittingEmptySubsequences: false)` over graphemes. */
    private fun splitFields(interior: List<String>): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        for (grapheme in interior) {
            if (grapheme == FIELD_SEPARATOR && fields.size < INTERIOR_MAX_SPLITS) {
                fields += current.toString()
                current.setLength(0)
            } else {
                current.append(grapheme)
            }
        }
        fields += current.toString()
        return fields
    }

    /**
     * Swift `Int(String)` for a regex `\d+` field: ASCII digits only (a non-ASCII Unicode digit the
     * regex accepted fails here, as in Swift), and a value beyond `Int.max` is null, not a trap.
     */
    private fun parseSwiftInt(text: String): Long? {
        if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
        return text.toLongOrNull()
    }

    private fun graphemesOf(text: String): List<String> {
        val matcher = graphemes.matcher(text)
        val result = mutableListOf<String>()
        while (matcher.find()) result += matcher.group()
        return result
    }
}
