// PortedFrom: MC1Services/Sources/MC1Services/Utilities/MeshCoreURIQuery.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.deeplinks

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object PercentEncoding {
    private const val HEX = "0123456789ABCDEF"

    fun formDecode(value: String?): String? = decode((value ?: "").replace("+", " "))

    fun decodePathComponent(value: String): String? = decode(value)

    fun encodePathComponent(value: String): String {
        val output = StringBuilder()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (char.isUnreservedPathCharacter()) output.append(char)
            else output.append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0F])
        }
        return output.toString()
    }

    private fun decode(value: String): String? {
        val output = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) return null
                val high = value[index + 1].digitToIntOrNull(16) ?: return null
                val low = value[index + 2].digitToIntOrNull(16) ?: return null
                output.write((high shl 4) or low)
                index += 3
            } else {
                val codePoint = value.codePointAt(index)
                output.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                index += Character.charCount(codePoint)
            }
        }
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        }.getOrNull()
    }

    private fun Char.isUnreservedPathCharacter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'
}
