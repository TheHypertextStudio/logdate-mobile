package studio.hypertext.atproto.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * DAG-CBOR blocks and CAR archives arrive from other servers and from anyone who calls an upload or
 * import method. These tests feed the decoders lengths and counts larger than their input, integers
 * outside 64 signed bits, deep nesting and random bytes, and require each to fail as malformed input
 * without allocating what the input claims or exhausting the stack.
 */
class UntrustedInputBoundsTest {
    private val eightByteMax =
        byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
    private val fourByteMax = byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())

    @Test
    fun `a byte or text string longer than the input is refused`() {
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0x5b) + eightByteMax) }
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0x7a) + fourByteMax + byteArrayOf(0x61)) }
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0x5b, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)) }
    }

    @Test
    fun `an array or map with more items than the input could hold is refused before it is allocated`() {
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0x9a.toByte()) + fourByteMax) }
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0x9b.toByte()) + eightByteMax) }
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0xba.toByte()) + fourByteMax) }
        // A map of one entry needs at least two more bytes, not one.
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0xa1.toByte(), 0x60)) }
    }

    @Test
    fun `integers outside 64 signed bits are refused and the extremes still decode`() {
        val tooLarge = byteArrayOf(0x1b, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(tooLarge) }
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(byteArrayOf(0x3b) + tooLarge.copyOfRange(1, 9)) }

        assertEquals(JsonPrimitive(Long.MAX_VALUE), DagCborCodec.decode(byteArrayOf(0x1b) + eightByteMax))
        assertEquals(JsonPrimitive(Long.MIN_VALUE), DagCborCodec.decode(byteArrayOf(0x3b) + eightByteMax))
    }

    @Test
    fun `map keys must be text`() {
        assertFailsWith<IllegalStateException> { DagCborCodec.decode(byteArrayOf(0xa1.toByte(), 0x01, 0x02)) }
    }

    @Test
    fun `values nested deeper than the limit are refused without exhausting the stack`() {
        val deep = ByteArray(100_000) { 0x81.toByte() } + byteArrayOf(0x00)
        assertFailsWith<IllegalArgumentException> { DagCborCodec.decode(deep) }

        val allowed = ByteArray(127) { 0x81.toByte() } + byteArrayOf(0x00)
        var element = DagCborCodec.decode(allowed)
        repeat(127) { element = (element as JsonArray).single() }
        assertEquals(JsonPrimitive(0L), element)
    }

    @Test
    fun `well formed values still round trip`() {
        val value =
            JsonObject(
                mapOf(
                    "text" to JsonPrimitive("hello"),
                    "list" to JsonArray(listOf(JsonPrimitive(1L), JsonPrimitive(-1L), JsonPrimitive(true))),
                    "bytes" to DagCborCodec.bytes(byteArrayOf(1, 2, 3)),
                ),
            )
        assertEquals(value, DagCborCodec.decode(DagCborCodec.encode(value)))
    }

    @Test
    fun `random bytes only ever fail as malformed DAG-CBOR`() {
        val random = Random(20261030)
        repeat(20_000) { attempt ->
            val input = random.nextBytes(random.nextInt(1, 48))
            assertMalformedOrDecoded("DAG-CBOR input #$attempt") { DagCborCodec.decodeSequence(input) }
        }
    }

    @Test
    fun `a CAR section longer than the archive is refused`() {
        val varintOfMaxLong = ByteArray(8) { 0xff.toByte() } + byteArrayOf(0x7f)
        assertFailsWith<IllegalArgumentException> { CarCodec.readCar(varintOfMaxLong) }
        // 0xffffffff truncated to an Int used to be negative.
        assertFailsWith<IllegalArgumentException> {
            CarCodec.readCar(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f))
        }
    }

    @Test
    fun `a CAR varint longer than nine bytes is refused`() {
        assertFailsWith<IllegalArgumentException> { CarCodec.readCar(ByteArray(12) { 0x80.toByte() } + byteArrayOf(0x01)) }
    }

    @Test
    fun `a CAR block whose CID digest is longer than the block is refused`() {
        val archive = CarCodec.writeCar(emptyList(), emptyList())
        // version 1, dag-cbor, sha2-256, then a digest length far past the end of the section.
        val block = byteArrayOf(0x01, 0x71, 0x12, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07)
        assertFailsWith<IllegalArgumentException> { CarCodec.readCar(archive + byteArrayOf(block.size.toByte()) + block) }
    }

    @Test
    fun `random bytes only ever fail as a malformed CAR archive`() {
        val random = Random(30102026)
        val header = CarCodec.writeCar(emptyList(), emptyList())
        repeat(5_000) { attempt ->
            val tail = random.nextBytes(random.nextInt(0, 64))
            assertMalformedOrDecoded("CAR input #$attempt") { CarCodec.readCar(if (attempt % 2 == 0) header + tail else tail) }
        }
    }

    private fun assertMalformedOrDecoded(
        label: String,
        decode: () -> Unit,
    ) {
        try {
            decode()
        } catch (_: IllegalArgumentException) {
            // Malformed input.
        } catch (_: IllegalStateException) {
            // Malformed input.
        } catch (other: Throwable) {
            fail("$label failed with ${other::class.simpleName}: ${other.message}")
        }
    }
}
