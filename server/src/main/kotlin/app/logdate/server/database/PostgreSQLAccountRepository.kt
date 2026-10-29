package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountModeration
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.requireValidNewIdentity
import app.logdate.server.entitlements.AccountEntitlementsTable
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLAccountRepository : AccountRepository {
    override suspend fun create(account: Account): Boolean =
        transaction {
            account.requireValidNewIdentity()
            AccountsTable
                .insertIgnore {
                    it[id] = account.id.toJavaUUID()
                    it[username] = account.username
                    it[displayName] = account.displayName
                    it[did] = account.did
                    it[handle] = account.handle
                    it[signingKeyPublic] = account.signingKeyPublic
                    it[plcRecoveryDidKey] = account.plcRecoveryDidKey
                    it[email] = account.email
                    it[emailVerified] = account.emailVerified
                    it[emailVerifiedAt] = account.emailVerifiedAt
                    it[bio] = account.bio
                    it[originalBio] = account.originalBio
                    it[createdAt] = account.createdAt
                    it[lastSignInAt] = account.lastSignInAt
                    it[isActive] = account.isActive
                    it[preferences] = account.preferences ?: "{}"
                    it[storageMode] = account.newAccountStorageMode()
                    it[identityKind] = account.identityKind.wireValue
                    it[publicIdentityDid] = account.publicIdentityDid
                }.insertedCount > 0
        }

    override suspend fun save(account: Account): Account =
        transaction {
            val existingAccount =
                AccountsTable
                    .selectAll()
                    .where { AccountsTable.id eq account.id.toJavaUUID() }
                    .forUpdate()
                    .singleOrNull()

            if (existingAccount != null) {
                // Update existing account
                AccountsTable.update({ AccountsTable.id eq account.id.toJavaUUID() }) {
                    it[username] = account.username
                    it[displayName] = account.displayName
                    if (existingAccount[AccountsTable.identityKind] == AccountIdentityKind.HOSTED.wireValue) {
                        it[did] = account.did
                        it[handle] = account.handle
                        it[signingKeyPublic] = account.signingKeyPublic
                        it[plcRecoveryDidKey] = account.plcRecoveryDidKey
                    }
                    it[email] = account.email
                    it[emailVerified] = account.emailVerified
                    it[emailVerifiedAt] = account.emailVerifiedAt
                    it[bio] = account.bio
                    it[originalBio] = account.originalBio
                    it[lastSignInAt] = account.lastSignInAt
                    it[isActive] = account.isActive
                    it[preferences] = account.preferences ?: "{}"
                }
                AccountsTable
                    .selectAll()
                    .where { AccountsTable.id eq account.id.toJavaUUID() }
                    .single()
                    .toAccount()
            } else {
                account.requireValidNewIdentity()
                AccountsTable.insert {
                    it[id] = account.id.toJavaUUID()
                    it[username] = account.username
                    it[displayName] = account.displayName
                    it[did] = account.did
                    it[handle] = account.handle
                    it[signingKeyPublic] = account.signingKeyPublic
                    it[plcRecoveryDidKey] = account.plcRecoveryDidKey
                    it[email] = account.email
                    it[emailVerified] = account.emailVerified
                    it[emailVerifiedAt] = account.emailVerifiedAt
                    it[bio] = account.bio
                    it[originalBio] = account.originalBio
                    it[createdAt] = account.createdAt
                    it[lastSignInAt] = account.lastSignInAt
                    it[isActive] = account.isActive
                    it[preferences] = account.preferences ?: "{}"
                    it[storageMode] = account.newAccountStorageMode()
                    it[identityKind] = account.identityKind.wireValue
                    it[publicIdentityDid] = account.publicIdentityDid
                }
                account
            }
        }

    override suspend fun createOnly(account: Account): Boolean =
        transaction {
            account.requireValidNewIdentity()
            AccountsTable
                .insertIgnore {
                    it[id] = account.id.toJavaUUID()
                    it[username] = account.username
                    it[displayName] = account.displayName
                    it[did] = account.did
                    it[handle] = account.handle
                    it[signingKeyPublic] = account.signingKeyPublic
                    it[plcRecoveryDidKey] = account.plcRecoveryDidKey
                    it[email] = account.email
                    it[emailVerified] = account.emailVerified
                    it[emailVerifiedAt] = account.emailVerifiedAt
                    it[bio] = account.bio
                    it[originalBio] = account.originalBio
                    it[createdAt] = account.createdAt
                    it[lastSignInAt] = account.lastSignInAt
                    it[isActive] = account.isActive
                    it[preferences] = account.preferences ?: "{}"
                    it[storageMode] = account.newAccountStorageMode()
                    it[identityKind] = account.identityKind.wireValue
                    it[publicIdentityDid] = account.publicIdentityDid
                }.insertedCount == 1
        }

    override suspend fun updateStorageModeIfUnchanged(
        accountId: Uuid,
        expected: AccountStorageMode,
        new: AccountStorageMode,
    ): Boolean {
        require(new != AccountStorageMode.UNRECOGNIZED) { "An account can't be moved to a storage mode this server doesn't know" }
        if (expected == AccountStorageMode.UNRECOGNIZED) return false
        return transaction {
            val hostedKind = AccountsTable.identityKind eq AccountIdentityKind.HOSTED.wireValue
            val kindAllowsMode =
                if (new == AccountStorageMode.PERSONAL_SPACE_V1) {
                    hostedKind or (AccountsTable.identityKind eq AccountIdentityKind.EXTERNAL.wireValue)
                } else {
                    hostedKind
                }
            AccountsTable.update({
                (AccountsTable.id eq accountId.toJavaUUID()) and
                    (AccountsTable.storageMode eq expected.wireValue) and kindAllowsMode
            }) {
                it[storageMode] = new.wireValue
            } == 1
        }
    }

    override suspend fun setModeration(
        accountId: Uuid,
        moderation: AccountModeration?,
    ): Boolean =
        transaction {
            AccountsTable.update({ AccountsTable.id eq accountId.toJavaUUID() }) {
                it[AccountsTable.moderation] = moderation?.wireValue
            } == 1
        }

    override suspend fun findById(id: Uuid): Account? =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.id eq id.toJavaUUID() }
                .singleOrNull()
                ?.toAccount()
        }

    override suspend fun recordPublicIdentity(
        accountId: Uuid,
        expectedDid: String,
    ): Boolean =
        transaction {
            AccountsTable.update({
                (AccountsTable.id eq accountId.toJavaUUID()) and (AccountsTable.did eq expectedDid) and
                    (AccountsTable.identityKind eq AccountIdentityKind.HOSTED.wireValue)
            }) {
                it[publicIdentityDid] = expectedDid
            } == 1
        }

    override suspend fun findByDid(did: String): Account? =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.did eq did }
                .singleOrNull()
                ?.toAccount()
        }

    override suspend fun findByHandle(handle: String): Account? =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.handle eq handle }
                .singleOrNull()
                ?.toAccount()
        }

    override suspend fun findByUsername(username: String): Account? =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.username eq username }
                .singleOrNull()
                ?.toAccount()
        }

    override suspend fun findByEmail(email: String): Account? =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.email eq email }
                .singleOrNull()
                ?.toAccount()
        }

    override suspend fun findByVerifiedEmail(email: String): List<Account> =
        transaction {
            AccountsTable
                .selectAll()
                .where {
                    (AccountsTable.email eq email) and
                        (AccountsTable.emailVerified eq true)
                }.map { it.toAccount() }
        }

    override suspend fun usernameExists(username: String): Boolean =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.username eq username }
                .count() > 0
        }

    override suspend fun emailExists(email: String): Boolean =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.email eq email }
                .count() > 0
        }

    override suspend fun updateLastSignIn(accountId: Uuid): Boolean =
        transaction {
            val updatedRows =
                AccountsTable.update({ AccountsTable.id eq accountId.toJavaUUID() }) {
                    it[lastSignInAt] = Clock.System.now()
                }
            updatedRows > 0
        }

    override suspend fun deactivateAccount(accountId: Uuid): Boolean =
        transaction {
            val updatedRows =
                AccountsTable.update({ AccountsTable.id eq accountId.toJavaUUID() }) {
                    it[isActive] = false
                }
            updatedRows > 0
        }

    override suspend fun getAllAccounts(): List<Account> =
        transaction {
            AccountsTable
                .selectAll()
                .where { AccountsTable.isActive eq true }
                .orderBy(AccountsTable.createdAt, SortOrder.DESC)
                .map { it.toAccount() }
        }

    override suspend fun findAccountsMissingIdentity(): List<Account> =
        transaction {
            AccountsTable
                .selectAll()
                .where {
                    (AccountsTable.isActive eq true) and
                        (AccountsTable.identityKind eq AccountIdentityKind.HOSTED.wireValue) and
                        (AccountsTable.handle.isNull() or AccountsTable.did.isNull() or AccountsTable.signingKeyPublic.isNull())
                }.orderBy(AccountsTable.createdAt, SortOrder.DESC)
                .map { it.toAccount() }
        }

    override suspend fun getAccountsCreatedAfter(timestamp: Instant): List<Account> =
        transaction {
            AccountsTable
                .selectAll()
                .where {
                    (AccountsTable.createdAt greater timestamp) and
                        (AccountsTable.isActive eq true)
                }.orderBy(AccountsTable.createdAt, SortOrder.DESC)
                .map { it.toAccount() }
        }

    override suspend fun deleteAccount(accountId: Uuid): Boolean =
        transaction {
            val id = accountId.toJavaUUID()
            // Keep deletion deterministic even when an older deployment has not
            // yet repaired one of the account-owned foreign keys. These rows are
            // all user-owned authentication/identity state; billing events use
            // ON DELETE SET NULL and remain as history.
            PendingEmailVerificationsTable.deleteWhere { PendingEmailVerificationsTable.accountId eq id }
            AtprotoPasswordCredentialsTable.deleteWhere { AtprotoPasswordCredentialsTable.accountId eq id }
            AtprotoSessionsTable.deleteWhere { AtprotoSessionsTable.accountId eq id }
            SigningKeysTable.deleteWhere { SigningKeysTable.accountId eq id }
            HostedPlcOperationsTable.deleteWhere { HostedPlcOperationsTable.accountId eq id }
            AccountIdentitiesTable.deleteWhere { AccountIdentitiesTable.accountId eq id }
            AccountLinkEventsTable.deleteWhere { AccountLinkEventsTable.accountId eq id }
            PasskeysTable.deleteWhere { PasskeysTable.accountId eq id }
            RestoreCredentialsTable.deleteWhere { RestoreCredentialsTable.accountId eq id }
            ResourceRoutesTable.deleteWhere { ResourceRoutesTable.accountId eq id }
            AccountEntitlementsTable.deleteWhere { AccountEntitlementsTable.accountId eq id }
            deleteAccountRepositories(id)
            val deletedRows = AccountsTable.deleteWhere { AccountsTable.id eq id }
            deletedRows > 0
        }

    override suspend fun updateIdentityIfUnchanged(
        expected: Account,
        updated: Account,
    ): Account? =
        transaction {
            if (expected.identityKind != AccountIdentityKind.HOSTED ||
                updated.identityKind != AccountIdentityKind.HOSTED
            ) {
                return@transaction null
            }
            val id = expected.id.toJavaUUID()
            val changed =
                AccountsTable.update({
                    (AccountsTable.id eq id) and
                        (AccountsTable.identityKind eq AccountIdentityKind.HOSTED.wireValue) and
                        AccountsTable.handle.matches(expected.handle) and
                        AccountsTable.did.matches(expected.did) and
                        AccountsTable.signingKeyPublic.matches(expected.signingKeyPublic) and
                        AccountsTable.plcRecoveryDidKey.matches(expected.plcRecoveryDidKey)
                }) {
                    it[handle] = updated.handle
                    it[did] = updated.did
                    it[signingKeyPublic] = updated.signingKeyPublic
                    it[plcRecoveryDidKey] = updated.plcRecoveryDidKey
                }
            if (changed == 0) {
                null
            } else {
                AccountsTable
                    .selectAll()
                    .where { AccountsTable.id eq id }
                    .single()
                    .toAccount()
            }
        }

    private fun Column<String?>.matches(value: String?): Op<Boolean> = if (value == null) isNull() else (this eq value)

    private fun Account.newAccountStorageMode(): String {
        require(storageMode != AccountStorageMode.UNRECOGNIZED) { "A new account needs a storage mode this server knows" }
        return storageMode.wireValue
    }

    private fun ResultRow.toAccount(): Account =
        Account(
            id = this[AccountsTable.id].toKotlinUuid(),
            username = this[AccountsTable.username],
            displayName = this[AccountsTable.displayName],
            did = this[AccountsTable.did],
            handle = this[AccountsTable.handle],
            signingKeyPublic = this[AccountsTable.signingKeyPublic],
            plcRecoveryDidKey = this[AccountsTable.plcRecoveryDidKey],
            email = this[AccountsTable.email],
            emailVerified = this[AccountsTable.emailVerified],
            emailVerifiedAt = this[AccountsTable.emailVerifiedAt],
            bio = this[AccountsTable.bio],
            originalBio = this[AccountsTable.originalBio],
            createdAt = this[AccountsTable.createdAt],
            lastSignInAt = this[AccountsTable.lastSignInAt],
            isActive = this[AccountsTable.isActive],
            preferences = this[AccountsTable.preferences],
            storageMode = AccountStorageMode.fromWireValue(this[AccountsTable.storageMode]),
            moderation = AccountModeration.fromWireValue(this[AccountsTable.moderation]),
            publicIdentityDid = this[AccountsTable.publicIdentityDid],
            identityKind = AccountIdentityKind.fromWireValue(this[AccountsTable.identityKind]),
        )
}
