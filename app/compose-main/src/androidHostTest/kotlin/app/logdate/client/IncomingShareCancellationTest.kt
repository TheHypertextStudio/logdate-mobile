package app.logdate.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IncomingShareCancellationTest {
    @Test
    fun `cancellation is rethrown instead of becoming an attachment failure`() {
        runBlocking {
            assertFailsWith<CancellationException> {
                importSharedAttachment("content://provider/first") {
                    throw CancellationException("Incoming share owner was cancelled")
                }
            }
        }
    }
}
