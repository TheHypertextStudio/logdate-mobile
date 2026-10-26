package app.logdate.client.sync

import app.logdate.client.sync.metadata.UploadScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteSyncRefreshScheduleTest {
    @Test
    fun `remote discovery is initially due then bounded independently of local queues`() {
        var time = 1_000L
        val schedule = RemoteSyncRefreshSchedule { time }
        val scope = UploadScope("account", "https://cloud.example")
        assertTrue(schedule.isDue(scope))
        schedule.recordAttempt(scope)
        assertFalse(schedule.isDue(scope))
        time += RemoteSyncRefreshSchedule.REFRESH_INTERVAL_MS - 1
        assertFalse(schedule.isDue(scope))
        time++
        assertTrue(schedule.isDue(scope))
        assertTrue(schedule.isDue(scope.copy(ownerId = "another")))
        assertTrue(schedule.isDue(scope.copy(serverOrigin = "https://other.example")))
    }
}
