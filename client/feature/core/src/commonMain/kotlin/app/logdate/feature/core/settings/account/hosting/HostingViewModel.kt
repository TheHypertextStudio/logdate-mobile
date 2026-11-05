package app.logdate.feature.core.settings.account.hosting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.feature.core.settings.account.ConnectedServer
import app.logdate.feature.core.settings.account.ConnectedServerInfo
import app.logdate.feature.core.settings.account.ServerHealth
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ServerCheckState {
    data object Idle : ServerCheckState

    data object Checking : ServerCheckState

    data object Failed : ServerCheckState
}

data class HostingUiState(
    val server: ConnectedServerInfo? = null,
    val health: ServerHealth? = null,
    val canMoveAccount: Boolean = false,
    val check: ServerCheckState = ServerCheckState.Idle,
)

/** The connected origin owns initial checking and checks after a server move. */
class HostingViewModel(
    private val connectedServer: ConnectedServer,
    private val canMoveAccount: suspend () -> Boolean = { false },
) : ViewModel() {
    private val _state = MutableStateFlow(HostingUiState(check = ServerCheckState.Checking))
    val state: StateFlow<HostingUiState> = _state.asStateFlow()
    private var serverJob: Job? = null
    private var checkJob: Job? = null
    private var generation = 0L

    init {
        observeServer()
        viewModelScope.launch {
            try {
                val movable = canMoveAccount()
                _state.update { it.copy(canMoveAccount = movable) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Could not read server move capability", error)
            }
        }
    }

    private fun observeServer() {
        if (serverJob?.isActive == true) return
        serverJob =
            viewModelScope.launch {
                try {
                    connectedServer.info.collect { server ->
                        val changedOrigin = server.origin != _state.value.server?.origin
                        _state.update { it.copy(server = server) }
                        if (changedOrigin) {
                            generation++
                            checkJob?.cancel()
                            checkJob = null
                            _state.update { it.copy(health = null) }
                            checkAgain()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read connected server", error)
                    _state.update { it.copy(check = ServerCheckState.Failed) }
                }
            }
    }

    fun checkAgain() {
        if (_state.value.server == null) {
            _state.update { it.copy(check = ServerCheckState.Checking) }
            observeServer()
            return
        }
        if (checkJob?.isActive == true) return
        val token = generation
        _state.update { it.copy(check = ServerCheckState.Checking) }
        checkJob =
            viewModelScope.launch {
                try {
                    val health = connectedServer.refresh()
                    if (token == generation) _state.update { it.copy(health = health, check = ServerCheckState.Idle) }
                } catch (cancelled: CancellationException) {
                    if (token == generation) _state.update { it.copy(check = ServerCheckState.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not check connected server", error)
                    if (token == generation) _state.update { it.copy(check = ServerCheckState.Failed) }
                }
            }
    }
}
