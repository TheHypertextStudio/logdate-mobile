package app.logdate.client.sync.recovery

/**
 * Lifecycle of a download inbox row, stored by [name].
 *
 * [WAITING] and [FAILED] rows are retried once due. [PARKED] rows used up their attempts. They stay
 * visible as backlog, but no pass or manual backup retries them.
 */
internal enum class DownloadInboxState {
    WAITING,
    FAILED,
    PARKED,
    APPLIED,
}
