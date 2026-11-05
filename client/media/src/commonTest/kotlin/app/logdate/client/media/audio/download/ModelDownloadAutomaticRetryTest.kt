package app.logdate.client.media.audio.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadAutomaticRetryTest {
    @Test
    fun successivePackageRevisionsCanReachReadyWithoutReopeningTheApp() =
        runTest {
            val responses =
                listOf(
                    ModelDownloadStatus.ArchiveCorrupt,
                    ModelDownloadStatus.AwaitingUpdate,
                    ModelDownloadStatus.ArchiveCorrupt,
                    ModelDownloadStatus.AwaitingUpdate,
                    ModelDownloadStatus.Completed,
                )
            var attempts = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { false },
                    { flowOf(responses[attempts++]) },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.AwaitingUpdate, controller.status.value)
            advanceTimeBy(15 * 60 * 1_000)
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.AwaitingUpdate, controller.status.value)
            advanceTimeBy(15 * 60 * 1_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(5, attempts)
        }

    @Test
    fun requestedDownloadResumesAfterProcessScopeIsRecreated() =
        runTest {
            val request = InMemoryModelDownloadRequest()
            var downloads = 0
            val firstScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
            val first =
                ModelDownloadController(
                    firstScope,
                    { false },
                    {
                        downloads++
                        flowOf(ModelDownloadStatus.NoNetwork)
                    },
                    StandardTestDispatcher(testScheduler),
                    request,
                )
            runCurrent()
            first.startDownload()
            runCurrent()
            assertEquals(true, request.isRequested())
            firstScope.cancel()
            runCurrent()
            val resumed =
                ModelDownloadController(
                    backgroundScope,
                    { false },
                    {
                        downloads++
                        flowOf(ModelDownloadStatus.Completed)
                    },
                    StandardTestDispatcher(testScheduler),
                    request,
                )
            runCurrent()
            assertEquals(2, downloads)
            assertEquals(ModelDownloadStatus.Completed, resumed.status.value)
            assertEquals(false, request.isRequested())
        }

    @Test
    fun retryFileProbesDoNotPublishAFalseMissingOrCheckingState() =
        runTest {
            val probe = CompletableDeferred<Boolean>()
            var checks = 0
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    isReady = { if (++checks >= 3) probe.await() else false },
                    download = { flowOf(if (++downloads == 1) ModelDownloadStatus.NoNetwork else ModelDownloadStatus.Completed) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            val states = mutableListOf<ModelDownloadStatus>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.status.collect { states += it } }
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.NoNetwork, controller.status.value)
            probe.complete(false)
            runCurrent()
            assertEquals(false, states.contains(ModelDownloadStatus.Idle))
            assertEquals(false, states.contains(ModelDownloadStatus.Checking))
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
        }

    @Test
    fun unavailableFilesAreCheckedAgainWithoutUserAction() =
        runTest {
            var checks = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    isReady = { if (++checks == 1) error("Storage temporarily unavailable") else true },
                    download = { error("Discovery must not download anything") },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            assertEquals(ModelDownloadStatus.CheckFailed, controller.status.value)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(2, checks)
        }

    @Test
    fun interruptedDownloadsContinueWithoutAnotherDownloadAction() =
        runTest {
            for (failure in listOf(
                ModelDownloadStatus.NoNetwork,
                ModelDownloadStatus.ServerUnavailable,
                ModelDownloadStatus.OutOfStorage,
            )) {
                var downloads = 0
                val controller =
                    ModelDownloadController(
                        backgroundScope,
                        isReady = { false },
                        download = { flowOf(if (++downloads == 1) failure else ModelDownloadStatus.Completed) },
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                controller.startDownload()
                runCurrent()
                assertEquals(failure, controller.status.value)
                advanceTimeBy(4_999)
                runCurrent()
                assertEquals(1, downloads)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(ModelDownloadStatus.Completed, controller.status.value)
                assertEquals(2, downloads)
            }
        }

    @Test
    fun retriesBackOffAndKeepOnlyOneDownloadOperation() =
        runTest {
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    isReady = { false },
                    download = {
                        downloads++
                        flowOf(ModelDownloadStatus.NoNetwork)
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            controller.startDownload()
            controller.checkAvailability()
            runCurrent()
            assertEquals(1, downloads)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(2, downloads)
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(2, downloads)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, downloads)
        }

    @Test
    fun readyFilesAreReusedBeforeRetryingATransfer() =
        runTest {
            var ready = false
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    isReady = { ready },
                    download = {
                        downloads++
                        flowOf(ModelDownloadStatus.ServerUnavailable)
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            ready = true
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(1, downloads)
        }

    @Test
    fun invalidDownloadsAreRetriedOnceWithoutAnUnlimitedLargeTransferLoop() =
        runTest {
            for (failure in listOf(ModelDownloadStatus.ArchiveCorrupt, ModelDownloadStatus.UnknownError)) {
                var downloads = 0
                val controller =
                    ModelDownloadController(
                        backgroundScope,
                        isReady = { false },
                        download = {
                            downloads++
                            flowOf(failure)
                        },
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                runCurrent()
                controller.startDownload()
                runCurrent()
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(2, downloads)
                assertEquals(failure, controller.status.value)
                advanceTimeBy(3_600_000)
                runCurrent()
                assertEquals(2, downloads)
            }
        }
}
