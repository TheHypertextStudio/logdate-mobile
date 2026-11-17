package app.logdate.feature.onboarding.ui.opening

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The opening scene is the whole screen, whatever the screen. These hold it to that without
 * letting it fill up: a phone gets a table and not four cards, a tablet gets more and larger
 * pieces and not a phone's worth in the middle, neither gets so many that the pile is lost among
 * them, and nothing ever lies over the words or the buttons. Tidied, there is one page for today.
 */
class OpeningArrangementTest {
    @Test
    fun `a phone's table holds a handful of pieces of every kind and runs to both edges`() {
        val scene = arrangeOpening(PHONE, PHONE_WORDS, PHONE_ACTIONS)

        assertTrue(scene.pieces.size in 6..8, "${scene.pieces.size} pieces on a phone")
        val reach = scene.reach()
        assertTrue(reach.left <= 8f && reach.right >= PHONE.width - 8f, "pieces stop short of the edges: $reach")
        assertTrue(reach.height >= PHONE.height * 0.7f, "pieces cover only ${reach.height} of ${PHONE.height} in height")
        assertEquals(5, scene.kinds(), "kinds of piece on a phone")
        assertEquals(1, scene.pieces.count { it.piece.content is StampContent }, "dates on the table")
    }

    @Test
    fun `a small phone still gets a pile it can read`() {
        val scene = arrangeOpening(SMALL_PHONE, SMALL_PHONE_WORDS, SMALL_PHONE_ACTIONS)

        assertTrue(scene.pieces.size >= 6, "only ${scene.pieces.size} pieces on a small phone")
        val photo = scene.pieces.first { it.piece.id == "print-cafe" }
        val wide = photo.piece.width * photo.loose.scale
        assertTrue(wide >= 140f, "today's photo is only $wide wide")
    }

    @Test
    fun `a tablet on its side fills both sides of the step with large pieces and no more than it needs`() {
        val scene = arrangeOpening(TABLET_WIDE, TABLET_WIDE_WORDS, TABLET_WIDE_ACTIONS)

        assertTrue(scene.pieces.size in 12..17, "${scene.pieces.size} pieces on a wide tablet")
        val third = TABLET_WIDE.width / 3f
        val startSide = scene.pieces.count { it.loose.center.x < third }
        val endSide = scene.pieces.count { it.loose.center.x > third * 2 }
        assertTrue(startSide >= 3 && endSide >= 3, "sides hold $startSide and $endSide pieces")
        val reach = scene.reach()
        assertTrue(reach.width >= TABLET_WIDE.width * 0.95f && reach.height >= TABLET_WIDE.height * 0.9f, "pieces reach only $reach")
        val phone = arrangeOpening(PHONE, PHONE_WORDS, PHONE_ACTIONS)
        assertTrue(scene.largest() > phone.largest(), "a tablet's largest piece is no larger than a phone's")
    }

    @Test
    fun `a tablet upright fills above and below the step`() {
        val scene = arrangeOpening(TABLET_TALL, TABLET_TALL_WORDS, TABLET_TALL_ACTIONS)

        assertTrue(scene.pieces.size in 10..15, "${scene.pieces.size} pieces on an upright tablet")
        val reach = scene.reach()
        assertTrue(reach.width >= TABLET_TALL.width * 0.95f && reach.height >= TABLET_TALL.height * 0.85f, "pieces reach only $reach")
    }

    @Test
    fun `nothing lies over the words or the actions on any screen`() {
        SCREENS.forEach { (canvas, words, actions) ->
            val scene = arrangeOpening(canvas, words, actions)
            scene.pieces.forEach { placed ->
                assertTrue(!placed.box(placed.loose).overlaps(words), "${placed.piece.id} lies over the words on $canvas")
                assertTrue(!placed.box(placed.loose).overlaps(actions), "${placed.piece.id} lies over the actions on $canvas")
            }
            scene.page?.let { page ->
                assertTrue(!page.bounds.overlaps(words), "today's page lies over the words on $canvas")
                assertTrue(!page.bounds.overlaps(actions), "today's page lies over the actions on $canvas")
            }
        }
    }

