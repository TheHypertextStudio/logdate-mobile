package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.cloud.BackupMetadata

/** Order is assigned under the backup lock, independently of WorkManager's history list order. */
internal data class CloudArchiveAttempt(
    val workId: String,
    val state: ArchiveWorkState,
    val order: Long = 0L,
    val backupId: String? = null,
    val deviceId: String? = null,
)

internal fun selectArchiveWork(
    history: List<CloudArchiveAttempt>,
    recorded: CloudArchiveAttempt? = null,
): CloudArchiveAttempt? {
    for (active in listOf(ArchiveWorkState.RUNNING, ArchiveWorkState.RETRYING, ArchiveWorkState.QUEUED)) {
        history.filter { it.state == active }.maxByOrNull { it.order }?.let { return it }
    }
    return (history + listOfNotNull(recorded))
        .filter { it.state == ArchiveWorkState.FAILED || it.state == ArchiveWorkState.SUCCEEDED }
        .maxWithOrNull(compareBy<CloudArchiveAttempt> { it.order }.thenBy { it.state == ArchiveWorkState.FAILED }.thenBy { it.workId })
}

/** A different device's or earlier request's archive cannot resolve this request's failure. */
internal fun confirmedArchiveAttemptAt(
    attempt: CloudArchiveAttempt,
    backups: List<BackupMetadata>,
    manifest: String,
): Long? {
    if (attempt.state != ArchiveWorkState.SUCCEEDED || attempt.backupId == null || attempt.deviceId == null) return null
    return backups
        .firstOrNull {
            it.id == attempt.backupId && it.deviceId == attempt.deviceId && it.manifest == manifest
        }?.createdAt
}
