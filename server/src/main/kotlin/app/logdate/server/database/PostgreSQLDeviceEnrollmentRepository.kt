package app.logdate.server.database

import app.logdate.server.enrollment.DeviceEnrollment
import app.logdate.server.enrollment.DeviceEnrollmentRepository
import app.logdate.server.enrollment.EnrollmentStatus
import app.logdate.server.enrollment.SessionIssueResult
import app.logdate.server.enrollment.matchesCreate
import app.logdate.server.enrollment.sessionIssueResult
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class PostgreSQLDeviceEnrollmentRepository : DeviceEnrollmentRepository {
    override suspend fun create(enrollment: DeviceEnrollment): DeviceEnrollment? =
        dbQuery {
            DeviceEnrollmentTable.deleteWhere { expiresAt lessEq System.currentTimeMillis() }
            DeviceEnrollmentTable.insertIgnore {
                it[id] = enrollment.id
                it[accountId] = enrollment.accountId
                it[deviceName] = enrollment.deviceName
                it[publicKey] = enrollment.publicKey
                it[confirmationCode] = enrollment.confirmationCode
                it[status] = enrollment.status.name
                it[claimHash] = enrollment.claimHash
                it[expiresAt] = enrollment.expiresAt
            }
            val stored =
                DeviceEnrollmentTable
                    .selectAll()
                    .where {
                        enrollment.claimHash?.let { DeviceEnrollmentTable.claimHash eq it }
                            ?: (DeviceEnrollmentTable.id eq enrollment.id)
                    }.singleOrNull()
                    ?.toEnrollment()
            stored?.takeIf { it.matchesCreate(enrollment) }
        }

    override suspend fun get(
        accountId: UUID,
        id: UUID,
        now: Long,
    ): DeviceEnrollment? =
        dbQuery {
            DeviceEnrollmentTable
                .selectAll()
                .where {
                    (DeviceEnrollmentTable.id eq id) and
                        (DeviceEnrollmentTable.accountId eq accountId) and
                        (DeviceEnrollmentTable.expiresAt greater now)
                }.singleOrNull()
                ?.toEnrollment()
        }

    override suspend fun approve(
        accountId: UUID,
        id: UUID,
        confirmationCode: String,
        encryptedEnvelope: String,
        now: Long,
    ): Boolean =
        dbQuery {
            val updated =
                DeviceEnrollmentTable.update({
                    (DeviceEnrollmentTable.id eq id) and
                        (DeviceEnrollmentTable.accountId eq accountId) and
                        (DeviceEnrollmentTable.confirmationCode eq confirmationCode) and
                        (DeviceEnrollmentTable.expiresAt greater now) and
                        (DeviceEnrollmentTable.status eq EnrollmentStatus.PENDING.name)
                }) {
                    it[status] = EnrollmentStatus.APPROVED.name
                    it[DeviceEnrollmentTable.encryptedEnvelope] = encryptedEnvelope
                }
            if (updated == 1) return@dbQuery true
            DeviceEnrollmentTable
                .selectAll()
                .where {
                    (DeviceEnrollmentTable.id eq id) and
                        (DeviceEnrollmentTable.accountId eq accountId) and
                        (DeviceEnrollmentTable.confirmationCode eq confirmationCode) and
                        (DeviceEnrollmentTable.expiresAt greater now) and
                        (DeviceEnrollmentTable.status eq EnrollmentStatus.APPROVED.name) and
                        (DeviceEnrollmentTable.encryptedEnvelope eq encryptedEnvelope)
                }.singleOrNull() != null
        }

    override suspend fun consume(
        accountId: UUID,
        id: UUID,
        now: Long,
    ): String? =
        dbQuery {
            val updated =
                DeviceEnrollmentTable.update({
                    (DeviceEnrollmentTable.id eq id) and
                        (DeviceEnrollmentTable.accountId eq accountId) and
                        (DeviceEnrollmentTable.expiresAt greater now) and
                        (DeviceEnrollmentTable.status eq EnrollmentStatus.APPROVED.name)
                }) { it[status] = "CONSUMED" }
            if (updated != 1) return@dbQuery null
            val envelope =
                DeviceEnrollmentTable
                    .selectAll()
                    .where { DeviceEnrollmentTable.id eq id }
                    .single()[DeviceEnrollmentTable.encryptedEnvelope]
            DeviceEnrollmentTable.deleteWhere { DeviceEnrollmentTable.id eq id }
            envelope
        }

    override suspend fun cancel(
        accountId: UUID,
        id: UUID,
    ): Boolean =
        dbQuery {
            DeviceEnrollmentTable.deleteWhere {
                (DeviceEnrollmentTable.id eq id) and (DeviceEnrollmentTable.accountId eq accountId)
            } == 1
        }

    override suspend fun reject(
        accountId: UUID,
        id: UUID,
        now: Long,
    ): Boolean =
        dbQuery {
            DeviceEnrollmentTable.update({
                (DeviceEnrollmentTable.id eq id) and
                    (DeviceEnrollmentTable.accountId eq accountId) and
                    (DeviceEnrollmentTable.expiresAt greater now) and
                    (DeviceEnrollmentTable.status eq EnrollmentStatus.PENDING.name)
            }) { it[status] = EnrollmentStatus.REJECTED.name } == 1
        }

    override suspend fun claim(
        claimHash: String,
        now: Long,
    ): DeviceEnrollment? =
        dbQuery {
            DeviceEnrollmentTable
                .selectAll()
                .where { (DeviceEnrollmentTable.claimHash eq claimHash) and (DeviceEnrollmentTable.expiresAt greater now) }
                .singleOrNull()
                ?.toEnrollment()
        }

    override suspend fun markSessionIssued(
        accountId: UUID,
        id: UUID,
        now: Long,
    ): SessionIssueResult =
        dbQuery {
            val updated =
                DeviceEnrollmentTable.update({
                    (DeviceEnrollmentTable.id eq id) and
                        (DeviceEnrollmentTable.accountId eq accountId) and
                        (DeviceEnrollmentTable.expiresAt greater now) and
                        (DeviceEnrollmentTable.status eq EnrollmentStatus.PENDING.name) and
                        (DeviceEnrollmentTable.sessionIssued eq false)
                }) { it[sessionIssued] = true }
            if (updated == 1) return@dbQuery SessionIssueResult.ISSUED
            val existing =
                DeviceEnrollmentTable
                    .selectAll()
                    .where {
                        (DeviceEnrollmentTable.id eq id) and
                            (DeviceEnrollmentTable.accountId eq accountId) and
                            (DeviceEnrollmentTable.expiresAt greater now)
                    }.singleOrNull()
                    ?.toEnrollment()
            // A row that changed between the update and this read is treated as already issued, never as a second grant.
            existing.sessionIssueResult().takeUnless { it == SessionIssueResult.ISSUED } ?: SessionIssueResult.ALREADY_ISSUED
        }
}

private fun ResultRow.toEnrollment() =
    DeviceEnrollment(
        id = this[DeviceEnrollmentTable.id],
        accountId = this[DeviceEnrollmentTable.accountId],
        deviceName = this[DeviceEnrollmentTable.deviceName],
        publicKey = this[DeviceEnrollmentTable.publicKey],
        confirmationCode = this[DeviceEnrollmentTable.confirmationCode],
        expiresAt = this[DeviceEnrollmentTable.expiresAt],
        status = EnrollmentStatus.valueOf(this[DeviceEnrollmentTable.status]),
        encryptedEnvelope = this[DeviceEnrollmentTable.encryptedEnvelope],
        claimHash = this[DeviceEnrollmentTable.claimHash],
        sessionIssued = this[DeviceEnrollmentTable.sessionIssued],
    )
