@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.watch.DiscoveredWatch
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchSetupStep
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.feature.core.settings.ui.watch.WatchSystemSetupButton
import app.logdate.feature.core.settings.ui.watch.messageResource
import app.logdate.feature.core.settings.ui.watch.watchLinkingCopy
import app.logdate.ui.common.applyScreenStyles
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepSecondaryButton
import app.logdate.ui.theme.Spacing
import app.logdate.ui.watch.WatchSetupProvider
import app.logdate.ui.watch.rememberWatchSetupLauncher
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_association_pending
import logdate.client.feature.core.generated.resources.watch_association_pending_description
import logdate.client.feature.core.generated.resources.watch_discovery_unavailable
import logdate.client.feature.core.generated.resources.watch_handoff_retry
import logdate.client.feature.core.generated.resources.watch_installation_unknown
import logdate.client.feature.core.generated.resources.watch_loading
import logdate.client.feature.core.generated.resources.watch_request_failed_installed_title
import logdate.client.feature.core.generated.resources.watch_request_failed_no_response_title
import logdate.client.feature.core.generated.resources.watch_request_failed_no_watch_title
import logdate.client.feature.core.generated.resources.watch_request_failed_not_installed_title
import logdate.client.feature.core.generated.resources.watch_request_failed_open_title
import logdate.client.feature.core.generated.resources.watch_setup_already_installed_action
import logdate.client.feature.core.generated.resources.watch_setup_already_open_action
import logdate.client.feature.core.generated.resources.watch_setup_associate_action
import logdate.client.feature.core.generated.resources.watch_setup_associate_description
import logdate.client.feature.core.generated.resources.watch_setup_associate_title
import logdate.client.feature.core.generated.resources.watch_setup_choose_watch_continue
import logdate.client.feature.core.generated.resources.watch_setup_choose_watch_description
import logdate.client.feature.core.generated.resources.watch_setup_choose_watch_title
import logdate.client.feature.core.generated.resources.watch_setup_complete_action
import logdate.client.feature.core.generated.resources.watch_setup_complete_description
import logdate.client.feature.core.generated.resources.watch_setup_complete_title
import logdate.client.feature.core.generated.resources.watch_setup_device_installed_tag
import logdate.client.feature.core.generated.resources.watch_setup_device_nearby
import logdate.client.feature.core.generated.resources.watch_setup_device_out_of_range
import logdate.client.feature.core.generated.resources.watch_setup_device_ready_to_install_tag
import logdate.client.feature.core.generated.resources.watch_setup_device_status_confirm_phone
import logdate.client.feature.core.generated.resources.watch_setup_device_status_connected
import logdate.client.feature.core.generated.resources.watch_setup_device_status_install_sent
import logdate.client.feature.core.generated.resources.watch_setup_device_status_linking
import logdate.client.feature.core.generated.resources.watch_setup_device_status_open
import logdate.client.feature.core.generated.resources.watch_setup_device_status_pair
import logdate.client.feature.core.generated.resources.watch_setup_install_action
import logdate.client.feature.core.generated.resources.watch_setup_install_description
import logdate.client.feature.core.generated.resources.watch_setup_install_sent_description
import logdate.client.feature.core.generated.resources.watch_setup_install_sent_title
import logdate.client.feature.core.generated.resources.watch_setup_install_title
import logdate.client.feature.core.generated.resources.watch_setup_open_action
import logdate.client.feature.core.generated.resources.watch_setup_open_description
import logdate.client.feature.core.generated.resources.watch_setup_open_title
import logdate.client.feature.core.generated.resources.watch_setup_opening_description
import logdate.client.feature.core.generated.resources.watch_setup_opening_title
import logdate.client.feature.core.generated.resources.watch_setup_pair_description
import logdate.client.feature.core.generated.resources.watch_setup_pair_title
import logdate.client.feature.core.generated.resources.watch_setup_send_again
import logdate.client.feature.core.generated.resources.watch_setup_send_install_again
import logdate.client.feature.core.generated.resources.watch_setup_start_action
import logdate.client.feature.core.generated.resources.watch_setup_switch_watch
import logdate.client.feature.core.generated.resources.watch_setup_troubleshoot_action
import logdate.client.feature.core.generated.resources.watch_unknown_description
import logdate.client.feature.core.generated.resources.watch_unsupported
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/** The colour family a problem or quiet step is drawn in, so its kind reads before any text does. */
internal enum class WatchSetupTone { PROBLEM, QUIET }

private enum class WatchSetupVisualStep {
    LOADING,
    UNSUPPORTED,
    NO_WATCH,
    CHOOSE_WATCH,
    DISCOVERY_UNAVAILABLE,
    OPEN_WATCH,
    OPENING_WATCH,
    PAIR_PHONE,
    CONFIRM_PHONE,
    NOT_INSTALLED,
    INSTALL_SENT,
    REQUEST_FAILED,
    LINKING,
    COMPLETE,
}

internal data class SetupStepProgress(
    val current: Int,
    val total: Int,
) {
    val fraction: Float get() = current.toFloat() / total.toFloat()
}

private const val SETUP_TOTAL_STEPS = 3

