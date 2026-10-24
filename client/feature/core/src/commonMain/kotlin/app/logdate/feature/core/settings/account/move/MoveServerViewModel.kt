package app.logdate.feature.core.settings.account.move

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.data.account.ServerScopedAccount
import app.logdate.client.networking.PasskeyApiErrorCodes
import app.logdate.client.networking.PasskeyApiException
import app.logdate.client.permissions.PasskeyErrorCodes
import app.logdate.client.permissions.PasskeyException
import app.logdate.feature.core.settings.ui.CheckedServer
import app.logdate.feature.core.settings.ui.ServerCheck
import app.logdate.feature.core.settings.ui.ServerProblem
import app.logdate.shared.model.LogDateAccount
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface MoveServerUiState {
    data object Loading : MoveServerUiState

    data class ChooseServer(
        val source: MoveEndpoint,
        val address: String = "",
        val isChecking: Boolean = false,
        val problem: ChooseProblem? = null,
    ) : MoveServerUiState

    data class Review(
        val source: MoveEndpoint,
        val destination: CheckedServer,
        val survey: MoveSurvey,
        val isWorking: Boolean = false,
        val problem: MoveProblem? = null,
    ) : MoveServerUiState

    data class CreateAccount(
        val source: MoveEndpoint,
        val destination: CheckedServer,
        val survey: MoveSurvey,
        val username: String,
        val isWorking: Boolean = false,
        val problem: AccountProblem? = null,
    ) : MoveServerUiState

    data class Uploading(
        val record: ServerMoveRecord,
        val progress: MoveProgress? = null,
    ) : MoveServerUiState

    data class Finished(
        val record: ServerMoveRecord,
        val cleanup: Cleanup,
    ) : MoveServerUiState

    /** The move is over; the screen closes. */
    data object Closed : MoveServerUiState
}

sealed interface ChooseProblem {
    data class Server(
        val problem: ServerProblem,
    ) : ChooseProblem

    data object SameAsCurrent : ChooseProblem
}

enum class MoveProblem {
    OFFLINE,
    SIGN_IN_FAILED,

    /** Switching servers failed; the app is back on the old server with nothing changed. */
    SWITCH_FAILED,
}

enum class AccountProblem {
    USERNAME_TAKEN,
    INVALID_USERNAME,
    OFFLINE,
    PASSKEY_FAILED,
    SERVER,
}

/** What can happen to the account on the old server once the journal has moved. */
sealed interface Cleanup {
    data object Offered : Cleanup

    data object Deleting : Cleanup

    /** Deleting needs signing in to the old server again. */
    data object NeedsSignIn : Cleanup

    data class Blocked(
        val reason: CleanupBlock,
    ) : Cleanup

    data class Failed(
        val offline: Boolean,
    ) : Cleanup
}

enum class CleanupBlock {
    /** Some photos exist only on the old server; deleting it would lose them. */
    MEDIA_ONLY_ON_OLD_SERVER,

    /** The new server refused some uploads; the old server still has those items. */
    UPLOADS_FAILED,
}

/**
 * Guides a signed-in person through moving their account to another server: choosing the server,
 * reviewing what moves, getting an account there, watching the upload, and deciding what happens
 * to the old account.
 *
 * @param checkServer asks a server whether it can hold this account, changing nothing
 */
