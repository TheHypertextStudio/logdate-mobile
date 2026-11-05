package app.logdate.server.database

import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.requireRoom
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Clock

/** Account-first space counters, shared by record writes, blob finalization and reservations. */
internal object PostgreSQLSpaceUsage {
    /**
     * Adds [delta] to [accountId]'s stored bytes, first checking growth against [quota] together with
     * the room other uploads hold, leaving out [reservation]. Locks the usage row until the
     * transaction ends, so concurrent writes check one after another. Every path takes this lock
     * after any record, state or blob row lock it needs, so the order never deadlocks.
     */
    fun change(
        accountId: UUID,
        delta: Long,
        quota: SpaceQuota? = null,
        reservation: UUID? = null,
    ) {
        val stored = lock(accountId)
        if (delta > 0 && quota != null) requireRoom(quota, stored + reserved(accountId, except = reservation), delta)
        AccountStorageTotals.set(accountId, StorageKind.SPACE, stored, delta)
    }

    /** Locks [accountId]'s storage totals until the transaction ends and returns its space bytes. */
    fun lock(accountId: UUID): Long =
        requireNotNull(AccountStorageTotals.lock(accountId)) { "Space rows belong to an account that exists" }.spaceBytes

    /** The room [accountId]'s unexpired upload reservations hold, leaving out [except]. */
    fun reserved(
        accountId: UUID,
        except: UUID?,
    ): Long {
        val total = SpaceUploadReservationsTable.bytes.sum()
        val active =
            (SpaceUploadReservationsTable.accountId eq accountId) and
                (SpaceUploadReservationsTable.expiresAt greater Clock.System.now())
        return SpaceUploadReservationsTable
            .select(total)
            .where { if (except == null) active else active and (SpaceUploadReservationsTable.id neq except) }
            .single()[total] ?: 0L
    }
}
