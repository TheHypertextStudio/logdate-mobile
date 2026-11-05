package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionCoverage
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Instant

/** Complete private coverage in the same account-locked transaction as the current source census. */
internal class PostgreSQLIdentityAdoptionCoverage(
    private val source: DataSource,
) {
    suspend fun read(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionCoverage? =
        withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction { readLocked(this, operation, now) }
        }

    internal fun readLocked(
        connection: Connection,
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionCoverage? {
        check(RawJdbcTransactionContext.isActive())
        if (operation.phase !in setOf(IdentityAdoptionPhase.FENCED, IdentityAdoptionPhase.VERIFIED)) return null
        val census = PostgreSQLIdentityAdoptionSourceCensus(source).readLocked(connection, operation, now) ?: return null
        check(census.fenced)
        return IdentityAdoptionCoverageScan(source, connection, operation, census, now).read()
    }
}