class MoveServerViewModel(
    private val move: ServerMove,
    private val checkServer: suspend (address: String) -> ServerCheck,
    private val currentAccount: () -> LogDateAccount?,
) : ViewModel() {
    private val _state = MutableStateFlow<MoveServerUiState>(MoveServerUiState.Loading)
    val state: StateFlow<MoveServerUiState> = _state.asStateFlow()

    private var destinationAccount: ServerScopedAccount? = null
    private var progressJob: Job? = null

    // Starts a new move, or picks up one the app was closed during.
    init {
        viewModelScope.launch {
            val record = move.inProgress()
            if (record == null) {
                _state.value = MoveServerUiState.ChooseServer(source = move.source())
                return@launch
            }
            move
                .resume(record)
                .onSuccess(::watchUpload)
                .onFailure { _state.value = MoveServerUiState.ChooseServer(source = move.source()) }
        }
    }

    fun setAddress(address: String) {
        _state.update { (it as? MoveServerUiState.ChooseServer)?.copy(address = address, problem = null) ?: it }
    }

    fun checkServer() {
        val choose = _state.value as? MoveServerUiState.ChooseServer ?: return
        if (choose.isChecking) return
        _state.value = choose.copy(isChecking = true, problem = null)
        viewModelScope.launch {
            _state.value =
                when (val check = checkServer(choose.address)) {
                    is ServerCheck.Unusable -> {
                        choose.copy(problem = ChooseProblem.Server(check.problem))
                    }

                    is ServerCheck.Usable -> {
                        if (check.server.origin == choose.source.origin) {
                            choose.copy(problem = ChooseProblem.SameAsCurrent)
                        } else {
                            MoveServerUiState.Review(source = choose.source, destination = check.server, survey = move.survey())
                        }
                    }
                }
        }
    }

    fun back() {
        _state.update { state ->
            when (state) {
                is MoveServerUiState.Review -> {
                    if (state.isWorking) state else MoveServerUiState.ChooseServer(state.source, address = state.destination.origin)
                }

                is MoveServerUiState.CreateAccount -> {
                    if (state.isWorking) state else MoveServerUiState.Review(state.source, state.destination, state.survey)
                }

                else -> {
                    state
                }
            }
        }
    }

    fun continueToAccount() {
        val review = _state.value as? MoveServerUiState.Review ?: return
        if (review.isWorking) return
        _state.value = review.copy(isWorking = true, problem = null)
        viewModelScope.launch {
            val account = move.openDestination(review.destination).also { destinationAccount = it }
            if (move.hasWorkingSignIn(account)) {
                commit(review.source, review.destination, review.survey, account)
            } else {
                _state.value =
                    MoveServerUiState.CreateAccount(
                        source = review.source,
                        destination = review.destination,
                        survey = review.survey,
                        username = currentAccount()?.username.orEmpty(),
                    )
            }
        }
    }

    fun createAccount(username: String) {
        val create = _state.value as? MoveServerUiState.CreateAccount ?: return
        val account = destinationAccount ?: return
        if (create.isWorking) return
        _state.value = create.copy(username = username, isWorking = true, problem = null)
        viewModelScope.launch {
            val displayName = currentAccount()?.displayName?.ifBlank { null } ?: username
            move
                .createAccount(account, username.trim(), displayName)
                .onSuccess { commit(create.source, create.destination, create.survey, account) }
                .onFailure { error ->
                    if ((error as? PasskeyApiException)?.errorCode == PasskeyApiErrorCodes.CANONICAL_OWNER_ID_TAKEN) {
                        signIn(create, account)
                    } else {
                        _state.value = create.copy(username = username, problem = accountProblem(error))
                    }
                }
        }
    }

    /** Signs in to an account this device already has on the new server. */
    fun signInInstead() {
        val create = _state.value as? MoveServerUiState.CreateAccount ?: return
        val account = destinationAccount ?: return
        if (create.isWorking) return
        _state.value = create.copy(isWorking = true, problem = null)
        viewModelScope.launch { signIn(create, account) }
    }

    private suspend fun signIn(
        create: MoveServerUiState.CreateAccount,
        account: ServerScopedAccount,
    ) {
        move
            .signIn(account)
            .onSuccess { commit(create.source, create.destination, create.survey, account) }
            .onFailure { error -> _state.value = create.copy(problem = accountProblem(error)) }
    }

    private suspend fun commit(
        source: MoveEndpoint,
        destination: CheckedServer,
        survey: MoveSurvey,
        account: ServerScopedAccount,
    ) {
        move
            .commit(destination, account, survey)
            .onSuccess { record ->
                account.close()
                destinationAccount = null
                watchUpload(record)
            }.onFailure { error ->
                Napier.e("Moving to ${destination.origin} failed", error)
                _state.value = MoveServerUiState.Review(source, destination, survey, problem = MoveProblem.SWITCH_FAILED)
            }
    }

    private fun watchUpload(record: ServerMoveRecord) {
        _state.value = MoveServerUiState.Uploading(record)
        progressJob?.cancel()
        progressJob =
            viewModelScope.launch {
                move.progress(record).collect { progress ->
                    val current = _state.value
                    if (current !is MoveServerUiState.Uploading && current !is MoveServerUiState.Finished) return@collect
                    val done = progress.remaining == 0 && !progress.isSyncing && progress.syncedSinceSwitch
                    _state.value =
                        if (!done) {
                            MoveServerUiState.Uploading(record, progress)
                        } else if (current is MoveServerUiState.Finished) {
                            current
                        } else {
                            MoveServerUiState.Finished(record, cleanupFor(record, progress))
                        }
                }
            }
    }

    private fun cleanupFor(
        record: ServerMoveRecord,
        progress: MoveProgress,
    ): Cleanup =
        when {
            record.remoteOnlyMedia > 0 -> Cleanup.Blocked(CleanupBlock.MEDIA_ONLY_ON_OLD_SERVER)
            progress.failed > 0 -> Cleanup.Blocked(CleanupBlock.UPLOADS_FAILED)
            else -> Cleanup.Offered
        }

    fun deleteSource() = cleanUp { record -> move.deleteSource(record) }

    fun signInToSourceAndDelete() = cleanUp { record -> move.signInToSourceAndDelete(record) }

    private fun cleanUp(delete: suspend (ServerMoveRecord) -> SourceDeletion) {
        val finished = _state.value as? MoveServerUiState.Finished ?: return
        if (finished.cleanup == Cleanup.Deleting || finished.cleanup is Cleanup.Blocked) return
        _state.value = finished.copy(cleanup = Cleanup.Deleting)
        viewModelScope.launch {
            _state.value =
                when (delete(finished.record)) {
                    SourceDeletion.DELETED -> close()
                    SourceDeletion.NEEDS_SIGN_IN -> finished.copy(cleanup = Cleanup.NeedsSignIn)
                    SourceDeletion.OFFLINE -> finished.copy(cleanup = Cleanup.Failed(offline = true))
                    SourceDeletion.FAILED -> finished.copy(cleanup = Cleanup.Failed(offline = false))
                }
        }
    }

    /** Ends the move and keeps the account on the old server as it is. */
    fun keepSource() {
        if (_state.value !is MoveServerUiState.Finished) return
        viewModelScope.launch {
            move.finish()
            _state.value = close()
        }
    }

    private fun close(): MoveServerUiState {
        progressJob?.cancel()
        return MoveServerUiState.Closed
    }

    override fun onCleared() {
        destinationAccount?.close()
    }
}

private fun accountProblem(error: Throwable): AccountProblem? {
    if (error is PasskeyException) {
        return if (error.errorCode == PasskeyErrorCodes.USER_CANCELLED) null else AccountProblem.PASSKEY_FAILED
    }
    Napier.w("Getting an account on the new server failed", error)
    return when ((error as? PasskeyApiException)?.errorCode) {
        PasskeyApiErrorCodes.USERNAME_TAKEN -> AccountProblem.USERNAME_TAKEN
        PasskeyApiErrorCodes.VALIDATION_ERROR -> AccountProblem.INVALID_USERNAME
        PasskeyApiErrorCodes.NETWORK_ERROR -> AccountProblem.OFFLINE
        else -> AccountProblem.SERVER
    }
}
