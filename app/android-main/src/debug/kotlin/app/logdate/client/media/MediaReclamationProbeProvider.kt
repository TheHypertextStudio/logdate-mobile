package app.logdate.client.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import app.logdate.client.media.storage.AndroidMediaReclamationJournal
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Debug-only second process for checking the media candidate journal on a managed emulator. */
class MediaReclamationProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val context = checkNotNull(context)
        val runId = requireNotNull(extras?.getString("runId"))
        require(runId.matches(Regex("[a-f0-9-]{36}")))
        val root = File(context.noBackupFilesDir, "media-reclamation-probe/$runId")
        val journal = AndroidMediaReclamationJournal(root)
        return when (method) {
            "pid" -> {
                Bundle().apply { putInt("pid", Process.myPid()) }
            }

            "recordThenExit" -> {
                runBlocking { journal.recordCandidate(requireNotNull(extras.getString("reference"))) }
                Thread {
                    Thread.sleep(200)
                    Runtime.getRuntime().halt(0)
                }.start()
                Bundle().apply { putInt("pid", Process.myPid()) }
            }

            "holdLock" -> {
                runBlocking { journal.pendingCandidates() }
                FileChannel
                    .open(File(root, "media-reclamation/.lock").toPath(), StandardOpenOption.WRITE)
                    .use { channel ->
                        channel.lock().use {
                            File(root, "ready").writeText("locked")
                            val release = File(root, "release")
                            val deadline = System.nanoTime() + 10_000_000_000L
                            while (!release.exists() && System.nanoTime() < deadline) Thread.sleep(10)
                            check(release.exists()) { "Probe lock was not released" }
                        }
                    }
                Bundle.EMPTY
            }

            else -> {
                error("Unknown media reclamation probe")
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
