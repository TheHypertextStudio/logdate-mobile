package app.logdate.client.data.search

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/**
 * Emits the first value at once, then only values that are not followed by another within
 * [timeout]. A screen shows results immediately and re-queries once per burst of changes.
 */
@OptIn(FlowPreview::class)
internal fun <T> Flow<T>.debounceAfterFirst(timeout: Duration): Flow<T> =
    flow {
        var first = true
        emitAll(
            debounce {
                if (first) {
                    first = false
                    Duration.ZERO
                } else {
                    timeout
                }
            },
        )
    }
