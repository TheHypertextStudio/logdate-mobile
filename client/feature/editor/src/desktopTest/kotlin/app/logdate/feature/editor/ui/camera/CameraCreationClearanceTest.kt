package app.logdate.feature.editor.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.handwriting.EntryPageChrome
import app.logdate.feature.editor.ui.handwriting.EntryPageOverviewState
import app.logdate.ui.platform.LocalReduceMotionOverride
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class CameraCreationClearanceTest(
    private val width: Int,
    private val height: Int,
    private val review: Boolean,
) {
    @Test
    fun cameraControlsOwnLowerAreaWithoutCreationChoicesWhilePreviewPaintsBehindSystemUi() =
        runDesktopComposeUiTest(width = width, height = height) {
            val block = CameraBlockUiState()
            var taps = 0
            setContent {
                CompositionLocalProvider(LocalReduceMotionOverride provides true) {
                    MaterialTheme {
                        EntryPageChrome(
                            EditorState(blocks = listOf(block), expandedBlockId = block.id),
                            EntryPageOverviewState(false),
                            EntryStartMode.CAMERA,
                            {},
                            {},
                            {},
                            header = {},
                            safeInsets = WindowInsets(0, 24, 0, 20),
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.Blue)) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 20.dp)
                                        .cameraCreationClearance()
                                        .padding(bottom = 24.dp),
                                ) {
                                    Button(
                                        { taps++ },
                                        Modifier.size(if (review) 144.dp else 72.dp, if (review) 48.dp else 72.dp).testTag("camera_probe"),
                                    ) {
                                        Text(if (review) "Use photo" else "Shutter")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val target = onNodeWithTag("camera_probe")
            val bounds = target.fetchSemanticsNode().boundsInRoot
            onNodeWithTag("entry_creation_actions").assertIsNotDisplayed()
            assertTrue(bounds.bottom <= height - 20, "Camera action must clear system navigation: $bounds")
            assertTrue(bounds.width >= 48f && bounds.height >= 48f)
            target.performTouchInput { click() }
            assertEquals(1, taps, "The physical camera action must not be intercepted by creation choices")
            val pixels = onRoot().captureToImage().toPixelMap()
            assertEquals(Color.Blue, pixels[0, 0])
            assertEquals(Color.Blue, pixels[0, height - 1], "Reserve inner controls without shrinking the full-window preview")
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}x{1}-review={2}")
        fun variants() =
            listOf(arrayOf<Any>(400, 800, false), arrayOf<Any>(800, 400, false), arrayOf<Any>(400, 800, true), arrayOf<Any>(800, 400, true))
    }
}
