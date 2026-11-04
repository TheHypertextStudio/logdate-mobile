@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package app.logdate.screenshots.audit.workspace

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.logdate.client.text.toUiText
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import app.logdate.feature.rewind.ui.AudioNoteRewindPanelUiState
import app.logdate.feature.rewind.ui.ImageRewindPanelUiState
import app.logdate.feature.rewind.ui.RewindDetailUiState
import app.logdate.feature.rewind.ui.RewindPanelUiState
import app.logdate.feature.rewind.ui.SubtitledRewindPanelUiState
import app.logdate.feature.rewind.ui.TextNoteRewindPanelUiState
import app.logdate.feature.rewind.ui.detail.LocalRewindPreviewPainters
import app.logdate.feature.rewind.ui.detail.RewindDetailScreenContent
import app.logdate.navigation.rememberWorkspaceRouteDecorator
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.platform.LocalReduceMotionOverride
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val rewindId = Uuid.parse("00000000-0000-0000-0000-000000000020")
private val sampleTime = Instant.parse("2026-09-24T18:00:00Z")
private const val PHOTO_URI = "android.resource://studio.hypertext.logdate.debug/drawable/workspace_sample_photo"
private val cover = SubtitledRewindPanelUiState("A few quiet moments", "September 21–27, 2026".toUiText(), backgroundUri = PHOTO_URI)
private val photo =
    ImageRewindPanelUiState(rewindId, sampleTime, PHOTO_URI, "An afternoon outside", "Thursday, September 24".toUiText())

@PreviewTest @WorkspacePreviewMatrix @Composable
fun ImmersiveRewindPhoto() = ImmersiveRewindScreenshot(photo)

@PreviewTest @WorkspacePreviewMatrix @Composable
fun ImmersiveRewindVideoFrame() = ImmersiveRewindScreenshot(photo.copy(isVideoFrame = true))

@PreviewTest @WorkspacePreviewMatrix @Composable
fun ImmersiveRewindText() =
    ImmersiveRewindScreenshot(
        TextNoteRewindPanelUiState(
            rewindId,
            sampleTime,
            "I took the long way home today. The afternoon light made everything feel a little slower, and I was glad I had time to notice it.",
            "Thursday, September 24".toUiText(),
        ),
    )

@PreviewTest @WorkspacePreviewMatrix @Composable
fun ImmersiveRewindAudio() =
    ImmersiveRewindScreenshot(
        AudioNoteRewindPanelUiState(
            rewindId,
            sampleTime,
            "fixture://afternoon-recording",
            42000L,
            "A few thoughts from the walk home. I want to remember how peaceful this afternoon felt.",
            "Thursday, September 24".toUiText(),
            listOf(0.2f, 0.5f, 0.8f, 0.4f, 0.6f, 0.9f, 0.3f, 0.7f),
        ),
    )

@PreviewTest
@Preview(name = "Book", device = "spec:width=1200dp,height=800dp", showBackground = true)
@Composable
fun ImmersiveRewindBook() =
    ImmersiveRewindScreenshot(
        photo,
        foldable = playbackFold(FoldableHingeOrientation.Vertical, FoldableHingeBounds(590.dp, 0.dp, 610.dp, 800.dp, 20.dp, 800.dp)),
    )

@PreviewTest
@Preview(name = "Narrow book", device = "spec:width=420dp,height=900dp", showBackground = true)
@Composable
fun ImmersiveRewindNarrowBook() =
    ImmersiveRewindScreenshot(
        photo,
        foldable = playbackFold(FoldableHingeOrientation.Vertical, FoldableHingeBounds(200.dp, 0.dp, 220.dp, 900.dp, 20.dp, 900.dp)),
    )

@PreviewTest
@Preview(name = "Mobile book", device = "spec:width=674dp,height=900dp", showBackground = true)
@Composable
fun ImmersiveRewindMobileBook() =
    ImmersiveRewindScreenshot(
        photo,
        foldable = playbackFold(FoldableHingeOrientation.Vertical, FoldableHingeBounds(327.dp, 0.dp, 347.dp, 900.dp, 20.dp, 900.dp)),
    )

@PreviewTest
@Preview(name = "Wide mobile book", device = "spec:width=840dp,height=900dp", showBackground = true)
@Composable
fun ImmersiveRewindWideMobileBook() =
    ImmersiveRewindScreenshot(
        photo,
        foldable = playbackFold(FoldableHingeOrientation.Vertical, FoldableHingeBounds(410.dp, 0.dp, 430.dp, 900.dp, 20.dp, 900.dp)),
    )

@PreviewTest
@Preview(name = "Tabletop", device = "spec:width=1000dp,height=900dp", showBackground = true)
@Composable
fun ImmersiveRewindTabletop() =
    ImmersiveRewindScreenshot(
        photo,
        foldable = playbackFold(FoldableHingeOrientation.Horizontal, FoldableHingeBounds(0.dp, 440.dp, 1000.dp, 460.dp, 1000.dp, 20.dp)),
    )

@PreviewTest
@Preview(name = "Reduced motion", device = ScreenshotTestData.PHONE, showBackground = true)
@Composable
fun ImmersiveRewindReducedMotion() = ImmersiveRewindScreenshot(photo, reducedMotion = true)

@Composable
internal fun ImmersiveRewindScreenshot(
    panel: RewindPanelUiState = cover,
    foldable: FoldableLayoutInfo = FoldableLayoutInfo(),
    reducedMotion: Boolean = false,
) {
    ScreenshotTheme {
        val context = LocalContext.current
        val bitmap =
            remember(context) {
                BitmapFactory.decodeResource(context.resources, app.logdate.client.R.drawable.workspace_sample_photo)
            }
        val image = remember(bitmap) { bitmap.asImage() }
        val handler = remember(image) { AsyncImagePreviewHandler { image } }
        val previewPainters = remember(bitmap) { mapOf(PHOTO_URI to BitmapPainter(bitmap.asImageBitmap())) }
        CompositionLocalProvider(
            LocalWorkspaceEnabled provides true,
            LocalWorkspaceSearchAction provides {},
            LocalReduceMotionOverride provides reducedMotion,
            LocalAsyncImagePreviewHandler provides handler,
            LocalRewindPreviewPainters provides previewPainters,
        ) {
            provideFoldableLayoutInfo(foldable) {
                NavDisplay(
                    backStack = listOf<NavKey>(RewindDetailRoute(rewindId)),
                    onBack = {},
                    entryDecorators = listOf(rememberWorkspaceRouteDecorator()),
                    entryProvider =
                        entryProvider {
                            taggedEntry<RewindDetailRoute> {
                                RewindDetailScreenContent(
                                    uiState = RewindDetailUiState.Success(listOf(panel, cover, photo)),
                                    onExitRewind = {},
                                    externalPause = true,
                                    onSharePanel = {},
                                    onShareRewindStats = {},
                                    onDeleteRewind = {},
                                )
                            }
                        },
                )
            }
        }
    }
}

private fun playbackFold(
    orientation: FoldableHingeOrientation,
    bounds: FoldableHingeBounds,
) = FoldableLayoutInfo(
    isFoldable = true,
    hinge = FoldableHingeInfo(orientation, FoldableHingeState.HalfOpened, FoldableOcclusionType.Full, bounds, true),
)