/**
 * First use walks the user through one real device and one clear step at a time, with each step's
 * title in the collapsing Material 3 top bar instead of duplicate page titles.
 */
@Composable
internal fun WatchSetupContent(
    status: WatchStatus?,
    requestFailure: WatchRequestFailure?,
    installRequestSent: Boolean,
    onBack: () -> Unit,
    onAssociate: () -> Unit,
    onInstall: () -> Unit,
    setupLauncher: ((WatchSetupProvider) -> Boolean)?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onConfirmMissing: (String) -> Unit = {},
    onOpenOnWatch: (() -> Unit)? = null,
    onSelectWatch: (String) -> Unit = {},
    onDiagnostics: (() -> Unit)? = null,
    onCompleteDone: (() -> Unit)? = null,
) {
    val availableWatches = status.availableWatches()
    var watchChoiceConfirmed by remember(availableWatches.map { it.nodeId }) {
        mutableStateOf(availableWatches.size <= 1)
    }
    var switchingWatch by remember(availableWatches.size) { mutableStateOf(false) }
    var openSent by remember(status?.nodeId) { mutableStateOf(false) }
    var localInstallSent by remember(status?.nodeId) { mutableStateOf(false) }
    LaunchedEffect(requestFailure) {
        if (requestFailure != null) {
            openSent = false
            localInstallSent = false
        }
    }
    val effectiveInstallSent = installRequestSent || localInstallSent
    val showChooser = availableWatches.size > 1 && (!watchChoiceConfirmed || switchingWatch)

    val visualStep =
        when {
            showChooser -> {
                WatchSetupVisualStep.CHOOSE_WATCH
            }

            else -> {
                when (status) {
                    null -> {
                        WatchSetupVisualStep.LOADING
                    }

                    WatchStatus.Unsupported -> {
                        WatchSetupVisualStep.UNSUPPORTED
                    }

                    WatchStatus.NoWatch -> {
                        WatchSetupVisualStep.NO_WATCH
                    }

                    is WatchStatus.Unknown -> {
                        when {
                            status.nodeId == null && requestFailure == null -> {
                                WatchSetupVisualStep.DISCOVERY_UNAVAILABLE
                            }

                            requestFailure != null -> {
                                WatchSetupVisualStep.REQUEST_FAILED
                            }

                            openSent && status.nodeId != null -> {
                                WatchSetupVisualStep.OPENING_WATCH
                            }

                            effectiveInstallSent && status.nodeId != null -> {
                                WatchSetupVisualStep.INSTALL_SENT
                            }

                            !status.knownInstalled && status.nodeId != null -> {
                                WatchSetupVisualStep.NOT_INSTALLED
                            }

                            else -> {
                                WatchSetupVisualStep.OPEN_WATCH
                            }
                        }
                    }

                    is WatchStatus.SettingUp -> {
                        when (status.step) {
                            is WatchSetupStep.PairWithPhone -> WatchSetupVisualStep.PAIR_PHONE
                            is WatchSetupStep.ConfirmOnPhone -> WatchSetupVisualStep.CONFIRM_PHONE
                            is WatchSetupStep.Linking -> WatchSetupVisualStep.LINKING
                        }
                    }

                    is WatchStatus.NotInstalled -> {
                        when {
                            requestFailure != null -> WatchSetupVisualStep.REQUEST_FAILED
                            effectiveInstallSent -> WatchSetupVisualStep.INSTALL_SENT
                            else -> WatchSetupVisualStep.NOT_INSTALLED
                        }
                    }

                    is WatchStatus.Ready -> {
                        WatchSetupVisualStep.COMPLETE
                    }

                    is WatchStatus.NeedsAttention -> {
                        WatchSetupVisualStep.LINKING
                    }
                }
            }
        }

    val stepBack: () -> Unit = {
        when {
            switchingWatch && watchChoiceConfirmed -> switchingWatch = false
            openSent -> openSent = false
            else -> onBack()
        }
    }
    val switchAction: (() -> Unit)? =
        if (availableWatches.size > 1) {
            { switchingWatch = true }
        } else {
            null
        }

    val reduceMotion by rememberSystemReduceMotion()
    AnimatedContent(
        targetState = visualStep,
        modifier = modifier,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn() togetherWith fadeOut()
            } else {
                (fadeIn() + slideInHorizontally { it / 6 }) togetherWith (fadeOut() + slideOutHorizontally { -it / 6 })
            }
        },
        label = "watch_setup_step",
    ) { step ->
        when (step) {
            WatchSetupVisualStep.LOADING -> {
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_loading),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    picture = { SearchingPicture(it) },
                ) {}
            }

            WatchSetupVisualStep.UNSUPPORTED -> {
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_unsupported),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    picture = { StoppedPicture(it, MaterialShapes.Slanted.toShape(), WatchSetupTone.QUIET, Icons.Rounded.WatchOff) },
                ) {}
            }

            WatchSetupVisualStep.NO_WATCH -> {
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_pair_title),
                    description = stringResource(Res.string.watch_setup_pair_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    picture = { IntroPicture(it) },
                ) {
                    WatchSystemSetupButton(
                        launcher = setupLauncher ?: rememberWatchSetupLauncher(),
                        label = stringResource(Res.string.watch_setup_start_action),
                        prominent = true,
                    )
                }
            }

            WatchSetupVisualStep.CHOOSE_WATCH -> {
                var selectedNodeId by remember(availableWatches) {
                    mutableStateOf(
                        status?.nodeId?.takeIf { id -> availableWatches.any { it.nodeId == id } }
                            ?: availableWatches.firstOrNull()?.nodeId,
                    )
                }
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_choose_watch_title),
                    description = stringResource(Res.string.watch_setup_choose_watch_description),
                    onBack = stepBack,
                    progress = SetupStepProgress(1, SETUP_TOTAL_STEPS),
                    modifier = Modifier.fillMaxSize(),
                    bodyContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            availableWatches.forEach { watch ->
                                WatchChoiceCard(
                                    watch = watch,
                                    selected = watch.nodeId == selectedNodeId,
                                    onClick = { selectedNodeId = watch.nodeId },
                                )
                            }
                        }
                    },
                ) {
                    WatchSetupAction(stringResource(Res.string.watch_setup_choose_watch_continue)) {
                        selectedNodeId?.let(onSelectWatch)
                        watchChoiceConfirmed = true
                        switchingWatch = false
                    }
                }
            }

            WatchSetupVisualStep.DISCOVERY_UNAVAILABLE -> {
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_discovery_unavailable),
                    description = stringResource(Res.string.watch_unknown_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    picture = {
                        StoppedPicture(
                            it,
                            MaterialShapes.Pentagon.toShape(),
                            WatchSetupTone.QUIET,
                            Icons.Rounded.QuestionMark,
                            badge = Icons.Rounded.Watch,
                        )
                    },
                ) {
                    WatchSetupAction(stringResource(Res.string.watch_handoff_retry), onRetry)
                }
            }

            WatchSetupVisualStep.OPEN_WATCH -> {
                val unknown = status as? WatchStatus.Unknown ?: return@AnimatedContent
                val watchName = unknown.watchName ?: ""
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_open_title, watchName),
                    description = stringResource(Res.string.watch_setup_open_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(2, SETUP_TOTAL_STEPS),
                    picture = { WakeWatchPicture(it) },
                    bodyContent = {
                        WatchDeviceInfoCard(
                            watchName = watchName,
                            nearby = unknown.nearby,
                            statusLine = stringResource(Res.string.watch_setup_device_installed_tag),
                            onSwitch = switchAction,
                        )
                    },
                ) {
                    if (onOpenOnWatch != null) {
                        WatchSetupAction(stringResource(Res.string.watch_setup_open_action)) {
                            openSent = true
                            onOpenOnWatch()
                        }
                        StepSecondaryButton(
                            text = stringResource(Res.string.watch_setup_already_open_action),
                            onClick = onRetry,
                            quiet = true,
                        )
                    } else {
                        WatchSetupAction(stringResource(Res.string.watch_handoff_retry), onRetry)
                    }
                }
            }

            WatchSetupVisualStep.OPENING_WATCH -> {
                val unknown = status as? WatchStatus.Unknown ?: return@AnimatedContent
                val watchName = unknown.watchName ?: ""
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_opening_title, watchName),
                    description = stringResource(Res.string.watch_setup_opening_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(2, SETUP_TOTAL_STEPS),
                    announce = true,
                    picture = { WorkingPicture(it, MaterialShapes.Cookie9Sided.toShape(), waiting = false) },
                    bodyContent = {
                        WatchDeviceInfoCard(
                            watchName = watchName,
                            nearby = unknown.nearby,
                            statusLine = stringResource(Res.string.watch_setup_device_status_open),
                            onSwitch = switchAction,
                        )
                    },
                ) {
                    if (onOpenOnWatch != null) {
                        StepSecondaryButton(
                            text = stringResource(Res.string.watch_setup_send_again),
                            onClick = onOpenOnWatch,
                            quiet = true,
                        )
                    }
                }
            }

            WatchSetupVisualStep.PAIR_PHONE -> {
                val settingUp = status as? WatchStatus.SettingUp ?: return@AnimatedContent
                val pair = settingUp.step as? WatchSetupStep.PairWithPhone ?: return@AnimatedContent
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_associate_title, pair.watchName),
                    description = stringResource(Res.string.watch_setup_associate_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(1, SETUP_TOTAL_STEPS),
                    picture = { ConnectPicture(it) },
                    bodyContent = {
                        WatchDeviceInfoCard(
                            watchName = pair.watchName,
                            nearby = settingUp.nearby,
                            statusLine = stringResource(Res.string.watch_setup_device_status_pair),
                            onSwitch = switchAction,
                        )
                    },
                ) { WatchSetupAction(stringResource(Res.string.watch_setup_associate_action), onAssociate) }
            }

            WatchSetupVisualStep.CONFIRM_PHONE -> {
                val settingUp = status as? WatchStatus.SettingUp
                val confirm = settingUp?.step as? WatchSetupStep.ConfirmOnPhone
                val watchName = confirm?.watchName ?: status.availableWatches().firstOrNull()?.displayName
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_association_pending),
                    description = stringResource(Res.string.watch_association_pending_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(1, SETUP_TOTAL_STEPS),
                    announce = true,
                    picture = { AllowPicture(it) },
                    bodyContent =
                        watchName?.let { name ->
                            {
                                WatchDeviceInfoCard(
                                    watchName = name,
                                    nearby = settingUp?.nearby ?: true,
                                    statusLine = stringResource(Res.string.watch_setup_device_status_confirm_phone),
                                )
                            }
                        },
                ) {}
            }

            WatchSetupVisualStep.NOT_INSTALLED -> {
                val watchName: String
                val nearby: Boolean
                val nodeId: String?
                when (status) {
                    is WatchStatus.NotInstalled -> {
                        watchName = status.watchName
                        nearby = status.nearby
                        nodeId = status.nodeId
                    }

                    is WatchStatus.Unknown -> {
                        watchName = status.watchName ?: ""
                        nearby = status.nearby
                        nodeId = status.nodeId
                    }

                    else -> {
                        return@AnimatedContent
                    }
                }
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_install_title, watchName),
                    description = stringResource(Res.string.watch_setup_install_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(2, SETUP_TOTAL_STEPS),
                    picture = { InstallPicture(it) },
                    bodyContent = {
                        WatchDeviceInfoCard(
                            watchName = watchName,
                            nearby = nearby,
                            statusLine = stringResource(Res.string.watch_setup_device_ready_to_install_tag),
                            onSwitch = switchAction,
                        )
                    },
                ) {
                    WatchSetupAction(stringResource(Res.string.watch_setup_install_action)) {
                        if (status is WatchStatus.Unknown && nodeId != null) {
                            localInstallSent = true
                            onConfirmMissing(nodeId)
                        } else {
                            onInstall()
                        }
                    }
                    if (status is WatchStatus.Unknown && onOpenOnWatch != null) {
                        StepSecondaryButton(
                            text = stringResource(Res.string.watch_setup_already_installed_action),
                            onClick = {
                                openSent = true
                                onOpenOnWatch()
                            },
                            quiet = true,
                        )
                    }
                }
            }

            WatchSetupVisualStep.INSTALL_SENT -> {
                val (watchName, nearby) =
                    when (status) {
                        is WatchStatus.NotInstalled -> status.watchName to status.nearby
                        is WatchStatus.Unknown -> (status.watchName ?: "") to status.nearby
                        else -> return@AnimatedContent
                    }
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_install_sent_title, watchName),
                    description = stringResource(Res.string.watch_setup_install_sent_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(2, SETUP_TOTAL_STEPS),
                    announce = true,
                    picture = { WorkingPicture(it, MaterialShapes.Cookie7Sided.toShape(), waiting = true) },
                    bodyContent = {
                        WatchDeviceInfoCard(
                            watchName = watchName,
                            nearby = nearby,
                            statusLine = stringResource(Res.string.watch_setup_device_status_install_sent),
                            onSwitch = switchAction,
                        )
                    },
                ) {
                    StepSecondaryButton(
                        text = stringResource(Res.string.watch_setup_send_install_again),
                        onClick = {
                            val nodeId = status.nodeId
                            if (status is WatchStatus.Unknown && nodeId != null) {
                                onConfirmMissing(nodeId)
                            } else {
                                onInstall()
                            }
                        },
                        quiet = true,
                    )
                }
            }

            WatchSetupVisualStep.REQUEST_FAILED -> {
                val failure = requestFailure ?: return@AnimatedContent
                val watchName =
                    when (status) {
                        is WatchStatus.Unknown -> status.watchName?.takeIf { it.isNotBlank() }
                        is WatchStatus.NotInstalled -> status.watchName.takeIf { it.isNotBlank() }
                        else -> null
                    }
                val nearby =
                    when (status) {
                        is WatchStatus.Unknown -> status.nearby
                        is WatchStatus.NotInstalled -> status.nearby
                        else -> true
                    }
                val stepProgress =
                    if (status is WatchStatus.NotInstalled || (status is WatchStatus.Unknown && status.nodeId != null)) {
                        SetupStepProgress(2, SETUP_TOTAL_STEPS)
                    } else {
                        null
                    }
                WatchFlowScaffold(
                    title = watchRequestFailureTitle(failure, watchName ?: ""),
                    description = stringResource(failure.messageResource()),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = stepProgress,
                    announce = true,
                    picture = {
                        StoppedPicture(
                            side = it,
                            shape = MaterialShapes.SoftBurst.toShape(),
                            tone = WatchSetupTone.PROBLEM,
                            symbol = Icons.Rounded.Watch,
                            badge = Icons.Rounded.PriorityHigh,
                        )
                    },
                    bodyContent =
                        watchName?.let { name ->
                            {
                                WatchDeviceInfoCard(
                                    watchName = name,
                                    nearby = nearby,
                                    statusLine = stringResource(Res.string.watch_setup_device_status_open),
                                    onSwitch = switchAction,
                                )
                            }
                        },
                ) {
                    when {
                        status is WatchStatus.Unknown && !status.knownInstalled && status.nodeId != null -> {
                            WatchSetupAction(stringResource(Res.string.watch_setup_install_action)) {
                                localInstallSent = true
                                onConfirmMissing(status.nodeId)
                            }
                        }

                        status is WatchStatus.Unknown && status.nodeId != null && onOpenOnWatch != null -> {
                            WatchSetupAction(stringResource(Res.string.watch_setup_open_action)) {
                                openSent = true
                                onOpenOnWatch()
                            }
                        }

                        status is WatchStatus.NotInstalled -> {
                            WatchSetupAction(stringResource(Res.string.watch_setup_install_action), onInstall)
                        }

                        else -> {
                            WatchSetupAction(stringResource(Res.string.watch_handoff_retry), onRetry)
                        }
                    }
                    when {
                        onDiagnostics != null -> {
                            StepSecondaryButton(
                                text = stringResource(Res.string.watch_setup_troubleshoot_action),
                                onClick = onDiagnostics,
                                quiet = true,
                            )
                        }
                    }
                }
            }

            WatchSetupVisualStep.LINKING -> {
                val settingUp = status as? WatchStatus.SettingUp ?: return@AnimatedContent
                val linking = settingUp.step as? WatchSetupStep.Linking ?: return@AnimatedContent
                val copy = watchLinkingCopy(linking)
                WatchFlowScaffold(
                    title = copy.title,
                    description = copy.description,
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(3, SETUP_TOTAL_STEPS),
                    announce = true,
                    picture = { LinkingPicture(it, linking.problem) },
                    bodyContent = {
                        WatchDeviceInfoCard(
                            watchName = linking.watchName,
                            nearby = settingUp.nearby,
                            statusLine = stringResource(Res.string.watch_setup_device_status_linking),
                            onSwitch = switchAction,
                        )
                    },
                ) {
                    copy.action?.let { WatchSetupAction(it, onAssociate) }
                }
            }

            WatchSetupVisualStep.COMPLETE -> {
                val ready = status as? WatchStatus.Ready ?: return@AnimatedContent
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_setup_complete_title, ready.watchName),
                    description = stringResource(Res.string.watch_setup_complete_description),
                    onBack = stepBack,
                    modifier = Modifier.fillMaxSize(),
                    progress = SetupStepProgress(3, SETUP_TOTAL_STEPS),
                    announce = true,
                    picture = { ReadyPicture(it) },
                    bodyContent = {
                        WatchDeviceInfoCard(
                            watchName = ready.watchName,
                            nearby = ready.nearby,
                            statusLine = stringResource(Res.string.watch_setup_device_status_connected),
                            confirmed = true,
                        )
                    },
                ) {
                    onCompleteDone?.let { done ->
                        WatchSetupAction(stringResource(Res.string.watch_setup_complete_action), done)
                    }
                }
            }
        }
    }
}

