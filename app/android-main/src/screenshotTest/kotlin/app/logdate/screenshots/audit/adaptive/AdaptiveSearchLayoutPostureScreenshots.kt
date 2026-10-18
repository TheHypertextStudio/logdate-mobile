package app.logdate.screenshots.audit.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

private const val FOLDABLE = "spec:width=1440dp,height=900dp"

private val searchBookLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = FoldableHingeBounds(708.dp, 0.dp, 732.dp, 900.dp, 24.dp, 900.dp),
                isSeparating = true,
            ),
    )

private val searchTabletopLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Tabletop,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Horizontal,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = FoldableHingeBounds(0.dp, 438.dp, 1440.dp, 462.dp, 1440.dp, 24.dp),
                isSeparating = true,
            ),
    )

@PreviewTest
@Preview(name = "Adaptive search results book posture", showBackground = true, device = FOLDABLE)
@Composable
fun A34_AdaptiveSearchLayoutBookPosture() {
    PostureSearchLayoutScene(searchBookLayoutInfo)
}

@PreviewTest
@Preview(name = "Adaptive search results tabletop posture", showBackground = true, device = FOLDABLE)
@Composable
fun A35_AdaptiveSearchLayoutTabletopPosture() {
    PostureSearchLayoutScene(searchTabletopLayoutInfo)
}

@Composable
private fun PostureSearchLayoutScene(layoutInfo: FoldableLayoutInfo) {
    provideFoldableLayoutInfo(layoutInfo) {
        ScreenshotTheme {
            SharedScreenshotScene(SharedScreenshotSceneId.SearchAdaptiveLayout)
        }
    }
}
