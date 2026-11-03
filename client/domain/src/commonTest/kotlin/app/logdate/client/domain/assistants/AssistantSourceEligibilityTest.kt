package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantSourceKind
import app.logdate.client.repository.assistants.AssistantSourceMetadata
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantSourceEligibilityTest {
    private val fixture = AssistantAuthorityFixture
    private val eligibility = AssistantSourceEligibility()
    private val source =
        AssistantSourceMetadata(
            id = fixture.otherId,
            journalIds = setOf(fixture.journalId),
            kind = AssistantSourceKind.TEXT,
            shared = false,
        )

    @Test
    fun `approved text and existing transcript are eligible`() {
        assertTrue(eligibility.allows(fixture.grant, source))
        assertTrue(eligibility.allows(fixture.grant, source.copy(kind = AssistantSourceKind.TRANSCRIPT)))
    }

    @Test
    fun `all memberships must be approved including future journals`() {
        assertFalse(eligibility.allows(fixture.grant, source.copy(journalIds = setOf(fixture.journalId, fixture.otherId))))
        assertFalse(eligibility.allows(fixture.grant, source.copy(journalIds = setOf(fixture.otherId))))
    }

    @Test
    fun `explicit exclusions shared sources and original media are denied`() {
        val excluded = fixture.grant.copy(contentScope = fixture.grant.contentScope.copy(excludedEntryIds = setOf(source.id)))
        assertFalse(eligibility.allows(excluded, source))
        assertFalse(eligibility.allows(fixture.grant, source.copy(shared = true)))
        assertFalse(eligibility.allows(fixture.grant, source.copy(kind = AssistantSourceKind.ORIGINAL_MEDIA)))
        assertFalse(eligibility.allows(fixture.grant, source.copy(kind = AssistantSourceKind.UNSUPPORTED)))
    }

    @Test
    fun `unfiled sources require separate consent and exclusions still apply`() {
        val unfiled = source.copy(journalIds = emptySet())
        val permitted = fixture.grant.copy(contentScope = fixture.grant.contentScope.copy(includeUnfiled = true))
        assertFalse(eligibility.allows(fixture.grant, unfiled))
        assertTrue(eligibility.allows(permitted, unfiled))
        assertFalse(
            eligibility.allows(permitted.copy(contentScope = permitted.contentScope.copy(excludedEntryIds = setOf(source.id))), unfiled),
        )
    }

    @Test
    fun `knowing a source identifier never substitutes for read permission`() {
        val withoutRead = fixture.grant.copy(capabilities = emptySet())
        assertFalse(eligibility.allows(withoutRead, source))
    }
}
