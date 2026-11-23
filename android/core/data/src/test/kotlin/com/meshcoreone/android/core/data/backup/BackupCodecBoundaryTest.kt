// AndroidOnly: WP-203 Independent raw framing, exact streaming limits, malformed wire and resource boundaries.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecBoundaryTest {
    private val codec = AppBackupCodec()

    @Test fun realFrozenSwiftFixtureDecodesEveryFamilyAndFraction() {
        val input = java.io.File("..").resolve("testing").resolve("fixtures").resolve("reference-codec")
            .resolve("reference-envelope.meshcoreone")
        val raw = input.readBytes()
        assertEquals(1835, raw.size)
        assertEquals("32e5898f8254eed7ca2744e5206698c00c95e5a1ee8767ece187b8599a012ffc",
            java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
        val value = codec.parseBackup(Bytes(raw))
        for (kind in BackupModelKind.entries) assertEquals(1L, value.manifest.count(kind))
        assertEquals(fraction("1700000500.9876542"), value.exportDate)
        assertEquals(Bytes.of(0, 0x80, 0xFF), value.contacts.single().avatarImageData)
        assertEquals("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", value.devices.single().radioId.canonicalString)
        assertEquals("Hi\u4F60\uD83D\uDE00\u05E9\u05DC\u05D5\u05DD", value.messages.single().text)
        assertEquals(UInt.MAX_VALUE, value.messages.single().timestamp)
        assertEquals(MessageStatus.DELIVERED, value.messages.single().status)
        assertEquals(ChannelFloodScope.Region("US"), value.channels.single().floodScope)
        assertEquals(value, codec.parseBackup(codec.encode(value)))
    }

    @Test fun rawDeflateIsCompatibleButRfc1950GzipAndTrailingDataAreNotAccepted() {
        val json = envelope().jsonObject().toString().toByteArray(Charsets.UTF_8)
        val zlib = ByteArrayOutputStream()
        DeflaterOutputStream(zlib).use { it.write(json) }
        assertEquals(AppBackupError.InvalidFile, failure(Bytes(zlib.toByteArray())).error)
        val gzip = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gzip).use { it.write(json) }
        assertEquals(AppBackupError.InvalidFile, failure(Bytes(gzip.toByteArray())).error)
        val raw = codec.encode(envelope())
        assertEquals(AppBackupError.InvalidFile, failure(raw + Bytes.of(0)).error)
        assertEquals(AppBackupError.InvalidFile, failure(raw + raw).error)
        assertEquals(envelope(), codec.parseBackup(raw))
    }

    @Test fun exactExpandedProductionCapIsStreamedWithoutAllocatingHalfGigabyte() {
        val cap = BackupContract.MAX_EXPANDED_BYTES
        val source = ZeroDeflateSource(cap)
        var count = 0L
        BackupInflater(source, cap, checkCancellation = {}).use { input ->
            val buffer = ByteArray(BACKUP_STREAM_CHUNK)
            while (true) {
                val next = input.read(buffer)
                if (next == -1) break
                count += next
            }
        }
        assertEquals(536_870_912L, count)
        assertTrue(source.closed)
    }

    @Test fun expandedProductionCapPlusOneFailsBeforeCopyingOverflowByte() {
        val cap = BackupContract.MAX_EXPANDED_BYTES
        val source = ZeroDeflateSource(cap + 1)
        var delivered = 0L
        val failure = assertThrows(AppBackupException::class.java) {
            BackupInflater(source, cap, checkCancellation = {}).use { input ->
                val buffer = ByteArray(BACKUP_STREAM_CHUNK)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    delivered += count
                }
            }
        }
        assertEquals(AppBackupError.DecompressedTooLarge(cap), failure.error)
        assertEquals(cap, delivered)
        assertTrue(source.closed)
    }

    @Test fun compressedProductionCapExactAndPlusOneUseBoundedReads() {
        val cap = BackupContract.MAX_COMPRESSED_BYTES
        val exact = StoredDeflateSource(cap)
        var expanded = 0L
        BackupInflater(exact, BackupContract.MAX_EXPANDED_BYTES, checkCancellation = {}).use { input ->
            val buffer = ByteArray(BACKUP_STREAM_CHUNK)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                expanded += count
            }
        }
        assertEquals(cap, exact.delivered)
        assertTrue(expanded in 1 until cap)
        val oversized = StoredDeflateSource(cap + 1)
        val failure = assertThrows(AppBackupException::class.java) {
            BackupInflater(oversized, BackupContract.MAX_EXPANDED_BYTES, checkCancellation = {}).use { input ->
                val buffer = ByteArray(BACKUP_STREAM_CHUNK)
                while (input.read(buffer) != -1) Unit
            }
        }
        assertEquals(AppBackupError.FileTooLarge(cap + 1, cap), failure.error)
        assertEquals(cap + 1, oversized.delivered)
        assertTrue(oversized.closed)
    }

    @Test fun streamInputAndOutputCloseOnFailureCancellationAndSuccess() {
        var inputClosed = false
        val input = object : ByteArrayInputStream(byteArrayOf(0, -1, -85, -51)) {
            override fun close() { inputClosed = true; super.close() }
        }
        assertThrows(AppBackupException::class.java) { codec.parseBackup(input) }
        assertTrue(inputClosed)
        var outputClosed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        assertThrows(CancellationException::class.java) { codec.encode(fullEnvelope(), output) { throw CancellationException("controlled") } }
        assertTrue(outputClosed)
        val source = ZeroDeflateSource(1_048_576)
        assertThrows(CancellationException::class.java) {
            BackupInflater(source, 1_048_576, checkCancellation = { throw CancellationException("controlled") }).use { it.read() }
        }
        assertTrue(source.closed)
    }

    @Test fun compressionExportChecksExpandedAndCompressedLimitsBeforeWritingOverflows() {
        val output = ByteArrayOutputStream()
        BackupDeflater(output, maximumExpanded = 8, checkCancellation = {}).use { deflater ->
            deflater.write(ByteArray(8))
            assertEquals(AppBackupError.DecompressedTooLarge(8),
                assertThrows(AppBackupException::class.java) { deflater.write(0) }.error)
            deflater.finish()
        }

        @Test fun streamOutputClosesWhenEnvelopeVersionOrOwnershipIsRejected() {
            val owned = snapshot().copy(selfRadioID = RADIO)
            for (invalid in listOf(
                envelope().copy(version = 3),
                envelope(snapshots = listOf(owned)).copy(version = 1),
            )) {
                var closed = false
                val output = object : ByteArrayOutputStream() {
                    override fun close() { closed = true; super.close() }
                }
                assertThrows(AppBackupException::class.java) { codec.encode(invalid, output) }
                assertTrue(closed)
            }
        }
        var written = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { written += length }
        }
        val failure = assertThrows(AppBackupException::class.java) {
            BackupDeflater(sink, maximumCompressed = 1, checkCancellation = {}).use {
                it.write(ByteArray(100))
                it.finish()
            }
        }
        assertTrue(failure.error is AppBackupError.FileTooLarge)
        assertTrue(written <= 1)
    }

    @Test fun requiredValuesTypesNumericWidthsAndEnumsFailExplicitly() {
        val value = fullEnvelope().jsonObject()
        for ((field, bad) in listOf(
            "status" to JsonPrimitive(999), "direction" to JsonPrimitive(-1), "textType" to JsonPrimitive(256),
            "timestamp" to JsonPrimitive(4_294_967_296L), "timestamp" to JsonPrimitive(-1),
            "timestamp" to JsonPrimitive(1.5), "timestamp" to JsonPrimitive("42"), "pathLength" to JsonPrimitive(256),
            "isRead" to JsonPrimitive(1), "id" to JsonPrimitive("1-1-1-1-1"), "id" to JsonPrimitive("not a uuid"),
            "pathNodes" to JsonPrimitive("not base64!"), "pathNodes" to JsonPrimitive("AQ"), "routeType" to JsonPrimitive(4),
        )) {
            assertEquals(field, AppBackupError.InvalidFile, failure(value.modifyingRow("messages") { it.replacing(field, bad) }.compressed()).error)
        }
        assertEquals(AppBackupError.InvalidFile, failure(value.modifyingRow("messages") { it.replacing("text", JsonNull) }.compressed()).error)
        assertEquals(AppBackupError.InvalidFile, failure(value.modifyingRow("devices") { it.replacing("txPower", JsonPrimitive(128)) }.compressed()).error)
        assertEquals(AppBackupError.InvalidFile, failure(value.modifyingRow("remoteNodeSessions") { it.replacing("role", JsonPrimitive(1)) }.compressed()).error)
    }

    @Test fun nullableUnknownAndLegacyDefaultDistinctionsArePreserved() {
        val value = fullEnvelope().jsonObject()
        val actual = codec.parseBackup(value.modifyingRow("messages") {
            it.replacing("contactID", JsonNull).replacing("linkPreviewURL", JsonNull).replacing("senderKeyPrefix", JsonNull)
        }.replacing("unknownFutureObject", buildJsonObject { put("flag", true) }).compressed())
        assertNull(actual.messages.single().contactID); assertNull(actual.messages.single().linkPreviewURL)
        assertNull(actual.messages.single().senderKeyPrefix)
        val legacy = codec.parseBackup(value.modifyingRow("messages") {
            it.replacing("sortDate", null).replacing("failureSeen", null).replacing("regionScopeMatches", null)
        }.compressed()).messages.single()
        assertEquals(legacy.createdAt, legacy.sortDate); assertFalse(legacy.failureSeen); assertTrue(legacy.regionScopeMatches.isEmpty())
        val arbitraryUuid = java.util.UUID.fromString("01234567-89AB-CDEF-FE01-23456789ABCD")
        val dto = message(id = arbitraryUuid)
        assertEquals(arbitraryUuid, decodeMessage(encodeMessage(dto)).id)
        assertEquals("01234567-89AB-CDEF-FE01-23456789ABCD", encodeMessage(dto).getValue("id").jsonPrimitive.content)
    }

    @Test fun malformedJsonDuplicateKeysInvalidUtf8AndNonFiniteNumbersAreRejected() {
        val json = envelope().jsonObject().toString()
        for (bad in listOf(
            json.replace("\"version\":1", "\"version\":NaN"), json.replace("\"version\":1", "\"version\":01"),
            json.replace("\"version\":1", "\"version\":1,\"version\":1"),
            json.replace("\"version\":1", "\"unknown\":garbage,\"version\":1"),
            json + "{}",
        )) assertEquals(AppBackupError.InvalidFile, failure(Bytes.utf8(bad).zlibCompressed()).error)
        assertEquals(AppBackupError.InvalidFile, failure(Bytes.of(0xC0, 0xAF).zlibCompressed()).error)
    }

    @Test fun legacyFloodScopeRawValueIsNotNormalizedThroughUnknownGermanyOrFranceFields() {
        val base = encodeChannel(channel().copy(regionScope = "Germany"))
        val omitted = decodeChannel(base.replacing("floodScopeModeRawValue", null))
        assertEquals("specific", omitted.floodScopeModeRawValue); assertEquals("Germany", omitted.regionScope)
        val inherit = decodeChannel(base.replacing("floodScopeModeRawValue", JsonPrimitive("inherit")))
        assertEquals("inherit", inherit.floodScopeModeRawValue); assertEquals("Germany", inherit.regionScope)
        val unknown = decodeChannel(base.replacing("floodScopeModeRawValue", JsonPrimitive("future-mode")).replacing("regionScope", JsonPrimitive("France")))
        assertEquals("future-mode", unknown.floodScopeModeRawValue); assertEquals("France", unknown.regionScope)
        assertEquals(unknown, decodeChannel(encodeChannel(unknown)))
        val nil = decodeChannel(base.replacing("regionScope", JsonNull).replacing("floodScopeModeRawValue", null))
        assertEquals("inherit", nil.floodScopeModeRawValue); assertNull(nil.regionScope)
    }

    private fun failure(bytes: Bytes): AppBackupException = assertThrows(AppBackupException::class.java) { codec.parseBackup(bytes) }
}

