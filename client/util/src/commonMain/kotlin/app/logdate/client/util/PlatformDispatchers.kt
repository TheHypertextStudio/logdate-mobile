package app.logdate.client.util

import kotlinx.coroutines.CoroutineDispatcher

expect val platformIODispatcher: CoroutineDispatcher

/**
 * Where repositories read and write the journal. Kept apart from [platformIODispatcher] where that pool
 * is shared with blocking work, so a backlog of file copies or media decoding does not hold up the
 * screens that read the journal.
 */
expect val platformDataDispatcher: CoroutineDispatcher
