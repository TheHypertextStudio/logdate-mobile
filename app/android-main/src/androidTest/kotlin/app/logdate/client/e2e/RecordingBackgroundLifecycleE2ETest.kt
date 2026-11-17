package app.logdate.client.e2e

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import app.logdate.client.MainActivity
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_NEW_ENTRY
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_TARGET
import app.logdate.client.media.audio.AndroidAudioRecordingManager
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.media.audio.AudioRecordingService
import app.logdate.client.media.audio.CrashSafeRecording
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.testing.onboarding.OnboardingTestFixture
import app.logdate.client.testing.onboarding.putOnboardingTestFixture
import app.logdate.ui.media.MediaDeviceSelectorTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.io.File

// What the entry page's controls are called.
private const val VOICE = "Voice"
private const val WRITE = "Write"
private const val ADD_TO_ENTRY = "Add to entry"
private const val RETURN_TO_ENTRY = "Return to entry"
private const val SAVE_ENTRY = "Save entry"

/** Exercises the real microphone and recording service on the API 35 managed emulator. */
@RunWith(AndroidJUnit4::class)
class RecordingBackgroundLifecycleE2ETest {
    private val koinRule = OnboardingKoinModuleOverrideRule(module {})
    private val permissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val activityRule = ActivityScenarioRule<MainActivity>(recordingEditorLaunchIntent())
    private val composeRule =
        AndroidComposeTestRule(activityRule) { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            checkNotNull(activity) { "MainActivity was not available from ActivityScenarioRule" }
        }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule).around(permissionRule).around(composeRule)

    @Test
    fun recordingSurvivesHomeAndAppResumeWithItsOriginalBlock() {
        verifyRecordingContinuity("home-resume") {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("OS Home must background LogDate", device.pressHome())
            composeRule.waitUntil(timeoutMillis = 10_000) {
                activityRule.scenario.state == Lifecycle.State.CREATED
            }

            val manager = GlobalContext.get().get<AudioRecordingManager>()
            assertTrue("The real service must still be recording in the background", manager.isRecording)
            assertRecordingNotificationPresent()
            val checkpoint = recordingDurationMillis(manager)
            val recordedFile = inFlightRecording(checkNotNull(manager.currentRecordingPath))
            val recordedSize = recordedFile.length()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                recordingDurationMillis(manager) >= checkpoint + 1_000 && recordedFile.length() > recordedSize
            }
            assertFalse("Home must not silently pause capture", runBlocking { manager.getRecordingPausedFlow().first() })

            val resumeIntent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            context.startActivity(resumeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            composeRule.waitUntil(timeoutMillis = 10_000) {
                activityRule.scenario.state == Lifecycle.State.RESUMED
            }
        }
    }

    @Test
    fun recordingSurvivesActivityRecreationWithItsOriginalBlock() {
        verifyRecordingContinuity("activity-recreation") {
            activityRule.scenario.recreate()
        }
    }

    @Test
    fun recordingSurvivesSwitchingToASecondaryEmulatedMicrophoneAndRemovingIt() {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
            "Microphone acceptance is allowed only on an Android emulator"
        }
        verifyRecordingContinuity("microphone-switch") {
            val routes = GlobalContext.get().get<AudioRouteRepository>()
            val original = checkNotNull(routes.inputDevices.value.selectedDeviceId)
            setEmulatedSecondaryMicrophoneConnected(true)
            try {
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    routes.inputDevices.value.devices.any {
                        it.isAvailable && it.category == MediaDeviceCategory.BUILT_IN && it.id != original
                    }
                }
                val alternative =
                    routes.inputDevices.value.devices.firstOrNull {
                        it.isAvailable && it.category == MediaDeviceCategory.BUILT_IN && it.id != original
                    }
                val context = ApplicationProvider.getApplicationContext<Context>()
                val nativeInputs =
                    context
                        .getSystemService(AudioManager::class.java)
                        .getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .joinToString { "id=${it.id}, type=${it.type}, address=${it.address}" }
                assertTrue("The emulator must expose two native microphone ports: $nativeInputs", alternative != null)
                val microphone = checkNotNull(alternative)
                composeRule.onNodeWithTag(MediaDeviceSelectorTags.chip("Microphone")).assertIsDisplayed().performClick()
                composeRule.onNodeWithTag(MediaDeviceSelectorTags.deviceRow("Microphone", microphone.id)).performClick()
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    routes.inputDevices.value.let { it.isSelectionConfirmed && it.selectedDeviceId == microphone.id }
                }
                capture("recording-microphone-switched")
                val manager = GlobalContext.get().get<AudioRecordingManager>()
                val checkpoint = recordingDurationMillis(manager)
                val recordedFile = inFlightRecording(checkNotNull(manager.currentRecordingPath))
                val recordedSize = recordedFile.length()
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    recordingDurationMillis(manager) >= checkpoint + 1_000 && recordedFile.length() > recordedSize
                }
                setEmulatedSecondaryMicrophoneConnected(false)
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    routes.inputDevices.value.let { it.isSelectionConfirmed && it.selectedDeviceId == original }
                }
            } finally {
                setEmulatedSecondaryMicrophoneConnected(false)
            }
        }
    }

    @Test
    fun recordingAnchorsItsBlockAndKeepsExistingTextAsPassiveContext() {
        verifyRecordingContinuity("focused-entry", includeExistingText = true) {
            val page = composeRule.onNodeWithTag("entry_page_scroll").fetchSemanticsNode()
            assertFalse("A focused recording must not expose a scroll action", page.config.contains(SemanticsActions.ScrollBy))
            composeRule.onNodeWithText(ADD_TO_ENTRY).assertIsNotEnabled()
            composeRule.onNodeWithContentDescription(SAVE_ENTRY).assertIsDisplayed().assertIsNotEnabled()
            val contextNodes = composeRule.onAllNodesWithTag("editor_passive_context").fetchSemanticsNodes()
            if (contextNodes.isNotEmpty()) {
                composeRule.onNodeWithText("Before this recording.").assertIsDisplayed()
            } else {
                composeRule.onNodeWithText("Before this recording.").assertDoesNotExist()
            }
            capture("recording-focused-entry-anchored")
        }
        returnToEntry()
        waitForAddToEntry()
        composeRule.onNodeWithText("Before this recording.").assertIsDisplayed()
        capture("recording-focused-entry-text-restored")
    }

    private fun setEmulatedSecondaryMicrophoneConnected(connected: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_ROUTING")
        try {
            AudioManager::class.java
                .getMethod(
                    "setWiredDeviceConnectionState",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                ).invoke(
                    audio,
                    Int.MIN_VALUE or 4,
                    if (connected) 1 else 0,
                    "acceptance-secondary-microphone",
                    "Acceptance secondary microphone",
                )
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun verifyRecordingContinuity(
        label: String,
        includeExistingText: Boolean = false,
        lifecycleChange: () -> Unit,
    ) {
        val manager = GlobalContext.get().get<AudioRecordingManager>()
        assertTrue("This test requires the production recording manager", manager is AndroidAudioRecordingManager)
        try {
            assertFalse("A previous recording must not own the test microphone", manager.isRecording)
            waitForChoice(VOICE)
            capture("recording-$label-empty-editor")
            if (includeExistingText) {
                composeRule.onNodeWithContentDescription(WRITE).performClick()
                waitForTag("editor_text_input")
                composeRule.onNodeWithTag("editor_text_input").performTextInput("Before this recording.")
                Espresso.closeSoftKeyboard()
                returnToEntry()
                waitForAddToEntry()
                composeRule.onNodeWithText(ADD_TO_ENTRY).performClick()
                waitForChoice(VOICE)
                composeRule.onNodeWithContentDescription(VOICE).performClick()
            } else {
                composeRule.onNodeWithContentDescription(VOICE).performClick()
            }
            waitForTag("audio_record_start_button")
            composeRule.onNodeWithTag("audio_record_start_button").performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                manager.isRecording && manager.currentRecordingTargetNoteId != null && manager.currentRecordingPath != null
            }
            val owner = checkNotNull(manager.currentRecordingTargetNoteId)
            val path = checkNotNull(manager.currentRecordingPath)
            composeRule.waitUntil(timeoutMillis = 10_000) {
                runBlocking { withTimeout(1_000) { manager.getRecordingDurationFlow().first() } }.inWholeMilliseconds >= 1_000
            }
            waitForFinish()
            composeRule.onNodeWithTag("memory_block_$owner").assertIsDisplayed()
            assertRecordingNotificationPresent()

            capture("recording-$label-before")
            lifecycleChange()

            waitForFinish()
            assertTrue("Recording must remain active after the lifecycle change", manager.isRecording)
            assertFalse("Capture must remain unpaused", runBlocking { manager.getRecordingPausedFlow().first() })
            assertEquals(owner, manager.currentRecordingTargetNoteId)
            assertEquals(path, manager.currentRecordingPath)
            composeRule.onNodeWithTag("memory_block_$owner").assertIsDisplayed()
            capture("recording-$label-after")
            composeRule.onNodeWithText("Finish").assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) { !manager.isRecording }

            val draftRepository = GlobalContext.get().get<EntryDraftRepository>()
            var savedAudio: JournalNote.Audio? = null
            composeRule.waitUntil(timeoutMillis = 15_000) {
                savedAudio =
                    runBlocking {
                        withTimeout(1_000) {
                            draftRepository
                                .getDrafts()
                                .first()
                                .flatMap { it.notes }
                                .filterIsInstance<JournalNote.Audio>()
                                .firstOrNull { it.uid == owner }
                        }
                    }
                savedAudio != null
            }
            val readyAudio = checkNotNull(savedAudio)
            assertEquals(path, Uri.parse(readyAudio.mediaRef).path)
            assertTrue("Finalized audio must retain recorded samples", File(path).length() > 0)
            assertTrue("Finalized audio must retain its measured duration", readyAudio.durationMs > 0)
            composeRule.onNodeWithTag("memory_block_$owner").assertIsDisplayed()
            composeRule
                .onNode(
                    hasTestTag("audio_block_duration") and hasAnyAncestor(hasTestTag("memory_block_$owner")),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
            if (composeRule.onAllNodesWithTag("completed_audio_transcript").fetchSemanticsNodes().isEmpty()) {
                returnToEntry()
                waitForAddToEntry()
                val bounds = composeRule.onNodeWithTag("memory_block_$owner").getUnclippedBoundsInRoot()
                assertTrue("A completed recording without transcript must collapse to its controls", bounds.bottom - bounds.top < 240.dp)
            }
            if (includeExistingText) {
                val retainedText =
                    runBlocking {
                        draftRepository
                            .getDrafts()
                            .first()
                            .first { draft -> draft.notes.any { it.uid == owner } }
                            .notes
                            .filterIsInstance<JournalNote.Text>()
                    }
                assertTrue("Existing text must remain durably saved", retainedText.any { it.content == "Before this recording." })
            }
            capture("recording-$label-finalized")
        } finally {
            try {
                if (manager.isRecording) {
                    runBlocking { withTimeout(10_000) { manager.stopRecording() } }
                }
            } finally {
                manager.requestStopRecording()
            }
        }
    }

    private fun recordingDurationMillis(manager: AudioRecordingManager): Long =
        runBlocking { withTimeout(1_000) { manager.getRecordingDurationFlow().first().inWholeMilliseconds } }

    /** Waits for one of the entry page's ways to add something, which are named by what they say. */
    private fun waitForChoice(name: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The file capture is writing now. The final file exists only once the recording has ended. */
    private fun inFlightRecording(finalPath: String): File {
        val final = File(finalPath)
        return CrashSafeRecording.inFlightFile(final, isolateForBackup = true).takeIf { it.isFile }
            ?: CrashSafeRecording.inFlightFile(final)
    }

    /** Leaves the piece that is open for the entry it belongs to, where there is one to leave. */
    private fun returnToEntry() {
        if (composeRule.onAllNodesWithContentDescription(RETURN_TO_ENTRY).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithContentDescription(RETURN_TO_ENTRY).performClick()
        }
    }

    /** Waits until the entry offers to add something, which it does only while nothing is open. */
    private fun waitForAddToEntry() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText(ADD_TO_ENTRY) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForFinish() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Finish").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val directory =
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
                "Recording acceptance requires a Gradle Managed Device"
            }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val file = File(directory, "$name.png")
        file.parentFile?.mkdirs()
        assertTrue("The managed emulator must produce a screenshot", device.takeScreenshot(file))
    }

    private fun assertRecordingNotificationPresent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue(
            "The real foreground recording service must expose its recording notification",
            notifications.activeNotifications.any { it.id == AudioRecordingService.NOTIFICATION_ID },
        )
    }
}

private fun recordingEditorLaunchIntent(): Intent =
    Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        putOnboardingTestFixture(OnboardingTestFixture.ONBOARDED_HOME)
        putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_NEW_ENTRY)
    }
