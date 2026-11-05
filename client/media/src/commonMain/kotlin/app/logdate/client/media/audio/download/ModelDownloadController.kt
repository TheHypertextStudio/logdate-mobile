package app.logdate.client.media.audio.download

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Owns file discovery and downloads without loading an inference engine. */
class ModelDownloadController(
    private val scope: CoroutineScope,
    private val isReady: suspend () -> Boolean,
    private val download: () -> Flow<ModelDownloadStatus>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val request: ModelDownloadRequest = InMemoryModelDownloadRequest(),
) {
    private val operationMutex = Mutex()
    private val mutableStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Checking)
    val status: StateFlow<ModelDownloadStatus> = mutableStatus.asStateFlow()

    init {
        checkAvailability()
    }

    fun checkAvailability() {
        scope.launch(dispatcher) {
            if (!operationMutex.tryLock()) return@launch
            try {
                if (mutableStatus.value == ModelDownloadStatus.Checking || mutableStatus.value == ModelDownloadStatus.CheckFailed) {
                    val pending = retryRequestAccess { request.isRequested() }
                    discoverUntilKnown(publishMissing = !pending)
                    if (mutableStatus.value == ModelDownloadStatus.Completed) {
                        clearRequest()
                    } else if (pending) {
                        downloadUntilSettled()
                    }
                }
            } finally {
                operationMutex.unlock()
            }
        }
    }

    fun startDownload() {
        scope.launch(dispatcher) {
            if (!operationMutex.tryLock()) return@launch
            try {
                if (mutableStatus.value != ModelDownloadStatus.Completed) {
                    retryRequestAccess { request.setRequested(true) }
                    downloadUntilSettled()
                }
            } catch (e: CancellationException) {
                mutableStatus.value = ModelDownloadStatus.Idle
                throw e
            } finally {
                operationMutex.unlock()
            }
        }
    }

    private suspend fun downloadUntilSettled() {
        mutableStatus.value = ModelDownloadStatus.Downloading(0, null)
        var retryDelay = INITIAL_RETRY_DELAY_MS
        var cleanRetries = 0
        while (true) {
            discoverUntilKnown(publishMissing = false)
            if (mutableStatus.value == ModelDownloadStatus.Completed) {
                clearRequest()
                return
            }
            downloadOnce()
            if (mutableStatus.value == ModelDownloadStatus.Completed) clearRequest()
            when (mutableStatus.value) {
                ModelDownloadStatus.AwaitingUpdate -> cleanRetries = 0
                ModelDownloadStatus.NoNetwork, ModelDownloadStatus.ServerUnavailable, ModelDownloadStatus.OutOfStorage -> Unit
                ModelDownloadStatus.ArchiveCorrupt, ModelDownloadStatus.UnknownError -> if (cleanRetries++ >= 1) return
                else -> return
            }
            delay(if (mutableStatus.value == ModelDownloadStatus.AwaitingUpdate) PACKAGE_UPDATE_CHECK_DELAY_MS else retryDelay)
            retryDelay = nextRetryDelay(retryDelay)
        }
    }

    private suspend fun downloadOnce() {
        var emitted = false
        try {
            download().collect {
                emitted = true
                mutableStatus.value = it
            }
            if (!emitted || (mutableStatus.value != ModelDownloadStatus.Completed && mutableStatus.value !is ModelDownloadStatus.Failure)) {
                mutableStatus.value = ModelDownloadStatus.UnknownError
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("On-device model download failed", e)
            mutableStatus.value = ModelDownloadStatus.UnknownError
        }
    }

    private suspend fun discoverUntilKnown(publishMissing: Boolean) {
        var retryDelay = INITIAL_RETRY_DELAY_MS
        while (!discover(publishMissing)) {
            delay(retryDelay)
            retryDelay = nextRetryDelay(retryDelay)
        }
    }

    private suspend fun discover(publishMissing: Boolean): Boolean {
        if (publishMissing) mutableStatus.value = ModelDownloadStatus.Checking
        return try {
            if (isReady()) {
                mutableStatus.value = ModelDownloadStatus.Completed
            } else if (publishMissing) {
                mutableStatus.value = ModelDownloadStatus.Idle
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Could not check on-device model files", e)
            mutableStatus.value = ModelDownloadStatus.CheckFailed
            false
        }
    }

    private suspend fun <T> retryRequestAccess(block: () -> T): T {
        var retryDelay = INITIAL_RETRY_DELAY_MS
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Could not access the pending model download request", e)
                mutableStatus.value = ModelDownloadStatus.CheckFailed
            }
            delay(retryDelay)
            retryDelay = nextRetryDelay(retryDelay)
        }
    }

    private fun clearRequest() {
        try {
            request.setRequested(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Could not clear completed model download request", e)
        }
    }

    private fun nextRetryDelay(previous: Long) = (previous * 2).coerceAtMost(MAX_RETRY_DELAY_MS)

    private companion object {
        const val INITIAL_RETRY_DELAY_MS = 5_000L
        const val PACKAGE_UPDATE_CHECK_DELAY_MS = 15L * 60 * 1_000
        const val MAX_RETRY_DELAY_MS = 300_000L
    }
}
