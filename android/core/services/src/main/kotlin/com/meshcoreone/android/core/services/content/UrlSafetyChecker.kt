// PortedFrom: MC1/Utilities/URLSafetyChecker.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Validates URLs before fetching to prevent SSRF attacks via private network access.
 * Rejects non-HTTP(S) schemes and private/reserved IPs. Fails closed: DNS failure or
 * timeout is treated as unsafe.
 */
object UrlSafetyChecker {
    /** Hosts that bypass safety checks (known-safe CDN domains). */
    private val allowedHosts = setOf(
        "media.giphy.com",
        "i.giphy.com",
    )

    private val dnsTimeout = 5.seconds

    /**
     * Returns `true` if the URL is safe to fetch, `false` otherwise.
     *
     * [resolveHost] performs the actual DNS lookup and defaults to blocking
     * [InetAddress.getAllByName] run on [Dispatchers.IO]; tests inject a deterministic
     * resolver to avoid real network access.
     */
    suspend fun isSafe(
        url: String,
        resolveHost: suspend (String) -> List<InetAddress> = ::defaultResolve,
    ): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.removeSurrounding("[", "]") ?: return false

        if (host in allowedHosts) return true

        if (isPrivateOrReserved(host)) return false

        // A literal IP that already cleared the private/reserved check above is definitively
        // safe without any DNS lookup: resolving an already-numeric host can't discover a
        // different address, so a real-network DNS dependency here would be both pointless and,
        // in a network-restricted test/CI sandbox, a false-negative "fails closed" result for a
        // genuinely safe public IP literal.
        if (isIpLiteral(host)) return true

        return resolveAndCheck(host, resolveHost)
    }

    /** `true` when [address] parses as either an IPv4 or IPv6 literal (no DNS). */
    private fun isIpLiteral(address: String): Boolean = parseIpv4(address) != null || parseIpv6(address) != null

    /** Checks whether an IP address literal falls within private or reserved ranges. */
    fun isPrivateOrReserved(address: String): Boolean {
        parseIpv4(address)?.let { return isPrivateIpv4(it) }
        parseIpv6(address)?.let { return isPrivateIpv6(it) }
        // Not a valid IP literal -- not private (will be resolved via DNS).
        return false
    }

    private fun isPrivateIpv4(ip: Long): Boolean {
        if (ip == 0L) return true // 0.0.0.0
        if ((ip ushr 24) == 127L) return true // 127.0.0.0/8
        if ((ip ushr 24) == 10L) return true // 10.0.0.0/8
        if ((ip ushr 20) == 0xAC1L) return true // 172.16.0.0/12
        if ((ip ushr 16) == 0xC0A8L) return true // 192.168.0.0/16
        if ((ip ushr 16) == 0xA9FEL) return true // 169.254.0.0/16 (link-local)
        if ((ip ushr 28) == 0xEL) return true // 224.0.0.0/4 (multicast)
        if ((ip ushr 28) == 0xFL) return true // 240.0.0.0/4 (reserved)
        return false
    }

    private fun isPrivateIpv6(bytes: IntArray): Boolean {
        if (bytes.all { it == 0 }) return true // ::
        if (bytes.dropLast(1).all { it == 0 } && bytes.last() == 1) return true // ::1
        if (bytes[0] == 0xFE && (bytes[1] and 0xC0) == 0x80) return true // fe80::/10
        if ((bytes[0] and 0xFE) == 0xFC) return true // fc00::/7
        // IPv4-mapped: check the embedded IPv4 address.
        if (bytes.copyOfRange(0, 10).all { it == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF) {
            val mapped = "${bytes[12]}.${bytes[13]}.${bytes[14]}.${bytes[15]}"
            return isPrivateOrReserved(mapped)
        }
        if (bytes[0] == 0xFF) return true // ff00::/8
        return false
    }

    /** Resolves the host via DNS and checks all returned addresses, with a 5s timeout. */
    private suspend fun resolveAndCheck(
        host: String,
        resolveHost: suspend (String) -> List<InetAddress>,
    ): Boolean {
        val result = withTimeoutOrNull(dnsTimeout) {
            try {
                resolveHost(host)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: UnknownHostException) {
                null
            }
        }
        if (result.isNullOrEmpty()) return false
        return result.all { !isPrivateOrReserved(it.hostAddress ?: return@all false) }
    }

    private suspend fun defaultResolve(host: String): List<InetAddress> =
        withContext(Dispatchers.IO) {
            try {
                InetAddress.getAllByName(host).toList()
            } catch (e: UnknownHostException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            }
        }

    /** Parses a strict dotted-decimal IPv4 literal (no DNS). Returns the 32-bit value or null. */
    private fun parseIpv4(address: String): Long? {
        val parts = address.split(".")
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part[0] == '0') return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    /**
     * Parses an IPv6 literal (with optional `::` compression and a trailing embedded IPv4
     * dotted suffix) into 16 unsigned bytes (no DNS).
     */
    private fun parseIpv6(address: String): IntArray? {
        if (address.isEmpty() || !address.contains(":")) return null
        var text = address
        var embeddedIpv4: Long? = null

        val lastColon = text.lastIndexOf(':')
        val tail = text.substring(lastColon + 1)
        if (tail.contains(".")) {
            embeddedIpv4 = parseIpv4(tail) ?: return null
            text = text.substring(0, lastColon + 1) + "0:0"
        }

        val halves = text.split("::", limit = 2)
        if (halves.size > 2) return null

        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(":").map { it.toIntOrNull(16)?.takeIf { v -> v in 0..0xFFFF } ?: return null }
        }

        val headGroups = groups(halves[0]) ?: return null
        val tailGroups = if (halves.size == 2) groups(halves[1]) ?: return null else null

        val allGroups: List<Int> = if (tailGroups == null) {
            if (headGroups.size != 8) return null
            headGroups
        } else {
            val missing = 8 - headGroups.size - tailGroups.size
            if (missing < 0) return null
            headGroups + List(missing) { 0 } + tailGroups
        }
        if (allGroups.size != 8) return null

        val bytes = IntArray(16)
        for (i in 0 until 8) {
            bytes[i * 2] = (allGroups[i] ushr 8) and 0xFF
            bytes[i * 2 + 1] = allGroups[i] and 0xFF
        }
        if (embeddedIpv4 != null) {
            val v4 = embeddedIpv4
            bytes[12] = ((v4 ushr 24) and 0xFF).toInt()
            bytes[13] = ((v4 ushr 16) and 0xFF).toInt()
            bytes[14] = ((v4 ushr 8) and 0xFF).toInt()
            bytes[15] = (v4 and 0xFF).toInt()
        }
        return bytes
    }
}
