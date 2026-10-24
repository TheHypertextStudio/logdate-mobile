package app.logdate.client.sync.diagnostics

import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import app.logdate.shared.model.diagnostics.SyncDiagnosticReport
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A full diagnostic buffer never suspends, fails, or recursively logs through sync. */
class SyncDiagnosticRecorder(
    private val history: DiagnosticHistory,
    scope: CoroutineScope,
    private val verboseMode: VerboseDiagnosticMode? = null,
    private val onEvent: ((SyncDiagnosticEvent, UploadScope?) -> Unit)? = null,
    private val onScopedEvent: ((SyncDiagnosticEvent, DiagnosticSource?) -> Unit)? = null,
    private val context: () -> app.logdate.shared.model.diagnostics.DiagnosticContext? = { null },
) {
    private sealed interface Command {
        data class Event(
            val value: SyncDiagnosticEvent,
        ) : Command

        data class Report(
            val result: CompletableDeferred<SyncDiagnosticReport>,
        ) : Command

        data class Clear(
            val result: CompletableDeferred<Unit>,
        ) : Command
    }

    private val queue = Channel<Command>(128)
    private val dropped = MutableStateFlow(0)

    init {
        val worker =
            scope.launch {
                try {
                    maintainHistory()
                    while (true) {
                        val received = withTimeoutOrNull(60_000L) { queue.receiveCatching() }
                        if (received == null) {
                            maintainHistory()
                            continue
                        }
                        val command = received.getOrNull() ?: break
                        try {
                            val lost = dropped.getAndUpdate { 0 }
                            if (lost > 0 && command !is Command.Clear) history.recordDrops(lost)
                            when (command) {
                                is Command.Event -> {
                                    // This tag is excluded from third-party crash sinks.
                                    runCatching { Napier.i(tag = TAG, message = Json.encodeToString(command.value)) }
                                    history.append(command.value, verbose = verboseMode?.isEnabled() == true)
                                }

                                is Command.Report -> {
                                    command.result.complete(history.report())
                                }

                                is Command.Clear -> {
                                    history.clear()
                                    command.result.complete(Unit)
                                }
                            }
                        } catch (failure: Exception) {
                            when (command) {
                                is Command.Report -> command.result.completeExceptionally(failure)
                                is Command.Clear -> command.result.completeExceptionally(failure)
                                is Command.Event -> Unit
                            }
                            if (failure is CancellationException) throw failure
                            dropped.update { if (it == Int.MAX_VALUE) it else it + 1 }
                        }
                    }
                } finally {
                    closeQueue()
                }
            }
        // A cancelled parent may prevent the coroutine body (and its finally block) from starting.
        worker.invokeOnCompletion { closeQueue() }
    }

    private suspend fun maintainHistory() {
        try {
            history.maintain()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            dropped.update { if (it == Int.MAX_VALUE) it else it + 1 }
        }
    }

    private fun closeQueue() {
        queue.close()
        while (true) {
            val command = queue.tryReceive().getOrNull() ?: break
            val cancelled = CancellationException("Diagnostic recorder stopped")
            when (command) {
                is Command.Report -> command.result.completeExceptionally(cancelled)
                is Command.Clear -> command.result.completeExceptionally(cancelled)
                is Command.Event -> Unit
            }
        }
    }

    fun record(
        event: SyncDiagnosticEvent,
        scope: UploadScope? = null,
        epoch: String? = null,
    ): Boolean {
        if (runCatching { DiagnosticReportCodec.validateEvent(event) }.isFailure) return false
        val trustedContext = runCatching { context()?.also(DiagnosticReportCodec::validateContext) }.getOrNull()
        val safe =
            event.copy(
                code =
                    app.logdate.shared.model.diagnostics
                        .diagnosticCode(event),
                context =
                    (trustedContext ?: event.context)?.let { observed ->
                        observed.copy(
                            network =
                                when {
                                    event.reason == app.logdate.shared.model.diagnostics.DiagnosticReason.OFFLINE -> {
                                        app.logdate.shared.model.diagnostics.DiagnosticNetworkState.OFFLINE
                                    }

                                    event.httpStatus != null -> {
                                        app.logdate.shared.model.diagnostics.DiagnosticNetworkState.ONLINE
                                    }

                                    else -> {
                                        observed.network
                                    }
                                },
                            scheduler =
                                when (event.outcome) {
                                    app.logdate.shared.model.diagnostics.DiagnosticOutcome.STARTED -> {
                                        app.logdate.shared.model.diagnostics.DiagnosticSchedulerState.RUNNING
                                    }

                                    app.logdate.shared.model.diagnostics.DiagnosticOutcome.QUEUED,
                                    app.logdate.shared.model.diagnostics.DiagnosticOutcome.RETRY_SCHEDULED,
                                    -> {
                                        app.logdate.shared.model.diagnostics.DiagnosticSchedulerState.WAITING
                                    }

                                    else -> {
                                        observed.scheduler
                                    }
                                },
                        )
                    },
            )
        val accepted = queue.trySend(Command.Event(safe)).isSuccess
        if (accepted) {
            runCatching { onEvent?.invoke(safe, scope) }
            runCatching { onScopedEvent?.invoke(safe, scope?.let { DiagnosticSource(it, epoch) }) }
        }
        if (!accepted) dropped.update { if (it == Int.MAX_VALUE) it else it + 1 }
        return accepted
    }

    suspend fun report(): SyncDiagnosticReport {
        val result = CompletableDeferred<SyncDiagnosticReport>()
        queue.send(Command.Report(result))
        return result.await()
    }

    suspend fun clear() {
        val result = CompletableDeferred<Unit>()
        queue.send(Command.Clear(result))
        result.await()
    }

    companion object {
        const val TAG = "SyncDiagnostics"
    }
}
