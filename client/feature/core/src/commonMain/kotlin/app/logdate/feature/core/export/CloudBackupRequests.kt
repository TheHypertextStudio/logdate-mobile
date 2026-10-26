package app.logdate.feature.core.export

/** Injectable platform boundary; retaining active work is part of the backup contract. */
internal fun interface CloudBackupWorkQueue {
    fun enqueueUnique(
        name: String,
        periodic: Boolean,
        keepExisting: Boolean,
    )
}

internal class CloudBackupRequests(
    private val queue: CloudBackupWorkQueue,
) {
    fun immediate() = queue.enqueueUnique("logdate:cloud:backup:immediate", periodic = false, keepExisting = true)

    fun periodic() = queue.enqueueUnique("logdate:cloud:backup", periodic = true, keepExisting = true)
}
