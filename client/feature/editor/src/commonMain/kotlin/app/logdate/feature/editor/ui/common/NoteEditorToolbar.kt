package app.logdate.feature.editor.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.common.IconTooltip
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.delay
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.changes_saved
import logdate.client.feature.editor.generated.resources.error_saving
import logdate.client.feature.editor.generated.resources.load_drafts
import logdate.client.feature.editor.generated.resources.manage_drafts
import logdate.client.feature.editor.generated.resources.more_options
import logdate.client.feature.editor.generated.resources.save_entry
import logdate.client.feature.editor.generated.resources.saving
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Top toolbar for the note editor.
 * Provides navigation, saving functionality, and access to additional options.
 *
 * @param onBack Callback when the user navigates back
 * @param onSave Callback when the user saves the entry
 * @param onShowDrafts Callback when the user wants to view drafts
 * @param modifier Modifier for the toolbar
 * @param autoSaveStatus The current auto-save status (optional)
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun NoteEditorToolbar(
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShowDrafts: () -> Unit,
    modifier: Modifier = Modifier,
    draftCount: Int = 0,
    autoSaveStatus: AutoSaveStatus? = null,
    actionsVisible: Boolean = true,
    actionsEnabled: Boolean = true,
    optionsVisible: Boolean = true,
    saveEnabled: Boolean = true,
    saveLabel: String? = null,
) {
    val saveDescription = saveLabel ?: stringResource(Res.string.save_entry)
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(optionsVisible, actionsVisible) {
        if (!optionsVisible || !actionsVisible) showMenu = false
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left-aligned back button — always visible
        IconTooltip(label = stringResource(UiRes.string.common_back)) {
            FilledTonalIconButton(
                onClick = { onBack() },
                enabled = actionsEnabled,
            ) {
                Icon(
                    painter = PlatformIcons.back(),
                    contentDescription = stringResource(UiRes.string.common_back),
                )
            }
        }

        AnimatedVisibility(
            visible = actionsVisible,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
        ) {
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(
                        Spacing.xs,
                        alignment = Alignment.End,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (autoSaveStatus != null) {
                    AutoSaveIndicator(
                        status = autoSaveStatus,
                        modifier = Modifier.padding(end = Spacing.xs),
                    )
                }

                if (optionsVisible && draftCount > 0) {
                    IconTooltip(label = stringResource(Res.string.load_drafts)) {
                        FilledTonalIconButton(
                            onClick = { onShowDrafts() },
                            enabled = actionsEnabled,
                            modifier = Modifier.testTag(LOGDATE_EDITOR_DRAFTS_BUTTON_TAG),
                        ) {
                            BadgedBox(
                                badge = {
                                    Badge { Text(draftCount.toString()) }
                                },
                            ) {
                                Icon(
                                    painter = PlatformIcons.drafts(),
                                    contentDescription = stringResource(Res.string.load_drafts),
                                )
                            }
                        }
                    }
                }

                IconTooltip(label = saveDescription) {
                    FilledTonalIconButton(
                        onClick = { onSave() },
                        enabled = actionsEnabled && saveEnabled,
                        modifier = Modifier.testTag(LOGDATE_EDITOR_SAVE_BUTTON_TAG),
                    ) {
                        Icon(
                            painter = PlatformIcons.save(),
                            contentDescription = saveDescription,
                        )
                    }
                }

                if (optionsVisible) {
                    IconTooltip(label = stringResource(Res.string.more_options)) {
                        FilledTonalIconButton(
                            onClick = { showMenu = true },
                            enabled = actionsEnabled,
                        ) {
                            Icon(
                                painter = PlatformIcons.more(),
                                contentDescription = stringResource(Res.string.more_options),
                            )

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.manage_drafts)) },
                                    enabled = actionsEnabled,
                                    onClick = {
                                        showMenu = false
                                        onShowDrafts()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

const val LOGDATE_EDITOR_SAVE_BUTTON_TAG = "editor_save_button"
const val LOGDATE_EDITOR_DRAFTS_BUTTON_TAG = "editor_drafts_button"

/**
 * A subtle indicator for displaying auto-save status.
 * Shows different UI elements based on the current save status.
 * The success indicator automatically disappears after a short delay.
 *
 * @param status The current auto-save status
 * @param modifier Modifier for the indicator
 * @param showDuration How long to show the success indicator before fading (in ms)
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AutoSaveIndicator(
    status: AutoSaveStatus,
    modifier: Modifier = Modifier,
    showDuration: Long = 1500,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Show different indicators based on status
        when (status) {
            AutoSaveStatus.SAVED -> {
                // Create a temporary visibility state that disappears after a delay
                var visibleState by remember { mutableStateOf(true) }

                // Effect to hide the icon after a brief period
                LaunchedEffect(status) {
                    delay(showDuration) // Show for the specified duration
                    visibleState = false
                }

                // Show a subtle green checkmark that fades in and out
                AnimatedVisibility(
                    visible = visibleState,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(500)),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(Res.string.changes_saved),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            AutoSaveStatus.ERROR -> {
                // Show error indicator with a temporary visibility state
                var visibleState by remember { mutableStateOf(true) }

                // Effect to hide the error icon after a delay
                LaunchedEffect(status) {
                    delay(showDuration * 2) // Show errors longer
                    visibleState = false
                }

                AnimatedVisibility(
                    visible = visibleState,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(500)),
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = stringResource(Res.string.error_saving),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            AutoSaveStatus.SAVING -> {
                // Very subtle "Saving..." text
                Text(
                    text = stringResource(Res.string.saving),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }

            else -> {
                // Don't show anything for IDLE state
            }
        }
    }
}
