package app.logdate.client.e2e

import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.media.device.MediaDeviceUiState
import app.logdate.client.text.toUiText
import app.logdate.feature.editor.ui.video.VideoPlayerTags
import app.logdate.feature.library.ui.detail.MediaDetailContent
import app.logdate.feature.library.ui.detail.MediaDetailUiState
import app.logdate.feature.library.ui.detail.MediaViewerItem
import app.logdate.feature.library.ui.detail.PresenterState
import app.logdate.ui.media.MediaDeviceSelectorTags
import app.logdate.ui.theme.LogDateTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Clock
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class LibraryMediaDetailVideoE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<VideoPlaybackHostActivity>()

    @Test
    fun `compact video detail shows presenter output route controls`() {
        assumeTrue(isCompactManagedDevice())
        var stopPresentingCount = 0
        setVideoDetailContent(onStopPresenting = { stopPresentingCount += 1 })

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(VideoPlayerTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithText("External display audio").assertIsDisplayed()
        composeRule
            .onAllNodesWithTag(MediaDeviceSelectorTags.chip("Audio output"))[0]
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText("Open output switcher").assertIsDisplayed()
        composeRule
            .onNodeWithTag(MediaDeviceSelectorTags.systemSettingsButton("Audio output"))
            .assertIsDisplayed()
            .assertHasClickAction()

        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.onAllNodesWithText("Stop Presenting")[0].assertIsDisplayed().performClick()

        assertEquals(1, stopPresentingCount)
    }

    @Test
    fun `expanded video detail shows presenter output route controls beside metadata`() {
        assumeTrue(isLargeScreenManagedDevice())
        var stopPresentingCount = 0
        setVideoDetailContent(onStopPresenting = { stopPresentingCount += 1 })

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(VideoPlayerTags.ROOT).assertIsDisplayed()
        composeRule.onNodeWithText("External display audio").assertIsDisplayed()
        composeRule
            .onAllNodesWithTag(MediaDeviceSelectorTags.chip("Audio output"))[0]
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.onNodeWithText("Open output switcher").assertIsDisplayed()
        composeRule
            .onNodeWithTag(MediaDeviceSelectorTags.systemSettingsButton("Audio output"))
            .assertIsDisplayed()
            .assertHasClickAction()

        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.onAllNodesWithContentDescription("Stop presenting")[0].assertIsDisplayed().performClick()

        assertEquals(1, stopPresentingCount)
    }

    private fun setVideoDetailContent(onStopPresenting: () -> Unit) {
        val videoUri = emptyVideoFileUri()
        val mediaItem =
            MediaViewerItem(
                uid = Uuid.random(),
                uri = videoUri,
                isVideo = true,
            )

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    MediaDetailContent(
                        state =
                            MediaDetailUiState.VideoContent(
                                mediaId = mediaItem.uid,
                                mediaRef = videoUri,
                                createdAt = Clock.System.now(),
                                location = null,
                            ),
                        presenterState =
                            PresenterState(
                                isExternalDisplayAvailable = true,
                                isPresenting = true,
                                currentIndex = 0,
                                totalItems = 1,
                                mediaItems = listOf(mediaItem),
                            ),
                        outputSelection = externalDisplayOutputSelection(),
                        onBack = {},
                        onStopPresenting = onStopPresenting,
                    )
                }
            }
        }
    }

    private fun externalDisplayOutputSelection(): MediaDeviceSelectionUiState =
        MediaDeviceSelectionUiState(
            kind = MediaDeviceKind.AUDIO_OUTPUT,
            devices =
                listOf(
                    MediaDeviceUiState(
                        id = "phone-speaker",
                        label = "Phone speaker".toUiText(),
                        kind = MediaDeviceKind.AUDIO_OUTPUT,
                        category = MediaDeviceCategory.BUILT_IN,
                    ),
                    MediaDeviceUiState(
                        id = "bluetooth-headphones",
                        label = "Bluetooth headphones".toUiText(),
                        kind = MediaDeviceKind.AUDIO_OUTPUT,
                        category = MediaDeviceCategory.BLUETOOTH,
                        isExternal = true,
                    ),
                ),
            selectedDeviceId = "phone-speaker",
            isSelectionControllable = false,
            routeControlMessage = "Use Android's output switcher to route playback.".toUiText(),
        )

    private fun emptyVideoFileUri(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "library-media-detail-video.mp4")
        if (!file.exists()) {
            file.writeBytes(ByteArray(0))
        }
        return Uri.fromFile(file).toString()
    }

    private fun isCompactManagedDevice(): Boolean = screenWidthDp() < 600

    private fun isLargeScreenManagedDevice(): Boolean = screenWidthDp() >= 600

    private fun screenWidthDp(): Int =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .resources
            .configuration
            .screenWidthDp
}
