package app.logdate.wear.capture

import app.logdate.client.repository.journals.JournalNote
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class MomentNoteCodecTest {
    @Test
    fun `a check-in is saved as its tag followed by a sentence`() {
        assertEquals("#mood:great Feeling great", MomentNoteCodec.encode(Feeling.GREAT))
        assertEquals("#mood:ok Feeling ok", MomentNoteCodec.encode(Feeling.OK))
    }

    @Test
    fun `a tag later in the text is not a check-in`() {
        assertEquals(MomentNoteKind.TEXT, MomentNoteCodec.classify(text("Dinner was great #mood:great")))
    }

    @Test
    fun `a check-in with a tag no feeling uses is still a check-in`() {
        assertEquals(MomentNoteKind.FEELING, MomentNoteCodec.classify(text("#mood:elated Feeling elated")))
    }

    @Test
    fun `notes are classified by what they hold`() {
        assertEquals(MomentNoteKind.FEELING, MomentNoteCodec.classify(text("#mood:sad Feeling sad")))
        assertEquals(MomentNoteKind.TEXT, MomentNoteCodec.classify(text("Lunch by the water")))
        assertEquals(
            MomentNoteKind.VOICE,
            MomentNoteCodec.classify(JournalNote.Audio(mediaRef = "/a.m4a", creationTimestamp = at(0), lastUpdated = at(0))),
        )
        assertEquals(
            MomentNoteKind.PHOTO,
            MomentNoteCodec.classify(JournalNote.Image(creationTimestamp = at(0), lastUpdated = at(0), mediaRef = "/p.jpg")),
        )
        assertEquals(
            MomentNoteKind.VIDEO,
            MomentNoteCodec.classify(JournalNote.Video(creationTimestamp = at(0), lastUpdated = at(0), mediaRef = "/v.mp4")),
        )
    }

    @Test
    fun `the latest feeling is the newest check-in, whatever the list order`() {
        val notes =
            listOf(
                text("#mood:rough Feeling rough", minute = 5),
                text("#mood:great Feeling great", minute = 30),
                text("Lunch by the water", minute = 45),
                text("#mood:sad Feeling sad", minute = 10),
            )

        assertEquals(Feeling.GREAT, MomentNoteCodec.latestFeeling(notes))
    }

    @Test
    fun `a newest check-in with an unknown tag is skipped`() {
        val notes =
            listOf(
                text("#mood:good Feeling good", minute = 5),
                text("#mood:elated Feeling elated", minute = 30),
            )

        assertEquals(Feeling.GOOD, MomentNoteCodec.latestFeeling(notes))
    }

    @Test
    fun `a day without check-ins has no feeling`() {
        assertNull(MomentNoteCodec.latestFeeling(listOf(text("Lunch by the water"))))
        assertNull(MomentNoteCodec.latestFeeling(emptyList()))
    }

    private fun text(
        content: String,
        minute: Int = 0,
    ): JournalNote.Text = JournalNote.Text(creationTimestamp = at(minute), lastUpdated = at(minute), content = content)

    private fun at(minute: Int): Instant = Instant.fromEpochMilliseconds(1_710_000_000_000 + minute * 60_000L)
}
