package studio.hypertext.atproto.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Deterministic subset DAG-CBOR encoder and decoder for repo primitives.
 */
@OptIn(ExperimentalEncodingApi::class)
public object DagCborCodec {
    /**
     * Encodes [element] into deterministic DAG-CBOR bytes.
     */
    public fun encode(element: JsonElement): ByteArray = encodeJsonElement(element)

    /**
     * Decodes [bytes] produced by this codec back into a JSON element.
     */
    public fun decode(bytes: ByteArray): JsonElement = Decoder(bytes).decodeElement()

    /**
     * Decodes every DAG-CBOR value in [bytes], one after the other.
     *
     * The repository event stream sends a header and a body back to back in one message, so a
     * single [decode] would read the header and ignore the body.
     */
    public fun decodeSequence(bytes: ByteArray): List<JsonElement> {
        val decoder = Decoder(bytes)
        return buildList { while (decoder.hasMore()) add(decoder.decodeElement()) }
    }

    internal fun link(cid: Cid): JsonObject =
        buildJsonObject {
            put(LINK_KEY, cid.toString())
        }

    internal fun bytes(bytes: ByteArray): JsonObject =
        buildJsonObject {
            put(BYTES_KEY, bytesBase64.encode(bytes))
        }

    internal fun linkOrNull(element: JsonElement?): Cid? {
        val objectValue = element as? JsonObject ?: return null
        if (objectValue.size != 1) {
            return null
        }
        return objectValue[LINK_KEY]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let(Cid::require)
    }

    internal fun bytesOrNull(element: JsonElement?): ByteArray? {
        val objectValue = element as? JsonObject ?: return null
        if (objectValue.size != 1) {
            return null
        }
        val encoded =
            objectValue[BYTES_KEY]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return null
        return bytesBase64.decode(encoded)
    }

    private fun encodeJsonElement(element: JsonElement): ByteArray =
        when (element) {
            is JsonObject -> {
                when {
                    linkOrNull(element) != null -> encodeLink(requireNotNull(linkOrNull(element)))
                    bytesOrNull(element) != null -> encodeByteString(requireNotNull(bytesOrNull(element)))
                    else -> encodeMap(element)
                }
            }

            is JsonArray -> {
                encodeArray(element)
            }

            JsonNull -> {
                byteArrayOf(NULL_SIMPLE_VALUE)
            }

            is JsonPrimitive -> {
                encodePrimitive(element)
            }
        }

    private fun encodeMap(map: JsonObject): ByteArray {
        val entries =
            map.entries.sortedWith(compareBy<Map.Entry<String, JsonElement>>({ it.key.encodeToByteArray().size }, { it.key }))
        val encodedEntries =
            buildList(entries.size * 2) {
                entries.forEach { (key, value) ->
                    add(encodeText(key))
                    add(encodeJsonElement(value))
                }
            }
        return encodeHead(MAJOR_TYPE_MAP, entries.size.toLong()) + encodedEntries.flatten()
    }

    private fun encodeArray(array: JsonArray): ByteArray =
        encodeHead(MAJOR_TYPE_ARRAY, array.size.toLong()) + array.map(::encodeJsonElement).flatten()

    private fun encodeByteString(bytes: ByteArray): ByteArray = encodeHead(MAJOR_TYPE_BYTE_STRING, bytes.size.toLong()) + bytes

    private fun encodeLink(cid: Cid): ByteArray =
        encodeHead(MAJOR_TYPE_TAG, CID_LINK_TAG) + encodeByteString(byteArrayOf(CID_LINK_LEAD_BYTE) + cid.toBytes())

    private fun encodePrimitive(primitive: JsonPrimitive): ByteArray =
        when {
            primitive.isString -> encodeText(primitive.content)
            primitive.booleanOrNull != null -> byteArrayOf(if (primitive.booleanOrNull == true) TRUE_SIMPLE_VALUE else FALSE_SIMPLE_VALUE)
            primitive.longOrNull != null -> encodeLong(primitive.content.toLong())
            else -> error("Unsupported DAG-CBOR primitive: ${primitive.content}")
        }

