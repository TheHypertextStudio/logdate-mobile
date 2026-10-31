package app.logdate.client.domain.export

import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Timeout
import okio.buffer

/**
 * Minimal ZIP archive writer for portable LogDate exports.
 *
 * Archives are written using the stored method so they can be created
 * consistently across Android, desktop, and iOS without a platform ZIP API.
 * Entries, offsets and entry counts beyond the classic ZIP limits use ZIP64
 * records, so a journal with many gigabytes of media still exports whole.
 */
class ZipArchiveWriter(
    private val fileSystem: FileSystem,
) {
    fun write(
        outputPath: Path,
        entries: List<ZipArchiveEntry>,
    ) {
        val sink = fileSystem.sink(outputPath).buffer()
        try {
            val centralDirectoryEntries = mutableListOf<CentralDirectoryEntry>()
            var offset = 0L

            entries.forEach { entry ->
                val normalizedPath = entry.path.trimStart('/')
                require(normalizedPath.isNotBlank()) { "ZIP entry path cannot be blank" }

                val nameBytes = normalizedPath.encodeToByteArray()
                require(nameBytes.size <= MAX_SHORT_VALUE) {
                    "ZIP entry path is too long: $normalizedPath"
                }

                val localHeaderOffset = offset
                writeLocalHeader(sink, nameBytes)
                offset += LOCAL_FILE_HEADER_SIZE + nameBytes.size

                val checksum = Crc32()
                val sizeBytes =
                    when (entry) {
                        is ZipArchiveEntry.Bytes -> {
                            checksum.update(entry.bytes)
                            sink.write(entry.bytes)
                            entry.bytes.size.toLong()
                        }

                        is ZipArchiveEntry.File -> {
                            writeFileContents(sink, entry.sourcePath, checksum)
                        }

                        is ZipArchiveEntry.Streaming -> {
                            writeStreamedContents(sink, entry.writeTo, checksum)
                        }
                    }
                val zip64Descriptor = sizeBytes >= ZIP64_MARKER
                sink.writeDataDescriptor(checksum.value, sizeBytes, zip64Descriptor)
                offset += sizeBytes + if (zip64Descriptor) ZIP64_DATA_DESCRIPTOR_SIZE else DATA_DESCRIPTOR_SIZE

                centralDirectoryEntries +=
                    CentralDirectoryEntry(
                        nameBytes = nameBytes,
                        crc32 = checksum.value,
                        sizeBytes = sizeBytes,
                        localHeaderOffset = localHeaderOffset,
                    )
            }

            val centralDirectoryOffset = offset
            centralDirectoryEntries.forEach { entry ->
                offset += writeCentralDirectoryHeader(sink, entry)
            }

            val centralDirectorySize = offset - centralDirectoryOffset
            val entryCount = centralDirectoryEntries.size.toLong()
            if (entryCount >= MAX_SHORT_VALUE || centralDirectorySize >= ZIP64_MARKER || centralDirectoryOffset >= ZIP64_MARKER) {
                sink.writeZip64EndOfCentralDirectory(
                    entryCount = entryCount,
                    centralDirectorySize = centralDirectorySize,
                    centralDirectoryOffset = centralDirectoryOffset,
                    recordOffset = offset,
                )
            }
            sink.writeEndOfCentralDirectory(
                entryCount = entryCount,
                centralDirectorySize = centralDirectorySize,
                centralDirectoryOffset = centralDirectoryOffset,
            )
        } finally {
            sink.close()
        }
    }

    private fun writeFileContents(
        sink: BufferedSink,
        sourcePath: Path,
        checksum: Crc32,
    ): Long {
        val buffer = Buffer()
        var totalBytes = 0L

        try {
            val source = fileSystem.source(sourcePath).buffer()
            try {
                while (true) {
                    val read = source.read(buffer, CHUNK_SIZE)
                    if (read == -1L) {
                        break
                    }
                    val chunk = buffer.readByteArray(read)
                    checksum.update(chunk)
                    sink.write(chunk)
                    totalBytes += read
                }
            } finally {
                source.close()
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read ZIP entry source: $sourcePath", e)
        }

        return totalBytes
    }

    /**
     * Streams generated content (e.g. serialized JSON) into the archive without
     * materializing it. Bytes pass through a forwarding sink that computes the CRC
     * and running size as they are written, so peak memory stays at the buffer size.
     * The archive uses data descriptors, so the size/CRC need not be known up front.
     */
    private fun writeStreamedContents(
        sink: BufferedSink,
        writeTo: (BufferedSink) -> Unit,
        checksum: Crc32,
    ): Long {
        val countingSink = CountingCrcSink(sink, checksum)
        val entrySink = countingSink.buffer()
        writeTo(entrySink)
        // Flush (not close) so the streamed bytes land in this entry without closing `sink`.
        entrySink.flush()
        return countingSink.size
    }

    private fun writeLocalHeader(
        sink: BufferedSink,
        nameBytes: ByteArray,
    ) {
        sink.writeIntLe(LOCAL_FILE_HEADER_SIGNATURE)
        sink.writeShortLe(VERSION_NEEDED)
        sink.writeShortLe(GENERAL_PURPOSE_FLAGS)
        sink.writeShortLe(COMPRESSION_METHOD_STORED)
        sink.writeShortLe(0)
        sink.writeShortLe(0)
        sink.writeIntLe(0)
        sink.writeIntLe(0)
        sink.writeIntLe(0)
        sink.writeShortLe(nameBytes.size)
        sink.writeShortLe(0)
        sink.write(nameBytes)
    }

    /**
     * Writes the entry's central directory record and returns its length. Sizes and offsets that do
     * not fit 32 bits are written as 0xFFFFFFFF with the real values in a ZIP64 extra field.
     */
    private fun writeCentralDirectoryHeader(
        sink: BufferedSink,
        entry: CentralDirectoryEntry,
    ): Long {
        val zip64Size = entry.sizeBytes >= ZIP64_MARKER
        val zip64Offset = entry.localHeaderOffset >= ZIP64_MARKER
        val zip64FieldsSize = (if (zip64Size) 16 else 0) + (if (zip64Offset) 8 else 0)
        val extraSize = if (zip64FieldsSize > 0) ZIP64_EXTRA_HEADER_SIZE + zip64FieldsSize else 0
        val version = if (extraSize > 0) VERSION_ZIP64 else VERSION_NEEDED
        sink.writeIntLe(CENTRAL_DIRECTORY_SIGNATURE)
        sink.writeShortLe(if (extraSize > 0) VERSION_ZIP64 else VERSION_MADE_BY)
        sink.writeShortLe(version)
        sink.writeShortLe(GENERAL_PURPOSE_FLAGS)
        sink.writeShortLe(COMPRESSION_METHOD_STORED)
        sink.writeShortLe(0)
        sink.writeShortLe(0)
        sink.writeIntLe(entry.crc32)
        sink.writeIntLe(if (zip64Size) ZIP64_MARKER else entry.sizeBytes)
        sink.writeIntLe(if (zip64Size) ZIP64_MARKER else entry.sizeBytes)
        sink.writeShortLe(entry.nameBytes.size)
        sink.writeShortLe(extraSize)
        sink.writeShortLe(0)
        sink.writeShortLe(0)
        sink.writeShortLe(0)
        sink.writeIntLe(0)
        sink.writeIntLe(if (zip64Offset) ZIP64_MARKER else entry.localHeaderOffset)
        sink.write(entry.nameBytes)
        if (extraSize > 0) {
            sink.writeShortLe(ZIP64_EXTRA_FIELD_ID)
            sink.writeShortLe(zip64FieldsSize)
            if (zip64Size) {
                sink.writeLongLe(entry.sizeBytes)
                sink.writeLongLe(entry.sizeBytes)
            }
            if (zip64Offset) sink.writeLongLe(entry.localHeaderOffset)
        }
        return CENTRAL_DIRECTORY_HEADER_SIZE + entry.nameBytes.size + extraSize
    }

    private fun BufferedSink.writeDataDescriptor(
        crc32: Long,
        sizeBytes: Long,
        zip64: Boolean,
    ) {
        writeIntLe(DATA_DESCRIPTOR_SIGNATURE)
        writeIntLe(crc32)
        if (zip64) {
            writeLongLe(sizeBytes)
            writeLongLe(sizeBytes)
        } else {
            writeIntLe(sizeBytes)
            writeIntLe(sizeBytes)
        }
    }

    private fun BufferedSink.writeZip64EndOfCentralDirectory(
        entryCount: Long,
        centralDirectorySize: Long,
        centralDirectoryOffset: Long,
        recordOffset: Long,
    ) {
        writeIntLe(ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        writeLongLe(ZIP64_END_OF_CENTRAL_DIRECTORY_REMAINING_SIZE)
        writeShortLe(VERSION_ZIP64)
        writeShortLe(VERSION_ZIP64)
        writeIntLe(0)
        writeIntLe(0)
        writeLongLe(entryCount)
        writeLongLe(entryCount)
        writeLongLe(centralDirectorySize)
        writeLongLe(centralDirectoryOffset)
        writeIntLe(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE)
        writeIntLe(0)
        writeLongLe(recordOffset)
        writeIntLe(1)
    }

    private fun BufferedSink.writeEndOfCentralDirectory(
        entryCount: Long,
        centralDirectorySize: Long,
        centralDirectoryOffset: Long,
    ) {
        val shortCount = if (entryCount >= MAX_SHORT_VALUE) MAX_SHORT_VALUE else entryCount.toInt()
        writeIntLe(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        writeShortLe(0)
        writeShortLe(0)
        writeShortLe(shortCount)
        writeShortLe(shortCount)
        writeIntLe(centralDirectorySize.coerceAtMost(ZIP64_MARKER))
        writeIntLe(centralDirectoryOffset.coerceAtMost(ZIP64_MARKER))
        writeShortLe(0)
    }

    private fun BufferedSink.writeShortLe(value: Int) {
        writeByte(value and 0xff)
        writeByte(value ushr 8 and 0xff)
    }

    private fun BufferedSink.writeIntLe(value: Long) {
        writeByte((value and 0xff).toInt())
        writeByte((value ushr 8 and 0xff).toInt())
        writeByte((value ushr 16 and 0xff).toInt())
        writeByte((value ushr 24 and 0xff).toInt())
    }

    private fun BufferedSink.writeLongLe(value: Long) {
        writeIntLe(value and 0xffffffffL)
        writeIntLe(value ushr 32)
    }

    private class CentralDirectoryEntry(
        val nameBytes: ByteArray,
        val crc32: Long,
        val sizeBytes: Long,
        val localHeaderOffset: Long,
    )

    companion object {
        private const val CHUNK_SIZE = 8_192L
        private const val LOCAL_FILE_HEADER_SIZE = 30L
        private const val CENTRAL_DIRECTORY_HEADER_SIZE = 46L
        private const val DATA_DESCRIPTOR_SIZE = 16L
        private const val ZIP64_DATA_DESCRIPTOR_SIZE = 24L
        private const val ZIP64_EXTRA_HEADER_SIZE = 4
        private const val ZIP64_EXTRA_FIELD_ID = 0x0001
        private const val ZIP64_END_OF_CENTRAL_DIRECTORY_REMAINING_SIZE = 44L
        private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
        private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
        private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L
        private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
        private const val ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50L
        private const val ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064b50L
        private const val VERSION_MADE_BY = 20
        private const val VERSION_NEEDED = 20
        private const val VERSION_ZIP64 = 45
        private const val GENERAL_PURPOSE_FLAGS = 0x08
        private const val COMPRESSION_METHOD_STORED = 0
        private const val MAX_SHORT_VALUE = 0xffff

        /** Values at or above this do not fit a classic ZIP field, which reserves it as the ZIP64 marker. */
        private const val ZIP64_MARKER = 0xffffffffL
    }
}

sealed interface ZipArchiveEntry {
    val path: String

    data class Bytes(
        override val path: String,
        val bytes: ByteArray,
    ) : ZipArchiveEntry

    data class File(
        override val path: String,
        val sourcePath: Path,
    ) : ZipArchiveEntry

    /**
     * An entry whose contents are generated on demand and streamed into the archive.
     * [writeTo] receives a buffered sink to write to; nothing is materialized in memory.
     */
    class Streaming(
        override val path: String,
        val writeTo: (BufferedSink) -> Unit,
    ) : ZipArchiveEntry
}

/**
 * A [Sink] that updates [checksum] and tracks the running [size] as bytes pass through
 * to [delegate], so streamed archive entries can compute their CRC/size without buffering
 * the whole entry. [close] is a no-op because this sink does not own [delegate].
 */
private class CountingCrcSink(
    private val delegate: BufferedSink,
    private val checksum: Crc32,
) : Sink {
    var size: Long = 0L
        private set

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        val bytes = source.readByteArray(byteCount)
        checksum.update(bytes)
        size += byteCount
        delegate.write(bytes)
    }

    override fun flush() = delegate.flush()

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() {
        // Intentionally does not close the underlying archive sink.
    }
}

private class Crc32 {
    private var state: Int = -1

    val value: Long
        get() = (state.inv().toLong() and 0xffffffffL)

    fun update(bytes: ByteArray) {
        bytes.forEach { byte ->
            val lookupIndex = (state xor (byte.toInt() and 0xff)) and 0xff
            state = CRC_TABLE[lookupIndex] xor (state ushr 8)
        }
    }

    companion object {
        private val CRC_TABLE: IntArray =
            IntArray(256) { index ->
                var value = index
                repeat(8) {
                    value =
                        if ((value and 1) != 0) {
                            0xedb88320.toInt() xor (value ushr 1)
                        } else {
                            value ushr 1
                        }
                }
                value
            }
    }
}
