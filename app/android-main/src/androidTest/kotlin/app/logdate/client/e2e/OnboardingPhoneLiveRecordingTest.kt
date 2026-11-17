package app.logdate.client.e2e

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.client.networking.ServerDiscoveryClient
import app.logdate.client.networking.ServerHealthChecker
import app.logdate.client.networking.ServerHealthInfo
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.client.sync.NoOpSyncManager
import app.logdate.client.sync.SyncManager
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerPasskeyConfig
import app.logdate.shared.model.ServerProtocolFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Records the two onboarding journeys on a Managed Device phone at the speed a person sees them.
 *
 * A Compose test rule replaces the frame clock and runs every animation to its end the moment the
 * test asks for a node, so a recording made through one shows each transition as a single jump.
 * This test has no Compose rule: the app keeps the display's own clock, taps are injected by the
 * OS, and the screen is read through the accessibility tree, which never touches app time.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingPhoneLiveRecordingTest {
    private val environment = OnboardingJourneyEnvironment()
    private val passkeyAccountRepository = OnboardingFakePasskeyAccountRepository()
    private val testModule =
        module {
            includes(environment.module)
            single<SyncManager> { NoOpSyncManager }
            single<PasskeyAccountRepository> { passkeyAccountRepository }
            single<ServerHealthChecker> { OnboardingFakeServerHealthChecker }
            single<ServerDiscoveryClient> { OnboardingFakeServerDiscoveryClient }
        }

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(OnboardingKoinModuleOverrideRule(testModule)).around(OnboardingLaunchOverrideRule())

    @Test(timeout = 180_000)
    fun recordLiveOnboardingPhoneFlow() =
        record("onboarding-phone-live-device.mp4") { device ->
            // The table has been dealt and today's date has landed on it.
            SystemClock.sleep(2_400)

            // Today's photo is picked up from the middle of the pile and thrown up and across. It stays where it stops.
            val width = device.displayWidth
            val height = device.displayHeight
            device.swipe(width * 3 / 10, height * 48 / 100, width * 62 / 100, height * 38 / 100, 14)
            SystemClock.sleep(2_000)

            // Today's pile lines up on one page, and the thrown photo goes back with it.
            device.tap(By.text(SEE_HOW_IT_WORKS))
            SystemClock.sleep(2_800)

            // The editor slides in already focused on a blank thought.
            device.tap(By.text(MAKE_FIRST_ENTRY))
            SystemClock.sleep(1_600)
            device.tap(device.await(TEXT_FIELD))
            for (word in "Evening walk along the water.".split(" ")) {
                device.executeShellCommand("input text ${if (word == "Evening") word else "%s$word"}")
                SystemClock.sleep(320)
            }
            SystemClock.sleep(1_200)

            // Saving opens Home with the entry on it and nothing in between.
            device.tap(By.desc(SAVE_ENTRY))
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!device.hasObject(HOME)) {
                for (retired in RETIRED_SCREENS) check(!device.hasObject(By.textContains(retired))) { "\"$retired\" came back" }
                check(SystemClock.elapsedRealtime() < deadline) { "Home did not open after the first entry was saved" }
                SystemClock.sleep(100)
            }
            device.await(By.textContains("Evening walk along the water"))
            SystemClock.sleep(2_400)
            device.swipe(360, 1100, 360, 920, 20)
            SystemClock.sleep(1_000)
            device.swipe(360, 920, 360, 1100, 20)
            SystemClock.sleep(1_500)
        }

    @Test(timeout = 180_000)
    fun recordReturningUserOnboardingPhoneFlow() {
        passkeyAccountRepository.reset()
        record("onboarding-returning-user-live-device.mp4") { device ->
            SystemClock.sleep(2_200)
            device.tap(By.text(SIGN_IN_TO_CLOUD))
            SystemClock.sleep(1_400)

            device.tap(device.await(TEXT_FIELD))
            device.executeShellCommand("input text willie")
            SystemClock.sleep(1_200)
            device.pressBack() // Dismisses the keyboard so the sign-in action is on screen.
            SystemClock.sleep(600)
            device.tap(By.text(SIGN_IN_WITH_PASSKEY))

            device.await(By.textStartsWith(WELCOME_BACK))
            SystemClock.sleep(1_800)
            device.await(HOME)
            SystemClock.sleep(1_400)
            device.swipe(360, 1100, 360, 960, 18)
            SystemClock.sleep(1_000)
            device.swipe(360, 960, 360, 1100, 18)
            SystemClock.sleep(1_200)
        }
    }

    private fun record(
        fileName: String,
        journey: (UiDevice) -> Unit,
    ) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output =
            File(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: instrumentation.targetContext.externalCacheDir?.absolutePath
                    ?: "/sdcard",
                "onboarding-live-recording",
            ).apply { mkdirs() }
        val settings = LIVE_SETTINGS.mapValues { (key, _) -> device.executeShellCommand("settings get $key").trim() }
        try {
            LIVE_SETTINGS.forEach { (key, value) -> device.executeShellCommand("settings put $key $value") }
            // Recording starts before the app does, so the film opens on the table being dealt.
            ManagedOnboardingRecording(device, output, fileName).use {
                ActivityScenario.launch(MainActivity::class.java).use {
                    device.await(By.text(SEE_HOW_IT_WORKS), 25_000)
                    journey(device)
                }
            }
        } finally {
            settings.forEach { (key, previous) ->
                device.executeShellCommand(if (previous == "null") "settings delete $key" else "settings put $key $previous")
            }
        }
    }

    private fun UiDevice.await(
        selector: BySelector,
        timeoutMillis: Long = 15_000,
    ): BySelector {
        check(wait(Until.hasObject(selector), timeoutMillis)) { "Nothing on screen matched $selector" }
        return selector
    }

    private fun UiDevice.tap(selector: BySelector) {
        val center = checkNotNull(findObject(await(selector))) { "Nothing on screen matched $selector" }.visibleCenter
        check(click(center.x, center.y))
    }

    // The words a person reads on each screen. They are compared with the accessibility tree, so
    // a change to the copy has to be made here too.
    private companion object {
        const val SEE_HOW_IT_WORKS = "See how it works"
        const val MAKE_FIRST_ENTRY = "Add your first memory"
        const val SIGN_IN_TO_CLOUD = "I already have an account"
        const val SIGN_IN_WITH_PASSKEY = "Sign in with passkey"
        const val SAVE_ENTRY = "Save entry"
        const val WELCOME_BACK = "Welcome back"
        val TEXT_FIELD: BySelector = By.clazz("android.widget.EditText")
        val HOME: BySelector = By.desc("Account, backup and settings")
        val RETIRED_SCREENS = listOf("Your fire is lit", "Happy logging", "Your first entry is saved", "Welcome to LogDate")
        val LIVE_SETTINGS =
            mapOf(
                "global animator_duration_scale" to "1.0",
                "global transition_animation_scale" to "1.0",
                "global window_animation_scale" to "1.0",
                "system show_touches" to "1",
                "secure show_ime_with_hard_keyboard" to "1",
            )
    }
}

