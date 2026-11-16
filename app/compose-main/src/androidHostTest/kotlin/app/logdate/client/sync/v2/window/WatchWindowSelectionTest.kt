package app.logdate.client.sync.v2.window

import app.logdate.client.domain.watch.WatchWindowPolicy
import app.logdate.client.domain.watch.WatchWindowPreferences
import app.logdate.client.domain.watch.WindowBudget
import app.logdate.client.domain.watch.WindowCandidate
import app.logdate.client.domain.watch.WindowExtraIds
import app.logdate.client.repository.journals.JournalNote
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class WatchWindowSelectionTest {
    private val now = Instant.parse("2026-10-31T18:00:00Z")
    private val zone = TimeZone.UTC
    private val preferences = WatchWindowPreferences(days = 7, onThisDay = true, rewind = true)

    @Test
    fun `encodes only the notes that can enter the window`() {
        val recent = (0 until 5).map { day -> text("Recent $day", now - day.days) }
        val archived = (0 until 300).map { hour -> text("Archived $hour", Instant.parse("2020-03-15T00:00:00Z") + hour.hours) }
        val starred = archived[42]
        var encoded = 0

        val window =
            selectWindow(
                notes = archived + recent,
                policy = WatchWindowPolicy(),
                extras = WindowExtraIds(starred = setOf(starred.uid)),
                now = now,
                zone = zone,
                dayStartHour = 0,
                preferences = preferences,
            ) { note ->
                encoded++
                JsonPrimitive(note.content())
            }

        assertEquals(6, encoded)
        assertEquals(
            (recent + starred).map { it.uid }.toSet(),
            window.plan.members
                .map { it.id }
                .toSet(),
        )
    }

    @Test
    fun `chooses the same members as sizing every note would`() {
        val notes = (0 until 40).map { day -> text("x".repeat(100 + day * 50), now - day.days) }
        val budget = WindowBudget(maxItems = 400, maxBytes = 2_000)
        val policy = WatchWindowPolicy(budget = budget)
        val extras = WindowExtraIds()

        val window = selectWindow(notes, policy, extras, now, zone, 0, preferences) { note -> JsonPrimitive(note.content()) }
        val everyNoteSized =
            policy.choose(
                notes.map { WindowCandidate(it.uid, it.creationTimestamp, JsonPrimitive(it.content()).sizeBytes()) },
                extras,
                now,
                zone,
                0,
                preferences,
            )

        assertEquals(everyNoteSized.members, window.plan.members)
        assertEquals(everyNoteSized.dropped, window.plan.dropped)
    }

    private fun text(
        content: String,
        at: Instant,
    ) = JournalNote.Text(content = content, creationTimestamp = at, lastUpdated = at)

    private fun JournalNote.content(): String = (this as JournalNote.Text).content
}
