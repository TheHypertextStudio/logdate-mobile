package app.logdate.server.database

import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.adoption.AccountWriteOrigin
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.shared.model.AccountIdentityKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import studio.hypertext.atproto.identity.AtprotoDid
import java.sql.Connection
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi

internal class OwnedRepoTransaction(
    val connection: Connection,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OwnedRepoTransaction>
}

internal suspend fun requireRepoWriteOrigin(): IdentityWriteExpectation =
    currentCoroutineContext()[AccountWriteOrigin]?.expectation
        ?: throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)

internal fun checkOwnedRepoTransaction(marker: OwnedRepoTransaction?) {
    if (TransactionManager.currentOrNull() != null) {
        if (marker == null || marker.connection !== accountWriteConnection()) {
            throw IdentityLifecycleConflictException("Repository writes must start before the database transaction")
        }
    } else if (marker != null) {
        throw IdentityLifecycleConflictException("The repository transaction is no longer active")
    }
}

@OptIn(ExperimentalUuidApi::class)
internal fun requireHostedRepoAccount(origin: IdentityWriteExpectation) {
    val accountId = origin.accountId.toJavaUUID()
    requireAccountIdentityWrite(accountId, origin.identityEpoch)
    val hosted =
        accountWriteConnection().prepareStatement("SELECT identity_kind FROM accounts WHERE id = ?").use {
            it.setObject(1, accountId)
            it.executeQuery().use { rows -> rows.next() && rows.getString(1) == AccountIdentityKind.HOSTED.wireValue }
        }
    if (!hosted) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
}

@OptIn(ExperimentalUuidApi::class)
internal fun requirePreparedRepoIdentity(origin: IdentityWriteExpectation) {
    val id = origin.accountId.toJavaUUID()
    val prepared =
        accountWriteConnection()
            .prepareStatement(
                "SELECT did IS NOT NULL OR EXISTS(SELECT 1 FROM logdate_collection_states WHERE user_id = ?) " +
                    "FROM accounts WHERE id = ?",
            ).use {
                it.setObject(1, id)
                it.setObject(2, id)
                it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
    if (!prepared) throw IdentityLifecycleConflictException("Prepare the identity before repository writes")
}

@OptIn(ExperimentalUuidApi::class)
internal fun requireRepoBinding(
    origin: IdentityWriteExpectation,
    repo: AtprotoDid,
) {
    val connection = accountWriteConnection()
    if (connection.identityRetired(repo.toString())) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
    val owners =
        connection
            .prepareStatement(
                "SELECT id FROM accounts WHERE did = ? UNION SELECT user_id FROM logdate_collection_states WHERE repo_did = ?",
            ).use {
                it.setString(1, repo.toString())
                it.setString(2, repo.toString())
                it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, java.util.UUID::class.java)) } }
            }
    val account = origin.accountId.toJavaUUID()
    val synthetic = repo.toString() == "did:logdate:$account"
    if (owners.any { it != account } || (account !in owners && !synthetic)) {
        throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
    }
}

internal suspend fun <T> ownedRepoMutation(
    repo: AtprotoDid,
    action: () -> T,
): T {
    val origin = requireRepoWriteOrigin()
    val marker = currentCoroutineContext()[OwnedRepoTransaction]
    return withContext(Dispatchers.IO) {
        checkOwnedRepoTransaction(marker)
        transaction {
            requireHostedRepoAccount(origin)
            requireRepoBinding(origin, repo)
            action()
        }
    }
}
