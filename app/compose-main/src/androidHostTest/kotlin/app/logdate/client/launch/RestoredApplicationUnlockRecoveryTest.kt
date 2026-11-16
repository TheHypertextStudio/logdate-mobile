package app.logdate.client.launch

import android.app.Application
import android.app.job.JobScheduler
import android.content.Intent
import android.os.UserManager
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.restore.PostRestoreDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class RestoredApplicationUnlockRecoveryTest {
    @OptIn(ExperimentalResourceApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `retained application waits for verified Android unlock before credential storage access`() =
        runBlocking {
            val base = RuntimeEnvironment.getApplication()
            val platform = shadowOf(base.getSystemService(UserManager::class.java))
            val broadcasts = shadowOf(base)
            // Config.NONE omits the permission AndroidX merges into the installed app manifest.
            broadcasts.grantPermissions("${base.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            val unlock = Intent(Intent.ACTION_USER_UNLOCKED)
            val folder = Files.createTempDirectory("restored-application-unlock").toFile()
            val storageReads = AtomicInteger()
            val databaseReads = AtomicInteger()
            val application =
                object : Application() {
                    init {
                        attachBaseContext(base)
                    }

                    override fun getFilesDir(): File = folder.also { storageReads.incrementAndGet() }

                    override fun getNoBackupFilesDir(): File =
                        File(folder, "private").also {
                            storageReads.incrementAndGet()
                            it.mkdirs()
                        }

                    override fun getApplicationContext(): android.content.Context = this
                }
            val detector = PostRestoreDetector(application)
            detector.beginNativeRestoreSession()
            detector.markNativePrivacyPayloadReceived()
            val generation = requireNotNull(detector.nativeRestoreSessionId())
            val delivery = "2f18404e-e9f5-434d-8b03-2f04f09ac523"
            val request = """{"delivery":"$delivery","generation":"$generation","scope":"${"a".repeat(64)}"}"""
            File(folder, "private/native-account-recovery.json").writeText("""{"request":$request}""")
            File(folder, "private/.logdate_account_admission_$generation").writeText(delivery)
            storageReads.set(0)
            startKoin {
                modules(
                    module {
                        single<LogDateDatabase> {
                            databaseReads.incrementAndGet()
                            error("Synthetic database boundary reached")
                        }
                    },
                )
            }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            platform.setUserUnlocked(false)
            var startup: ApplicationStartup? = null
            try {
                val active = ApplicationStartupCoordinator.start(application)
                startup = active
                withTimeout(5_000) {
                    while (
                        !broadcasts.hasReceiverForIntent(unlock) &&
                        storageReads.get() == 0 &&
                        databaseReads.get() == 0 &&
                        active.state.value is ApplicationStartupState.Loading
                    ) {
                        delay(10)
                    }
                }
                val state = active.state.value
                assertEquals(0, storageReads.get(), "Locked startup must not read credential-backed files")
                assertEquals(0, databaseReads.get(), "Locked startup must not open the database")
                if (state is ApplicationStartupState.Failed) throw AssertionError("Locked startup failed before unlocking", state.cause)
                assertTrue(broadcasts.hasReceiverForIntent(unlock), "Locked startup must await the platform unlock signal")
                assertEquals(ApplicationStartupState.Loading, active.state.value)

                base.sendBroadcast(unlock)
                ShadowLooper.idleMainLooper()
                assertEquals(ApplicationStartupState.Loading, active.state.value)
                assertEquals(0, storageReads.get(), "An unlock broadcast cannot override a still-locked Android user")
                assertEquals(0, databaseReads.get())

                platform.setUserUnlocked(true)
                base.sendBroadcast(unlock)
                ShadowLooper.idleMainLooper()
                assertFalse(withTimeout(5_000) { active.awaitReady() })
                assertTrue(storageReads.get() > 0)
                assertEquals(1, databaseReads.get())
                val recovery = base.getSystemService(JobScheduler::class.java).allPendingJobs.single()
                assertEquals(
                    request,
                    recovery.extras.getString("request"),
                    "Unlocked fallback must resume the original retained account request",
                )
                assertFalse(broadcasts.hasReceiverForIntent(unlock), "The completed startup must release its unlock receiver")
            } finally {
                platform.setUserUnlocked(true)
                base.sendBroadcast(unlock)
                ShadowLooper.idleMainLooper()
                try {
                    startup?.let { withTimeout(5_000) { it.awaitReady() } }
                } finally {
                    Dispatchers.resetMain()
                    stopKoin()
                    setResourceReaderAndroidContext(base)
                    folder.deleteRecursively()
                }
            }
        }
}
