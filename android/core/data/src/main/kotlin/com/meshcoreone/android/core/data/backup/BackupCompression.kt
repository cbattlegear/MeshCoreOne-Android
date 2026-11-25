// PortedFrom: MC1Services/Sources/MC1Services/Extensions/Data+Extensions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Raw DEFLATE framing was measured by the frozen macOS oracle.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.model.*
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal const val BACKUP_STREAM_CHUNK = 64 * 1024

internal class BackupInflater(
    private val source: InputStream,
    private val maximumExpanded: Long,
    private val maximumCompressed: Long = BackupContract.MAX_COMPRESSED_BYTES,
    private val checkCancellation: () -> Unit,
) : InputStream() {
    private val inflater = Inflater(true)
    private val input = ByteArray(BACKUP_STREAM_CHUNK)
    private val single = ByteArray(1)
    private var compressed = 0L
    private var expanded = 0L
    private var ended = false
    private var closed = false

    init {
        require(maximumExpanded in 0..BackupContract.MAX_EXPANDED_BYTES)
        require(maximumCompressed in 0..BackupContract.MAX_COMPRESSED_BYTES)
    }

    override fun read(): Int = if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 255

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Closed backup stream" }
        java.util.Objects.checkFromIndexSize(offset, length, buffer.size)
        if (length == 0) return 0
        if (ended) return -1
        while (true) {
            checkCancellation()
            if (inflater.finished()) {
                if (inflater.remaining != 0 || source.read() != -1) invalidValue("compression.trailing", BackupValueProblem.JSON)
                ended = true
                return -1
            }
            if (inflater.needsDictionary()) invalidValue("compression.dictionary", BackupValueProblem.JSON)
            if (inflater.needsInput()) {
                val available = minOf(input.size.toLong(), maximumCompressed - compressed + 1).toInt()
                val count = source.read(input, 0, available)
                if (count == -1) invalidValue("compression.truncated", BackupValueProblem.JSON)
                if (count == 0) invalidValue("compression.noProgress", BackupValueProblem.JSON)
                compressed = Math.addExact(compressed, count.toLong())
                if (compressed > maximumCompressed) {
                    throw AppBackupException(AppBackupError.FileTooLarge(compressed, maximumCompressed))
                }
                inflater.setInput(input, 0, count)
            }
            val remaining = maximumExpanded - expanded
            val overflowProbe = remaining == 0L
            val request = if (overflowProbe) 1 else minOf(length.toLong(), remaining).toInt()
            val count = try {
                if (overflowProbe) inflater.inflate(single, 0, 1) else inflater.inflate(buffer, offset, request)
            } catch (cause: DataFormatException) {
                invalidValue("compression.deflate", BackupValueProblem.JSON, cause)
            }
            if (count > 0) {
                if (overflowProbe) {
                    throw AppBackupException(AppBackupError.DecompressedTooLarge(maximumExpanded))
                }
                expanded += count
                return count
            }
            if (!inflater.finished() && !inflater.needsInput() && !inflater.needsDictionary()) {
                invalidValue("compression.noProgress", BackupValueProblem.JSON)
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { inflater.end() } finally { source.close() }
        }
    }
}

internal class BackupDeflater(
    private val destination: OutputStream,
    private val maximumExpanded: Long = BackupContract.MAX_EXPANDED_BYTES,
    private val maximumCompressed: Long = BackupContract.MAX_COMPRESSED_BYTES,
    private val checkCancellation: () -> Unit,
) : OutputStream() {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    private val output = ByteArray(BACKUP_STREAM_CHUNK)
    private val single = ByteArray(1)
    private var expanded = 0L
    private var compressed = 0L
    private var finished = false
    private var closed = false

    init {
        require(maximumExpanded in 0..BackupContract.MAX_EXPANDED_BYTES)
        require(maximumCompressed in 0..BackupContract.MAX_COMPRESSED_BYTES)
    }

    override fun write(value: Int) { single[0] = value.toByte(); write(single, 0, 1) }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed && !finished) { "Closed backup stream" }
        java.util.Objects.checkFromIndexSize(offset, length, buffer.size)
        if (length.toLong() > maximumExpanded - expanded) {
            throw AppBackupException(AppBackupError.DecompressedTooLarge(maximumExpanded))
        }
        expanded += length
        var position = offset
        val end = offset + length
        while (position < end) {
            checkCancellation()
            val count = minOf(BACKUP_STREAM_CHUNK, end - position)
            deflater.setInput(buffer, position, count)
            while (!deflater.needsInput()) drain()
            position += count
        }
    }

    fun finish() {
        check(!closed) { "Closed backup stream" }
        if (!finished) {
            deflater.finish()
            while (!deflater.finished()) drain()
            destination.flush()
            finished = true
        }
    }

    private fun drain() {
        checkCancellation()
        val count = deflater.deflate(output)
        if (count > 0) {
            if (count.toLong() > maximumCompressed - compressed) {
                throw AppBackupException(AppBackupError.FileTooLarge(Math.addExact(compressed, count.toLong()), maximumCompressed))
            }
            destination.write(output, 0, count)
            compressed += count
        } else if (!deflater.finished() && !deflater.needsInput()) {
            invalidValue("compression.noProgress", BackupValueProblem.JSON)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { deflater.end() } finally { destination.close() }
        }
    }
}