private fun WatchStatus?.availableWatches(): List<DiscoveredWatch> =
    when (this) {
        is WatchStatus.Unknown -> availableWatches
        is WatchStatus.NotInstalled -> availableWatches
        is WatchStatus.SettingUp -> availableWatches
        null, WatchStatus.Unsupported, WatchStatus.NoWatch, is WatchStatus.Ready, is WatchStatus.NeedsAttention -> emptyList()
    }

@Composable
private fun watchRequestFailureTitle(
    failure: WatchRequestFailure,
    watchName: String,
): String {
    val fallbackWatch = watchName.ifBlank { "watch" }
    return when (failure) {
        WatchRequestFailure.LOOKUP_UNAVAILABLE -> {
            stringResource(Res.string.watch_discovery_unavailable)
        }

        WatchRequestFailure.ALREADY_INSTALLED -> {
            stringResource(Res.string.watch_request_failed_installed_title, fallbackWatch)
        }

        WatchRequestFailure.INSTALLATION_UNKNOWN -> {
            stringResource(Res.string.watch_installation_unknown)
        }

        WatchRequestFailure.OPEN_FAILED -> {
            stringResource(Res.string.watch_request_failed_open_title, fallbackWatch)
        }

        WatchRequestFailure.NO_WATCH_CONNECTED -> {
            stringResource(Res.string.watch_request_failed_no_watch_title)
        }

        WatchRequestFailure.NOT_INSTALLED_ON_WATCH -> {
            stringResource(Res.string.watch_request_failed_not_installed_title, fallbackWatch)
        }

        WatchRequestFailure.WATCH_DID_NOT_RESPOND -> {
            stringResource(Res.string.watch_request_failed_no_response_title, fallbackWatch)
        }
    }
}

