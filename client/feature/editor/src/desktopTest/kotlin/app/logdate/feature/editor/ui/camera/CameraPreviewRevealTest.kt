package app.logdate.feature.editor.ui.camera

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CameraPreviewRevealTest {
    @Test(timeout = 60_000)
    fun theCardStaysDarkUntilTheFirstFrameThenFadesUp() =
        runDesktopComposeUiTest {
            var streaming by mutableStateOf(false)
            setContent { MaterialTheme { CameraPreviewRevealCover(streaming = streaming) } }
            onNodeWithTag(CAMERA_PREVIEW_COVER_TAG).assertIsDisplayed()

            mainClock.autoAdvance = false
            streaming = true
            mainClock.advanceTimeBy(48)
            // Partway through, the cover is still there: the picture fades up instead of popping in.
            onNodeWithTag(CAMERA_PREVIEW_COVER_TAG).assertExists()
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag(CAMERA_PREVIEW_COVER_TAG).assertDoesNotExist()

            // Flipping cameras drops the stream for a moment; that has its own cover, so this one stays gone.
            streaming = false
            waitForIdle()
            onNodeWithTag(CAMERA_PREVIEW_COVER_TAG).assertDoesNotExist()
        }
}
