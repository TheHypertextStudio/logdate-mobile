package app.logdate.shared.model

/** A Cloud quota observation carries its account even while usage is unknown or unavailable. */
sealed interface CloudQuotaSnapshot {
    val accountId: String?

    data class Loading(
        override val accountId: String?,
    ) : CloudQuotaSnapshot

    data class Available(
        override val accountId: String?,
        val quota: CloudStorageQuota,
    ) : CloudQuotaSnapshot

    data class Unavailable(
        override val accountId: String?,
    ) : CloudQuotaSnapshot
}
