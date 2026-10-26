package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.cloud.BackupMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArchiveWorkHistoryTest {
    @Test
    fun `latest successful attempt supersedes old failures regardless of history order`() {
        val failed = CloudArchiveAttempt("old", ArchiveWorkState.FAILED, 10)
        val success = CloudArchiveAttempt("new", ArchiveWorkState.SUCCEEDED, 20, "backup", "device")
        assertEquals(success, selectArchiveWork(listOf(failed, success)))
        assertEquals(success, selectArchiveWork(listOf(success, failed)))
    }

    @Test
    fun `a later failure is never masked by earlier successful history`() {
        val success = CloudArchiveAttempt("old", ArchiveWorkState.SUCCEEDED, 10, "backup", "device")
        val failed = CloudArchiveAttempt("new", ArchiveWorkState.FAILED, 20)
        assertEquals(failed, selectArchiveWork(listOf(success, failed)))
    }

    @Test
    fun `periodic terminal record supersedes old immediate history`() {
        val failed = CloudArchiveAttempt("manual", ArchiveWorkState.FAILED, 10)
        val periodic = CloudArchiveAttempt("periodic", ArchiveWorkState.SUCCEEDED, 20, "backup", "device")
        assertEquals(periodic, selectArchiveWork(listOf(failed), periodic))
    }

    @Test
    fun `unknown legacy success cannot hide known new failure`() {
        val failed = CloudArchiveAttempt("new", ArchiveWorkState.FAILED, 20)
        assertEquals(failed, selectArchiveWork(listOf(CloudArchiveAttempt("legacy", ArchiveWorkState.SUCCEEDED), failed)))
    }

    @Test
    fun `confirmation must identify this successful request and device`() {
        val success = CloudArchiveAttempt("new", ArchiveWorkState.SUCCEEDED, 20, "backup", "device")
        val other = BackupMetadata("other", "other-device", "encrypted", 30, 42, "url")
        val expected = BackupMetadata("backup", "device", "encrypted", 20, 42, "url")
        assertNull(confirmedArchiveAttemptAt(success, listOf(other), "encrypted"))
        assertEquals(20L, confirmedArchiveAttemptAt(success, listOf(other, expected), "encrypted"))
        assertNull(confirmedArchiveAttemptAt(success.copy(state = ArchiveWorkState.FAILED), listOf(expected), "encrypted"))
    }
}