    private fun encodeText(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return encodeHead(MAJOR_TYPE_TEXT, bytes.size.toLong()) + bytes
    }

    private fun encodeLong(value: Long): ByteArray =
        when {
            value >= 0L -> encodeHead(MAJOR_TYPE_UNSIGNED_INT, value)
            else -> encodeHead(MAJOR_TYPE_NEGATIVE_INT, -(value + 1))
        }

    private fun encodeHead(
        majorType: Int,
        value: Long,
    ): ByteArray {
        require(value >= 0L) { "CBOR values must be non-negative" }
        return when {
            value < SMALL_VALUE_THRESHOLD -> byteArrayOf(((majorType shl MAJOR_TYPE_SHIFT) or value.toInt()).toByte())
            value <= UBYTE_MAX -> byteArrayOf(((majorType shl MAJOR_TYPE_SHIFT) or ONE_BYTE_INFO).toByte(), value.toByte())
            value <= USHORT_MAX -> byteArrayOf(((majorType shl MAJOR_TYPE_SHIFT) or TWO_BYTE_INFO).toByte()) + encodeBigEndian(value, 2)
            value <= UINT_MAX -> byteArrayOf(((majorType shl MAJOR_TYPE_SHIFT) or FOUR_BYTE_INFO).toByte()) + encodeBigEndian(value, 4)
            else -> byteArrayOf(((majorType shl MAJOR_TYPE_SHIFT) or EIGHT_BYTE_INFO).toByte()) + encodeBigEndian(value, 8)
        }
    }

    private fun encodeBigEndian(
        value: Long,
        width: Int,
    ): ByteArray =
        ByteArray(width) { index ->
            val shift = (width - index - 1) * BITS_PER_BYTE
            ((value shr shift) and BYTE_MASK).toByte()
        }

    private fun List<ByteArray>.flatten(): ByteArray {
        val size = sumOf(ByteArray::size)
        val output = ByteArray(size)
        var cursor = 0
        forEach { bytes ->
            bytes.copyInto(output, destinationOffset = cursor)
            cursor += bytes.size
        }
        return output
    }

