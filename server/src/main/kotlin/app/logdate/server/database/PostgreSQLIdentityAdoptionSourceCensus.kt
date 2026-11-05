package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceCensus
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceSummary
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Instant

/** A single account-locked census, including every page and the complete owned repository graph. */
internal class PostgreSQLIdentityAdoptionSourceCensus(
    private val source: DataSource,
) {
    suspend fun read(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionSourceCensus? =
        withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction { readLocked(this, operation, now) }
        }

    internal fun readLocked(
        connection: Connection,
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionSourceCensus? {
        val repository = PostgreSQLIdentityAdoptionRepositoryInventory(source).readLocked(connection, operation, now) ?: return null
        val sources =
            IdentityAdoptionSourceKind.entries.map { kind ->
                summary(connection, operation, repository, kind, now) ?: return null
            }
        val digest =
            IdentityAdoptionSourceEvidence.census(
                operation,
                repository.generation,
                repository.fenced,
                sources,
                repository.inventory,
            )
        return IdentityAdoptionSourceCensus(
            operation.id,
            operation.revision,
            repository.generation,
            repository.fenced,
            sources,
            repository.inventory,
            digest,
        )
    }

    private fun summary(
        connection: Connection,
        operation: IdentityAdoptionOperation,
        repository: IdentityAdoptionRepositorySnapshot,
        kind: IdentityAdoptionSourceKind,
        now: Instant,
    ): IdentityAdoptionSourceSummary? {
        val reader = PostgreSQLIdentityAdoptionSourceInventory(source)
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("app.logdate.identity-adoption.source-items|1\u0000".encodeToByteArray())
        var cursor: List<String>? = null
        var expectedCount: Long? = null
        var present: Boolean? = null
        var count = 0L
        do {
            val page = reader.pageLocked(connection, operation, kind, cursor, 32, now) ?: return null
            stagingCheck(
                page.operationId == operation.id &&
                    page.revision == operation.revision &&
                    page.kind == kind &&
                    page.generation == repository.generation &&
                    page.fenced == repository.fenced,
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            if (expectedCount == null) {
                expectedCount = page.count
                present = page.present
            }
            stagingCheck(page.count == expectedCount && page.present == present, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            for (item in page.items) {
                hash.update(IdentityAdoptionSourceEvidence.item(kind, item))
                count = Math.addExact(count, 1L)
            }
            stagingCheck(
                page.next == null || (page.items.isNotEmpty() && page.next != cursor),
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            cursor = page.next
        } while (cursor != null)
        stagingCheck(count == expectedCount, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
        return IdentityAdoptionSourceEvidence.summary(kind, requireNotNull(present), count, hash.digest())
    }
}
