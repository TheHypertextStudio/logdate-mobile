package app.logdate.feature.core.export

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudBackupRequestsTest {
    @Test
    fun `another immediate request retains the running backup`() {
        val queue = ActiveQueue()
        val requests = CloudBackupRequests(queue)
        requests.immediate()
        val running = queue.work.getValue("logdate:cloud:backup:immediate")
        requests.immediate()
        assertEquals(running, queue.work["logdate:cloud:backup:immediate"])
        assertEquals(1, queue.work.size)
    }

    @Test
    fun `periodic scheduling retains the existing schedule`() {
        val queue = ActiveQueue()
        val requests = CloudBackupRequests(queue)
        requests.periodic()
        val existing = queue.work.getValue("logdate:cloud:backup")
        requests.periodic()
        assertEquals(existing, queue.work["logdate:cloud:backup"])
    }

    private class ActiveQueue : CloudBackupWorkQueue {
        val work = mutableMapOf<String, Int>()
        private var sequence = 0

        override fun enqueueUnique(
            name: String,
            periodic: Boolean,
            keepExisting: Boolean,
        ) {
            if (!keepExisting || name !in work) work[name] = ++sequence
        }
    }
}
