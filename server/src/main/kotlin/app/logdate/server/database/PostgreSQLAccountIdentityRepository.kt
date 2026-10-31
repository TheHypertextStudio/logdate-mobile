package app.logdate.server.database

import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountLinkEvent
import app.logdate.server.auth.IdentityProvider
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLAccountIdentityRepository : AccountIdentityRepository {
    override suspend fun save(identity: AccountIdentity): AccountIdentity =
        dbQuery {
            val existing =
                AccountIdentitiesTable
                    .selectAll()
                    .where {
                        (AccountIdentitiesTable.provider eq identity.provider.name) and
                            (AccountIdentitiesTable.providerSubject eq identity.providerSubject)
                    }.singleOrNull()

            if (existing == null) {
                AccountIdentitiesTable.insert {
                    it[id] = identity.id.toJavaUUID()
                    it[accountId] = identity.accountId.toJavaUUID()
                    it[provider] = identity.provider.name
                    it[providerSubject] = identity.providerSubject
                    it[email] = identity.email
                    it[emailVerified] = identity.emailVerified
                    it[createdAt] = identity.createdAt
                    it[lastSignInAt] = identity.lastSignInAt
                    it[metadataJson] = identity.metadataJson
                }
                identity
            } else {
                val identityId = existing[AccountIdentitiesTable.id].toKotlinUuid()
                AccountIdentitiesTable.update({ AccountIdentitiesTable.id eq identityId.toJavaUUID() }) {
                    it[accountId] = identity.accountId.toJavaUUID()
                    it[email] = identity.email
                    it[emailVerified] = identity.emailVerified
                    it[lastSignInAt] = identity.lastSignInAt
                    it[metadataJson] = identity.metadataJson
                }
                identity.copy(id = identityId)
            }
        }

    override suspend fun findByProviderSubject(
        provider: IdentityProvider,
        providerSubject: String,
    ): AccountIdentity? =
        dbQuery {
            AccountIdentitiesTable
                .selectAll()
                .where {
                    (AccountIdentitiesTable.provider eq provider.name) and
                        (AccountIdentitiesTable.providerSubject eq providerSubject)
                }.singleOrNull()
                ?.toAccountIdentity()
        }

    override suspend fun findByAccountId(accountId: Uuid): List<AccountIdentity> =
        dbQuery {
            AccountIdentitiesTable
                .selectAll()
                .where { AccountIdentitiesTable.accountId eq accountId.toJavaUUID() }
                .map { it.toAccountIdentity() }
        }

    override suspend fun findByVerifiedEmail(email: String): List<AccountIdentity> =
        dbQuery {
            AccountIdentitiesTable
                .selectAll()
                .where {
                    (AccountIdentitiesTable.email eq email) and
                        (AccountIdentitiesTable.emailVerified eq true)
                }.map { it.toAccountIdentity() }
        }

    override suspend fun touchLastSignIn(identityId: Uuid): Boolean =
        dbQuery {
            AccountIdentitiesTable.update({ AccountIdentitiesTable.id eq identityId.toJavaUUID() }) {
                it[lastSignInAt] = Clock.System.now()
            } > 0
        }

    override suspend fun saveLinkEvent(event: AccountLinkEvent): AccountLinkEvent =
        dbQuery {
            AccountLinkEventsTable.insert {
                it[id] = event.id.toJavaUUID()
                it[accountId] = event.accountId.toJavaUUID()
                it[provider] = event.provider.name
                it[providerSubject] = event.providerSubject
                it[reason] = event.reason
                it[ipHash] = event.ipHash
                it[userAgentHash] = event.userAgentHash
                it[createdAt] = event.createdAt
                it[metadataJson] = event.metadataJson
            }
            event
        }

    private fun ResultRow.toAccountIdentity(): AccountIdentity =
        AccountIdentity(
            id = this[AccountIdentitiesTable.id].toKotlinUuid(),
            accountId = this[AccountIdentitiesTable.accountId].toKotlinUuid(),
            provider = IdentityProvider.valueOf(this[AccountIdentitiesTable.provider]),
            providerSubject = this[AccountIdentitiesTable.providerSubject],
            email = this[AccountIdentitiesTable.email],
            emailVerified = this[AccountIdentitiesTable.emailVerified],
            createdAt = this[AccountIdentitiesTable.createdAt],
            lastSignInAt = this[AccountIdentitiesTable.lastSignInAt],
            metadataJson = this[AccountIdentitiesTable.metadataJson],
        )
}
