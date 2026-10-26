package app.logdate.feature.core.backup

import android.content.Context
import androidx.work.WorkManager
import app.logdate.feature.core.export.CloudBackupWorker
import app.logdate.feature.core.restore.CloudRestoreWorker
import app.logdate.feature.core.restore.RestoreWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

internal fun cloudArchiveFile(
    context: Context,
    prefix: String,
    workId: java.util.UUID,
): File = File(context.noBackupFilesDir, "$prefix-$workId.zip")

/** A conservative sweep; no file still referenced by pending work is removed. */
internal suspend fun pruneAbandonedCloudArchives(context: Context) {
    val activeNames =
        try {
            withContext(Dispatchers.IO) {
                val workManager = WorkManager.getInstance(context)
                val names = mutableSetOf<String>()
                listOf(
                    CloudBackupWorker.WORK_NAME,
                    "${CloudBackupWorker.WORK_NAME}:immediate",
                    CloudRestoreWorker.WORK_NAME,
                    RestoreWorker.WORK_NAME,
                ).forEach { uniqueName ->
                    workManager.getWorkInfosForUniqueWork(uniqueName).get().forEach { work ->
                        if (!work.state.isFinished) {
                            if (uniqueName.startsWith(CloudBackupWorker.WORK_NAME)) {
                                names += "cloud-backup-pending.zip"
                                names += "cloud-backup-pending.scope"
                                names += "cloud-backup-pending.scope.part"
                                names += "cloud-backup-pending.plain.part"
                                names += "cloud-backup-pending.encrypted.part"
                            }
                            names += "cloud-backup-${work.id}.zip"
                            names += "cloud-backup-${work.id}.scope"
                            names += "cloud-backup-${work.id}.scope.part"
                            names += "cloud-backup-${work.id}.plain.part"
                            names += "cloud-backup-${work.id}.encrypted.part"
                            names += "cloud-restore-${work.id}.zip"
                            names += "cloud-restore-${work.id}.plain.part"
                            names += "cloud-restore-${work.id}.encrypted.part"
                            if (uniqueName == RestoreWorker.WORK_NAME) {
                                context.noBackupFilesDir
                                    .listFiles()
                                    .orEmpty()
                                    .filter { it.name.startsWith("cloud-restore-") && it.name.endsWith(".zip") }
                                    .forEach { names += it.name }
                            }
                        }
                    }
                }
                names
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return
        }
    pruneAbandonedCloudArchives(context.noBackupFilesDir, System.currentTimeMillis(), activeNames)
}

fun pruneAbandonedCloudArchives(
    directory: File,
    nowMillis: Long,
    activeNames: Set<String>,
) {
    directory.listFiles().orEmpty().forEach { file ->
        if (file.isFile &&
            (file.name.startsWith("cloud-backup-") || file.name.startsWith("cloud-restore-")) &&
            (
                file.name.endsWith(".zip") ||
                    file.name.endsWith(".part") ||
                    file.name.endsWith(".scope")
            ) &&
            file.name !in activeNames &&
            nowMillis - file.lastModified() > MAX_ARCHIVE_AGE_MILLIS
        ) {
            file.delete()
        }
    }
}

private const val MAX_ARCHIVE_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