    @Test
    fun `every loose piece keeps most of itself on the screen`() {
        SCREENS.forEach { (canvas, words, actions) ->
            arrangeOpening(canvas, words, actions).pieces.forEach { placed ->
                val box = placed.box(placed.loose)
                val seen = box.intersect(Rect(Offset.Zero, canvas))
                val share = (seen.width.coerceAtLeast(0f) * seen.height.coerceAtLeast(0f)) / (box.width * box.height)
                assertTrue(share >= 0.55f, "${placed.piece.id} shows only ${(share * 100).toInt()}% of itself on $canvas")
            }
        }
    }

    @Test
    fun `once tidied every screen has one page and today's four pieces stand on it without touching`() {
        SCREENS.forEach { (canvas, words, actions) ->
            val scene = arrangeOpening(canvas, words, actions)
            val page = assertNotNull(scene.page, "no page for today on $canvas").bounds

            val filed = scene.pieces.filter { it.filed != null }
            assertEquals(
                OpeningCatalog.today.map { it.id }.toSet(),
                filed.map { it.piece.id }.toSet(),
                "what stands on the page on $canvas",
            )
            val boxes = filed.map { it.box(assertNotNull(it.filed)) }
            boxes.forEach { box ->
                assertTrue(
                    box.left >= page.left && box.right <= page.right && box.top >= page.top && box.bottom <= page.bottom,
                    "a piece stands off the page on $canvas: $box on $page",
                )
            }
            boxes.forEachIndexed { index, box ->
                boxes.drop(index + 1).forEach { other -> assertTrue(!box.overlaps(other), "pieces overlap on the page on $canvas") }
            }
        }
    }

    @Test
    fun `once tidied a piece in the page's way leaves and the rest of the table stays`() {
        SCREENS.forEach { (canvas, words, actions) ->
            val scene = arrangeOpening(canvas, words, actions)
            val page = assertNotNull(scene.page).bounds
            val others = scene.pieces.filter { it.filed == null }

            others.forEach { placed ->
                if (placed.stays) assertTrue(!placed.box(placed.loose).overlaps(page), "${placed.piece.id} stays under the page on $canvas")
            }
            assertTrue(others.any { it.stays }, "tidying empties the table on $canvas")
        }
    }

    @Test
    fun `a screen with little height lays today's page out in a row and still shows the photo large`() {
        val scene = arrangeOpening(UNFOLDED, UNFOLDED_WORDS, UNFOLDED_ACTIONS)

        val page = assertNotNull(scene.page)
        assertTrue(page.width > page.height * 2f, "the page is ${page.width} by ${page.height}")
        val photo = scene.pieces.first { it.piece.id == "print-cafe" }
        val wide = assertNotNull(photo.filed).scale * photo.piece.width
        assertTrue(wide >= 120f, "today's photo is only $wide wide on its page")
    }

    @Test
    fun `words set so large they leave no room under them put today above them`() {
        val words = Rect(16f, 184f, 374f, 690f)
        val scene = arrangeOpening(PHONE, words, PHONE_ACTIONS)

        val photo = scene.pieces.first { it.piece.id == "print-cafe" }
        assertTrue(photo.box(photo.loose).bottom <= words.top, "today's photo is not above the words")
        scene.pieces.forEach { placed ->
            assertTrue(!placed.box(placed.loose).overlaps(words), "${placed.piece.id} lies over the words")
        }
        scene.page?.let { assertTrue(!it.bounds.overlaps(words), "today's page lies over the words: ${it.bounds}") }
    }

