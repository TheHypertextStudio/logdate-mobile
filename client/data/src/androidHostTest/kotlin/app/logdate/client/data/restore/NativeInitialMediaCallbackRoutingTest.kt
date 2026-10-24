package app.logdate.client.data.restore

import android.app.Application
import android.app.backup.BackupAgent
import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.logdate.client.device.restore.NativeBackupAgent
import app.logdate.client.device.restore.NativeJournalReplayCustody
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses the agent's shared framework callback routing, with the XML-filtered framework writer as its boundary double. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class NativeInitialMediaCallbackRoutingTest {
    @Test
    fun `selected media before snapshot bypasses filtered framework writer without diverting snapshot`() {
        val root = createTempDirectory("initial-media-callback-").toFile()
        val parent = ApplicationProvider.getApplicationContext<Application>()
        val context =
            object : ContextWrapper(parent) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }

                override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
            }
        val agent = NativeBackupAgent()
        ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(agent, context)
        val writes = mutableListOf<String>()
        val writer: (ParcelFileDescriptor, File) -> Unit = { descriptor, target ->
            val path = target.relativeTo(context.filesDir).invariantSeparatorsPath
            writes += path
            // Android's declared include allowlist drops selected media but permits the snapshot subtree.
            if (path.startsWith("native_journal_transfer/")) {
                target.parentFile!!.mkdirs()
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                    target.writeBytes(it.readBytes())
                }
            }
        }
        val route =
            NativeBackupAgent::class.java
                .getDeclaredMethod(
                    "restoreNativeFile",
                    File::class.java,
                    ParcelFileDescriptor::class.java,
                    Long::class.javaPrimitiveType,
                    java.lang.Boolean::class.java,
                    Int::class.javaPrimitiveType,
                    kotlin.jvm.functions.Function2::class.java,
                ).apply { isAccessible = true }

        fun deliver(
            path: String,
            text: String,
        ) {
            val input = File(root, "transport").apply { writeText(text) }
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                route.invoke(agent, File(context.filesDir, path), it, input.length(), true, BackupAgent.TYPE_FILE, writer)
            }
        }
        try {
            deliver("media/original.jpg", "selected original")
            assertTrue(NativeJournalReplayCustody(context).hasInputs())
            assertFalse(NativeJournalReplayCustody(context).hasJournalInputs())
            assertTrue(writes.isEmpty(), "Selected media must not reach the framework writer that silently discards it")
            deliver("native_journal_transfer/current/database/logdate", "snapshot database")
            deliver("native_journal_transfer/current/source_scope.json", "snapshot receipt")
            assertEquals(
                listOf("native_journal_transfer/current/database/logdate", "native_journal_transfer/current/source_scope.json"),
                writes,
            )
            assertEquals("snapshot database", File(context.filesDir, writes.first()).readText())
            assertTrue(NativeJournalReplayCustody(context).hasInputs(), "Initial selected originals must remain in custody")
            assertFalse(NativeJournalReplayCustody(context).hasJournalInputs(), "Media custody must not reroute later snapshot callbacks")
            assertFalse(File(context.filesDir, "media/original.jpg").exists())
        } finally {
            agent.onDestroy()
            check(root.deleteRecursively())
        }
    }
}
