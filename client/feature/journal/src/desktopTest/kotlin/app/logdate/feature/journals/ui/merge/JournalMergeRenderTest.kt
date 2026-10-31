package app.logdate.feature.journals.ui.merge

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalMergeCandidate
import app.logdate.client.repository.journals.JournalMergePreview
import app.logdate.shared.model.Journal
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class JournalMergeRenderTest {
    private val source = Journal(id = id(201), title = "Summer in the mountains")
    private val destination = Journal(id = id(202), title = "Journeys with friends and family")
    private val preview = JournalMergePreview(source, destination, (1..12).mapTo(mutableSetOf(), ::id), (9..36).mapTo(mutableSetOf(), ::id))
    private val picker =
        JournalMergeUiState(
            sourceTitle = source.title,
            source = JournalMergeCandidate(source, 12),
            loading = false,
            candidates =
                listOf(
                    JournalMergeCandidate(destination, 28),
                    JournalMergeCandidate(Journal(id = id(203), title = "Weekend field notes"), 16),
                    JournalMergeCandidate(Journal(id = id(204), title = "The road to the coast and everything we found"), 42),
                ),
        )

    @Test
    fun populatedPickerAndReviewAtMatchingPhoneAndTabletSizes() {
        listOf(false, true).forEach { review ->
            render("phone-${if (review) "review" else "picker"}", 411, 891, review)
            render("tablet-${if (review) "review" else "picker"}", 1280, 800, review)
        }
    }

    @Test
    fun compactLandscapeDarkRtlLargeTextAndHingesStayUsable() {
        render("compact", 320, 640, false)
        render("compact-review", 320, 891, true)
        render("landscape", 891, 411, true)
        render("dark", 411, 891, false, dark = true)
        render("rtl", 411, 891, true, rtl = true)
        render("large-text", 411, 891, true, fontScale = 2f)
        render("tablet-portrait", 800, 1280, true)
        render("tablet-rtl", 1280, 800, true, rtl = true)
        render("tablet-dark", 1280, 800, true, dark = true)
        render("book", 1440, 900, true, foldable = book)
        render("tabletop", 1440, 900, true, foldable = tabletop)
    }

    @Test
    fun sourcePickerShowsTheDestinationAndKeepsTheSourceChoicesSearchable() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        picker.copy(
                            pickerTarget = JournalMergePickerTarget.Source,
                            editingSelection = true,
                            destinationTitle = destination.title,
                            candidates = listOf(JournalMergeCandidate(source, 12)) + picker.candidates.drop(1),
                        ),
                    )
                }
            }
            onNodeWithText("Move contents from…").assertIsDisplayed()
            onNodeWithText("Into ‘${destination.title}’").assertIsDisplayed()
            onNodeWithText(source.title).assertIsDisplayed()
            save("source-picker", onRoot().captureToImage())
        }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        review: Boolean,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        foldable: FoldableLayoutInfo? = null,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            LogDateTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalWorkspaceSearchAction provides {},
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    provideFoldableLayoutInfo(foldable ?: FoldableLayoutInfo()) {
                        JournalMergeScreenContent(if (review) picker.copy(stage = JournalMergeStage.Review(preview)) else picker)
                    }
                }
            }
        }
        onAllNodesWithTag("workspace_search").assertCountEquals(0)
        onNodeWithTag("journal_merge_activity").assertIsDisplayed()
        save(name, onRoot().captureToImage())
        if (review) {
            onNodeWithText("Merge journals").performScrollTo()
            onNodeWithContentDescription("Change destination").assertExists()
            onNodeWithText("Cancel").performScrollTo()
            onNodeWithText("Cancel").assertIsDisplayed()
        } else {
            onNodeWithText(destination.title).assertExists()
        }
        if (review) save("$name-actions", onRoot().captureToImage())
    }

    private fun save(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_MERGE_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }

    private fun id(value: Int) = Uuid.parse("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")

    private val book =
        FoldableLayoutInfo(
            true,
            FoldablePosture.Book,
            FoldableHingeInfo(
                FoldableHingeOrientation.Vertical,
                FoldableHingeState.HalfOpened,
                FoldableOcclusionType.Full,
                FoldableHingeBounds(708.dp, 0.dp, 732.dp, 900.dp, 24.dp, 900.dp),
                true,
            ),
        )
    private val tabletop =
        FoldableLayoutInfo(
            true,
            FoldablePosture.Tabletop,
            FoldableHingeInfo(
                FoldableHingeOrientation.Horizontal,
                FoldableHingeState.HalfOpened,
                FoldableOcclusionType.Full,
                FoldableHingeBounds(0.dp, 438.dp, 1440.dp, 462.dp, 1440.dp, 24.dp),
                true,
            ),
        )
}
