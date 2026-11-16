package app.logdate.client.e2e

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.client.database.DatabaseStartupMonitor
import app.logdate.client.database.DatabaseStartupState
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.device.restore.NativeRestorePrivacyMetadataRequiredException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The host supplies an isolated partial native delivery, then its original preferences. */
@RunWith(AndroidJUnit4::class)
class NativeRestorePrivacyRuntimeTest {
    @Test
    fun retainedJournalRequiresItsPrivacyMetadata() =
        runBlocking {
            val mode = InstrumentationRegistry.getArguments().getString("restorePrivacyMode")
            assumeTrue("requires the partial-delivery host sequence", mode == "blocked" || mode == "resolved")
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
            val context = ApplicationProvider.getApplicationContext<Context>()
            require(context.packageName.endsWith(".debug"))
            val databaseFile = context.getDatabasePath("logdate")
            assertTrue(databaseFile.isFile && databaseFile.length() > 0)
            val keyStore = PassphraseBackupStore(context)
            val key = assertNotNull(keyStore.read())
            val koin = GlobalContext.get()
            if (mode == "resolved") {
                val database = koin.get<LogDateDatabase>()
                assertTrue(database.textNoteDao().getAll().isNotEmpty(), "Original entries must remain readable")
                assertIs<DatabaseStartupState.Ready>(koin.get<DatabaseStartupMonitor>().state.value)
            } else {
                val error = runCatching { koin.get<LogDateDatabase>() }.exceptionOrNull()
                val chain = generateSequence(error) { it.cause }.take(20).map { it.javaClass.simpleName }.toList()
                assertTrue(
                    generateSequence(error) { it.cause }.take(20).any { it is NativeRestorePrivacyMetadataRequiredException },
                    "Every database consumer must be refused unknown restored privacy; " +
                        "causes=$chain; state=${koin.get<DatabaseStartupMonitor>().state.value.javaClass.simpleName}",
                )
                assertIs<DatabaseStartupState.RecoveryRequired>(koin.get<DatabaseStartupMonitor>().state.value)
                ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    val startup = startupInstance(context)
                    val state = startupState(startup)
                    awaitPrivacyFailure(state)
                    requirePrivacyScreen(context, device)
                    assertFalse(device.hasObject(By.text("Write your first entry")))
                    val previous = state.value
                    withRetryDelay(startup) {
                        device.findObject(By.text("Try again")).click()
                        awaitPrivacyFailure(state, previous)
                        requirePrivacyScreen(context, device)
                    }
                    val beforeRecreate = state.value
                    activity.recreate()
                    awaitPrivacyFailure(state, beforeRecreate)
                    requirePrivacyScreen(context, device)
                    assertFalse(device.hasObject(By.text("Write your first entry")))
                }
            }
            assertTrue(databaseFile.isFile && databaseFile.length() > 0)
            assertContentEquals(key, keyStore.read(), "Privacy recovery must retain the journal key")
        }

    private fun requirePrivacyScreen(
        context: Context,
        device: UiDevice,
    ) {
        try {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var visible = false
            while (!visible && SystemClock.elapsedRealtime() < deadline) {
                dismissNativeEmulatorPlatformDialog(device, (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 5_000))
                visible =
                    device.wait(
                        Until.hasObject(By.textContains("privacy settings weren’t restored")),
                        (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 500),
                    )
            }
            assertTrue(visible, "Protected privacy recovery state must be visible and reachable")
        } catch (failure: Throwable) {
            runCatching { device.takeScreenshot(File(context.noBackupFilesDir, "native-privacy-failure.png")) }
            runCatching { device.dumpWindowHierarchy(File(context.noBackupFilesDir, "native-privacy-failure.xml")) }
            throw failure
        }
    }

    private suspend fun awaitPrivacyFailure(
        state: StateFlow<*>,
        previous: Any? = null,
    ) {
        val started = SystemClock.elapsedRealtime()
        val completed =
            withTimeoutOrNull(45_000) {
                state.first { it !== previous && it?.javaClass?.simpleName != "Loading" }
            }
        val current = completed ?: state.value
        val kind = current?.javaClass?.simpleName ?: "Absent"
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("PRIVACY_STARTUP_STATE", kind)
                putLong("PRIVACY_STARTUP_WAIT_MS", SystemClock.elapsedRealtime() - started)
            },
        )
        val failure = assertNotNull(completed, "Privacy recovery retry did not finish; state=$kind")
        assertEquals("Failed", kind, "Unknown restored privacy must keep startup protected")
        val cause =
            failure.javaClass
                .getDeclaredField("cause")
                .apply { isAccessible = true }
                .get(failure) as Throwable
        val chain = generateSequence(cause) { it.cause }.take(20).toList()
        assertTrue(
            chain.any { it is NativeRestorePrivacyMetadataRequiredException },
            "Retry changed recovery reason: ${chain.map { it.javaClass.simpleName }}",
        )
    }

    private fun startupInstance(context: Context): Any {
        val application = context.applicationContext
        val field = application.javaClass.declaredFields.firstOrNull { it.name == "startup" }
        if (field != null) return assertNotNull(field.apply { isAccessible = true }.get(application))
        val coordinator = Class.forName("app.logdate.client.launch.ApplicationStartupCoordinator")
        val startups = coordinator.getDeclaredField("retainedApplicationStartups").apply { isAccessible = true }.get(null) as Map<*, *>
        return synchronized(startups) { assertNotNull(startups[application]) }
    }

    private fun startupState(startup: Any): StateFlow<*> =
        startup.javaClass
            .getDeclaredField("state")
            .apply { isAccessible = true }
            .get(startup) as StateFlow<*>

    private suspend fun withRetryDelay(
        startup: Any,
        block: suspend () -> Unit,
    ) {
        val millis = InstrumentationRegistry.getArguments().getString("privacyRetryDelayMs")?.toLong() ?: 0
        require(millis in 0..25_000)
        if (millis == 0L) return block()
        val field = startup.javaClass.getDeclaredField("initialize").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val original = field.get(startup) as suspend () -> Unit
        val delayed: suspend () -> Unit = {
            delay(millis)
            original()
        }
        if (millis > 0) field.set(startup, delayed)
        try {
            block()
        } finally {
            if (millis > 0) field.set(startup, original)
        }
    }
}
