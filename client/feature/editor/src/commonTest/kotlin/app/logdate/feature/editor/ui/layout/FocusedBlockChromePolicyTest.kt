package app.logdate.feature.editor.ui.layout

import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class FocusedBlockChromePolicyTest {
    private val chromeHeight = 64.dp
    private val dockReserve = 80.dp

    @Test
    fun `text block reserves top chrome and creation dock space`() {
        val block = TextBlockUiState()
        val clearance = FocusedBlockChromePolicy.clearanceFor(block, chromeHeight, dockReserve)
        assertEquals(chromeHeight, clearance.top)
        assertEquals(dockReserve, clearance.bottom)
    }

    @Test
    fun `idle audio block reserves top chrome and creation dock space`() {
        val block = AudioBlockUiState(captureState = AudioCaptureState.Empty)
        val clearance = FocusedBlockChromePolicy.clearanceFor(block, chromeHeight, dockReserve)
        assertEquals(chromeHeight, clearance.top)
        assertEquals(dockReserve, clearance.bottom)
    }

    @Test
    fun `recorded audio block reserves top chrome but does not reserve dock space`() {
        val block =
            AudioBlockUiState(
                captureState =
                    AudioCaptureState.Ready(
                        uri = "file:///test.m4a",
                        durationMs = 5000L,
                    ),
            )
        val clearance = FocusedBlockChromePolicy.clearanceFor(block, chromeHeight, dockReserve)
        assertEquals(chromeHeight, clearance.top)
        assertEquals(0.dp, clearance.bottom)
    }

    @Test
    fun `camera capture does not reserve chrome space to allow full-bleed viewfinder`() {
        val block = CameraBlockUiState()
        val clearance = FocusedBlockChromePolicy.clearanceFor(block, chromeHeight, dockReserve)
        assertEquals(0.dp, clearance.top)
        assertEquals(0.dp, clearance.bottom)
    }

    @Test
    fun `image and video blocks do not reserve chrome space`() {
        val image = ImageBlockUiState()
        val video = VideoBlockUiState()
        assertEquals(
            FocusedBlockChromeClearance(0.dp, 0.dp),
            FocusedBlockChromePolicy.clearanceFor(image, chromeHeight, dockReserve),
        )
        assertEquals(
            FocusedBlockChromeClearance(0.dp, 0.dp),
            FocusedBlockChromePolicy.clearanceFor(video, chromeHeight, dockReserve),
        )
    }
}
