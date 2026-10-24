package app.logdate.client.data.restore

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeIosTransferStagingTest {
    @Test
    fun `only archive and private custody files are emitted and mutation aborts`() {
        val root = Files.createTempDirectory("native-ios-emission-").toFile()
        try {
            val archive = File(root, "archive/data").apply { mkdirs() }
            val manifest = File(root, "archive/manifest.json").apply { writeText("manifest fixture") }
            val notes = File(archive, "notes.json").apply { writeText("notes fixture") }
            val privateDirectory = File(root, ".logdate-os-private").apply { mkdirs() }
            val custody = File(privateDirectory, "custody-v1.json").apply { writeText("custody fixture") }
            val emitted = mutableListOf<String>()
            emitNativeIosStagedFiles(root) { emitted += it.relativeTo(root).invariantSeparatorsPath }
            assertEquals(
                listOf("archive/data/notes.json", "archive/manifest.json", ".logdate-os-private/custody-v1.json"),
                emitted,
            )
            assertFailsWith<IllegalStateException> {
                emitNativeIosStagedFiles(root) { if (it == notes) notes.writeText("changed") }
            }
            assertTrue(manifest.exists() && custody.exists())
        } finally {
            check(root.deleteRecursively())
        }
    }

    @Test
    fun `abandoned private bytes are removed without interrupting an active export`() {
        val sandbox = Files.createTempDirectory("native-ios-staging-").toFile()
        try {
            val files = File(sandbox, "files").apply { mkdirs() }
            val noBackup = File(sandbox, "no_backup").apply { mkdirs() }
            val context =
                object : ContextWrapper(null) {
                    override fun getFilesDir(): File = files

                    override fun getNoBackupFilesDir(): File = noBackup
                }
            val sender = NativeIosTransferSender(context, NativeJournalTransferSnapshot(context))
            val custody = File(files, "logdate-os-transfer/.logdate-os-private/custody-v1.json")
            custody.parentFile!!.mkdirs()
            custody.writeText("private fixture")
            val channel =
                FileChannel.open(
                    File(noBackup, "native-ios-transfer.lock").toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            channel.use {
                channel.lock().use {
                    sender.discardAbandoned()
                    assertTrue(custody.exists())
                }
            }
            sender.discardAbandoned()
            assertFalse(custody.exists())
        } finally {
            check(sandbox.deleteRecursively())
        }
    }

    @Test
    fun `failed cleanup does not block ordinary provider start but iOS export fails closed`() {
        val sandbox = Files.createTempDirectory("native-ios-cleanup-failure-").toFile()
        try {
            val files = File(sandbox, "files").apply { mkdirs() }
            val noBackup = File(sandbox, "no_backup").apply { writeText("blocked directory") }
            val context =
                object : ContextWrapper(null) {
                    override fun getFilesDir(): File = files

                    override fun getNoBackupFilesDir(): File = noBackup
                }
            val sender = NativeIosTransferSender(context, NativeJournalTransferSnapshot(context))
            val custody = File(files, "logdate-os-transfer/.logdate-os-private/custody-v1.json")
            custody.parentFile!!.mkdirs()
            custody.writeText("private fixture")

            assertFalse(discardAbandonedForProviderStart(sender::discardAbandoned))
            assertTrue(custody.exists())
            assertFailsWith<Exception> {
                runBlocking { sender.send(0) { error("Must never emit") } }
            }
            assertTrue(custody.exists())
        } finally {
            check(sandbox.deleteRecursively())
        }
    }

    @Test
    fun `linked archive content is never emitted`() {
        val root = Files.createTempDirectory("native-ios-linked-").toFile()
        val outside = Files.createTempDirectory("native-ios-outside-").toFile()
        try {
            File(root, "archive").mkdirs()
            File(root, ".logdate-os-private").mkdirs()
            File(root, ".logdate-os-private/custody-v1.json").writeText("custody fixture")
            File(outside, "secret.txt").writeText("outside fixture")
            Files.createSymbolicLink(File(root, "archive/linked").toPath(), outside.toPath())
            assertFailsWith<IllegalStateException> {
                emitNativeIosStagedFiles(root) { error("No files should be emitted") }
            }
        } finally {
            check(root.deleteRecursively())
            check(outside.deleteRecursively())
        }
    }
}
