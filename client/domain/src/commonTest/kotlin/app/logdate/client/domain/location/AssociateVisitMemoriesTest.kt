package app.logdate.client.domain.location

import app.logdate.client.domain.location.history.AssociateVisitMemories
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.VisitMemoryLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AssociateVisitMemoriesTest {
    private val start = Instant.parse("2026-09-27T10:00:00Z")
    private val visit = PlaceVisit("morning", start, start + 30.minutes, listOf("a"), 36.0, -115.0, true)
    private val note =
        JournalNote.Text(
            Uuid.parse("00000000-0000-0000-0000-000000000001"),
            start + 10.minutes,
            start + 10.minutes,
            "Coffee with a friend",
            location = NoteLocation(coordinates = NoteCoordinates(36.0, -115.0)),
        )

    @Test
    fun `a morning memory does not appear on the afternoon return`() {
        val afternoon = visit.copy(id = "afternoon", start = start + 4.hours, end = start + 5.hours, evidenceIds = listOf("b"))
        val result = AssociateVisitMemories()(listOf(visit, afternoon), listOf(note), emptyList())
        assertEquals(listOf(note.uid.toString()), result.first().memoryIds)
        assertEquals(emptyList(), result.last().memoryIds)
    }

    @Test
    fun `explicit retrospective link overrides creation time without changing note`() {
        val laterNote = note.copy(creationTimestamp = start + 5.hours, location = null)
        val result = AssociateVisitMemories()(listOf(visit), listOf(laterNote), listOf(VisitMemoryLink(note.uid.toString(), "a")))
        assertEquals(listOf(note.uid.toString()), result.single().memoryIds)
        assertEquals(start + 5.hours, laterNote.creationTimestamp)
    }

    @Test
    fun `ambiguous overlapping visits do not automatically claim memory`() {
        val other = visit.copy(id = "other", evidenceIds = listOf("b"))
        val result = AssociateVisitMemories()(listOf(visit, other), listOf(note), emptyList())
        assertEquals(listOf(emptyList(), emptyList()), result.map { it.memoryIds })
    }

    @Test
    fun `matches note within 5m boundary grace and uncertainty-expanded spatial tolerance`() {
        val wideVisit =
            visit.copy(
                id = "wide",
                evidenceIds = listOf("w"),
                uncertaintyRadiusMeters = 80f,
            )
        // 3 minutes before arrival and ~95m away should still match scored association.
        val edgeNote =
            note.copy(
                creationTimestamp = start - 3.minutes,
                location = NoteLocation(coordinates = NoteCoordinates(36.00085, -115.0)),
            )
        val result = AssociateVisitMemories()(listOf(wideVisit), listOf(edgeNote), emptyList())
        assertEquals(listOf(edgeNote.uid.toString()), result.single().memoryIds)
    }

    @Test
    fun `prefers visit strictly containing timestamp when two candidates overlap`() {
        val earlier = visit.copy(id = "earlier", start = start - 30.minutes, end = start, evidenceIds = listOf("e"))
        val strictlyInsideNote = note.copy(creationTimestamp = start + 2.minutes)
        val result = AssociateVisitMemories()(listOf(earlier, visit), listOf(strictlyInsideNote), emptyList())
        assertEquals(emptyList(), result.first().memoryIds)
        assertEquals(listOf(strictlyInsideNote.uid.toString()), result.last().memoryIds)
    }
}
