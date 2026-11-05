package app.logdate.feature.core.settings.ui

import app.logdate.client.datastore.UserSession
import app.logdate.shared.model.CloudStorageQuota
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DataSettingsViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun quotaIsNotAuthoritativeBeforeTheFirstValue() =
        runTest {
            val fixture = DataSettingsFixture()
            val viewModel = fixture.create()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(true, viewModel.uiState.value.isAuthenticated)
            assertFalse(viewModel.uiState.value.hasAuthoritativeQuota)
            fixture.quota.emit(CloudStorageQuota(100, 25, emptyList()))
            runCurrent()
            assertTrue(viewModel.uiState.value.hasAuthoritativeQuota)
        }

    @Test
    fun failedQuotaDoesNotHideTheAccountOrArchive() =
        runTest {
            val fixture = DataSettingsFixture(quotaFlow = flow { throw IllegalStateException("private quota detail") })
            val viewModel = fixture.create()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(true, viewModel.uiState.value.isAuthenticated)
            assertEquals(CloudArchivePhase.COMPLETE, viewModel.uiState.value.cloudArchiveStatus.phase)
            assertFalse(viewModel.uiState.value.hasAuthoritativeQuota)
        }

    @Test
    fun repeatedSyncRequestsShareOneSchedulerRequest() =
        runTest {
            val fixture = DataSettingsFixture()
            val viewModel = fixture.create()
            viewModel.syncNow()
            viewModel.syncNow()
            runCurrent()

            assertEquals(1, fixture.syncRequests)
            fixture.syncAccepted.complete(Unit)
            runCurrent()
            assertEquals(SyncFeedback.Started, viewModel.syncFeedback.value)
        }

    @Test
    fun archiveRetryCoalescesWhileTheSchedulerResponds() =
        runTest {
            val fixture = DataSettingsFixture(archivePhase = CloudArchivePhase.FAILED)
            val viewModel = fixture.create()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            runCurrent()
            viewModel.backupArchiveNow()
            viewModel.backupArchiveNow()
            runCurrent()

            assertEquals(1, fixture.archiveRequests)
            fixture.archiveAccepted.complete(Unit)
        }

    @Test
    fun integrityCancellationKeepsThePriorStablePresentation() =
        runTest {
            val fixture = DataSettingsFixture()
            fixture.ownerFailure = CancellationException("ordinary cancellation")
            val viewModel = fixture.create()
            viewModel.runIntegrityCheck()
            runCurrent()

            assertFalse(viewModel.integrityState.value.isChecking)
            assertIs<IntegrityOperation.Idle>(viewModel.integrityState.value.operation)
        }

    @Test
    fun returningAfterCollectionStopsDoesNotRestartAccountLoading() =
        runTest {
            val fixture = DataSettingsFixture()
            val viewModel = fixture.create()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            runCurrent()
            collector.cancel()
            advanceTimeBy(6000)
            runCurrent()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(1, fixture.sessionObservations)
            assertEquals(true, viewModel.uiState.value.isAuthenticated)
        }

    @Test
    fun feedbackWaitsForAcknowledgementAndAnOldAcknowledgementCannotEraseANewerResult() =
        runTest {
            val fixture = DataSettingsFixture()
            val viewModel = fixture.create()
            fixture.syncAccepted.complete(Unit)
            viewModel.syncNow()
            runCurrent()
            val first = requireNotNull(viewModel.syncFeedbackDelivery.value)
            viewModel.syncNow()
            runCurrent()
            assertEquals(first, viewModel.syncFeedbackDelivery.value)

            viewModel.acknowledgeSyncFeedback(first.id)
            runCurrent()
            val second = requireNotNull(viewModel.syncFeedbackDelivery.value)
            assertNotEquals(first.id, second.id)
            viewModel.acknowledgeSyncFeedback(first.id)
            runCurrent()
            assertEquals(second, viewModel.syncFeedbackDelivery.value)
            viewModel.acknowledgeSyncFeedback(second.id)
            runCurrent()
            assertNull(viewModel.syncFeedbackDelivery.value)
        }

    @Test
    fun retryClearsItsFailureAndCancellationDoesNotBecomeAnotherFailure() =
        runTest {
            val fixture = DataSettingsFixture()
            fixture.syncFailure = IllegalStateException("private scheduler detail")
            val viewModel = fixture.create()
            viewModel.syncNow()
            runCurrent()
            assertIs<SyncRequestState.Failed>(viewModel.uiState.value.syncRequest)

            fixture.syncFailure = CancellationException("ordinary cancellation")
            viewModel.syncNow()
            runCurrent()
            assertIs<SyncRequestState.Idle>(viewModel.uiState.value.syncRequest)
            assertEquals(SyncFeedback.Failed, viewModel.syncFeedbackDelivery.value?.feedback)
            viewModel.acknowledgeSyncFeedback(requireNotNull(viewModel.syncFeedbackDelivery.value).id)
            runCurrent()
            assertNull(viewModel.syncFeedbackDelivery.value)
        }

    @Test
    fun failedQuotaRefreshRetainsItsLastUsableValue() =
        runTest {
            val quota = CloudStorageQuota(100, 25, emptyList())
            val fixture =
                DataSettingsFixture(
                    quotaFlow =
                        flow {
                            emit(quota)
                            throw IllegalStateException("private quota detail")
                        },
                )
            val viewModel = fixture.create()
            runCurrent()

            assertEquals(quota, viewModel.uiState.value.quotaState)
            assertIs<QuotaAvailability.Unavailable>(viewModel.uiState.value.quotaAvailability)
            assertEquals(true, viewModel.uiState.value.isAuthenticated)
            assertEquals(CloudArchivePhase.COMPLETE, viewModel.uiState.value.cloudArchiveStatus.phase)
        }

    @Test
    fun archiveObserverFailureStaysInTheArchiveRegion() =
        runTest {
            val fixture = DataSettingsFixture(archiveFlow = flow { throw IllegalStateException("private archive detail") })
            val viewModel = fixture.create()
            fixture.quota.emit(CloudStorageQuota(100, 25, emptyList()))
            runCurrent()

            assertEquals(true, viewModel.uiState.value.isAuthenticated)
            assertEquals(CloudArchivePhase.UNAVAILABLE, viewModel.uiState.value.cloudArchiveStatus.phase)
            assertTrue(viewModel.uiState.value.hasAuthoritativeQuota)
        }

    @Test
    fun archiveRequestFailureIsLocalAndRetryClearsItImmediately() =
        runTest {
            val fixture = DataSettingsFixture(archivePhase = CloudArchivePhase.FAILED)
            fixture.archiveFailure = IllegalStateException("private scheduler detail")
            val viewModel = fixture.create()
            runCurrent()
            viewModel.backupArchiveNow()
            runCurrent()
            assertIs<ArchiveRequestState.Failed>(viewModel.uiState.value.archiveRequest)
            assertIs<SyncRequestState.Idle>(viewModel.uiState.value.syncRequest)

            fixture.archiveFailure = null
            viewModel.backupArchiveNow()
            assertIs<ArchiveRequestState.Requesting>(viewModel.uiState.value.archiveRequest)
            fixture.archiveAccepted.complete(Unit)
            runCurrent()
            assertIs<ArchiveRequestState.Accepted>(viewModel.uiState.value.archiveRequest)
        }

    @Test
    fun restoreCompletionRedeliveryDoesNotRestartAnAuditAndANewerRestoreQueuesOne() =
        runTest {
            val fixture = DataSettingsFixture()
            fixture.auditGate = CompletableDeferred()
            val viewModel = fixture.create()
            val first = Uuid.random()
            val second = Uuid.random()
            viewModel.onRestoreCompleted(first)
            viewModel.onRestoreCompleted(first)
            viewModel.onRestoreCompleted(second)
            viewModel.onRestoreCompleted(first)
            runCurrent()
            assertEquals(1, fixture.audits)

            fixture.auditGate?.complete(Unit)
            runCurrent()
            assertEquals(2, fixture.audits)
            viewModel.onRestoreCompleted(first)
            viewModel.onRestoreCompleted(second)
            runCurrent()
            assertEquals(2, fixture.audits)
        }

    @Test
    fun signingOutCancelsAnArchiveRequestWithoutPublishingItsOldResult() =
        runTest {
            val fixture = DataSettingsFixture(archivePhase = CloudArchivePhase.FAILED)
            val viewModel = fixture.create()
            viewModel.backupArchiveNow()
            runCurrent()
            assertIs<ArchiveRequestState.Requesting>(viewModel.uiState.value.archiveRequest)
            fixture.signOut()
            fixture.archiveAccepted.complete(Unit)
            runCurrent()

            assertEquals(false, viewModel.uiState.value.isAuthenticated)
            assertEquals(CloudArchivePhase.SIGNED_OUT, viewModel.uiState.value.cloudArchiveStatus.phase)
            assertIs<ArchiveRequestState.Idle>(viewModel.uiState.value.archiveRequest)
        }

    @Test
    fun signingOutCancelsASyncRequestWithoutDeliveringItsOldAccountFeedback() =
        runTest {
            val fixture = DataSettingsFixture()
            val viewModel = fixture.create()
            viewModel.syncNow()
            runCurrent()
            fixture.signOut()
            fixture.syncAccepted.complete(Unit)
            runCurrent()

            assertIs<SyncRequestState.Idle>(viewModel.uiState.value.syncRequest)
            assertNull(viewModel.syncFeedbackDelivery.value)
        }

    @Test
    fun sameAccountTokenRefreshPreservesItsArchiveRequest() =
        runTest {
            val fixture = DataSettingsFixture(archivePhase = CloudArchivePhase.FAILED)
            val viewModel = fixture.create()
            viewModel.backupArchiveNow()
            runCurrent()
            fixture.refreshToken()
            runCurrent()
            viewModel.backupArchiveNow()
            runCurrent()

            assertEquals(1, fixture.archiveRequests)
            assertIs<ArchiveRequestState.Requesting>(viewModel.uiState.value.archiveRequest)
            fixture.archiveAccepted.complete(Unit)
        }

    @Test
    fun syncWaitsForTheFirstAccountObservationBeforeReportingNeedsAccount() =
        runTest {
            val sessions = MutableSharedFlow<UserSession?>()
            val fixture = DataSettingsFixture(sessionFlowOverride = sessions, cachedSessionMissing = true)
            val viewModel = fixture.create()
            runCurrent()
            assertNull(viewModel.uiState.value.isAuthenticated)
            viewModel.syncNow()
            runCurrent()
            assertEquals(0, fixture.syncRequests)
            assertIs<SyncRequestState.Idle>(viewModel.uiState.value.syncRequest)
            assertNull(viewModel.syncFeedbackDelivery.value)

            sessions.emit(null)
            runCurrent()
            assertEquals(false, viewModel.uiState.value.isAuthenticated)
            viewModel.syncNow()
            runCurrent()
            assertEquals(SyncFeedback.NeedsAccount, viewModel.syncFeedbackDelivery.value?.feedback)
            assertEquals(0, fixture.syncRequests)
        }
}
