@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun Connection.lockIdentityAdoptionBrowserProposal(id: Uuid) {
    prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
        it.setString(1, "logdate:identity-adoption-proposal:$id")
        it.executeQuery().close()
    }
}

internal fun identityAdoptionBrowserHash(value: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))

internal fun identityAdoptionBrowserChallengeHash(challenge: String): String =
    identityAdoptionBrowserHash("app.logdate.identity-adoption.browser-app-proof|1:$challenge")

/** Caller holds the proposal advisory lock and originating account row. */
internal fun Connection.registerIdentityAdoptionBrowser(
    binding: IdentityAdoptionOAuthBinding,
    attemptKey: String,
    challenge: String,
    now: Instant,
    expiresAt: Instant,
) {
    val hash = identityAdoptionBrowserChallengeHash(challenge)
    prepareStatement(
        "INSERT INTO identity_adoption_browser_proposals(proposal_id,account_id,source_did,source_epoch,device_id," +
            "attempt_key,challenge_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
    ).use {
        it.setObject(1, binding.proposalId.toJavaUUID())
        it.setObject(2, binding.accountId.toJavaUUID())
        it.setString(3, binding.sourceDid)
        it.setLong(4, binding.sourceEpoch)
        it.setString(5, binding.deviceId)
        it.setString(6, attemptKey)
        it.setString(7, hash)
        it.setTimestamp(8, now.timestamp())
        it.setTimestamp(9, expiresAt.timestamp())
        it.executeUpdate()
    }
    val matches =
        prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM identity_adoption_browser_proposals WHERE proposal_id=? AND account_id=? " +
                "AND source_did=? AND source_epoch=? AND device_id=? AND attempt_key=? AND challenge_hash=? AND canceled_at IS NULL)",
        ).use {
            it.setObject(1, binding.proposalId.toJavaUUID())
            it.setObject(2, binding.accountId.toJavaUUID())
            it.setString(3, binding.sourceDid)
            it.setLong(4, binding.sourceEpoch)
            it.setString(5, binding.deviceId)
            it.setString(6, attemptKey)
            it.setString(7, hash)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }
    if (!matches) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
}

internal fun Connection.identityAdoptionBrowserAllows(
    binding: IdentityAdoptionOAuthBinding,
    requireRecord: Boolean = true,
): Boolean =
    prepareStatement("SELECT * FROM identity_adoption_browser_proposals WHERE proposal_id = ?").use {
        it.setObject(1, binding.proposalId.toJavaUUID())
        it.executeQuery().use { rows ->
            if (!rows.next()) return@use !requireRecord
            rows.getObject("account_id").toString() == binding.accountId.toString() &&
                rows.getString("source_did") == binding.sourceDid &&
                rows.getLong("source_epoch") == binding.sourceEpoch &&
                rows.getString("device_id") == binding.deviceId &&
                rows.getTimestamp("canceled_at") == null
        }
    }
