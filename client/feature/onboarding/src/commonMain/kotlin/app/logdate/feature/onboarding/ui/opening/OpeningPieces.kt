package app.logdate.feature.onboarding.ui.opening

import kotlinx.datetime.LocalTime

/**
 * The things that lie on the table when LogDate first opens.
 *
 * They are an example of a few days of someone's life, in the forms LogDate keeps a memory in: a
 * photo, a clip, a few words, a voice note, something drawn by hand. Today's are a pile with
 * today's date on it. The rest lie around the edges of the screen. Each piece has the size it is
 * drawn at before the scene scales it.
 */
internal data class OpeningPiece(
    val id: String,
    val width: Float,
    val height: Float,
    val content: PieceContent,
)

internal sealed interface PieceContent

/** Today's date. */
internal data class StampContent(
    val tone: PieceTone,
) : PieceContent

/** A photo, or a clip when [clip] gives its length. [time] is when it was taken, where the frame has room to say. */
internal data class PrintContent(
    val photo: ScenePhoto,
    val frame: PrintFrame,
    val time: LocalTime? = null,
    val clip: String? = null,
) : PieceContent

internal data class NoteContent(
    val text: SceneText,
    val tone: PieceTone,
    val time: LocalTime? = null,
) : PieceContent

/** A voice note, with what was said where LogDate has written it out. */
internal data class VoiceContent(
    val length: String,
    val tone: PieceTone,
    val heard: SceneText? = null,
) : PieceContent

/** Something drawn by hand, in ink. */
internal data class SketchContent(
    val drawing: SceneSketch,
    val tone: PieceTone,
) : PieceContent

/** The colour a piece is made in. */
internal enum class PieceTone { Paper, Cream, Peach, Blush }

/** How a photo is printed: an instant print with a deep bottom margin, a bordered print, or bare. */
internal enum class PrintFrame { Instant, Border, Bare }

internal enum class ScenePhoto { Cafe, Dusk, Cake, Couch, Pug, Concert, Hike, Pizza, Record, Beach, RoadTrip, Rain }

internal enum class SceneText { Coffee, Nana, LongWay, Wish }

internal enum class SceneSketch { Heart, Sun }

internal object OpeningCatalog {
    const val HEART = "sketch-heart"

    val todayStamp = OpeningPiece("stamp-today", 104f, 116f, StampContent(PieceTone.Peach))
    val todayPhoto = OpeningPiece("print-cafe", 176f, 198f, PrintContent(ScenePhoto.Cafe, PrintFrame.Instant, LocalTime(16, 42)))
    val todayNote = OpeningPiece("note-coffee", 190f, 84f, NoteContent(SceneText.Coffee, PieceTone.Paper, LocalTime(16, 44)))
    val todayVoice = OpeningPiece("voice-short", 168f, 46f, VoiceContent("0:12", PieceTone.Paper))

    /** Today's pieces, the ones every screen shows: the date, then what was added under it, in order. */
    val today: List<OpeningPiece> = listOf(todayStamp, todayPhoto, todayNote, todayVoice)

    /**
     * Other days' memories, in the order they are brought out as a screen has room for more. A
     * phone has room for three, so the first few are chosen to differ: words, a photo, a drawing.
     */
    val more: List<OpeningPiece> =
        listOf(
            OpeningPiece("note-nana", 126f, 118f, NoteContent(SceneText.Nana, PieceTone.Peach)),
            OpeningPiece("print-cake", 196f, 152f, PrintContent(ScenePhoto.Cake, PrintFrame.Border)),
            OpeningPiece("print-pug", 204f, 160f, PrintContent(ScenePhoto.Pug, PrintFrame.Border)),
            OpeningPiece("print-concert", 168f, 190f, PrintContent(ScenePhoto.Concert, PrintFrame.Instant, LocalTime(22, 10))),
            OpeningPiece("voice-wish", 206f, 78f, VoiceContent("0:31", PieceTone.Blush, SceneText.Wish)),
            OpeningPiece("print-beach", 210f, 132f, PrintContent(ScenePhoto.Beach, PrintFrame.Bare)),
            OpeningPiece("print-hike", 190f, 150f, PrintContent(ScenePhoto.Hike, PrintFrame.Border)),
            OpeningPiece("note-longway", 178f, 66f, NoteContent(SceneText.LongWay, PieceTone.Cream)),
            OpeningPiece("print-record", 150f, 150f, PrintContent(ScenePhoto.Record, PrintFrame.Bare)),
            OpeningPiece("print-roadtrip", 200f, 156f, PrintContent(ScenePhoto.RoadTrip, PrintFrame.Border)),
            OpeningPiece("sketch-sun", 124f, 108f, SketchContent(SceneSketch.Sun, PieceTone.Paper)),
            OpeningPiece("clip-dusk", 150f, 196f, PrintContent(ScenePhoto.Dusk, PrintFrame.Bare, clip = "0:08")),
            OpeningPiece("print-pizza", 142f, 196f, PrintContent(ScenePhoto.Pizza, PrintFrame.Border)),
            OpeningPiece("print-couch", 150f, 170f, PrintContent(ScenePhoto.Couch, PrintFrame.Instant, LocalTime(21, 5))),
            OpeningPiece("print-rain", 132f, 176f, PrintContent(ScenePhoto.Rain, PrintFrame.Bare)),
            // Asked for by name, to lie against today's pile.
            OpeningPiece(HEART, 112f, 112f, SketchContent(SceneSketch.Heart, PieceTone.Blush)),
        )

    /** Every piece there is. */
    val all: List<OpeningPiece> = today + more
}
