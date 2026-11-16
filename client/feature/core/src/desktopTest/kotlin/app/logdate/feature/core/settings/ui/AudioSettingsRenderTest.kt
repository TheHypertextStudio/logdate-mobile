package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.LocalFoldableLayoutInfoOverride
import app.logdate.ui.platform.LocalReduceMotionOverride
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AudioSettingsRenderTest {
    @Test
    fun asymmetricRtlFoldsKeepContentInThePhysicalUsablePane() {
        for ((left, right) in listOf(280 to 300, 380 to 400)) {
            runDesktopComposeUiTest(width = 1000, height = 800) {
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalLayoutDirection provides LayoutDirection.Rtl,
                        LocalFoldableLayoutInfoOverride provides book(left, right),
                    ) {
                        LogDateTheme {
                            AudioModelSettingsContent(
                                AudioSettingsCapability.SoundRecognition,
                                VoiceNotesSettingsViewModel.UiState(),
                                {},
                                {},
                            )
                        }
                    }
                }
                val title = onNodeWithText("Sound recognition").fetchSemanticsNode().boundsInRoot
                assertTrue(title.left >= right, "Title at $title crossed physical hinge $left..$right")
                assertTrue(title.right <= 1000)
            }
        }
    }

    @Test
    fun populatedSettingsAcrossSizesAndStates() {
        val cases =
            listOf(
                Scene("overview-phone", overview = true, status = ModelDownloadStatus.Completed),
                Scene("recognition-phone", status = ModelDownloadStatus.Idle),
                Scene("transcription-ready", capability = AudioSettingsCapability.Transcription, status = ModelDownloadStatus.Completed),
                Scene("download-tablet", width = 1000, height = 800, status = ModelDownloadStatus.Downloading(125_000_000, 400_000_000)),
                Scene("check-failed-dark", dark = true, status = ModelDownloadStatus.CheckFailed),
                Scene(
                    "system-large-text",
                    width = 360,
                    fontScale = 2f,
                    capability = AudioSettingsCapability.Transcription,
                    status = ModelDownloadStatus.ManagedBySystem,
                ),
                Scene("recognition-rtl", rtl = true, status = ModelDownloadStatus.Completed),
                Scene("recognition-book", width = 1000, height = 800, fold = book(), status = ModelDownloadStatus.AwaitingUpdate),
                Scene(
                    "recognition-tabletop",
                    width = 800,
                    height = 900,
                    fold = tabletop(),
                    reduceMotion = true,
                    status = ModelDownloadStatus.Downloading(125_000_000, 400_000_000),
                ),
                Scene("recognition-landscape", width = 891, height = 411, status = ModelDownloadStatus.OutOfStorage),
            )
        for (scene in cases) {
            runDesktopComposeUiTest(width = scene.width, height = scene.height) {
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, scene.fontScale),
                        LocalLayoutDirection provides if (scene.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalFoldableLayoutInfoOverride provides scene.fold,
                        LocalReduceMotionOverride provides scene.reduceMotion,
                    ) {
                        LogDateTheme(darkTheme = scene.dark) {
                            val state =
                                VoiceNotesSettingsViewModel.UiState(
                                    supportsLiveTranscription = scene.overview,
                                    transcription = VoiceNotesSettingsViewModel.ModelRowState(scene.status),
                                    tagging =
                                        VoiceNotesSettingsViewModel.ModelRowState(
                                            if (scene.overview) ModelDownloadStatus.Idle else scene.status,
                                        ),
                                )
                            if (scene.overview) {
                                AudioNotesSettingsContent(state, {}, {}, {})
                            } else {
                                AudioModelSettingsContent(scene.capability, state, {}, {})
                            }
                        }
                    }
                }
                val title =
                    when {
                        scene.overview -> "Audio notes"
                        scene.capability == AudioSettingsCapability.Transcription -> "Transcription"
                        else -> "Sound recognition"
                    }
                onNodeWithText(title).assertExists()
                val output = System.getenv("LOGDATE_AUDIO_SCREENSHOT_DIR")
                if (output != null) {
                    val pixels = onRoot().captureToImage().toPixelMap()
                    val rendered = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until pixels.height) {
                        for (x in 0 until pixels.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
                    }
                    val directory = File(output).apply { mkdirs() }
                    ImageIO.write(rendered, "png", File(directory, "${scene.name}.png"))
                }
            }
        }
    }

    private data class Scene(
        val name: String,
        val width: Int = 411,
        val height: Int = 891,
        val overview: Boolean = false,
        val capability: AudioSettingsCapability = AudioSettingsCapability.SoundRecognition,
        val status: ModelDownloadStatus = ModelDownloadStatus.Checking,
        val dark: Boolean = false,
        val reduceMotion: Boolean = false,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val fold: FoldableLayoutInfo? = null,
    )

    private fun book(
        left: Int = 490,
        right: Int = 510,
    ) = FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                FoldableHingeOrientation.Vertical,
                FoldableHingeState.HalfOpened,
                FoldableOcclusionType.Full,
                FoldableHingeBounds(left.dp, 0.dp, right.dp, 800.dp, (right - left).dp, 800.dp),
                true,
            ),
    )

    private fun tabletop() =
        FoldableLayoutInfo(
            isFoldable = true,
            posture = FoldablePosture.Tabletop,
            hinge =
                FoldableHingeInfo(
                    FoldableHingeOrientation.Horizontal,
                    FoldableHingeState.HalfOpened,
                    FoldableOcclusionType.Full,
                    FoldableHingeBounds(0.dp, 440.dp, 800.dp, 460.dp, 800.dp, 20.dp),
                    true,
                ),
        )
}
