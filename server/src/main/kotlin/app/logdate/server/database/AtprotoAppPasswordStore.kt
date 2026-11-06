package app.logdate.server.database

import app.logdate.server.atproto.AtprotoAppPassword
import app.logdate.server.atproto.AtprotoSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class AtprotoAppPasswordStore(
    private val source: DataSource,
) {
    data class Entry(
        val id: String,
        val name: String,
        val createdAt: String,
    )

    suspend fun list(accountId: Uuid): List<Entry> =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection.prepareStatement(LIST).use { statement ->
                    statement.setString(1, accountId.toString())
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    Entry(
                                        rows.getString("id"),
                                        rows.getString("name"),
                                        rows.getTimestamp("created_at").toInstant().toString(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

    /** Invoke after a fresh passkey verifies the requested name, account and DID. */
    suspend fun issue(
        accountId: Uuid,
        did: String,
        name: String,
    ): AtprotoAppPassword? =
        withContext(Dispatchers.IO) {
            val label = name.trim()
            require(label.length in 1..64 && label.none(Char::isISOControl))
            val password = AtprotoAppPassword.generate()
            source.connection.use { connection ->
                connection.autoCommit = true
                connection.prepareStatement(ISSUE).use { statement ->
                    statement.setString(1, Uuid.random().toString())
                    statement.setString(2, label)
                    statement.setString(3, password.verifier)
                    statement.setString(4, accountId.toString())
                    statement.setString(5, did)
                    password.takeIf { statement.executeUpdate() == 1 }
                }
            }
        }

    data class Match(
        val accountId: Uuid,
        val passwordId: Uuid,
        val did: String,
    )

    suspend fun match(
        identifier: String,
        value: String,
    ): Match? =
        withContext(Dispatchers.IO) {
            val verifier = AtprotoAppPassword.verifier(value) ?: return@withContext null
            source.connection.use { connection ->
                connection.prepareStatement(MATCH).use { statement ->
                    statement.setString(1, verifier)
                    statement.setString(2, identifier)
                    statement.setString(3, identifier)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            Match(Uuid.parse(rows.getString("account_id")), Uuid.parse(rows.getString("id")), rows.getString("did"))
                        } else {
                            null
                        }
                    }
                }
            }
        }

    suspend fun startSession(
        session: AtprotoSession,
        did: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val passwordId = session.appPasswordId ?: return@withContext false
            source.connection.use { connection ->
                connection.autoCommit = true
                connection.prepareStatement(START_SESSION).use { statement ->
                    statement.setString(1, session.id)
                    statement.setTimestamp(2, Timestamp(session.createdAtEpochMillis))
                    statement.setTimestamp(3, Timestamp(session.refreshExpiresAtEpochMillis))
                    statement.setString(4, passwordId.toString())
                    statement.setString(5, session.accountId.toString())
                    statement.setString(6, did)
                    statement.executeUpdate() == 1
                }
            }
        }

    suspend fun revoke(
        accountId: Uuid,
        passwordId: Uuid,
    ): Boolean =
        withContext(Dispatchers.IO) {
            source.connection.use { connection ->
                connection.autoCommit = true
                connection.prepareStatement(REVOKE).use { statement ->
                    statement.setString(1, passwordId.toString())
                    statement.setString(2, accountId.toString())
                    statement.executeQuery().use { it.next() }
                }
            }
        }

    private companion object {
        private const val ISSUE = """
            INSERT INTO atproto_app_passwords (id, account_id, did, name, verifier)
            SELECT ?::uuid, a.id, a.did, ?, ?
            FROM accounts a
            WHERE a.id = ?::uuid AND a.did = ? AND a.public_identity_did = a.did
                AND a.is_active AND a.moderation IS NULL
                AND EXISTS (SELECT 1 FROM hosted_plc_operations o WHERE o.account_id = a.id AND o.did = a.did)
            FOR UPDATE OF a
            ON CONFLICT DO NOTHING
        """

        private const val ELIGIBLE = """
            p.revoked_at IS NULL AND p.did = a.did AND a.public_identity_did = a.did
                AND a.is_active AND a.moderation IS NULL
                AND EXISTS (SELECT 1 FROM hosted_plc_operations o WHERE o.account_id = a.id AND o.did = a.did)
        """

        private const val LIST =
            """
            SELECT p.id, p.name, p.created_at FROM atproto_app_passwords p JOIN accounts a ON a.id = p.account_id
            WHERE a.id = ?::uuid AND
        """ + ELIGIBLE + " ORDER BY p.created_at DESC"

        private const val MATCH =
            """
            SELECT p.id, p.account_id, p.did FROM atproto_app_passwords p JOIN accounts a ON a.id = p.account_id
            WHERE p.verifier = ? AND (a.handle = ? OR a.did = ?) AND
        """ + ELIGIBLE

        private const val START_SESSION =
            """
            INSERT INTO atproto_sessions (id, account_id, app_password_id, created_at, refresh_expires_at)
            SELECT ?, a.id, p.id, ?, ? FROM atproto_app_passwords p JOIN accounts a ON a.id = p.account_id
            WHERE p.id = ?::uuid AND a.id = ?::uuid AND p.did = ? AND
        """ + ELIGIBLE + " FOR UPDATE OF p, a"

        private const val REVOKE = """
            WITH revoked AS (
                UPDATE atproto_app_passwords SET revoked_at = now()
                WHERE id = ?::uuid AND account_id = ?::uuid AND revoked_at IS NULL
                RETURNING id, account_id
            ), ended AS (
                UPDATE atproto_sessions s SET revoked_at = now()
                FROM revoked r WHERE s.app_password_id = r.id AND s.account_id = r.account_id AND s.revoked_at IS NULL
            )
            SELECT 1 FROM revoked
        """
    }
}
