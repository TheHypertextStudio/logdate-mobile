package app.logdate.wear.screenshots

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import app.logdate.wear.v2.haptics.HapticsPreference
import app.logdate.wear.v2.settings.RemindersSetting
import app.logdate.wear.v2.settings.WatchSettingsActions
import app.logdate.wear.v2.settings.WatchSettingsUiState
import app.logdate.wear.v2.settings.WatchStatus
import app.logdate.wear.v2.settings.watchSettingsItems
import app.logdate.wear.v2.theme.LogDateWearTheme
import com.android.tools.screenshot.PreviewTest

/** Every state of the settings section, on the graphite theme. */
class WatchSettingsScreenshots {
    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S01_SettingsHealthy() {
        SettingsScene(WatchSettingsUiState(places = true))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S02_SettingsWaitingForPhone() {
        SettingsScene(WatchSettingsUiState(status = WatchStatus.WaitingForPhone(3)))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S03_SettingsSendFailing() {
        SettingsScene(WatchSettingsUiState(status = WatchStatus.SendFailing(2), haptics = HapticsPreference.ESSENTIAL))
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S04_SettingsMoreOnPhone() {
        SettingsScene(WatchSettingsUiState(places = true, phoneSettingsNodeId = "phone-node"), anchorItem = 5)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S05_SettingsEssentialAndPlaces() {
        SettingsScene(WatchSettingsUiState(places = true, haptics = HapticsPreference.ESSENTIAL), anchorItem = 2)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S06_SettingsRemindersOn() {
        SettingsScene(WatchSettingsUiState(reminders = RemindersSetting(enabled = true, afterWorkouts = true)), anchorItem = 4)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S07_SettingsRemindersOff() {
        SettingsScene(WatchSettingsUiState(), anchorItem = 4)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S08_SettingsRemindersEveningOnly() {
        SettingsScene(WatchSettingsUiState(reminders = RemindersSetting(enabled = true)), anchorItem = 4)
    }

    @PreviewTest
    @WearScreenshotPreviewMatrix
    @WearLargeFontPreview
    @Composable
    fun S09_SettingsNeedsPhoneToLink() {
        SettingsScene(WatchSettingsUiState(status = WatchStatus.NeedsDeviceLink))
    }
}

@Composable
private fun SettingsScene(
    state: WatchSettingsUiState,
    anchorItem: Int = 0,
) {
    LogDateWearTheme(dynamicColor = false) {
        AppScaffold(timeText = {}) {
            val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = anchorItem)
            val spec = rememberTransformationSpec()
            ScreenScaffold(scrollState = listState, timeText = {}) { contentPadding ->
                TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                    watchSettingsItems(state, WatchSettingsActions(), spec)
                }
            }
        }
    }
}