private object OnboardingFakeServerHealthChecker : ServerHealthChecker {
    override suspend fun checkServerHealth(baseUrl: String): Result<ServerHealthInfo> =
        Result.success(ServerHealthInfo(status = "healthy", version = "1.0.0"))
}

private object OnboardingFakeServerDiscoveryClient : ServerDiscoveryClient {
    override suspend fun discoverServer(serverOrigin: String): Result<ServerDescriptor> =
        Result.success(
            ServerDescriptor(
                serverOrigin = serverOrigin,
                apiBaseUrl = "${serverOrigin.trimEnd('/')}/api/v1",
                deploymentKind = DeploymentKind.FIRST_PARTY,
                displayName = "LogDate Cloud",
                handleDomain = "logdate.app",
                passkey = ServerPasskeyConfig(rpId = "logdate.app", rpName = "LogDate Cloud"),
                capabilities = listOf(ServerCapability.AUTH_PASSKEY, ServerCapability.SYNC_CONTENT, ServerCapability.SYNC_MEDIA),
                protocolFeatures = listOf(ServerProtocolFeature.CANONICAL_OWNER_BINDING_V1),
            ),
        )
}

private class OnboardingFakePasskeyAccountRepository : PasskeyAccountRepository {
    private val account = MutableStateFlow<LogDateAccount?>(null)
    private val authenticated = MutableStateFlow(false)
    override val currentAccount: StateFlow<LogDateAccount?> = account.asStateFlow()
    override val isAuthenticated: StateFlow<Boolean> = authenticated.asStateFlow()

