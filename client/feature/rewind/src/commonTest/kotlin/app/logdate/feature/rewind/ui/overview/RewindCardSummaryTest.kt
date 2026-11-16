package app.logdate.feature.rewind.ui.overview

import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RewindCardSummaryTest {
    private val at = Instant.parse("2026-10-21T12:00:00Z")

    @Test
    fun `counts each kind of moment and ends on the last day inside the week`() {
        val rewind =
            Rewind(
                uid = Uuid.random(),
                startDate = Instant.parse("2026-10-19T00:00:00Z"),
                endDate = Instant.parse("2026-10-26T00:00:00Z"),
                generationDate = at,
                label = "2026#43",
                title = "A week of walks",
                content =
                    listOf(
                        RewindContent.Image(timestamp = at, sourceId = Uuid.random(), uri = "small.jpg", significanceScore = 0.2f),
                        RewindContent.Image(timestamp = at, sourceId = Uuid.random(), uri = "best.jpg", significanceScore = 0.9f),
                        RewindContent.TextNote(timestamp = at, sourceId = Uuid.random(), content = "Lake"),
                        RewindContent.AudioNote(timestamp = at, sourceId = Uuid.random(), uri = "walk.m4a", durationMs = 1_000),
                    ),
            )

        val summary = rewind.cardSummary(TimeZone.UTC)

        assertEquals(LocalDate(2026, 10, 19), summary.start)
        assertEquals(LocalDate(2026, 10, 25), summary.end)
        assertEquals(2, summary.photoCount)
        assertEquals(1, summary.textCount)
        assertEquals(1, summary.audioCount)
        assertEquals("best.jpg", summary.heroImageUri)
    }
}
