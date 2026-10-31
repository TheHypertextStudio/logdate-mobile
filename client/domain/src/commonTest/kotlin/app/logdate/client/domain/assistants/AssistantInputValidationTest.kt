package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantSearchRequest
import app.logdate.client.repository.assistants.AssistantSourceReadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AssistantInputValidationTest {
    private val validation = AssistantInputValidation()

    @Test
    fun `query limit counts supplementary Unicode code points`() {
        assertValid(validation.search(AssistantSearchRequest("🌿".repeat(256))))
        assertInvalid(validation.search(AssistantSearchRequest("🌿".repeat(257))))
        assertInvalid(validation.search(AssistantSearchRequest("  ")))
        assertInvalid(validation.search(AssistantSearchRequest("\uD800")))
    }

    @Test
    fun `search accepts one through ten sources and bounded opaque cursors`() {
        listOf(1, 10).forEach { assertValid(validation.search(AssistantSearchRequest("garden", limit = it))) }
        listOf(0, 11).forEach { assertInvalid(validation.search(AssistantSearchRequest("garden", limit = it))) }
        assertValid(validation.search(AssistantSearchRequest("garden", cursor = "a".repeat(512))))
        listOf("", "a".repeat(513), "cursor with spaces").forEach {
            assertInvalid(validation.search(AssistantSearchRequest("garden", cursor = it)))
        }
    }

    @Test
    fun `calendar dates must be real and ordered`() {
        assertValid(validation.search(AssistantSearchRequest("garden", fromDate = "2026-02-01", throughDate = "2026-02-28")))
        assertValid(validation.search(AssistantSearchRequest("garden", fromDate = "2026-02-01", throughDate = "2026-02-01")))
        listOf("2026-02-30", "tomorrow", "2026-13-01", "").forEach {
            assertInvalid(validation.search(AssistantSearchRequest("garden", fromDate = it)))
            assertInvalid(validation.search(AssistantSearchRequest("garden", throughDate = it)))
        }
        assertInvalid(validation.search(AssistantSearchRequest("garden", fromDate = "2026-02-02", throughDate = "2026-02-01")))
    }

    @Test
    fun `source page sizes are validated`() {
        val request = AssistantSourceReadRequest(AssistantAuthorityFixture.otherId)
        listOf(1, 4000).forEach { assertValid(validation.sourceRead(request.copy(limit = it))) }
        listOf(0, 4001).forEach { assertInvalid(validation.sourceRead(request.copy(limit = it))) }
        assertInvalid(validation.sourceRead(request.copy(cursor = "a".repeat(513))))
    }

    @Test
    fun `input failures contain no submitted text`() {
        val result = validation.search(AssistantSearchRequest("sensitive".repeat(300)))
        assertInvalid(result)
        assertEquals(false, result.toString().contains("sensitive"))
    }

    private fun assertValid(result: AssistantInputDecision) = assertEquals(AssistantInputDecision.Valid, result)

    private fun assertInvalid(result: AssistantInputDecision) = assertIs<AssistantInputDecision.Invalid>(result)
}
