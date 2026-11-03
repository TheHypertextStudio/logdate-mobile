package app.logdate.client.repository.assistants

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class AssistantProtocolTest {
    @Test
    fun `wire results bound excerpts and source pages by Unicode code point`() {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val hit = AssistantSearchHit(id, null, "🌿".repeat(280), "logdate://entry/$id")
        AssistantProtocol.encodeSearchResult(AssistantSearchResult(listOf(hit), AssistantCoverage()))
        assertFailsWith<IllegalArgumentException> {
            AssistantProtocol.encodeSearchResult(
                AssistantSearchResult(listOf(hit.copy(excerpt = "🌿".repeat(281))), AssistantCoverage()),
            )
        }
        val page = AssistantSourcePage(id, "🌿".repeat(4000), "logdate://entry/$id", AssistantCoverage())
        AssistantProtocol.encodeSourcePage(page)
        assertFailsWith<IllegalArgumentException> { AssistantProtocol.encodeSourcePage(page.copy(text = "🌿".repeat(4001))) }
    }

    @Test
    fun `wire results bound source count and preserve dates and coverage`() {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val time = kotlin.time.Instant.parse("2026-10-20T12:00:00Z")
        val hit = AssistantSearchHit(id, null, "garden", "logdate://entry/$id", eventTime = time, createdAt = time)
        val result = AssistantSearchResult(List(10) { hit }, AssistantCoverage())
        val encoded = AssistantProtocol.encodeSearchResult(result)
        assertEquals(true, encoded.contains("\"eventTime\":\"2026-10-20T12:00:00Z\""))
        assertEquals(true, encoded.contains("\"createdAt\":\"2026-10-20T12:00:00Z\""))
        assertEquals(true, encoded.contains("\"partial\":true"))
        assertFailsWith<IllegalArgumentException> { AssistantProtocol.encodeSearchResult(result.copy(sources = List(11) { hit })) }
    }

    @Test
    fun `unknown grant credentials and extra fields are rejected`() {
        listOf(
            "\"grantId\":\"other\"",
            "\"credentials\":\"secret\"",
            "\"unexpected\":{}",
        ).forEach { extra ->
            assertIs<AssistantDecodeResult.Invalid>(AssistantProtocol.decodeSearch("""{"query":"garden",$extra}"""))
        }
    }

    @Test
    fun `strict decoder rejects malformed types missing fields and lenient syntax`() {
        listOf(
            """{"query":123}""",
            """{"limit":10}""",
            """{query:"garden"}""",
            """{"query":"garden",}""",
        ).forEach { assertIs<AssistantDecodeResult.Invalid>(AssistantProtocol.decodeSearch(it)) }
    }

    @Test
    fun `search and source requests use their own strict contracts`() {
        val id = "00000000-0000-0000-0000-000000000001"
        val search = AssistantProtocol.decodeSearch("""{"query":"garden","limit":10,"fromDate":"2026-01-01"}""")
        assertEquals("garden", assertIs<AssistantDecodeResult.Decoded<AssistantSearchRequest>>(search).value.query)
        val source = AssistantProtocol.decodeSourceRead("""{"sourceId":"$id","limit":4000}""")
        assertEquals(Uuid.parse(id), assertIs<AssistantDecodeResult.Decoded<AssistantSourceReadRequest>>(source).value.sourceId)
        assertIs<AssistantDecodeResult.Invalid>(AssistantProtocol.decodeSourceRead("""{"sourceId":"invalid"}"""))
        assertIs<AssistantDecodeResult.Invalid>(AssistantProtocol.decodeSearch("""{"query":"garden","grantId":"other"}"""))
    }

    @Test
    fun `capabilities serialize only to the approved scope strings`() {
        assertEquals(
            mapOf(
                AssistantCapability.READ_MEMORIES to "\"memories:read\"",
                AssistantCapability.READ_PLACES to "\"places:read\"",
                AssistantCapability.READ_PEOPLE to "\"people:read\"",
                AssistantCapability.START_CAPTURE to "\"capture:start\"",
            ),
            AssistantCapability.entries.associateWith(AssistantProtocol::encodeCapability),
        )
    }

    @Test
    fun `default diagnostic strings omit query and source payloads`() {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val sensitive = "do not log this private body"
        val values =
            listOf(
                AssistantSearchRequest(sensitive, cursor = "private-cursor"),
                AssistantSearchHit(id, sensitive, sensitive, "logdate://entry/$id"),
                AssistantSourcePage(id, sensitive, "logdate://entry/$id", AssistantCoverage()),
                AssistantDecodeResult.Decoded(AssistantSearchRequest(sensitive)),
            )
        values.forEach {
            assertFalse(it.toString().contains(sensitive))
            assertFalse(it.toString().contains("private-"))
        }
        val invalid = AssistantProtocol.decodeSearch("""{"query":"$sensitive","approved":true}""")
        assertIs<AssistantDecodeResult.Invalid>(invalid)
        assertFalse(invalid.toString().contains(sensitive))
    }
}
