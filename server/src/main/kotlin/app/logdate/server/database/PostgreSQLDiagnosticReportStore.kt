@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.diagnostics.DiagnosticReportService
import app.logdate.server.diagnostics.DiagnosticReportStore
import app.logdate.server.diagnostics.DiagnosticSaveResult
import app.logdate.server.diagnostics.StoredDiagnosticReport
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

object DiagnosticReportsTable : Table("diagnostic_reports") {
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val reportId = javaUUID("report_id")
    val createdAtMs = long("created_at_ms")
    val encryptedPayload = binary("encrypted_payload")
    val encryptedFingerprint = binary("encrypted_fingerprint")

    override val primaryKey = PrimaryKey(accountId, reportId)

    init {
        index("diagnostic_reports_account_created_idx", false, accountId, createdAtMs)
    }
}

/** Separate accepted-upload ledger keeps the daily allowance after users delete reports. */
object DiagnosticReportUploadsTable : Table("diagnostic_report_uploads") {
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val reportId = javaUUID("report_id")
    val createdAtMs = long("created_at_ms")
    val encryptedFingerprint = binary("encrypted_fingerprint").nullable()

    override val primaryKey = PrimaryKey(accountId, reportId)

    init {
        index("diagnostic_report_uploads_account_created_idx", false, accountId, createdAtMs)
    }
}

/** The account row lock serializes quota, idempotency, and retention across server processes. */
class PostgreSQLDiagnosticReportStore : DiagnosticReportStore {
    override suspend fun save(
        owner: Uuid,
        report: StoredDiagnosticReport,
        matchesFingerprint: (ByteArray) -> Boolean,
    ): DiagnosticSaveResult =
        dbQuery {
            val account = owner.toJavaUUID()
            AccountsTable
                .selectAll()
                .where { AccountsTable.id eq account }
                .forUpdate()
                .single()
            val cutoff = report.createdAt - DiagnosticReportService.RETENTION_MS
            DiagnosticReportsTable.deleteWhere { (accountId eq account) and (createdAtMs lessEq cutoff) }
            DiagnosticReportUploadsTable.deleteWhere { (accountId eq account) and (createdAtMs lessEq cutoff) }

            val existing =
                DiagnosticReportUploadsTable
                    .selectAll()
                    .where {
                        (DiagnosticReportUploadsTable.accountId eq account) and
                            (DiagnosticReportUploadsTable.reportId eq report.reportId.toJavaUUID())
                    }.singleOrNull()
            if (existing != null) {
                val fingerprint = existing[DiagnosticReportUploadsTable.encryptedFingerprint]
                return@dbQuery if (fingerprint != null && matchesFingerprint(fingerprint)) {
                    DiagnosticSaveResult.EXISTING
                } else {
                    DiagnosticSaveResult.CONFLICT
                }
            }
            val used =
                DiagnosticReportUploadsTable
                    .selectAll()
                    .where {
                        (DiagnosticReportUploadsTable.accountId eq account) and
                            (DiagnosticReportUploadsTable.createdAtMs greater report.createdAt - DiagnosticReportService.DAY_MS)
                    }.count()
            if (used >= DiagnosticReportService.DAILY_LIMIT) return@dbQuery DiagnosticSaveResult.DAILY_LIMIT

            DiagnosticReportsTable.insert {
                it[accountId] = account
                it[reportId] = report.reportId.toJavaUUID()
                it[createdAtMs] = report.createdAt
                it[encryptedPayload] = report.encryptedPayload
                it[encryptedFingerprint] = report.encryptedFingerprint
            }
            DiagnosticReportUploadsTable.insert {
                it[accountId] = account
                it[reportId] = report.reportId.toJavaUUID()
                it[createdAtMs] = report.createdAt
                it[encryptedFingerprint] = report.encryptedFingerprint
            }

            val oldest =
                DiagnosticReportsTable
                    .selectAll()
                    .where { DiagnosticReportsTable.accountId eq account }
                    .orderBy(DiagnosticReportsTable.createdAtMs, SortOrder.DESC)
                    .drop(DiagnosticReportService.RETAINED_LIMIT)
                    .map { it[DiagnosticReportsTable.reportId] }
            oldest.forEach { id ->
                DiagnosticReportsTable.deleteWhere { (accountId eq account) and (reportId eq id) }
            }
            DiagnosticSaveResult.CREATED
        }