/** The one thing to do on a setup step, the same size and place on every step. */
@Composable
private fun WatchSetupAction(
    label: String,
    onClick: () -> Unit,
) {
    StepPrimaryButton(text = label, onClick = onClick)
}

/**
 * Turnkey step screen using Material 3's collapsing [LargeTopAppBar] so the step's real title owns
 * the header cleanly instead of stacking a generic bar title above a second headline.
 */
@Composable
internal fun WatchFlowScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    description: String? = null,
    progress: SetupStepProgress? = null,
    announce: Boolean = false,
    picture: (@Composable (Dp) -> Unit)? = null,
    bodyContent: (@Composable ColumnScope.() -> Unit)? = null,
    action: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val largeText = LocalDensity.current.fontScale >= 1.6f
        val short = maxHeight < 480.dp
        val landscape = short && maxWidth >= 600.dp && !largeText
        val compactHeader = landscape || largeText
        val scrollBehavior =
            if (compactHeader) {
                TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
            } else {
                TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
            }
        val sideInset = if (maxWidth > 560.dp) (maxWidth - 560.dp) / 2 else 0.dp
        val titleModifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
        val navigationIcon: @Composable () -> Unit = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(UiRes.string.common_back))
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().applyScreenStyles().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (compactHeader) {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = titleModifier,
                            )
                        },
                        navigationIcon = navigationIcon,
                        modifier = Modifier.padding(horizontal = sideInset),
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    LargeTopAppBar(
                        title = {
                            Text(
                                text = title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = titleModifier,
                            )
                        },
                        navigationIcon = navigationIcon,
                        modifier = Modifier.padding(horizontal = sideInset),
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { insets ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                val availableHeight = this.maxHeight
                if (landscape) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = availableHeight)
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            WatchStepWords(description, progress)
                            bodyContent?.invoke(this)
                        }
                        Column(
                            modifier = Modifier.weight(1f).heightIn(min = (availableHeight - Spacing.md * 2).coerceAtLeast(0.dp)),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            if (picture != null) {
                                WatchSetupPicture(picture, Modifier.fillMaxWidth().weight(1f))
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            WatchSetupActions(action)
                        }
                    }
                } else if (short || largeText) {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = availableHeight)
                                .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        WatchStepWords(description, progress)
                        bodyContent?.invoke(this)
                        Spacer(Modifier.weight(1f))
                        if (picture != null) {
                            WatchSetupPicture(picture, Modifier.fillMaxWidth().height(160.dp))
                            Spacer(Modifier.weight(1f))
                        }
                        WatchSetupActions(action)
                    }
                } else {
                    val panelHeight = if (maxWidth >= 600.dp) minOf(availableHeight, 760.dp) else availableHeight
                    Column(
                        modifier =
                            Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = panelHeight)
                                .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        WatchStepWords(description, progress)
                        if (picture != null) {
                            WatchSetupPicture(picture, Modifier.fillMaxWidth().weight(1f))
                        }
                        bodyContent?.invoke(this)
                        if (picture == null) {
                            Spacer(Modifier.weight(1f))
                        }
                        WatchSetupActions(action)
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchStepWords(
    description: String?,
    progress: SetupStepProgress?,
    modifier: Modifier = Modifier,
) {
    if (progress == null && description == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (progress != null) {
            val reduceMotion by rememberSystemReduceMotion()
            val target = progress.fraction.coerceIn(0f, 1f)
            val animated = if (reduceMotion) target else animateFloatAsState(target, label = "watch_setup_progress").value
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WatchDeviceInfoCard(
    watchName: String,
    nearby: Boolean,
    statusLine: String,
    confirmed: Boolean = false,
    onSwitch: (() -> Unit)? = null,
) {
    val containerColor =
        if (confirmed) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    val contentColor =
        if (confirmed) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val secondaryText =
        if (confirmed) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val availability =
        stringResource(
            if (nearby) Res.string.watch_setup_device_nearby else Res.string.watch_setup_device_out_of_range,
        )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (confirmed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (confirmed) Icons.Rounded.Check else Icons.Rounded.Watch,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = watchName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                WatchPresenceLine(
                    text = "$availability · $statusLine",
                    nearby = nearby,
                    emphasized = confirmed,
                    textColor = secondaryText,
                )
            }
            onSwitch?.let { switch ->
                TextButton(onClick = switch) {
                    Text(stringResource(Res.string.watch_setup_switch_watch))
                }
            }
        }
    }
}

@Composable
private fun WatchChoiceCard(
    watch: DiscoveredWatch,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val secondaryText =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val availability =
        stringResource(
            if (watch.isNearby) Res.string.watch_setup_device_nearby else Res.string.watch_setup_device_out_of_range,
        )
    val installTag =
        stringResource(
            if (watch.appInstalled) Res.string.watch_setup_device_installed_tag else Res.string.watch_setup_device_ready_to_install_tag,
        )
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Watch,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = watch.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                WatchPresenceLine(
                    text = "$availability · $installTag",
                    nearby = watch.isNearby,
                    emphasized = selected,
                    textColor = secondaryText,
                )
            }
            Icon(
                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WatchPresenceLine(
    text: String,
    nearby: Boolean,
    emphasized: Boolean,
    textColor: Color,
) {
    val dotColor =
        when {
            !nearby -> MaterialTheme.colorScheme.outline
            emphasized -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.primary
        }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).background(dotColor, CircleShape))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }
}

