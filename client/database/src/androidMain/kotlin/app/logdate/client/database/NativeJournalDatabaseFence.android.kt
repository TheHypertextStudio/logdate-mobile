package app.logdate.client.database

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import io.github.aakira.napier.Napier
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private const val STALL_REPORT_SECONDS = 15L
private const val STALL_REPORT_FRAMES = 48
private const val STALL_TAG = "JournalDatabaseFence"

private val nativeJournalFences = ConcurrentHashMap<String, NativeJournalDatabaseFence>()

private fun nativeJournalFence(file: File): NativeJournalDatabaseFence =
    nativeJournalFences.computeIfAbsent(file.canonicalPath) { NativeJournalDatabaseFence() }

/** Keeps SQL ownership through preference capture, including independently opened backup handles. */
internal fun <T> withNativeJournalDatabaseFence(
    source: File,
    action: () -> T,
): T = nativeJournalFence(source).run(action)

internal fun nativeJournalOpenHelperFactory(
    databaseFile: File,
    delegate: SupportSQLiteOpenHelper.Factory,
): SupportSQLiteOpenHelper.Factory {
    val fence = nativeJournalFence(databaseFile)
    return SupportSQLiteOpenHelper.Factory { configuration ->
        val helper = delegate.create(configuration)
        object : SupportSQLiteOpenHelper by helper {
            @Volatile private var opened: SupportSQLiteDatabase? = null

            // Room checks thread-local ownership before dispatching a suspend DAO call.
            // Reuse only this helper's validated handle; all actual SQL remains fenced.
            private fun current(writable: Boolean): SupportSQLiteDatabase? = opened?.takeIf { it.isOpen && (!writable || !it.isReadOnly) }

            private fun open(writable: Boolean): SupportSQLiteDatabase =
                current(writable) ?: fence.run {
                    current(writable) ?: NativeJournalFencedDatabase(
                        if (writable) helper.writableDatabase else helper.readableDatabase,
                        fence,
                    ).also { opened = it }
                }

            override val writableDatabase: SupportSQLiteDatabase
                get() = open(writable = true)

            override val readableDatabase: SupportSQLiteDatabase
                get() = open(writable = false)

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) = fence.run { helper.setWriteAheadLoggingEnabled(enabled) }

            override fun close() =
                fence.run {
                    try {
                        helper.close()
                    } finally {
                        opened = null
                    }
                }
        }
    }
}

/** Exposes the owner so a stalled journal can say which thread is holding it. */
private class OwnedFenceLock : ReentrantLock(true) {
    val holder: Thread? get() = owner
}

private class NativeJournalDatabaseFence {
    private val lock = OwnedFenceLock()
    private val transactionDepth = ThreadLocal.withInitial { 0 }

    /** Waits for the fence, and reports the holder once if the wait passes [STALL_REPORT_SECONDS]. */
    private fun acquire() {
        if (lock.tryLock(STALL_REPORT_SECONDS, TimeUnit.SECONDS)) return
        val holder = lock.holder
        val trace =
            holder
                ?.stackTrace
                ?.take(STALL_REPORT_FRAMES)
                ?.joinToString(separator = "\n  at ", prefix = "\n  at ")
                .orEmpty()
        Napier.w(
            "Journal database has been held for over ${STALL_REPORT_SECONDS}s by ${holder?.name} " +
                "(${holder?.state}); ${Thread.currentThread().name} is waiting$trace",
            tag = STALL_TAG,
        )
        lock.lock()
    }

    fun <T> run(action: () -> T): T {
        acquire()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun <T> begin(action: () -> T): T {
        acquire()
        try {
            return action().also { transactionDepth.set(transactionDepth.get() + 1) }
        } catch (error: Throwable) {
            lock.unlock()
            throw error
        }
    }

    fun <T> end(action: () -> T): T {
        if (transactionDepth.get() == 0) return run(action)
        try {
            return action()
        } finally {
            val remaining = transactionDepth.get() - 1
            if (remaining == 0) transactionDepth.remove() else transactionDepth.set(remaining)
            lock.unlock()
        }
    }

    fun <T> sql(
        sql: String,
        action: () -> T,
    ): T =
        when (DatabaseUtils.getSqlStatementType(sql)) {
            DatabaseUtils.STATEMENT_BEGIN -> begin(action)
            DatabaseUtils.STATEMENT_COMMIT, DatabaseUtils.STATEMENT_ABORT -> end(action)
            else -> run(action)
        }

    /** Room consumes and closes these cursors on its SQL thread before suspending. */
    fun query(action: () -> Cursor): Cursor {
        acquire()
        try {
            val cursor = action()
            return object : Cursor by cursor {
                private val released = AtomicBoolean()

                override fun close() {
                    if (!released.compareAndSet(false, true)) return
                    try {
                        cursor.close()
                    } finally {
                        lock.unlock()
                    }
                }
            }
        } catch (error: Throwable) {
            lock.unlock()
            throw error
        }
    }
}

private class NativeJournalFencedDatabase(
    private val database: SupportSQLiteDatabase,
    private val fence: NativeJournalDatabaseFence,
) : SupportSQLiteDatabase by database {
    override fun beginTransaction() = fence.begin { database.beginTransaction() }

    override fun beginTransactionNonExclusive() = fence.begin { database.beginTransactionNonExclusive() }

    override fun beginTransactionReadOnly() = fence.begin { database.beginTransactionReadOnly() }

    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
        fence.begin { database.beginTransactionWithListener(transactionListener) }

    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
        fence.begin { database.beginTransactionWithListenerNonExclusive(transactionListener) }

    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) =
        fence.begin { database.beginTransactionWithListenerReadOnly(transactionListener) }

