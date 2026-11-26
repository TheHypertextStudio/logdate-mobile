package app.logdate.feature.core.settings.ui.watch.v2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface WatchDiagnosticFeedback {
    data class Sent(
        val fix: WatchDiagnosticFix,
    ) : WatchDiagnosticFeedback

    data class Failed(
        val reason: WatchRequestFailure? = null,
    ) : WatchDiagnosticFeedback
}

sealed interface WatchDiagnosticsState {
    data object Checking : WatchDiagnosticsState

    data object NeedsWatch : WatchDiagnosticsState

    data class Ready(
        val steps: List<WatchDiagnosticStep>,
        val watchName: String? = null,
        val watchVersion: String? = null,
        val phoneVersion: String? = null,
        val feedback: WatchDiagnosticFeedback? = null,
    ) : WatchDiagnosticsState

    data object Failed : WatchDiagnosticsState
}

class WatchDiagnosticsViewModel(
    private val access: WatchDiagnosticsAccess,
    private val manager: WatchConnectionManager,
    private val postFixRecheckDelayMs: Long = 800L,
) : ViewModel() {
    private val mutableState = MutableStateFlow<WatchDiagnosticsState>(WatchDiagnosticsState.Checking)
    val state = mutableState.asStateFlow()
    private var targetNode: String? = null
    private var lastConnection: WatchConnectionState? = null
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    init {
        check()
    }

    fun check() =
        runAction(feedbackOnSuccess = null) {
            // Re-probe fresh transport state on entry or user refresh.
            null
        }

    fun reportLocalAction(
        fix: WatchDiagnosticFix,
        succeeded: Boolean,
    ) {
        val current = mutableState.value as? WatchDiagnosticsState.Ready ?: return
        mutableState.value =
            current.copy(
                feedback =
                    if (succeeded) {
                        WatchDiagnosticFeedback.Sent(fix)
                    } else {
                        WatchDiagnosticFeedback.Failed()
                    },
            )
    }

    fun fix(fix: WatchDiagnosticFix) {
        val node = targetNode?.takeIf { it.isNotBlank() } ?: return
        if (mutableState.value !is WatchDiagnosticsState.Ready) return
        if (fix == WatchDiagnosticFix.UPDATE_PHONE) return
        runAction(feedbackOnSuccess = fix, recheckDelayMs = postFixRecheckDelayMs) {
            when (fix) {
                WatchDiagnosticFix.PAIR -> {
                    manager.beginAssociation()
                    null
                }

                WatchDiagnosticFix.INSTALL -> {
                    val result =
                        if (lastConnection is WatchConnectionState.AppUnknown) {
                            manager.confirmMissingAppAndInstall(node)
                        } else {
                            manager.installAppOnWatch(node)
                        }
                    (result as? WatchRequestResult.Failed)?.reason
                }

                WatchDiagnosticFix.UPDATE_WATCH -> {
                    val result = manager.updateAppOnWatch(node)
                    (result as? WatchRequestResult.Failed)?.reason
                }

                WatchDiagnosticFix.UPDATE_PHONE -> {
                    null
                }

                WatchDiagnosticFix.OPEN_WATCH -> {
                    val result = manager.openAppOnWatch(node)
                    (result as? WatchRequestResult.Failed)?.reason
                }

                WatchDiagnosticFix.SYNC -> {
                    manager.requestSync(node)
                    null
                }
            }
        }
    }

    private fun runAction(
        feedbackOnSuccess: WatchDiagnosticFix?,
        recheckDelayMs: Long = 0L,
        action: suspend () -> WatchRequestFailure?,
    ) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            val hadResult = mutableState.value is WatchDiagnosticsState.Ready
            if (!hadResult) {
                mutableState.value = WatchDiagnosticsState.Checking
            }
            var actionFeedback: WatchDiagnosticFeedback? = null
            try {
                if (feedbackOnSuccess != null) {
                    val failureReason =
                        try {
                            action()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Napier.w("Watch diagnostic repair action $feedbackOnSuccess failed", error)
                            actionFeedback = WatchDiagnosticFeedback.Failed()
                            null
                        }
                    if (failureReason != null) {
                        actionFeedback = WatchDiagnosticFeedback.Failed(failureReason)
                    } else if (actionFeedback == null) {
                        actionFeedback = WatchDiagnosticFeedback.Sent(feedbackOnSuccess)
                    }
                    if (recheckDelayMs > 0L) delay(recheckDelayMs)
                }
                val snapshot = checkNotNull(withTimeoutOrNull(15_000) { access.inspect() })
                lastConnection = snapshot.connection
                targetNode = snapshot.connection.nodeId
                mutableState.value =
                    if (targetNode.isNullOrBlank()) {
                        WatchDiagnosticsState.NeedsWatch
                    } else {
                        WatchDiagnosticsState.Ready(
                            steps = WatchDiagnostics.evaluate(snapshot),
                            watchName = snapshot.watchName ?: snapshot.connection.displayWatchName(),
                            watchVersion = snapshot.watchVersion,
                            phoneVersion = snapshot.phoneVersion,
                            feedback = actionFeedback,
                        )
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Napier.e("Could not diagnose watch connection", failure)
                val current = mutableState.value
                mutableState.value =
                    if (current is WatchDiagnosticsState.Ready) {
                        current.copy(feedback = actionFeedback ?: WatchDiagnosticFeedback.Failed())
                    } else {
                        WatchDiagnosticsState.Failed
                    }
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
