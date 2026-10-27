package app.logdate.client.sync

import kotlinx.coroutines.delay

/**
 * Repeats sync passes while each one makes progress and more work is due now.
 *
 * Stops on the first failed pass, when nothing is due, when [shouldContinue] says so, or after
 * [MAX_STALLED_PASSES] passes in a row that move nothing. Work left behind is picked up by the next
 * scheduled sync rather than by spinning here.
 */
internal class SyncPassLoop(
    private val runPass: suspend () -> SyncResult,
    private val shouldContinue: suspend () -> Boolean = { true },
    private val pause: suspend () -> Unit = { delay(PASS_SPACING_MS) },
) {
    suspend fun runUntilSettled() {
        var stalledPasses = 0
        while (true) {
            val result = runPass()
            if (!result.success || !result.hasMorePending) return
            stalledPasses = if (result.madeProgress) 0 else stalledPasses + 1
            if (stalledPasses >= MAX_STALLED_PASSES || !shouldContinue()) return
            pause()
        }
    }

    private val SyncResult.madeProgress: Boolean
        get() = uploadedItems + downloadedItems + conflictsResolved > 0

    companion object {
        const val PASS_SPACING_MS = 1_000L
        const val MAX_STALLED_PASSES = 3
    }
}
