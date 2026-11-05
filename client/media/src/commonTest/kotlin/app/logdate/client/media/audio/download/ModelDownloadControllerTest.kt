package app.logdate.client.media.audio.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadControllerTest {
    @Test
    fun waitingForResponseHeadersIsAlreadyAnActiveDownload() =
        runTest {
            val headers = CompletableDeferred<Unit>()
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { false },
                    {
                        flow {
                            headers.await()
                            emit(ModelDownloadStatus.Completed)
                        }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            assertEquals(ModelDownloadStatus.Downloading(0, null), controller.status.value)
            headers.complete(Unit)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
        }

    @Test
    fun installedFilesAreDiscoveredWithoutStartingADownload() =
        runTest {
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    isReady = { true },
                    download = {
                        downloads++
                        flowOf(ModelDownloadStatus.Completed)
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            assertEquals(ModelDownloadStatus.Checking, controller.status.value)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(0, downloads)
        }

    @Test
    fun delayedDiscoveryDoesNotClaimFilesAreMissing() =
        runTest {
            val ready = CompletableDeferred<Boolean>()
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { ready.await() },
                    { flowOf(ModelDownloadStatus.Completed) },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            assertEquals(ModelDownloadStatus.Checking, controller.status.value)
            ready.complete(false)
            runCurrent()
            assertEquals(ModelDownloadStatus.Idle, controller.status.value)
        }

    @Test
    fun failedDiscoveryRetriesAutomaticallyWithoutDownloading() =
        runTest {
            var fail = true
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    isReady = { if (fail) error("Files unavailable") else true },
                    download = {
                        downloads++
                        flowOf(ModelDownloadStatus.Completed)
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            assertEquals(ModelDownloadStatus.CheckFailed, controller.status.value)
            fail = false
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(0, downloads)
        }

    @Test
    fun discoveryRequestsCannotOverwriteAnActiveDownload() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val progress = ModelDownloadStatus.Downloading(25, 100)
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { false },
                    {
                        downloads++
                        flow {
                            emit(progress)
                            finish.await()
                            emit(ModelDownloadStatus.Completed)
                        }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            assertEquals(progress, controller.status.value)
            controller.checkAvailability()
            controller.startDownload()
            runCurrent()
            assertEquals(progress, controller.status.value)
            assertEquals(1, downloads)
            finish.complete(Unit)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
        }

    @Test
    fun downloadRequestsDuringDiscoveryDoNotStartAnotherOperation() =
        runTest {
            val ready = CompletableDeferred<Boolean>()
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { ready.await() },
                    {
                        downloads++
                        flowOf(ModelDownloadStatus.Completed)
                    },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            ready.complete(true)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(0, downloads)
        }

    @Test
    fun installedModelIsNotDownloadedAgain() =
        runTest {
            var downloads = 0
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { true },
                    {
                        downloads++
                        flowOf(ModelDownloadStatus.Completed)
                    },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(0, downloads)
        }

    @Test
    fun downloadFailureRetriesAutomaticallyAndInstallationIsPublished() =
        runTest {
            var downloads = 0
            val finish = CompletableDeferred<Unit>()
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { false },
                    {
                        downloads++
                        flow {
                            if (downloads == 1) {
                                emit(ModelDownloadStatus.NoNetwork)
                            } else {
                                emit(ModelDownloadStatus.Extracting)
                                finish.await()
                                emit(ModelDownloadStatus.Completed)
                            }
                        }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            assertEquals(ModelDownloadStatus.NoNetwork, controller.status.value)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(ModelDownloadStatus.Extracting, controller.status.value)
            finish.complete(Unit)
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, controller.status.value)
            assertEquals(2, downloads)
        }

    @Test
    fun cancellationIsNotReportedAsDownloadFailure() =
        runTest {
            val progress = ModelDownloadStatus.Downloading(1, null)
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { false },
                    {
                        flow {
                            emit(progress)
                            throw CancellationException("Stopped")
                        }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            assertEquals(ModelDownloadStatus.Idle, controller.status.value)
        }

    @Test
    fun thrownDownloadFailureDoesNotRemainInProgress() =
        runTest {
            val controller =
                ModelDownloadController(
                    backgroundScope,
                    { false },
                    {
                        flow {
                            emit(ModelDownloadStatus.Downloading(1, null))
                            error("Read failed")
                        }
                    },
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            controller.startDownload()
            runCurrent()
            assertEquals(ModelDownloadStatus.UnknownError, controller.status.value)
        }
}
