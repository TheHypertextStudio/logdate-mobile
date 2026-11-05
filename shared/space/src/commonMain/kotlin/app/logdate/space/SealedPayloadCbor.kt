package app.logdate.space

import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.coroutines.cancellation.CancellationException

/** Bounded validation before an opaque payload reaches the general DAG-CBOR decoder. */
internal object SealedPayloadCbor {
    const val MAX_DEPTH = 128

    fun validate(bytes: ByteArray) {
        try {
            ensure(bytes.size <= SealedPadding.MAX_CIPHERTEXT)
            val reader = Reader(bytes)
            reader.read(0)
            ensure(reader.cursor == bytes.size)
            ensure(DagCborCodec.encode(DagCborCodec.decode(bytes)).contentEquals(bytes))
        } catch (error: CancellationException) {
            throw error
        } catch (error: SealedRecordException) {
            throw error
        } catch (_: Exception) {
            fail()
        }
    }

    private fun ensure(valid: Boolean) {
        if (!valid) fail()
    }

    private fun fail(): Nothing = throw SealedRecordException("The sealed payload is unsupported")

    private class Reader(
        private val bytes: ByteArray,
    ) {
        var cursor = 0
            private set

        fun read(depth: Int) {
            ensure(depth <= MAX_DEPTH)
            val header = byte()
            val info = header and 31
            when (header ushr 5) {
                0, 1 -> argument(info)
                2 -> skipBytes(info)
                3 -> text(info)
                4 -> repeat(count(info)) { read(depth + 1) }
                5 -> readMap(info, depth)
                6 -> readLink(info, depth)
                7 -> ensure(header in 0xf4..0xf6)
                else -> fail()
            }
        }

        private fun readMap(
            info: Int,
            depth: Int,
        ) {
            val count = count(info)
            ensure(count <= (bytes.size - cursor) / 2)
            val keys = mutableSetOf<String>()
            repeat(count) {
                ensure(depth < MAX_DEPTH)
                val header = byte()
                ensure(header ushr 5 == 3)
                ensure(keys.add(text(header and 31)))
                read(depth + 1)
            }
        }

        private fun skipBytes(info: Int) {
            val length = count(info)
            cursor += length
        }

        private fun readLink(
            info: Int,
            depth: Int,
        ) {
            ensure(argument(info) == 42L && depth < MAX_DEPTH)
            val header = byte()
            ensure(header ushr 5 == 2)
            val length = count(header and 31)
            val end = cursor + length
            ensure(length > 1 && byte() == 0)
            CidReader(bytes, cursor, end).validate()
            cursor = end
        }

        private fun text(info: Int): String {
            val length = count(info)
            val text = bytes.decodeToString(cursor, cursor + length, throwOnInvalidSequence = true)
            cursor += length
            return text
        }

        private fun count(info: Int): Int {
            val value = argument(info)
            ensure(value <= bytes.size - cursor)
            return value.toInt()
        }

        private fun argument(info: Int): Long {
            if (info < 24) return info.toLong()
            val width =
                when (info) {
                    24 -> 1
                    25 -> 2
                    26 -> 4
                    27 -> 8
                    else -> fail()
                }
            var value = 0L
            repeat(width) { index ->
                val next = byte()
                ensure(width != 8 || index != 0 || next < 128)
                value = (value shl 8) or next.toLong()
            }
            return value
        }

        private fun byte(): Int {
            ensure(cursor < bytes.size)
            return bytes[cursor++].toInt() and 0xff
        }
    }

    private class CidReader(
        private val bytes: ByteArray,
        private var cursor: Int,
        private val end: Int,
    ) {
        fun validate() {
            ensure(varint() == 1uL && varint() > 0uL)
            varint() // Multihash code, including identity hashes.
            val length = varint()
            ensure(length > 0uL && length == (end - cursor).toULong())
        }

        private fun varint(): ULong {
            var value = 0uL
            repeat(10) { index ->
                ensure(cursor < end)
                val next = bytes[cursor++].toInt() and 0xff
                ensure(index != 9 || next < 2)
                value = value or ((next and 127).toULong() shl (index * 7))
                if (next and 128 == 0) {
                    ensure(index == 0 || next != 0)
                    return value
                }
            }
            fail()
        }
    }
}
