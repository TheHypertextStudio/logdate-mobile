package app.logdate.space

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SealedPayloadCborTest {
    @Test
    fun `unsupported CBOR is refused before decoding or allocating containers`() {
        val malformed =
            listOf(
                "8181818181".hexBytes(), // Truncated nesting.
                "9a7fffffff".hexBytes(), // A huge declared array with no elements.
                "ba7fffffff".hexBytes(), // A huge declared map with no keys.
                "a201010202".hexBytes(), // Non-string map keys.
                "a2616101616102".hexBytes(), // Duplicate string keys.
                "61ff".hexBytes(), // Invalid UTF8.
                "f90000".hexBytes(), // Floating-point values are not AT data.
                "c001".hexBytes(), // Unsupported tag.
                "d82a4100".hexBytes(), // A link without a CID.
                "a2616201616102".hexBytes(), // Noncanonical map order.
                "1801".hexBytes(), // Nonminimal integer encoding.
                byteArrayOf(1, 2), // Trailing value.
                ByteArray(129) { 0x81.toByte() } + byteArrayOf(0), // Beyond the supported nesting depth.
            )
        for (bytes in malformed) {
            assertFailsWith<SealedRecordException> { SealedPayloadCbor.validate(bytes) }
        }
    }

    @Test
    fun `canonical values remain readable without dropping values`() {
        SealedPayloadCbor.validate("a2616101616202".hexBytes())
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
