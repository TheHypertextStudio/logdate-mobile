package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryAuditor
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.space.SealedPadding

/** Fixed SQL sources only: a request cannot supply a relation, predicate or identifier. */
internal data class IdentityAdoptionSourceTable(
    val name: String,
    val keys: List<String>,
    val owner: String = "t.user_id",
    val binary: String? = null,
    val returnBytes: Boolean = false,
    val from: String = "$name t",
    val didOwner: Boolean = false,
    val keyAlias: String = "t",
    val optional: Boolean = false,
    val binaryLimit: Int = 2 * 1024 * 1024,
) {
    val keySql: String get() = keys.joinToString(",", "ARRAY[", "]") { "$keyAlias.\"$it\"::text" }
}

internal fun IdentityAdoptionSourceKind.sourceTable(): IdentityAdoptionSourceTable =
    when (this) {
        IdentityAdoptionSourceKind.STORAGE_AUTHORITY ->
            IdentityAdoptionSourceTable("identity_adoption_storage_authority", listOf("account_id"), "t.account_id")
        IdentityAdoptionSourceKind.SPACE_STATE ->
            IdentityAdoptionSourceTable("space_states", listOf("space_uri", "author_did"), "t.account_id")
        IdentityAdoptionSourceKind.SPACE_RECORD ->
            IdentityAdoptionSourceTable(
                "space_records",
                listOf("space_uri", "author_did", "rkey"),
                "t.account_id",
                "record",
                true,
                binaryLimit = SealedPadding.MAX_CIPHERTEXT + 8192,
            )
        IdentityAdoptionSourceKind.SPACE_REFERENCE ->
            IdentityAdoptionSourceTable(
                "space_record_blobs",
                listOf("space_uri", "author_did", "rkey", "cid"),
                "r.account_id",
                from =
                    "space_record_blobs t JOIN space_records r ON r.space_uri = t.space_uri " +
                        "AND r.author_did = t.author_did AND r.rkey = t.rkey",
            )
        IdentityAdoptionSourceKind.SPACE_BLOB ->
            IdentityAdoptionSourceTable("space_blobs", listOf("space_uri", "cid"), "t.account_id")
        IdentityAdoptionSourceKind.COLLECTION_STATE -> IdentityAdoptionSourceTable("logdate_collection_states", listOf("user_id"))
        IdentityAdoptionSourceKind.COLLECTION_RECORD ->
            IdentityAdoptionSourceTable("logdate_collection_records", listOf("collection", "record_key"))
        IdentityAdoptionSourceKind.REPO_HEAD ->
            IdentityAdoptionSourceTable("atproto_repo_heads", listOf("repo_did"), "t.repo_did", didOwner = true)
        IdentityAdoptionSourceKind.REPO_COMMIT ->
            IdentityAdoptionSourceTable("atproto_repo_commits", listOf("revision"), "t.repo_did", didOwner = true)
        IdentityAdoptionSourceKind.REPO_BLOCK ->
            IdentityAdoptionSourceTable(
                "atproto_repo_blocks",
                listOf("cid"),
                "l.repo_did",
                "bytes",
                true,
                "atproto_repo_block_links l LEFT JOIN atproto_repo_blocks t ON t.cid = l.cid",
                true,
                "l",
                binaryLimit = IdentityAdoptionRepositoryAuditor.MAX_BLOCK_BYTES,
            )
        IdentityAdoptionSourceKind.SYNC_CONTENT -> IdentityAdoptionSourceTable("sync_content", listOf("id"))
        IdentityAdoptionSourceKind.SYNC_JOURNAL -> IdentityAdoptionSourceTable("sync_journals", listOf("id"))
        IdentityAdoptionSourceKind.SYNC_ASSOCIATION -> IdentityAdoptionSourceTable("sync_associations", listOf("journal_id", "content_id"))
        IdentityAdoptionSourceKind.SYNC_MEDIA -> IdentityAdoptionSourceTable("sync_media", listOf("media_id"), binary = "data")
        IdentityAdoptionSourceKind.SYNC_BACKUP -> IdentityAdoptionSourceTable("sync_backups", listOf("id"), optional = true)
        IdentityAdoptionSourceKind.MEDIA -> IdentityAdoptionSourceTable("logdate_media_records", listOf("media_id"), binary = "data")
        IdentityAdoptionSourceKind.BACKUP -> IdentityAdoptionSourceTable("logdate_backups", listOf("id"))
        IdentityAdoptionSourceKind.ATPROTO_BLOB -> IdentityAdoptionSourceTable("logdate_atproto_blobs", listOf("cid"))
        IdentityAdoptionSourceKind.LOCATION -> IdentityAdoptionSourceTable("location_history", listOf("id"))
        IdentityAdoptionSourceKind.LOCATION_VERSION -> IdentityAdoptionSourceTable("location_history_versions", listOf("user_id"))
    }
