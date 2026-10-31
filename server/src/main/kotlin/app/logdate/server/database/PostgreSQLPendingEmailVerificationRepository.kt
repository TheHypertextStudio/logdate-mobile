package app.logdate.server.database

import app.logdate.server.auth.PendingEmailVerification
import app.logdate.server.auth.PendingEmailVerificationRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLPendingEmailVerificationRepository : PendingEmailVerificationRepository {
    override suspend fun create(challenge: PendingEmailVerification): PendingEmailVerification =
        dbQuery {
            PendingEmailVerificationsTable.insert {
                it[transactionId] = challenge.transactionId.toJavaUUID()
                it[accountId] = challenge.accountId.toJavaUUID()
                it[nonce] = challenge.nonce
                it[expiresAt] = challenge.expiresAt
                it[createdAt] = challenge.createdAt
            }
            challenge
        }

    override suspend fun consume(transactionId: Uuid): PendingEmailVerification? =
        dbQuery {
            val row =
                PendingEmailVerificationsTable
                    .selectAll()
                    .where { PendingEmailVerificationsTable.transactionId eq transactionId.toJavaUUID() }
                    .singleOrNull()
                    ?: return@dbQuery null
            val pending =
                PendingEmailVerification(
                    transactionId = row[PendingEmailVerificationsTable.transactionId].toKotlinUuid(),
                    accountId = row[PendingEmailVerificationsTable.accountId].toKotlinUuid(),
                    nonce = row[PendingEmailVerificationsTable.nonce],
                    expiresAt = row[PendingEmailVerificationsTable.expiresAt],
                    createdAt = row[PendingEmailVerificationsTable.createdAt],
                )
            PendingEmailVerificationsTable.deleteWhere {
                this.transactionId eq transactionId.toJavaUUID()
            }
            pending
        }

    override suspend fun deleteExpired(now: Instant): Int =
        dbQuery {
            PendingEmailVerificationsTable.deleteWhere {
                expiresAt lessEq now
            }
        }
}
