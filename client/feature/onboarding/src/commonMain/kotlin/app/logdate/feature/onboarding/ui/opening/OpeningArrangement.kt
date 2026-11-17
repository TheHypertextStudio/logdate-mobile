package app.logdate.feature.onboarding.ui.opening

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where a piece is, how it is turned and how large it is drawn. Lengths are in dp. */
internal data class PiecePose(
    val center: Offset,
    val rotation: Float,
    val scale: Float,
)

/**
 * One piece, where it lies [loose] on the table, and what becomes of it when the table is tidied.
 *
 * Today's pieces have a place on today's page, [filed]. Of the rest, a piece that lies where the
 * page will be gets out of its way and leaves the screen, and every other piece [stays] where it
 * is. [depth] runs from 0, far from the viewer, to 1, near.
 */
internal data class PlacedPiece(
    val piece: OpeningPiece,
    val loose: PiecePose,
    val filed: PiecePose?,
    val stays: Boolean,
    val depth: Float,
)

/**
 * A whole scene. [pieces] are in drawing order, so the first lies under the rest. [pile] is the
 * room today's pile takes, which is where the eye should rest, and [page] is the page the pile is
 * lined up on when the table is tidied, where the screen has room for one. [keepClear] is where
 * the words and the actions are, with a margin, and [canvas] is the screen.
 */
internal data class OpeningArrangement(
    val canvas: Size,
    val pieces: List<PlacedPiece>,
    val page: DayPage?,
    val pile: Rect,
    val keepClear: List<Rect>,
)

/**
 * Lays the opening scene out for a screen of any size.
 *
 * The scene is not a picture with a size of its own. It is the whole screen, [canvas], with the
 * step's [words] and [actions] somewhere on it, and the pieces fill the room those leave: today's
 * pile between them, a few more hanging from the top and bottom edges, and, where the screen is
 * wider than the step, more down each side. A larger screen has more room, so it gets more pieces
 * and larger ones, but never so many that the pile stops being what the eye lands on. [actions]
 * is empty where the scene does not reach them.
 *
 * Tidied, today's pile lines up on one page: see [fileToday].
 */
internal fun arrangeOpening(
    canvas: Size,
    words: Rect,
    actions: Rect,
): OpeningArrangement {
    val room = roomAround(canvas, words, actions)
    val keepClear = listOfNotNull(words.inflate(KEEP_CLEAR), actions.takeUnless { it.isEmpty }?.inflate(KEEP_CLEAR))
    val pile = PILES.maxBy { it.scaleIn(room.stage) }
    val pileScale = pile.scaleIn(room.stage).coerceIn(PILE_SMALLEST, PILE_LARGEST)
    val pileCentre = room.stage.center

    fun inPile(spot: Spot) = PiecePose(pileCentre + Offset(spot.x, spot.y) * pileScale, spot.turn, pileScale)
    // The date goes down last and on top: it is what the pile is filed under.
    val today =
        listOf(
            Loose(OpeningCatalog.todayPhoto, inPile(pile.print), depth = 0.95f),
            Loose(OpeningCatalog.todayVoice, inPile(pile.voice), depth = 0.95f),
            Loose(OpeningCatalog.todayNote, inPile(pile.note), depth = 1f),
            Loose(OpeningCatalog.todayStamp, inPile(pile.stamp), depth = 1f),
        )
    val more = dealInto(placesFor(room, pile, pileCentre, pileScale))
    val day = fileToday(room.stage)
    val inTheWay = day?.page?.bounds?.inflate(PAGE_CLEAR)
    return OpeningArrangement(
        canvas = canvas,
        pieces =
            (more + today)
                .map { lying ->
                    val pose = lying.pose.clearOf(lying.piece, keepClear)
                    val box = Rect(pose.center, 0f).inflate(pose.halfWidth(lying.piece), pose.halfHeight(lying.piece))
                    PlacedPiece(
                        lying.piece,
                        pose,
                        day?.places?.get(lying.piece.id),
                        stays = inTheWay == null || !box.overlaps(inTheWay),
                        lying.depth,
                    )
                }.sortedBy { it.depth },
        page = day?.page,
        pile = Rect(pileCentre, 0f).inflate(pile.width * pileScale / 2f, pile.height * pileScale / 2f),
        keepClear = keepClear,
    )
}

private data class Loose(
    val piece: OpeningPiece,
    val pose: PiecePose,
    val depth: Float,
)

/**
 * The parts of the screen the step leaves free. [stage] is where today goes: between the words and
 * the actions, or above the words where there is no room between them, as when the words are set
 * very large. [start] and [end] exist only where they are wide enough to use.
 */
