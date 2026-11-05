@file:Suppress("ktlint:standard:function-naming")

package app.logdate.client.e2e

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.ui.LockableContent
import app.logdate.feature.core.AppUnlockState
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.requiresUnlock
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.viewmodel.ext.android.getViewModel
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Actual Activity lifecycle, production app lock, and Android device credential UI on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class AppLockLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var model: AppViewModel
    private var prepared = false

    @After
    fun resetOnlyTheOwnedEmulatorPreference() {
        if (prepared) {
            capture("test-end")
            runBlocking { GlobalContext.get().get<UserStateRepository>().setBiometricEnabled(false) }
        }
    }

    @Test
    fun nativeCancellationWrongPinAndRepeatedReturnStayProtected() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appLockNative") == "true")
        prepare()
        compose.onNodeWithText("Journal draft").performTextInput("Keep this draft")
        compose.onNodeWithText("Protected action").performClick()
        val actionBounds = compose.onNodeWithText("Protected action").fetchSemanticsNode().boundsInWindow
        backgroundAndReturn()
        assertLocked("return-locked")

        compose.onNodeWithText("Use passcode").performClick()
        awaitPin()
        capture("native-prompt")
        cancelNativePrompt()
        await { modelState().unlockState == AppUnlockState.Idle }
        assertLocked("cancelled")
        device.click(actionBounds.center.x.toInt(), actionBounds.center.y.toInt())
        device.pressKeyCode(android.view.KeyEvent.KEYCODE_Z)

        compose.onNodeWithText("Use passcode").performClick()
        awaitPin()
        device.pressHome()
        returnToApp()
        await { modelState().unlockState == AppUnlockState.Idle }
        assertLocked("home-during-prompt")

        compose.onNodeWithText("Use passcode").performClick()
        enterPin("9999")
        assertNotNull(
            device.wait(Until.findObject(By.textContains("Wrong PIN")), 10_000),
            "Wrong PIN feedback must stay in native credential UI",
        )
        assertEquals(true, modelState().requiresUnlock)
        capture("wrong-pin")
        enterPin("1234")
        awaitUnlocked()
        compose.onNodeWithText("Keep this draft").assertIsDisplayed()
        compose.onNodeWithText("Action count: 1").assertIsDisplayed()
        capture("unlocked-draft")

        repeat(2) { cycle ->
            backgroundAndReturn()
            assertLocked("repeat-${cycle + 1}-locked")
            compose.onNodeWithText("Use passcode").performClick()
            enterPin("1234")
            awaitUnlocked()
            compose.onNodeWithText("Keep this draft").assertIsDisplayed()
            compose.onNodeWithText("Action count: 1").assertIsDisplayed()
        }
        capture("repeated-unlock")
    }

    @Test
    fun unavailableNativeProviderKeepsTheLockAndOffersRetry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appLockUnavailable") == "true")
        prepare()
        backgroundAndReturn()
        assertLocked("unavailable-before")
        compose.onNodeWithText("Use passcode").performClick()
        await { modelState().unlockState == AppUnlockState.Failed }
        assertLocked("unavailable")
        compose.onNodeWithText("Couldn’t verify it’s you. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Use passcode").performClick()
        await { modelState().unlockState == AppUnlockState.Failed }
        assertLocked("unavailable-retried")
    }

    @Test
    fun sensitiveAuthenticationCannotTakeOverTheRootUnlock() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appLockNative") == "true")
        prepare()
        backgroundAndReturn()
        val sensitiveResult =
            java.util.concurrent.atomic
                .AtomicReference<app.logdate.feature.core.AppAuthState?>()
        compose.runOnUiThread {
            GlobalContext.get().get<app.logdate.feature.core.BiometricGatekeeper>().authenticate(
                requestEnrollmentIfNecessary = false,
                onResult = { sensitiveResult.set(it) },
            )
            model.showNativeUnlockPrompt()
        }
        enterPin("1234")
        await { sensitiveResult.get() == app.logdate.feature.core.AppAuthState.AUTHENTICATED }
        assertLocked("sensitive-success-still-locked")
        compose.onNodeWithText("Use passcode").performClick()
        enterPin("1234")
        awaitUnlocked()
    }

    @Test
    fun actualNavigationShellPromptsOnReturnAndRetriesAfterCancellation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appLockNative") == "true")
        prepare(replaceContent = false)
        repeat(2) { cycle ->
            device.pressHome()
            await { modelState().requiresUnlock }
            returnToApp()
            awaitPin()
            cancelNativePrompt()
            await { modelState().unlockState == AppUnlockState.Idle }
            compose.onNodeWithText("Use passcode").assertIsDisplayed()
            capture("shell-cancelled-$cycle")
            compose.onNodeWithText("Use passcode").performClick()
            enterPin("1234")
            await { !modelState().requiresUnlock }
        }
        compose.onNodeWithText("Use passcode").assertDoesNotExist()
        capture("shell-unlocked")
    }

    @Test
    fun protectedDialogCannotRemainAboveTheLock() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appLockUnavailable") == "true")
        prepare()
        compose.onNodeWithText("Open protected dialog").performClick()
        compose.onNodeWithText("Private confirmation").assertIsDisplayed()
        backgroundAndReturn()

        compose.onNodeWithText("Private confirmation").assertDoesNotExist()
        compose.onNodeWithText("Confirm protected action").assertDoesNotExist()
        compose.onNodeWithText("Use passcode").assertIsDisplayed()
        capture("dialog-hidden")
    }

    @Test
    fun lockingTheEditorFlushesTheLatestDraftBeforeItsUiLeaves() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appLockUnavailable") == "true")
        prepare()
        lateinit var editor: app.logdate.feature.editor.ui.editor.EntryEditorViewModel
        compose.runOnUiThread {
            editor = compose.activity.getViewModel()
            compose.activity.setContent {
                val state = model.uiState.collectAsState().value as? GlobalAppUiLoadedState
                if (state != null) {
                    LogDateTheme(dynamicColor = false) {
                        LockableContent(
                            isLocked = state.requiresUnlock,
                            displayName = "",
                            onUsePasscode = model::showNativeUnlockPrompt,
                            unlockState = state.unlockState,
                        ) {
                            app.logdate.feature.editor.ui
                                .NoteEditorScreen({}, {}, viewModel = editor)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val block =
                editor.createNewBlock(app.logdate.feature.editor.ui.editor.BlockType.TEXT)
                    as app.logdate.feature.editor.ui.editor.TextBlockUiState
            editor.updateBlock(block.copy(content = "Typed immediately before app lock"))
            model.onAppBackgrounded()
        }
        compose.onNodeWithText("Use passcode").assertIsDisplayed()
        val drafts = GlobalContext.get().get<app.logdate.client.repository.journals.EntryDraftRepository>()
        val stored =
            runBlocking {
                kotlinx.coroutines.withTimeout(5_000) {
                    drafts.getDrafts().first { all ->
                        all.any { draft ->
                            draft.notes.any {
                                (it as? app.logdate.client.repository.journals.JournalNote.Text)?.content ==
                                    "Typed immediately before app lock"
                            }
                        }
                    }
                }
            }
        assertEquals(true, stored.isNotEmpty())
    }

    private fun prepare(replaceContent: Boolean = true) {
        require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        runBlocking {
            GlobalContext.get().get<UserStateRepository>().setBiometricEnabled(false)
            if (!replaceContent) GlobalContext.get().get<UserStateRepository>().setIsOnboardingComplete(true)
        }
        compose.runOnUiThread { model = compose.activity.getViewModel() }
        await { (model.uiState.value as? GlobalAppUiLoadedState)?.requiresUnlock == false }
        if (replaceContent) {
            compose.runOnUiThread {
                compose.activity.setContent {
                    val state = model.uiState.collectAsState().value as? GlobalAppUiLoadedState
                    if (state != null) {
                        LogDateTheme(dynamicColor = false) {
                            LockableContent(
                                isLocked = state.requiresUnlock,
                                displayName = "",
                                onUsePasscode = model::showNativeUnlockPrompt,
                                unlockState = state.unlockState,
                            ) {
                                var draft by rememberSaveable { mutableStateOf("") }
                                var clicks by rememberSaveable { mutableStateOf(0) }
                                var showDialog by rememberSaveable { mutableStateOf(false) }
                                if (showDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showDialog = false },
                                        text = { Text("Private confirmation") },
                                        confirmButton = {
                                            Button(onClick = {
                                                clicks++
                                                showDialog = false
                                            }) { Text("Confirm protected action") }
                                        },
                                    )
                                }
                                Column(
                                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    TextField(
                                        value = draft,
                                        onValueChange = { draft = it },
                                        label = { Text("Journal draft") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Button(onClick = { clicks++ }) { Text("Protected action") }
                                    Text("Action count: $clicks")
                                    Button(onClick = { showDialog = true }) { Text("Open protected dialog") }
                                }
                            }
                        }
                    }
                }
            }
        }
        prepared = true
        runBlocking { GlobalContext.get().get<UserStateRepository>().setBiometricEnabled(true) }
        await { !modelState().requiresUnlock }
        compose.waitForIdle()
    }

    private fun backgroundAndReturn() {
        device.pressHome()
        await { modelState().requiresUnlock }
        returnToApp()
        compose.onNodeWithText("Use passcode").assertIsDisplayed()
    }

    private fun returnToApp() {
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        await {
            compose.activity.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        compose.waitForIdle()
    }

    private fun assertLocked(captureName: String) {
        compose.onNodeWithText("Use passcode").assertIsDisplayed()
        compose.onNodeWithText("Journal draft").assertDoesNotExist()
        compose.onNodeWithText("Protected action").assertDoesNotExist()
        compose.onNodeWithText("Keep this draft").assertDoesNotExist()
        assertEquals(true, modelState().requiresUnlock)
        capture(captureName)
    }

    private fun cancelNativePrompt() {
        device.pressBack()
        device.waitForIdle()
        if (device.hasObject(By.clazz("android.widget.EditText"))) device.pressBack()
    }

    private fun awaitPin() = assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))

    private fun enterPin(pin: String) {
        awaitPin().text = pin
        device.pressEnter()
    }

    private fun awaitUnlocked() {
        await { !modelState().requiresUnlock }
        compose.onNodeWithText("Journal draft").assertIsDisplayed()
    }

    private fun modelState() = model.uiState.value as GlobalAppUiLoadedState

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        check(condition()) { "App lock did not reach the expected state: ${model.uiState.value}" }
    }

    private fun capture(name: String) {
        SystemClock.sleep(300)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "app-lock")
        folder.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
