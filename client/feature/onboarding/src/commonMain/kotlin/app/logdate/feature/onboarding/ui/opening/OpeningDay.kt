package app.logdate.feature.onboarding.ui.opening

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.min

/**
 * Today's page: what the pile becomes when the table is tidied.
 *
 * It is laid out the way LogDate shows a day. The date and the word "Today" head the page, and
 * what was added today stands under them: the photo, the words, the voice note. There is one page
 * on a screen of any size, because there is one today.
 *
 * [width] and [height] are the page at a scale of one, [scale] is how large it is drawn, and
 * [centre] is where its middle is on the screen. [heading] is where the words at its head start,
 * measured from the page's top left corner at a scale of one.
 */
internal data class DayPage(
    val centre: Offset,
    val width: Float,
    val height: Float,
    val scale: Float,
    val heading: Offset,
) {
    /** The room the page takes on the screen. */
    val bounds: Rect
        get() =
            Rect(
                centre.x - width * scale / 2f,
                centre.y - height * scale / 2f,
                centre.x + width * scale / 2f,
                centre.y + height * scale / 2f,
            )
}

/** Today lined up: its [page], and where each of today's pieces stands on it. */
internal class TidiedDay(
    val page: DayPage,
    val places: Map<String, PiecePose>,
)

/**
 * Lines today up on a page in [stage], the room between the words and the actions.
 *
 * The page has three shapes and the one that shows the photo largest is used: wide, with the
 * photo beside the words and the voice note, which is the page a phone gets; long, with
 * everything in a row, for a screen with little height such as an unfolded phone; and tall, with
 * everything in a column, for a narrow room. Where no page can be drawn large enough to read
 * there is none, and the pile stays as it is.
 */
internal fun fileToday(stage: Rect): TidiedDay? {
    val room = Rect(stage.left + PAGE_MARGIN, stage.top, stage.right - PAGE_MARGIN, stage.bottom)
    if (room.width <= 0f || room.height <= 0f) return null

    fun fitting(shape: PageShape) = min(room.width / shape.width, room.height / shape.height).coerceAtMost(PAGE_LARGEST)
    val shape = PAGE_SHAPES.maxBy { fitting(it) * it.photo.scale * it.favour }
    val scale = fitting(shape)
    if (scale < PAGE_SMALLEST) return null
    val origin = Offset(room.center.x - shape.width * scale / 2f, room.center.y - shape.height * scale / 2f)

    fun stands(at: Slot) = PiecePose(origin + Offset(at.x, at.y) * scale, 0f, at.scale * scale)
    return TidiedDay(
        page = DayPage(room.center, shape.width, shape.height, scale, shape.heading),
        places =
            mapOf(
                OpeningCatalog.todayStamp.id to stands(shape.stamp),
                OpeningCatalog.todayPhoto.id to stands(shape.photo),
                OpeningCatalog.todayNote.id to stands(shape.note),
                OpeningCatalog.todayVoice.id to stands(shape.voice),
            ),
    )
}

/** Where the middle of a piece stands on the page at a scale of one, and how large the piece is drawn there. */
private class Slot(
    val x: Float,
    val y: Float,
    val scale: Float,
)

/** One way of laying the page out. [favour] breaks a near tie towards the shape that reads most like the app. */
private class PageShape(
    val width: Float,
    val height: Float,
    val heading: Offset,
    val stamp: Slot,
    val photo: Slot,
    val note: Slot,
    val voice: Slot,
    val favour: Float = 1f,
)

// The date is drawn this much smaller on the page than on the table, where it is the largest thing in the pile.
private const val STAMP_ON_PAGE = 73f / 104f

// The page's three shapes. Every number is in dp at a scale of one, measured from the page's top
// left corner; a slot is the middle of its piece. The page keeps 18 clear inside its edge, the
// date is 73 by 81 at its head, and the entries start 14 under it.
private val PAGE_SHAPES =
    listOf(
        // Wide: the photo on the left, the words and the voice note beside it.
        PageShape(
            width = 363f,
            height = 301f,
            heading = Offset(105f, 32f),
            stamp = Slot(54.5f, 58.5f, STAMP_ON_PAGE),
            photo = Slot(93.7f, 198.2f, 0.86f),
            note = Slot(263.1f, 149.1f, 0.86f),
            voice = Slot(253.7f, 215f, 0.86f),
            favour = 1.06f,
        ),
        // Long: the date and its words, then the photo, then the words and the voice note, in a row.
        PageShape(
            width = 539f,
            height = 206f,
            heading = Offset(18f, 110f),
            stamp = Slot(54.5f, 58.5f, STAMP_ON_PAGE),
            photo = Slot(269.7f, 103.2f, 0.86f),
            note = Slot(439.1f, 54.1f, 0.86f),
            voice = Slot(429.7f, 120f, 0.86f),
        ),
        // Tall: everything in one column, larger.
        PageShape(
            width = 287f,
            height = 561f,
            heading = Offset(105f, 32f),
            stamp = Slot(54.5f, 58.5f, STAMP_ON_PAGE),
            photo = Slot(128f, 236.8f, 1.25f),
            note = Slot(136.8f, 423f, 1.25f),
            voice = Slot(123f, 514.3f, 1.25f),
        ),
    )

// The room the page keeps from the sides of the stage, and the largest and smallest it is drawn.
private const val PAGE_MARGIN = 12f
private const val PAGE_LARGEST = 1.5f
private const val PAGE_SMALLEST = 0.5f
