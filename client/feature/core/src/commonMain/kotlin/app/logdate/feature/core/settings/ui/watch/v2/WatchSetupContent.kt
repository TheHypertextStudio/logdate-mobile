@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailureMessage
import app.logdate.feature.core.settings.ui.watch.WatchSetupStep
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.feature.core.settings.ui.watch.WatchSystemSetupButton
import app.logdate.feature.core.settings.ui.watch.WatchUnknownActions
import app.logdate.feature.core.settings.ui.watch.watchLinkingCopy
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.theme.Spacing
import app.logdate.ui.watch.WatchSetupProvider
import app.logdate.ui.watch.rememberWatchSetupLauncher
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_association_pending
import logdate.client.feature.core.generated.resources.watch_association_pending_description
import logdate.client.feature.core.generated.resources.watch_check_on_watch
import logdate.client.feature.core.generated.resources.watch_discovery_unavailable
import logdate.client.feature.core.generated.resources.watch_installation_unknown
import logdate.client.feature.core.generated.resources.watch_loading
import logdate.client.feature.core.generated.resources.watch_setup_associate_action
import logdate.client.feature.core.generated.resources.watch_setup_associate_description
import logdate.client.feature.core.generated.resources.watch_setup_associate_title
import logdate.client.feature.core.generated.resources.watch_setup_install_action
import logdate.client.feature.core.generated.resources.watch_setup_install_description
import logdate.client.feature.core.generated.resources.watch_setup_install_title
import logdate.client.feature.core.generated.resources.watch_setup_pair_description
import logdate.client.feature.core.generated.resources.watch_setup_pair_title
import logdate.client.feature.core.generated.resources.watch_setup_sent
import logdate.client.feature.core.generated.resources.watch_setup_start_action
import logdate.client.feature.core.generated.resources.watch_unknown_description
import logdate.client.feature.core.generated.resources.watch_unknown_title
import logdate.client.feature.core.generated.resources.watch_unsupported
import org.jetbrains.compose.resources.stringResource

/** The colour family a problem or quiet step is drawn in, so its kind reads before any text does. */
private enum class WatchSetupTone { PROBLEM, QUIET }

/**
 * First use has one next action and no controls for a watch LogDate cannot yet reach.
 *
 * Every step keeps the same three regions for a window size: words first, a picture in the room that is left,
 * and the action last. Only the picture changes from step to step.
 */
