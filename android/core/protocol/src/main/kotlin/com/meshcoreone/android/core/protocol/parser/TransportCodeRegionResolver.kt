// PortedFrom: MeshCore/Sources/MeshCore/Protocol/TransportCodeRegionResolver.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: reuse canonical wire HMAC and JVM localized numeric collation; callers own scope-key caches.
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.ByteReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.WireCrypto
import com.meshcoreone.android.core.protocol.event.EventList
import com.meshcoreone.android.core.protocol.model.sha256
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class RegionScopeKey(val name: String, val key: Bytes)

sealed interface RegionMatchResult {
    data object None : RegionMatchResult
    data class Unique(val name: String) : RegionMatchResult
    data class Ambiguous(val names: EventList<String>) : RegionMatchResult {
        constructor(names: Collection<String>) : this(EventList(names))
    }
}

object TransportCodeRegionResolver {
    fun deriveScopeKey(regionName: String): Bytes? {
        val name = regionName.trimWhitespacesAndNewlines()
        if (name.isEmpty() || name.startsWith("$")) return null
        return sha256(Bytes.utf8(if (name.startsWith("#")) name else "#$name")).prefix(16)
    }

    fun calcTransportCode(scopeKey: Bytes, payloadTypeBits: UByte, payload: Bytes): UShort {
        val combined = Bytes.of(payloadTypeBits.toInt() and 15) + payload
        val mac = WireCrypto.hmacSha256(combined, scopeKey)
        return rewriteReservedCode(ByteReader(mac).readUInt16LE())
    }

    internal fun rewriteReservedCode(rawCode: UShort): UShort = when (rawCode.toInt()) {
        0 -> 1u
        0xffff -> 0xfffeu
        else -> rawCode
    }

    fun matchRegions(
        scopeKeys: Collection<RegionScopeKey>,
        expectedTransportCode0: UShort,
        payloadTypeBits: UByte,
        payload: Bytes,
        locale: Locale = Locale.getDefault(),
    ): RegionMatchResult {
        val names = scopeKeys.mapNotNull { scope ->
            val name = scope.name.trimWhitespacesAndNewlines()
            if (name.isNotEmpty() && calcTransportCode(scope.key, payloadTypeBits, payload) == expectedTransportCode0) name else null
        }.distinctBy { Normalizer.normalize(it, Normalizer.Form.NFC) }.sortedWith(naturalComparator(locale))
        return when (names.size) {
            0 -> RegionMatchResult.None
            1 -> RegionMatchResult.Unique(names.single())
            else -> RegionMatchResult.Ambiguous(names)
        }
    }

    private fun naturalComparator(locale: Locale): Comparator<String> {
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.SECONDARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        return Comparator { left, right ->
            val a = chunks(left)
            val b = chunks(right)
            var result = 0
            for (index in 0 until minOf(a.size, b.size)) {
                val x = a[index]
                val y = b[index]
                result = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
                    val nx = x.trimStart('0')
                    val ny = y.trimStart('0')
                    nx.length.compareTo(ny.length).takeUnless { it == 0 } ?: nx.compareTo(ny)
                } else collator.compare(x, y)
                if (result != 0) break
            }
            if (result == 0) result = a.size.compareTo(b.size)
            if (result == 0) left.compareTo(right) else result
        }
    }

    private fun chunks(name: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        for (index in 1 until name.length) {
            if ((name[index] in '0'..'9') != (name[index - 1] in '0'..'9')) {
                result += name.substring(start, index)
                start = index
            }
        }
        if (start < name.length) result += name.substring(start)
        return result
    }
}
