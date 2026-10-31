package app.logdate.server.database

import app.logdate.server.accountkeys.AccountKeyEnvelopeRepository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

class PostgreSQLAccountKeyEnvelopeRepository : AccountKeyEnvelopeRepository {
    override suspend fun get(
        accountId: UUID,
        credentialId: String,
    ): String? =
        dbQuery {
            if (!activeCredential(accountId, credentialId)) return@dbQuery null
            AccountKeyEnvelopeTable
                .selectAll()
                .where {
                    (AccountKeyEnvelopeTable.accountId eq accountId) and (AccountKeyEnvelopeTable.credentialId eq credentialId)
                }.singleOrNull()
                ?.get(AccountKeyEnvelopeTable.ciphertext)
        }

    override suspend fun put(
        accountId: UUID,
        credentialId: String,
        ciphertext: String,
    ): Boolean =
        dbQuery {
            if (!activeCredential(accountId, credentialId)) return@dbQuery false
            AccountKeyEnvelopeTable.insertIgnore {
                it[AccountKeyEnvelopeTable.accountId] = accountId
                it[AccountKeyEnvelopeTable.credentialId] = credentialId
                it[AccountKeyEnvelopeTable.ciphertext] = ciphertext
            }
            AccountKeyEnvelopeTable
                .selectAll()
                .where {
                    (AccountKeyEnvelopeTable.accountId eq accountId) and (AccountKeyEnvelopeTable.credentialId eq credentialId)
                }.singleOrNull()
                ?.get(AccountKeyEnvelopeTable.ciphertext) == ciphertext
        }

    private fun activeCredential(
        accountId: UUID,
        credentialId: String,
    ): Boolean =
        PasskeysTable
            .selectAll()
            .where {
                (PasskeysTable.accountId eq accountId) and (PasskeysTable.credentialId eq credentialId) and (PasskeysTable.isActive eq true)
            }.singleOrNull() != null
}
