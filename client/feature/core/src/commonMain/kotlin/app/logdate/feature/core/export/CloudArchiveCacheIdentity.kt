package app.logdate.feature.core.export

/** All inputs that must remain equal before reusing a privately retained, unfinished upload. */
internal data class CloudArchiveCacheIdentity(
    val origin: String,
    val accountId: String,
    val identityFingerprint: String,
    val options: String,
    val sourceFingerprint: String,
)

internal fun reusableCloudArchive(
    retained: CloudArchiveCacheIdentity,
    current: CloudArchiveCacheIdentity,
    expectedDigest: String,
    actualDigest: String,
    complete: Boolean,
): Boolean = complete && retained == current && expectedDigest.isNotBlank() && expectedDigest == actualDigest
