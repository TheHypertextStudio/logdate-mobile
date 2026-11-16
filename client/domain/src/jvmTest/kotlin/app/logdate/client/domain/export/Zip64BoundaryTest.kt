package app.logdate.client.domain.export

import app.logdate.client.domain.restore.ArchiveZipReader
import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Timeout
import okio.blackholeSink
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Writes and reads back an archive larger than 4 GiB without storing its payload: the archive goes
 * to a sink that keeps runs of zero bytes as counts, and the reader sees them through a file
 * handle over those runs.
 */
class Zip64BoundaryTest {
    @Test
    fun `an export larger than 4 GiB keeps every entry readable`() {
        val fileSystem = RunLengthFileSystem()
        val archive = "/export.zip".toPath()
        val largeSize = (1L shl 32) + 4_096

        ZipArchiveWriter(fileSystem).write(
            archive,
            listOf(
                ZipArchiveEntry.Bytes("metadata.json", """{"version":"2.0"}""".encodeToByteArray()),
                ZipArchiveEntry.Streaming("media/long-recording.wav") { sink -> sink.writeZeros(largeSize) },
                ZipArchiveEntry.Bytes("after.json", """{"notes":[]}""".encodeToByteArray()),
            ),
        )

        val reader = ArchiveZipReader(fileSystem.handle())
        assertEquals(listOf("metadata.json", "media/long-recording.wav", "after.json"), reader.entryNames)
        assertEquals("""{"notes":[]}""", reader.source("after.json").buffer().use { it.readUtf8() })
        assertEquals("""{"version":"2.0"}""", reader.source("metadata.json").buffer().use { it.readUtf8() })
        val copied = reader.source("media/long-recording.wav").buffer().use { it.readAll(blackholeSink()) }
        assertEquals(largeSize, copied)
    }

    private fun okio.BufferedSink.writeZeros(count: Long) {
        val chunk = ByteArray(CHUNK)
        var remaining = count
        while (remaining > 0) {
            val next = minOf(remaining, CHUNK.toLong()).toInt()
            write(chunk, 0, next)
            remaining -= next
        }
    }

    /** Keeps written bytes as literal chunks and zero runs, so gigabytes of zeros cost a few bytes. */
    private class RunLengthFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
        private val runs = mutableListOf<Run>()

        override fun sink(
            file: Path,
            mustCreate: Boolean,
        ): Sink =
            object : Sink {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    val bytes = source.readByteArray(byteCount)
                    val last = runs.lastOrNull()
                    when {
                        bytes.all { it == 0.toByte() } && last is Run.Zeros -> last.length += bytes.size
                        bytes.all { it == 0.toByte() } -> runs += Run.Zeros(bytes.size.toLong())
                        else -> runs += Run.Literal(bytes)
                    }
                }

                override fun flush() = Unit

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }

        fun handle(): FileHandle = RunLengthFileHandle(runs.toList())
    }

    private sealed interface Run {
        val size: Long

        class Zeros(
            var length: Long,
        ) : Run {
            override val size: Long get() = length
        }

        class Literal(
            val bytes: ByteArray,
        ) : Run {
            override val size: Long get() = bytes.size.toLong()
        }
    }

    private class RunLengthFileHandle(
        private val runs: List<Run>,
    ) : FileHandle(readWrite = false) {
        private val starts = runs.runningFold(0L) { offset, run -> offset + run.size }

        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ): Int {
            if (fileOffset >= starts.last()) return -1
            val index = starts.binarySearch(fileOffset).let { if (it >= 0) it else -it - 2 }
            val within = fileOffset - starts[index]
            val count = minOf(byteCount.toLong(), runs[index].size - within).toInt()
            when (val run = runs[index]) {
                is Run.Zeros -> array.fill(0, arrayOffset, arrayOffset + count)
                is Run.Literal -> run.bytes.copyInto(array, arrayOffset, within.toInt(), within.toInt() + count)
            }
            return count
        }

        override fun protectedSize(): Long = starts.last()

        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int,
        ) = error("read only")

        override fun protectedFlush() = Unit

        override fun protectedResize(size: Long) = error("read only")

        override fun protectedClose() = Unit
    }

    private companion object {
        const val CHUNK = 1 shl 16
    }
}
