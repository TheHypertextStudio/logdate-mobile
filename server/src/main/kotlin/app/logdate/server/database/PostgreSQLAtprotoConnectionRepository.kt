@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.bluesky.connection.AtprotoConnectionRepository
import app.logdate.server.bluesky.connection.ConnectionChanged
import app.logdate.server.bluesky.connection.ConnectionSnapshot
import app.logdate.server.bluesky.connection.DisconnectDecision
import app.logdate.server.bluesky.connection.DisconnectReceipt
import app.logdate.shared.model.AtprotoConnectionState
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class PostgreSQLAtprotoConnectionRepository(
    private val source: DataSource,
    private val clock: Clock = Clock.System,
) : AtprotoConnectionRepository {
    override suspend fun snapshot(did: AtprotoDid): ConnectionSnapshot? = source.blueskyTransaction { atprotoConnectionSnapshot(did) }

    override suspend fun createDecision(decision: DisconnectDecision) {
        val now = clock.now()
        require(decision.expiresAt > now && decision.expiresAt <= now + 5.minutes)
        source.atprotoStateTransaction(decision.did) {
            if (!matchesCurrent(decision)) throw ConnectionChanged()
            prepareStatement(
                "INSERT INTO atproto_disconnect_decisions " +
                    "(decision_key, account_id, did, expected_revision, challenge_hash, created_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setString(1, key(decision.id))
                it.setObject(2, decision.accountId.toJavaUUID())
                it.setString(3, decision.did.toString())
                it.setLong(4, decision.expectedRevision)
                it.setString(5, decision.challengeHash)
                it.setTimestamp(6, now.timestamp())
                it.setTimestamp(7, decision.expiresAt.timestamp())
                it.executeUpdate()
            }
        }
    }

    override suspend fun authorizeDecision(
        accountId: Uuid,
        decisionId: String,
        challengeHash: String,
        now: Instant,
    ): DisconnectDecision? =
        withDecision(decisionId) { rows ->
            val decision = rows.decision(decisionId)
            if (decision.accountId != accountId ||
                decision.challengeHash != challengeHash ||
                now >= decision.expiresAt ||
                now < rows.getTimestamp("created_at").connectionInstant() ||
                rows.getTimestamp("completed_at") != null ||
                !matchesCurrent(decision)
            ) {
                return@withDecision null
            }
            prepareStatement(
                "UPDATE atproto_disconnect_decisions SET authorized_at = COALESCE(authorized_at, ?) WHERE decision_key = ?",
            ).use {
                it.setTimestamp(1, now.timestamp())
                it.setString(2, key(decisionId))
                it.executeUpdate()
            }
            decision
        }

    override suspend fun pendingDecision(
        accountId: Uuid,
        decisionId: String,
    ): DisconnectDecision? =
        source.blueskyTransaction {
            prepareStatement(
                "SELECT * FROM atproto_disconnect_decisions WHERE account_id = ? AND decision_key = ? AND completed_at IS NULL",
            ).use {
                it.setObject(1, accountId.toJavaUUID())
                it.setString(2, key(decisionId))
                it.executeQuery().use { rows -> if (rows.next()) rows.decision(decisionId) else null }
            }
        }

    override suspend fun authorizedDecision(
        accountId: Uuid,
        decisionId: String,
        now: Instant,
    ): DisconnectDecision? =
        withDecision(decisionId) { rows ->
            val decision = rows.decision(decisionId)
            decision.takeIf {
                it.accountId == accountId &&
                    now < it.expiresAt &&
                    rows.getTimestamp("authorized_at")?.connectionInstant()?.let { at -> at <= now } == true &&
                    rows.getTimestamp("completed_at") == null &&
                    matchesCurrent(it)
            }
        }

    override suspend fun completeDisconnect(decisionId: String): DisconnectReceipt =
        withDecision(decisionId) { rows ->
            rows.receipt(decisionId)?.let { return@withDecision it }
            val decision = rows.decision(decisionId)
            if (rows.getTimestamp("authorized_at") == null || !matchesCurrent(decision)) throw ConnectionChanged()
            // The caller holds the SDK DID lock: an already-authorized operation may finish after expiry.
            prepareStatement("DELETE FROM bluesky_oauth_sessions WHERE did = ?").use {
                it.setString(1, decision.did.toString())
                it.executeUpdate()
            }
            val now = clock.now()
            val snapshot = advanceAtprotoConnection(decision.did, AtprotoConnectionState.DISCONNECTED, now)
            prepareStatement(
                "UPDATE atproto_disconnect_decisions SET completed_at = ?, disconnected_revision = ? WHERE decision_key = ?",
            ).use {
                it.setTimestamp(1, now.timestamp())
                it.setLong(2, snapshot.revision)
                it.setString(3, key(decisionId))
                it.executeUpdate()
            }
            DisconnectReceipt(decisionId, snapshot.revision, now, false)
        } ?: throw ConnectionChanged()

    override suspend fun confirmRemote(decisionId: String): DisconnectReceipt =
        withDecision(decisionId) { rows ->
            val receipt = rows.receipt(decisionId) ?: throw ConnectionChanged()
            prepareStatement("UPDATE atproto_disconnect_decisions SET remote_confirmed = TRUE WHERE decision_key = ?").use {
                it.setString(1, key(decisionId))
                it.executeUpdate()
            }
            receipt.copy(remoteConfirmed = true)
        } ?: throw ConnectionChanged()

    override suspend fun receipt(
        accountId: Uuid,
        decisionId: String,
    ): DisconnectReceipt? =
        source.blueskyTransaction {
            prepareStatement("SELECT * FROM atproto_disconnect_decisions WHERE decision_key = ? AND account_id = ?").use {
                it.setString(1, key(decisionId))
                it.setObject(2, accountId.toJavaUUID())
                it.executeQuery().use { rows -> if (rows.next()) rows.receipt(decisionId) else null }
            }
        }

    private suspend fun <T> withDecision(
        decisionId: String,
        block: Connection.(ResultSet) -> T,
    ): T? {
        val did =
            source.blueskyTransaction {
                prepareStatement("SELECT did FROM atproto_disconnect_decisions WHERE decision_key = ?").use {
                    it.setString(1, key(decisionId))
                    it.executeQuery().use { rows -> if (rows.next()) AtprotoDid.require(rows.getString(1)) else null }
                }
            } ?: return null
        return source.atprotoStateTransaction(did) {
            prepareStatement("SELECT * FROM atproto_disconnect_decisions WHERE decision_key = ? FOR UPDATE").use {
                it.setString(1, key(decisionId))
                it.executeQuery().use { rows -> if (rows.next()) block(rows) else null }
            }
        }
    }

    private fun Connection.matchesCurrent(decision: DisconnectDecision): Boolean {
        if ((atprotoConnectionSnapshot(decision.did)?.revision ?: 0L) != decision.expectedRevision) return false
        return prepareStatement(
            "SELECT 1 FROM accounts WHERE id = ? AND did = ? AND identity_kind = 'external' AND is_active = TRUE",
        ).use {
            it.setObject(1, decision.accountId.toJavaUUID())
            it.setString(2, decision.did.toString())
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun ResultSet.decision(id: String): DisconnectDecision =
        DisconnectDecision(
            id = id,
            accountId = Uuid.parse(getString("account_id")),
            did = AtprotoDid.require(getString("did")),
            expectedRevision = getLong("expected_revision"),
            challengeHash = getString("challenge_hash"),
            expiresAt = getTimestamp("expires_at").connectionInstant(),
        )

    private fun ResultSet.receipt(id: String): DisconnectReceipt? {
        val completed = getTimestamp("completed_at")?.connectionInstant() ?: return null
        if (completed + 24.hours <= clock.now()) return null
        return DisconnectReceipt(id, getLong("disconnected_revision"), completed, getBoolean("remote_confirmed"))
    }

    private fun key(id: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.encodeToByteArray()))
}
