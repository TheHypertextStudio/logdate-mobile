package app.logdate.client.e2e

import android.app.backup.BackupAgent
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.device.crypto.AccountIdentityBackup
import app.logdate.client.device.crypto.AndroidIdentityKeyBackupStore
import app.logdate.client.device.crypto.MediaIdentityBackup
import app.logdate.client.device.restore.NativeBackupAgent
import app.logdate.client.device.restore.NativeJournalReplayCustody
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.PostRestoreType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

@RunWith(AndroidJUnit4::class)
class NativeModernRestoreFormatTest {
    @Test
    fun iosAndVersionedInputsPreserveEveryNativeDestination() =
        withFixture {
            val before = retainedState()
            val formats = listOf(8 to "", 10 to "", 0 to "private-source-version", 0 to " ")
            formats.forEach { (flags, version) ->
                destinations().forEach { destination ->
                    val agent = agent()
                    rejectModern(agent, destination, flags, version)
                    agent.onRestoreFinished()
                    agent.onDestroy()
                    assertEquals(before, retainedState())
                }
            }
            verifyRetainedJournal()
            assertEquals(0, accountRecoveries)
        }

    @Test
    fun rejectedDeliveryCannotCompleteOrFallBackAfterAgentRecreation() =
        withFixture {
            val before = retainedState()
            val first = agent()
            rejectModern(first, database, 8, "")
            rejectModern(first, preferencesFile, 2, "")
            rejectLegacy(first, keyFile)
            first.onRestoreFinished()
            first.onDestroy()
            val recreated = agent()
            rejectLegacy(recreated, preferencesFile)
            recreated.onRestoreFinished()
            rejectModern(recreated, database, 2, "")
            recreated.onDestroy()
            val completionOnly = agent()
            completionOnly.onRestoreFinished()
            rejectModern(completionOnly, database, 2, "")
            assertEquals(before, retainedState())
            verifyRetainedJournal()
            assertEquals(0, accountRecoveries)
        }

    @Test
    fun rejectedFirstDeliveryCreatesNoRecoveryReceipts() =
        withFixture(completeDelivery = false) {
            val before = retainedState()
            val first = agent()
            rejectModern(first, preferencesFile, 0, "unrecognized-version")
            first.onRestoreFinished()
            first.onDestroy()
            agent().onRestoreFinished()
            assertEquals(before, retainedState())
            assertEquals(0, accountRecoveries)
            verifyRetainedJournal()
        }

    @Test
    fun explicitNativeTransportFlagsRetainAnAlreadyAcceptedJournal() {
        for (flags in listOf(0, 1, 2, 3)) {
            withFixture(transportFlags = flags) {
                val rejected = agent()
                rejectModern(rejected, database, 8, "")
                rejected.onRestoreFinished()
                rejected.onDestroy()
                val updated = File(folder, "updated-journal")
                createJournal(updated, "Accepted native journal")
                val identity = assertNotNull(PostRestoreDetector(context).capturePrivacyRepairIdentity())
                val native = agent()
                deliverModern(native, database, updated, flags, "")
                deliverModern(native, preferencesFile, incoming, flags, "")
                native.onRestoreFinished()
                verifyRetainedJournal()
                assertEquals(PostRestoreType.CONTENT_RESTORED, PostRestoreDetector(context).detect(true))
                assertEquals(identity, PostRestoreDetector(context).capturePrivacyRepairIdentity())
                assertEquals(0, accountRecoveries)
                val retained = NativeJournalReplayCustody(context).sealedPayloads()
                assertContentEquals(updated.readBytes(), retained.single { it.path == "database/logdate" }.file.readBytes())
                assertContentEquals(incoming.readBytes(), retained.single { it.path == "files/logdate.preferences_pb" }.file.readBytes())
                runBlocking {
                    assertEquals("BIOMETRIC", createAndroidMainDataStore(context).data.first()[stringPreferencesKey("security_level")])
                    assertEquals("destination-device", createAndroidMainDataStore(context).data.first()[stringPreferencesKey("device.id")])
                }
            }
        }
    }

