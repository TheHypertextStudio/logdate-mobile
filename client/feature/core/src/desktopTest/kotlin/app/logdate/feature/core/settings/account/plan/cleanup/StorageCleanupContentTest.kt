package app.logdate.feature.core.settings.account.plan.cleanup

import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.logdate.client.domain.storage.StoredItem
import app.logdate.client.domain.storage.StoredKind
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The clean-up page as someone meets it: what it lists, what choosing does, and what deleting asks. */
@OptIn(ExperimentalTestApi::class)
class StorageCleanupContentTest {
    /** Holds the page's state and changes it the way the view model does. */
    private class Page(
        initial: StorageCleanupUiState,
    ) {
        var state by mutableStateOf(initial)
        var deletions = 0

        val actions =
            StorageCleanupActions(
                onToggle = { id ->
                    change {
                        it.copy(
                            selected =
                                if (id in
                                    it.selected
                                ) {
                                    it.selected - id
                                } else {
                                    it.selected + id
                                },
                            outcome = null,
                        )
                    }
                },
                onClearSelection = { change { it.copy(selected = emptySet()) } },
                onDelete = {
                    deletions++
                    change { ready ->
                        val gone = ready.selectedItems
                        ready.copy(
                            items = ready.items - gone.toSet(),
                            selected = emptySet(),
                            outcome = CleanupOutcome(gone.sumOf { it.sizeBytes ?: 0L }, gone.size, failedCount = 0),
                        )
                    }
                },
                onResultShown = { change { it.copy(outcome = null) } },
            )

        private fun change(block: (StorageCleanupUiState.Ready) -> StorageCleanupUiState.Ready) {
            state = (state as? StorageCleanupUiState.Ready)?.let(block) ?: state
        }
    }

