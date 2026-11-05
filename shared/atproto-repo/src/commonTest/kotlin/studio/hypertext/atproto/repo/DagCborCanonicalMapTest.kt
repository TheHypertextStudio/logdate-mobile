package studio.hypertext.atproto.repo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class DagCborCanonicalMapTest {
    @Test
    fun `equal UTF8 length keys use unsigned UTF8 ordering rather than UTF16 ordering`() {
        val value = JsonObject(mapOf("😀a" to JsonPrimitive(1), "\uE000aa" to JsonPrimitive(2)))
        // Both keys are five bytes. EE 80 80 sorts before F0 9F 98 80; UTF16 reverses them.
        val expected = "a265ee808061610265f09f98806101".hexBytes()
        assertContentEquals(expected, DagCborCodec.encode(value))
        assertContentEquals(expected, DagCborCodec.encode(JsonObject(value.toList().reversed().toMap())))
    }

    @Test
    fun `key byte length takes priority over lexical order`() {
        val value = JsonObject(mapOf("aa" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
        assertContentEquals("a261620262616101".hexBytes(), DagCborCodec.encode(value))
    }

    @Test
    fun `a malformed UTF16 string cannot silently turn into replacement text`() {
        assertFails { DagCborCodec.encode(JsonPrimitive("\uD800")) }
        assertFails { DagCborCodec.encode(JsonPrimitive("\uDC00")) }
    }

    @Test
    fun `malformed UTF16 map keys cannot silently collapse onto valid replacement keys`() {
        val value = JsonObject(mapOf("\uD800" to JsonPrimitive(1), "\uFFFD" to JsonPrimitive(2)))
        assertFails { DagCborCodec.encode(value) }
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
