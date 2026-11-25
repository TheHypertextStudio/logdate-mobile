@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.sync.peer.removal.WatchRemovalAccess
import app.logdate.client.sync.peer.removal.WatchRemovalResult
import app.logdate.client.sync.peer.removal.WatchRemovalTarget
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.step.StepPrimaryButton
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
import logdate.client.feature.core.generated.resources.watch_remove_deferred
import logdate.client.feature.core.generated.resources.watch_remove_discarded
import logdate.client.feature.core.generated.resources.watch_remove_discarded_unknown
import logdate.client.feature.core.generated.resources.watch_remove_done
import logdate.client.feature.core.generated.resources.watch_remove_failed
import logdate.client.feature.core.generated.resources.watch_remove_loading
import logdate.client.feature.core.generated.resources.watch_remove_retry
import logdate.client.feature.core.generated.resources.watch_remove_review
import logdate.client.feature.core.generated.resources.watch_remove_unavailable
import logdate.client.feature.core.generated.resources.watch_remove_unsent
import logdate.client.feature.core.generated.resources.watch_remove_unsent_unknown
import logdate.client.feature.core.generated.resources.watch_v2_remove
import org.jetbrains.compose.resources.pluralStringResource
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
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                when (val value = state) {
                    WatchRemovalState.Loading -> {
                        RemovalStatusCard(message = stringResource(Res.string.watch_remove_loading))
                    }

                    is WatchRemovalState.Review -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                val largeText = LocalDensity.current.fontScale >= 1.6f
                                if (largeText) {
                                    RemovalWatchBadge()
                                    Text(
                                        text = stringResource(Res.string.watch_remove_review, value.target.displayName),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RemovalWatchBadge()
                                        Text(
                                            text = stringResource(Res.string.watch_remove_review, value.target.displayName),
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(Res.string.watch_remove_consequence),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val unsent = value.target.pending
                        if (unsent == null || unsent > 0) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(Spacing.md),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Text(
                                        text =
                                            if (unsent == null) {
                                                stringResource(Res.string.watch_remove_unsent_unknown)
                                            } else {
                                                pluralStringResource(Res.plurals.watch_remove_unsent, unsent, unsent)
                                            },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        val height = ButtonDefaults.MediumContainerHeight
                        Button(
                            onClick = onRemove,
                            shapes = ButtonDefaults.shapesFor(height),
                            modifier = Modifier.fillMaxWidth().heightIn(min = height),
                            contentPadding = ButtonDefaults.contentPaddingFor(height),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                        ) {
                            Text(
                                text = stringResource(Res.string.watch_remove_confirm),
                                style = ButtonDefaults.textStyleFor(height),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    WatchRemovalState.Removing -> {
                        RemovalStatusCard(message = stringResource(Res.string.watch_remove_busy))
                    }

                    WatchRemovalState.Unavailable -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier.padding(Spacing.lg),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WatchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(Res.string.watch_remove_unavailable),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is WatchRemovalState.Finished -> {
                        when (val result = value.result) {
                            is WatchRemovalResult.Removed -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(Spacing.lg),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text = stringResource(Res.string.watch_remove_done),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                }
                                val discarded = result.discarded
                                if (discarded == null || discarded > 0) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(Spacing.md),
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Info,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text =
                                                    if (discarded == null) {
                                                        stringResource(Res.string.watch_remove_discarded_unknown)
                                                    } else {
                                                        pluralStringResource(Res.plurals.watch_remove_discarded, discarded, discarded)
                                                    },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            WatchRemovalResult.RemovedWillWipeOnNextConnect -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(Spacing.lg),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text = stringResource(Res.string.watch_remove_deferred),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                }
                            }

                            is WatchRemovalResult.Failed -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(Spacing.lg),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text = stringResource(Res.string.watch_remove_failed),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                }
                                StepPrimaryButton(
                                    text = stringResource(Res.string.watch_remove_retry),
                                    onClick = onRetry,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemovalWatchBadge() {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.WatchOff,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun RemovalStatusCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
