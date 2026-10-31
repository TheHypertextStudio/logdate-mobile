package app.logdate.screenshots.audit.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.screenshots.common.PostureRouteFrame
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.SharedScreenshotScene
import app.logdate.screenshots.shared.SharedScreenshotSceneId
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import com.android.tools.screenshot.PreviewTest

private const val BOOK_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val TABLETOP_FOLDABLE = "spec:width=1440dp,height=900dp"

private val bookPostureLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 708.dp,
                        top = 0.dp,
                        right = 732.dp,
                        bottom = 900.dp,
                        width = 24.dp,
                        height = 900.dp,
                    ),
                isSeparating = true,
            ),
    )

private val tabletopPostureLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Tabletop,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Horizontal,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 0.dp,
                        top = 438.dp,
                        right = 1440.dp,
                        bottom = 462.dp,
                        width = 1440.dp,
                        height = 24.dp,
                    ),
                isSeparating = true,
            ),
    )

@PreviewTest
@Preview(name = "Rewind overview book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A34_RewindOverviewBookPosture() {
    PostureRouteFrame(bookPostureLayoutInfo) {
        SharedScreenshotScene(SharedScreenshotSceneId.RewindOverviewCanonical)
    }
}

@PreviewTest
@Preview(name = "Past rewinds book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A35_PastRewindsBookPosture() {
    BookPostureRewindScene(SharedScreenshotSceneId.PastRewinds)
}

@PreviewTest
@Preview(name = "Rewind detail book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A38_RewindDetailBookPosture() {
    FoldableRewindScene(
        sceneId = SharedScreenshotSceneId.RewindDetailPopulated,
        foldableLayoutInfo = bookPostureLayoutInfo,
    )
}

@PreviewTest
@Preview(name = "Rewind detail tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A39_RewindDetailTabletopPosture() {
    FoldableRewindScene(
        sceneId = SharedScreenshotSceneId.RewindDetailPopulated,
        foldableLayoutInfo = tabletopPostureLayoutInfo,
    )
}

@Composable
private fun BookPostureRewindScene(sceneId: SharedScreenshotSceneId) {
    FoldableRewindScene(
        sceneId = sceneId,
        foldableLayoutInfo = bookPostureLayoutInfo,
    )
}

@Composable
private fun FoldableRewindScene(
    sceneId: SharedScreenshotSceneId,
    foldableLayoutInfo: FoldableLayoutInfo,
) {
    provideFoldableLayoutInfo(foldableLayoutInfo) {
        ScreenshotTheme {
            SharedScreenshotScene(sceneId)
        }
    }
}
