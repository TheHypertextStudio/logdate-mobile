@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.logdate

import kotlin.uuid.Uuid

internal const val PRIVATE_BLOB_PREFIX = "identity-adoption-private/"
internal const val PRIVATE_BLOB_OWNER = "logdate-private-owner"
internal const val PRIVATE_BLOB_STATE = "logdate-private-state"
internal const val PRIVATE_BLOB_RETIRED = "retired"

internal fun privateBlobPath(blobId: String): String {
    require(Uuid.parseOrNull(blobId)?.toString() == blobId)
    return "$PRIVATE_BLOB_PREFIX$blobId"
}

internal fun requirePrivateBlobOwner(owner: String) {
    require(owner.matches(Regex("[A-Za-z0-9_-]{43}")))
}

internal fun privateBlobId(path: String): String? {
    if (!path.startsWith(PRIVATE_BLOB_PREFIX)) return null
    val id = path.removePrefix(PRIVATE_BLOB_PREFIX)
    return id.takeIf { Uuid.parseOrNull(it)?.toString() == it }
}
