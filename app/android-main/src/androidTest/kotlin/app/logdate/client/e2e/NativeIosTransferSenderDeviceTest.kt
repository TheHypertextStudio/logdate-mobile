package app.logdate.client.e2e

import android.app.backup.BackupAgent
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.datastore.createAndroidMainDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class NativeIosTransferSenderDeviceTest {
    @Test
    fun profileOnlySourceProducesEncryptedJournalAndCompleteAppleBundle() =
        withFixture { context ->
            val database = context.getDatabasePath("logdate")
            assertFalse(database.exists())
            runBlocking {
                createAndroidMainDataStore(context).edit { it[stringPreferencesKey("display_name")] = "Alex" }
            }

            val profileDocuments = mutableListOf<String>()
            val emitted =
                runBlocking {
                    send(
                        context,
                        onEmit = { file ->
                            if (file.path.endsWith("/data/profile.json")) profileDocuments += file.readText()
                        },
                    )
                }

            assertTrue(database.isFile, "A profile-only source must gain an encrypted journal")
            val owner =
                assertNotNull(
                    runBlocking {
                        createAndroidMainDataStore(context).data.first()[stringPreferencesKey("identity.canonical_owner_id")]
                    },
                )
            Uuid.parse(owner)
            val passphrase = assertNotNull(PassphraseBackupStore(context).read())
            assertEquals(32, passphrase.size)
            passphrase.fill(0)
            assertTrue(emitted.any { it.endsWith("/archive/manifest.json") })
            assertTrue(emitted.any { it.endsWith("/.logdate-os-private/custody-v1.json") })
            assertEquals(1, profileDocuments.size)
            assertTrue(profileDocuments.single().contains("Alex"))
            assertFalse(File(context.filesDir, "logdate-os-transfer").exists(), "Private staging must be removed after delivery")
        }

    @Test
    fun emptySourceEmitsNothingWithoutCreatingJournal() =
        withFixture { context ->
            val emitted = runBlocking { send(context) }
            assertTrue(emitted.isEmpty())
            assertFalse(context.getDatabasePath("logdate").exists())
            assertFalse(File(context.filesDir, "logdate-os-transfer").exists())
        }

    @Test
    fun retainedJournalBlocksOwnerCreationForProfileOnlySource() =
        withFixture { context ->
            runBlocking {
                createAndroidMainDataStore(context).edit { it[stringPreferencesKey("display_name")] = "Alex" }
            }
            val retained = File(context.filesDir, "native_journal_transfer/current")
            assertTrue(retained.mkdirs())
            File(retained, "incomplete").writeText("Existing recovery custody")

            val emitted = mutableListOf<String>()
            val failure = runCatching { runBlocking { send(context, emitted) } }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(emitted.isEmpty())
            assertTrue(File(retained, "incomplete").isFile)
            assertEquals(
                null,
                runBlocking { createAndroidMainDataStore(context).data.first()[stringPreferencesKey("identity.canonical_owner_id")] },
            )
            assertFalse(context.getDatabasePath("logdate").exists())
        }

    @Test
    fun unsupportedSettingFailsClosedWithoutCreatingJournal() =
        withFixture { context ->
            runBlocking {
                createAndroidMainDataStore(context).edit { it[stringPreferencesKey("theme_preference")] = "dark" }
            }
            val emitted = mutableListOf<String>()
            val failure = runCatching { runBlocking { send(context, emitted) } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(emitted.isEmpty())
            assertFalse(context.getDatabasePath("logdate").exists())
            assertFalse(File(context.filesDir, "logdate-os-transfer").exists())
        }

    private fun withFixture(block: (Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Emulator required" }
        val base = instrumentation.targetContext
        check(base.packageName.endsWith(".debug")) { "Isolated debug application required" }
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "ios-sender-").toFile()
        val files = File(root, "files").apply { check(mkdirs()) }
        val noBackup = File(root, "no-backup").apply { check(mkdirs()) }
        val context =
            object : ContextWrapper(base) {
                override fun getFilesDir() = files

                override fun getNoBackupFilesDir() = noBackup

                override fun getDatabasePath(name: String) = File(name).takeIf(File::isAbsolute) ?: File(root, "database/$name")

                override fun getApplicationContext() = this
            }
        try {
            block(context)
        } finally {
            check(root.deleteRecursively())
        }
    }

    private suspend fun send(
        context: Context,
        emitted: MutableList<String> = mutableListOf(),
        onEmit: (File) -> Unit = {},
    ): List<String> {
        val snapshotClass = Class.forName("app.logdate.client.data.restore.NativeJournalTransferSnapshot")
        val snapshot = snapshotClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val senderClass = Class.forName("app.logdate.client.data.restore.NativeIosTransferSender")
        val sender =
            senderClass
                .getDeclaredConstructor(Context::class.java, snapshotClass)
                .apply { isAccessible = true }
                .newInstance(context, snapshot)
        val method =
            senderClass
                .getDeclaredMethod("send", Int::class.javaPrimitiveType, Function1::class.java, Continuation::class.java)
                .apply { isAccessible = true }
        val flags = BackupAgent.FLAG_CROSS_PLATFORM_DATA_TRANSFER_IOS or BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
        suspendCoroutine<Unit> { continuation ->
            try {
                val callback: (File) -> Unit = { file ->
                    emitted += file.path
                    onEmit(file)
                }
                val result = method.invoke(sender, flags, callback, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
            } catch (failure: InvocationTargetException) {
                continuation.resumeWithException(checkNotNull(failure.cause))
            }
        }
        return emitted
    }
}
