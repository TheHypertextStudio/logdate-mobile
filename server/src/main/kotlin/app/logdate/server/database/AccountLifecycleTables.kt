package app.logdate.server.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp

/** Mirrors V39: one row per linked device session. */
object DeviceSessionsTable : Table("device_sessions") {
    val id = javaUUID("id")
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val deviceName = varchar("device_name", 120)
    val platform = varchar("platform", 32)
    val createdAt = long("created_at")
    val lastRefreshedAt = long("last_refreshed_at")
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_device_sessions_account", false, accountId)
    }
}

/** Mirrors V39: one row per minted link code, keyed by the SHA-256 hash of the code's bytes. */
object DeviceLinkCodesTable : Table("device_link_codes") {
    val codeHash = varchar("code_hash", 64)
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val redeemedAt = long("redeemed_at").nullable()
    val sessionId = javaUUID("session_id").nullable()

    override val primaryKey = PrimaryKey(codeHash)

    init {
        index("idx_device_link_codes_account_created", false, accountId, createdAt)
        index("idx_device_link_codes_expires", false, expiresAt)
    }
}

/** Persistent operation binding from V64; constraints and active-operation uniqueness live in the migration. */
internal object IdentityAdoptionOperationsTable : Table("identity_adoption_operations") {
    val id = varchar("id", 64)
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val sourceDid = varchar("source_did", 2048)
    val targetDid = varchar("target_did", 2048)
    val deviceId = varchar("device_id", 255)
    val sourceEpoch = long("source_epoch")
    val revision = long("revision")
    val phase = varchar("phase", 16)
    val leaseExpiresAt = timestamp("lease_expires_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** No account foreign key: retirement must survive account deletion. */
internal object RetiredIdentityDidsTable : Table("retired_identity_dids") {
    val sourceDid = varchar("source_did", 2048)
    val accountId = javaUUID("account_id")
    val operationId = varchar("operation_id", 64).uniqueIndex()
    val identityEpoch = long("identity_epoch")
    val retiredAt = timestamp("retired_at")
    override val primaryKey = PrimaryKey(sourceDid)
}

internal object IdentityAdoptionReservationsTable : Table("identity_adoption_reservations") {
    val targetDid = varchar("target_did", 2048)
    val operationId =
        varchar(
            "operation_id",
            64,
        ).uniqueIndex().references(IdentityAdoptionOperationsTable.id, onDelete = ReferenceOption.CASCADE)
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(targetDid)
}

/** A digest alone prevents deleted cloud IDs from being reused by stale requests. */
internal object RetiredAccountIdsTable : Table("retired_account_ids") {
    val accountIdHash = varchar("account_id_hash", 64)
    val deletedAt = timestamp("deleted_at")
    override val primaryKey = PrimaryKey(accountIdHash)
}
