package studio.hypertext.atproto.lexicon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.lexicon.data.Blob
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.lexicon.data.CidLink
import studio.hypertext.atproto.lexicon.data.LexiconData
import studio.hypertext.atproto.lexicon.fixtures.DefsLocation
import studio.hypertext.atproto.lexicon.fixtures.DefsMedia
import studio.hypertext.atproto.lexicon.fixtures.Entry
import studio.hypertext.atproto.lexicon.fixtures.EntryAudioBlock
import studio.hypertext.atproto.lexicon.fixtures.EntryBlocksItem
import studio.hypertext.atproto.lexicon.fixtures.EntryImageBlock
import studio.hypertext.atproto.lexicon.fixtures.EntryTextBlock
import studio.hypertext.atproto.lexicon.fixtures.EntryVideoBlock
import studio.hypertext.atproto.lexicon.fixtures.Geo
import studio.hypertext.atproto.lexicon.fixtures.KitchenSink
import studio.hypertext.atproto.lexicon.fixtures.KitchenSinkLocation
import studio.hypertext.atproto.lexicon.fixtures.KitchenSinkPick
import studio.hypertext.atproto.lexicon.fixtures.Sealed
import studio.hypertext.atproto.repo.Cid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Models generated from this module's fixture schemas must round-trip unchanged through JSON and
 * DAG-CBOR on every platform.
 */
class GeneratedModelRoundTripTest {
    private val json = LexiconData.json

    private fun media(name: String) =
        DefsMedia(
            blob = Blob(ref = CidLink(Cid.rawSha256(name.encodeToByteArray())), mimeType = "application/octet-stream", size = 512),
            mimeType = "image/jpeg",
        )

    private val entry =
        Entry(
            blocks =
                listOf(
                    EntryBlocksItem.TextBlock(EntryTextBlock(id = "t1", text = "A morning at the lake", format = "markdown")),
                    EntryBlocksItem.ImageBlock(EntryImageBlock(id = "i1", media = media("photo"), caption = "Sunrise")),
                    EntryBlocksItem.VideoBlock(EntryVideoBlock(id = "v1", media = media("clip"), durationMs = 4_000)),
                    EntryBlocksItem.AudioBlock(EntryAudioBlock(id = "a1", media = media("voice"), durationMs = 12_000)),
                ),
            createdAt = "2026-10-16T08:30:00.000Z",
            location = DefsLocation(geo = Geo(latitude = "47.6062", longitude = "-122.3321"), accuracyCentimeters = 1_250),
            timeZone = "America/Los_Angeles",
        )

    @Test
    fun `a journal entry round-trips through JSON and DAG-CBOR`() {
        assertRoundTrips(Entry.serializer(), entry)
    }

    @Test
    fun `union members carry their type and a record carries its collection`() {
        val element = json.encodeToJsonElement(Entry.serializer(), entry).jsonObject
        val types =
            element.getValue("blocks").jsonArray.map {
                it.jsonObject
                    .getValue("\$type")
                    .jsonPrimitive.content
            }

        assertEquals("com.example.journal.entry", element.getValue("\$type").jsonPrimitive.content)
        assertEquals(
            listOf(
                "com.example.journal.entry#textBlock",
                "com.example.journal.entry#imageBlock",
                "com.example.journal.entry#videoBlock",
                "com.example.journal.entry#audioBlock",
            ),
            types,
        )
        assertFalse("\$type" in element.getValue("location").jsonObject, "A plain ref must not carry \$type")
    }

    @Test
    fun `optional fields that were absent stay absent`() {
        val element = json.encodeToJsonElement(Entry.serializer(), entry).jsonObject

        assertFalse("updatedAt" in element)
        assertFalse("caption" in element.getValue("blocks").jsonArray[0].jsonObject)
    }