    override suspend fun list(
        owner: Uuid,
        now: Long,
    ): List<StoredDiagnosticReport> =
        dbQuery {
            DiagnosticReportsTable
                .selectAll()
                .where {
                    (DiagnosticReportsTable.accountId eq owner.toJavaUUID()) and
                        (DiagnosticReportsTable.createdAtMs greater now - DiagnosticReportService.RETENTION_MS)
                }.orderBy(DiagnosticReportsTable.createdAtMs, SortOrder.DESC)
                .map { row ->
                    StoredDiagnosticReport(
                        row[DiagnosticReportsTable.reportId].toKotlinUuid(),
                        row[DiagnosticReportsTable.createdAtMs],
                        row[DiagnosticReportsTable.encryptedPayload],
                        row[DiagnosticReportsTable.encryptedFingerprint],
                    )
                }
        }

    override suspend fun find(
        owner: Uuid,
        reportId: Uuid,
        now: Long,
    ): StoredDiagnosticReport? =
        dbQuery {
            DiagnosticReportsTable
                .selectAll()
                .where {
                    (DiagnosticReportsTable.accountId eq owner.toJavaUUID()) and
                        (DiagnosticReportsTable.reportId eq reportId.toJavaUUID()) and
                        (DiagnosticReportsTable.createdAtMs greater now - DiagnosticReportService.RETENTION_MS)
                }.singleOrNull()
                ?.let { row ->
                    StoredDiagnosticReport(
                        row[DiagnosticReportsTable.reportId].toKotlinUuid(),
                        row[DiagnosticReportsTable.createdAtMs],
                        row[DiagnosticReportsTable.encryptedPayload],
                        row[DiagnosticReportsTable.encryptedFingerprint],
                    )
                }
        }

    override suspend fun delete(
        owner: Uuid,
        reportId: Uuid,
    ): Boolean =
        dbQuery {
            val account = owner.toJavaUUID()
            AccountsTable
                .selectAll()
                .where { AccountsTable.id eq account }
                .forUpdate()
                .single()
            val existing =
                DiagnosticReportUploadsTable
                    .selectAll()
                    .where {
                        (DiagnosticReportUploadsTable.accountId eq account) and
                            (DiagnosticReportUploadsTable.reportId eq reportId.toJavaUUID())
                    }.singleOrNull()
            DiagnosticReportUploadsTable.update({
                (DiagnosticReportUploadsTable.accountId eq account) and
                    (DiagnosticReportUploadsTable.reportId eq reportId.toJavaUUID())
            }) {
                it[encryptedFingerprint] = null
            }
            val removed =
                DiagnosticReportsTable.deleteWhere {
                    (accountId eq account) and (DiagnosticReportsTable.reportId eq reportId.toJavaUUID())
                }
            removed > 0 || existing?.get(DiagnosticReportUploadsTable.encryptedFingerprint) != null
        }

    override suspend fun deleteAll(owner: Uuid): Int =
        dbQuery {
            val account = owner.toJavaUUID()
            AccountsTable
                .selectAll()
                .where { AccountsTable.id eq account }
                .forUpdate()
                .single()
            DiagnosticReportUploadsTable.update({ DiagnosticReportUploadsTable.accountId eq account }) {
                it[encryptedFingerprint] = null
            }
            DiagnosticReportsTable.deleteWhere { accountId eq account }
        }

    override suspend fun purgeExpired(before: Long): Int =
        dbQuery {
            val deleted = DiagnosticReportsTable.deleteWhere { createdAtMs lessEq before }
            DiagnosticReportUploadsTable.deleteWhere { createdAtMs lessEq before }
            deleted
        }
}
