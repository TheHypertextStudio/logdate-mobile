package app.logdate.feature.core.settings.account.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.device.auth.AtprotoConnectionBinding
import app.logdate.client.repository.account.AccountConnectionProblem
import app.logdate.client.repository.account.AccountConnectionState
import app.logdate.client.repository.account.AtprotoConnectionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AtprotoConnectionViewModel(
    private val repository: AtprotoConnectionRepository,
    ownerChanges: Flow<AtprotoConnectionBinding?>,
) : ViewModel() {
    private val localProblem = MutableStateFlow<AccountConnectionProblem?>(null)
    val state =
        combine(repository.state, localProblem) { current, problem ->
            if (problem == null) current else current.copy(problem = problem, observationFailed = current.snapshot != null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountConnectionState())

    init {
        viewModelScope.launch {
            ownerChanges.distinctUntilChanged().collectLatest { owner ->
                try {
                    repository.invalidate()
                    localProblem.value = null
                    if (owner != null) repository.refresh()
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    localProblem.value = AccountConnectionProblem.UNAVAILABLE
                }
            }
        }
    }

    fun refresh() {
        perform { repository.refresh() }
    }

    fun requestDisconnect() {
        perform { repository.requestDisconnect() }
    }

    fun cancelConfirmation() {
        perform { repository.cancelDisconnectConfirmation() }
    }

    fun confirmDisconnect() {
        perform { repository.confirmDisconnect() }
    }

    fun cancelPending() {
        perform { repository.cancelPending() }
    }

    fun reconnect(openBrowser: (String) -> Boolean) {
        perform {
            val url = repository.reconnect().getOrNull() ?: return@perform
            currentCoroutineContext().ensureActive()
            if (!openBrowser(url)) {
                repository.cancelPending()
                localProblem.value = AccountConnectionProblem.BROWSER_UNAVAILABLE
            }
        }
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            localProblem.value = null
            try {
                action()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                localProblem.value = AccountConnectionProblem.UNAVAILABLE
            }
        }
    }
}
