package app.logdate.wear.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.logdate.wear.LogDateWearApplication
import app.logdate.wear.launch.WearExperience
import app.logdate.wear.launch.WearExperienceGate
import app.logdate.wear.launch.WearLaunchRequest
import app.logdate.wear.launch.WearStartupState
import app.logdate.wear.launch.resolveWearExperience
import app.logdate.wear.launch.toWearLaunchRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The watch app's only activity.
 *
 * Tiles, complications and the benchmark modules start it as `app.logdate.wear.presentation.MainActivity`,
 * so its name and package stay as they are. It is single-top: a launch that arrives while it is on
 * screen comes through [onNewIntent].
 */
class MainActivity : ComponentActivity() {
    private val experienceGate: WearExperienceGate by inject()
    private val experience = MutableStateFlow<WearExperience?>(null)
    private val launchRequests = Channel<WearLaunchRequest>(Channel.CONFLATED)

    @Volatile
    private var splashHoldExpired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        val startup = (application as LogDateWearApplication).startup
        // Resolve the experience flag in parallel with storage startup; it reads DataStore only.
        resolveExperience(savedInstanceState)
        lifecycleScope.launch {
            delay(SPLASH_HOLD_TIMEOUT_MS)
            splashHoldExpired = true
        }
        splashScreen.setKeepOnScreenCondition {
            !splashHoldExpired && (startup.state.value is WearStartupState.Loading || experience.value == null)
        }

        val initialRequest = intent.toWearLaunchRequest()
        val newRequests = launchRequests.receiveAsFlow()
        setContent {
            val startupState by startup.state.collectAsState()
            val resolvedExperience by experience.collectAsState()
            if (startupState !is WearStartupState.Ready || resolvedExperience == null) {
                WearStartupScreen(startupState, startup::start)
            } else {
                MainActivityContent(
                    experience = resolvedExperience,
                    initialRequest = initialRequest,
                    newRequests = newRequests,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Retry after an unlock/foreground return; start is single-flight and a no-op once ready.
        (application as LogDateWearApplication).startup.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val request = intent.toWearLaunchRequest() ?: return
        launchRequests.trySend(request)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val resolved = experience.value ?: return
        outState.putString(STATE_EXPERIENCE, resolved.name)
    }

    /**
     * Picks the watch app behind the splash screen. A recreated activity passes its saved choice, so
     * the app never changes while the activity is alive.
     */
    private fun resolveExperience(savedInstanceState: Bundle?) {
        val restored = WearExperience.fromName(savedInstanceState?.getString(STATE_EXPERIENCE))
        lifecycleScope.launch {
            experience.value = resolveWearExperience(restored) { experienceGate }
        }
    }

    private companion object {
        const val STATE_EXPERIENCE = "wear_experience"
        const val SPLASH_HOLD_TIMEOUT_MS = 1_500L
    }
}
