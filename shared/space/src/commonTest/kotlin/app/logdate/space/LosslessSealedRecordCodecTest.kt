package app.logdate.space

import app.logdate.lexicon.app.logdate.crypto.Sealed
import app.logdate.lexicon.app.logdate.crypto.SealedPayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.lexicon.data.CidLink
import studio.hypertext.atproto.lexicon.data.LexiconData
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LosslessSealedRecordCodecTest {
    private val source = SpaceRef.personal("did:web:source.example")
    private val target = SpaceRef.personal("did:web:target.example")
    private val value =
        buildJsonObject {
            put("collection", "app.logdate.future.record")
            put("rkey", "synthetic")
            put("futurePayload", JsonArray(listOf(JsonPrimitive("雪"), JsonNull)))
            put("record", buildJsonObject { put("futureField", "synthetic private text") })
        }

    @Test
    fun `published envelope vectors open through the lossless path`() =
        runTest {
            for (entry in ConformanceFixtures.envelopes) {
                val keys = SealingKeys.personal(entry.string("identityKey").hexToBytes())
                val space = requireNotNull(SpaceRef.parse(entry.string("space")))
                val payload = expanded(entry.obj("payload"), entry.list("expand"))
                val record =
                    entry["envelope"]?.let { LexiconData.json.decodeFromJsonElement(Sealed.serializer(), it) }
                        ?: SealedRecordCodec
                            .sealWithNonce(
                                keys,
                                space,
                                entry.string("author"),
                                LexiconData.json.decodeFromJsonElement(SealedPayload.serializer(), payload),
                                entry.string("nonce").hexToBytes(),
                            ).record
                val opened =
                    LosslessSealedRecordCodec.open(
                        keys,
                        space,
                        entry.string("author"),
                        entry.string("sealedRkey"),
                        record,
                    )
                assertEquals(payload, opened.value)
            }
        }

    @Test
    fun `long binary extensions CID links and integer boundaries retain their AT values`() =
        runTest {
            val bytes = ByteArray(256) { it.toByte() }
            val extra =
                buildJsonObject {
                    put("binary", LexiconData.json.encodeToJsonElement(Bytes.serializer(), Bytes(bytes)))
                    put("link", LexiconData.json.encodeToJsonElement(CidLink.serializer(), CidLink(Cid.rawSha256(bytes))))
                    put("minimum", Long.MIN_VALUE)
                    put("maximum", Long.MAX_VALUE)
                }
            val expected = JsonObject(value + ("futureExtension" to extra))
            val keys = SealingKeys.personal(ByteArray(32) { 7 })
            val sealed = LosslessSealedRecordCodec.seal(keys, source, source.authority, LosslessSealedPayload(expected))
            val opened = LosslessSealedRecordCodec.open(keys, source, source.authority, sealed.rkey, sealed.record)
            assertEquals(expected, opened.value)
        }

    @Test
    fun `unknown payload and record fields survive new authority and fresh nonces`() =
        runTest {
            val keys = SealingKeys.personal(ByteArray(32) { 7 })
            val first = LosslessSealedRecordCodec.seal(keys, source, source.authority, LosslessSealedPayload(value))
            val opened = LosslessSealedRecordCodec.open(keys, source, source.authority, first.rkey, first.record)
            assertEquals(value, opened.value)
            val changed = LosslessSealedRecordCodec.seal(keys, target, target.authority, opened)
            assertEquals(first.rkey, changed.rkey)
            assertNotEquals(first.record.nonce, changed.record.nonce)
            val readback = LosslessSealedRecordCodec.open(keys, target, target.authority, changed.rkey, changed.record)
            assertEquals(value, readback.value)
            assertFailsWith<SealedRecordException> {
                LosslessSealedRecordCodec.open(keys, source, source.authority, changed.rkey, changed.record)
            }
        }

    @Test
    fun `an independently sealed complete payload retains all outer fields`() =
        runTest {
            val keys = SealingKeys.personal(ByteArray(32) { 7 })
            val rkey = SealedRecordCodec.sealedRkey(keys, "app.logdate.future.record", "synthetic")
            val plaintext = SealedPadding.pad(DagCborCodec.encode(value))
            val record = SealedRecordCodec.encrypt(keys, source, source.authority, rkey, plaintext)
            assertEquals(value, LosslessSealedRecordCodec.open(keys, source, source.authority, rkey, record).value)
        }

    @Test
    fun `authenticated duplicate fields and trailing data are refused rather than normalized`() =
        runTest {
            val encoded = DagCborCodec.encode(value)
            val extraRecord = DagCborCodec.encode(JsonPrimitive("record")) + byteArrayOf(0xa0.toByte())
            val duplicated = encoded.copyOf().also { it[0] = 0xa5.toByte() } + extraRecord
            val keys = SealingKeys.personal(ByteArray(32) { 7 })
            val rkey = SealedRecordCodec.sealedRkey(keys, "app.logdate.future.record", "synthetic")
            for (bytes in listOf(encoded + byteArrayOf(1), duplicated)) {
                val record =
                    SealedRecordCodec.encrypt(
                        keys,
                        source,
                        source.authority,
                        rkey,
                        SealedPadding.pad(bytes),
                    )
                assertFailsWith<SealedRecordException> {
                    LosslessSealedRecordCodec.open(keys, source, source.authority, rkey, record)
                }
            }
        }

    @Test
    fun `the payload shape must have string identity fields and a present record`() {
        for (wrong in listOf(
            JsonObject(value - "record"),
            JsonObject(value + ("collection" to JsonPrimitive(true))),
            JsonObject(value + ("rkey" to JsonPrimitive(1))),
        )) {
            assertFailsWith<SealedRecordException> { LosslessSealedPayload(wrong) }
        }
    }

    @Test
    fun `tampering is refused without echoing private values`() =
        runTest {
            val keys = SealingKeys.personal(ByteArray(32) { 7 })
            val write = LosslessSealedRecordCodec.seal(keys, source, source.authority, LosslessSealedPayload(value))
            val damaged =
                write.record.ciphertext
                    .toByteArray()
                    .also { it[0] = (it[0].toInt() xor 1).toByte() }
            val error =
                assertFailsWith<SealedRecordException> {
                    LosslessSealedRecordCodec.open(
                        keys,
                        source,
                        source.authority,
                        write.rkey,
                        write.record.copy(ciphertext = Bytes(damaged)),
                    )
                }
            val messages = generateSequence<Throwable>(error) { it.cause }.joinToString { it.message.orEmpty() }
            assertTrue("synthetic private text" !in messages)
        }

    @Test
    fun `oversized and excessively nested input cannot reach encryption`() =
        runTest {
            val keys = SealingKeys.personal(ByteArray(32) { 7 })
            var nested: kotlinx.serialization.json.JsonElement = JsonNull
            repeat(129) { nested = JsonArray(listOf(nested)) }
            val tooDeep = LosslessSealedPayload(JsonObject(value + ("record" to nested)))
            assertFailsWith<SealedRecordException> {
                LosslessSealedRecordCodec.seal(keys, source, source.authority, tooDeep)
            }
            val oversized = LosslessSealedPayload(JsonObject(value + ("record" to JsonPrimitive("x".repeat(4_000_000)))))
            assertFailsWith<SealedRecordException> {
                LosslessSealedRecordCodec.seal(keys, source, source.authority, oversized)
            }
        }
}
