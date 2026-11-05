package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.diagnostics.DiagnosticReportingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticDiagnosticReportingViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun initialLoadingNeverInventsAnOffPreference() =
        runTest {
            val actions = Actions()
            val ready = CompletableDeferred<Unit>()
            actions.refreshAction = { ready.await() }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            assertIs<ReportingAvailability.Loading>(model.state.value.availability)
            assertNull(model.state.value.settings)
            model.setEnabled(true)
            model.requestDelete()
            assertEquals(0, actions.preparations)
            ready.complete(Unit)
            assertEquals(
                true,
                model.state.value.settings
                    ?.enabled,
            )
        }

    @Test fun consentAndEnableAreGuardedUntilThePersonConfirms() =
        runTest {
            val actions = Actions(enabled = false)
            val preparing = CompletableDeferred<DiagnosticReportingApproval?>()
            actions.prepareAction = { preparing.await() }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(true)
            model.setEnabled(true)
            assertEquals(1, actions.preparations)
            assertIs<ReportingToggleOperation.Preparing>(model.state.value.toggle)
            assertEquals(0, actions.enables)
            preparing.complete(Approval())
            assertIs<ReportingToggleOperation.AwaitingConsent>(model.state.value.toggle)
            val saving = CompletableDeferred<Boolean>()
            actions.enableAction = { saving.await() }
            model.confirmToggle()
            model.confirmToggle()
            assertEquals(1, actions.enables)
            assertIs<ReportingToggleOperation.Saving>(model.state.value.toggle)
            saving.complete(true)
            assertIs<ReportingToggleOperation.Idle>(model.state.value.toggle)
        }

    @Test fun failedDisableRetainsTheAuthoritativeValueAndRetryClearsOnlyItsFailure() =
        runTest {
            val actions = Actions()
            actions.disableAction = { false }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(false)
            assertEquals(ReportingToggleOperation.Failed(false, ReportingToggleFailure.DISABLE), model.state.value.toggle)
            assertEquals(
                true,
                model.state.value.settings
                    ?.enabled,
            )
            val pending = CompletableDeferred<Boolean>()
            actions.disableAction = { pending.await() }
            model.retryToggle()
            assertIs<ReportingToggleOperation.Saving>(model.state.value.toggle)
            pending.complete(true)
            assertEquals(2, actions.disables)
            assertIs<ReportingToggleOperation.Idle>(model.state.value.toggle)
        }

    @Test fun cancellationOfToggleWorkIsQuiet() =
        runTest {
            val actions = Actions()
            actions.disableAction = { throw CancellationException("ordinary cancellation") }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(false)
            assertIs<ReportingToggleOperation.Idle>(model.state.value.toggle)
            assertEquals(
                true,
                model.state.value.settings
                    ?.enabled,
            )
        }

    @Test fun dismissingConsentDoesNotPerformOrPersistAnything() =
        runTest {
            val actions = Actions(enabled = false)
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(true)
            model.cancelToggleConsent()
            model.confirmToggle()
            assertEquals(0, actions.enables)
            assertIs<ReportingToggleOperation.Idle>(model.state.value.toggle)
            model.requestDelete()
            model.cancelDeleteConsent()
            model.confirmDelete()
            assertEquals(0, actions.deletes)
            assertIs<ReportingDeleteOperation.Idle>(model.state.value.deletion)
        }

    @Test fun deleteFailureAndToggleFailureStayOnTheirOwnActions() =
        runTest {
            val actions = Actions()
            actions.disableAction = { false }
            actions.deleteAction = { false }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(false)
            val toggleFailure = model.state.value.toggle
            model.requestDelete()
            model.confirmDelete()
            assertEquals(toggleFailure, model.state.value.toggle)
            assertEquals(ReportingDeleteOperation.Failed(ReportingDeleteFailure.DELETE), model.state.value.deletion)
            model.retryDelete()
            assertIs<ReportingDeleteOperation.AwaitingConsent>(model.state.value.deletion)
            assertEquals(toggleFailure, model.state.value.toggle)
        }

    @Test fun duplicateDeleteRequestsAreCoalescedAndSuccessSurvivesUnrelatedToggleWork() =
        runTest {
            val actions = Actions()
            val deleting = CompletableDeferred<Boolean>()
            actions.deleteAction = { deleting.await() }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.requestDelete()
            model.confirmDelete()
            model.requestDelete()
            model.confirmDelete()
            assertEquals(1, actions.deletes)
            assertIs<ReportingDeleteOperation.Deleting>(model.state.value.deletion)
            deleting.complete(true)
            assertIs<ReportingDeleteOperation.Deleted>(model.state.value.deletion)
            actions.disableAction = { false }
            model.setEnabled(false)
            assertIs<ReportingDeleteOperation.Deleted>(model.state.value.deletion)
        }

    @Test fun cancellationOfDeleteWorkDoesNotClaimFailureOrSuccess() =
        runTest {
            val actions = Actions()
            actions.deleteAction = { throw CancellationException("ordinary cancellation") }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.requestDelete()
            model.confirmDelete()
            assertIs<ReportingDeleteOperation.Idle>(model.state.value.deletion)
        }

    @Test fun operationAndResultDoNotDependOnAUiCollectorRemainingAttached() =
        runTest {
            val actions = Actions()
            val pending = CompletableDeferred<Boolean>()
            actions.deleteAction = { pending.await() }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            model.requestDelete()
            model.confirmDelete()
            collector.cancel()
            pending.complete(true)
            runCurrent()
            assertIs<ReportingDeleteOperation.Deleted>(model.state.value.deletion)
        }

    @Test fun unavailableReadsRetainLoadedValuesAndExposeAnExplicitRetry() =
        runTest {
            val actions = Actions()
            val model = AutomaticDiagnosticReportingViewModel(actions)
            actions.refreshAction = { throw IllegalStateException("private storage detail") }
            model.refresh()
            assertIs<ReportingAvailability.Unavailable>(model.state.value.availability)
            assertEquals(
                true,
                model.state.value.settings
                    ?.enabled,
            )
            actions.refreshAction = {}
            model.refresh()
            assertIs<ReportingAvailability.Available>(model.state.value.availability)
        }

    @Test fun aLateConsentCannotOpenAConfirmationForANewDestination() =
        runTest {
            val actions = Actions(enabled = false)
            val pending = CompletableDeferred<DiagnosticReportingApproval?>()
            actions.prepareAction = { withContext(NonCancellable) { pending.await() } }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(true)
            actions.settings.value = actions.settings.value.copy(destination = "https://other.invalid")
            pending.complete(Approval())
            runCurrent()
            assertIs<ReportingToggleOperation.Idle>(model.state.value.toggle)
            assertEquals(
                "https://other.invalid",
                model.state.value.settings
                    ?.destination,
            )
            assertEquals(0, actions.enables)
        }

    @Test fun consentPreparationAndEnableFailuresHaveDistinctRetryableStates() =
        runTest {
            val actions = Actions(enabled = false)
            actions.prepareAction = { null }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(true)
            assertEquals(ReportingToggleOperation.Failed(true, ReportingToggleFailure.PREPARE), model.state.value.toggle)
            assertEquals(0, actions.enables)
            actions.prepareAction = { Approval() }
            model.retryToggle()
            assertIs<ReportingToggleOperation.AwaitingConsent>(model.state.value.toggle)
            actions.enableAction = { false }
            model.confirmToggle()
            assertEquals(ReportingToggleOperation.Failed(true, ReportingToggleFailure.ENABLE), model.state.value.toggle)
            assertEquals(
                false,
                model.state.value.settings
                    ?.enabled,
            )
        }

    @Test fun deletionPreparationFailureCannotCallDeleteAndRetryRequiresFreshConfirmation() =
        runTest {
            val actions = Actions()
            actions.prepareAction = { null }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.requestDelete()
            assertEquals(ReportingDeleteOperation.Failed(ReportingDeleteFailure.PREPARE), model.state.value.deletion)
            assertEquals(0, actions.deletes)
            actions.prepareAction = { Approval() }
            model.retryDelete()
            assertIs<ReportingDeleteOperation.AwaitingConsent>(model.state.value.deletion)
            assertEquals(0, actions.deletes)
        }

    @Test fun storageFailureDuringAnOwnedWriteStaysOnThatAction() =
        runTest {
            val actions = Actions()
            actions.disableAction = {
                actions.settings.value = actions.settings.value.copy(storageFailed = true)
                false
            }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(false)
            assertEquals(ReportingToggleOperation.Failed(false, ReportingToggleFailure.DISABLE), model.state.value.toggle)
            assertIs<ReportingAvailability.Available>(model.state.value.availability)
            assertEquals(
                true,
                model.state.value.settings
                    ?.enabled,
            )
        }

    @Test fun aQueuedOwnedFailureStaysLocalAndAnExplicitRefreshCanStillFail() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val actions = Actions()
            actions.disableAction = {
                // The controller publishes before returning; its observer can run after completion.
                actions.settings.value = actions.settings.value.copy(storageFailed = true)
                false
            }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            runCurrent()
            model.setEnabled(false)
            runCurrent()
            assertEquals(ReportingToggleOperation.Failed(false, ReportingToggleFailure.DISABLE), model.state.value.toggle)
            assertIs<ReportingAvailability.Available>(model.state.value.availability)
            assertEquals(
                true,
                model.state.value.settings
                    ?.enabled,
            )

            // refresh() returns normally, but the service still cannot confirm its stored settings.
            model.refresh()
            runCurrent()
            assertIs<ReportingAvailability.Unavailable>(model.state.value.availability)
            assertEquals(ReportingToggleOperation.Failed(false, ReportingToggleFailure.DISABLE), model.state.value.toggle)
            assertEquals(
                true,
                model.state.value.settings
                    ?.enabled,
            )
        }

    @Test fun anUnrelatedPassiveStorageFailureIsNotHiddenByAnOlderActionFailure() =
        runTest {
            val actions = Actions()
            actions.disableAction = { false }
            val model = AutomaticDiagnosticReportingViewModel(actions)
            model.setEnabled(false)
            assertIs<ReportingAvailability.Available>(model.state.value.availability)
            actions.settings.value = actions.settings.value.copy(storageFailed = true)
            runCurrent()
            assertIs<ReportingAvailability.Unavailable>(model.state.value.availability)
            assertEquals(ReportingToggleOperation.Failed(false, ReportingToggleFailure.DISABLE), model.state.value.toggle)
        }

    @Test fun anUnconfirmedStorageSnapshotCannotInventAnOffPreference() =
        runTest {
            val actions = Actions(enabled = false)
            actions.settings.value = actions.settings.value.copy(storageFailed = true)
            val model = AutomaticDiagnosticReportingViewModel(actions)
            assertIs<ReportingAvailability.Unavailable>(model.state.value.availability)
            assertNull(model.state.value.settings)
            model.setEnabled(true)
            assertEquals(0, actions.preparations)
        }

    private class Approval(
        override val destination: String = "https://fixture.invalid",
    ) : DiagnosticReportingApproval

    private class Actions(
        enabled: Boolean = true,
    ) : DiagnosticReportingActions {
        override val settings = MutableStateFlow(DiagnosticReportingSettings("https://fixture.invalid", true, enabled))
        var refreshAction: suspend () -> Unit = {}
        var prepareAction: suspend () -> DiagnosticReportingApproval? = { Approval() }
        var enableAction: suspend () -> Boolean = { true }
        var disableAction: suspend () -> Boolean = { true }
        var deleteAction: suspend () -> Boolean = { true }
        var preparations = 0
        var enables = 0
        var disables = 0
        var deletes = 0

        override suspend fun refresh() = refreshAction()

        override suspend fun prepareConsent(): DiagnosticReportingApproval? {
            preparations++
            return prepareAction()
        }

        override suspend fun enable(approval: DiagnosticReportingApproval): Boolean {
            enables++
            val accepted = enableAction()
            if (accepted) settings.value = settings.value.copy(enabled = true)
            return accepted
        }

        override suspend fun disable(): Boolean {
            disables++
            val accepted = disableAction()
            if (accepted) settings.value = settings.value.copy(enabled = false)
            return accepted
        }

        override suspend fun delete(approval: DiagnosticReportingApproval): Boolean {
            deletes++
            return deleteAction()
        }
    }
}
