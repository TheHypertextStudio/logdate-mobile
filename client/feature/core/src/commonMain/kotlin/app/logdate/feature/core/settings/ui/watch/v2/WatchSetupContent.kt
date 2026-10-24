@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailureMessage
import app.logdate.feature.core.settings.ui.watch.WatchSetupStep
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.feature.core.settings.ui.watch.WatchSystemSetupButton
import app.logdate.ui.theme.Spacing
import app.logdate.ui.watch.WatchSetupProvider
import app.logdate.ui.watch.rememberWatchSetupLauncher
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_association_pending
import logdate.client.feature.core.generated.resources.watch_association_pending_description
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
import logdate.client.feature.core.generated.resources.watch_unsupported
import org.jetbrains.compose.resources.stringResource

/** First use has one next action and no controls for a watch LogDate cannot yet reach. */
@Composable
internal fun WatchSetupContent(
    status: WatchStatus?,
    requestFailure: WatchRequestFailure?,
    installRequestSent: Boolean,
    onAssociate: () -> Unit,
    onInstall: () -> Unit,
    setupLauncher: ((WatchSetupProvider) -> Boolean)?,
    modifier: Modifier = Modifier,
) {
    when (status) {
        null -> {
            WatchSetupHero(stringResource(Res.string.watch_loading), null, Icons.Default.Watch, modifier) {
                CircularProgressIndicator()
            }
        }

        WatchStatus.Unsupported -> {
            WatchSetupHero(stringResource(Res.string.watch_unsupported), null, Icons.Default.Watch, modifier) {}
        }

        WatchStatus.NoWatch -> {
            WatchSetupHero(
                stringResource(Res.string.watch_setup_pair_title),
                stringResource(Res.string.watch_setup_pair_description),
                Icons.Default.Mic,
                modifier,
            ) {
                WatchSystemSetupButton(
                    launcher = setupLauncher ?: rememberWatchSetupLauncher(),
                    label = stringResource(Res.string.watch_setup_start_action),
                )
            }
        }

        is WatchStatus.SettingUp -> {
            when (val step = status.step) {
                is WatchSetupStep.PairWithPhone -> {
                    WatchSetupHero(
                        stringResource(Res.string.watch_setup_associate_title, step.watchName),
                        stringResource(Res.string.watch_setup_associate_description),
                        Icons.Default.Watch,
                        modifier,
                    ) { Button(onClick = onAssociate) { Text(stringResource(Res.string.watch_setup_associate_action)) } }
                }

                WatchSetupStep.ConfirmOnPhone -> {
                    WatchSetupHero(
                        stringResource(Res.string.watch_association_pending),
                        stringResource(Res.string.watch_association_pending_description),
                        Icons.Default.Watch,
                        modifier,
                    ) { CircularProgressIndicator() }
                }
            }
        }

        is WatchStatus.NotInstalled -> {
            WatchSetupHero(
                stringResource(Res.string.watch_setup_install_title, status.watchName),
                stringResource(Res.string.watch_setup_install_description),
                Icons.Default.Download,
                modifier,
            ) {
                Button(onClick = onInstall) { Text(stringResource(Res.string.watch_setup_install_action)) }
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

        is WatchStatus.Ready, is WatchStatus.NeedsAttention -> {
            Unit
        }
    }
}

/** Each setup step uses the page itself, with one action anchored below the illustration. */
@Composable
private fun WatchSetupHero(
    title: String,
    description: String?,
    symbol: ImageVector,
    modifier: Modifier,
    action: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val largeText = LocalDensity.current.fontScale >= 1.6f
        val wide = maxWidth >= 700.dp && !largeText
        val sidePadding = if (maxWidth > 1100.dp) (maxWidth - 1040.dp) / 2 else Spacing.xl
        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = sidePadding, vertical = Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                WatchSetupCopy(title, description, action, Modifier.weight(1f))
                WatchSetupArtwork(symbol, Modifier.weight(1f).fillMaxHeight(), large = true)
            }
        } else if (maxHeight >= 620.dp && !largeText) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                WatchSetupArtwork(symbol, Modifier.fillMaxWidth().weight(1f), large = false)
                WatchSetupCopy(title, description, action)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                WatchSetupArtwork(symbol, Modifier.fillMaxWidth().height(200.dp), large = false)
                WatchSetupCopy(title, description, action)
            }
        }
    }
}

@Composable
private fun WatchSetupCopy(
    title: String,
    description: String?,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        action()
    }
}

@Composable
private fun WatchSetupArtwork(
    symbol: ImageVector,
    modifier: Modifier,
    large: Boolean,
) {
    BoxWithConstraints(modifier.testTag("watch_setup_art"), contentAlignment = Alignment.Center) {
        val halo = (minOf(maxWidth, maxHeight) * 0.78f).coerceIn(150.dp, if (large) 400.dp else 300.dp)
        val strapWidth = halo * 0.28f
        val strapHeight = halo * 1.13f
        val face = halo * 0.62f
        Surface(modifier = Modifier.size(halo), shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {}
        Surface(
            modifier = Modifier.width(strapWidth).height(strapHeight),
            shape = RoundedCornerShape(40.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
        }
        Surface(
            modifier = Modifier.size(face),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(8.dp, MaterialTheme.colorScheme.primary),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    symbol,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier.size(
                            face * 0.3f,
                        ),
                )
            }
        }
    }
}
