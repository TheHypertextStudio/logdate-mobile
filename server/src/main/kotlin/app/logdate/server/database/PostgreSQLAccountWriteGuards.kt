package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.shared.model.AccountIdentityKind
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal fun accountWriteConnection(): Connection = TransactionManager.current().connection.connection as Connection

/** DID claims precede the account-ID claim and account row throughout account creation. */
@OptIn(ExperimentalUuidApi::class)
internal fun lockAccountCreationClaims(account: Account) {
    lockAccountDidClaims(account.did)
    if (TransactionManager.current().db.vendor == "postgresql") accountWriteConnection().lockAccountIdClaim(account.id.toJavaUUID())
}

internal fun lockAccountDidClaims(vararg dids: String?) {
    if (TransactionManager.current().db.vendor != "postgresql") return
    dids
        .filterNotNull()
        .distinct()
        .sorted()
        .forEach(accountWriteConnection()::lockIdentityClaim)
}

@OptIn(ExperimentalUuidApi::class)
internal fun accountIdentityWriteOutcome(account: Account): IdentityFenceOutcome =
    accountWriteConnection().lockIdentityWrite(
        IdentityWriteExpectation(account.id, account.identityEpoch),
        Clock.System.now(),
        author = account.did,
    )

/** The account lock remains held through the caller's key mutation. Legacy callers own epoch zero. */
@OptIn(ExperimentalUuidApi::class)
internal fun requireHostedIdentityWrite(
    accountId: Uuid,
    identityEpoch: Long,
) {
    val connection = accountWriteConnection()
    val outcome = connection.lockIdentityWrite(IdentityWriteExpectation(accountId, identityEpoch), Clock.System.now())
    if (outcome != IdentityFenceOutcome.ALLOWED) throw IdentityWriteRefusedException(outcome)
    connection.prepareStatement("SELECT identity_kind, did FROM accounts WHERE id = ?").use {
        it.setObject(1, accountId.toJavaUUID())
        it.executeQuery().use { rows ->
            check(rows.next())
            val did = rows.getString("did")
            if (rows.getString("identity_kind") != AccountIdentityKind.HOSTED.wireValue ||
                (did != null && connection.identityRetired(did))
            ) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
            }
        }
    }
}

/** DID claim precedes the account; history cannot assign another owner's or retired identity. */
@OptIn(ExperimentalUuidApi::class)
internal fun requireHostedPlcWrite(
    accountId: Uuid,
    did: String,
    identityEpoch: Long,
) {
    lockAccountDidClaims(did)
    requireHostedIdentityWrite(accountId, identityEpoch)
    requireHostedDidClaim(accountId, did)
}

/** Called while the account row is locked; reads claim ownership without acquiring another DID lock. */
@OptIn(ExperimentalUuidApi::class)
internal fun requireHostedDidClaim(
    accountId: Uuid,
    did: String,
) {
    val connection = accountWriteConnection()
    if (!connection.identityClaimAvailable(did, accountId.toJavaUUID())) {
        throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
    }
    connection.prepareStatement("SELECT did FROM accounts WHERE id = ?").use {
        it.setObject(1, accountId.toJavaUUID())
        it.executeQuery().use { rows ->
            check(rows.next())
            val currentDid = rows.getString(1)
            if (currentDid != null && currentDid.trim().lowercase() != did.trim().lowercase()) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
            }
        }
    }
}

/** Account ownership and originating epoch are checked in the metadata mutation's transaction. */
@OptIn(ExperimentalUuidApi::class)
internal fun requireAccountIdentityWrite(
    accountId: java.util.UUID,
    identityEpoch: Long,
) {
    val outcome =
        accountWriteConnection().lockIdentityWrite(
            IdentityWriteExpectation(accountId.toKotlinUuid(), identityEpoch),
            Clock.System.now(),
        )
    if (outcome != IdentityFenceOutcome.ALLOWED) throw IdentityWriteRefusedException(outcome)
}
