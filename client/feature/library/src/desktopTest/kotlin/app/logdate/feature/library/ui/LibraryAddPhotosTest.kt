package app.logdate.feature.library.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An empty library is where someone looks for the photos they already have, so that is where
 * bringing them in from the device is offered.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryAddPhotosTest {
    @Test
    fun `an empty library offers to bring in photos from the device`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var requests = 0
            setContent {
                LogDateTheme {
                    LibraryScreenContent(LibraryUiState.Empty, {}, onAddPhotos = { requests++ })
                }
            }
            onNodeWithTag(LIBRARY_ADD_PHOTOS_TAG).assertIsDisplayed().performClick()
            assertEquals(1, requests)
        }

    @Test
    fun `the offer is left out where photos cannot be brought in`() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                LogDateTheme {
                    LibraryScreenContent(LibraryUiState.Empty, {})
                }
            }
            assertTrue(onAllNodesWithTag(LIBRARY_ADD_PHOTOS_TAG).fetchSemanticsNodes().isEmpty())
        }
}
