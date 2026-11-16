package app.logdate.client.launch

import android.app.Application
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.media.audio.CrashSafeRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class RestoredApplicationRecordingRecoveryTest {
    @OptIn(ExperimentalResourceApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `restore retained Application salvages completed recording before database access and retry is idempotent`() =
        runBlocking {
            val folder = Files.createTempDirectory("restored-application-audio").toFile()
            val application =
                object : Application() {
                    init {
                        attachBaseContext(RuntimeEnvironment.getApplication())
                    }

                    override fun getFilesDir(): File = folder

                    override fun getApplicationContext(): android.content.Context = this
                }
            val final = File(folder, "audio_notes/synthetic-recording.m4a")
            val staged = CrashSafeRecording.backupExcludedFile(final)
            val audio = byteArrayOf(1, 2, 3, 4)
            staged.writeBytes(audio)
            assertTrue(CrashSafeRecording.markCompletedForRetry(staged))
            var databaseReached = false
            startKoin {
                modules(
                    module {
                        single<LogDateDatabase> {
                            databaseReached = true
                            assertTrue(final.isFile, "Completed recording must be recovered before database access")
                            error("Synthetic database boundary reached")
                        }
                    },
                )
            }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            try {
                val startup = ApplicationStartupCoordinator.start(application)
                assertFalse(withTimeout(5_000) { startup.awaitReady() })
                assertTrue(databaseReached, "Startup must reach the deliberate database failure")
                assertTrue(final.isFile, "Recording recovery did not reach the database boundary: ${startup.state.value}")
                assertContentEquals(audio, final.readBytes())
                assertFalse(staged.exists())
                val retry = ApplicationStartupCoordinator.start(application)
                assertSame(startup, retry)
                assertFalse(withTimeout(5_000) { retry.awaitReady() })
                assertContentEquals(audio, final.readBytes())
            } finally {
                Dispatchers.resetMain()
                stopKoin()
                setResourceReaderAndroidContext(RuntimeEnvironment.getApplication())
                folder.deleteRecursively()
            }
        }
}