    @Test
    fun `only a photo lies where the system draws its clock and the table keeps every piece`() {
        (SCREENS + Triple(PHONE, Rect(16f, 184f, 374f, 690f), PHONE_ACTIONS)).forEach { (canvas, words, actions) ->
            listOf(24f, 52f).forEach { bar ->
                val scene = arrangeOpening(canvas, words, actions, systemTop = bar)

                scene.pieces.filter { it.piece.content !is PrintContent }.forEach { placed ->
                    val box = placed.box(placed.loose)
                    assertTrue(box.top >= bar, "${placed.piece.id} lies under a $bar bar on $canvas")
                    assertTrue(!box.overlaps(words), "${placed.piece.id} lies over the words under a $bar bar on $canvas")
                }
                val without = arrangeOpening(canvas, words, actions).pieces.size
                assertTrue(scene.pieces.size >= without, "a $bar bar leaves ${scene.pieces.size} of $without pieces on $canvas")
            }
        }
        val phone = arrangeOpening(PHONE, PHONE_WORDS, PHONE_ACTIONS, systemTop = 24f)
        assertTrue(phone.pieces.any { it.piece.content is PrintContent && it.box(it.loose).top < 0f }, "no photo hangs off the top")
        val note = phone.pieces.first { it.piece.id == "note-nana" }
        assertTrue(note.loose.scale >= 0.7f, "the note under the bar is drawn at ${note.loose.scale}")
    }

    // The room a piece takes on the screen, turned as it lies.
    private fun PlacedPiece.box(pose: PiecePose) =
        Rect(
            pose.center.x - pose.halfWidth(piece),
            pose.center.y - pose.halfHeight(piece),
            pose.center.x + pose.halfWidth(piece),
            pose.center.y + pose.halfHeight(piece),
        )

    private fun OpeningArrangement.reach(): Rect {
        val boxes = pieces.map { it.box(it.loose) }
        return Rect(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    private fun OpeningArrangement.kinds() = pieces.map { it.piece.content::class }.toSet().size

    private fun OpeningArrangement.largest() = pieces.maxOf { it.piece.width * it.loose.scale }

    private companion object {
        // Where a step puts its words and actions on each screen, measured from the running layout.
        val PHONE = Size(390f, 844f)
        val PHONE_WORDS = Rect(16f, 168f, 374f, 244f)
        val PHONE_ACTIONS = Rect(16f, 716f, 374f, 828f)

        val TABLET_WIDE = Size(1280f, 800f)
        val TABLET_WIDE_WORDS = Rect(418f, 208f, 862f, 284f)
        val TABLET_WIDE_ACTIONS = Rect(418f, 640f, 862f, 752f)

        val TABLET_TALL = Size(800f, 1280f)
        val TABLET_TALL_WORDS = Rect(178f, 404f, 622f, 480f)
        val TABLET_TALL_ACTIONS = Rect(178f, 932f, 622f, 1044f)

        // An unfolded phone: wide, with little room between the words and the actions.
        val UNFOLDED = Size(673f, 841f)
        val UNFOLDED_WORDS = Rect(114f, 290f, 559f, 366f)
        val UNFOLDED_ACTIONS = Rect(114f, 640f, 559f, 752f)

        val SMALL_PHONE = Size(360f, 640f)
        val SMALL_PHONE_WORDS = Rect(16f, 168f, 344f, 244f)
        val SMALL_PHONE_ACTIONS = Rect(16f, 512f, 344f, 624f)

        val SCREENS =
            listOf(
                Triple(PHONE, PHONE_WORDS, PHONE_ACTIONS),
                Triple(SMALL_PHONE, SMALL_PHONE_WORDS, SMALL_PHONE_ACTIONS),
                Triple(TABLET_WIDE, TABLET_WIDE_WORDS, TABLET_WIDE_ACTIONS),
                Triple(TABLET_TALL, TABLET_TALL_WORDS, TABLET_TALL_ACTIONS),
                Triple(UNFOLDED, UNFOLDED_WORDS, UNFOLDED_ACTIONS),
            )
    }
}
