package app.logdate.client.feature.widgets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

internal enum class WidgetSetupSaveState { Idle, Saving, WaitingForStorage }

internal enum class WidgetSetupSaveResult { Saved, Retry, StorageFull, TargetRemoved }

/** Main-thread-owned submission state; a canceled disk write must finish before its replacement. */
internal class WidgetSetupSaveRecovery<T : Any>(
    private val scope: CoroutineScope,
    private val persist: suspend (T, canCommit: () -> Boolean) -> WidgetSetupSaveResult,
    private val onSaved: (T) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val onCanceled: (T) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(WidgetSetupSaveState.Idle)
    val state = mutableState.asStateFlow()

    private class Attempt<T>(
        val request: T,
    )

    private val writes = Mutex()
    private val authorized = AtomicReference<Attempt<T>?>(null)
    private var attempt: Attempt<T>? = null
    private var active = false
    private var job: Job? = null
    val pending: T? get() = attempt?.request

    fun submit(request: T) {
        attempt = Attempt(request)
        authorized.set(if (active) attempt else null)
        mutableState.value = WidgetSetupSaveState.Saving
        restart()
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        authorized.set(if (active) attempt else null)
        restart()
    }

    fun cancel() {
        authorized.set(null)
        attempt = null
        job?.cancel()
        mutableState.value = WidgetSetupSaveState.Idle
    }

    private fun restart() {
        job?.cancel()
        if (!active) return
        val owned = attempt ?: return
        job =
            scope.launch {
                var retryDelay = 1_000L
                while (authorized.get() === owned) {
                    val result =
                        try {
                            writes.withLock {
                                currentCoroutineContext().ensureActive()
                                persist(owned.request) { authorized.get() === owned }
                            }
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            onFailure(error)
                            WidgetSetupSaveResult.Retry
                        }
                    currentCoroutineContext().ensureActive()
                    if (authorized.get() !== owned) return@launch
                    if (result == WidgetSetupSaveResult.Saved || result == WidgetSetupSaveResult.TargetRemoved) {
                        authorized.set(null)
                        attempt = null
                        mutableState.value = WidgetSetupSaveState.Idle
                        if (result == WidgetSetupSaveResult.Saved) onSaved(owned.request) else onCanceled(owned.request)
                        return@launch
                    }
                    mutableState.value =
                        if (result ==
                            WidgetSetupSaveResult.StorageFull
                        ) {
                            WidgetSetupSaveState.WaitingForStorage
                        } else {
                            WidgetSetupSaveState.Saving
                        }
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000)
                }
            }
    }
}
