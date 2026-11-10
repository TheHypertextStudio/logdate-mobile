package app.logdate.client.sync

import app.logdate.client.sync.metadata.PendingOperation
import kotlin.test.Test
import kotlin.test.assertEquals

class DeletionDecisionTest {
    private fun decide(
        localExists: Boolean = true,
        pendingOperation: PendingOperation? = null,
        hasRelatedEdits: Boolean = false,
        localSyncVersion: Long = 1L,
        remoteVersion: Long? = 1L,
    ) = decideRemoteDeletion(
        DeletionFacts(localExists, pendingOperation, hasRelatedEdits, localSyncVersion, remoteVersion),
    )

    @Test
    fun deletesWhenNothingLocalDiffers() {
        assertEquals(DeletionDecision.DELETE, decide())
    }

    @Test
    fun deletesWhenLocalItemIsAlreadyGone() {
        assertEquals(DeletionDecision.DELETE, decide(localExists = false, pendingOperation = PendingOperation.UPDATE))
    }

    @Test
    fun preservesPendingCreateOrUpdate() {
        assertEquals(DeletionDecision.PRESERVE, decide(pendingOperation = PendingOperation.CREATE))
        assertEquals(DeletionDecision.PRESERVE, decide(pendingOperation = PendingOperation.UPDATE))
    }

    @Test
    fun deletesWhenLocalDeletionIsPending() {
        assertEquals(DeletionDecision.DELETE, decide(pendingOperation = PendingOperation.DELETE))
    }

    @Test
    fun preservesWhenMembershipEditsArePending() {
        assertEquals(DeletionDecision.PRESERVE, decide(hasRelatedEdits = true))
    }

    @Test
    fun preservesWhenLocalVersionIsNewerThanTheTombstone() {
        assertEquals(DeletionDecision.PRESERVE, decide(localSyncVersion = 5L, remoteVersion = 4L))
    }

    @Test
    fun deletesWhenLocalVersionIsNotNewer() {
        assertEquals(DeletionDecision.DELETE, decide(localSyncVersion = 4L, remoteVersion = 4L))
    }

    @Test
    fun withoutTombstoneVersionOnlyPendingWorkPreserves() {
        assertEquals(DeletionDecision.DELETE, decide(localSyncVersion = 99L, remoteVersion = null))
    }
}