private class ZeroDeflateSource(private val total: Long) : InputStream() {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    private val zeros = ByteArray(BACKUP_STREAM_CHUNK)
    private var supplied = 0L
    var closed = false
        private set
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (deflater.finished()) return -1
        while (true) {
            if (deflater.needsInput()) {
                if (supplied == total) deflater.finish()
                else {
                    val count = minOf(zeros.size.toLong(), total - supplied).toInt()
                    deflater.setInput(zeros, 0, count)
                    supplied += count
                }
            }
            val count = deflater.deflate(buffer, offset, length)
            if (count > 0) return count
            if (deflater.finished()) return -1
        }
    }
    override fun close() { if (!closed) { closed = true; deflater.end() } }
}

private class StoredDeflateSource(encodedBytes: Long) : InputStream() {
    private val blocks = mutableListOf<Int>()
    private var block = 0
    private var offset = 0
    var delivered = 0L
        private set
    var closed = false
        private set
    init {
        var remaining = encodedBytes
        while (remaining > 65540) { blocks += 65535; remaining -= 65540 }
        if (remaining in 1..4) {
            blocks[blocks.lastIndex] -= (5 - remaining).toInt()
            remaining = 5
        }
        blocks += (remaining - 5).toInt()
    }
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
    }
    override fun read(buffer: ByteArray, target: Int, length: Int): Int {
        if (block == blocks.size) return -1
        val payload = blocks[block]
        val total = payload + 5
        val count = minOf(length, total - offset)
        java.util.Arrays.fill(buffer, target, target + count, 0)
        val header = intArrayOf(if (block == blocks.lastIndex) 1 else 0, payload and 255, payload ushr 8,
            payload.inv() and 255, (payload.inv() ushr 8) and 255)
        for (index in offset until minOf(offset + count, 5)) buffer[target + index - offset] = header[index].toByte()
        offset += count
        delivered += count
        if (offset == total) { block++; offset = 0 }
        return count
    }
    override fun close() { closed = true }
}
