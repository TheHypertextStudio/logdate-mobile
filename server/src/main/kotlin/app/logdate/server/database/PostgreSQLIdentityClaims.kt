package app.logdate.server.database

import app.logdate.server.auth.retiredAccountIdDigest
import java.sql.Connection
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

internal fun Connection.lockIdentityClaim(did: String) {
    prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
        it.setString(1, "logdate:identity-claim:$did")
        it.executeQuery().use { rows -> check(rows.next()) }
    }
}

internal fun Connection.identityClaimAvailable(
    did: String,
    exceptAccountId: UUID? = null,
): Boolean =
    prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM accounts WHERE did = ?" + (if (exceptAccountId == null) "" else " AND id <> ?") + ") " +
            "OR EXISTS(SELECT 1 FROM hosted_plc_operations WHERE did = ?" +
            (if (exceptAccountId == null) "" else " AND account_id <> ?") + ") " +
            "OR EXISTS(SELECT 1 FROM identity_adoption_reservations WHERE target_did = ?) " +
            "OR EXISTS(SELECT 1 FROM retired_identity_dids WHERE source_did = ?)",
    ).use {
        var index = 1
        it.setString(index++, did)
        if (exceptAccountId != null) it.setObject(index++, exceptAccountId)
        it.setString(index++, did)
        if (exceptAccountId != null) it.setObject(index++, exceptAccountId)
        it.setString(index++, did)
        it.setString(index, did)
        it.executeQuery().use { rows ->
            check(rows.next())
            !rows.getBoolean(1)
        }
    }

internal fun Connection.lockAccountIdClaim(accountId: UUID) {
    prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
        it.setString(1, "logdate:account-id-claim:$accountId")
        it.executeQuery().use { rows -> check(rows.next()) }
    }
}

@OptIn(ExperimentalUuidApi::class)
internal fun Connection.accountIdRetired(accountId: UUID): Boolean =
    prepareStatement("SELECT EXISTS(SELECT 1 FROM retired_account_ids WHERE account_id_hash = ?)").use {
        it.setString(1, retiredAccountIdDigest(accountId.toKotlinUuid()))
        it.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }

@OptIn(ExperimentalUuidApi::class)
internal fun Connection.retireAccountId(accountId: UUID) {
    prepareStatement("INSERT INTO retired_account_ids(account_id_hash, deleted_at) VALUES (?, ?) ON CONFLICT DO NOTHING").use {
        it.setString(1, retiredAccountIdDigest(accountId.toKotlinUuid()))
        it.setTimestamp(
            2,
            kotlin.time.Clock.System
                .now()
                .timestamp(),
        )
        it.executeUpdate()
    }
}

internal fun Connection.identityRetired(did: String): Boolean =
    prepareStatement("SELECT EXISTS(SELECT 1 FROM retired_identity_dids WHERE source_did = ?)").use {
        it.setString(1, did)
        it.executeQuery().use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }
