@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.sync.peer.removal.WatchRemovalAccess
import app.logdate.client.sync.peer.removal.WatchRemovalResult
import app.logdate.client.sync.peer.removal.WatchRemovalTarget
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_remove_busy
import logdate.client.feature.core.generated.resources.watch_remove_confirm
import logdate.client.feature.core.generated.resources.watch_remove_consequence
import logdate.client.feature.core.generated.resources.watch_remove_count_unknown
import logdate.client.feature.core.generated.resources.watch_remove_deferred
import logdate.client.feature.core.generated.resources.watch_remove_done
import logdate.client.feature.core.generated.resources.watch_remove_failed
import logdate.client.feature.core.generated.resources.watch_remove_loading
import logdate.client.feature.core.generated.resources.watch_remove_pending
import logdate.client.feature.core.generated.resources.watch_remove_retry
import logdate.client.feature.core.generated.resources.watch_remove_review
import logdate.client.feature.core.generated.resources.watch_remove_unavailable
import logdate.client.feature.core.generated.resources.watch_v2_remove
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

sealed interface WatchRemovalState {
    data object Loading : WatchRemovalState

    data class Review(
        val target: WatchRemovalTarget,
    ) : WatchRemovalState

    data object Removing : WatchRemovalState

    data class Finished(
        val result: WatchRemovalResult,
    ) : WatchRemovalState

    data object Unavailable : WatchRemovalState
}

class WatchRemovalViewModel(
    private val access: WatchRemovalAccess,
) : ViewModel() {
    private val mutable = MutableStateFlow<WatchRemovalState>(WatchRemovalState.Loading)
    val state = mutable.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutable.value = WatchRemovalState.Loading
            mutable.value =
                try {
                    access.target()?.let(WatchRemovalState::Review) ?: WatchRemovalState.Unavailable
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("The watch removal target could not be loaded", error)
                    WatchRemovalState.Unavailable
                }
        }
    }

    fun remove() {
        val target = (mutable.value as? WatchRemovalState.Review)?.target ?: return
        mutable.value = WatchRemovalState.Removing
        viewModelScope.launch { mutable.value = WatchRemovalState.Finished(access.remove(target)) }
    }
}

@Composable
fun WatchRemovalScreen(
    onBack: () -> Unit,
    viewModel: WatchRemovalViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    WatchRemovalContent(state, viewModel::remove, viewModel::load, onBack)
}

@Composable
fun WatchRemovalContent(
    state: WatchRemovalState,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(title = stringResource(Res.string.watch_v2_remove), onBack = onBack) {
        item {
            MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    when (val value = state) {
                        WatchRemovalState.Loading -> {
                            Text(stringResource(Res.string.watch_remove_loading))
                        }

                        is WatchRemovalState.Review -> {
                            Text(
                                stringResource(Res.string.watch_remove_review, value.target.displayName),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                stringResource(
                                    Res.string.watch_remove_pending,
                                    value.target.pending?.toString() ?: stringResource(Res.string.watch_remove_count_unknown),
                                ),
                            )
                            Text(stringResource(Res.string.watch_remove_consequence))
                            Button(
                                onClick = onRemove,
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                            ) { Text(stringResource(Res.string.watch_remove_confirm)) }
                        }

                        WatchRemovalState.Removing -> {
                            Text(stringResource(Res.string.watch_remove_busy))
                        }

                        WatchRemovalState.Unavailable -> {
                            Text(stringResource(Res.string.watch_remove_unavailable))
                        }

                        is WatchRemovalState.Finished -> {
                            when (val result = value.result) {
                                WatchRemovalResult.Removed -> {
                                    Text(stringResource(Res.string.watch_remove_done))
                                }

                                WatchRemovalResult.RemovedWillWipeOnNextConnect -> {
                                    Text(stringResource(Res.string.watch_remove_deferred))
                                }

                                is WatchRemovalResult.Failed -> {
                                    Text(stringResource(Res.string.watch_remove_failed))
                                    Button(onClick = onRetry) { Text(stringResource(Res.string.watch_remove_retry)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
