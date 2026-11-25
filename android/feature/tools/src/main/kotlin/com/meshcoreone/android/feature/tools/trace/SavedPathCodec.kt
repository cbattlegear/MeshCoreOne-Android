// PortedFrom: MC1/Views/Tools/TracePath/TracePathViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.protocol.bytes.Bytes

/** Shared by the wire path and saved-path loading, so loading inverts the same auto-return mirror. */
internal object SavedPathCodec {
    /** The return leg: outbound reversed, without the far hop (it is not repeated). */
    fun mirroredReturn(outbound: List<Bytes>): List<Bytes> = outbound.asReversed().drop(1)

    /** Splits saved bytes into hops of [hashSize] (the last may be short). */
    fun hopHashes(pathBytes: Bytes, hashSize: Int): List<Bytes> {
        val step = hashSize.coerceAtLeast(1)
        val result = ArrayList<Bytes>()
        var start = 0
        while (start < pathBytes.size) {
            val end = minOf(start + step, pathBytes.size)
            result += pathBytes.slice(start, end)
            start = end
        }
        return result
    }

    /** Outbound hop count when [hops] is outbound plus [mirroredReturn]; odd length is necessary, not sufficient. */
    fun autoReturnOutboundCount(hops: List<Bytes>): Int? {
        if (hops.size % 2 == 0) return null
        val outboundCount = (hops.size + 1) / 2
        val outbound = hops.take(outboundCount)
        return if (hops.drop(outboundCount) == mirroredReturn(outbound)) outboundCount else null
    }

    /** Outbound hashes plus the optional mirrored return, concatenated. */
    fun wirePath(outbound: List<Bytes>, autoReturn: Boolean): Bytes {
        if (outbound.isEmpty()) return Bytes.EMPTY
        val hops = if (autoReturn) outbound + mirroredReturn(outbound) else outbound
        return hops.fold(Bytes.EMPTY) { total, hop -> total + hop }
    }
}
