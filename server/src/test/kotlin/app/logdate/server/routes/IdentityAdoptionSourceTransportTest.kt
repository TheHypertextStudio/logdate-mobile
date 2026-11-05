package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionSourceBinary
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceItem
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourcePage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityAdoptionSourceTransportTest {
    @Test fun wireByteBudgetKeepsEverySourceItemAndItsOriginalBinary() {
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val binary =
            IdentityAdoptionSourceBinary(
                bytes.size.toLong(),
                HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes),
                ),
                bytes,
            )
        val source =
            listOf("a", "b").map {
                IdentityAdoptionSourceItem(
                    listOf("space", "author", it),
                    buildJsonObject { put("rkey", it) },
                    binary,
                    emptySet(),
                )
            }
        val page =
            IdentityAdoptionSourcePage(
                "synthetic-operation",
                2,
                2,
                true,
                IdentityAdoptionSourceKind.SPACE_RECORD,
                2,
                source,
                null,
            )
        val first = identityAdoptionSourceTransportPage(page)
        assertEquals(2L, first.count)
        assertEquals(1, first.items.size)
        assertEquals(source.first().key, first.next)
        assertContentEquals(bytes, Base64.getUrlDecoder().decode(first.items.single().binary))
        assertContentEquals(
            IdentityAdoptionSourceEvidence.canonicalItem(page.kind, source.first()),
            Base64.getUrlDecoder().decode(first.items.single().evidence),
        )
        val second = identityAdoptionSourceTransportPage(page.copy(items = source.drop(1)))
        assertEquals(1, second.items.size)
        assertNull(second.next)
        assertContentEquals(
            IdentityAdoptionSourceEvidence.canonicalItem(page.kind, source.last()),
            Base64.getUrlDecoder().decode(second.items.single().evidence),
        )
    }

    @Test fun oneValidEvidenceItemMayExceedTheSoftPageBudgetWithoutDisappearing() {
        val number = JsonPrimitive(123)
        val source =
            IdentityAdoptionSourceItem(
                listOf("large-note"),
                buildJsonObject {
                    put("unknown", JsonArray(List(400_000) { number }))
                },
                null,
                emptySet(),
            )
        val evidence = IdentityAdoptionSourceEvidence.canonicalItem(IdentityAdoptionSourceKind.SYNC_CONTENT, source)
        assertTrue(evidence.size > 4 * 1024 * 1024)
        val page =
            IdentityAdoptionSourcePage(
                "synthetic-operation",
                2,
                2,
                true,
                IdentityAdoptionSourceKind.SYNC_CONTENT,
                1,
                listOf(source),
                null,
            )
        val transported = identityAdoptionSourceTransportPage(page)
        assertEquals(1, transported.items.size)
        assertNull(transported.next)
        assertContentEquals(evidence, Base64.getUrlDecoder().decode(transported.items.single().evidence))
    }
}