private class Room(
    val above: Rect,
    val stage: Rect,
    val below: Rect?,
    val start: Rect?,
    val end: Rect?,
)

private fun roomAround(
    canvas: Size,
    words: Rect,
    actions: Rect,
): Room {
    val reachesActions = !actions.isEmpty
    val half = max(words.width, if (reachesActions) actions.width else 0f) / 2f + COLUMN_MARGIN
    var left = (words.center.x - half).coerceAtLeast(0f)
    var right = (words.center.x + half).coerceAtMost(canvas.width)
    val start = Rect(0f, 0f, left, canvas.height).takeIf { it.width >= SIDE_NARROWEST }
    val end = Rect(right, 0f, canvas.width, canvas.height).takeIf { it.width >= SIDE_NARROWEST }
    // A side too narrow to hold anything of its own belongs to the middle.
    if (start == null) left = 0f
    if (end == null) right = canvas.width
    val stageTop = words.bottom + STAGE_BELOW_WORDS
    val stageBottom = if (reachesActions) actions.top - STAGE_ABOVE_ACTIONS else canvas.height - STAGE_ABOVE_ACTIONS
    val above = Rect(left, 0f, right, max(words.top - 12f, 0f))
    val between = Rect(left, stageTop, right, max(stageBottom, stageTop + 1f))
    val below = if (reachesActions) Rect(left, min(actions.bottom + 12f, canvas.height), right, canvas.height) else null
    if (between.height < STAGE_SHORTEST && above.height > between.height) {
        return Room(
            above = Rect(left, 0f, right, 0f),
            stage = Rect(left, TOP_OF_STAGE_ABOVE, right, above.bottom),
            below = below,
            start = start,
            end = end,
        )
    }
    return Room(above = above, stage = between, below = below, start = start, end = end)
}

/** A place in today's pile, measured from the middle of the pile. */
private class Spot(
    val x: Float,
    val y: Float,
    val turn: Float,
    val scale: Float = 1f,
)

/**
 * Today's pile, composed by hand for a room of one shape. The pile is what the eye lands on, so
 * where its pieces overlap is chosen, not computed. [heart] is the one piece from another day
 * that lies against it.
 */
private class Pile(
    val width: Float,
    val height: Float,
    val print: Spot,
    val note: Spot,
    val voice: Spot,
    val stamp: Spot,
    val heart: Spot,
) {
    /** How large this pile can be drawn in [stage]. It may run a little past the sides. */
    fun scaleIn(stage: Rect) = min((stage.width + PILE_BLEED) / width, stage.height / height)
}

// The pile for a room taller than it is wide, and the pile for a room wider than it is tall. The
// one that can be drawn larger is used.
private val PILES =
    listOf(
        Pile(
            width = 300f,
            height = 372f,
            print = Spot(-62f, -52f, -7f),
            note = Spot(74f, 62f, 4f),
            voice = Spot(-84f, 140f, -5f),
            stamp = Spot(118f, -128f, 9f),
            heart = Spot(114f, -24f, -7f, scale = 0.74f),
        ),
        Pile(
            width = 400f,
            height = 262f,
            print = Spot(-108f, 0f, -6f),
            note = Spot(92f, 44f, 4f),
            voice = Spot(70f, 110f, -4f),
            stamp = Spot(52f, -62f, 9f),
            heart = Spot(160f, -58f, -7f, scale = 0.74f),
        ),
    )

/** A place a piece can lie, before it is known which piece. */
private class Place(
    val centre: Offset,
    val scale: Float,
    val rotation: Float,
    val depth: Float,
    val takes: Takes = Takes.Photos,
    val hang: Hang = Hang.None,
    val within: Rect? = null,
    val wants: String? = null,
)

/** Which edge of the screen a piece hangs over, if any. A hanging piece is partly off the screen. */
private enum class Hang { None, Top, Bottom, Start, End }

/**
 * What a place is best given. A large place at an edge wants something that still reads when part
 * of it is off the screen, which is a photo or a square note. A small place between larger ones
 * wants one of the small things: a voice note, a line of words, a drawing.
 */
private enum class Takes { Photos, Showy, Small }

private fun Takes.suits(piece: OpeningPiece): Boolean =
    when (this) {
        Takes.Photos -> piece.content is PrintContent
        Takes.Showy -> piece.content is PrintContent || (piece.content is NoteContent && piece.height > ONE_LINE_TALLEST)
        Takes.Small -> piece.content !is PrintContent
    }

/**
 * Every place the screen has room for, most wanted first: against today's pile, hanging from the
 * top, down the sides, hanging from the bottom, tucked behind the pile, then the small places
 * between the large ones.
 */
