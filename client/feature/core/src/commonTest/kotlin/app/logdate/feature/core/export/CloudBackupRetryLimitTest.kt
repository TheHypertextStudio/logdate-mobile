package app.logdate.feature.core.export

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudBackupRetryLimitTest {
    @Test
    fun `a backup retries until it reaches the attempt limit`() {
        assertTrue(cloudBackupShouldRetry(MAX_CLOUD_BACKUP_ATTEMPTS - 2))
    }

    @Test
    fun `a backup at the attempt limit stops retrying`() {
        assertFalse(cloudBackupShouldRetry(MAX_CLOUD_BACKUP_ATTEMPTS - 1))
        assertFalse(cloudBackupShouldRetry(MAX_CLOUD_BACKUP_ATTEMPTS))
    }
}
