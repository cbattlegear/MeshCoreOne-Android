// PortedFrom: MC1/Services/ImageURLDetector.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot

data class GifImageMetadata(
    val width: Int,
    val height: Int,
    val frameDelaysMillis: SnapshotList<Long>,
) {
    val durationMillis: Long get() = frameDelaysMillis.sum()

    companion object {
        fun parse(bytes: ByteArray): GifImageMetadata? {
            if (bytes.size < 13 ||
                !(bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                    bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray()))) return null
            fun byte(index: Int): Int = bytes[index].toInt() and 255
            fun little(index: Int): Int = byte(index) or (byte(index + 1) shl 8)
            val width = little(6)
            val height = little(8)
            if (width == 0 || height == 0) return null
            var cursor = 13
            if (byte(10) and 128 != 0) cursor += 3 * (1 shl ((byte(10) and 7) + 1))
            if (cursor > bytes.size) return null
            val delays = mutableListOf<Long>()
            var pendingDelay = 100L
            fun skipBlocks(): Boolean {
                while (cursor < bytes.size) {
                    val size = byte(cursor++)
                    if (size == 0) return true
                    if (size > bytes.size - cursor) return false
                    cursor += size
                }
                return false
            }
            while (cursor < bytes.size) {
                when (byte(cursor++)) {
                    0x3b -> return if (delays.isEmpty()) null else GifImageMetadata(width, height, delays.snapshot())
                    0x21 -> {
                        if (cursor >= bytes.size) return null
                        if (byte(cursor++) == 0xf9) {
                            if (bytes.size - cursor < 6 || byte(cursor) != 4 || byte(cursor + 5) != 0) return null
                            pendingDelay = (little(cursor + 2) * 10L).coerceAtLeast(20L)
                            cursor += 6
                        } else if (!skipBlocks()) return null
                    }
                    0x2c -> {
                        if (bytes.size - cursor < 9) return null
                        if (little(cursor + 4) == 0 || little(cursor + 6) == 0) return null
                        val packed = byte(cursor + 8)
                        cursor += 9
                        if (packed and 128 != 0) cursor += 3 * (1 shl ((packed and 7) + 1))
                        if (cursor >= bytes.size) return null
                        cursor++
                        if (!skipBlocks()) return null
                        delays.add(pendingDelay)
                        pendingDelay = 100L
                    }
                    else -> return null
                }
            }
            return null
        }
    }
}
