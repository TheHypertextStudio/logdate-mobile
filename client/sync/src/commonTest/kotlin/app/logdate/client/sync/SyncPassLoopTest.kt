package app.logdate.client.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncPassLoopTest {
    private fun pass(
        uploaded: Int = 0,
        hasMorePending: Boolean = false,
        success: Boolean = true,
    ) = SyncResult(success = success, uploadedItems = uploaded, hasMorePending = hasMorePending)

    /** Runs the loop over [results] in order, counting how many passes actually ran. */
    private suspend fun countPasses(
        results: List<SyncResult>,
        shouldContinue: suspend () -> Boolean = { true },
    ): Int {
        var index = 0
        var ran = 0
        SyncPassLoop(
            runPass = {
                ran++
                results.getOrElse(index++) { results.last() }
            },
            shouldContinue = shouldContinue,
            pause = {},
        ).runUntilSettled()
        return ran
    }

    @Test
    fun `a pass with nothing more due ends the loop`() =
        runTest {
            assertEquals(1, countPasses(listOf(pass(uploaded = 1))))
        }

    @Test
    fun `passes repeat while each one makes progress and more work is due`() =
        runTest {
            val passes = listOf(pass(uploaded = 2, hasMorePending = true), pass(uploaded = 1, hasMorePending = true), pass(uploaded = 1))

            assertEquals(3, countPasses(passes))
        }

    @Test
    fun `passes that make no progress stop after the stall limit`() =
        runTest {
            val stalled = pass(hasMorePending = true)

            assertEquals(SyncPassLoop.MAX_STALLED_PASSES, countPasses(listOf(stalled)))
        }

    @Test
    fun `a failed pass ends the loop even when more work is due`() =
        runTest {
            assertEquals(1, countPasses(listOf(pass(uploaded = 1, hasMorePending = true, success = false))))
        }

    @Test
    fun `the loop stops when the policy no longer allows another pass`() =
        runTest {
            val passes = listOf(pass(uploaded = 1, hasMorePending = true))

            assertEquals(1, countPasses(passes, shouldContinue = { false }))
        }
}
