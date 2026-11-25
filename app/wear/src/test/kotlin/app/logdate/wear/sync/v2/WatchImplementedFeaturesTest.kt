package app.logdate.wear.sync.v2

import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.wear.di.watchSyncFeatures
import kotlin.test.Test
import kotlin.test.assertTrue

class WatchImplementedFeaturesTest {
    @Test
    fun `production watch advertises marked moments and operation receipts`() {
        assertTrue(WearFeatures.MOMENTS in watchSyncFeatures)
        assertTrue(WearFeatures.OPERATION_RECEIPTS in watchSyncFeatures)
    }

    @Test
    fun `the watch advertises reminders and activity triggers`() {
        assertTrue(WearFeatures.ACTIVITY_REMINDERS in watchSyncFeatures)
        assertTrue(WearFeatures.REMINDERS in watchSyncFeatures)
    }
}