    fun reset() {
        account.value = null
        authenticated.value = false
    }

    override suspend fun createAccountWithPasskey(request: AccountCreationRequest): Result<LogDateAccount> {
        val created = LogDateAccount(username = request.username, displayName = request.displayName)
        account.value = created
        authenticated.value = true
        return Result.success(created)
    }

    override suspend fun authenticateWithPasskey(
        username: String?,
        adoptLocalData: Boolean,
    ): Result<LogDateAccount> {
        val resolved =
            LogDateAccount(
                username = username ?: "willie",
                displayName = "Willie",
            )
        account.value = resolved
        authenticated.value = true
        return Result.success(resolved)
    }

    override suspend fun checkUsernameAvailability(username: String): Result<Boolean> = Result.success(true)

    override suspend fun signOut(): Result<Unit> {
        reset()
        return Result.success(Unit)
    }

    override suspend fun getCurrentAccount(): LogDateAccount? = account.value

    override suspend fun getAccountInfo(): Result<LogDateAccount> =
        account.value?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Not signed in"))

    override suspend fun refreshAuthentication(): Result<Unit> = Result.success(Unit)

    override suspend fun listPasskeys(): Result<List<PasskeyInfo>> = Result.success(emptyList())

    override suspend fun deletePasskey(credentialId: String): Result<Unit> = Result.success(Unit)

    override suspend fun createRestoreKey(): Result<Unit> = Result.success(Unit)

    override suspend fun signInWithRestoreKey(): Result<LogDateAccount> = Result.failure(IllegalStateException("Unavailable"))

    override suspend fun deleteRestoreKey(): Result<Unit> = Result.success(Unit)
}

private class ManagedOnboardingRecording(
    private val device: UiDevice,
    private val output: File,
    private val targetFileName: String,
) : AutoCloseable {
    private val token = Uuid.random().toString()
    private val remoteVideo = "/sdcard/onboarding-rec-$token.mp4"
    private val remotePid = "/sdcard/onboarding-rec-$token.pid"
    private val executor = Executors.newSingleThreadExecutor()
    private val reader: java.util.concurrent.Future<*>
    val file = File(output, targetFileName)

    init {
        val script = File(output, "record-$token.sh")
        script.writeText(
            "#!/system/bin/sh\necho \$\$ > $remotePid\n" +
                "exec screenrecord --size 720x1600 --bit-rate 4000000 --time-limit 120 $remoteVideo\n",
        )
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("sh ${script.absolutePath}")
        reader =
            executor.submit {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    File(output, "screenrecord-$token.log").outputStream().use { stream -> input.copyTo(stream) }
                }
            }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (device.executeShellCommand("cat $remotePid").trim().toIntOrNull() == null) {
            check(SystemClock.elapsedRealtime() < deadline) { "Screen recorder did not start" }
            SystemClock.sleep(40)
        }
    }

    override fun close() {
        try {
            device
                .executeShellCommand("cat $remotePid")
                .trim()
                .toIntOrNull()
                ?.let { pid ->
                    if (!reader.isDone && device.executeShellCommand("cat /proc/$pid/cmdline").contains(remoteVideo)) {
                        device.executeShellCommand("kill -2 $pid")
                    }
                }
            reader.get(35, TimeUnit.SECONDS)
            device.executeShellCommand("cp $remoteVideo ${file.absolutePath}")
            device.executeShellCommand("cp $remoteVideo /sdcard/$targetFileName")
            check(file.isFile && file.length() > 10_000) { "Original video was not retained at ${file.absolutePath}" }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            device.executeShellCommand("rm -f $remoteVideo $remotePid")
        }
    }
}
