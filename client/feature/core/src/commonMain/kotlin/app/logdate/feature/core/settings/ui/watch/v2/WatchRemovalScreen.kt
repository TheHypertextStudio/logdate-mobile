@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.sync.peer.removal.WatchRemovalAccess
import app.logdate.client.sync.peer.removal.WatchRemovalResult
import app.logdate.client.sync.peer.removal.WatchRemovalTarget
import app.logdate.ui.common.PlatformBackHandler
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepSecondaryButton
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
import logdate.client.feature.core.generated.resources.watch_remove_done_clean
import logdate.client.feature.core.generated.resources.watch_remove_failed
import logdate.client.feature.core.generated.resources.watch_remove_failed_title
import logdate.client.feature.core.generated.resources.watch_remove_loading
import logdate.client.feature.core.generated.resources.watch_remove_retry
import logdate.client.feature.core.generated.resources.watch_remove_review
import logdate.client.feature.core.generated.resources.watch_remove_unavailable
import logdate.client.feature.core.generated.resources.watch_remove_unavailable_title
import logdate.client.feature.core.generated.resources.watch_remove_unsent
import logdate.client.feature.core.generated.resources.watch_remove_unsent_unknown
import logdate.client.feature.core.generated.resources.watch_setup_complete_action
import logdate.client.ui.generated.resources.common_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

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
    val reduceMotion by rememberSystemReduceMotion()
    val haptic = LocalHapticFeedback.current
    // Prevent backing out mid-revocation/wipe so phone and watch state never diverge halfway through removal.
    PlatformBackHandler(enabled = state is WatchRemovalState.Removing) {}
    LaunchedEffect(state) {
        if (state is WatchRemovalState.Finished) {
            val feedback =
                if (state.result is WatchRemovalResult.Failed) {
                    HapticFeedbackType.LongPress
                } else {
                    HapticFeedbackType.Confirm
                }
            haptic.performHapticFeedback(feedback)
        }
    }
    AnimatedContent(
        targetState = state,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (reduceMotion) {
                fadeIn() togetherWith fadeOut()
            } else {
                (fadeIn() + slideInHorizontally { it / 6 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 6 })
            }
        },
        label = "watch_removal_step",
    ) { current ->
        when (current) {
            WatchRemovalState.Loading -> {
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_remove_loading),
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                    picture = { SearchingPicture(it) },
                ) {}
            }

            is WatchRemovalState.Review -> {
                val unsent = current.target.pending
                val consequence = stringResource(Res.string.watch_remove_consequence)
                val description =
                    when (unsent) {
                        null -> "${stringResource(Res.string.watch_remove_unsent_unknown)}\n\n$consequence"
                        0 -> consequence
                        else -> "${pluralStringResource(Res.plurals.watch_remove_unsent, unsent, unsent)}\n\n$consequence"
                    }
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_remove_review, current.target.displayName),
                    description = description,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                    picture = {
                        StoppedPicture(
                            side = it,
                            shape = MaterialShapes.Slanted.toShape(),
                            tone = WatchSetupTone.PROBLEM,
                            symbol = Icons.Rounded.WatchOff,
                        )
                    },
                ) {
                    val height = ButtonDefaults.MediumContainerHeight
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRemove()
                        },
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
                    StepSecondaryButton(
                        text = stringResource(UiRes.string.common_cancel),
                        onClick = onBack,
                        quiet = true,
                    )
                }
            }

            WatchRemovalState.Removing -> {
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_remove_busy),
                    onBack = null,
                    modifier = Modifier.fillMaxSize(),
                    announce = true,
                    picture = {
                        WorkingPicture(
                            side = it,
                            shape = MaterialShapes.Cookie9Sided.toShape(),
                            waiting = false,
                        )
                    },
                ) {}
            }

            WatchRemovalState.Unavailable -> {
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_remove_unavailable_title),
                    description = stringResource(Res.string.watch_remove_unavailable),
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                    picture = {
                        StoppedPicture(
                            side = it,
                            shape = MaterialShapes.Slanted.toShape(),
                            tone = WatchSetupTone.QUIET,
                            symbol = Icons.Rounded.WatchOff,
                        )
                    },
                ) {
                    StepPrimaryButton(
                        text = stringResource(Res.string.watch_setup_complete_action),
                        onClick = onBack,
                    )
                }
            }

            is WatchRemovalState.Finished -> {
                when (val result = current.result) {
                    is WatchRemovalResult.Removed -> {
                        val discarded = result.discarded
                        val description =
                            when (discarded) {
                                null -> stringResource(Res.string.watch_remove_discarded_unknown)
                                0 -> stringResource(Res.string.watch_remove_done_clean)
                                else -> pluralStringResource(Res.plurals.watch_remove_discarded, discarded, discarded)
                            }
                        WatchFlowScaffold(
                            title = stringResource(Res.string.watch_remove_done),
                            description = description,
                            onBack = onBack,
                            modifier = Modifier.fillMaxSize(),
                            announce = true,
                            picture = { ReadyPicture(it) },
                        ) {
                            StepPrimaryButton(
                                text = stringResource(Res.string.watch_setup_complete_action),
                                onClick = onBack,
                            )
                        }
                    }

                    WatchRemovalResult.RemovedWillWipeOnNextConnect -> {
                        WatchFlowScaffold(
                            title = stringResource(Res.string.watch_remove_done),
                            description = stringResource(Res.string.watch_remove_deferred),
                            onBack = onBack,
                            modifier = Modifier.fillMaxSize(),
                            announce = true,
                            picture = { ReadyPicture(it) },
                        ) {
                            StepPrimaryButton(
                                text = stringResource(Res.string.watch_setup_complete_action),
                                onClick = onBack,
                            )
                        }
                    }

                    is WatchRemovalResult.Failed -> {
                        WatchFlowScaffold(
                            title = stringResource(Res.string.watch_remove_failed_title),
                            description = stringResource(Res.string.watch_remove_failed),
                            onBack = onBack,
                            modifier = Modifier.fillMaxSize(),
                            announce = true,
                            picture = {
                                StoppedPicture(
                                    side = it,
                                    shape = MaterialShapes.SoftBurst.toShape(),
                                    tone = WatchSetupTone.PROBLEM,
                                    symbol = Icons.Rounded.WatchOff,
                                    badge = Icons.Rounded.PriorityHigh,
                                )
                            },
                        ) {
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