/** The picture's room, which tells the picture the largest square it can fill. */
@Composable
private fun WatchSetupPicture(
    picture: @Composable (Dp) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.testTag("watch_setup_art"), contentAlignment = Alignment.Center) {
        picture(minOf(maxWidth, maxHeight).coerceIn(96.dp, 320.dp))
    }
}

/** The action's room, kept as tall as the button on a step that has none so the picture never changes size. */
@Composable
private fun WatchSetupActions(action: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = action,
    )
}

/** One filled shape holding one symbol: the piece every setup picture is built from. */
@Composable
private fun Mark(
    symbol: ImageVector,
    size: Dp,
    shape: Shape,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.size(size), shape = shape, color = container, contentColor = content) {
        Box(contentAlignment = Alignment.Center) {
            Icon(symbol, contentDescription = null, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/** What the watch app is for: the watch, with recording, marking and replaying gathered around it. */
@Composable
internal fun IntroPicture(side: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(side)) {
        Mark(
            Icons.Rounded.Watch,
            side * 0.64f,
            MaterialShapes.Flower.toShape(),
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            Modifier.align(Alignment.Center),
        )
        Mark(
            Icons.Rounded.Mic,
            side * 0.3f,
            MaterialShapes.Cookie9Sided.toShape(),
            colors.primary,
            colors.onPrimary,
            Modifier.align(Alignment.TopStart),
        )
        Mark(
            Icons.Rounded.Bookmark,
            side * 0.26f,
            MaterialShapes.Arch.toShape(),
            colors.secondaryContainer,
            colors.onSecondaryContainer,
            Modifier.align(Alignment.TopEnd).offset(y = side * 0.1f),
        )
        Mark(
            Icons.Rounded.PlayArrow,
            side * 0.3f,
            MaterialShapes.Slanted.toShape(),
            colors.primaryContainer,
            colors.onPrimaryContainer,
            Modifier.align(Alignment.BottomEnd).offset(x = -side * 0.04f),
        )
    }
}

/** The app travelling from the phone to the watch. */
@Composable
private fun InstallPicture(side: Dp) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(side * 0.05f)) {
        Mark(Icons.Rounded.Smartphone, side * 0.24f, CircleShape, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        repeat(3) { Box(Modifier.size(side * 0.035f).background(colors.tertiary, CircleShape)) }
        Mark(
            Icons.Rounded.Download,
            side * 0.44f,
            MaterialShapes.Cookie7Sided.toShape(),
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
        )
    }
}

/** The phone and the watch as two equal halves, joined where they overlap. */
@Composable
private fun ConnectPicture(side: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(width = side, height = side * 0.56f)) {
        Mark(
            Icons.Rounded.Smartphone,
            side * 0.56f,
            MaterialShapes.Square.toShape(),
            colors.primaryContainer,
            colors.onPrimaryContainer,
            Modifier.align(Alignment.CenterStart),
        )
        Mark(
            Icons.Rounded.Watch,
            side * 0.56f,
            MaterialShapes.Clover4Leaf.toShape(),
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            Modifier.align(Alignment.CenterEnd),
        )
        Mark(Icons.Rounded.Link, side * 0.22f, CircleShape, colors.primary, colors.onPrimary, Modifier.align(Alignment.Center))
    }
}

/** The phone's own prompt in outline, with the choice to make marked. */
@Composable
private fun AllowPicture(side: Dp) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(side),
        shape = RoundedCornerShape(side * 0.1f),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurfaceVariant,
    ) {
        Column(Modifier.padding(side * 0.08f), verticalArrangement = Arrangement.spacedBy(side * 0.05f)) {
            Mark(Icons.Rounded.Watch, side * 0.18f, CircleShape, colors.primaryContainer, colors.onPrimaryContainer)
            Box(Modifier.fillMaxWidth(0.8f).height(side * 0.045f).background(colors.outlineVariant, CircleShape))
            Box(Modifier.fillMaxWidth(0.55f).height(side * 0.045f).background(colors.outlineVariant, CircleShape))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(side * 0.04f, Alignment.End)) {
                Box(Modifier.size(width = side * 0.28f, height = side * 0.14f).border(2.dp, colors.outline, CircleShape))
                Surface(
                    modifier = Modifier.size(width = side * 0.28f, height = side * 0.14f),
                    shape = CircleShape,
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.TouchApp, contentDescription = null, modifier = Modifier.size(side * 0.09f))
                    }
                }
            }
        }
    }
}

/** Nothing found yet: the watch at the middle of the rings the phone is searching. The badge moves unless motion is reduced. */
@Composable
internal fun SearchingPicture(side: Dp) {
    val reduceMotion by rememberSystemReduceMotion()
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        Box(Modifier.size(side).border(2.dp, colors.outlineVariant, CircleShape))
        Box(Modifier.size(side * 0.72f).border(2.dp, colors.outlineVariant, CircleShape))
        Mark(Icons.Rounded.Watch, side * 0.44f, CircleShape, colors.secondaryContainer, colors.onSecondaryContainer)
        val badge = Modifier.align(Alignment.TopEnd).offset(x = -side * 0.08f, y = side * 0.08f)
        if (reduceMotion) {
            Mark(Icons.Rounded.Search, side * 0.2f, CircleShape, colors.primary, colors.onPrimary, badge)
        } else {
            Surface(modifier = badge.size(side * 0.2f), shape = CircleShape, color = colors.primary) {
                LoadingIndicator(modifier = Modifier.padding(side * 0.03f), color = colors.onPrimary)
            }
        }
    }
}

