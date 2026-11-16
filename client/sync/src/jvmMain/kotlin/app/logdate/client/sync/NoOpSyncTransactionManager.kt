package app.logdate.client.sync

/**
 * No-op implementation of SyncTransactionManager for platforms without database transaction support.
 * Simply executes the block without transaction semantics.
 * This is adequate for platforms like Desktop where SQLite transactions are handled differently.
 */
class NoOpSyncTransactionManager : SyncTransactionManager {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
}
