package app.logdate.server.database

import app.logdate.server.identity.adoption.AccountIdentityFence
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import java.sql.Connection
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

/** The caller must keep this transaction open through its mutation and acquire no earlier row locks. */
@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLAccountIdentityFence(
    private val connection: Connection,
) : AccountIdentityFence {
    override fun lockAndCheck(
        expectation: IdentityWriteExpectation,
        now: Instant,
    ): IdentityFenceOutcome {
        check(!connection.autoCommit) { "An identity fence requires the mutation transaction" }
        check(connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) {
            "An identity fence requires read committed isolation"
        }
        val account = expectation.accountId.toJavaUUID()
        val current = lockAccount(account, expectation.identityEpoch)
        if (current != IdentityFenceOutcome.ALLOWED) return current
        if (connection.accountIdRetired(account)) return IdentityFenceOutcome.DELETED
        connection
            .prepareStatement(
                "UPDATE identity_adoption_operations SET phase = 'PREPARING', revision = revision + 1, updated_at = ? " +
                    "WHERE account_id = ? AND phase IN ('FENCED', 'VERIFIED') AND lease_expires_at <= ?",
            ).use {
                it.setTimestamp(1, now.timestamp())
                it.setObject(2, account)
                it.setTimestamp(3, now.timestamp())
                it.executeUpdate()
            }
        return connection
            .prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM identity_adoption_operations " +
                    "WHERE account_id = ? AND phase IN ('FENCED', 'VERIFIED'))",
            ).use {
                it.setObject(1, account)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    if (rows.getBoolean(1)) IdentityFenceOutcome.HELD else IdentityFenceOutcome.ALLOWED
                }
            }
    }

    private fun lockAccount(
        account: java.util.UUID,
        epoch: Long,
    ): IdentityFenceOutcome =
        connection
            .prepareStatement(
                "SELECT identity_epoch, is_active, moderation, identity_kind, storage_mode, did FROM accounts WHERE id = ? FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, account)
                statement.executeQuery().use accountRows@{ rows ->
                    if (!rows.next() || !rows.getBoolean("is_active") || rows.getString("moderation") != null) {
                        return@accountRows IdentityFenceOutcome.DELETED
                    }
                    if (rows.getLong("identity_epoch") != epoch) return@accountRows IdentityFenceOutcome.CHANGED
                    val kind = AccountIdentityKind.fromWireValue(rows.getString("identity_kind"))
                    val mode = AccountStorageMode.fromWireValue(rows.getString("storage_mode"))
                    if (kind == AccountIdentityKind.UNRECOGNIZED ||
                        mode == AccountStorageMode.UNRECOGNIZED ||
                        (kind == AccountIdentityKind.EXTERNAL && mode != AccountStorageMode.PERSONAL_SPACE_V1)
                    ) {
                        return@accountRows IdentityFenceOutcome.CHANGED
                    }
                    val did = rows.getString("did")
                    if (did != null && connection.identityRetired(did)) IdentityFenceOutcome.CHANGED else IdentityFenceOutcome.ALLOWED
                }
            }
}

@OptIn(ExperimentalUuidApi::class)
internal fun Connection.lockIdentityWrite(
    expectation: IdentityWriteExpectation,
    now: Instant,
    space: String? = null,
    author: String? = null,
): IdentityFenceOutcome {
    val outcome = PostgreSQLAccountIdentityFence(this).lockAndCheck(expectation, now)
    if (outcome != IdentityFenceOutcome.ALLOWED) return outcome
    val identities =
        listOfNotNull(
            space?.let {
                app.logdate.space.SpaceRef
                    .parse(it)
                    ?.authority
            },
            author,
        ).distinct()
    for (did in identities) {
        if (identityRetired(did)) return IdentityFenceOutcome.CHANGED
    }
    return IdentityFenceOutcome.ALLOWED
}
