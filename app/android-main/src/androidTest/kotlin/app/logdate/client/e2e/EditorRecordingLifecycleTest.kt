package app.logdate.client.e2e

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.media.audio.AndroidAudioNotificationHandler
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackStatusProvider
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.testing.onboarding.DEBUG_SKIP_ONBOARDING_EXTRA
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Runs only through Gradle Managed Devices; never targets an operator device or clears app data. */
@RunWith(AndroidJUnit4::class)
class EditorRecordingLifecycleTest {
    private val createEntry = hasText("Create Entry") or hasContentDescription("Create new entry") or hasContentDescription("Add a memory")

    @get:Rule val compose = createEmptyComposeRule()

    @get:Rule val microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun recordingSurvivesBackgroundRecreationAndNotificationReturnWithOneReachableTake() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launch =
            Intent(context, MainActivity::class.java).apply {
                action = "app.logdate.action.CAPTURE"
                putExtra(app.logdate.client.capture.EXTRA_CAPTURE_MODE, "voice")
                putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            val manager = KoinPlatform.getKoin().get<AudioRecordingManager>()
            compose.waitUntil(30_000) { manager.isRecording }
            val block = assertNotNull(manager.currentRecordingTargetNoteId)
            val draft = assertNotNull(manager.currentRecordingDraftId)
            val path = assertNotNull(manager.currentRecordingPath)
            compose.onNodeWithText("Finish").assertIsDisplayed()
            capture(context, "recording-started")

            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(manager.isRecording, "Backgrounding must keep the same foreground-service session")
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            compose.waitForIdle()
            assertEquals(block, manager.currentRecordingTargetNoteId)
            assertEquals(path, manager.currentRecordingPath)
            compose.onNodeWithText("Finish").assertIsDisplayed()
            capture(context, "recording-recreated")
            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent().setClassName(context, app.logdate.client.capture.INTERNAL_CAPTURE_ACTIVITY).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        action = app.logdate.client.capture.ACTION_CAPTURE
                        putExtra(app.logdate.client.capture.EXTRA_CAPTURE_MODE, "text")
                        putExtra(app.logdate.client.capture.EXTRA_CAPTURE_TEXT, "Retained scratch entry")
                    },
                )
            }
            compose.waitUntil(15_000) { compose.onNodeWithTag("editor_text_input").isDisplayed() }
            compose.onNodeWithText("Retained scratch entry").assertIsDisplayed()
            assertTrue(manager.isRecording)
            assertEquals(block, manager.currentRecordingTargetNoteId)
            capture(context, "second-editor-retained-during-recording")

            scenario.moveToState(Lifecycle.State.CREATED)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val notification = notifications.activeNotifications.single { it.id == AndroidAudioNotificationHandler.NOTIFICATION_ID }
            assertNotNull(notification.notification.contentIntent)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.openNotification()
            val notificationTitle = device.wait(Until.findObject(By.text("Recording audio")), 10_000)
            assertNotNull(notificationTitle, "The system shade must expose the recording notification")
            capture(context, "recording-notification-shade")
            notificationTitle.click()
            compose.waitUntil(15_000) { scenario.state == Lifecycle.State.RESUMED }
            compose.waitUntil(15_000) {
                manager.currentRecordingTargetNoteId == block && manager.isRecording
            }
            assertEquals(draft, manager.currentRecordingDraftId)
            compose.onNodeWithText("Finish").assertIsDisplayed().performClick()
            compose.waitUntil(30_000) { !manager.isRecording && !manager.isStartingRecording }
            compose.waitUntil(15_000) { compose.onNodeWithTag("audio_block_duration").isDisplayed() }
            if (compose.onNodeWithContentDescription("Return to entry").isDisplayed()) {
                compose.onNodeWithContentDescription("Return to entry").performClick()
            }
            compose.waitUntil(15_000) { compose.onNodeWithContentDescription("Save entry").isDisplayed() }
            capture(context, "recording-finished")
            assertTrue(File(path.removePrefix("file://")).length() > 0, "The captured audio must be retained")
            compose.onNodeWithContentDescription("Save entry").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.waitUntil(15_000) { compose.onNodeWithText("Retained scratch entry").isDisplayed() }
            if (compose.onNodeWithContentDescription("Return to entry").isDisplayed()) {
                compose.onNodeWithContentDescription("Return to entry").performClick()
            }
            compose.waitUntil(15_000) { compose.onNodeWithContentDescription("Save entry").isDisplayed() }
            compose.waitUntil(15_000) { compose.onNodeWithTag("audio_preview_ready").isDisplayed() }
            compose.onNodeWithText("Audio").assertDoesNotExist()
            compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertHasClickAction()
            val playback = KoinPlatform.getKoin().get<AudioPlaybackManager>() as AudioPlaybackStatusProvider
            compose.onNodeWithContentDescription("Play").performClick()
            compose.waitUntil(15_000) { playback.playbackStatus.value.isPlaying }
            compose.waitUntil(15_000) { compose.onNodeWithContentDescription("Pause").isDisplayed() }
            compose.onNodeWithContentDescription("Pause").assertHasClickAction().performClick()
            compose.waitUntil(15_000) { !playback.playbackStatus.value.isPlaying }
            capture(context, "second-editor-restored-after-recording")
            compose.onNodeWithContentDescription("Save entry").assertIsDisplayed().performClick()
            compose.waitForIdle()
            capture(context, "recording-saved")
            val preferences = KoinPlatform.getKoin().get<LogdatePreferencesDataSource>()
            val previousMode = runBlocking { preferences.observeEntryStartMode().first() }
            try {
                runBlocking { preferences.setEntryStartMode(EntryStartMode.TEXT) }
                repeat(3) { visit ->
                    compose.waitUntil(15_000) {
                        !compose.onNodeWithContentDescription("Save entry").isDisplayed() &&
                            !compose.onNodeWithContentDescription("Return to entry").isDisplayed() &&
                            compose.onAllNodes(createEntry and isEnabled()).onFirst().isDisplayed()
                    }
                    compose
                        .onAllNodes(createEntry and isEnabled())
                        .onFirst()
                        .assertIsDisplayed()
                        .performClick()
                    capture(context, "reopened-editor-opening-$visit")
                    compose.waitUntil(15_000) { compose.onNodeWithTag("editor_text_input").isDisplayed() }
                    compose.onNodeWithTag("editor_text_input").assertIsDisplayed()
                    capture(context, "reopened-editor-$visit")
                    repeat(4) {
                        if (!compose.onAllNodes(createEntry).onFirst().isDisplayed()) {
                            device.pressBack()
                            compose.waitForIdle()
                        }
                    }
                }
            } finally {
                runBlocking { preferences.setEntryStartMode(previousMode) }
            }
        }
    }

    @Test
    fun exitingRecordingToDraftStopsCaptureAndAddEntryCanOpenAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launch =
            Intent(context, MainActivity::class.java).apply {
                action = app.logdate.client.capture.ACTION_CAPTURE
                putExtra(app.logdate.client.capture.EXTRA_CAPTURE_MODE, "voice")
                putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ActivityScenario.launch<MainActivity>(launch).use {
            val manager = KoinPlatform.getKoin().get<AudioRecordingManager>()
            compose.waitUntil(30_000) { manager.isRecording }
            val block = assertNotNull(manager.currentRecordingTargetNoteId)
            val path = assertNotNull(manager.currentRecordingPath)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            compose.onNodeWithText("Finish").assertIsDisplayed()
            compose.waitForIdle()
            repeat(3) {
                if (!compose.onNodeWithText("Save Draft").isDisplayed()) {
                    device.pressBack()
                    compose.waitForIdle()
                }
            }
            compose.waitUntil(15_000) { compose.onNodeWithText("Save Draft").isDisplayed() }
            compose.onNodeWithText("Save Draft").assertIsDisplayed().performClick()
            compose.waitUntil(30_000) { !manager.isRecording && !manager.isStartingRecording }
            val drafts = KoinPlatform.getKoin().get<EntryDraftRepository>()
            compose.waitUntil(15_000) {
                runBlocking { drafts.getDrafts().first().any { draft -> draft.notes.any { it.uid == block && it is JournalNote.Audio } } }
            }
            assertTrue(File(path.removePrefix("file://")).length() > 0)
            capture(context, "recording-exit-retained")
            compose
                .onAllNodes(createEntry)
                .onFirst()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("editor_text_input").assertIsDisplayed()
            assertTrue(!manager.isRecording, "Reopening Add entry must not start another recording")
            capture(context, "recording-exit-reopened")
        }
    }

    @Test
    fun systemEdgeBackCancelDialogDismissAndDiscardAllowFreshAddEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val overlays = device.executeShellCommand("cmd overlay list").lineSequence()
        val previousNavigation =
            overlays
                .firstOrNull {
                    it.startsWith("[x]") && it.contains("com.android.internal.systemui.navbar.")
                }?.substringAfter("] ")
        device.executeShellCommand("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural")
        try {
            val text = "Synthetic entry for explicit discard"
            val launch =
                Intent().setClassName(context, app.logdate.client.capture.INTERNAL_CAPTURE_ACTIVITY).apply {
                    action = app.logdate.client.capture.ACTION_CAPTURE
                    putExtra(app.logdate.client.capture.EXTRA_CAPTURE_MODE, "text")
                    putExtra(app.logdate.client.capture.EXTRA_CAPTURE_TEXT, text)
                    putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            ActivityScenario.launch<MainActivity>(launch).use {
                compose.waitUntil(30_000) { compose.onNodeWithText(text).isDisplayed() }
                compose.waitForIdle()
                if (compose.onNodeWithContentDescription("Return to entry").isDisplayed()) {
                    compose.onNodeWithContentDescription("Return to entry").performClick()
                }
                compose.waitForIdle()
                capture(context, "edge-back-initial-entry")
                val y = device.displayHeight / 2
                check(device.swipe(arrayOf(Point(1, y), Point(device.displayWidth / 5, y), Point(1, y)), 40))
                compose.waitForIdle()
                compose.onNodeWithText(text).assertIsDisplayed()
                assertTrue(!compose.onNodeWithTag("exit_dialog_discard").isDisplayed(), "Cancelled edge back must keep editing")
                capture(context, "edge-back-cancel-retains-text")
                check(device.swipe(1, y, device.displayWidth / 2, y, 60))
                compose.waitUntil(10_000) { compose.onNodeWithTag("exit_dialog_discard").isDisplayed() }
                capture(context, "edge-back-commit-confirms-exit")
                device.pressBack()
                compose.waitForIdle()
                compose.onNodeWithText(text).assertIsDisplayed()
                assertTrue(!compose.onNodeWithTag("exit_dialog_discard").isDisplayed(), "Dismissing confirmation must preserve input")
                check(device.swipe(1, y, device.displayWidth / 2, y, 60))
                compose.waitUntil(10_000) { compose.onNodeWithTag("exit_dialog_discard").isDisplayed() }
                compose.onNodeWithTag("exit_dialog_discard").performClick()
                compose.waitUntil(15_000) { compose.onAllNodes(createEntry).onFirst().isDisplayed() }
                val drafts = KoinPlatform.getKoin().get<EntryDraftRepository>()
                assertTrue(
                    runBlocking {
                        drafts.getDrafts().first().none { draft ->
                            draft.notes.any { note ->
                                note is JournalNote.Text &&
                                    note.content == text
                            }
                        }
                    },
                )
                repeat(3) { visit ->
                    compose.onAllNodes(createEntry).onFirst().performClick()
                    compose
                        .onNodeWithTag("editor_text_input")
                        .assertIsDisplayed()
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                    capture(context, "discard-reopened-$visit")
                    repeat(4) {
                        if (!compose.onAllNodes(createEntry).onFirst().isDisplayed()) {
                            device.pressBack()
                            compose.waitForIdle()
                        }
                    }
                    compose.onAllNodes(createEntry).onFirst().assertIsDisplayed()
                }
            }
        } finally {
            previousNavigation?.let { device.executeShellCommand("cmd overlay enable-exclusive --category $it") }
        }
    }

    @Test
    fun explicitRecordingDiscardStopsItsServiceAndAddEntryDoesNotRestartIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launch =
            Intent(context, MainActivity::class.java).apply {
                action = app.logdate.client.capture.ACTION_CAPTURE
                putExtra(app.logdate.client.capture.EXTRA_CAPTURE_MODE, "voice")
                putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ActivityScenario.launch<MainActivity>(launch).use {
            val manager = KoinPlatform.getKoin().get<AudioRecordingManager>()
            compose.waitUntil(30_000) { manager.isRecording }
            val draftId = assertNotNull(manager.currentRecordingDraftId)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            repeat(3) {
                if (!compose.onNodeWithTag("exit_dialog_discard").isDisplayed()) {
                    device.pressBack()
                    compose.waitForIdle()
                }
            }
            compose.onNodeWithTag("exit_dialog_discard").assertIsDisplayed().performClick()
            compose.waitUntil(30_000) {
                !manager.isRecording &&
                    !manager.isStartingRecording &&
                    compose.onAllNodes(createEntry).onFirst().isDisplayed()
            }
            val drafts = KoinPlatform.getKoin().get<EntryDraftRepository>()
            assertTrue(runBlocking { drafts.getDrafts().first().none { it.id == draftId } })
            compose.onAllNodes(createEntry).onFirst().performClick()
            compose
                .onNodeWithTag("editor_text_input")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            assertTrue(!manager.isRecording && !manager.isStartingRecording)
            capture(context, "recording-discard-reopened")
        }
    }

    @Test
    fun unavailableEarlierRecordingRetainsDurationAndOffersRecoveryWithoutStartingCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launch =
            Intent().setClassName(context, app.logdate.client.capture.INTERNAL_CAPTURE_ACTIVITY).apply {
                action = app.logdate.client.capture.ACTION_CAPTURE
                putExtra(app.logdate.client.capture.EXTRA_CAPTURE_MODE, "text")
                putExtra(app.logdate.client.capture.EXTRA_CAPTURE_TEXT, "Review retained recordings")
                putExtra(DEBUG_SKIP_ONBOARDING_EXTRA, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ActivityScenario.launch<MainActivity>(launch).use {
            compose.waitUntil(30_000) { compose.onNodeWithTag("editor_text_input").isDisplayed() }
            if (compose.onNodeWithContentDescription("Return to entry").isDisplayed()) {
                compose.onNodeWithContentDescription("Return to entry").performClick()
            }
            val notes = KoinPlatform.getKoin().get<JournalNotesRepository>()
            val now = Clock.System.now()
            val note =
                JournalNote.Audio(
                    mediaRef = File(context.filesDir, "audio_notes/missing_${Uuid.random()}.m4a").toURI().toString(),
                    durationMs = 12_345,
                    creationTimestamp = now,
                    lastUpdated = now,
                    caption = "Retained recording details",
                )
            runBlocking { notes.create(note) }
            try {
                val row = hasAnyAncestor(hasTestTag("earlier_note_${note.uid}"))
                compose.waitUntil(15_000) { compose.onNode(hasTestTag("audio_preview_retry") and row).isDisplayed() }
                compose.onNode(hasText("Recording unavailable") and row).assertIsDisplayed()
                compose.onNode(hasText("00:12") and row).assertIsDisplayed()
                compose.onNode(hasContentDescription("Play") and row).assertDoesNotExist()
                compose.onNode(hasTestTag("audio_preview_retry") and row).performClick()
                compose.waitUntil(15_000) { compose.onNode(hasTestTag("audio_preview_retry") and row).isDisplayed() }
                compose.onNode(hasText("Recording unavailable") and row).assertIsDisplayed()
                compose.onNode(hasText("Retained recording details") and row).assertIsDisplayed()
                val retained = runBlocking { notes.getNoteById(note.uid) } as JournalNote.Audio
                assertEquals(note.mediaRef, retained.mediaRef)
                assertEquals(note.durationMs, retained.durationMs)
                assertEquals(note.caption, retained.caption)
                val recorder = KoinPlatform.getKoin().get<AudioRecordingManager>()
                assertTrue(!recorder.isRecording && !recorder.isStartingRecording)
                capture(context, "earlier-audio-unavailable-retains-details")
            } finally {
                runBlocking { notes.removeById(note.uid) }
            }
        }
    }

    private fun capture(
        context: Context,
        name: String,
    ) {
        val directory =
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
                "Recording lifecycle acceptance requires a Gradle Managed Device"
            }
        val destination = File(directory, "$name.png")
        destination.parentFile?.mkdirs()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(destination))
    }
}
