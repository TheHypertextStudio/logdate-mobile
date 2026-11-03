package app.logdate.client.repository.assistants

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class AssistantMetadataBoundsTest {
    private val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val hit = AssistantSearchHit(id, "Title", "Excerpt", "logdate://entry/$id")

    @Test
    fun `titles and source links are bounded including supplementary characters`() {
        encode(hit.copy(title = "🌿".repeat(280), sourceUrl = "a".repeat(2048)))
        listOf(
            hit.copy(title = "a".repeat(281)),
            hit.copy(title = "🌿".repeat(281)),
            hit.copy(title = "\uD800"),
            hit.copy(sourceUrl = "a".repeat(2049)),
            hit.copy(sourceUrl = "\uDC00"),
            hit.copy(sourceUrl = ""),
        ).forEach { source -> assertFailsWith<IllegalArgumentException> { encode(source) } }
    }

    @Test
    fun `search and source cursors use the same opaque bounds as input`() {
        val search = AssistantSearchResult(listOf(hit), AssistantCoverage(), nextCursor = "a".repeat(512))
        val page = AssistantSourcePage(id, "text", hit.sourceUrl, AssistantCoverage(), nextCursor = "a".repeat(512))
        AssistantProtocol.encodeSearchResult(search)
        AssistantProtocol.encodeSourcePage(page)
        listOf("", "a".repeat(513), "not opaque", "../other", "\uD800").forEach { cursor ->
            assertFailsWith<IllegalArgumentException> { AssistantProtocol.encodeSearchResult(search.copy(nextCursor = cursor)) }
            assertFailsWith<IllegalArgumentException> { AssistantProtocol.encodeSourcePage(page.copy(nextCursor = cursor)) }
        }
        assertFailsWith<IllegalArgumentException> { AssistantProtocol.encodeSourcePage(page.copy(sourceUrl = "a".repeat(2049))) }
    }

    private fun encode(source: AssistantSearchHit) =
        AssistantProtocol.encodeSearchResult(AssistantSearchResult(listOf(source), AssistantCoverage()))
}
