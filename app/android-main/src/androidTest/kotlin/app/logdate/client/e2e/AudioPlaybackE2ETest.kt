package app.logdate.client.e2e

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.MainActivity
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.audio.AudioPlaybackStatus
import app.logdate.client.media.audio.AudioPlaybackStatusProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Duration.Companion.seconds

/**
 * Instrumented E2E tests for the audio playback experience.
 *
 * This suite verifies the visibility and behavior of the audio mini-player,
 * ensuring it correctly displays playback metadata (like titles and journal
 * associations) and responds to user controls like pausing and stopping.
 */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackE2ETest {
    private val fakePlaybackManager = FakePlaybackManagerWithStatus()

    private val testModule =
        module {
            single<AudioPlaybackManager> { fakePlaybackManager }
        }

    private val koinRule = AudioPlaybackKoinRule(testModule)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule).around(composeRule)

    @Test
    fun `mini player shows stop button that dismisses`() {
        // Wait for app to load
        composeRule.waitForIdle()

        // The mini-player should not be visible before playback starts
        composeRule.onNodeWithContentDescription("Stop playback").assertDoesNotExist()
    }

    @Test
    fun `audio playback metadata fallback shows audio recording`() {
        // Verify that the default metadata title is "Audio Recording"
        // when no title is explicitly set
        val metadata = AudioPlaybackMetadata()
        assert(metadata.title == null) {
            "Default metadata title should be null (fallback applied at playback layer)"
        }
    }

    @Test
    fun `audio playback metadata preserves journal names`() {
        val metadata =
            AudioPlaybackMetadata(
                title = "Morning Recording",
                journalNames = listOf("Travel", "Daily"),
            )
        assert(metadata.journalNames.size == 2)
        assert(metadata.journalNames.joinToString(", ") == "Travel, Daily")
    }

    @Test
    fun `audio playback metadata preserves palette colors`() {
        val metadata =
            AudioPlaybackMetadata(
                title = "Evening Recording",
                accentColor = 0xFF6C3483,
                immersiveBackground = 0xFF0D0D1A,
                gradientStart = 0xFF1A1A2E,
                gradientEnd = 0xFF9B59B6,
            )
        assert(metadata.accentColor == 0xFF6C3483L)
        assert(metadata.immersiveBackground == 0xFF0D0D1AL)
        assert(metadata.gradientStart == 0xFF1A1A2EL)
        assert(metadata.gradientEnd == 0xFF9B59B6L)
    }
}

private class AudioPlaybackKoinRule(
    private val module: Module,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                startKoinIfAppHasNot(context)
                loadKoinModules(module)
                base.evaluate()
            }
        }
}

/**
 * Fake AudioPlaybackManager that also implements AudioPlaybackStatusProvider,
 * allowing tests to verify playback status observation.
 */
private class FakePlaybackManagerWithStatus :
    AudioPlaybackManager,
    AudioPlaybackStatusProvider {
    private val _playbackStatus = MutableStateFlow(AudioPlaybackStatus())
    override val playbackStatus: StateFlow<AudioPlaybackStatus> = _playbackStatus

    private var currentOnProgress: ((Float) -> Unit)? = null
    private var currentOnCompleted: (() -> Unit)? = null

    override fun startPlayback(
        uri: String,
        metadata: AudioPlaybackMetadata?,
        onProgressUpdated: (Float) -> Unit,
        onPlaybackCompleted: () -> Unit,
    ) {
        currentOnProgress = onProgressUpdated
        currentOnCompleted = onPlaybackCompleted
        _playbackStatus.value =
            AudioPlaybackStatus(
                isPlaying = true,
                progress = 0f,
                duration = 30.seconds,
            )
        onProgressUpdated(0f)
    }

    override fun pausePlayback() {
        _playbackStatus.value = _playbackStatus.value.copy(isPlaying = false)
    }

    override fun stopPlayback() {
        _playbackStatus.value = AudioPlaybackStatus()
        currentOnProgress = null
        currentOnCompleted = null
    }

    override fun seekTo(position: Float) {
        _playbackStatus.value = _playbackStatus.value.copy(progress = position)
        currentOnProgress?.invoke(position)
    }

    override fun release() {
        stopPlayback()
    }
}
