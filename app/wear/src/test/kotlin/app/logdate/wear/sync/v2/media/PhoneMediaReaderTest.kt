package app.logdate.wear.sync.v2.media

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.peer.link.LinkWire
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class PhoneMediaReaderTest {
    private val bytes = "verified phone recording".toByteArray()
    private val identity =
        WatchMediaIdentity(
            PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 1 }), "phone", "watch", LinkMode.PAIR),
            Uuid.random(),
            10,
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            "audio.m4a",
        )
    private var valid = true
    private var failOnce = false
    private var wrongNode = false
    private var closes = 0
    private val offsets = mutableListOf<Long>()
    private val source =
        MediaRangeSource { request ->
            offsets += request.offset
            object : MediaRangeConnection {
                override val identity = request.identity
                override val offset = request.offset
                override val sourceNodeId = if (wrongNode) "other" else identity.peer.phoneNode
                private var cursor = offset.toInt()

                override suspend fun read(buffer: ByteArray): Int {
                    if (failOnce && cursor > offset) {
                        failOnce = false
                        throw IOException("Disconnected")
                    }
                    if (cursor == bytes.size) return -1
                    val count = minOf(3, buffer.size, bytes.size - cursor)
                    bytes.copyInto(buffer, 0, cursor, cursor + count)
                    cursor += count
                    return count
                }

                override suspend fun close() {
                    closes++
                }
            }
        }

    @Test fun `seek reads only the requested range and preserves caller offsets`() =
        runTest {
            val reader = PhoneMediaReader(source, { valid })
            assertEquals(5L, reader.open(identity, 4, 5))
            val result = ByteArray(9)
            assertEquals(3, reader.read(result, 2, 5))
            assertEquals(2, reader.read(result, 5, 4))
            assertEquals(-1, reader.read(result, 0, 9))
            assertContentEquals(bytes.copyOfRange(4, 9), result.copyOfRange(2, 7))
            assertEquals(listOf(4L), offsets)
            reader.close()
            assertEquals(1, closes)
        }

    @Test fun `disconnect resumes from the last delivered byte`() =
        runTest {
            failOnce = true
            val reader = PhoneMediaReader(source, { valid })
            reader.open(identity, 0)
            val output = mutableListOf<Byte>()
            val buffer = ByteArray(8)
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count == -1) break
                output += buffer.take(count)
            }
            reader.close()
            assertContentEquals(bytes, output.toByteArray())
            assertEquals(listOf(0L, 3L), offsets)
            assertEquals(2, closes)
        }

    @Test fun `end position opens without a channel and beyond end is rejected`() =
        runTest {
            val reader = PhoneMediaReader(source, { valid })
            assertEquals(0, reader.open(identity, identity.sizeBytes).toInt())
            assertEquals(-1, reader.read(ByteArray(1), 0, 1))
            reader.close()
            assertFailsWith<IOException> { reader.open(identity, identity.sizeBytes + 1) }
            assertEquals(emptyList(), offsets)
        }

    @Test fun `revocation prevents subsequent delivery and closes the stream`() =
        runTest {
            val reader = PhoneMediaReader(source, { valid })
            reader.open(identity, 0)
            valid = false
            assertFailsWith<IOException> { reader.read(ByteArray(3), 0, 3) }
            reader.close()
            assertEquals(1, closes)
        }

    @Test fun `a different source node is rejected and closed`() =
        runTest {
            wrongNode = true
            val reader = PhoneMediaReader(source, { valid })
            assertFailsWith<IOException> { reader.open(identity, 0) }
            reader.close()
            assertEquals(1, closes)
        }

    @Test fun `full streams check their digest before delivering the final bytes`() =
        runTest {
            val reader = PhoneMediaReader(source, { valid })
            reader.open(identity.copy(sha256 = "0".repeat(64)), 0)
            assertFailsWith<IOException> {
                while (reader.read(ByteArray(8192), 0, 8192) != -1) continue
            }
            reader.close()
        }
}
