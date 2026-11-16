package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LocationHistoryBrowsingVisibilityTest {
    @Test
    fun phoneHidesUnavailableBrowsingWithoutHidingRecordingControls() = checkBrowsing(book = false)

    @Test
    fun bookHidesUnavailableBrowsingWithoutHidingRecordingControls() = checkBrowsing(book = true)

    private fun checkBrowsing(book: Boolean) =
        runDesktopComposeUiTest(width = if (book) 1000 else 411, height = 1000) {
            var opens = 0
            var recordingRequest: Boolean? = null
            val browse = mutableStateOf<(() -> Unit)?>(null)
            val posture =
                if (book) {
                    FoldableLayoutInfo(
                        isFoldable = true,
                        posture = FoldablePosture.Book,
                        hinge =
                            FoldableHingeInfo(
                                orientation = FoldableHingeOrientation.Vertical,
                                state = FoldableHingeState.HalfOpened,
                                occlusionType = FoldableOcclusionType.Full,
                                bounds = FoldableHingeBounds(490.dp, 0.dp, 510.dp, 1000.dp, 20.dp, 1000.dp),
                                isSeparating = true,
                            ),
                    )
                } else {
                    FoldableLayoutInfo()
                }
            setContent {
                provideFoldableLayoutInfo(posture) {
                    LogDateTheme(dynamicColor = false) {
                        LocationSettingsContent(
                            settings = LocationTrackingSettings(),
                            onBack = {},
                            onToggleBackgroundTracking = { recordingRequest = it },
                            onSetCaptureMode = {},
                            onShowLocationTimeline = browse.value,
                            onNavigateToTrackingOptions = {},
                            onNavigateToInterval = {},
                        )
                    }
                }
            }

            onNodeWithText("View Places").assertDoesNotExist()
            onNodeWithText("Enable Background Location Tracking").assertIsDisplayed().performClick()
            assertEquals(true, recordingRequest)
            onNodeWithText("Location data is stored on your device").assertIsDisplayed()

            runOnIdle { browse.value = { opens++ } }
            onNodeWithText("View Places").assertIsDisplayed().performClick()
            assertEquals(1, opens)

            runOnIdle { browse.value = null }
            onNodeWithText("View Places").assertDoesNotExist()
            onNodeWithText("Enable Background Location Tracking").assertIsDisplayed()
        }
}