    @Test
    fun interruptedMarkersAndFailedRejectionWritesPreserveRetainedContent() {
        for (suffix in listOf("", ".new", ".bak")) {
            withFixture {
                File(context.noBackupFilesDir, ".logdate_native_restore_format_rejected$suffix").writeBytes(byteArrayOf())
                val before = retainedState()
                val recreated = agent()
                recreated.onRestoreFinished()
                rejectLegacy(recreated, database)
                assertEquals(before, retainedState())
                assertEquals(0, accountRecoveries)
                val identity = assertNotNull(PostRestoreDetector(context).capturePrivacyRepairIdentity())
                val native = agent()
                deliverModern(native, preferencesFile, incoming, 2, "")
                native.onRestoreFinished()
                verifyRetainedJournal()
                assertEquals(identity, PostRestoreDetector(context).capturePrivacyRepairIdentity())
                assertEquals(0, accountRecoveries)
                assertContentEquals(
                    incoming.readBytes(),
                    NativeJournalReplayCustody(
                        context,
                    ).sealedPayloads().single { it.path == "files/logdate.preferences_pb" }.file.readBytes(),
                )
            }
        }
        withFixture {
            val installation = File(context.noBackupFilesDir, ".logdate_installation_id")
            val installationId = Uuid.random().toString()
            installation.writeText(installationId)
            val detector = PostRestoreDetector(context)
            val tickets =
                listOf(
                    detector.pendingContentRecoveryId(),
                    detector.pendingAccountRecoveryId(),
                    detector.pendingMediaQuarantineId(),
                )
            val before = retainedState()
            File(folder, "marker-storage-blocker").writeText("Blocks the marker parent")
            markerStorageBlocked = true
            val rejected = agent()
            rejectModern(rejected, database, 8, "")
            markerStorageBlocked = false
            rejectModern(rejected, database, 2, "")
            rejectLegacy(rejected, preferencesFile)
            rejected.onRestoreFinished()
            rejected.onDestroy()
            assertEquals(before, retainedState())
            assertEquals(0, accountRecoveries)
            // No marker could persist; a recreated agent may retry the existing valid recovery.
            agent().onRestoreFinished()
            assertEquals(
                tickets,
                listOf(
                    detector.pendingContentRecoveryId(),
                    detector.pendingAccountRecoveryId(),
                    detector.pendingMediaQuarantineId(),
                ),
            )
            assertEquals(installationId, installation.readText())
            assertEquals(1, accountRecoveries)
            val native = agent()
            deliverModern(native, preferencesFile, incoming, 2, "")
            native.onRestoreFinished()
            assertEquals(installationId, installation.readText())
            verifyRetainedJournal()
            assertEquals(1, accountRecoveries)
        }
    }

    private fun withFixture(
        completeDelivery: Boolean = true,
        transportFlags: Int = 2,
        block: Fixture.() -> Unit,
    ) {
        InstrumentationRegistry.getArguments().getString("nativeRestoreRequiredSdkFull")?.let { required ->
            assertTrue(Build.VERSION.SDK_INT >= 36)
            assertEquals(required.toInt(), Build.VERSION.SDK_INT_FULL)
        }
        if (Build.VERSION.SDK_INT < 36 || Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return
        require(Build.HARDWARE in setOf("ranchu", "goldfish"))
        Fixture(completeDelivery, transportFlags).use { it.block() }
    }

    private class Fixture(
        completeDelivery: Boolean,
        transportFlags: Int,
    ) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val root =
            instrumentation.targetContext
                .createDeviceProtectedStorageContext()
                .getDir("native-restore-probe", Context.MODE_PRIVATE)
        val folder = File(root, "format-rejection-${Uuid.random()}").apply { check(mkdirs()) }
        var markerStorageBlocked = false
        val context =
            object : ContextWrapper(instrumentation.context) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir(): File = File(folder, "files").apply { mkdirs() }

                override fun getCacheDir(): File = File(folder, "cache").apply { mkdirs() }

                override fun getNoBackupFilesDir(): File =
                    if (markerStorageBlocked) File(folder, "marker-storage-blocker/private") else File(folder, "private").apply { mkdirs() }

                override fun getDatabasePath(name: String): File = File(folder, "databases/$name")
            }
        val database = context.getDatabasePath("logdate")
        val keyFile = File(context.filesDir, "db_recovery/passphrase_backup")
        val preferencesFile = File(context.filesDir, "logdate.preferences_pb")
        val incoming = File(folder, "incoming")
        private val originalKey = ByteArray(32) { (it + 11).toByte() }
        private val identityKey = ByteArray(32) { (it + 43).toByte() }
        private val identityBinding = Base64.encode(MessageDigest.getInstance("SHA-256").digest(identityKey))
        private val mediaKey = ByteArray(32) { 91 }
        private val agents = mutableListOf<NativeBackupAgent>()
        var accountRecoveries = 0
            private set

