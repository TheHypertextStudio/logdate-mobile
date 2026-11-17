package app.logdate.client.domain.timeline

import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.Person
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TimelineContentRetentionTest {
    private val at = Instant.parse("2026-10-28T12:00:00Z")
    private val note = JournalNote.Text(content = "Retained source", creationTimestamp = at, lastUpdated = at)
    private val loaded =
        TimelineDay(at, at, "Available summary", LocalDate(2026, 10, 28), people = listOf(Person(name = "Alex")), entries = listOf(note))

    @Test
    fun anAuthoritativeDeletionInvalidatesTheRetainedFullDaySummary() {
        val another = note.copy(uid = kotlin.uuid.Uuid.random(), content = "Deleted source")
        val fullDay = loaded.copy(entries = listOf(note, another))
        val pending = loaded.copy(tldr = "", people = emptyList(), isRecentWindow = true)
        val retained = retainAvailableTimelineSummaries(Timeline(listOf(fullDay)), Timeline(listOf(pending)))
        val deleted = loaded.copy(tldr = "", people = emptyList(), summaryUnavailableReason = TimelineSummaryUnavailableReason.NO_NETWORK)
        val result = retainAvailableTimelineSummaries(retained, Timeline(listOf(deleted))).days.single()
        assertEquals("", result.tldr)
    }

    @Test
    fun syncAcknowledgementsKeepMomentContextWithoutRestoringOldRawMetadata() {
        val moment =
            inferMomentsHeuristically(loaded.entries, emptyList())
                .single()
                .copy(label = "At lunch", inferenceSource = MomentInferenceSource.AI_INFERRED)
        val previous = loaded.copy(moments = listOf(moment))
        val updated = note.copy(lastUpdated = Instant.parse("2026-10-28T12:01:00Z"), syncVersion = 5)
        val incoming =
            loaded.copy(
                tldr = "",
                entries = listOf(updated),
                moments = inferMomentsHeuristically(listOf(updated), emptyList()),
            )
        val result = retainAvailableTimelineSummaries(Timeline(listOf(previous)), Timeline(listOf(incoming))).days.single()
        assertEquals("At lunch", result.moments.single().label)
        assertEquals(5L, result.entries.single().syncVersion)
        assertEquals(updated.lastUpdated, result.entries.single().lastUpdated)
    }

    @Test
    fun aPartialWindowCannotEraseTheFullSummarySourceProvenance() {
        val another = note.copy(uid = kotlin.uuid.Uuid.random(), content = "An earlier entry")
        val fullDay = loaded.copy(entries = listOf(note, another))
        val pending = loaded.copy(tldr = "", people = emptyList(), isRecentWindow = true)
        val retained = retainAvailableTimelineSummaries(Timeline(listOf(fullDay)), Timeline(listOf(pending)))
        val offline = fullDay.copy(tldr = "", people = emptyList(), summaryUnavailableReason = TimelineSummaryUnavailableReason.NO_NETWORK)
        val result = retainAvailableTimelineSummaries(retained, Timeline(listOf(offline))).days.single()
        assertEquals(fullDay.tldr, result.tldr)
        assertEquals(fullDay.people, result.people)
    }

    @Test
    fun pendingRawSnapshotsKeepCompatibleAiMomentContext() {
        val moment =
            inferMomentsHeuristically(loaded.entries, emptyList())
                .single()
                .copy(label = "At lunch", people = listOf("Alex"), inferenceSource = MomentInferenceSource.AI_INFERRED)
        val previous = loaded.copy(moments = listOf(moment))
        val incoming =
            loaded.copy(
                tldr = "",
                people = emptyList(),
                moments = inferMomentsHeuristically(loaded.entries, emptyList()),
            )
        val result = retainAvailableTimelineSummaries(Timeline(listOf(previous)), Timeline(listOf(incoming))).days.single()
        assertEquals("At lunch", result.moments.single().label)
        assertEquals(listOf("Alex"), result.moments.single().people)
    }

    @Test
    fun aPendingRecentWindowKeepsTheLoadedFullDaySummary() {
        val another = note.copy(uid = kotlin.uuid.Uuid.random(), content = "An earlier entry")
        val fullDay = loaded.copy(entries = listOf(note, another))
        val incoming = loaded.copy(tldr = "", people = emptyList(), isRecentWindow = true)
        val result = retainAvailableTimelineSummaries(Timeline(listOf(fullDay)), Timeline(listOf(incoming))).days.single()
        assertEquals(fullDay.tldr, result.tldr)
    }

    @Test
    fun pendingDerivedContentKeepsItsPreviouslyAvailablePeople() {
        val incoming = loaded.copy(tldr = "", people = emptyList())
        val result = retainAvailableTimelineSummaries(Timeline(listOf(loaded)), Timeline(listOf(incoming))).days.single()
        assertEquals(loaded.tldr, result.tldr)
        assertEquals(loaded.people, result.people)
    }

    @Test
    fun aChangedTextRevisionCannotInheritThePreviousSummary() {
        val incoming = loaded.copy(tldr = "", people = emptyList(), entries = listOf(note.copy(content = "Newer text")))
        assertEquals(incoming, retainAvailableTimelineSummaries(Timeline(listOf(loaded)), Timeline(listOf(incoming))).days.single())
    }

    @Test
    fun aCompletedResultCanDeliberatelyRemoveDerivedPeople() {
        val incoming = loaded.copy(tldr = "Updated summary", people = emptyList())
        assertEquals(incoming, retainAvailableTimelineSummaries(Timeline(listOf(loaded)), Timeline(listOf(incoming))).days.single())
    }

    @Test
    fun anotherDateCannotInheritDerivedContent() {
        val incoming = loaded.copy(date = LocalDate(2026, 10, 29), tldr = "", people = emptyList())
        assertEquals(incoming, retainAvailableTimelineSummaries(Timeline(listOf(loaded)), Timeline(listOf(incoming))).days.single())
    }
}
