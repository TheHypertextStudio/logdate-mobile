package app.logdate.feature.core.export

import app.logdate.feature.core.settings.ui.ArchiveWorkState
import app.logdate.feature.core.settings.ui.CloudArchiveProgress
import app.logdate.feature.core.settings.ui.CloudArchiveStage
import app.logdate.feature.core.settings.ui.resolveCloudArchiveStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudArchiveCacheIdentityTest {
    private val identity = CloudArchiveCacheIdentity("https://cloud.example", "account", "identity", "all-v2", "source")

    @Test
    fun `current export progress reaches archive status`() {
        val progress = CloudArchiveProgress(CloudArchiveStage.EXPORTING, 0.5f)
        assertEquals(progress, resolveCloudArchiveStatus(true, ArchiveWorkState.RUNNING, null, false, progress).progress)
    }

    @Test
    fun `a complete unfinished archive is reusable for matching content and scope`() {
        assertTrue(reusableCloudArchive(identity, identity, "digest", "digest", true))
    }

    @Test
    fun `scope identity options and source must all match`() {
        listOf(
            identity.copy(origin = "https://other.example"),
            identity.copy(accountId = "other"),
            identity.copy(identityFingerprint = "other"),
            identity.copy(options = "other"),
            identity.copy(sourceFingerprint = "other"),
        ).forEach { changed -> assertFalse(reusableCloudArchive(identity, changed, "digest", "digest", true)) }
    }

    @Test
    fun `corrupt or partial files are never reused`() {
        assertFalse(reusableCloudArchive(identity, identity, "digest", "changed", true))
        assertFalse(reusableCloudArchive(identity, identity, "digest", "digest", false))
    }
}