        init {
            System.loadLibrary("sqlcipher")
            val sourceRoot = File(folder, "seed-source")
            val source =
                object : ContextWrapper(context) {
                    override fun getFilesDir(): File = File(sourceRoot, "files").apply { mkdirs() }

                    override fun getDatabasePath(name: String): File = File(sourceRoot, "databases/$name")
                }
            createJournal(source.getDatabasePath("logdate"), "Retained encrypted journal")
            PassphraseBackupStore(source).write(originalKey)
            runBlocking {
                AndroidIdentityKeyBackupStore(source).writeAccountIdentity(
                    AccountIdentityBackup("native-test-owner", identityKey, mediaKey),
                )
                AndroidIdentityKeyBackupStore(source).writeMediaIdentity(
                    MediaIdentityBackup(identityBinding, mediaKey),
                )
                createAndroidMainDataStore(source).edit {
                    it[stringPreferencesKey("identity.canonical_owner_id")] = "native-test-owner"
                    it[stringPreferencesKey("identity.canonical_owner_bound")] = "true"
                    it[stringPreferencesKey("security_level")] = "BIOMETRIC"
                }
            }
            val seedFiles =
                mapOf(database to source.getDatabasePath("logdate")) +
                    source.filesDir
                        .walkTopDown()
                        .filter { it.isFile }
                        .associate { File(context.filesDir, it.relativeTo(source.filesDir).path) to it }
            if (completeDelivery) {
                val seed = object : NativeBackupAgent() {}
                attach(seed)
                seedFiles.forEach { (destination, payload) -> deliverModern(seed, destination, payload, transportFlags, "") }
                seed.onRestoreFinished()
                seed.onDestroy()
                val identity = assertNotNull(PostRestoreDetector(context).capturePrivacyRepairIdentity())
                assertEquals(identity.accountDeliveryId, identity.contentDeliveryId)
                assertEquals(PostRestoreType.CONTENT_RESTORED, PostRestoreDetector(context).detect(true))
                assertNotNull(PostRestoreDetector(context).pendingAccountRecoveryId())
                assertNotNull(PostRestoreDetector(context).pendingMediaQuarantineId())
            } else {
                seedFiles.forEach { (destination, payload) ->
                    destination.parentFile!!.mkdirs()
                    payload.copyTo(destination)
                }
            }
            File(context.filesDir, "media/retained-original").apply {
                parentFile?.mkdirs()
                writeText("Retained original media")
            }
            runBlocking {
                createAndroidMainDataStore(context).edit { it[stringPreferencesKey("device.id")] = "destination-device" }
            }
            incoming.writeBytes(preferencesFile.readBytes())
        }

        fun agent(): NativeBackupAgent =
            object : NativeBackupAgent() {
                override suspend fun restoreAccount(preferences: DataStore<Preferences>) {
                    accountRecoveries++
                }
            }.also { attach(it) }

