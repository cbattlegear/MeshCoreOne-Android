// PortedFrom: MC1/Views/Components/WiFiAddressFields.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

object WiFiAddressValidation {
    const val HOSTNAME_MAX_LENGTH = 253
    const val LABEL_MAX_LENGTH = 63

    fun normalizedHost(raw: String): String = raw.trim { it.isWhitespace() || Character.isSpaceChar(it) }
    fun replaceCommas(raw: String): String = raw.replace(',', '.')

    fun isValidHost(raw: String): Boolean {
        val host = normalizedHost(raw)
        if (host.isEmpty()) return false
        val parts = host.split('.')
        val looksLikeIPv4 = parts.all { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
        return if (looksLikeIPv4) isValidIPAddress(host) else isValidHostname(host)
    }

    fun isValidIPAddress(ip: String): Boolean {
        val parts = ip.split('.').filter { it.isNotEmpty() }
        return parts.size == 4 && parts.all { part ->
            val number = part.toLongOrNull()
            number != null && number in 0..255
        }
    }

    fun isValidPort(port: String): Boolean {
        val digits = port.removePrefix("+")
        return digits.isNotEmpty() && digits.all { it in '0'..'9' } &&
            (port.toLongOrNull()?.let { it in 1..65535 } == true)
    }

    private fun isValidHostname(host: String): Boolean {
        val candidate = host.removeSuffix(".")
        if (candidate.length !in 1..HOSTNAME_MAX_LENGTH) return false
        return candidate.split('.').all { label ->
            label.length in 1..LABEL_MAX_LENGTH && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }
}
