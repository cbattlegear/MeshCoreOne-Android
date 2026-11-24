// PortedFrom: MC1Services/Sources/MC1Services/Extensions/Data+Extensions.swift@db14559b39d32322b06477c6ae676112f583db50
// Actual WP-004 Swift/macOS bytes exercise the owned raw-DEFLATE utility, not a restore.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256
import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.Test

class ReferenceCompressionTest {
    private val fixtures = File(requireNotNull(System.getProperty("referenceCodecFixtures")))
    private fun compressed(): Bytes = Bytes(File(fixtures, "reference-envelope.meshcoreone").readBytes())

    @Test fun actualSwiftExportUsesRawDeflateAndDecodesToExactIndependentJsonBytes() {
        val input = compressed(); val expected = Bytes(File(fixtures, "reference-envelope.json").readBytes())
        assertEquals(1835, input.size); assertEquals(5544, expected.size)
        assertEquals("32e5898f8254eed7ca2744e5206698c00c95e5a1ee8767ece187b8599a012ffc", sha256(input).hexString)
        assertEquals("a953245efdc5c6f913914fe9c40928eb12332b68dc34ebcacd3df0c00eb1cc8c", sha256(expected).hexString)
        assertEquals(expected, input.zlibDecompressed(expected.size.toLong()))
    }

    @Test fun truncatedCorruptAndExpandedOverCapInputsFailWithTypedCauses() {
        val input = compressed()
        val cap = assertFailsWith<AppBackupException> { input.zlibDecompressed(5543) }
        assertEquals(AppBackupError.DecompressedTooLarge(5543), cap.error)
        val truncated = assertFailsWith<AppBackupException> { input.prefix(input.size - 1).zlibDecompressed(6000) }
        assertEquals(AppBackupError.InvalidFile, truncated.error); assertNotNull(truncated.cause)
        assertEquals(AppBackupError.InvalidFile, assertFailsWith<AppBackupException> { Bytes.of(255, 255, 255).zlibDecompressed(6000) }.error)
        assertEquals(AppBackupError.InvalidFile, assertFailsWith<AppBackupException> { Bytes.EMPTY.zlibDecompressed(6000) }.error)
    }

    @Test fun utilityEmptyAndHighBitUtf8RoundTripsAreNotPresentedAsBidirectionalIosCompatibility() {
        listOf(Bytes.EMPTY, Bytes.of(0, 128, 255), Bytes.utf8("Hi\u4F60\uD83D\uDE00\u05E9\u05DC\u05D5\u05DD")).forEach {
            assertEquals(it, it.zlibCompressed().zlibDecompressed(it.size.toLong()))
        }
        assertEquals(52_428_800L, BackupContract.MAX_COMPRESSED_BYTES)
        assertEquals(536_870_912L, BackupContract.MAX_EXPANDED_BYTES)
        BackupContract.validateCompressedSize(52_428_800)
        assertEquals(AppBackupError.FileTooLarge(52_428_801, 52_428_800),
            assertFailsWith<AppBackupException> { BackupContract.validateCompressedSize(52_428_801) }.error)
        BackupContract.validateVersion(1); BackupContract.validateVersion(2)
        for (version in listOf(0L, -1L)) {
            assertEquals(AppBackupError.InvalidFile, assertFailsWith<AppBackupException> { BackupContract.validateVersion(version) }.error)
        }
        assertEquals(AppBackupError.UnsupportedVersion(3, 2), assertFailsWith<AppBackupException> { BackupContract.validateVersion(3) }.error)
        assertEquals(12, BackupContract.modelArrayKeys.size); assertEquals(setOf("discoveredNodes"), BackupContract.legacyOptionalArrayKeys)
    }
}
