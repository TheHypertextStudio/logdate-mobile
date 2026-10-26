package app.logdate.feature.core.export

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.feature.core.settings.ui.ArchiveWorkState
import app.logdate.feature.core.settings.ui.CloudArchiveAttempt
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Periodic WorkManager output may reset; retain only the last attempt for this account/server. */
internal class CloudBackupAttemptStore(
    private val directory: File,
) {
    fun read(scope: OriginBoundSession): CloudArchiveAttempt? {
        val file = recordFile(scope)
        if (!file.isFile || file.length() !in 1L..4096L) return null
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size != 6 || lines[0] != "archive-attempt-v1") return null
        val state = ArchiveWorkState.entries.firstOrNull { it.name == lines[2] } ?: return null
        val order = lines[3].toLongOrNull()?.takeIf { it > 0 && it < Long.MAX_VALUE } ?: return null
        return CloudArchiveAttempt(
            lines[1],
            state,
            order,
            lines[4].takeUnless { it == "-" },
            lines[5].takeUnless { it == "-" },
        )
    }

    fun begin(
        scope: OriginBoundSession,
        workId: String,
    ): CloudArchiveAttempt {
        val previous = read(scope)?.order ?: 0L
        val attempt = CloudArchiveAttempt(workId, ArchiveWorkState.RUNNING, maxOf(System.currentTimeMillis(), previous + 1))
        write(scope, attempt)
        return attempt
    }

    fun write(
        scope: OriginBoundSession,
        attempt: CloudArchiveAttempt,
    ) {
        val file = recordFile(scope)
        val values =
            listOf(
                "archive-attempt-v1",
                attempt.workId,
                attempt.state.name,
                attempt.order.toString(),
                attempt.backupId ?: "-",
                attempt.deviceId ?: "-",
            )
        require(values.none { '\n' in it || '\r' in it })
        val part = File(directory, "${file.name}.part")
        FileOutputStream(part).use { output ->
            output.write(values.joinToString("\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(part.renameTo(file)) { "Unable to record archive attempt" }
    }

    private fun recordFile(scope: OriginBoundSession): File {
        val value = "${scope.origin.length}:${scope.origin}${scope.session.accountId}"
        val key = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hexDigest()
        return File(directory, "cloud-archive-attempt-$key.state")
    }
}