        private fun attach(agent: NativeBackupAgent) {
            ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }
                .invoke(agent, context)
            agents += agent
        }

        fun destinations(): List<File> =
            listOf(
                database,
                keyFile,
                preferencesFile,
                File(context.filesDir, "identity_recovery/account_key_backup"),
                File(context.filesDir, "identity_recovery/media_key_backup"),
                File(context.filesDir, "media/retained-original"),
            )

        fun retainedState(): Map<String, List<Byte>> =
            listOf("files", "private", "databases")
                .flatMap { path ->
                    File(folder, path)
                        .walkTopDown()
                        .filter { it.isFile && !it.name.startsWith(".logdate_native_restore_format_rejected") }
                        .map { it.relativeTo(folder).invariantSeparatorsPath to it.readBytes().toList() }
                        .toList()
                }.toMap()

        fun rejectModern(
            agent: NativeBackupAgent,
            destination: File,
            flags: Int,
            version: String,
        ) {
            val failure =
                assertFailsWith<InvocationTargetException> {
                    deliverModern(agent, destination, incoming, flags, version)
                }
            assertEquals("Native restore payload format is not supported", assertIs<IOException>(failure.targetException).message)
        }

        fun rejectLegacy(
            agent: NativeBackupAgent,
            destination: File,
        ) {
            ParcelFileDescriptor.open(incoming, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val failure =
                    assertFailsWith<IOException> {
                        agent.onRestoreFile(descriptor, incoming.length(), destination, BackupAgent.TYPE_FILE, 0x180L, 1L)
                    }
                assertEquals("Native restore payload format is not supported", failure.message)
                assertEquals(0L, Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR))
            }
        }

        fun deliverModern(
            agent: NativeBackupAgent,
            destination: File,
            source: File,
            flags: Int,
            version: String,
        ) {
            val inputType = Class.forName("android.app.backup.FullRestoreDataInput")
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val input = nativeModernRestoreInput(inputType, descriptor, destination, source.length(), 0x180L, flags, version)
                try {
                    NativeBackupAgent::class.java.getDeclaredMethod("onRestoreFile", inputType).invoke(agent, input)
                } catch (failure: InvocationTargetException) {
                    assertEquals(0L, Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR))
                    throw failure
                }
            }
        }

        fun createJournal(
            destination: File,
            content: String,
        ) {
            destination.parentFile?.mkdirs()
            CipherDatabase
                .openDatabase(
                    destination.absolutePath,
                    originalKey,
                    null,
                    CipherDatabase.OPEN_READWRITE or CipherDatabase.CREATE_IF_NECESSARY,
                    null,
                    null,
                ).use {
                    it.execSQL("CREATE TABLE retained_entries (content TEXT NOT NULL)")
                    it.execSQL("INSERT INTO retained_entries VALUES (?)", arrayOf(content))
                }
        }

        fun verifyRetainedJournal(expected: String = "Retained encrypted journal") {
            val key = assertNotNull(PassphraseBackupStore(context).read())
            assertEquals(originalKey.toList(), key.toList())
            runBlocking {
                val identity = assertNotNull(AndroidIdentityKeyBackupStore(context).readAccountIdentity())
                assertEquals("native-test-owner", identity.accountId)
                assertEquals(identityKey.toList(), identity.key.toList())
                assertEquals(mediaKey.toList(), identity.mediaKey?.toList())
                val media = assertNotNull(AndroidIdentityKeyBackupStore(context).readMediaIdentity(identityBinding))
                assertEquals(mediaKey.toList(), media.key.toList())
            }
            CipherDatabase.openDatabase(database.absolutePath, key, null, CipherDatabase.OPEN_READONLY, null, null).use {
                it.rawQuery("SELECT content FROM retained_entries", emptyArray<String>()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(expected, cursor.getString(0))
                    assertTrue(!cursor.moveToNext())
                }
            }
            assertEquals("Retained original media", File(context.filesDir, "media/retained-original").readText())
        }

        override fun close() {
            agents.asReversed().forEach { it.onDestroy() }
            folder.deleteRecursively()
        }
    }
}
