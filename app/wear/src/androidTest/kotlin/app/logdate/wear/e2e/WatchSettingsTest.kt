package app.logdate.wear.e2e

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.AppScaffold
import app.logdate.wear.v2.haptics.HapticsPreference
import app.logdate.wear.v2.settings.WatchSettingsActions
import app.logdate.wear.v2.settings.WatchSettingsScreen
import app.logdate.wear.v2.settings.WatchSettingsScreenTags
import app.logdate.wear.v2.settings.WatchSettingsTags
import app.logdate.wear.v2.settings.WatchSettingsUiState
import app.logdate.wear.v2.settings.WatchStatus
import app.logdate.wear.v2.theme.LogDateWearTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Taps through Watch settings on a Wear OS emulator, scrolling with the crown to reach each control. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WatchSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun healthy_sync_shows_no_status_card() {
        show(WatchSettingsUiState())

        assertTrue(composeRule.onAllNodesWithTag(WatchSettingsTags.STATUS).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun waiting_moments_show_how_many() {
        show(WatchSettingsUiState(status = WatchStatus.WaitingForPhone(3)))

        composeRule.onNodeWithText("3 moments waiting for your phone").assertIsDisplayed()
    }

    @Test
    fun a_failing_send_retries_on_tap() {
        var retries = 0
        show(WatchSettingsUiState(status = WatchStatus.SendFailing(2)), WatchSettingsActions(onRetrySync = { retries++ }))

        composeRule.onNodeWithTag(WatchSettingsTags.STATUS).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun a_watch_that_needs_a_link_says_to_set_it_up_from_the_phone() {
        show(WatchSettingsUiState(status = WatchStatus.NeedsDeviceLink))

        composeRule.onNodeWithText("Finish setup on phone").assertIsDisplayed()
        composeRule.onNodeWithText("Bring your phone nearby so this watch can sync").assertIsDisplayed()
    }

    @Test
    fun a_watch_that_needs_a_link_opens_the_phone_on_tap() {
        var opened = 0
        show(
            WatchSettingsUiState(status = WatchStatus.NeedsDeviceLink, phoneSettingsNodeId = "phone-node"),
            WatchSettingsActions(onOpenPhoneSettings = { opened++ }),
        )

        composeRule.onNodeWithText("Finish setup on phone").assertIsDisplayed()
        composeRule.onNodeWithTag(WatchSettingsTags.STATUS).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun a_failing_backup_says_so_and_retries_on_tap() {
        var retries = 0
        show(WatchSettingsUiState(status = WatchStatus.BackupFailing(1)), WatchSettingsActions(onRetrySync = { retries++ }))

        composeRule.onNodeWithText("Some moments couldn’t back up").assertIsDisplayed()
        composeRule.onNodeWithTag(WatchSettingsTags.STATUS).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun choosing_essential_haptics_reports_it() {
        val chosen = mutableListOf<HapticsPreference>()
        show(WatchSettingsUiState(), WatchSettingsActions(onHaptics = { chosen += it }))

        clickWhenShown(WatchSettingsTags.HAPTICS_ESSENTIAL)

        assertEquals(listOf(HapticsPreference.ESSENTIAL), chosen)
    }

    @Test
    fun the_places_switch_reports_the_new_value() {
        val values = mutableListOf<Boolean>()
        show(WatchSettingsUiState(places = false), WatchSettingsActions(onPlaces = { values += it }))

        clickWhenShown(WatchSettingsTags.PLACES)

        assertEquals(listOf(true), values)
    }

    @Test
    fun more_on_your_phone_appears_only_when_offered() {
        var opens = 0
        show(WatchSettingsUiState(phoneSettingsNodeId = "phone-node"), WatchSettingsActions(onOpenPhoneSettings = { opens++ }))

        clickWhenShown(WatchSettingsTags.MORE_ON_PHONE)

        assertEquals(1, opens)
    }

    @Test
    fun the_reminders_switch_reports_the_new_value() {
        val values = mutableListOf<Boolean>()
        show(WatchSettingsUiState(), WatchSettingsActions(onReminders = { values += it }))

        clickWhenShown(WatchSettingsTags.REMINDERS)

        assertEquals(listOf(true), values)
    }

    private fun show(
        state: WatchSettingsUiState,
        actions: WatchSettingsActions = WatchSettingsActions(),
    ) {
        composeRule.setContent { SettingsScene(state, actions) }
    }

    private fun clickWhenShown(tag: String) {
        repeat(MAX_SCROLLS) {
            val viewport = composeRule.onNodeWithTag(SETTINGS_LIST).fetchSemanticsNode().boundsInRoot
            val node = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            val center = node?.boundsInRoot?.center?.y
            if (center != null &&
                composeRule.onNodeWithTag(tag).isDisplayed() &&
                center in (viewport.top + viewport.height * VISIBLE_MARGIN)..(viewport.bottom - viewport.height * VISIBLE_MARGIN)
            ) {
                captureBeforeClick(tag)
                composeRule.onNodeWithTag(tag).performClick()
                composeRule.waitForIdle()
                return
            }
            composeRule.onNodeWithTag(SETTINGS_LIST).performRotaryScrollInput { rotateToScrollVertically(SCROLL_STEP) }
            composeRule.waitForIdle()
        }
        error("$tag never appeared in the settings list")
    }

    private fun captureBeforeClick(tag: String) {
        val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val directory = File(output).apply { mkdirs() }
        File(directory, "$tag.txt").writeText(composeRule.onNodeWithTag(tag).printToString())
        File(directory, "$tag.png").outputStream().use { stream ->
            composeRule
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    companion object {
        private const val VISIBLE_MARGIN = 0.15f
        private const val MAX_SCROLLS = 20
        private const val SCROLL_STEP = 48f
    }
}

private const val SETTINGS_LIST = WatchSettingsScreenTags.LIST

@Composable
private fun SettingsScene(
    state: WatchSettingsUiState,
    actions: WatchSettingsActions,
) {
    LogDateWearTheme(dynamicColor = false) {
        AppScaffold(timeText = {}) {
            WatchSettingsScreen(state, actions)
        }
    }
}
