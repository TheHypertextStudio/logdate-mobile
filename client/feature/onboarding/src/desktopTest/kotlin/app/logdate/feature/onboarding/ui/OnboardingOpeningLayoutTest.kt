package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.account.CLOUD_ACCOUNT_WELCOME_CREATE_TAG
import app.logdate.feature.core.account.CLOUD_ACCOUNT_WELCOME_SIGN_IN_TAG
import app.logdate.feature.core.account.CloudAccountSignInContent
import app.logdate.feature.core.account.CloudAccountWelcomeContent
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.feature.onboarding.ui.opening.OpeningCatalog
import app.logdate.feature.onboarding.ui.opening.OpeningScene
import app.logdate.feature.onboarding.ui.opening.openingPieceTag
import app.logdate.ui.step.STEP_TITLE_TAG
import app.logdate.ui.step.StepSceneBounds
import app.logdate.ui.theme.FirstLaunchTheme
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every onboarding step keeps its title and its actions in one place, so a person tapping through
 * never has to find them again. The opening once pinned its actions to the bottom edge and once
 * floated them mid-screen with its words, and titles sat wherever each step's contents left them.
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingOpeningLayoutTest {
    @Test
    fun `the title and the main action stay put from the opening to the timeline to the next step`() {
        val opening =
            actions("opening-hero-phone", ONBOARDING_START_GET_STARTED_TAG, ONBOARDING_START_FROM_BACKUP_TAG) {
                OnboardingOpeningScreen(overview = false, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
            }
        val timeline =
            actions("opening-overview-phone", ONBOARDING_OVERVIEW_CONTINUE_TAG, "onboarding_overview_back") {
                OnboardingOpeningScreen(overview = true, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
            }
        val cloud =
            actions("cloud-welcome-phone", CLOUD_ACCOUNT_WELCOME_CREATE_TAG, CLOUD_ACCOUNT_WELCOME_SIGN_IN_TAG) {
                CloudAccountWelcomeContent(
                    onContinue = {},
                    onSignIn = {},
                    onBack = {},
                    serverSelectionState = ServerSelectionState(),
                )
            }

        var signIn = DpRect(0.dp, 0.dp, 0.dp, 0.dp)
        var signInTitle = DpRect(0.dp, 0.dp, 0.dp, 0.dp)
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    CloudAccountSignInContent(
                        username = "",
                        onUsernameChange = {},
                        onSignIn = {},
                        onAccountRecovery = {},
                        onPrivacyPolicy = {},
                        onTermsOfService = {},
                        onBack = {},
                        serverDisplayName = "LogDate Cloud",
                        serverHandleDomain = "logdate.app",
                    )
                }
            }
            save("cloud-sign-in-phone", onRoot().captureToImage().toPixelMap())
            signIn = onNode(hasText("Sign in with passkey") and hasClickAction()).getUnclippedBoundsInRoot()
            signInTitle = onNodeWithTag(STEP_TITLE_TAG).getUnclippedBoundsInRoot()
        }

        assertEquals(opening.title.top, timeline.title.top, "the title moved between the opening and the timeline")
        assertEquals(opening.title.top, cloud.title.top, "the title moved between the opening and LogDate Cloud")
        assertEquals(opening.title.top, signInTitle.top, "the title moved between the opening and signing in")

        assertEquals(opening.primary, signIn, "the main action moved between the opening and signing in")
        assertEquals(opening.primary, timeline.primary, "the main action moved between the opening and the timeline")
        assertEquals(opening.secondary.top, timeline.secondary.top, "the second action moved between the opening and the timeline")
        assertEquals(opening.primary, cloud.primary, "the main action moved between the opening and a later step")
        assertEquals(opening.secondary.bottom, cloud.secondary.bottom, "the second action moved between the opening and a later step")
        assertTrue(PHONE_HEIGHT.dp - opening.secondary.bottom in 12.dp..24.dp, "actions end ${opening.secondary.bottom} down the screen")
    }

    @Test
    fun `the opening covers the whole screen and keeps off the words and the buttons`() =
        SCREENS.forEach { (name, size) ->
            runDesktopComposeUiTest(width = size.first, height = size.second) {
                setContent {
                    LogDateTheme(dynamicColor = false, darkTheme = false) {
                        OnboardingOpeningScreen(overview = false, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
                    }
                }
                save("opening-$name", onRoot().captureToImage().toPixelMap())
                val pieces = piecesOnScreen()
                val title = boundsOf(STEP_TITLE_TAG)
                val primary = boundsOf(ONBOARDING_START_GET_STARTED_TAG)
                val secondary = boundsOf(ONBOARDING_START_FROM_BACKUP_TAG)

                val wanted = if (size.first >= 600) 10..19 else 6..8
                assertTrue(pieces.size in wanted, "${pieces.size} pieces on $name")
                pieces.forEach { (id, piece) ->
                    assertTrue(!piece.overlaps(title), "$id lies over the title on $name")
                    assertTrue(!piece.overlaps(primary), "$id lies over the main action on $name")
                    assertTrue(!piece.overlaps(secondary), "$id lies over the second action on $name")
                }
                val left = pieces.values.minOf { it.left }
                val right = pieces.values.maxOf { it.right }
                val top = pieces.values.minOf { it.top }
                val bottom = pieces.values.maxOf { it.bottom }
                assertTrue(left <= 8f && right >= size.first - 8f, "the table stops short of the sides on $name: $left to $right")
                assertTrue(top <= 8f, "the table stops short of the top on $name: $top")
                assertTrue(bottom >= primary.top - 64f, "the table stops well above the actions on $name: $bottom")
            }
        }

    @Test
    fun `tidied, today stands on one page and the rest of the table stays`() =
        SCREENS.forEach { (name, size) ->
            runDesktopComposeUiTest(width = size.first, height = size.second) {
                setContent {
                    LogDateTheme(dynamicColor = false, darkTheme = false) {
                        OnboardingOpeningScreen(overview = true, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
                    }
                }
                save("opening-$name-overview", onRoot().captureToImage().toPixelMap())
                val pieces = piecesOnScreen()
                val date = pieces.getValue("stamp-today")
                val entries = listOf("print-cafe", "note-coffee", "voice-short").map { pieces.getValue(it) }

                entries.forEachIndexed { index, one ->
                    entries.drop(index + 1).forEach { other -> assertTrue(!one.overlaps(other), "today's entries overlap on $name") }
                    assertTrue(!one.overlaps(date), "today's date lies over an entry on $name")
                }
                assertTrue(
                    date.top <= entries.minOf { it.top } && date.left <= entries.minOf { it.left },
                    "today's date does not head its page on $name",
                )
                assertEquals(1, pieces.keys.count { it.startsWith("stamp-") }, "dates on $name")
                assertTrue(pieces.size > 4, "tidying emptied the table on $name")
                val title = boundsOf(STEP_TITLE_TAG)
                val primary = boundsOf(ONBOARDING_OVERVIEW_CONTINUE_TAG)
                pieces.forEach { (id, piece) ->
                    assertTrue(!piece.overlaps(title), "$id is filed over the title on $name")
                    assertTrue(!piece.overlaps(primary), "$id is filed over the main action on $name")
                }
            }
        }

    @Test
    fun `a piece that is moved stays where it is put, and one put on the buttons slides off them`() =
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    OnboardingOpeningScreen(overview = false, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
                }
            }
            val rest = boundsOf(openingPieceTag("print-cafe"))

            onRoot().performTouchInput {
                down(rest.center)
                moveTo(rest.center + Offset(30f, 20f), delayMillis = 400)
                moveTo(rest.center + Offset(60f, 40f), delayMillis = 400)
            }
            mainClock.advanceTimeBy(600)
            save("scene-piece-held", onRoot().captureToImage().toPixelMap())
            onRoot().performTouchInput { up() }
            mainClock.advanceTimeBy(4_000)
            val moved = boundsOf(openingPieceTag("print-cafe"))
            val carried = moved.center - rest.center
            assertTrue(carried.x in 45f..90f, "the photo moved ${carried.x} across, not the 60 it was carried")
            assertTrue(carried.y in 25f..70f, "the photo moved ${carried.y} down, not the 40 it was carried")

            val primary = boundsOf(ONBOARDING_START_GET_STARTED_TAG)
            onRoot().performTouchInput {
                down(moved.center)
                moveTo(moved.center + (primary.center - moved.center) / 2f, delayMillis = 400)
                moveTo(primary.center, delayMillis = 400)
            }
            mainClock.advanceTimeBy(600)
            onRoot().performTouchInput { up() }
            mainClock.advanceTimeBy(4_000)
            save("scene-piece-dropped-on-buttons", onRoot().captureToImage().toPixelMap())
            val dropped = boundsOf(openingPieceTag("print-cafe"))
            assertTrue(!dropped.overlaps(primary), "the photo was left lying on the main action: $dropped over $primary")
            assertTrue(dropped.width * dropped.height > 0f, "the photo left the screen")
        }

    @Test
    fun `turning the device moves the nearest pieces further than the ones behind them`() =
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            var turned by mutableStateOf(Offset.Zero)
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    FirstLaunchTheme {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            OpeningScene(
                                bounds =
                                    StepSceneBounds(
                                        size = Size(PHONE_WIDTH.toFloat(), PHONE_HEIGHT.toFloat()),
                                        words = Rect(16f, 184f, 374f, 260f),
                                        actions = Rect(16f, 716f, 374f, 828f),
                                    ),
                                tidied = false,
                                animate = false,
                                parallax = { turned },
                            )
                        }
                    }
                }
            }
            val nearAtRest = boundsOf(openingPieceTag("note-coffee"))
            val farAtRest = boundsOf(openingPieceTag("print-cake"))
            turned = Offset(-1f, 1f)
            waitForIdle()
            save("scene-turned", onRoot().captureToImage().toPixelMap())
            val near = boundsOf(openingPieceTag("note-coffee")).bottom - nearAtRest.bottom
            val far = boundsOf(openingPieceTag("print-cake")).bottom - farAtRest.bottom
            assertTrue(far > 1f, "the piece behind did not move: $far")
            assertTrue(near > far + 3f, "the nearest piece moved $near and the one behind it $far")
        }

    @Test
    fun `the scene is on the screen in the very first frame`() =
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            // A screenshot or a preview draws one frame and no more. With the clock held, nothing
            // that waits for a second frame can appear.
            mainClock.autoAdvance = false
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    OnboardingOpeningScreen(overview = false, onContinue = {}, onBack = {}, onSignIn = {}, animateScene = false)
                }
            }
            val pieces = piecesOnScreen()
            assertTrue(pieces.size >= 6, "only ${pieces.size} pieces are drawn in the first frame")
        }

    @Test
    fun `the scene lies over the step without getting in the way of its actions`() =
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            var continued = 0
            var signedIn = 0
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    OnboardingOpeningScreen(
                        overview = false,
                        onContinue = { continued++ },
                        onBack = {},
                        onSignIn = { signedIn++ },
                        animateScene = false,
                    )
                }
            }
            onNodeWithTag(ONBOARDING_START_GET_STARTED_TAG).performClick()
            onNodeWithTag(ONBOARDING_START_FROM_BACKUP_TAG).performClick()
            assertEquals(1, continued, "the main action did not answer a tap")
            assertEquals(1, signedIn, "the second action did not answer a tap")

            // A screen reader finds what is under a finger by the node in front. The scene is in
            // front of everything, so its description sits on one piece and not across the screen.
            val described = onNode(hasContentDescription("An example of a few days", substring = true)).fetchSemanticsNode().boundsInRoot
            assertTrue(described.width < PHONE_WIDTH / 2f, "the scene's description covers $described")
            assertTrue(!described.overlaps(boundsOf(ONBOARDING_START_GET_STARTED_TAG)), "the scene's description lies over the main action")
        }

    @Test
    fun `the table is dealt from off the screen and today's date comes down last`() =
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            mainClock.autoAdvance = false
            var overview by mutableStateOf(false)
            var taps = 0
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    OnboardingOpeningScreen(overview = overview, onContinue = { taps++ }, onBack = {}, onSignIn = {})
                }
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            val started = mainClock.currentTime

            fun at(millis: Long) = mainClock.advanceTimeBy((started + millis - mainClock.currentTime).coerceAtLeast(0))

            at(40)
            save("deal-0040", onRoot().captureToImage().toPixelMap())
            assertTrue("print-cafe" !in piecesOnScreen(), "today's photo is on the table before it has been dealt")
            // Pieces on their way in cross the actions. A tap on an action then is still a tap on the action.
            at(450)
            onNodeWithTag(ONBOARDING_START_GET_STARTED_TAG).performClick()
            assertEquals(1, taps, "a piece in flight took a tap meant for the main action")
            listOf(500L, 700L, 900L, 1_100L, 1_300L, 1_500L).forEach { millis ->
                at(millis)
                save("deal-${millis.toString().padStart(4, '0')}", onRoot().captureToImage().toPixelMap())
            }
            at(3_500)
            save("deal-3500", onRoot().captureToImage().toPixelMap())
            val dealt = piecesOnScreen()
            assertTrue(dealt.size >= 6, "only ${dealt.size} pieces were dealt")

            overview = true
            val tidying = mainClock.currentTime
            listOf(150L, 350L, 600L, 1_000L, 2_500L).forEach { millis ->
                mainClock.advanceTimeBy(tidying + millis - mainClock.currentTime)
                save("tidy-${millis.toString().padStart(4, '0')}", onRoot().captureToImage().toPixelMap())
            }
            val tidied = piecesOnScreen()
            val entries = listOf("print-cafe", "note-coffee", "voice-short").map { tidied.getValue(it) }
            entries.forEachIndexed { index, one ->
                entries.drop(index + 1).forEach { other -> assertTrue(!one.overlaps(other), "today's entries overlap on the page") }
            }
            assertTrue(OpeningCatalog.HEART !in tidied, "a piece in the page's way is still on the screen")
            assertTrue("print-cake" in tidied, "tidying cleared a piece that was not in the way")
        }

    @Test
    fun `the opening holds together in the dark, with large text, on its side and unfolded`() {
        val looks =
            listOf(
                Triple("phone-dark", PHONE_WIDTH to PHONE_HEIGHT, true to 1f),
                Triple("phone-large-text", PHONE_WIDTH to PHONE_HEIGHT, false to 2f),
                Triple("phone-landscape", 844 to 390, false to 1f),
                Triple("unfolded", 673 to 841, false to 1f),
                Triple("desktop", 1600 to 1000, false to 1f),
            )
        looks.forEach { (name, size, look) ->
            listOf(false, true).forEach { overview ->
                runDesktopComposeUiTest(width = size.first, height = size.second) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, look.second)) {
                            LogDateTheme(dynamicColor = false, darkTheme = look.first) {
                                OnboardingOpeningScreen(
                                    overview = overview,
                                    onContinue = {},
                                    onBack = {},
                                    onSignIn = {},
                                    animateScene = false,
                                )
                            }
                        }
                    }
                    save("opening-$name${if (overview) "-overview" else ""}", onRoot().captureToImage().toPixelMap())
                    val pieces = piecesOnScreen()
                    val title = boundsOf(STEP_TITLE_TAG)
                    assertTrue(pieces.size >= 2, "only ${pieces.size} pieces on $name")
                    pieces.forEach { (id, piece) -> assertTrue(!piece.overlaps(title), "$id lies over the title on $name") }
                }
            }
        }
    }

    /** Every piece of the opening that shows on the screen, by its id, with the room it takes as drawn. */
    private fun ComposeUiTest.piecesOnScreen(): Map<String, Rect> =
        OpeningCatalog.all
            .mapNotNull { piece ->
                val shown = onAllNodesWithTag(openingPieceTag(piece.id)).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                if (shown == null || shown.width <= 0f || shown.height <= 0f) null else piece.id to shown
            }.toMap()

    /** Where a node is drawn, turned and scaled as it is. The test window has one pixel to the dp. */
    private fun ComposeUiTest.boundsOf(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private class ActionBounds(
        val primary: DpRect,
        val secondary: DpRect,
        val title: DpRect,
    )

    private fun actions(
        name: String,
        primaryTag: String,
        secondaryTag: String,
        content: @Composable () -> Unit,
    ): ActionBounds {
        lateinit var bounds: ActionBounds
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { LogDateTheme(dynamicColor = false, darkTheme = false, content = content) }
            save(name, onRoot().captureToImage().toPixelMap())
            bounds =
                ActionBounds(
                    onNodeWithTag(primaryTag).getUnclippedBoundsInRoot(),
                    onNodeWithTag(secondaryTag).getUnclippedBoundsInRoot(),
                    onNodeWithTag(STEP_TITLE_TAG).getUnclippedBoundsInRoot(),
                )
        }
        return bounds
    }

    private fun save(
        name: String,
        pixels: androidx.compose.ui.graphics.PixelMap,
    ) {
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/reports/onboarding-opening").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "$name.png"))
    }

    private companion object {
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844

        // The screens the opening is composed for, in dp.
        val SCREENS =
            listOf(
                "phone" to (PHONE_WIDTH to PHONE_HEIGHT),
                "small-phone" to (360 to 640),
                "tablet-landscape" to (1280 to 800),
                "tablet-portrait" to (800 to 1280),
            )
    }
}
