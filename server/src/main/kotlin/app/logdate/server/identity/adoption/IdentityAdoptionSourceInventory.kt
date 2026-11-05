package app.logdate.server.identity.adoption

import kotlinx.serialization.json.JsonObject

/** All representations participate, even when the account normally reads only one storage format. */
internal enum class IdentityAdoptionSourceKind {
    SPACE_STATE,
    SPACE_RECORD,
    SPACE_REFERENCE,
    SPACE_BLOB,
    COLLECTION_STATE,
    COLLECTION_RECORD,
    REPO_HEAD,
    REPO_COMMIT,
    REPO_BLOCK,
    SYNC_CONTENT,
    SYNC_JOURNAL,
    SYNC_ASSOCIATION,
    SYNC_MEDIA,
    SYNC_BACKUP,
    MEDIA,
    BACKUP,
    ATPROTO_BLOB,
    LOCATION,
    LOCATION_VERSION,
    STORAGE_AUTHORITY,
}

internal data class IdentityAdoptionSourceBinary(
    val size: Long,
    val digest: String,
    val bytes: ByteArray?,
)

internal data class IdentityAdoptionSourceItem(
    val key: List<String>,
    val fields: JsonObject,
    val binary: IdentityAdoptionSourceBinary?,
    val sqlNullColumns: Set<String>,
)

internal data class IdentityAdoptionSourcePage(
    val operationId: String,
    val revision: Long,
    val generation: Long,
    val fenced: Boolean,
    val kind: IdentityAdoptionSourceKind,
    val count: Long,
    val items: List<IdentityAdoptionSourceItem>,
    val next: List<String>?,
    val present: Boolean = true,
)