/** Linking runs by itself, so its picture has to say whether it is under way, stuck, or stopped. */
@Composable
private fun LinkingPicture(
    side: Dp,
    problem: WatchLinkProblem?,
) {
    when (problem) {
        null -> {
            WorkingPicture(side, MaterialShapes.Cookie9Sided.toShape(), waiting = false)
        }

        WatchLinkProblem.RETRYING -> {
            WorkingPicture(side, MaterialShapes.Sunny.toShape(), waiting = true)
        }

        WatchLinkProblem.ANOTHER_ACCOUNT -> {
            StoppedPicture(
                side,
                MaterialShapes.SoftBurst.toShape(),
                WatchSetupTone.PROBLEM,
                Icons.Rounded.AccountCircle,
                badge = Icons.Rounded.PriorityHigh,
            )
        }

        WatchLinkProblem.DEVICE_LIMIT -> {
            StoppedPicture(
                side,
                MaterialShapes.Cookie6Sided.toShape(),
                WatchSetupTone.PROBLEM,
                Icons.Rounded.Devices,
                badge = Icons.Rounded.PriorityHigh,
            )
        }

        WatchLinkProblem.SET_UP_AGAIN -> {
            StoppedPicture(side, MaterialShapes.Gem.toShape(), WatchSetupTone.PROBLEM, Icons.Rounded.Refresh, badge = Icons.Rounded.Watch)
        }

        WatchLinkProblem.REMOVED -> {
            StoppedPicture(side, MaterialShapes.Bun.toShape(), WatchSetupTone.QUIET, Icons.Rounded.WatchOff)
        }
    }
}

