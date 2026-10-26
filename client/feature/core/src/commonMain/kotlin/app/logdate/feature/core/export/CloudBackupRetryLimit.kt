package app.logdate.feature.core.export

/** Attempts a cloud backup gets before it reports failure and waits for the user to act. */
const val MAX_CLOUD_BACKUP_ATTEMPTS = 6

/** WorkManager supplies the zero-based number of prior executions. */
fun cloudBackupShouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_CLOUD_BACKUP_ATTEMPTS - 1
