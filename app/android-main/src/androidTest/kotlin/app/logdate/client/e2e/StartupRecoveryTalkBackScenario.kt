package app.logdate.client.e2e

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.MainActivity
import app.logdate.client.database.DatabaseStartupMonitor
import app.logdate.client.database.DatabaseStartupState
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.getDatabaseBuilder
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.retainedJournalWorkspace
import app.logdate.client.device.storage.SecureStorage
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.isAppUnlocked
import app.logdate.shared.model.user.AppSecurityLevel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/** Exercises normal startup; the late portable-key delivery is synthetic, not a provider transfer. */
internal class StartupRecoveryTalkBackScenario(
    private val automation: UiAutomation,
    private val driveAction: (scene: String, action: String, activated: () -> Boolean) -> Unit,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val koin get() = GlobalContext.get()
    private val noteText = "Private restored TalkBack note"
    private val journalTitle = "Private restored TalkBack journal"

    fun run(fontScale: Float) =
        runBlocking {
            check(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Use a fresh managed security fixture" }
            val file = context.getDatabasePath("logdate")
            check(!file.exists()) { "Use a fresh managed device; existing journals must remain untouched" }
            val storage = koin.get<SecureStorage>()
            assertNull(storage.getString("db_encryption_key"))
            val backup = PassphraseBackupStore(context)
            check(!backup.hasBackup()) { "Do not replace retained recovery material" }
            val key = ByteArray(32) { (it + 31).toByte() }
            seedJournal(key)
            val originalDatabase = file.readBytes()
            val store = createAndroidMainDataStore(context)
            val originalPreferences = store.data.first()
            val preferences = koin.get<LogdatePreferencesDataSource>()
            var scenario: ActivityScenario<MainActivity>? = null
            try {
                preferences.setShouldHideOnboarding(true)
                preferences.setShouldShowBiometric(true)
                PostRestoreDetector(context).apply {
                    beginNativeRestoreSession()
                    markNativeRestorePayloadReceived(databaseDelivered = true)
                    markNativePrivacyPayloadReceived()
                    recordRestoreCompleted()
                }
                scenario = ActivityScenario.launch(MainActivity::class.java)
                val startup = applicationStartupState()
                await { startup.value?.javaClass?.simpleName == "Failed" && appWindowHas("Finish restoring your journal") }
                assertTrue(appWindowHas("Try again"), "A late key must be retryable without moving the journal")
                assertTrue(appWindowHas("Open recovery options"), "A failed transfer must retain its isolated recovery path")
                scenario.onActivity { assertEquals(fontScale, it.resources.configuration.fontScale) }
                assertLockedContentHidden()
                val initialFailure = startup.value
                driveAction("missing-key", "Try again") {
                    startup.value !== initialFailure && startup.value?.javaClass?.simpleName == "Failed"
                }
                assertTrue(koin.get<DatabaseStartupMonitor>().state.value is DatabaseStartupState.RecoveryRequired)
                assertTrue(
                    originalDatabase.contentEquals(file.readBytes()),
                    "An unsuccessful retry cannot replace the encrypted journal",
                )
                assertFalse(backup.hasBackup(), "A missing key cannot be replaced with a new one")
                assertNull(storage.getString("db_encryption_key"))
                assertNull(retainedJournalWorkspace(context).current(), "Retry must not detach a protected journal")
                verifyJournal(file, key)

                // Simulate Android delivering the original portable key after the first attempt.
                backup.write(key)
                driveAction("late-key", "Try again") { startup.value?.javaClass?.simpleName == "Ready" }
                assertTrue(koin.get<DatabaseStartupMonitor>().state.value is DatabaseStartupState.Ready)
                assertTrue(backup.read()!!.contentEquals(key), "Recovery must retain the original key")
                assertNull(retainedJournalWorkspace(context).current(), "A late key must open the original journal in place")
                verifyJournal(file, key)
                await { automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
                systemKey(KeyEvent.KEYCODE_BACK)
                await { appWindowHas("Set up device security") }
                assertLocked(scenario)
                driveAction("app-lock", "Set up device security") {
                    automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
                }
                systemKey(KeyEvent.KEYCODE_BACK)
                await { appWindowHas("Set up device security") }
                assertLocked(scenario)
                systemKey(KeyEvent.KEYCODE_HOME)
                await { automation.rootInActiveWindow?.packageName?.toString() != context.packageName }
                instrumentation.runOnMainSync {
                    context.startActivity(
                        Intent(
                            context,
                            MainActivity::class.java,
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
                await { appWindowHas("Set up device security") }
                assertLocked(scenario)
                driveAction("app-lock-resumed", "Set up device security") {
                    automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
                }
                systemKey(KeyEvent.KEYCODE_BACK)
                await { appWindowHas("Set up device security") }
                assertLocked(scenario)
                verifyJournal(file, key)
            } finally {
                restoreFixtureState(
                    { scenario?.close() },
                    {
                        store.edit { current ->
                            val security = LogdatePreferencesDataSource.SECURITY_LEVEL
                            val onboarded = LogdatePreferencesDataSource.IS_ONBOARDED
                            val timestamp = LogdatePreferencesDataSource.ONBOARDED_TIMESTAMP
                            originalPreferences[security]?.let { current[security] = it } ?: current.remove(security)
                            originalPreferences[onboarded]?.let { current[onboarded] = it } ?: current.remove(onboarded)
                            originalPreferences[timestamp]?.let { current[timestamp] = it } ?: current.remove(timestamp)
                        }
                        val restored = store.data.first()
                        assertEquals(
                            originalPreferences[LogdatePreferencesDataSource.SECURITY_LEVEL],
                            restored[LogdatePreferencesDataSource.SECURITY_LEVEL],
                        )
                        assertEquals(
                            originalPreferences[LogdatePreferencesDataSource.IS_ONBOARDED],
                            restored[LogdatePreferencesDataSource.IS_ONBOARDED],
                        )
                        assertEquals(
                            originalPreferences[LogdatePreferencesDataSource.ONBOARDED_TIMESTAMP],
                            restored[LogdatePreferencesDataSource.ONBOARDED_TIMESTAMP],
                        )
                    },
                )
            }
        }

    private suspend fun seedJournal(key: ByteArray) {
        val database = getRoomDatabase(getDatabaseBuilder(context, key.copyOf()), driver = null)
        try {
            val journal = Uuid.random()
            val note = Uuid.random()
            val at = Clock.System.now()
            database.journalDao().create(JournalEntity(journal, journalTitle, "", at, at))
            database.textNoteDao().addNote(TextNoteEntity(noteText, note, at, at))
            database.journalNotesDao().addNoteToJournal(journal, note)
        } finally {
            database.close()
        }
    }

    private fun applicationStartupState(): StateFlow<*> {
        val application = context.applicationContext
        val startup =
            application.javaClass
                .getDeclaredField("startup")
                .apply { isAccessible = true }
                .get(application)
        return startup.javaClass
            .getDeclaredField("state")
            .apply { isAccessible = true }
            .get(startup) as StateFlow<*>
    }

    private fun verifyJournal(
        file: File,
        key: ByteArray,
    ) {
        CipherDatabase.openDatabase(file.absolutePath, key.copyOf(), null, CipherDatabase.OPEN_READONLY, null, null).use { database ->
            database.rawQuery("SELECT content FROM text_notes", emptyArray<String>()).use {
                assertTrue(it.moveToFirst())
                assertEquals(noteText, it.getString(0))
                assertFalse(it.moveToNext())
            }
        }
    }

    private suspend fun assertLocked(scenario: ActivityScenario<MainActivity>) {
        lateinit var viewModel: AppViewModel
        scenario.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        withTimeout(30_000) {
            val state = viewModel.uiState.filterIsInstance<GlobalAppUiLoadedState>().first()
            assertFalse(state.isAppUnlocked)
        }
        assertEquals(
            AppSecurityLevel.BIOMETRIC,
            koin
                .get<LogdatePreferencesDataSource>()
                .userData
                .first()
                .securityLevel,
        )
        assertLockedContentHidden()
    }

    private fun assertLockedContentHidden() {
        val roots =
            (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
                .filter { it.packageName?.toString() == context.packageName }
        assertTrue(roots.isNotEmpty(), "A visible LogDate window is required for privacy acceptance")
        val text = roots.joinToString(" ") { windowText(it) }
        assertFalse(text.contains(noteText))
        assertFalse(text.contains(journalTitle))
    }

    private fun appWindowHas(text: String): Boolean {
        val root = automation.rootInActiveWindow ?: return false
        return root.packageName?.toString() == context.packageName && windowText(root).contains(text)
    }

    private fun systemKey(code: Int) {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0)
            assertTrue(automation.injectInputEvent(event, true), "System key $code action $action must be delivered")
        }
    }

    private fun windowText(node: android.view.accessibility.AccessibilityNodeInfo): String =
        buildString {
            append(node.text?.toString().orEmpty()).append(' ')
            append(node.contentDescription?.toString().orEmpty()).append(' ')
            for (index in 0 until node.childCount) node.getChild(index)?.let { append(windowText(it)) }
        }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Actual startup recovery did not reach the expected native window" }
            SystemClock.sleep(100)
        }
    }
}
