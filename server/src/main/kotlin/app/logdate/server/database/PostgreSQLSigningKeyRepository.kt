package app.logdate.server.database

import app.logdate.server.identity.EncryptedPrivateKey
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.SigningKeyRepository
import app.logdate.server.identity.StoredSigningKey
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLSigningKeyRepository : SigningKeyRepository {
    override suspend fun replaceActiveKey(
        signingKey: StoredSigningKey,
        identityEpoch: Long,
    ): StoredSigningKey =
        transaction {
            requireHostedIdentityWrite(signingKey.accountId, identityEpoch)
            SigningKeysTable.update({
                (SigningKeysTable.accountId eq signingKey.accountId.toJavaUUID()) and
                    (SigningKeysTable.revokedAt eq null)
            }) {
                it[revokedAt] =
                    kotlin.time.Clock.System
                        .now()
            }
            insertKey(signingKey)
        }

    override suspend fun save(
        signingKey: StoredSigningKey,
        identityEpoch: Long,
    ): StoredSigningKey =
        transaction {
            requireHostedIdentityWrite(signingKey.accountId, identityEpoch)
            insertKey(signingKey)
        }

    override suspend fun createActiveKeyIfMissing(
        signingKey: StoredSigningKey,
        identityEpoch: Long,
    ): StoredSigningKey =
        transaction {
            require(signingKey.revokedAt == null)
            requireHostedIdentityWrite(signingKey.accountId, identityEpoch)
            activeKey(signingKey.accountId)?.let { return@transaction it }
            val owner =
                AccountsTable
                    .select(AccountsTable.signingKeyPublic)
                    .where { AccountsTable.id eq signingKey.accountId.toJavaUUID() }
                    .single()
            val hasHistory =
                HostedPlcOperationsTable
                    .select(HostedPlcOperationsTable.id)
                    .where { HostedPlcOperationsTable.accountId eq signingKey.accountId.toJavaUUID() }
                    .any()
            if (owner[AccountsTable.signingKeyPublic] != null || hasHistory) {
                throw IdentityLifecycleConflictException("Hosted signing authority needs reconciliation")
            }
            insertKey(signingKey)
        }

    private fun insertKey(signingKey: StoredSigningKey): StoredSigningKey {
        SigningKeysTable.insert {
            it[id] = signingKey.id.toJavaUUID()
            it[accountId] = signingKey.accountId.toJavaUUID()
            it[purpose] = signingKey.purpose
            it[algorithm] = signingKey.algorithm
            it[publicKeyMultibase] = signingKey.publicKeyMultibase
            it[privateKeyEncrypted] = signingKey.privateKeyEncrypted
            it[createdAt] = signingKey.createdAt
            it[revokedAt] = signingKey.revokedAt
        }
        return signingKey
    }

    override suspend fun findActiveByAccountId(accountId: Uuid): StoredSigningKey? = transaction { activeKey(accountId) }

    private fun activeKey(accountId: Uuid): StoredSigningKey? {
        val active =
            SigningKeysTable
                .selectAll()
                .where {
                    (SigningKeysTable.accountId eq accountId.toJavaUUID()) and (SigningKeysTable.revokedAt eq null)
                }.toList()
        if (active.size > 1) throw IdentityLifecycleConflictException("Active signing keys need reconciliation")
        return active.singleOrNull()?.toStoredSigningKey()
    }

    override suspend fun revokeActiveKeys(
        accountId: Uuid,
        identityEpoch: Long,
    ): Int =
        transaction {
            requireHostedIdentityWrite(accountId, identityEpoch)
            SigningKeysTable.update({
                (SigningKeysTable.accountId eq accountId.toJavaUUID()) and
                    (SigningKeysTable.revokedAt eq null)
            }) {
                it[revokedAt] =
                    kotlin.time.Clock.System
                        .now()
            }
        }

    override suspend fun encryptedPrivateKeys(): List<EncryptedPrivateKey> =
        transaction {
            SigningKeysTable
                .select(SigningKeysTable.id, SigningKeysTable.privateKeyEncrypted)
                .where { SigningKeysTable.revokedAt eq null }
                .map {
                    EncryptedPrivateKey(
                        id = it[SigningKeysTable.id].toString(),
                        privateKeyEncrypted = it[SigningKeysTable.privateKeyEncrypted],
                    )
                }
        }

    override suspend fun replaceEncryptedPrivateKey(
        id: String,
        expected: String,
        replacement: String,
    ): Boolean =
        transaction {
            // Reading ownership takes no key-row lock; the account always owns the first lock.
            val owner =
                SigningKeysTable
                    .select(SigningKeysTable.accountId)
                    .where { SigningKeysTable.id eq Uuid.parse(id).toJavaUUID() }
                    .singleOrNull()
                    ?.get(SigningKeysTable.accountId) ?: return@transaction false
            requireHostedIdentityWrite(owner.toKotlinUuid(), 0)
            SigningKeysTable.update({
                (SigningKeysTable.id eq Uuid.parse(id).toJavaUUID()) and
                    (SigningKeysTable.privateKeyEncrypted eq expected)
            }) {
                it[privateKeyEncrypted] = replacement
            } == 1
        }

    private fun ResultRow.toStoredSigningKey(): StoredSigningKey =
        StoredSigningKey(
            id = this[SigningKeysTable.id].toKotlinUuid(),
            accountId = this[SigningKeysTable.accountId].toKotlinUuid(),
            purpose = this[SigningKeysTable.purpose],
            algorithm = this[SigningKeysTable.algorithm],
            publicKeyMultibase = this[SigningKeysTable.publicKeyMultibase],
            privateKeyEncrypted = this[SigningKeysTable.privateKeyEncrypted],
            createdAt = this[SigningKeysTable.createdAt],
            revokedAt = this[SigningKeysTable.revokedAt],
        )
}