@Composable
internal fun WatchSetupContent(
    status: WatchStatus?,
    requestFailure: WatchRequestFailure?,
    installRequestSent: Boolean,
    onAssociate: () -> Unit,
    onInstall: () -> Unit,
    setupLauncher: ((WatchSetupProvider) -> Boolean)?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onConfirmMissing: (String) -> Unit = {},
    onOpenOnWatch: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
) {
    when (status) {
        null -> {
            WatchSetupStepLayout(
                title = stringResource(Res.string.watch_loading),
                description = null,
                modifier = modifier,
                picture = { SearchingPicture(it) },
            ) {}
        }

        WatchStatus.Unsupported -> {
            WatchSetupStepLayout(
                title = stringResource(Res.string.watch_unsupported),
                description = null,
                modifier = modifier,
                picture = { StoppedPicture(it, MaterialShapes.Slanted.toShape(), WatchSetupTone.QUIET, Icons.Rounded.WatchOff) },
            ) {}
        }

        is WatchStatus.Unknown -> {
            WatchSetupStepLayout(
                title =
                    status.watchName?.let { stringResource(Res.string.watch_unknown_title, it) }
                        ?: stringResource(Res.string.watch_discovery_unavailable),
                description = stringResource(Res.string.watch_unknown_description),
                modifier = modifier,
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
                WatchUnknownActions(
                    status = status,
                    onRetry = onRetry,
                    onConfirmMissing = onConfirmMissing,
                    prominent = true,
                    onOpenOnWatch = onOpenOnWatch,
                    onDiagnostics = onDiagnostics,
                )
                requestFailure?.let { WatchRequestFailureMessage(it) }
            }
        }

        WatchStatus.NoWatch -> {
            WatchSetupStepLayout(
                title = stringResource(Res.string.watch_setup_pair_title),
                description = stringResource(Res.string.watch_setup_pair_description),
                modifier = modifier,
                picture = { IntroPicture(it) },
            ) {
                WatchSystemSetupButton(
                    launcher = setupLauncher ?: rememberWatchSetupLauncher(),
                    label = stringResource(Res.string.watch_setup_start_action),
                    prominent = true,
                )
            }
        }

        is WatchStatus.SettingUp -> {
            when (val step = status.step) {
                is WatchSetupStep.PairWithPhone -> {
                    WatchSetupStepLayout(
                        title = stringResource(Res.string.watch_setup_associate_title, step.watchName),
                        description = stringResource(Res.string.watch_setup_associate_description),
                        modifier = modifier,
                        picture = { ConnectPicture(it) },
                    ) { WatchSetupAction(stringResource(Res.string.watch_setup_associate_action), onAssociate) }
                }

                WatchSetupStep.ConfirmOnPhone -> {
                    WatchSetupStepLayout(
                        title = stringResource(Res.string.watch_association_pending),
                        description = stringResource(Res.string.watch_association_pending_description),
                        modifier = modifier,
                        announce = true,
                        picture = { AllowPicture(it) },
                    ) {}
                }

                is WatchSetupStep.Linking -> {
                    val copy = watchLinkingCopy(step)
                    WatchSetupStepLayout(
                        title = copy.title,
                        description = copy.description,
                        modifier = modifier,
                        announce = true,
                        picture = { LinkingPicture(it, step.problem) },
                    ) {
                        copy.action?.let { WatchSetupAction(it, onAssociate) }
                    }
                }
            }
        }

        is WatchStatus.NotInstalled -> {
            WatchSetupStepLayout(
                title = stringResource(Res.string.watch_setup_install_title, status.watchName),
                description = stringResource(Res.string.watch_setup_install_description),
                modifier = modifier,
                picture = { InstallPicture(it) },
            ) {
                WatchSetupAction(stringResource(Res.string.watch_setup_install_action), onInstall)
                if (installRequestSent) {
                    Text(
                        stringResource(Res.string.watch_setup_sent),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                requestFailure?.let { WatchRequestFailureMessage(it) }
            }
        }

        is WatchStatus.Ready, is WatchStatus.NeedsAttention -> {}
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
 * One setup step. The words start at the top and the action ends at the bottom on every step, so a step that
 * replaces another moves neither; a step with nothing to do keeps a button's height empty. A step whose words
 * and actions outgrow the window scrolls.
 *
 * @param announce Whether a screen reader is told when this step replaces another without the person acting.
 * @param picture Draws the step's picture to fit the side length it is given.
 */
@Composable
private fun WatchSetupStepLayout(
    title: String,
    description: String?,
    modifier: Modifier,
    picture: @Composable (Dp) -> Unit,
    announce: Boolean = false,
    action: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val largeText = LocalDensity.current.fontScale >= 1.6f
        val short = maxHeight < 480.dp
        if (short && maxWidth >= 600.dp && !largeText) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                WatchSetupWords(title, description, announce, Modifier.weight(1f).verticalScroll(rememberScrollState()))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    WatchSetupPicture(picture, Modifier.fillMaxWidth().weight(1f))
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
                        .heightIn(min = maxHeight)
                        .padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                WatchSetupWords(title, description, announce)
                Spacer(Modifier.weight(1f))
                WatchSetupPicture(picture, Modifier.fillMaxWidth().height(180.dp))
                Spacer(Modifier.weight(1f))
                WatchSetupActions(action)
            }
        } else {
            val panelHeight = if (maxWidth >= 600.dp) minOf(maxHeight, 760.dp) else maxHeight
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .height(panelHeight)
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = panelHeight)
                        .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                WatchSetupWords(title, description, announce)
                WatchSetupPicture(picture, Modifier.fillMaxWidth().weight(1f))
                WatchSetupActions(action)
            }
        }
    }
}

@Composable
private fun WatchSetupWords(
    title: String,
    description: String?,
    announce: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
        )
        description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
private fun IntroPicture(side: Dp) {
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
private fun SearchingPicture(side: Dp) {
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
private fun WorkingPicture(
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
private fun StoppedPicture(
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
