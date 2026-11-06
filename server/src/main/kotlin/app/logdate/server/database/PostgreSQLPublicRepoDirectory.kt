package app.logdate.server.database

import app.logdate.server.atproto.firehose.PublicRepoCandidate
import app.logdate.server.atproto.firehose.PublicRepoDirectory
import app.logdate.shared.model.AccountStorageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The repositories of accounts that may be shown, found with one indexed query per page: accounts joined
 * to repository heads, filtered by the same per-account consent rules as `AccountPublicRepoGate`, in DID order.
 */
class PostgreSQLPublicRepoDirectory : PublicRepoDirectory {
    override suspend fun after(
        afterDid: String?,
        limit: Int,
    ): List<PublicRepoCandidate> =
        withContext(Dispatchers.IO) {
            transaction {
                val publicIdentity =
                    (AccountsTable.did like "did:web:%") or (AccountsTable.did like "did:plc:%")
                AccountsTable
                    .join(
                        AtprotoRepoHeadsTable,
                        JoinType.INNER,
                        additionalConstraint = { AccountsTable.did eq AtprotoRepoHeadsTable.repoDid },
                    ).selectAll()
                    .where {
                        (AccountsTable.isActive eq true) and
                            AccountsTable.moderation.isNull() and
                            (AccountsTable.storageMode eq AccountStorageMode.PERSONAL_SPACE_V1.wireValue) and
                            (AccountsTable.publicIdentityDid eq AccountsTable.did) and
                            publicIdentity and
                            (if (afterDid == null) Op.TRUE else AtprotoRepoHeadsTable.repoDid greater afterDid)
                    }.orderBy(AtprotoRepoHeadsTable.repoDid, SortOrder.ASC)
                    .limit(limit)
                    .map { row ->
                        PublicRepoCandidate(
                            did = row[AtprotoRepoHeadsTable.repoDid],
                            rootCid = row[AtprotoRepoHeadsTable.rootCid],
                            commitCid = row[AtprotoRepoHeadsTable.commitCid],
                            revision = row[AtprotoRepoHeadsTable.revision],
                        )
                    }
            }
        }
}
