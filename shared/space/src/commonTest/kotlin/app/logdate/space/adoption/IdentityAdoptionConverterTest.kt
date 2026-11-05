package app.logdate.space.adoption

import app.logdate.lexicon.app.logdate.crypto.Sealed
import app.logdate.space.Base32
import app.logdate.space.LosslessSealedPayload
import app.logdate.space.LosslessSealedRecordCodec
import app.logdate.space.SealedWrite
import app.logdate.space.SealingKeys
import app.logdate.space.SpaceRef
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.lexicon.data.LexiconData
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IdentityAdoptionConverterTest {
    private val source = SpaceRef.personal("did:web:source.example")
    private val target = SpaceRef.personal("did:web:target.example")
    private val collection = "app.logdate.crypto.sealed"

    @Test
    fun `typed self identities move while peer identities text and extensions remain intact`() =
        runTest {
            val keys = keys()
            for ((type, field) in listOf(
                "app.logdate.people.person" to "connection",
                "app.logdate.graph.connection" to "subject",
                "app.logdate.graph.circleMember" to "subject",
                "app.logdate.graph.block" to "subject",
                "app.logdate.graph.mute" to "subject",
                "app.logdate.share.item" to "author",
            )) {
                for (did in listOf(source.authority, "did:web:peer.example")) {
                    val record =
                        buildJsonObject {
                            put(field, did)
                            put("text", source.authority)
                            put("extension", buildJsonObject { put(field, source.authority) })
                        }
                    val original = payload(type, record)
                    val sealed = seal(keys, source, original)
                    val changed = IdentityAdoptionConverter.reseal(keys, source, target, sealed.rkey, wire(sealed))
                    val actual = LosslessSealedRecordCodec.open(keys, target, target.authority, changed.rkey, changed.record)
                    val mapped = if (did == source.authority) target.authority else did
                    val expectedRecord = JsonObject(record + (field to JsonPrimitive(mapped)))
                    assertEquals(JsonObject(original.value + ("record" to expectedRecord)), actual.value)
                    assertEquals(sealed.rkey, changed.rkey)
                    assertNotEquals(sealed.record.nonce, changed.record.nonce)
                }
            }
        }

    @Test
    fun `same-space relationships and unknown payload fields survive without interpretation`() =
        runTest {
            val keys = keys()
            val original =
                payload(
                    "app.logdate.future.record",
                    buildJsonObject {
                        put(
                            "relation",
                            buildJsonObject {
                                put("collection", "app.logdate.journal.entry")
                                put("rkey", "entry")
                            },
                        )
                        put("future", JsonArray(listOf(JsonNull, JsonPrimitive("雪"))))
                    },
                )
            val sealed = seal(keys, source, original)
            val changed = IdentityAdoptionConverter.reseal(keys, source, target, sealed.rkey, wire(sealed))
            val opened = LosslessSealedRecordCodec.open(keys, target, target.authority, changed.rkey, changed.record)
            assertEquals(original.value, opened.value)
        }

    @Test
    fun `known cryptographic and group authority cannot be moved by copying ciphertext`() =
        runTest {
            val keys = keys()
            for (type in listOf(
                "app.logdate.crypto.sealed",
                "app.logdate.crypto.keyDeclaration",
                "app.logdate.crypto.wrappedKey",
                "app.logdate.space.member",
                "app.logdate.space.groupProfile",
            )) {
                val sealed = seal(keys, source, payload(type, buildJsonObject { put("opaque", "retained") }))
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionConverter.reseal(keys, source, target, sealed.rkey, wire(sealed))
                }
            }
        }

    @Test
    fun `an unknown wire envelope field cannot disappear through typed decoding`() =
        runTest {
            val keys = keys()
            val sealed = seal(keys, source, payload("app.logdate.future.record", JsonObject(emptyMap())))
            val extended = JsonObject(wire(sealed) + ("futureEnvelopeField" to JsonPrimitive("retained")))
            assertFailsWith<IdentityAdoptionException> {
                IdentityAdoptionConverter.reseal(keys, source, target, sealed.rkey, extended)
            }
            assertEquals(JsonPrimitive("retained"), extended["futureEnvelopeField"])
        }

    @Test
    fun `group and unchanged authority are refused`() =
        runTest {
            val keys = keys()
            val sealed = seal(keys, source, payload("app.logdate.future.record", JsonObject(emptyMap())))
            for (destination in listOf(source, SpaceRef(target.authority, "app.logdate.space.group", "group"))) {
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionConverter.reseal(keys, source, destination, sealed.rkey, wire(sealed))
                }
            }
        }

    @Test
    fun `readback requires the deliberately converted full payload and exact source evidence`() =
        runTest {
            val keys = keys()
            val original = payload("app.logdate.people.person", buildJsonObject { put("connection", source.authority) })
            val mappedRecord = buildJsonObject { put("connection", target.authority) }
            val desired = LosslessSealedPayload(JsonObject(original.value + ("record" to mappedRecord)))
            val before = seal(keys, source, original)
            val after = seal(keys, target, desired)
            val unchanged = seal(keys, target, original)
            val expected = IdentityAdoptionExpectedRecord(key(before), 1, digest(wire(before)), false, emptySet())
            val evidence = verify(keys, expected, before, after)
            assertEquals(digest(wire(after)), evidence.targetDigest)
            assertFailsWith<IdentityAdoptionException> { verify(keys, expected, before, unchanged) }
            assertFailsWith<IdentityAdoptionException> { verify(keys, expected.copy(version = 2), before, after) }
            assertFailsWith<IdentityAdoptionException> { verify(keys, expected.copy(digest = Bytes(ByteArray(32))), before, after) }
        }

    @Test
    fun `tombstones retain every marker field and cannot turn into live records`() =
        runTest {
            val marker =
                buildJsonObject {
                    put("deleted", true)
                    put("deletedAt", 12)
                    put("future", "retained")
                }
            val key = IdentityAdoptionWireKey(collection, "~~~~")
            val expected = IdentityAdoptionExpectedRecord(key, 2, digest(marker), true, emptySet())
            assertEquals(true, IdentityAdoptionRecordVerifier.verifyTombstone(expected, 2, marker, key, marker).deleted)
            val changed = JsonObject(marker + ("future" to JsonPrimitive("changed")))
            assertFailsWith<IdentityAdoptionException> {
                IdentityAdoptionRecordVerifier.verifyTombstone(expected, 2, marker, key, changed)
            }
        }

    @Test
    fun `share audiences change only typed person identity and refuse retiring group authority`() =
        runTest {
            val keys = keys()
            val person =
                buildJsonObject {
                    put("\$type", "app.logdate.share.share#personAudience")
                    put("subject", source.authority)
                    put("text", source.authority)
                }
            val circle =
                buildJsonObject {
                    put("\$type", "app.logdate.share.share#circleAudience")
                    put(
                        "circle",
                        buildJsonObject {
                            put("collection", "app.logdate.graph.circle")
                            put("rkey", "same-circle")
                        },
                    )
                }
            val peerGroup =
                buildJsonObject {
                    put("\$type", "app.logdate.share.share#groupAudience")
                    put("space", SpaceRef("did:web:peer.example", "app.logdate.space.group", "group").uri)
                }
            val audience = listOf(person, circle, peerGroup)
            val original = payload("app.logdate.share.share", buildJsonObject { put("audience", JsonArray(audience)) })
            val before = seal(keys, source, original)
            val after = IdentityAdoptionConverter.reseal(keys, source, target, before.rkey, wire(before))
            val opened = LosslessSealedRecordCodec.open(keys, target, target.authority, after.rkey, after.record)
            val mappedPerson = JsonObject(person + ("subject" to JsonPrimitive(target.authority)))
            val desired = buildJsonObject { put("audience", JsonArray(listOf(mappedPerson, circle, peerGroup))) }
            assertEquals(JsonObject(original.value + ("record" to desired)), opened.value)
            for (unsupported in listOf(
                JsonObject(peerGroup + ("space" to JsonPrimitive(SpaceRef(source.authority, "app.logdate.space.group", "g").uri))),
                buildJsonObject { put("\$type", "app.logdate.share.share#futureAudience") },
            )) {
                val record = payload("app.logdate.share.share", buildJsonObject { put("audience", JsonArray(listOf(unsupported))) })
                val sealed = seal(keys, source, record)
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionConverter.reseal(keys, source, target, sealed.rkey, wire(sealed))
                }
            }
        }

    @Test
    fun `optional identity stays absent or null while malformed typed identities refuse`() =
        runTest {
            val keys = keys()
            for (fields in listOf(JsonObject(emptyMap()), buildJsonObject { put("connection", JsonNull) })) {
                val original = payload("app.logdate.people.person", fields)
                val before = seal(keys, source, original)
                val after = IdentityAdoptionConverter.reseal(keys, source, target, before.rkey, wire(before))
                val opened = LosslessSealedRecordCodec.open(keys, target, target.authority, after.rkey, after.record)
                assertEquals(original.value, opened.value)
            }
            for (value in listOf(JsonPrimitive(1), JsonPrimitive("not-a-did"), JsonNull)) {
                val original = payload("app.logdate.graph.connection", buildJsonObject { put("subject", value) })
                val before = seal(keys, source, original)
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionConverter.reseal(keys, source, target, before.rkey, wire(before))
                }
            }
        }

    @Test
    fun `readback refuses unknown wire fields unsupported versions changed keys and unauthenticated ciphertext`() =
        runTest {
            val keys = keys()
            val original = payload("app.logdate.future.record", JsonObject(emptyMap()))
            val before = seal(keys, source, original)
            val after = seal(keys, target, original)
            val expected = IdentityAdoptionExpectedRecord(key(before), 1, digest(wire(before)), false, emptySet())
            val fields: List<Pair<String, JsonElement>> =
                listOf(
                    "future" to JsonPrimitive("retained"),
                    "version" to JsonPrimitive(2),
                    "version" to JsonPrimitive("1"),
                )
            for (field in fields) {
                val raw = JsonObject(wire(after) + field)
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionRecordVerifier.verify(keys, source, target, expected, 1, wire(before), key(after), raw)
                }
                val rawSource = JsonObject(wire(before) + field)
                val extended = expected.copy(digest = digest(rawSource))
                assertFailsWith<IdentityAdoptionException> {
                    IdentityAdoptionRecordVerifier.verify(keys, source, target, extended, 1, rawSource, key(after), wire(after))
                }
            }
            assertFailsWith<IdentityAdoptionException> {
                IdentityAdoptionRecordVerifier.verify(
                    keys,
                    source,
                    target,
                    expected,
                    1,
                    wire(before),
                    key(after).copy(recordKey = "different"),
                    wire(after),
                )
            }
            val bytes = after.record.ciphertext.toByteArray()
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            val damaged = wire(after.copy(record = after.record.copy(ciphertext = Bytes(bytes))))
            assertFailsWith<IdentityAdoptionException> {
                IdentityAdoptionRecordVerifier.verify(keys, source, target, expected, 1, wire(before), key(after), damaged)
            }
        }

    @Test
    fun `blob references inside unknown extension fields require exact declared inventory`() =
        runTest {
            val keys = keys()
            val cid = "b" + Base32.encode(byteArrayOf(1, 0x55, 0x12, 0x20) + ByteArray(32) { 5 })
            val blob =
                buildJsonObject {
                    put("\$type", "blob")
                    put("ref", buildJsonObject { put("\$link", cid) })
                    put("size", 123)
                }
            val original = payload("app.logdate.future.record", buildJsonObject { put("future", JsonArray(listOf(blob))) })
            val before = seal(keys, source, original)
            val after = seal(keys, target, original)
            val expected = IdentityAdoptionExpectedRecord(key(before), 1, digest(wire(before)), false, setOf(cid))
            assertEquals(digest(wire(after)), verify(keys, expected, before, after).targetDigest)
            assertFailsWith<IdentityAdoptionException> { verify(keys, expected.copy(blobs = emptySet()), before, after) }
            assertFailsWith<IdentityAdoptionException> { verify(keys, expected.copy(blobs = setOf("missing")), before, after) }
            val malformed = JsonObject(blob - "ref")
            val badPayload = payload("app.logdate.future.record", buildJsonObject { put("future", malformed) })
            val badBefore = seal(keys, source, badPayload)
            val badAfter = seal(keys, target, badPayload)
            val badExpected = IdentityAdoptionExpectedRecord(key(badBefore), 1, digest(wire(badBefore)), false, emptySet())
            assertFailsWith<IdentityAdoptionException> { verify(keys, badExpected, badBefore, badAfter) }
        }

    private suspend fun verify(
        keys: SealingKeys,
        expected: IdentityAdoptionExpectedRecord,
        before: SealedWrite,
        after: SealedWrite,
    ): IdentityAdoptionRecordEvidence =
        IdentityAdoptionRecordVerifier.verify(
            keys,
            source,
            target,
            expected,
            1,
            wire(before),
            key(after),
            wire(after),
        )

    private fun payload(
        type: String,
        record: JsonObject,
    ): LosslessSealedPayload =
        LosslessSealedPayload(
            buildJsonObject {
                put("collection", type)
                put("rkey", "synthetic")
                put("record", record)
                put("futurePayloadField", JsonArray(listOf(JsonPrimitive("kept"), JsonNull)))
            },
        )

    private suspend fun keys(): SealingKeys = SealingKeys.personal(ByteArray(32) { 7 })

    private suspend fun seal(
        keys: SealingKeys,
        space: SpaceRef,
        payload: LosslessSealedPayload,
    ): SealedWrite = LosslessSealedRecordCodec.seal(keys, space, space.authority, payload)

    private fun wire(write: SealedWrite): JsonObject = LexiconData.json.encodeToJsonElement(Sealed.serializer(), write.record).jsonObject

    private fun key(write: SealedWrite): IdentityAdoptionWireKey = IdentityAdoptionWireKey(collection, write.rkey)

    private suspend fun digest(value: JsonObject): Bytes =
        Bytes(
            CryptographyProvider.Default
                .get(SHA256)
                .hasher()
                .hash(DagCborCodec.encode(value)),
        )
}
