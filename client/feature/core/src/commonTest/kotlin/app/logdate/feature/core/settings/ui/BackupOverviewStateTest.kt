package app.logdate.feature.core.settings.ui

import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.SyncPausedReason
import app.logdate.client.sync.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupOverviewStateTest {
    private val idle = SyncStatus(true, null, 0, false, false)

    @Test
    fun `unsupported targets never offer a request that cannot run`() =
        runTest {
            val archive = UnavailableCloudArchiveStatusSource.observe(UserSession("access", "refresh", "account")).first()
            assertFalse(backupOverviewState(idle, archive).canBackUpNow)
            assertEquals(app.logdate.feature.core.sync.AccountSyncStatus.UP_TO_DATE, backupStatus(idle, archive))
        }

    @Test
    fun `completed sync cannot stand in for a confirmed archive`() {
        val state = backupOverviewState(idle, CloudArchiveStatus(CloudArchivePhase.NEVER_BACKED_UP))
        assertEquals(null, state.lastBackupAt)
        assertTrue(state.canBackUpNow)
    }

    @Test
    fun `previous confirmed archive remains separate from pending changes`() {
        val state = backupOverviewState(idle.copy(pendingUploads = 3), CloudArchiveStatus(CloudArchivePhase.COMPLETE, 1234L))
        assertEquals(1234L, state.lastBackupAt)
        assertEquals(3, state.pendingChanges)
    }

    @Test
    fun `recovery requirements cannot be bypassed by the primary action`() {
        val state =
            backupOverviewState(
                idle.copy(pausedReason = SyncPausedReason.NEEDS_RECOVERY_PHRASE),
                CloudArchiveStatus(CloudArchivePhase.FAILED),
            )
        assertFalse(state.canBackUpNow)
    }

    @Test
    fun `running and queued work do not offer duplicate requests`() {
        for (phase in listOf(CloudArchivePhase.RUNNING, CloudArchivePhase.QUEUED, CloudArchivePhase.NEEDS_RECOVERY)) {
            assertFalse(backupOverviewState(idle, CloudArchiveStatus(phase)).canBackUpNow)
        }
    }

    @Test
    fun `unresolved conflicts lead to review rather than retry override`() {
        val state = backupOverviewState(idle.copy(conflictCount = 2), CloudArchiveStatus(CloudArchivePhase.FAILED))
        assertFalse(state.canBackUpNow)
        assertTrue(state.needsReview)
    }

    @Test
    fun `unreadable queue and identity dependent content require recovery first`() {
        val archive = CloudArchiveStatus(CloudArchivePhase.COMPLETE, 1234L)
        assertFalse(backupOverviewState(idle.copy(queueReadable = false), archive).canBackUpNow)
        assertFalse(backupOverviewState(idle.copy(unreadableCloudCount = 1), archive).canBackUpNow)
        assertFalse(backupOverviewState(null, archive).canBackUpNow)
    }

    @Test
    fun `only actual issues create a review action`() {
        val archive = CloudArchiveStatus(CloudArchivePhase.COMPLETE, 1234L)
        assertFalse(backupOverviewState(idle.copy(hasErrors = true), archive).needsReview)
        assertTrue(backupOverviewState(idle, archive, 1).needsReview)
        assertFalse(backupOverviewState(idle, archive, 1).canBackUpNow)
    }

    @Test
    fun `waiting alone does not create an issue action`() {
        val state = backupOverviewState(idle.copy(pendingUploads = 2), CloudArchiveStatus(CloudArchivePhase.QUEUED))
        assertFalse(state.needsReview)
        assertEquals(2, state.pendingChanges)
    }

    @Test
    fun `earlier metadata stays discoverable without blocking a current backup`() {
        val state = backupOverviewState(idle.copy(earlierConflictCount = 1268), CloudArchiveStatus(CloudArchivePhase.NEVER_BACKED_UP))
        assertTrue(state.needsReview)
        assertTrue(state.canBackUpNow)
    }
}
