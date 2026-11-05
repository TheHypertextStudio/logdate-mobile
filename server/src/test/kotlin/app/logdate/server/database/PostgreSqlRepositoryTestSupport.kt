package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.database.support.withH2Database
import app.logdate.server.entitlements.AccountEntitlementsTable
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.shared.model.PasskeyInfo
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@OptIn(ExperimentalUuidApi::class)
internal object PostgreSqlRepositoryTestSupport {
    fun identityService(repository: PostgreSQLAccountRepository) =
        AtprotoIdentityService(
            accountRepository = repository,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "storage-mode-test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )

    fun withAccountTables(block: suspend () -> Unit) {
        withH2Database(
            AccountsTable,
            PendingEmailVerificationsTable,
            AtprotoPasswordCredentialsTable,
            AtprotoSessionsTable,
            SigningKeysTable,
            HostedPlcOperationsTable,
            IdentityPublicationAttemptsTable,
            AccountIdentitiesTable,
            AccountLinkEventsTable,
            PasskeysTable,
            RestoreCredentialsTable,
            ResourceRoutesTable,
            AccountEntitlementsTable,
            AtprotoRepoHeadsTable,
            AtprotoRepoBlocksTable,
            AtprotoRepoBlockLinksTable,
            AtprotoRepoCommitsTable,
            LogDateCollectionStatesTable,
            LogDateCollectionRecordsTable,
        ) {
            runBlocking { block() }
        }
    }

    /**
     * Writes a small repository for [accountDid], with one block shared with every other repository,
     * plus sync state under [syncRepoDid] and one indexed record.
     */
    fun insertRepository(
        accountId: Uuid,
        accountDid: String,
        syncRepoDid: String,
    ) {
        transaction {
            for (did in setOf(accountDid, syncRepoDid)) {
                AtprotoRepoHeadsTable.insert {
                    it[repoDid] = did
                    it[rootCid] = "root"
                    it[commitCid] = "commit"
                    it[revision] = 1
                    it[updatedAt] = Clock.System.now()
                }
                AtprotoRepoCommitsTable.insert {
                    it[repoDid] = did
                    it[revision] = 1
                    it[cid] = "commit"
                    it[rootCid] = "root"
                    it[prevCid] = null
                    it[createdAtEpochMillis] = 1
                    it[recordCount] = 1
                    it[signature] = "signature"
                }
                for (blockCid in listOf("shared", "only-$did")) {
                    AtprotoRepoBlocksTable.insertIgnore {
                        it[cid] = blockCid
                        it[bytes] = byteArrayOf(1)
                    }
                    AtprotoRepoBlockLinksTable.insert {
                        it[repoDid] = did
                        it[cid] = blockCid
                    }
                }
            }
            LogDateCollectionStatesTable.insert {
                it[userId] = accountId.toJavaUuid()
                it[repoDid] = syncRepoDid
                it[lastVersion] = 1
                it[updatedAt] = Clock.System.now()
            }
            LogDateCollectionRecordsTable.insert {
                it[userId] = accountId.toJavaUuid()
                it[collection] = "entry"
                it[recordKey] = "entry-1"
                it[serverVersion] = 1
                it[deleted] = false
                it[deletedAt] = null
            }
        }
    }

    fun withAccountIdentityTables(block: suspend () -> Unit) {
        withH2Database(AccountsTable, AccountIdentitiesTable, AccountLinkEventsTable) {
            runBlocking { block() }
        }
    }

    fun withPasskeyTables(block: suspend () -> Unit) {
        withH2Database(AccountsTable, PasskeysTable) {
            runBlocking { block() }
        }
    }

    fun withSessionTables(block: suspend () -> Unit) {
        withH2Database(SessionsTable) {
            runBlocking { block() }
        }
    }

    fun samplePasskeyInfo(credentialId: String): PasskeyInfo =
        PasskeyInfo(
            id = Uuid.random(),
            credentialId = credentialId,
            nickname = "phone",
            deviceType = "platform",
            createdAt = Clock.System.now(),
            lastUsedAt = null,
            isActive = true,
        )

    fun sampleAccount(
        id: Uuid,
        username: String,
    ): Account =
        Account(
            id = id,
            username = username,
            displayName = username,
            email = "$username@example.com",
            emailVerified = true,
            bio = null,
            createdAt = Clock.System.now(),
            preferences = "{}",
            isActive = true,
        )
}