    private fun cleanupPage(
        kind: StoredKind,
        state: StorageCleanupUiState,
        dark: Boolean = false,
        width: Int = 411,
        height: Int = 900,
        fontScale: Float = 1f,
        test: DesktopComposeUiTest.(Page) -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        val page = Page(state)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                LogDateTheme(darkTheme = dark, dynamicColor = false) {
                    StorageCleanupContent(
                        kind = kind,
                        state = page.state,
                        onBack = {},
                        actions = page.actions,
                        thumbnail = { item, modifier ->
                            StorageCleanupFixtures.picture(item)?.let {
                                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
                            }
                        },
                    )
                }
            }
        }
        test(page)
    }

    private fun ready(
        items: List<StoredItem>,
        selected: Set<Uuid> = emptySet(),
        outcome: CleanupOutcome? = null,
        deleting: Boolean = false,
    ) = StorageCleanupUiState.Ready(items = items, selected = selected, outcome = outcome, deleting = deleting)

    private fun DesktopComposeUiTest.item(item: StoredItem) = onNodeWithTag(storageCleanupItemTag(item.id))

    private fun DesktopComposeUiTest.scrollTo(item: StoredItem) {
        onNode(hasScrollAction()).performScrollToNode(hasTestTag(storageCleanupItemTag(item.id)))
    }

    @Test
    fun photosAreAGridLargestFirstWithTheirSizes() {
        val photos = StorageCleanupFixtures.photos
        cleanupPage(StoredKind.Photos, ready(photos)) {
            saveScreenshot("cleanup-photos", onRoot().captureToImage())
            onNodeWithText("Photos").assertIsDisplayed()
            onNodeWithText("112 MB").assertIsDisplayed()
            onNodeWithText("${photos.size} photos, largest first").assertIsDisplayed()
            onNode(hasContentDescription("14 MB", substring = true)).assertIsDisplayed()

            // Three across on a phone, in the order given: the largest is first on the first line.
            val first = item(photos[0]).getUnclippedBoundsInRoot()
            val second = item(photos[1]).getUnclippedBoundsInRoot()
            val fourth = item(photos[3]).getUnclippedBoundsInRoot()
            assertEquals(first.top, second.top)
            assertTrue(second.left > first.right)
            assertTrue(fourth.top >= first.bottom)
            assertEquals(first.left, fourth.left)
        }
    }

    @Test
    fun aWiderPageGetsLargerPicturesAndMoreOfThem() {
        val photos = StorageCleanupFixtures.photos
        var phoneWidth = 0.dp
        cleanupPage(StoredKind.Photos, ready(photos)) { phoneWidth = item(photos[0]).getUnclippedBoundsInRoot().width }
        cleanupPage(StoredKind.Photos, ready(photos, selected = setOf(photos[1].id, photos[4].id)), width = 840, height = 1100) {
            saveScreenshot("cleanup-photos-tablet", onRoot().captureToImage())
            val first = item(photos[0]).getUnclippedBoundsInRoot()
            val fifth = item(photos[4]).getUnclippedBoundsInRoot()
            assertTrue(first.width > phoneWidth, "pictures are ${first.width} on a tablet and $phoneWidth on a phone")
            assertEquals(first.top, fifth.top)
        }
    }

    @Test
    fun choosingThingsAddsThemUpInTheBar() {
        val photos = StorageCleanupFixtures.photos
        cleanupPage(StoredKind.Photos, ready(photos)) { page ->
            onNodeWithTag(STORAGE_CLEANUP_BAR_TAG).assertDoesNotExist()

            item(photos[0]).assertIsOff().performClick()
            item(photos[2]).performClick()
            waitForIdle()

            item(photos[0]).assertIsOn()
            onNodeWithTag(STORAGE_CLEANUP_BAR_TAG).assertIsDisplayed()
            onNodeWithText("2 selected").assertIsDisplayed()
            onNodeWithText("25 MB").assertIsDisplayed()
            saveScreenshot("cleanup-photos-chosen", onRoot().captureToImage())

            item(photos[2]).performClick()
            waitForIdle()
            onNodeWithText("1 selected").assertIsDisplayed()

            onNodeWithTag(STORAGE_CLEANUP_CLEAR_TAG).performClick()
            waitForIdle()
            assertTrue((page.state as StorageCleanupUiState.Ready).selected.isEmpty())
            onNodeWithTag(STORAGE_CLEANUP_BAR_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun deletingAsksOnceSayingWhatIsFreedAndThatItCannotBeUndone() {
        val photos = StorageCleanupFixtures.photos
        cleanupPage(StoredKind.Photos, ready(photos, selected = setOf(photos[0].id, photos[2].id))) { page ->
            onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).performClick()
            waitForIdle()

            onNodeWithText("Delete 2 photos?").assertIsDisplayed()
            onNodeWithText("This frees 25 MB. They’re removed from every device and can’t be undone.").assertIsDisplayed()
            // The whole window, since the dialog is a root of its own.
            saveScreenshot("cleanup-confirm", captureToImage())

            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertEquals(0, page.deletions)
            onNodeWithText("Delete 2 photos?").assertDoesNotExist()

            onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).performClick()
            waitForIdle()
            // The dialog's own Delete, not the bar's.
            onNodeWithTag(STORAGE_CLEANUP_CONFIRM_TAG).performClick()
            waitForIdle()

            assertEquals(1, page.deletions)
            item(photos[0]).assertDoesNotExist()
            item(photos[1]).assertIsDisplayed()
        }
    }

    @Test
    fun deletingOneThingAsksAboutOneThing() {
        val recordings = StorageCleanupFixtures.recordings
        cleanupPage(StoredKind.Recordings, ready(recordings, selected = setOf(recordings[0].id))) {
            onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).performClick()
            waitForIdle()

            onNodeWithText("Delete 1 recording?").assertIsDisplayed()
            onNodeWithText("This frees 36 MB. It’s removed from every device and can’t be undone.").assertIsDisplayed()
        }
    }

    @Test
    fun afterDeletingTheBarSaysWhatWasFreed() {
        val photos = StorageCleanupFixtures.photos
        cleanupPage(StoredKind.Photos, ready(photos.drop(2), outcome = CleanupOutcome(26_214_400, deletedCount = 2, failedCount = 0))) {
            onNodeWithTag(STORAGE_CLEANUP_RESULT_TAG).assertIsDisplayed()
            onNodeWithText("25 MB freed").assertIsDisplayed()
            saveScreenshot("cleanup-freed", onRoot().captureToImage())
        }
    }

    @Test
    fun whatCouldNotBeDeletedStaysChosenAndSaysSo() {
        val photos = StorageCleanupFixtures.photos
        cleanupPage(
            StoredKind.Photos,
            ready(photos, selected = setOf(photos[1].id), outcome = CleanupOutcome(0, deletedCount = 0, failedCount = 1)),
        ) {
            item(photos[1]).assertIsOn()
            onNodeWithText("1 selected").assertIsDisplayed()
            onNodeWithText("1 couldn’t be deleted").assertIsDisplayed()
            onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun nothingCanBeChosenWhileDeleting() {
        val photos = StorageCleanupFixtures.photos
        cleanupPage(StoredKind.Photos, ready(photos, selected = setOf(photos[0].id), deleting = true)) { page ->
            item(photos[1]).performClick()
            onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).performClick()
            waitForIdle()

            assertEquals(setOf(photos[0].id), (page.state as StorageCleanupUiState.Ready).selected)
            onNodeWithText("Delete 1 photo?").assertDoesNotExist()
        }
    }

    @Test
    fun recordingsAreRowsWithWhatWasSaidAndHowLong() {
        val recordings = StorageCleanupFixtures.recordings
        cleanupPage(StoredKind.Recordings, ready(recordings, selected = setOf(recordings[1].id))) {
            saveScreenshot("cleanup-recordings", onRoot().captureToImage())
            onNodeWithText("Recordings").assertIsDisplayed()
            onNodeWithContentDescription("Okay so we finally made it up to the lookout and, 36 MB").assertIsDisplayed()
            scrollTo(recordings.last())
            onNodeWithContentDescription("Checking the mic before the interview, Not downloaded").assertIsDisplayed()
            item(recordings[1]).assertIsOn()
        }
    }

    @Test
    fun writtenEntriesReadInDarkTheme() {
        val entries = StorageCleanupFixtures.entries
        cleanupPage(StoredKind.WrittenEntries, ready(entries, selected = setOf(entries[0].id, entries[3].id)), dark = true) {
            saveScreenshot("cleanup-entries-dark", onRoot().captureToImage())
            onNodeWithText("Written entries").assertIsDisplayed()
            onNodeWithText("${entries.size} entries, largest first").assertIsDisplayed()
            onNodeWithText("2 selected").assertIsDisplayed()
        }
    }

    @Test
    fun videosSayHowLongTheyRun() {
        val videos = StorageCleanupFixtures.videos
        cleanupPage(StoredKind.Videos, ready(videos)) {
            saveScreenshot("cleanup-videos", onRoot().captureToImage())
            onNode(hasContentDescription("412 MB · 3:24", substring = true)).assertIsDisplayed()
        }
    }

    @Test
    fun rowsFillAWiderPageInTwoColumns() {
        val recordings = StorageCleanupFixtures.recordings
        cleanupPage(StoredKind.Recordings, ready(recordings), width = 840, height = 1100) {
            saveScreenshot("cleanup-recordings-tablet", onRoot().captureToImage())
            val first = item(recordings[0]).getUnclippedBoundsInRoot()
            val second = item(recordings[1]).getUnclippedBoundsInRoot()
            assertEquals(first.top, second.top)
            assertTrue(second.left > first.right)
        }
    }

    @Test
    fun atTwiceTheTextSizeDeletingStaysInReach() {
        val recordings = StorageCleanupFixtures.recordings
        cleanupPage(StoredKind.Recordings, ready(recordings, selected = setOf(recordings[0].id)), fontScale = 2f) {
            saveScreenshot("cleanup-recordings-large-text", onRoot().captureToImage())
            onNodeWithText("1 selected").assertIsDisplayed()
            val window = onRoot().getUnclippedBoundsInRoot()
            val delete = onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(delete.right <= window.right && delete.bottom <= window.bottom, "Delete is at $delete in $window")
        }
        val photos = StorageCleanupFixtures.photos
        cleanupPage(StoredKind.Photos, ready(photos, selected = setOf(photos[0].id)), fontScale = 2f) {
            saveScreenshot("cleanup-photos-large-text", onRoot().captureToImage())
            onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun aKindWithNothingStoredSaysSo() =
        cleanupPage(StoredKind.Videos, ready(emptyList())) {
            saveScreenshot("cleanup-empty", onRoot().captureToImage())
            onNodeWithTag(STORAGE_CLEANUP_EMPTY_TAG).assertIsDisplayed()
            onNodeWithText("No videos").assertIsDisplayed()
            onNodeWithTag(STORAGE_CLEANUP_BAR_TAG).assertDoesNotExist()
        }

    @Test
    fun everythingTouchableIsALargeEnoughTarget() {
        val smallest = 48.dp
        val photos = StorageCleanupFixtures.photos
        cleanupPage(StoredKind.Photos, ready(photos, selected = setOf(photos[0].id))) {
            item(photos[0]).assertHeightIsAtLeast(smallest).assertWidthIsAtLeast(smallest)
            onNodeWithTag(STORAGE_CLEANUP_DELETE_TAG).assertHeightIsAtLeast(smallest).assertWidthIsAtLeast(smallest)
            onNodeWithTag(STORAGE_CLEANUP_CLEAR_TAG).assertHeightIsAtLeast(smallest).assertWidthIsAtLeast(smallest)
        }
        val entries = StorageCleanupFixtures.entries
        cleanupPage(StoredKind.WrittenEntries, ready(entries)) {
            scrollTo(entries.last())
            item(entries.last()).assertHeightIsAtLeast(smallest)
        }
    }

    private fun saveScreenshot(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_SETTINGS_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
