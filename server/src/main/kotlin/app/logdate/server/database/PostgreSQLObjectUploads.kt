package app.logdate.server.database

import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.ObjectUploadKind
import app.logdate.server.logdate.ObjectUploads
import app.logdate.server.migration.SpaceMigrationConfig
import app.logdate.server.space.SpaceBlob
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import studio.hypertext.atproto.identity.AtprotoDid
import java.sql.Timestamp
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Survives account deletion so an in-flight provider write retains its original owner and path. */
internal object ObjectUploadIntentsTable : Table("object_upload_intents") {
    val id = varchar("id", 64)
    val accountId = javaUUID("account_id")
    val identityEpoch = long("identity_epoch")
    val repoDid = varchar("repo_did", 2048).nullable()
    val kind = varchar("upload_kind", 16)
    val space = text("space_uri").nullable()
    val legacyMigrationId = varchar("legacy_migration_id", 64).nullable()
    val legacyMigrationVersion = long("legacy_migration_version").nullable()
    val storagePath = text("storage_path").uniqueIndex()
    val phase = varchar("phase", 16)
    val settledAt = timestamp("settled_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLObjectUploads(
    private val migrationConfig: SpaceMigrationConfig = SpaceMigrationConfig(),
    private val clock: Clock = Clock.System,
) : ObjectUploads {
    override fun begin(
        origin: IdentityWriteExpectation,
        repo: AtprotoDid?,
        path: String,
        kind: ObjectUploadKind,
        space: String?,
    ): ObjectUpload = admit(origin, repo, path, kind, space, null, null)

    override fun beginMigration(
        origin: IdentityWriteExpectation,
        path: String,
        space: String,
        migrationId: String,
        migrationVersion: Long,
    ): ObjectUpload = admit(origin, null, path, ObjectUploadKind.SPACE, space, migrationId, migrationVersion)

    private fun admit(
        origin: IdentityWriteExpectation,
        repo: AtprotoDid?,
        path: String,
        kind: ObjectUploadKind,
        space: String?,
        migrationId: String?,
        migrationVersion: Long?,
    ): ObjectUpload {
        outsideTransaction()
        return transaction {
            requireAuthority(origin, repo, kind, space)
            requireLegacyMigrationUploadAuthority(origin, migrationId, migrationVersion)
            requireUnretiredObjectPath(path)
            accountWriteConnection().requireExclusiveObjectPath(
                origin.accountId.toJavaUUID(),
                LegacyObjectKind.UNREFERENCED,
                path,
                path,
            )
            val upload = ObjectUpload(Uuid.random().toString(), origin, repo, path, kind, space, migrationId, migrationVersion)
            accountWriteConnection()
                .prepareStatement(
                    "INSERT INTO object_upload_intents(id, account_id, identity_epoch, repo_did, " +
                        "storage_path, upload_kind, space_uri, legacy_migration_id, legacy_migration_version, phase) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'WRITING')",
                ).use {
                    it.setString(1, upload.id)
                    it.setObject(2, origin.accountId.toJavaUUID())
                    it.setLong(3, origin.identityEpoch)
                    it.setString(4, repo?.toString())
                    it.setString(5, path)
                    it.setString(6, kind.name)
                    it.setString(7, space)
                    it.setString(8, migrationId)
                    it.setObject(9, migrationVersion)
                    check(it.executeUpdate() == 1)
                }
            upload
        }
    }

    override fun stored(upload: ObjectUpload) {
        outsideTransaction()
        transaction { update(upload, "WRITING", "STORED") }
    }

    override fun <T> attach(
        upload: ObjectUpload,
        publish: () -> T,
        ownsResult: (T) -> Boolean,
    ): T {
        outsideTransaction()
        if (upload.kind == ObjectUploadKind.SPACE) {
            throw IdentityLifecycleConflictException("Space uploads finalize inside the space store transaction")
        }
        return transaction {
            requireAuthority(upload.origin, upload.repo, upload.kind, upload.space)
            requireUnretiredObjectPath(upload.path)
            update(upload, "STORED", "ATTACHED")
            // The metadata repository joins this transaction; both effects commit or roll back.
            val result = publish()
            if (!ownsResult(result)) update(upload, "ATTACHED", "STORED")
            result
        }
    }

    /** The space store owns this transaction; never call a suspending store API through generic attach. */
    internal fun attachSpaceInTransaction(
        admissionId: String,
        origin: IdentityWriteExpectation,
        space: String,
        path: String,
        publish: () -> SpaceBlob?,
    ): SpaceBlob? {
        check(TransactionManager.currentOrNull() != null)
        requireSpaceUploadAuthority(origin, space, migrationConfig)
        val upload = spaceUpload(admissionId, origin, space, path)
        requireLegacyMigrationUploadAuthority(origin, upload.legacyMigrationId, upload.legacyMigrationVersion)
        update(upload, "STORED", "ATTACHED")
        val result = publish()
        if (result?.storagePath != path) update(upload, "ATTACHED", "STORED")
        return result
    }

    private fun spaceUpload(
        id: String,
        origin: IdentityWriteExpectation,
        space: String,
        path: String,
    ): ObjectUpload =
        accountWriteConnection()
            .prepareStatement(
                "SELECT legacy_migration_id, legacy_migration_version FROM object_upload_intents " +
                    "WHERE id = ? AND account_id = ? AND identity_epoch = ? AND repo_did IS NULL " +
                    "AND upload_kind = 'SPACE' AND space_uri = ? AND storage_path = ? AND phase = 'STORED'",
            ).use {
                it.setString(1, id)
                it.setObject(2, origin.accountId.toJavaUUID())
                it.setLong(3, origin.identityEpoch)
                it.setString(4, space)
                it.setString(5, path)
                it.executeQuery().use { rows ->
                    if (!rows.next()) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                    val version = rows.getLong(2).let { value -> if (rows.wasNull()) null else value }
                    ObjectUpload(id, origin, null, path, ObjectUploadKind.SPACE, space, rows.getString(1), version)
                }
            }

    override fun cleanup(
        upload: ObjectUpload,
        deleteObject: (String) -> Boolean,
    ): Boolean {
        outsideTransaction()
        return PostgreSQLObjectUploadCleanup().cleanup(upload, deleteObject)
    }

    override fun pendingCleanup(after: String?): List<ObjectUpload> {
        outsideTransaction()
        return PostgreSQLObjectUploadCleanup(clock).pending(after)
    }

    override fun recover(
        upload: ObjectUpload,
        deleteObject: (String) -> Boolean,
    ): Boolean {
        outsideTransaction()
        return PostgreSQLObjectUploadCleanup(clock).cleanup(upload, deleteObject, recovery = true)
    }

    private fun update(
        upload: ObjectUpload,
        previous: String,
        next: String,
    ) {
        // The timestamp must describe settlement after any concurrent intent mutation finishes.
        accountWriteConnection().prepareStatement("SELECT id FROM object_upload_intents WHERE id = ? FOR UPDATE").use {
            it.setString(1, upload.id)
            it.executeQuery().close()
        }
        val updated =
            accountWriteConnection()
                .prepareStatement(
                    "UPDATE object_upload_intents SET phase = ?, settled_at = ? WHERE id = ? AND account_id = ? AND identity_epoch = ? " +
                        "AND repo_did IS NOT DISTINCT FROM ? AND storage_path = ? AND upload_kind = ? " +
                        "AND space_uri IS NOT DISTINCT FROM ? AND legacy_migration_id IS NOT DISTINCT FROM ? " +
                        "AND legacy_migration_version IS NOT DISTINCT FROM ? AND phase = ?",
                ).use {
                    it.setString(1, next)
                    it.setTimestamp(2, if (next == "STORED") Timestamp.from(java.time.Instant.parse(clock.now().toString())) else null)
                    it.setString(3, upload.id)
                    it.setObject(4, upload.origin.accountId.toJavaUUID())
                    it.setLong(5, upload.origin.identityEpoch)
                    it.setString(6, upload.repo?.toString())
                    it.setString(7, upload.path)
                    it.setString(8, upload.kind.name)
                    it.setString(9, upload.space)
                    it.setString(10, upload.legacyMigrationId)
                    it.setObject(11, upload.legacyMigrationVersion)
                    it.setString(12, previous)
                    it.executeUpdate()
                }
        if (updated != 1) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
    }

    private fun requireAuthority(
        origin: IdentityWriteExpectation,
        repo: AtprotoDid?,
        kind: ObjectUploadKind,
        space: String?,
    ) {
        if (kind != ObjectUploadKind.SPACE && space != null) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
        when (kind) {
            ObjectUploadKind.HOSTED_REPO -> {
                requireHostedRepoAccount(origin)
                requireRepoBinding(origin, requireNotNull(repo))
            }
            ObjectUploadKind.MEDIA -> {
                if (origin.identityEpoch != 0L || repo != null) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                requireLegacyCollectionWrite(origin.accountId.toJavaUUID(), migrationConfig, Clock.System.now())
            }
            ObjectUploadKind.SPACE -> {
                if (repo != null) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                requireSpaceUploadAuthority(origin, space, migrationConfig)
            }
            ObjectUploadKind.BACKUP -> {
                if (repo != null) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                requireAccountIdentityWrite(origin.accountId.toJavaUUID(), origin.identityEpoch)
            }
        }
    }

    private fun outsideTransaction() {
        if (TransactionManager.currentOrNull() != null || RawJdbcTransactionContext.isActive()) {
            throw IdentityLifecycleConflictException("Uploads cannot run inside another database transaction")
        }
    }
}
