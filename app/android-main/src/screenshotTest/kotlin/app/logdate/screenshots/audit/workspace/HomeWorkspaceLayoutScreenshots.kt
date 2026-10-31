@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package app.logdate.screenshots.audit.workspace

import androidx.compose.material.icons.filled.Book
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.logdate.client.text.toUiText
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryActions
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent
import app.logdate.feature.timeline.ui.details.TimelineDayDetailPanel
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.components.home_timeline.HistoryMapFixture
import app.logdate.screenshots.components.home_timeline.historyDay
import app.logdate.screenshots.flows.flow02_home_timeline.timelineDetailState
import app.logdate.ui.workspace.WorkspaceBottomEdge
import app.logdate.ui.workspace.WorkspaceScaffold
import com.android.tools.screenshot.PreviewTest

private fun workspaceFold(book: Boolean): app.logdate.ui.foldable.FoldableLayoutInfo {
    val bounds =
        if (book) {
            app.logdate.ui.foldable
                .FoldableHingeBounds(630.dp, 0.dp, 650.dp, 800.dp, 20.dp, 800.dp)
        } else {
            app.logdate.ui.foldable
                .FoldableHingeBounds(0.dp, 630.dp, 800.dp, 650.dp, 800.dp, 20.dp)
        }
    return app.logdate.ui.foldable.FoldableLayoutInfo(
        true,
        if (book) app.logdate.ui.foldable.FoldablePosture.Book else app.logdate.ui.foldable.FoldablePosture.Tabletop,
        app.logdate.ui.foldable.FoldableHingeInfo(
            if (book) {
                app.logdate.ui.foldable.FoldableHingeOrientation.Vertical
            } else {
                app.logdate.ui.foldable.FoldableHingeOrientation.Horizontal
            },
            app.logdate.ui.foldable.FoldableHingeState.HalfOpened,
            app.logdate.ui.foldable.FoldableOcclusionType.Full,
            bounds,
            true,
        ),
    )
}

@PreviewTest
@Preview(name = "Book", device = ScreenshotTestData.TABLET, showBackground = true)
@Composable
fun WorkspaceBook() {
    app.logdate.ui.foldable.provideFoldableLayoutInfo(workspaceFold(true)) {
        // One rendered frame cannot measure where the panels sit, so the settled position is given.
        CompositionLocalProvider(app.logdate.ui.foldable.LocalWindowOriginOverride provides DpOffset(96.dp, 88.dp)) {
            WorkspaceScene("timeline", detail = { TimelineDayDetailPanel(timelineDetailState, {}) }) { TimelineFixture() }
        }
    }
}

@PreviewTest
@Preview(name = "Tabletop", device = ScreenshotTestData.TABLET_PORTRAIT, showBackground = true)
@Composable
fun WorkspaceTabletop() {
    app.logdate.ui.foldable.provideFoldableLayoutInfo(workspaceFold(false)) {
        CompositionLocalProvider(app.logdate.ui.foldable.LocalWindowOriginOverride provides DpOffset(0.dp, 88.dp)) {
            WorkspaceScene("locations") {
                HumanLocationHistoryContent(
                    historyDay,
                    HumanLocationHistoryActions(),
                    toolbarActions = { PlacesOptionsFixture() },
                    mapContent = { HistoryMapFixture(it) },
                )
            }
        }
    }
}

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspacePlaces() =
    WorkspaceScene("locations") {
        HumanLocationHistoryContent(
            historyDay.copy(tab = app.logdate.feature.location.timeline.ui.history.HistoryTab.Places),
            HumanLocationHistoryActions(),
            toolbarActions = { PlacesOptionsFixture() },
            initialSupportingExtent = app.logdate.ui.workspace.SupportingExtent.Browsing,
            mapContent = {
                HistoryMapFixture(it)
            },
        )
    }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceRewindPlayback() = ImmersiveRewindScreenshot()

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceLocationsBrowsing() =
    WorkspaceScene("locations") {
        HumanLocationHistoryContent(
            historyDay,
            HumanLocationHistoryActions(),
            initialSupportingExtent = app.logdate.ui.workspace.SupportingExtent.Browsing,
            toolbarActions = { PlacesOptionsFixture() },
            mapContent = { HistoryMapFixture(it) },
        )
    }

@PreviewTest
@Preview(name = "Reduced motion", device = ScreenshotTestData.PHONE, showBackground = true)
@Composable
fun WorkspaceReducedMotion() {
    CompositionLocalProvider(app.logdate.ui.platform.LocalReduceMotionOverride provides true) {
        WorkspaceScene("locations") {
            HumanLocationHistoryContent(
                historyDay,
                HumanLocationHistoryActions(),
                toolbarActions = { PlacesOptionsFixture() },
                mapContent = { HistoryMapFixture(it) },
            )
        }
    }
}

@PreviewTest
@Preview(name = "Phone", device = ScreenshotTestData.PHONE, showBackground = true)
@Preview(name = "Landscape", device = ScreenshotTestData.PHONE_LANDSCAPE, showBackground = true)
@Preview(name = "Large text", device = ScreenshotTestData.COMPACT_PHONE, fontScale = 2f, showBackground = true)
@Composable
fun WorkspaceNavigationScrolledToBottom() =
    ScreenshotTheme {
        WorkspaceScaffold(
            workspaceDestinations,
            "locations",
            {},
            onSearch = {},
            bottomEdge = WorkspaceBottomEdge(initiallyReached = true),
        ) {
            HumanLocationHistoryContent(
                historyDay,
                HumanLocationHistoryActions(),
                toolbarActions = { PlacesOptionsFixture() },
                mapContent = { HistoryMapFixture(it) },
            )
        }
    }

@PreviewTest
@Preview(name = "Sync recovery", device = ScreenshotTestData.COMPACT_PHONE, fontScale = 2f, showBackground = true)
@Composable
fun WorkspaceSyncRecovery() =
    ScreenshotTheme {
        WorkspaceScaffold(workspaceDestinations, "locations", {}, onSearch = {}, actions = {
            app.logdate.feature.core.main.HomeWorkspaceAccountAction(
                app.logdate.feature.core.sync.SyncPresentation.NeedsRecovery,
                null,
                {},
                {},
                {},
            )
        }) {
            HumanLocationHistoryContent(
                historyDay.copy(
                    recoveryMessage = "Recording was interrupted".toUiText(),
                    recoveryActionLabel = "Resume recording".toUiText(),
                ),
                HumanLocationHistoryActions(),
                toolbarActions = { PlacesOptionsFixture() },
                mapContent = {
                    HistoryMapFixture(it)
                },
            )
        }
    }

@PreviewTest @DesktopWindowPreviewMatrix @Composable
fun WorkspaceTimelineInDesktopWindow() = WorkspaceScene("timeline", inDesktopWindow = true) { TimelineFixture() }

@PreviewTest @DesktopWindowPreviewMatrix @Composable
fun WorkspaceLocationsInDesktopWindow() =
    WorkspaceScene("locations", inDesktopWindow = true) {
        HumanLocationHistoryContent(historyDay, HumanLocationHistoryActions(), toolbarActions = {
            PlacesOptionsFixture()
        }, mapContent = { HistoryMapFixture(it) })
    }