private fun placesFor(
    room: Room,
    pile: Pile,
    pileCentre: Offset,
    pileScale: Float,
): List<Place> {
    var turn = 0

    fun nextTurn() = TURNS[turn++ % TURNS.size]

    val heart =
        Place(
            centre = pileCentre + Offset(pile.heart.x, pile.heart.y) * pileScale,
            scale = pile.heart.scale * pileScale,
            rotation = pile.heart.turn,
            depth = 0.9f,
            wants = OpeningCatalog.HEART,
        )
    val spare = (room.stage.width - pile.width * pileScale) / 2f
    val tucks =
        if (spare >= 90f) {
            listOf(
                Place(
                    pileCentre + Offset(-(pile.width / 2f + 34f), -(pile.height / 2f - 76f)) * pileScale,
                    0.82f * pileScale,
                    -11f,
                    depth = 0.6f,
                ),
                Place(pileCentre + Offset(pile.width / 2f + 38f, pile.height / 2f - 70f) * pileScale, 0.8f * pileScale, 10f, depth = 0.6f),
            )
        } else {
            emptyList()
        }
    val top = band(room.above, Hang.Top, ::nextTurn)
    val bottom = room.below?.let { band(it, Hang.Bottom, ::nextTurn) }.orEmpty()
    val start = room.start?.let { side(it, Hang.Start, ::nextTurn) }.orEmpty()
    val end = room.end?.let { side(it, Hang.End, ::nextTurn) }.orEmpty()
    // The two sides fill in step with each other, so a short supply of pieces is shared between them.
    val sides = List(max(start.size, end.size) * 2) { index -> (if (index % 2 == 0) start else end).getOrNull(index / 2) }.filterNotNull()
    val edges = top + sides + bottom
    return listOf(heart) + edges.filter { it.takes != Takes.Small } + tucks + edges.filter { it.takes == Takes.Small }
}

/**
 * Places along the top or bottom of the screen: a row of large ones hanging off the edge, and,
 * where the band is deep, a small one inside each place two of those meet.
 */
private fun band(
    region: Rect,
    hang: Hang,
    turn: () -> Float,
): List<Place> {
    if (region.height < 96f || region.width < 150f) return emptyList()
    val scale = (region.height / 150f).coerceIn(0.75f, 1.5f)
    val count = (region.width / (150f * scale)).toInt().coerceIn(1, 5)
    val edge = if (hang == Hang.Top) region.top else region.bottom
    val row =
        List(count) { index ->
            val x = region.left + region.width * (index + 0.5f) / count
            Place(Offset(x, edge), scale, turn(), depth = 0.5f, takes = Takes.Showy, hang = hang, within = region)
        }
    if (region.height < 330f) return row
    val inner = if (hang == Hang.Top) region.top + region.height * 0.74f else region.top + region.height * 0.26f
    val small =
        List(count - 1) { index ->
            Place(
                Offset(region.left + region.width * (index + 1f) / count, inner),
                scale * 0.72f,
                turn(),
                depth = 0.55f,
                takes = Takes.Small,
            )
        }
    return row + small
}

/**
 * Places down one side of the screen: large ones along the outer edge and hanging off it, for
 * photos, and one or two small ones along the inner edge, lying over them, for the small things.
 */
private fun side(
    region: Rect,
    hang: Hang,
    turn: () -> Float,
): List<Place> {
    val scale = (region.width / 280f).coerceIn(0.9f, 1.4f)
    val count = (region.height / (190f * scale)).roundToInt().coerceIn(2, 5)
    val outer =
        List(count) { index ->
            val y = region.top + region.height * (index + 0.5f) / count
            Place(Offset(region.center.x, y), scale, turn(), depth = 0.8f, hang = hang, within = region)
        }
    if (region.width < 300f) return outer
    val inward = if (hang == Hang.Start) 1f else -1f
    val small =
        List((count - 2).coerceIn(0, 2)) { index ->
            // The start side's small things sit towards the top and the end side's towards the
            // bottom, so the two sides do not mirror each other.
            val gap = if (hang == Hang.Start) index + 1 else count - 1 - index
            val y = region.top + region.height * gap / count
            Place(Offset(region.center.x + inward * region.width * 0.27f, y), scale * 0.72f, turn(), depth = 0.85f, takes = Takes.Small)
        }
    return outer + small
}

/**
 * Puts a piece in each place until the places or the pieces run out, in the order the catalog
 * brings them out. A place that asks for a piece by name gets it. Otherwise a place gets the next
 * piece of the kind it is best given, or failing that the next piece of any kind, and a place
 * that hangs off an edge is never given a one-line piece, which would be cut where it cannot be read.
 */
