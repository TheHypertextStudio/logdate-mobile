@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity

import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryBlock
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryInventory
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryRecord
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryRole
import app.logdate.server.identity.adoption.IdentityAdoptionSourceBinary
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceItem
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.repo.DagCborCodec
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class IdentityAdoptionSourceEvidenceTest {
    @Test fun transportEvidenceIsTheExactPreimageOfTheExistingSourceDigest() {
        val item = item()
        val bytes = IdentityAdoptionSourceEvidence.canonicalItem(IdentityAdoptionSourceKind.SYNC_CONTENT, item)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        assertEquals("63fea4f2cdc0b74406c08a2d04faf03defb6673aa82bbdd942671fcee222d083", HexFormat.of().formatHex(digest))
        assertContentEquals(IdentityAdoptionSourceEvidence.item(IdentityAdoptionSourceKind.SYNC_CONTENT, item), digest)
        assertContentEquals(bytes, DagCborCodec.encode(DagCborCodec.decode(bytes)))
    }

    @Test fun lexicalNumbersAndSqlNullEvidenceRemainDistinctAcrossTransport() {
        val item = item()
        val bytes = IdentityAdoptionSourceEvidence.canonicalItem(IdentityAdoptionSourceKind.SYNC_CONTENT, item)
        val body = DagCborCodec.decode(bytes).jsonObject
        val fields = body.getValue("fields").jsonArray
        assertEquals(JsonPrimitive("object"), fields[0])
        val values = fields[1].jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("0.123456789012345678901234567890"))), values["decimal"])
        assertEquals(JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("9223372036854775808"))), values["large"])
        assertEquals(JsonArray(listOf(JsonPrimitive("null"))), values["absent"])
        assertEquals(JsonArray(listOf(JsonPrimitive("null"))), values["jsonNull"])
        assertEquals(setOf("absent"), body.getValue("sqlNullColumns").jsonObject.keys)
        val changed =
            IdentityAdoptionSourceEvidence.canonicalItem(
                IdentityAdoptionSourceKind.SYNC_CONTENT,
                item.copy(sqlNullColumns = emptySet()),
            )
        assertNotEquals(bytes.toList(), changed.toList())
    }

    @Test fun binaryCommitmentsDoNotEmbedOrSilentlyRewriteTheSourceBody() {
        val item = item().copy(binary = IdentityAdoptionSourceBinary(3, "a".repeat(64), byteArrayOf(1, 2, 3)))
        val bytes = IdentityAdoptionSourceEvidence.canonicalItem(IdentityAdoptionSourceKind.SYNC_CONTENT, item)
        val body =
            DagCborCodec
                .decode(bytes)
                .jsonObject
                .getValue("binary")
                .jsonObject
        assertEquals(JsonPrimitive(3), body["size"])
        assertEquals(JsonPrimitive("a".repeat(64)), body["digest"])
        assertNull(body["bytes"])
        val withoutBody = item.copy(binary = item.binary?.copy(bytes = null))
        assertContentEquals(bytes, IdentityAdoptionSourceEvidence.canonicalItem(IdentityAdoptionSourceKind.SYNC_CONTENT, withoutBody))
    }

    @Test fun sourceSummaryMatchesTheNativeFrozenInventoryScan() {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("app.logdate.identity-adoption.source-items|1\u0000".encodeToByteArray())
        hash.update(IdentityAdoptionSourceEvidence.item(IdentityAdoptionSourceKind.SYNC_CONTENT, item()))
        val items = hash.digest()
        assertEquals("21633c0ab25813fa38f3fb6fa466d0041f2bb92eaf006dfa2aa28cef8f3c84f4", HexFormat.of().formatHex(items))
        val summary = IdentityAdoptionSourceEvidence.summary(IdentityAdoptionSourceKind.SYNC_CONTENT, true, 1, items)
        assertEquals("77c59e886b83f79c4d1300d6dad137929a2647e2589d17d5a9358fb142ca06e8", summary.digest)
        assertEquals(1, summary.count)
        assertNotEquals(
            summary.digest,
            IdentityAdoptionSourceEvidence.summary(IdentityAdoptionSourceKind.SYNC_CONTENT, false, 1, items).digest,
        )
    }

    @Test fun fullCensusMatchesTheNativeRepositoryComposition() {
        val text = checkNotNull(javaClass.getResource("/identity-adoption/repository-inventory-v1.json")).readText()
        val fixture = Json.parseToJsonElement(text).jsonObject
        val expected = fixture.getValue("expected").jsonObject
        val records =
            expected.getValue("records").jsonArray.map { entry ->
                val row = entry.jsonObject
                IdentityAdoptionRepositoryRecord(
                    row.getValue("collection").jsonPrimitive.content,
                    row.getValue("rkey").jsonPrimitive.content,
                    row.getValue("cid").jsonPrimitive.content,
                )
            }
        val blocks =
            expected.getValue("blocks").jsonArray.map { entry ->
                val row = entry.jsonObject
                IdentityAdoptionRepositoryBlock(
                    row.getValue("cid").jsonPrimitive.content,
                    row
                        .getValue("roles")
                        .jsonArray
                        .map { IdentityAdoptionRepositoryRole.valueOf(it.jsonPrimitive.content) }
                        .toSet(),
                )
            }
        val repository = IdentityAdoptionRepositoryInventory(records, blocks)
        val operation =
            IdentityAdoptionOperation(
                "22222222-2222-4222-8222-222222222222",
                IdentityAdoptionBinding(
                    kotlin.uuid.Uuid.parse("11111111-1111-4111-8111-111111111111"),
                    "did:web:source.example",
                    "did:web:target.example",
                    "device-a",
                    0,
                ),
                2,
                IdentityAdoptionPhase.FENCED,
                kotlin.time.Instant.parse("1970-01-01T00:21:40Z"),
            )
        val counts =
            mapOf(
                IdentityAdoptionSourceKind.REPO_HEAD to 1L,
                IdentityAdoptionSourceKind.REPO_COMMIT to
                    fixture
                        .getValue("commits")
                        .jsonArray.size
                        .toLong(),
                IdentityAdoptionSourceKind.REPO_BLOCK to
                    fixture
                        .getValue("blocks")
                        .jsonArray.size
                        .toLong(),
            )
        val summaries = IdentityAdoptionSourceKind.entries.map { IdentityAdoptionSourceSummary(it, true, counts[it] ?: 0, "07".repeat(32)) }
        val digest = IdentityAdoptionSourceEvidence.census(operation, 2, true, summaries, repository)
        assertEquals("f37f929de0b40da046ba4646240901b420adc14d45767972da8ec9de543a0e81", digest)
        assertNotEquals(
            digest,
            IdentityAdoptionSourceEvidence.census(operation, 2, true, summaries, repository.copy(records = records.dropLast(1))),
        )
    }

    @Test fun integerRevisionAboveJavascriptPrecisionRetainsItsTidBits() {
        assertEquals(
            "22c2222222223",
            studio.hypertext.atproto.syntax.Tid
                .fromLong(9_007_199_254_740_993)
                .toString(),
        )
    }

    private fun item() =
        IdentityAdoptionSourceItem(
            listOf("synthetic-note"),
            Json
                .parseToJsonElement(
                    """{"decimal":0.123456789012345678901234567890,"large":9223372036854775808,"absent":null,"jsonNull":null,"future":{"text":"雪"}}""",
                ).jsonObject,
            null,
            setOf("absent"),
        )
}
