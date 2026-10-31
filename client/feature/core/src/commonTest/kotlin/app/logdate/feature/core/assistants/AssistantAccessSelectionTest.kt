package app.logdate.feature.core.assistants

import app.logdate.client.repository.assistants.AssistantCapability.READ_MEMORIES
import app.logdate.client.repository.assistants.AssistantCapability.READ_PEOPLE
import app.logdate.client.repository.assistants.AssistantCapability.READ_PLACES
import app.logdate.client.repository.assistants.AssistantCapability.START_CAPTURE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AssistantAccessSelectionTest {
    private val travel = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val notes = Uuid.parse("00000000-0000-4000-8000-000000000002")
    private val journals = setOf(travel, notes)
    private val requested = setOf(READ_MEMORIES, READ_PLACES, READ_PEOPLE, START_CAPTURE)

    @Test
    fun `nothing is allowed until the person turns something on`() {
        assertNull(AssistantAccessSelection().grant(requested, journals))
    }

    @Test
    fun `places alone grant no access to entries`() {
        val grant = assertNotNull(AssistantAccessSelection().withCapability(READ_PLACES, true, journals).grant(requested, journals))
        assertEquals(setOf(READ_PLACES), grant.capabilities)
        assertTrue(grant.contentScope.journalIds.isEmpty())
        assertFalse(grant.contentScope.includeUnfiled)
    }

    @Test
    fun `turning entries on shares every journal until narrowed and turning it off clears them`() {
        val on = AssistantAccessSelection().withCapability(READ_MEMORIES, true, journals)
        assertEquals(journals, on.journalIds)
        assertTrue(on.includeUnfiled)
        val narrowed = on.withEntries(setOf(travel), includeUnfiled = false)
        val grant = assertNotNull(narrowed.grant(requested, journals))
        assertEquals(setOf(travel), grant.contentScope.journalIds)
        assertFalse(grant.contentScope.includeUnfiled)
        val off = narrowed.withCapability(READ_MEMORIES, false, journals)
        assertFalse(READ_MEMORIES in off.capabilities)
        assertTrue(off.journalIds.isEmpty())
    }

    @Test
    fun `narrowing entries to nothing turns entries off`() {
        val selection =
            AssistantAccessSelection()
                .withCapability(READ_MEMORIES, true, journals)
                .withEntries(emptySet(), includeUnfiled = false)
        assertFalse(READ_MEMORIES in selection.capabilities)
        assertNull(selection.grant(requested, journals))
    }

    @Test
    fun `access the assistant did not ask for cannot be granted`() {
        val selection = AssistantAccessSelection().withCapability(READ_PEOPLE, true, journals)
        assertNull(selection.grant(setOf(READ_PLACES), journals))
    }

    @Test
    fun `a journal removed while the sheet is open cannot be granted`() {
        val selection = AssistantAccessSelection().withCapability(READ_MEMORIES, true, journals)
        assertNull(selection.grant(requested, setOf(travel)))
    }

    @Test
    fun `diagnostic strings never contain identifiers or names`() {
        val selection = AssistantAccessSelection().withCapability(READ_MEMORIES, true, journals)
        assertFalse(selection.toString().contains(travel.toString()))
        assertFalse(AssistantJournalOption(travel, "Travel").toString().contains("Travel"))
        assertFalse(AssistantActivity(READ_PLACES, "Looked at October places", "Yesterday").toString().contains("October"))
    }
}
