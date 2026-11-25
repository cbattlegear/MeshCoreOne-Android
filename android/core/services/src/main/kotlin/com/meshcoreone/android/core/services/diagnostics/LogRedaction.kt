// PortedFrom: MC1Services/Sources/MC1Services/Utilities/LogRedaction.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.text.BreakIterator
import java.text.Normalizer

/**
 * Centralized redaction utility for logging sensitive data.
 *
 * Swift `String.count`, `prefix(_:)`, `hasPrefix(_:)`, `contains(_:)` and
 * `split(separator:maxSplits:)` operate on grapheme clusters (Characters) compared with
 * canonical equivalence; the helpers below reproduce that with
 * [BreakIterator.getCharacterInstance] and NFC-normalized cluster comparison.
 */
internal object LogRedaction {
    /** Placeholder for password values that should never be logged. */
    const val PASSWORD_PLACEHOLDER = "[REDACTED]"

    private const val NODE_NAME_VISIBLE_CHARACTERS = 3
    private const val NODE_NAME_MASK = "***"
    private const val CLI_COMMAND_MAX_CHARACTERS = 40
    private const val TRUNCATION_SUFFIX = "..."
    private const val DEFAULT_PUBLIC_KEY_PREFIX = 6
    private const val PASSWORD_PREFIX = "password"
    private const val SET_PASSWORD = "set password"
    private const val SPACE = " "
    private const val CLI_MAX_SPLITS = 2

    /**
     * Formats public key prefix as lowercase hex (`%02x` per byte).
     * Public keys are NOT redacted since they're public.
     */
    fun publicKeyHex(key: Bytes, prefixLength: Int = DEFAULT_PUBLIC_KEY_PREFIX): String =
        hex(key.prefix(prefixLength))

    /** Redacts a node/room name to first 3 characters + "***". */
    fun nodeName(name: String): String {
        if (graphemeCount(name) <= NODE_NAME_VISIBLE_CHARACTERS) return NODE_NAME_MASK
        return graphemePrefix(name, NODE_NAME_VISIBLE_CHARACTERS) + NODE_NAME_MASK
    }

    /** Redacts sensitive parts of CLI commands (passwords), otherwise truncates to 40 characters. */
    fun cliCommand(command: String): String {
        val lower = command.lowercase()
        // Redact password values in CLI commands like "set password XYZ" or "password XYZ"
        if (hasCharacterPrefix(lower, PASSWORD_PREFIX) || containsCharacters(lower, SET_PASSWORD)) {
            val parts = splitOnSpace(command, CLI_MAX_SPLITS)
            if (parts.size >= 2) {
                return parts.dropLast(1).joinToString(SPACE) + SPACE + PASSWORD_PLACEHOLDER
            }
        }
        return truncated(command, CLI_COMMAND_MAX_CHARACTERS)
    }

    /** Lowercase two-digit hex of every byte (Swift `String(format: "%02x", $0)` joined). */
    fun hex(bytes: Bytes): String = bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt()) }

    /** Swift `text.count <= limit ? text : String(text.prefix(limit)) + "..."`. */
    fun truncated(text: String, limit: Int): String =
        if (graphemeCount(text) <= limit) text else graphemePrefix(text, limit) + TRUNCATION_SUFFIX

    /** Swift `String.count`: number of extended grapheme clusters. */
    fun graphemeCount(text: String): Int = graphemes(text).size

    /** Swift `String(text.prefix(limit))`: the first [limit] extended grapheme clusters. */
    fun graphemePrefix(text: String, limit: Int): String {
        require(limit >= 0) { "Can't take a prefix of negative length from a collection" }
        return graphemes(text).take(limit).joinToString(separator = "")
    }

    private fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        val clusters = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            clusters += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return clusters
    }

    /** Swift Character equality: canonical equivalence of one grapheme cluster. */
    private fun canonical(cluster: String): String = Normalizer.normalize(cluster, Normalizer.Form.NFC)

    private fun canonicalGraphemes(text: String): List<String> = graphemes(text).map(::canonical)

    private fun hasCharacterPrefix(text: String, prefix: String): Boolean {
        val haystack = canonicalGraphemes(text)
        val needle = canonicalGraphemes(prefix)
        return haystack.size >= needle.size && haystack.subList(0, needle.size) == needle
    }

    private fun containsCharacters(text: String, fragment: String): Boolean {
        val haystack = canonicalGraphemes(text)
        val needle = canonicalGraphemes(fragment)
        if (needle.isEmpty()) return true
        return (0..haystack.size - needle.size).any { start ->
            haystack.subList(start, start + needle.size) == needle
        }
    }

    /**
     * Swift `split(separator: " ", maxSplits:)` with the default
     * `omittingEmptySubsequences: true`: empty pieces are skipped and do not count toward
     * [maxSplits]; once [maxSplits] pieces exist the remainder (leading spaces included) is
     * appended whole when non-empty.
     */
    private fun splitOnSpace(text: String, maxSplits: Int): List<String> {
        val clusters = graphemes(text)
        if (maxSplits == 0 || clusters.isEmpty()) {
            return if (clusters.isEmpty()) emptyList() else listOf(text)
        }
        val pieces = ArrayList<String>()
        var pieceStart = 0
        var index = 0
        while (index < clusters.size) {
            if (canonical(clusters[index]) != SPACE) {
                index += 1
                continue
            }
            val appended = pieceStart != index
            if (appended) pieces += clusters.subList(pieceStart, index).joinToString(separator = "")
            index += 1
            pieceStart = index
            if (appended && pieces.size == maxSplits) break
        }
        if (pieceStart != clusters.size) pieces += clusters.subList(pieceStart, clusters.size).joinToString(separator = "")
        return pieces
    }
}