/**
 * The phone and the watch with the work between them. The middle moves unless motion is reduced.
 *
 * @param waiting Whether linking has gone on long enough that the person may need to help it.
 */
@Composable
internal fun WorkingPicture(
    side: Dp,
    shape: Shape,
    waiting: Boolean,
) {
    val reduceMotion by rememberSystemReduceMotion()
    val colors = MaterialTheme.colorScheme
    val container = if (waiting) colors.tertiaryContainer else colors.secondaryContainer
    val content = if (waiting) colors.onTertiaryContainer else colors.onSecondaryContainer
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(side * 0.05f)) {
        Mark(Icons.Rounded.Smartphone, side * 0.22f, CircleShape, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        Surface(modifier = Modifier.size(side * 0.44f), shape = shape, color = container, contentColor = content) {
            Box(contentAlignment = Alignment.Center) {
                if (reduceMotion) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(side * 0.2f))
                } else {
                    LoadingIndicator(modifier = Modifier.size(side * 0.26f), color = content)
                }
            }
        }
        Mark(Icons.Rounded.Watch, side * 0.22f, CircleShape, colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

/** Setup that has stopped: one shape for what stopped it, with a badge for why where there is one. */
@Composable
internal fun StoppedPicture(
    side: Dp,
    shape: Shape,
    tone: WatchSetupTone,
    symbol: ImageVector,
    badge: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    val problem = tone == WatchSetupTone.PROBLEM
    Box(Modifier.size(side * 0.86f)) {
        Mark(
            symbol,
            side * 0.78f,
            shape,
            if (problem) colors.errorContainer else colors.surfaceContainerHighest,
            if (problem) colors.onErrorContainer else colors.onSurfaceVariant,
            Modifier.align(Alignment.TopStart),
        )
        badge?.let {
            Mark(
                it,
                side * 0.28f,
                CircleShape,
                if (problem) colors.error else colors.outline,
                if (problem) colors.onError else colors.surface,
                Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** The phone signalling the watch to open LogDate and finish connecting. */
@Composable
private fun WakeWatchPicture(side: Dp) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(side * 0.05f)) {
        Mark(Icons.Rounded.Smartphone, side * 0.24f, CircleShape, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        repeat(3) { Box(Modifier.size(side * 0.035f).background(colors.primary, CircleShape)) }
        Box(Modifier.size(side * 0.48f)) {
            Mark(
                Icons.Rounded.Watch,
                side * 0.44f,
                MaterialShapes.Clover4Leaf.toShape(),
                colors.tertiaryContainer,
                colors.onTertiaryContainer,
                Modifier.align(Alignment.Center),
            )
            Mark(
                Icons.Rounded.TouchApp,
                side * 0.17f,
                CircleShape,
                colors.primary,
                colors.onPrimary,
                Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** Celebrates a paired, linked watch before entering Watch Settings. */
@Composable
internal fun ReadyPicture(side: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(side * 0.78f)) {
        Mark(
            Icons.Rounded.Watch,
            side * 0.7f,
            MaterialShapes.Flower.toShape(),
            colors.primaryContainer,
            colors.onPrimaryContainer,
            Modifier.align(Alignment.TopStart),
        )
        Mark(
            Icons.Rounded.Check,
            side * 0.26f,
            CircleShape,
            colors.primary,
            colors.onPrimary,
            Modifier.align(Alignment.BottomEnd),
        )
    }
}
