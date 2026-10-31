package app.logdate.client.media

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OncePerProcessTest {
    @Test
    fun `legacy media is scanned once however many galleries collect`() =
        runTest {
            var scans = 0
            val backfill = OncePerProcess { scans++ }

            (1..5).map { async { backfill.run() } }.awaitAll()
            backfill.run()

            assertEquals(1, scans)
        }

    @Test
    fun `a scan that fails is attempted again by the next collection`() =
        runTest {
            var scans = 0
            val backfill =
                OncePerProcess {
                    scans++
                    if (scans == 1) error("storage unavailable")
                }

            assertFailsWith<IllegalStateException> { backfill.run() }
            backfill.run()
            backfill.run()

            assertEquals(2, scans)
        }
}
