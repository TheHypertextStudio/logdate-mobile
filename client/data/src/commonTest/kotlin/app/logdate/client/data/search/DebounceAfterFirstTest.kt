package app.logdate.client.data.search

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class DebounceAfterFirstTest {
    @Test
    fun `the first index generation is searched at once and a burst of changes once`() =
        runTest {
            val generations =
                flow {
                    emit(1L)
                    delay(1_000)
                    repeat(50) { emit(2L + it) }
                    delay(1_000)
                    emit(100L)
                }
            val searched = mutableListOf<Long>()

            val job = launch { generations.debounceAfterFirst(300.milliseconds).toList(searched) }
            runCurrent()
            assertEquals(listOf(1L), searched, "the first results waited for the debounce")

            advanceTimeBy(5_000)
            job.join()

            assertEquals(listOf(1L, 51L, 100L), searched)
        }
}
