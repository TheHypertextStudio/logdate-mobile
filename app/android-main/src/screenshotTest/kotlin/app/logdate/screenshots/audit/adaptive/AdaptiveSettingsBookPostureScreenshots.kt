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

private const val BOOK_FOLDABLE = "spec:width=1440dp,height=900dp"

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

@PreviewTest
@Preview(name = "Settings overview book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A09_SettingsOverviewBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.SettingsOverview)
}

@PreviewTest
@Preview(name = "Account settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A10_AccountSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.AccountSettings)
}

@PreviewTest
@Preview(name = "Privacy settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A11_PrivacySettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.PrivacySettings)
}

@PreviewTest
@Preview(name = "Data settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A12_DataSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.DataSettings)
}

@PreviewTest
@Preview(name = "Memories settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A13_MemoriesSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.MemoriesSettings)
}

@PreviewTest
@Preview(name = "Devices settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A14_DevicesSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.DevicesSettings)
}

@PreviewTest
@Preview(name = "Rewind settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A15_RewindSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.RewindSettings)
}

@PreviewTest
@Preview(name = "Watch settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A16_WatchSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.WatchSettings)
}

@PreviewTest
@Preview(name = "Watch notification settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A18_WatchNotificationSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.WatchNotificationSettings)
}

@PreviewTest
@Preview(name = "Watch troubleshooting book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A19_WatchTroubleshootingBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.WatchTroubleshooting)
}

@PreviewTest
@Preview(name = "Streak settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A20_StreakSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.CampfireStreakBurning)
}

@PreviewTest
@Preview(name = "Timeline settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A21_TimelineSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.TimelineSettings)
}

@PreviewTest
@Preview(name = "Day boundary settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A22_DayBoundarySettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.DayBoundarySettings)
}

@PreviewTest
@Preview(name = "Library settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A23_LibrarySettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.LibrarySettings)
}

@PreviewTest
@Preview(name = "Recommendation settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A24_RecommendationSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.RecommendationSettings)
}

@PreviewTest
@Preview(name = "Birthday settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A25_BirthdaySettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.BirthdaySettings)
}

@PreviewTest
@Preview(name = "Advanced settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A26_AdvancedSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.AdvancedSettings)
}

@PreviewTest
@Preview(name = "Voice notes settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A27_VoiceNotesSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.VoiceNotesSettings)
}

@PreviewTest
@Preview(name = "Sync settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A28_SyncSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.SyncSettings)
}

@PreviewTest
@Preview(name = "Location settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A30_LocationSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.LocationSettings)
}

@PreviewTest
@Preview(name = "Location tracking options book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A31_LocationTrackingOptionsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.LocationTrackingOptions)
}

@PreviewTest
@Preview(name = "Location interval book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A32_LocationIntervalBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.LocationInterval)
}

@PreviewTest
@Preview(name = "Developer tools book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A33_DeveloperToolsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.DeveloperTools)
}

@PreviewTest
@Preview(name = "Events settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A36_EventsSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.EventsSettings)
}

@PreviewTest
@Preview(name = "Calendar sync settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A37_CalendarSyncSettingsBookPosture() {
    BookPostureSettingsScene(SharedScreenshotSceneId.CalendarSyncSettings)
}

@Composable
private fun BookPostureSettingsScene(sceneId: SharedScreenshotSceneId) {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            SharedScreenshotScene(sceneId)
        }
    }
}
