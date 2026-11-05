@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.ObjectUploadKind
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import studio.hypertext.atproto.identity.AtprotoDid
import java.sql.Connection
import java.sql.Timestamp
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Retires only completed, unattached writes. Uncertain provider outcomes keep their admission barrier. */
internal class PostgreSQLObjectUploadCleanup(
    private val clock: Clock = Clock.System,
) {
    private enum class Admission { SKIP, FINISHED, DELETE }

    fun cleanup(
        upload: ObjectUpload,
        deleteObject: (String) -> Boolean,
        recovery: Boolean = false,
    ): Boolean {
        when (transaction { prepare(upload, recovery) }) {
            Admission.SKIP -> return false
            Admission.FINISHED -> return true
            Admission.DELETE -> Unit
        }
        if (!deleteObject(upload.path)) return false
        transaction {
            val connection = accountWriteConnection()
            connection.lockUploadOwner(upload)
            requirePhase(connection, upload, "DELETE_PENDING")
            connection.lockObjectPath(upload.path)
            connection.requireExclusiveObjectPath(
                upload.origin.accountId.toJavaUUID(),
                LegacyObjectKind.UNREFERENCED,
                upload.path,
                upload.path,
            )
            connection.prepareStatement("UPDATE legacy_object_deletions SET completed = TRUE WHERE operation_id = ?").use {
                it.setString(1, upload.id)
                check(it.executeUpdate() == 1)
            }
            connection.setPhase(upload, "RETIRED")
        }
        return true
    }

    fun pending(after: String?): List<ObjectUpload> =
        transaction {
            accountWriteConnection()
                .prepareStatement(
                    "SELECT id, account_id, identity_epoch, repo_did, storage_path, upload_kind, space_uri, " +
                        "legacy_migration_id, legacy_migration_version " +
                        "FROM object_upload_intents " +
                        "WHERE (phase = 'DELETE_PENDING' OR (phase = 'STORED' AND settled_at <= ?)) " +
                        "AND id > ? ORDER BY id LIMIT 100",
                ).use {
                    it.setTimestamp(1, graceCutoff())
                    it.setString(2, after ?: "")
                    it.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    ObjectUpload(
                                        rows.getString(1),
                                        IdentityWriteExpectation(Uuid.parse(rows.getString(2)), rows.getLong(3)),
                                        rows.getString(4)?.let(AtprotoDid::require),
                                        rows.getString(5),
                                        ObjectUploadKind.valueOf(rows.getString(6)),
                                        rows.getString(7),
                                        rows.getString(8),
                                        rows.getLong(9).let { value -> if (rows.wasNull()) null else value },
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun prepare(
        upload: ObjectUpload,
        recovery: Boolean,
    ): Admission {
        val connection = accountWriteConnection()
        connection.lockUploadOwner(upload)
        when (phase(connection, upload)) {
            "WRITING", "ATTACHED" -> return Admission.SKIP
            "RETIRED" -> return Admission.FINISHED
            "STORED", "DELETE_PENDING" -> Unit
            else -> throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
        }
        if (recovery && !eligible(connection, upload)) return Admission.SKIP
        connection.lockObjectPath(upload.path)
        connection.requireExclusiveObjectPath(
            upload.origin.accountId.toJavaUUID(),
            LegacyObjectKind.UNREFERENCED,
            upload.path,
            upload.path,
        )
        retirePath(connection, upload)
        connection.setPhase(upload, "DELETE_PENDING")
        return Admission.DELETE
    }

    private fun graceCutoff(): Timestamp = Timestamp.from(java.time.Instant.parse((clock.now() - 2.minutes).toString()))

    private fun eligible(
        connection: Connection,
        upload: ObjectUpload,
    ): Boolean =
        connection
            .prepareStatement(
                "SELECT phase = 'DELETE_PENDING' OR (phase = 'STORED' AND settled_at <= ?) FROM object_upload_intents WHERE id = ?",
            ).use {
                it.setTimestamp(1, graceCutoff())
                it.setString(2, upload.id)
                it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }

    private fun retirePath(
        connection: Connection,
        upload: ObjectUpload,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO legacy_object_deletions(operation_id, account_id, kind, resource_id, " +
                    "identity_epoch, storage_path, completed) " +
                    "VALUES (?, ?, 'UNREFERENCED', ?, ?, ?, FALSE) ON CONFLICT (storage_path) DO NOTHING",
            ).use {
                it.setString(1, upload.id)
                it.setObject(2, upload.origin.accountId.toJavaUUID())
                it.setString(3, upload.path)
                it.setLong(4, upload.origin.identityEpoch)
                it.setString(5, upload.path)
                it.executeUpdate()
            }
        connection
            .prepareStatement(
                "SELECT operation_id, account_id, identity_epoch FROM legacy_object_deletions WHERE storage_path = ?",
            ).use {
                it.setString(1, upload.path)
                it.executeQuery().use { rows ->
                    if (!rows.next() ||
                        rows.getString(1) != upload.id ||
                        rows.getString(2) != upload.origin.accountId.toString() ||
                        rows.getLong(3) != upload.origin.identityEpoch
                    ) {
                        throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                    }
                }
            }
    }

    private fun requirePhase(
        connection: Connection,
        upload: ObjectUpload,
        expected: String,
    ) {
        if (phase(connection, upload) != expected) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
    }

    private fun phase(
        connection: Connection,
        upload: ObjectUpload,
    ): String =
        connection
            .prepareStatement(
                "SELECT phase FROM object_upload_intents WHERE id = ? AND account_id = ? AND identity_epoch = ? " +
                    "AND repo_did IS NOT DISTINCT FROM ? AND storage_path = ? AND upload_kind = ? " +
                    "AND space_uri IS NOT DISTINCT FROM ? AND legacy_migration_id IS NOT DISTINCT FROM ? " +
                    "AND legacy_migration_version IS NOT DISTINCT FROM ? FOR UPDATE",
            ).use {
                it.setString(1, upload.id)
                it.setObject(2, upload.origin.accountId.toJavaUUID())
                it.setLong(3, upload.origin.identityEpoch)
                it.setString(4, upload.repo?.toString())
                it.setString(5, upload.path)
                it.setString(6, upload.kind.name)
                it.setString(7, upload.space)
                it.setString(8, upload.legacyMigrationId)
                it.setObject(9, upload.legacyMigrationVersion)
                it.executeQuery().use { rows ->
                    if (!rows.next()) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
                    rows.getString(1)
                }
            }

    private fun Connection.lockUploadOwner(upload: ObjectUpload) {
        // A deleted owner has no row; the durable ticket still owns cleanup of its exact path.
        prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE").use {
            it.setObject(1, upload.origin.accountId.toJavaUUID())
            it.executeQuery().close()
        }
    }

    private fun Connection.setPhase(
        upload: ObjectUpload,
        phase: String,
    ) {
        prepareStatement("UPDATE object_upload_intents SET phase = ? WHERE id = ?").use {
            it.setString(1, phase)
            it.setString(2, upload.id)
            check(it.executeUpdate() == 1)
        }
    }
}