    @Test
    fun `an open union keeps a block type this app doesn't know`() {
        val stickerBlock =
            JsonObject(
                mapOf(
                    "\$type" to JsonPrimitive("com.example.journal.entry#stickerBlock"),
                    "id" to JsonPrimitive("s1"),
                    "sticker" to JsonPrimitive("party"),
                ),
            )
        val source = json.encodeToJsonElement(Entry.serializer(), entry).jsonObject
        val withSticker =
            JsonObject(
                source + ("blocks" to kotlinx.serialization.json.JsonArray(source.getValue("blocks").jsonArray + stickerBlock)),
            )

        val decoded = json.decodeFromJsonElement(Entry.serializer(), withSticker)
        val unknown = assertIs<EntryBlocksItem.Unknown>(decoded.blocks.last())

        assertEquals("com.example.journal.entry#stickerBlock", unknown.type)
        assertEquals(withSticker, json.encodeToJsonElement(Entry.serializer(), decoded))
    }

    @Test
    fun `a closed union rejects a type it doesn't list`() {
        val source = json.encodeToJsonElement(KitchenSink.serializer(), kitchenSink()).jsonObject
        val other = JsonObject(mapOf("\$type" to JsonPrimitive("com.example.other"), "name" to JsonPrimitive("x")))

        val error =
            assertFailsWith<SerializationException> {
                json.decodeFromJsonElement(KitchenSink.serializer(), JsonObject(source + ("pick" to other)))
            }

        assertEquals("\$type com.example.other isn't allowed in closed union com.example.kitchenSink#KitchenSinkPick", error.message)
    }

    @Test
    fun `content links bytes nullable fields and keyword names round-trip`() {
        val value = kitchenSink()
        val element = json.encodeToJsonElement(KitchenSink.serializer(), value).jsonObject

        assertRoundTrips(KitchenSink.serializer(), value)
        assertEquals(JsonNull, element["label"], "A required nullable field is written as null")
        assertEquals("kept", element.getValue("object").jsonPrimitive.content)
        assertEquals(LexiconRecordKey.Literal("self"), KitchenSink.KEY)
    }

    @Test
    fun `members with the same short name and a record member are told apart`() {
        listOf(
            KitchenSinkPick.KitchenSinkLocation(KitchenSinkLocation(name = "Home")),
            KitchenSinkPick.DefsLocation(DefsLocation(geo = Geo(latitude = "0", longitude = "0"))),
            KitchenSinkPick.Entry(entry),
        ).forEach { pick -> assertRoundTrips(KitchenSink.serializer(), kitchenSink(pick)) }
    }

    @Test
    fun `a sealed private record round-trips its bytes`() {
        val sealed =
            Sealed(
                ciphertext = Bytes(ByteArray(48) { (it * 7).toByte() }),
                keyId = "device-key-1",
                nonce = Bytes(ByteArray(24) { (255 - it).toByte() }),
                version = 1,
            )

        assertRoundTrips(Sealed.serializer(), sealed)
        assertTrue(LexiconData.encodeToDagCbor(Sealed.serializer(), sealed).isNotEmpty())
    }

    private fun kitchenSink(pick: KitchenSinkPick = KitchenSinkPick.KitchenSinkLocation(KitchenSinkLocation(name = "Home"))) =
        KitchenSink(
            attachment = CidLink(Cid.rawSha256("attachment".encodeToByteArray())),
            digest = Bytes(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte())),
            label = null,
            `object` = "kept",
            pick = pick,
        )

    private fun <T> assertRoundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        val element = json.encodeToJsonElement(serializer, value)
        assertEquals(value, json.decodeFromJsonElement(serializer, element), "JSON round trip changed the value")
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)), "JSON text round trip")

        val cbor = LexiconData.encodeToDagCbor(serializer, value)
        assertEquals(value, LexiconData.decodeFromDagCbor(serializer, cbor), "DAG-CBOR round trip changed the value")
        assertEquals(cbor.toList(), LexiconData.encodeToDagCbor(serializer, LexiconData.decodeFromDagCbor(serializer, cbor)).toList())
    }
}
