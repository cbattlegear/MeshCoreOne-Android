// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketCodes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/ErrorCode.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketSize.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Expected tables come from immutable original Git objects, not candidate code.
package com.meshcoreone.android.core.protocol.primitives

import com.meshcoreone.android.core.protocol.model.AnonRequestType
import com.meshcoreone.android.core.protocol.model.BinaryRequestType
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ControlType
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.PacketSize
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.model.StatsType
import com.meshcoreone.android.core.protocol.model.TextType
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireTablesTest {
    private val sourceRevision = "db14559b39d32322b06477c6ae676112f583db50"

    private fun original(path: String): String {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("MeshCore").isDirectory() && it.resolve("android").isDirectory() }
        val process = ProcessBuilder("git", "--no-pager", "-C", root.toString(), "show", "$sourceRevision:$path").start()
        val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val error = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Original Git object read did not finish")
        assertEquals(0, process.exitValue(), "Pinned source is unavailable: $path ($error)")
        return text
    }

    private fun nativeName(swiftName: String): String =
        swiftName.replace(Regex("([A-Z])([A-Z][a-z])"), "$1_$2")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase(java.util.Locale.ROOT)

    private fun values(source: String, type: String): Map<String, Int> {
        val section = Regex(
            "public enum ${Regex.escape(type)}: UInt8, Sendable[^\\{]*\\{(.*?)\\n\\}",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.groupValues?.get(1) ?: error("Missing original byte enum: $type")
        val entries = Regex("^\\s*case ([A-Za-z][A-Za-z0-9]*) = (0x[0-9A-Fa-f]+|[0-9]+)\\s*$", RegexOption.MULTILINE)
            .findAll(section).map { match ->
                val literal = match.groupValues[2]
                nativeName(match.groupValues[1]) to if (literal.startsWith("0x")) literal.drop(2).toInt(16) else literal.toInt()
            }.toList()
        assertTrue(entries.isNotEmpty(), "Zero original enum entries: $type")
        assertEquals(entries.size, entries.toMap().size, "Duplicate original enum name: $type")
        return entries.toMap()
    }

    @Test
    fun `Every named command and response opcode matches the pinned wire table`() {
        val source = original("MeshCore/Sources/MeshCore/Protocol/PacketCodes.swift")
        assertEquals(values(source, "CommandCode"), CommandCode.entries.associate { it.name to it.rawValue.toInt() })
        assertEquals(values(source, "ResponseCode"), ResponseCode.entries.associate { it.name to it.rawValue.toInt() })
        assertEquals(values(source, "BinaryRequestType"), BinaryRequestType.entries.associate { it.name to it.rawValue.toInt() })
        assertEquals(values(source, "AnonRequestType"), AnonRequestType.entries.associate { it.name to it.rawValue.toInt() })
        assertEquals(values(source, "ControlType"), ControlType.entries.associate { it.name to it.rawValue.toInt() })
        assertEquals(values(source, "StatsType"), StatsType.entries.associate { it.name to it.rawValue.toInt() })
        assertEquals(values(source, "TextType"), TextType.entries.associate { it.name to it.rawValue.toInt() })
        assertEquals(
            values(original("MeshCore/Sources/MeshCore/Protocol/ErrorCode.swift"), "ErrorCode"),
            ErrorCode.entries.associate { it.name to it.rawValue.toInt() },
        )
    }

    @Test
    fun `Every response category matches the pinned source routing switch`() {
        val source = original("MeshCore/Sources/MeshCore/Protocol/PacketCodes.swift")
            .substringAfter("public extension ResponseCode")
        val expected = mutableMapOf<String, String>()
        val groups = Regex("case (.*?):\\s*\\.([a-z]+)", RegexOption.DOT_MATCHES_ALL).findAll(source)
        for (group in groups) {
            for (name in Regex("\\.([A-Za-z][A-Za-z0-9]*)").findAll(group.groupValues[1])) {
                val key = nativeName(name.groupValues[1])
                assertEquals(null, expected.put(key, nativeName(group.groupValues[2])), "Duplicate routed response $key")
            }
        }
        assertEquals(ResponseCode.entries.size, expected.size, "Original response routing was not fully accounted for")
        assertEquals(expected, ResponseCode.entries.associate { it.name to it.category.name })
    }

    @Test
    fun `Every packet width matches the pinned source including composed widths`() {
        val source = original("MeshCore/Sources/MeshCore/Protocol/PacketSize.swift")
        val expected = linkedMapOf<String, Int>()
        for (entry in Regex("^\\s*static let ([A-Za-z][A-Za-z0-9]*) = (.+)$", RegexOption.MULTILINE).findAll(source)) {
            val name = nativeName(entry.groupValues[1])
            val expression = entry.groupValues[2].trim()
            val value = expression.toIntOrNull() ?: expression.split(" + ").sumOf { part ->
                expected[nativeName(part)] ?: error("Unknown original size expression: $expression")
            }
            assertEquals(null, expected.put(name, value), "Duplicate original packet width: $name")
        }
        val actual = PacketSize::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
        assertTrue(expected.isNotEmpty(), "Zero original packet size constants")
        assertEquals(expected, actual)
    }
}
