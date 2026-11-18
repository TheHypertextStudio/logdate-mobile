package app.logdate.client.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The journal's queries get their own share of the IO threads rather than competing in
 * [Dispatchers.IO] itself. Background work, file copies and image decoding also block IO threads;
 * when they fill the shared 64, every screen that reads the journal waits behind them.
 */
internal actual val roomDatabaseDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(ROOM_QUERY_PARALLELISM)

/** The default size of [Dispatchers.IO], so the journal keeps as many threads as it had when it shared that pool. */
internal const val ROOM_QUERY_PARALLELISM = 64
