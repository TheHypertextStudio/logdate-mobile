package app.logdate.feature.core.settings.ui.watch.v2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface WatchDiagnosticsState {
    data object Checking : WatchDiagnosticsState

    data object NeedsWatch : WatchDiagnosticsState

    data class Ready(
        val steps: List<WatchDiagnosticStep>,
    ) : WatchDiagnosticsState

    data object Failed : WatchDiagnosticsState
}

class WatchDiagnosticsViewModel(
    private val access: WatchDiagnosticsAccess,
    private val manager: WatchConnectionManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow<WatchDiagnosticsState>(WatchDiagnosticsState.Checking)
    val state = mutableState.asStateFlow()
    private var targetNode: String? = null
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    init {
        check()
    }

    fun check() = runAction { }

    fun fix(fix: WatchDiagnosticFix) {
        if (targetNode.isNullOrBlank() || mutableState.value !is WatchDiagnosticsState.Ready) return
        runAction {
            when (fix) {
                WatchDiagnosticFix.PAIR -> {
                    manager.beginAssociation()
                }

                WatchDiagnosticFix.INSTALL, WatchDiagnosticFix.UPDATE -> {
                    kotlin.check(
                        manager.installAppOnWatch(checkNotNull(targetNode)) == WatchRequestResult.Sent,
                    )
                }

                WatchDiagnosticFix.OPEN_WATCH -> {
                    kotlin.check(manager.openAppOnWatch(checkNotNull(targetNode)) == WatchRequestResult.Sent)
                }

                WatchDiagnosticFix.SYNC -> {
                    manager.requestSync(checkNotNull(targetNode))
                }
            }
        }
    }

    private fun runAction(action: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            mutableState.value = WatchDiagnosticsState.Checking
            try {
                action()
                val snapshot = checkNotNull(withTimeoutOrNull(15_000) { access.inspect() })
                targetNode = snapshot.connection.nodeId
                mutableState.value =
                    if (targetNode.isNullOrBlank()) {
                        WatchDiagnosticsState.NeedsWatch
                    } else {
                        WatchDiagnosticsState.Ready(WatchDiagnostics.evaluate(snapshot))
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Napier.e("Could not diagnose watch connection", failure)
                mutableState.value = WatchDiagnosticsState.Failed
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