private fun dealInto(places: List<Place>): List<Loose> {
    val left = OpeningCatalog.more.toMutableList()
    val asked = places.mapNotNull { it.wants }.toSet()

    fun take(place: Place): OpeningPiece? {
        fun fits(piece: OpeningPiece) = piece.id !in asked && (place.hang == Hang.None || piece.height > ONE_LINE_TALLEST)
        val index =
            if (place.wants != null) {
                left.indexOfFirst { it.id == place.wants }
            } else {
                left.indexOfFirst { fits(it) && place.takes.suits(it) }.takeIf { it >= 0 } ?: left.indexOfFirst(::fits)
            }
        return if (index >= 0) left.removeAt(index) else null
    }
    return places.mapNotNull { place -> take(place)?.let { Loose(it, place.fit(it), place.depth) } }
}

/** Where a piece lies in this place. A hanging piece sits partly over its edge, and never grows past the room behind it. */
private fun Place.fit(piece: OpeningPiece): PiecePose {
    val region = within ?: return PiecePose(centre, rotation, scale)
    return when (hang) {
        Hang.Top, Hang.Bottom -> {
            val fitted = min(scale, region.height / (piece.height * (1f - OVERHANG)))
            val reach = piece.height * fitted * (0.5f - OVERHANG)
            PiecePose(Offset(centre.x, if (hang == Hang.Top) centre.y + reach else centre.y - reach), rotation, fitted)
        }

        Hang.Start, Hang.End -> {
            val fitted = min(scale, region.width / (piece.width * (1f - OVERHANG)))
            val reach = piece.width * fitted * (0.5f - OVERHANG)
            PiecePose(Offset(if (hang == Hang.Start) region.left + reach else region.right - reach, centre.y), rotation, fitted)
        }

        Hang.None -> {
            PiecePose(centre, rotation, scale)
        }
    }
}

/** Moves a piece the shortest way off the words and the actions, so nothing lies over them. */
internal fun PiecePose.clearOf(
    piece: OpeningPiece,
    keepClear: List<Rect>,
): PiecePose {
    var centre = center
    for (kept in keepClear) {
        val box = Rect(centre, 0f).inflate(halfWidth(piece), halfHeight(piece))
        if (!box.overlaps(kept)) continue
        val moves =
            listOf(
                Offset(0f, kept.top - box.bottom),
                Offset(0f, kept.bottom - box.top),
                Offset(kept.left - box.right, 0f),
                Offset(kept.right - box.left, 0f),
            )
        centre += moves.minBy { abs(it.x) + abs(it.y) }
    }
    return copy(center = centre)
}

/** Half the room [piece] takes across the screen in this pose, turned as it is. */
internal fun PiecePose.halfWidth(piece: OpeningPiece): Float {
    val turn = rotation * (PI.toFloat() / 180f)
    return (piece.width * abs(cos(turn)) + piece.height * abs(sin(turn))) * scale / 2f
}

/** Half the room [piece] takes down the screen in this pose, turned as it is. */
internal fun PiecePose.halfHeight(piece: OpeningPiece): Float {
    val turn = rotation * (PI.toFloat() / 180f)
    return (piece.width * abs(sin(turn)) + piece.height * abs(cos(turn))) * scale / 2f
}

private fun Rect.inflate(
    x: Float,
    y: Float,
) = Rect(left - x, top - y, right + x, bottom + y)

// How far the scene keeps off the words and the actions, and how far a piece that stays on the
// table keeps off today's page.
private const val KEEP_CLEAR = 10f
private const val PAGE_CLEAR = 8f

// The room the step keeps either side of its widest part, and the narrowest side worth using.
private const val COLUMN_MARGIN = 28f
private const val SIDE_NARROWEST = 190f

// The stage starts this far under the words and ends this far above the actions, so the pile
// never crowds the button under it.
private const val STAGE_BELOW_WORDS = 18f
private const val STAGE_ABOVE_ACTIONS = 26f

// Today goes above the words where the room between the words and the actions is shorter than
// this, starting far enough down to clear the status bar.
private const val STAGE_SHORTEST = 150f
private const val TOP_OF_STAGE_ABOVE = 28f

// How far today's pile may run past the sides of the stage, and the smallest and largest it is drawn.
private const val PILE_BLEED = 32f
private const val PILE_SMALLEST = 0.45f
private const val PILE_LARGEST = 1.7f

// How much of a hanging piece is off the screen.
private const val OVERHANG = 0.22f

// A piece no taller than this has one line on it, and is never hung off an edge.
private const val ONE_LINE_TALLEST = 60f

// How each place in turn is turned, in degrees.
private val TURNS = listOf(-8f, 6f, -4f, 9f, -6f, 5f, -10f, 7f, -3f, 8f)
