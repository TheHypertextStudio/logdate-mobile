package app.logdate.client.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val platformIODispatcher: CoroutineDispatcher = Dispatchers.IO

/**
 * A separate view of [Dispatchers.IO]: its threads do not count against the 64 that background work,
 * file copies and image decoding fill, so the journal keeps as many threads as it had before.
 */
actual val platformDataDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DATA_PARALLELISM)

/** The default size of [Dispatchers.IO]. */
internal const val DATA_PARALLELISM = 64
