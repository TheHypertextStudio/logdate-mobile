package app.logdate.client.launch

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal sealed interface ApplicationStartupState {
    data object Loading : ApplicationStartupState

    data object Ready : ApplicationStartupState

    data class Failed(
        val cause: Throwable,
    ) : ApplicationStartupState
}

/** Shares one off-main initialization attempt across activities and incoming watch work. */
internal class ApplicationStartup(
    private val scope: CoroutineScope,
    private val startDeviceWork: (suspend () -> Unit)? = null,
    private val initialize: suspend () -> Unit,
) {
    private val mutableState = MutableStateFlow<ApplicationStartupState>(ApplicationStartupState.Loading)
    val state: StateFlow<ApplicationStartupState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var retryAfter: Job? = null

    @Synchronized
    fun start() {
        if (state.value is ApplicationStartupState.Ready) return
        val currentJob = job
        if (currentJob?.isActive == true) {
            retryWhenFailedAttemptEnds(currentJob)
            return
        }
        launchAttempt { initialize() }
    }

    /** Retention is explicit and runs only after the failed initializer has released its handles. */
    @Synchronized
    fun recoverRetainedJournal(prepare: suspend () -> Unit) {
        if (state.value !is ApplicationStartupState.Failed) return
        val previous = job
        launchAttempt {
            previous?.join()
            prepare()
            initialize()
        }
    }

    suspend fun awaitReady(): Boolean = state.first { it !is ApplicationStartupState.Loading } is ApplicationStartupState.Ready

    /** An attempt can report failure before its job finishes; retry once that job has ended. */
    private fun retryWhenFailedAttemptEnds(currentJob: Job) {
        if (state.value !is ApplicationStartupState.Failed || retryAfter === currentJob) return
        retryAfter = currentJob
        mutableState.value = ApplicationStartupState.Loading
        currentJob.invokeOnCompletion { retryWhenFinished(currentJob) }
    }

    @Synchronized
    private fun retryWhenFinished(previous: Job) {
        if (retryAfter !== previous) return
        retryAfter = null
        if (job === previous && state.value !is ApplicationStartupState.Ready) start()
    }

    private fun launchAttempt(open: suspend () -> Unit) {
        mutableState.value = ApplicationStartupState.Loading
        val attempt =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    open()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Application storage initialization failed", error, tag = APP_STARTUP_TAG)
                    mutableState.value = ApplicationStartupState.Failed(error)
                    return@launch
                }
                mutableState.value = ApplicationStartupState.Ready
                launchDeviceWork()
            }
        job = attempt
        attempt.start()
    }

    /**
     * Device observers own installation work. Their dependency graph must not hold validated local
     * content behind optional service startup, so a failure here leaves the journal open.
     */
    private fun launchDeviceWork() {
        val start = startDeviceWork ?: return
        scope.launch {
            try {
                start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.w("Device-owned startup work could not start; journal access remains available")
            }
        }
    }
}

/**
 * Suspends until [dependenciesStarted] is true. Startup starts Koin on the main thread before it opens
 * the journal, so code that needs Koin right after [ApplicationStartup.start] waits here. If this
 * attempt fails before Koin starts, throws with the failure as the cause instead of waiting forever.
 */
internal suspend fun ApplicationStartup.awaitDependencies(dependenciesStarted: StateFlow<Boolean>) {
    val (started, current) =
        combine(dependenciesStarted, state) { started, current -> started to current }
            .first { (started, current) -> started || current is ApplicationStartupState.Failed }
    if (!started) {
        throw IllegalStateException(
            "LogDate startup failed before Koin started",
            (current as ApplicationStartupState.Failed).cause,
        )
    }
}

/** Keeps a native restore completion token pending until startup reaches a terminal state. */
internal fun observeNativeRestoreDeliveryCompletions(
    scope: CoroutineScope,
    completions: StateFlow<Long>,
    startup: ApplicationStartup,
    reconcile: suspend () -> Unit,
): Job =
    scope.launch {
        var lastCompletion = 0L
        var completionPending = false
        combine(completions, startup.state) { count, state -> count to state }
            .collect { (count, state) ->
                if (count > lastCompletion) {
                    lastCompletion = count
                    completionPending = true
                }
                if (!completionPending || state == ApplicationStartupState.Loading) return@collect
                completionPending = false
                when (state) {
                    ApplicationStartupState.Ready -> {
                        reconcile()
                    }

                    is ApplicationStartupState.Failed -> {
                        if (applicationStartupFailureMessage(state.cause) in
                            setOf(
                                ApplicationStartupFailureMessage.DATABASE_KEY_RECOVERY,
                                ApplicationStartupFailureMessage.PRIVACY_METADATA_RECOVERY,
                                ApplicationStartupFailureMessage.NATIVE_RESTORE_PENDING,
                            )
                        ) {
                            startup.start()
                        }
                    }

                    ApplicationStartupState.Loading -> {}
                }
            }
    }
