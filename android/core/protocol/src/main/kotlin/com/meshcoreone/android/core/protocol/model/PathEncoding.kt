// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PathEncoding.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.model

object PathEncoding {
    const val MAX_PATH_HASH_MODE = 2
    const val MAX_HOP_COUNT = 63
    const val MAX_PATH_BYTES = 64
}

data class PathLenDecoded(val hashSize: Int, val hopCount: Int, val byteLength: Int)

fun decodePathLen(encoded: UByte): PathLenDecoded? {
    val raw = encoded.toInt()
    val mode = raw ushr 6
    if (mode >= 3) return null
    val hashSize = mode + 1
    val hopCount = raw and 63
    return PathLenDecoded(hashSize, hopCount, hashSize * hopCount)
}

fun encodePathLen(hashSize: Int, hopCount: Int): UByte {
    require(hashSize in 1..3) { "Path hash size must be 1, 2 or 3" }
    require(hopCount >= 0) { "Path hop count must not be negative" }
    return (((hashSize - 1) shl 6) or minOf(hopCount, PathEncoding.MAX_HOP_COUNT)).toUByte()
}