    override fun endTransaction() = fence.end { database.endTransaction() }

    override fun setTransactionSuccessful() = fence.run { database.setTransactionSuccessful() }

    override fun yieldIfContendedSafely(): Boolean = fence.run { database.yieldIfContendedSafely() }

    override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean =
        fence.run { database.yieldIfContendedSafely(sleepAfterYieldDelayMillis) }

    override fun compileStatement(sql: String): SupportSQLiteStatement =
        fence.run {
            val statement = database.compileStatement(sql)
            object : SupportSQLiteStatement by statement {
                override fun execute() = fence.sql(sql) { statement.execute() }

                override fun executeInsert(): Long = fence.sql(sql) { statement.executeInsert() }

                override fun executeUpdateDelete(): Int = fence.sql(sql) { statement.executeUpdateDelete() }

                override fun simpleQueryForLong(): Long = fence.sql(sql) { statement.simpleQueryForLong() }

                override fun simpleQueryForString(): String? = fence.sql(sql) { statement.simpleQueryForString() }

                override fun close() = fence.run { statement.close() }
            }
        }

    override fun query(query: String): Cursor = fence.query { database.query(query) }

    override fun query(
        query: String,
        bindArgs: Array<out Any?>,
    ): Cursor = fence.query { database.query(query, bindArgs) }

    override fun query(query: SupportSQLiteQuery): Cursor = fence.query { database.query(query) }

    override fun query(
        query: SupportSQLiteQuery,
        cancellationSignal: CancellationSignal?,
    ): Cursor = fence.query { database.query(query, cancellationSignal) }

    override fun execSQL(sql: String) = fence.sql(sql) { database.execSQL(sql) }

    override fun execSQL(
        sql: String,
        bindArgs: Array<out Any?>,
    ) = fence.sql(sql) { database.execSQL(sql, bindArgs) }

    override fun insert(
        table: String,
        conflictAlgorithm: Int,
        values: ContentValues,
    ): Long = fence.run { database.insert(table, conflictAlgorithm, values) }

    override fun delete(
        table: String,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int = fence.run { database.delete(table, whereClause, whereArgs) }

    override fun update(
        table: String,
        conflictAlgorithm: Int,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int = fence.run { database.update(table, conflictAlgorithm, values, whereClause, whereArgs) }

    override var version: Int
        get() = fence.run { database.version }
        set(value) = fence.run { database.version = value }

    override var pageSize: Long
        get() = fence.run { database.pageSize }
        set(value) = fence.run { database.pageSize = value }

    override val maximumSize: Long
        get() = fence.run { database.maximumSize }

    override fun setMaximumSize(numBytes: Long): Long = fence.run { database.setMaximumSize(numBytes) }

    override fun setLocale(locale: Locale) = fence.run { database.setLocale(locale) }

    override fun setMaxSqlCacheSize(cacheSize: Int) = fence.run { database.setMaxSqlCacheSize(cacheSize) }

    override fun setForeignKeyConstraintsEnabled(enabled: Boolean) = fence.run { database.setForeignKeyConstraintsEnabled(enabled) }

    override fun enableWriteAheadLogging(): Boolean = fence.run { database.enableWriteAheadLogging() }

    override fun disableWriteAheadLogging() = fence.run { database.disableWriteAheadLogging() }

    override val isWriteAheadLoggingEnabled: Boolean
        get() = fence.run { database.isWriteAheadLoggingEnabled }

    override fun execPerConnectionSQL(
        sql: String,
        bindArgs: Array<out Any?>?,
    ) = fence.run { database.execPerConnectionSQL(sql, bindArgs) }

    override fun close() = fence.run { database.close() }
}
