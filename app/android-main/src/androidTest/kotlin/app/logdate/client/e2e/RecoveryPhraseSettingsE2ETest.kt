@file:Suppress("ktlint:standard:function-naming")

package app.logdate.client.e2e

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.feature.core.AppAuthState
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.settings.account.recovery.PhraseRevealState
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseContent
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseScreen
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseUiState
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseViewModel
import app.logdate.ui.platform.LocalReduceMotionOverride
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the actual screen with a disposable phrase; never uses an account's secure storage. */
@RunWith(AndroidJUnit4::class)
class RecoveryPhraseSettingsE2ETest {
    @get:Rule
    val compose = createComposeRule()

    private val words =
        listOf("orbit", "canvas", "meadow", "lantern", "harbor", "velvet", "summit", "pepper", "gravel", "whisper", "copper", "tundra")

    @Before
    fun emulatorOnly() {
        require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use an Android emulator" }
    }

    @Test
    fun viewerExplainsRecoveryAndGroupsPrivacyWithThePhrase() {
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("For restoring a journal backup when automatic recovery isn’t available.").assertIsDisplayed()
        compose.onNodeWithText("How recovery works").assertIsDisplayed()
        val phrase = compose.onNodeWithTag("recovery-phrase-content").fetchSemanticsNode().boundsInRoot
        val reminder = compose.onNodeWithText("Keep your recovery phrase private.").fetchSemanticsNode().boundsInRoot
        assertTrue(reminder.top >= phrase.top && reminder.bottom <= phrase.bottom, "The privacy reminder belongs inside the phrase surface")
    }

