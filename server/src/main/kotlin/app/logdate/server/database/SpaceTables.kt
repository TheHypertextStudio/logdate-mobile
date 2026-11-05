package app.logdate.server.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * The last sequence number issued for one author's repository in one space; its row is the write lock.
 * [purgedThroughSeq] is the highest sequence number of a tombstone that purging deleted.
 */
object SpaceStatesTable : Table("space_states") {
    val spaceUri = text("space_uri")
    val authorDid = text("author_did")
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val lastSeq = long("last_seq").default(0L)
    val purgedThroughSeq = long("purged_through_seq").default(0L)
    override val primaryKey = PrimaryKey(spaceUri, authorDid)

    init {
        index("idx_space_states_account", false, accountId)
    }
}

/** Sealed records and their tombstones. [record] holds the DAG-CBOR bytes of a live record. */
object SpaceRecordsTable : Table("space_records") {
    val spaceUri = text("space_uri")
    val authorDid = text("author_did")
    val rkey = varchar("rkey", 512)
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val seq = long("seq")
    val cid = varchar("cid", 255).nullable()
    val record = binary("record").nullable()
    val sizeBytes = integer("size_bytes").default(0)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(spaceUri, authorDid, rkey)

    init {
        index("idx_space_records_seq", false, spaceUri, authorDid, seq)
        index("idx_space_records_account", false, accountId)
    }
}

/** The blobs each live record refers to, so purging can keep referenced blobs. */
object SpaceRecordBlobsTable : Table("space_record_blobs") {
    val spaceUri = text("space_uri")
    val authorDid = text("author_did")
    val rkey = varchar("rkey", 512)
    val cid = varchar("cid", 255)
    override val primaryKey = PrimaryKey(spaceUri, authorDid, rkey, cid)

    init {
        foreignKey(
            spaceUri to SpaceRecordsTable.spaceUri,
            authorDid to SpaceRecordsTable.authorDid,
            rkey to SpaceRecordsTable.rkey,
            onDelete = ReferenceOption.CASCADE,
        )
        index("idx_space_record_blobs_cid", false, spaceUri, cid)
    }
}

/** Metadata for encrypted blobs uploaded to a space; the bytes live in object storage at [storagePath]. */
object SpaceBlobsTable : Table("space_blobs") {
    val spaceUri = text("space_uri")
    val cid = varchar("cid", 255)
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val mimeType = varchar("mime_type", 255)
    val sizeBytes = long("size_bytes")
    val storagePath = text("storage_path")
    val purgeOperationId = varchar("purge_operation_id", 64).nullable()
    val purgeIdentityEpoch = long("purge_identity_epoch").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(spaceUri, cid)

    init {
        index("idx_space_blobs_account", false, accountId)
        index("idx_space_blobs_created", false, createdAt)
    }
}

/** Opaque write-once identities; content and encryption fingerprints remain on the device. */
object SpaceBlobUploadReceiptsTable : Table("space_blob_upload_receipts") {
    val spaceUri = text("space_uri")
    val uploadIntent = varchar("upload_intent", 36)
    val cid = varchar("cid", 255)
    override val primaryKey = PrimaryKey(spaceUri, uploadIntent)

    init {
        foreignKey(spaceUri to SpaceBlobsTable.spaceUri, cid to SpaceBlobsTable.cid, onDelete = ReferenceOption.CASCADE)
    }
}

/** Room held against an account's quota while an upload is in progress; it stops counting at [expiresAt]. */
object SpaceUploadReservationsTable : Table("space_upload_reservations") {
    val id = javaUUID("id")
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val bytes = long("bytes")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_space_upload_reservations_account", false, accountId)
    }
}
