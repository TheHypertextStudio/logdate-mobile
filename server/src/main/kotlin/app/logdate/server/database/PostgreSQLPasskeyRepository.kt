package app.logdate.server.database

import app.logdate.server.passkeys.PasskeyRepository
import app.logdate.server.passkeys.StoredPasskeyData
import app.logdate.shared.model.PasskeyInfo
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLPasskeyRepository : PasskeyRepository {
    override suspend fun storePasskey(
        userId: Uuid,
        credentialId: String,
        publicKey: ByteArray,
        signCount: Long,
        info: PasskeyInfo,
    ): Boolean =
        try {
            dbQuery {
                PasskeysTable.insert {
                    it[id] = info.id.toJavaUUID()
                    it[accountId] = userId.toJavaUUID()
                    it[PasskeysTable.credentialId] = credentialId
                    it[PasskeysTable.publicKey] = encodeByteArray(publicKey)
                    it[PasskeysTable.signCount] = signCount
                    it[nickname] = info.nickname
                    it[deviceType] = info.deviceType
                    it[createdAt] = info.createdAt
                    it[lastUsedAt] = info.lastUsedAt
                    it[isActive] = info.isActive
                    it[webauthnData] = "{}"
                }
            }
            true
        } catch (e: Exception) {
            false
        }

    override suspend fun getPasskeyByCredentialId(credentialId: String): Pair<Uuid, StoredPasskeyData>? =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where { (PasskeysTable.credentialId eq credentialId) and (PasskeysTable.isActive eq true) }
                .singleOrNull()
                ?.let { row ->
                    val userId = row[PasskeysTable.accountId].toKotlinUuid()
                    val storedData =
                        StoredPasskeyData(
                            credentialId = row[PasskeysTable.credentialId],
                            publicKey = decodeByteArray(row[PasskeysTable.publicKey]),
                            signCount = row[PasskeysTable.signCount],
                            info = row.toPasskeyInfo(),
                            userId = userId,
                        )
                    userId to storedData
                }
        }

    override suspend fun getPasskeysForUser(userId: Uuid): List<PasskeyInfo> =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where { (PasskeysTable.accountId eq userId.toJavaUUID()) and (PasskeysTable.isActive eq true) }
                .orderBy(PasskeysTable.lastUsedAt, SortOrder.DESC)
                .map { it.toPasskeyInfo() }
        }

    override suspend fun updateSignCount(
        credentialId: String,
        newSignCount: Long,
    ): Boolean =
        dbQuery {
            val updatedRows =
                PasskeysTable.update({ PasskeysTable.credentialId eq credentialId }) {
                    it[signCount] = newSignCount
                    it[lastUsedAt] = Clock.System.now()
                }
            updatedRows > 0
        }

    override suspend fun deactivatePasskey(
        credentialId: String,
        userId: Uuid,
    ): Boolean =
        dbQuery {
            val updatedRows =
                PasskeysTable.update({
                    (PasskeysTable.credentialId eq credentialId) and (PasskeysTable.accountId eq userId.toJavaUUID())
                }) {
                    it[isActive] = false
                }
            updatedRows > 0
        }

    override suspend fun credentialBelongsToUser(
        credentialId: String,
        userId: Uuid,
    ): Boolean =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where {
                    (PasskeysTable.credentialId eq credentialId) and (PasskeysTable.accountId eq userId.toJavaUUID())
                }.count() > 0
        }

    override suspend fun getCredentialIdsForUser(userId: Uuid): List<String> =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where { (PasskeysTable.accountId eq userId.toJavaUUID()) and (PasskeysTable.isActive eq true) }
                .map { it[PasskeysTable.credentialId] }
        }

    override suspend fun credentialExists(credentialId: String): Boolean =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where { PasskeysTable.credentialId eq credentialId }
                .count() > 0
        }

    // Additional utility methods for internal use
    suspend fun findById(passkeyId: Uuid): PasskeyInfo? =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where { PasskeysTable.id eq passkeyId.toJavaUUID() }
                .singleOrNull()
                ?.toPasskeyInfo()
        }

    suspend fun findByCredentialId(credentialId: String): PasskeyInfo? =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where { PasskeysTable.credentialId eq credentialId }
                .singleOrNull()
                ?.toPasskeyInfo()
        }

    suspend fun findByAccountId(accountId: Uuid): List<PasskeyInfo> =
        dbQuery {
            PasskeysTable
                .selectAll()
                .where { PasskeysTable.accountId eq accountId.toJavaUUID() }
                .orderBy(PasskeysTable.createdAt, SortOrder.DESC)
                .map { it.toPasskeyInfo() }
        }

    suspend fun findActiveByAccountId(accountId: Uuid): List<PasskeyInfo> = getPasskeysForUser(accountId)

    suspend fun deletePasskey(passkeyId: Uuid): Boolean =
        dbQuery {
            val deletedRows = PasskeysTable.deleteWhere { id eq passkeyId.toJavaUUID() }
            deletedRows > 0
        }

    private fun ResultRow.toPasskeyInfo(): PasskeyInfo =
        PasskeyInfo(
            id = this[PasskeysTable.id].toKotlinUuid(),
            credentialId = this[PasskeysTable.credentialId],
            nickname = this[PasskeysTable.nickname],
            deviceType = this[PasskeysTable.deviceType],
            createdAt = this[PasskeysTable.createdAt],
            lastUsedAt = this[PasskeysTable.lastUsedAt],
            isActive = this[PasskeysTable.isActive],
        )

    private fun encodeByteArray(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeByteArray(value: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(value) }.getOrElse { value.toByteArray() }
}
