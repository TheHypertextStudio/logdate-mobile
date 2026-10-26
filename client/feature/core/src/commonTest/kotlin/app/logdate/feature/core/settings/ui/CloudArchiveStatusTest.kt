package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.cloud.BackupMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudArchiveStatusTest {
    @Test
    fun `terminal and unconfirmed archives offer manual retry instead of claiming automatic recovery`() {
        assertTrue(CloudArchiveStatus(CloudArchivePhase.FAILED).needsManualRetry)
        assertTrue(CloudArchiveStatus(CloudArchivePhase.UNAVAILABLE).needsManualRetry)
        listOf(
            CloudArchivePhase.RUNNING,
            CloudArchivePhase.QUEUED,
            CloudArchivePhase.RETRYING,
            CloudArchivePhase.COMPLETE,
            CloudArchivePhase.NEEDS_RECOVERY,
            CloudArchivePhase.SIGNED_OUT,
        ).forEach {
            assertFalse(CloudArchiveStatus(it).needsManualRetry)
        }
    }

    @Test
    fun `running archive exposes measured export progress without claiming remote completion`() {
        val progress = CloudArchiveProgress(CloudArchiveStage.EXPORTING, 0.4f)
        val status = resolveCloudArchiveStatus(true, ArchiveWorkState.RUNNING, null, false, progress)
        assertEquals(progress, status.progress)
        assertEquals(null, status.lastCompletedAt)
    }

    @Test
    fun `finished workers do not retain stale progress`() {
        val status =
            resolveCloudArchiveStatus(true, ArchiveWorkState.SUCCEEDED, null, false, CloudArchiveProgress(CloudArchiveStage.UPLOADING))
        assertEquals(null, status.progress)
        assertEquals(CloudArchivePhase.NEVER_BACKED_UP, status.phase)
    }

    @Test
    fun `missing identity never reports a successful archive`() {
        val status = resolveCloudArchiveStatus(false, ArchiveWorkState.SUCCEEDED, 1234L, false)

        assertEquals(CloudArchivePhase.NEEDS_RECOVERY, status.phase)
        assertEquals(null, status.lastCompletedAt)
        assertFalse(status.canRetry)
    }

    @Test
    fun `remote encrypted archive is the only evidence of last success`() {
        val localSuccessOnly = resolveCloudArchiveStatus(true, ArchiveWorkState.SUCCEEDED, null, false)
        val remoteSuccess = resolveCloudArchiveStatus(true, ArchiveWorkState.IDLE, 1234L, false)

        assertEquals(CloudArchivePhase.NEVER_BACKED_UP, localSuccessOnly.phase)
        assertEquals(null, localSuccessOnly.lastCompletedAt)
        assertEquals(CloudArchivePhase.COMPLETE, remoteSuccess.phase)
        assertEquals(1234L, remoteSuccess.lastCompletedAt)
    }

    @Test
    fun `queued running and retrying work cannot masquerade as complete`() {
        assertEquals(
            CloudArchivePhase.QUEUED,
            resolveCloudArchiveStatus(true, ArchiveWorkState.QUEUED, 1234L, false).phase,
        )
        assertEquals(
            CloudArchivePhase.RUNNING,
            resolveCloudArchiveStatus(true, ArchiveWorkState.RUNNING, 1234L, false).phase,
        )
        assertEquals(
            CloudArchivePhase.RETRYING,
            resolveCloudArchiveStatus(true, ArchiveWorkState.RETRYING, 1234L, false).phase,
        )
    }

    @Test
    fun `remote lookup failure never reports a healthy archive`() {
        val status = resolveCloudArchiveStatus(true, ArchiveWorkState.IDLE, 1234L, true)

        assertEquals(CloudArchivePhase.UNAVAILABLE, status.phase)
        assertEquals(null, status.lastCompletedAt)
        assertTrue(status.canRetry)
    }

    @Test
    fun `failed work remains actionable even after an earlier backup`() {
        val status = resolveCloudArchiveStatus(true, ArchiveWorkState.FAILED, 1234L, false)

        assertEquals(CloudArchivePhase.FAILED, status.phase)
        assertEquals(1234L, status.lastCompletedAt)
        assertTrue(status.canRetry)
    }

    @Test
    fun `last account archive includes another device but ignores legacy backups`() {
        val backups =
            listOf(
                backup("another-device", ENCRYPTED_MANIFEST, 5000L),
                backup("this-device", "{}", 6000L),
                backup("this-device", ENCRYPTED_MANIFEST, 3000L),
            )

        assertEquals(5000L, latestEncryptedArchiveAt(backups, ENCRYPTED_MANIFEST))
        assertEquals(null, latestEncryptedArchiveAt(backups, "unknown-manifest"))
    }

    @Test
    fun `archive confirmation cannot leak across sign out or account switch`() {
        val complete = CloudArchiveStatus(CloudArchivePhase.COMPLETE, 1234L)

        assertEquals(CloudArchivePhase.SIGNED_OUT, scopedCloudArchiveStatus(null, "account-a", complete).phase)
        assertEquals(CloudArchivePhase.CHECKING, scopedCloudArchiveStatus("account-b", "account-a", complete).phase)
        assertEquals(complete, scopedCloudArchiveStatus("account-a", "account-a", complete))
    }

    private fun backup(
        deviceId: String,
        manifest: String,
        createdAt: Long,
    ) = BackupMetadata("id-$createdAt", deviceId, manifest, createdAt, 42L, "url")

    companion object {
        private const val ENCRYPTED_MANIFEST = "encrypted"
    }
}
