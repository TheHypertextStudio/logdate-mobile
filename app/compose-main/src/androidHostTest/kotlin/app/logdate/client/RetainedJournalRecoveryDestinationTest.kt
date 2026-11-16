package app.logdate.client

import app.logdate.feature.core.settings.navigation.DataSettingsRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetainedJournalRecoveryDestinationTest {
    @Test
    fun `a retained journal with pending recovery opens archive and account options`() {
        assertEquals(
            DataSettingsRoute,
            retainedJournalRecoveryDestination(
                hasRetainedJournal = true,
                pendingContentRecoveryId = "recovery-id",
            ),
        )
    }

    @Test
    fun `ordinary startup does not open recovery options`() {
        assertNull(
            retainedJournalRecoveryDestination(
                hasRetainedJournal = false,
                pendingContentRecoveryId = null,
            ),
        )
    }

    @Test
    fun `a recovery marker without retained ciphertext does not open recovery options`() {
        assertNull(
            retainedJournalRecoveryDestination(
                hasRetainedJournal = false,
                pendingContentRecoveryId = "recovery-id",
            ),
        )
    }

    @Test
    fun `retained ciphertext without a recovery marker does not change startup routing`() {
        assertNull(
            retainedJournalRecoveryDestination(
                hasRetainedJournal = true,
                pendingContentRecoveryId = null,
            ),
        )
    }
}