    private class Decoder(
        private val bytes: ByteArray,
    ) {
        private var cursor: Int = 0

        fun hasMore(): Boolean = cursor < bytes.size

        fun decodeElement(): JsonElement {
            val header = readByte().toInt() and 0xff
            val majorType = header shr MAJOR_TYPE_SHIFT
            val info = header and INFO_MASK
            return when (majorType) {
                MAJOR_TYPE_UNSIGNED_INT -> {
                    JsonPrimitive(readLength(info))
                }

                MAJOR_TYPE_NEGATIVE_INT -> {
                    JsonPrimitive(-(readLength(info) + 1))
                }

                MAJOR_TYPE_BYTE_STRING -> {
                    bytes(readBytes(readLength(info).toInt()))
                }

                MAJOR_TYPE_TEXT -> {
                    JsonPrimitive(readText(readLength(info).toInt()))
                }

                MAJOR_TYPE_ARRAY -> {
                    val size = readLength(info).toInt()
                    JsonArray(List(size) { decodeElement() })
                }

                MAJOR_TYPE_MAP -> {
                    val size = readLength(info).toInt()
                    val entries =
                        buildMap(size) {
                            repeat(size) {
                                val key = decodeElement() as JsonPrimitive
                                put(key.content, decodeElement())
                            }
                        }
                    JsonObject(entries)
                }

                MAJOR_TYPE_TAG -> {
                    decodeTag(info)
                }

                MAJOR_TYPE_SIMPLE -> {
                    when (header.toByte()) {
                        FALSE_SIMPLE_VALUE -> JsonPrimitive(false)
                        TRUE_SIMPLE_VALUE -> JsonPrimitive(true)
                        NULL_SIMPLE_VALUE -> JsonNull
                        else -> error("Unsupported simple value 0x${header.toString(16)}")
                    }
                }

                else -> {
                    error("Unsupported DAG-CBOR major type $majorType")
                }
            }
        }

        private fun decodeTag(info: Int): JsonElement {
            val tag = readLength(info)
            return when (tag) {
                CID_LINK_TAG -> {
                    val tagged = decodeElement()
                    val encodedCid = bytesOrNull(tagged) ?: error("CID link tag must wrap a byte string")
                    require(encodedCid.isNotEmpty() && encodedCid.first() == CID_LINK_LEAD_BYTE) {
                        "CID link payload must begin with 0x00"
                    }
                    link(Cid.fromBytes(encodedCid.copyOfRange(1, encodedCid.size)))
                }

                else -> {
                    error("Unsupported DAG-CBOR tag $tag")
                }
            }
        }

        private fun readLength(info: Int): Long =
            when (info) {
                in 0 until SMALL_VALUE_THRESHOLD.toInt() -> info.toLong()
                ONE_BYTE_INFO -> readUnsigned(1)
                TWO_BYTE_INFO -> readUnsigned(2)
                FOUR_BYTE_INFO -> readUnsigned(4)
                EIGHT_BYTE_INFO -> readUnsigned(8)
                else -> error("Unsupported CBOR length info $info")
            }

        private fun readUnsigned(width: Int): Long {
            var value = 0L
            repeat(width) {
                value = (value shl BITS_PER_BYTE) or ((readByte().toInt() and 0xff).toLong())
            }
            return value
        }

        private fun readText(length: Int): String {
            val bytes = readBytes(length)
            return bytes.decodeToString()
        }

        private fun readBytes(length: Int): ByteArray {
            val end = cursor + length
            require(end <= bytes.size) { "Unexpected end of DAG-CBOR input" }
            val result = bytes.copyOfRange(cursor, end)
            cursor = end
            return result
        }

        private fun readByte(): Byte {
            require(cursor < bytes.size) { "Unexpected end of DAG-CBOR input" }
            return bytes[cursor++]
        }
    }
}

private const val MAJOR_TYPE_SHIFT: Int = 5
private const val MAJOR_TYPE_UNSIGNED_INT: Int = 0
private const val MAJOR_TYPE_NEGATIVE_INT: Int = 1
private const val MAJOR_TYPE_BYTE_STRING: Int = 2
private const val MAJOR_TYPE_TEXT: Int = 3
private const val MAJOR_TYPE_ARRAY: Int = 4
private const val MAJOR_TYPE_MAP: Int = 5
private const val MAJOR_TYPE_TAG: Int = 6
private const val MAJOR_TYPE_SIMPLE: Int = 7
private const val INFO_MASK: Int = 0x1f
private const val SMALL_VALUE_THRESHOLD: Long = 24L
private const val ONE_BYTE_INFO: Int = 24
private const val TWO_BYTE_INFO: Int = 25
private const val FOUR_BYTE_INFO: Int = 26
private const val EIGHT_BYTE_INFO: Int = 27
private const val TRUE_SIMPLE_VALUE: Byte = 0xf5.toByte()
private const val FALSE_SIMPLE_VALUE: Byte = 0xf4.toByte()
private const val NULL_SIMPLE_VALUE: Byte = 0xf6.toByte()
private const val BITS_PER_BYTE: Int = 8
private const val BYTE_MASK: Long = 0xff
private const val UBYTE_MAX: Long = 0xff
private const val USHORT_MAX: Long = 0xffff
private const val UINT_MAX: Long = 0xffff_ffff
private const val CID_LINK_TAG: Long = 42L
private const val CID_LINK_LEAD_BYTE: Byte = 0x00
private const val LINK_KEY: String = "\$link"
private const val BYTES_KEY: String = "\$bytes"

// The data model encodes bytes with the standard base64 alphabet; padding is optional on read.
private val bytesBase64 = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