    @Test
    fun viewerNeverOffersSetupConfirmation() {
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(
                    RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)),
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Copy phrase").assertIsDisplayed()
        compose.onNodeWithText("Confirm recovery copy").assertDoesNotExist()
        compose.onNodeWithText("A private recovery copy is required before encrypted Cloud backup can run.").assertDoesNotExist()
    }

    @Test
    fun descriptionAndActionsShareTheTitleLeadingEdge() {
        compose.setContent {
            TestTheme { RecoveryPhraseContent(RecoveryPhraseUiState.Available(), {}, {}, {}, {}) }
        }
        compose.assertRecoveryViewerAlignment()
        compose.onNodeWithText("12 words").assertDoesNotExist()
        compose.onNodeWithText("These 12 words unlock your encrypted journal.").assertDoesNotExist()
    }

    @Test
    fun helpExplainsRecoveryWithoutClaimingWhereThePhraseIsStored() {
        var state by mutableStateOf<RecoveryPhraseUiState>(
            RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)),
        )
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(
                    state,
                    {},
                    {},
                    { state = RecoveryPhraseUiState.Available() },
                    {},
                )
            }
        }
        compose.onNodeWithText("Saved elsewhere").assertDoesNotExist()
        compose.onNodeWithText("How recovery works").performScrollTo().performClick()
        capture("about")
        compose.onNodeWithContentDescription("Journal backup to another device").assertIsDisplayed()
        compose.onNodeWithText("Read recovery policy").assertIsDisplayed()
        capture("about-details")
        compose.onNodeWithText("orbit").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close recovery help").performClick()
        compose.onNodeWithText("Show recovery phrase").assertIsDisplayed()
        compose.onNodeWithText("orbit").assertDoesNotExist()
    }

    @Test
    fun recoveryHelpOpensThePublishedDetailsLink() {
        var opened: String? = null
        val handler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened = uri
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                TestTheme {
                    RecoveryPhraseContent(RecoveryPhraseUiState.Available(), {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("How recovery works").performScrollTo().performClick()
        compose.onNodeWithText("Read recovery policy").assertIsDisplayed().performClick()
        assertEquals("https://logdate.app/privacy", opened)
    }

    @Test
    fun helpDoesNotCancelTheInitialStorageCheck() {
        val storage = CompletableDeferred<List<String>>()
        lateinit var model: RecoveryPhraseViewModel
        compose.runOnUiThread {
            model = RecoveryPhraseViewModel(DeferredGatekeeper(), loadPhrase = { storage.await() })
        }
        compose.setContent { TestTheme { RecoveryPhraseScreen({}, {}, model) } }
        compose.onNodeWithText("How recovery works").performScrollTo().performClick()
        storage.complete(words)
        compose.onNodeWithContentDescription("Close recovery help").performClick()
        compose.onNodeWithText("Show recovery phrase").assertIsEnabled()
    }

    @Test
    fun largeTextKeepsIndicesAndWordsOnSingleLines() {
        val phrase = words.toMutableList().apply { this[9] = "abstract" }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TestTheme {
                    RecoveryPhraseContent(
                        RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(phrase)),
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        listOf("12", "abstract").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(text, useUnmergedTree = true)
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount, "Phrase indices and words must stay on one line")
            val layout = layouts.single()
            val bounds = text.indices.map(layout::getBoundingBox)
            // Layout sizes are integers; character advances may end between physical pixels.
            assertTrue(
                bounds.all { it.left >= -1f && it.right <= layout.size.width + 1f },
                "$text character bounds must fit: $bounds in ${layout.size}",
            )
        }
    }

    @Test
    fun openingWhileStorageLoadsNeverShowsAnError() {
        val stored = CompletableDeferred<List<String>?>()
        lateinit var model: RecoveryPhraseViewModel
        compose.runOnUiThread { model = RecoveryPhraseViewModel(DeferredGatekeeper(), loadPhrase = { stored.await() }) }
        compose.setContent { TestTheme { RecoveryPhraseScreen({}, {}, model) } }
        compose.onNodeWithText("Checking this device…").assertIsNotEnabled()
        compose.onNodeWithText("Recovery phrase").assertIsDisplayed()
        compose.onNodeWithText("Couldn’t read the phrase on this device. Try again.").assertDoesNotExist()
        stored.complete(words)
        compose.onNodeWithText("Show recovery phrase").assertIsDisplayed()
    }

    @Test
    fun authenticationIsLocalAndCancellationRestoresTheAction() {
        val gate = DeferredGatekeeper()
        lateinit var model: RecoveryPhraseViewModel
        compose.runOnUiThread { model = RecoveryPhraseViewModel(gate, loadPhrase = { words }) }
        compose.setContent { TestTheme { RecoveryPhraseScreen({}, {}, model) } }
        compose.onNodeWithText("Show recovery phrase").performClick()
        compose.onNodeWithText("Recovery phrase").assertIsDisplayed()
        compose.onNodeWithText("Hidden for privacy").assertIsDisplayed()
        compose.onNodeWithText("Unlocking…").assertIsNotEnabled()
        compose.runOnUiThread { gate.complete(AppAuthState.REQUIRE_PROMPT) }
        compose.onNodeWithText("Show recovery phrase").assertIsDisplayed()
        compose.onNodeWithText("orbit").assertDoesNotExist()
    }

    @Test
    fun copyWritesTheActualPhraseAsSensitiveAndHideImmediatelyRemovesTheWords() {
        var state by mutableStateOf<RecoveryPhraseUiState>(
            RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)),
        )
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(state, {}, {}, { state = RecoveryPhraseUiState.Available() }, {})
            }
        }
        compose.onNodeWithText("Copy phrase").performScrollTo().performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals(words.joinToString(" "), clip.getItemAt(0).text.toString())
            assertTrue(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Hide").performClick()
        // Recompose one frame, then check while the container's exit resize is still running.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("orbit").assertDoesNotExist()
        compose.onNodeWithText("tundra").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Show recovery phrase").assertIsDisplayed()
    }

    @Test
    fun aFailedCopyCanBeRetriedWithoutLosingThePhrase() {
        var attempts = 0
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(
                    RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)),
                    {},
                    {},
                    {},
                    {},
                    onCopy = { if (++attempts == 1) error("Clipboard temporarily unavailable") },
                )
            }
        }
        compose.onNodeWithText("Copy phrase").performScrollTo().performClick()
        compose.onNodeWithText("Couldn’t copy the phrase. Try again.").assertIsDisplayed()
        capture("copy-failed")
        compose.onNodeWithText("orbit").assertIsDisplayed()
        compose.onNodeWithText("Copy phrase").performScrollTo().performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onNodeWithText("Couldn’t copy the phrase. Try again.").assertDoesNotExist()
    }

    @Test
    fun delayedClipboardCompletionDoesNotClaimSuccessEarly() {
        val write = CompletableDeferred<Unit>()
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(
                    RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)),
                    {},
                    {},
                    {},
                    {},
                    onCopy = { write.await() },
                )
            }
        }
        compose.onNodeWithText("Copy phrase").performScrollTo().performClick()
        compose.onNodeWithText("Copying…").assertIsNotEnabled()
        compose.onNodeWithText("Copied").assertDoesNotExist()
        compose.onNodeWithText("Hide").assertIsDisplayed()
        write.complete(Unit)
        compose.onNodeWithText("Copied").assertIsDisplayed()
    }

    @Test
    fun reducedMotionStillRevealsAndHidesThePhrase() {
        var state by mutableStateOf<RecoveryPhraseUiState>(RecoveryPhraseUiState.Available())
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotionOverride provides true) {
                TestTheme {
                    RecoveryPhraseContent(
                        state,
                        {},
                        { state = RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)) },
                        { state = RecoveryPhraseUiState.Available() },
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("Show recovery phrase").performClick()
        compose.onNodeWithText("orbit").assertIsDisplayed()
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("orbit").assertDoesNotExist()
    }

    @Test
    fun captureTheViewerAndLocalFeedback() {
        var state by mutableStateOf<RecoveryPhraseUiState>(RecoveryPhraseUiState.Checking)
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(
                    state,
                    {},
                    { state = RecoveryPhraseUiState.Available(PhraseRevealState.Authenticating) },
                    { state = RecoveryPhraseUiState.Available() },
                    {},
                )
            }
        }
        capture("checking")
        compose.runOnUiThread { state = RecoveryPhraseUiState.Available() }
        compose.assertRecoveryViewerAlignment()
        capture("hidden")
        compose.onNodeWithText("Show recovery phrase").performClick()
        capture("authenticating")
        compose.runOnUiThread { state = RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)) }
        capture("revealed")
        compose.onNodeWithText("Copy phrase").performScrollTo().performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        capture("copied")
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("orbit").assertDoesNotExist()
        capture("hidden-again")
        compose.onNodeWithText("How recovery works").performScrollTo().performClick()
        capture("about")
        compose.onNodeWithText("Read recovery policy").assertIsDisplayed()
        capture("about-details")
        compose.onNodeWithContentDescription("Close recovery help").performClick()
    }

    @Test
    fun pausedScreenDropsWordsWhileItsCompositionRemainsAlive() {
        lateinit var owner: ScreenLifecycle
        lateinit var model: RecoveryPhraseViewModel
        val gate = DeferredGatekeeper()
        compose.runOnUiThread {
            owner = ScreenLifecycle()
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            model = RecoveryPhraseViewModel(gate, loadPhrase = { words })
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TestTheme { RecoveryPhraseScreen({}, {}, model) }
            }
        }
        compose.onNodeWithText("Show recovery phrase").performClick()
        compose.runOnUiThread { gate.complete(AppAuthState.AUTHENTICATED) }
        compose.onNodeWithText("orbit").assertIsDisplayed()
        // Drive the screen lifecycle while its composition remains attached, like an outgoing entry.
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithText("orbit").assertDoesNotExist()
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("orbit").assertDoesNotExist()
        compose.onNodeWithText("Show recovery phrase").assertIsDisplayed()
    }

    @Test
    fun backCoversWordsBeforeTheOutgoingScreenIsDisposed() {
        val gate = DeferredGatekeeper()
        lateinit var model: RecoveryPhraseViewModel
        var navigated = false
        compose.runOnUiThread { model = RecoveryPhraseViewModel(gate, loadPhrase = { words }) }
        compose.setContent { TestTheme { RecoveryPhraseScreen({ navigated = true }, {}, model) } }
        compose.onNodeWithText("Show recovery phrase").performClick()
        compose.runOnUiThread { gate.complete(AppAuthState.AUTHENTICATED) }
        compose.onNodeWithText("orbit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(navigated)
        compose.onNodeWithText("orbit").assertDoesNotExist()
    }

    @Test
    fun hidingCancelsPendingCopyFeedback() {
        val pending = CompletableDeferred<Unit>()
        var cancelled = false
        var state by mutableStateOf<RecoveryPhraseUiState>(RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)))
        compose.setContent {
            TestTheme {
                RecoveryPhraseContent(
                    state,
                    {},
                    {},
                    { state = RecoveryPhraseUiState.Available() },
                    {},
                    onCopy = {
                        try {
                            pending.await()
                        } finally {
                            cancelled = true
                        }
                    },
                )
            }
        }
        compose.onNodeWithText("Copy phrase").performScrollTo().performClick()
        compose.onNodeWithText("Copying…").assertIsNotEnabled()
        compose.onNodeWithText("Hide").performClick()
        compose.onNodeWithText("orbit").assertDoesNotExist()
        compose.waitForIdle()
        assertTrue(cancelled)
        pending.complete(Unit)
        compose.onNodeWithText("Copied").assertDoesNotExist()
    }

    private class ScreenLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        CompositionLocalProvider(
            LocalLayoutDirection provides if (arguments.getString("recoveryRtl") == "true") LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            LogDateTheme(
                dynamicColor = arguments.getString("recoveryDynamic") == "true",
                darkTheme =
                    arguments.getString("recoveryDark") == "true",
                content = content,
            )
        }
    }

    private fun capture(name: String) {
        val scenario = InstrumentationRegistry.getArguments().getString("recoveryCapture") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "recovery-ui/$scenario").apply { mkdirs() }
        compose.waitForIdle()
        // The native dialog window must present its Compose frame before a device screenshot.
        SystemClock.sleep(600)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private class DeferredGatekeeper : BiometricGatekeeper {
        override val authState = MutableStateFlow(AppAuthState.NO_PROMPT_NEEDED)
        private var callback: (AppAuthState) -> Unit = {}

        override fun authenticate(
            title: app.logdate.client.text.UiText,
            subtitle: app.logdate.client.text.UiText,
            cancelLabel: app.logdate.client.text.UiText,
            requireConfirmation: Boolean,
            requestEnrollmentIfNecessary: Boolean,
            description: app.logdate.client.text.UiText?,
            onResult: (AppAuthState) -> Unit,
        ) {
            callback = onResult
        }

        fun complete(result: AppAuthState) = callback(result)

        override fun requestEnrollment() {}
    }
}
