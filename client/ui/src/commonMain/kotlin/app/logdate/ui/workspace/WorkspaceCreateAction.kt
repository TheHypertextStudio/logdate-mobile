@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.workspace

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import app.logdate.ui.creation.EntryCreationShape
import app.logdate.ui.creation.EntryCreationSource
import app.logdate.ui.creation.LocalEntryCreationLauncher
import app.logdate.ui.creation.entryCreationBounds
import app.logdate.ui.platform.PlatformIcons

@Composable
internal fun WorkspaceCreateAction(
    onClick: () -> Unit,
    label: String,
    createEntryTransition: Boolean,
    source: EntryCreationSource,
    extended: Boolean? = null,
    active: Boolean = true,
) {
    val launcher = LocalEntryCreationLauncher.current
    val sourceId = source.id
    val enabled = active && (!createEntryTransition || source.canLaunch)
    val shape = if (extended == null) EntryCreationShape.Round else EntryCreationShape.Large
    val modifier =
        (if (createEntryTransition && active) Modifier.entryCreationBounds(sourceId, sourceShape = shape) else Modifier)
            .then(if (!enabled) Modifier.semantics { disabled() } else Modifier)
    val click: () -> Unit = {
        when {
            !enabled -> {}

            createEntryTransition -> {
                source.launch(sourceId, launcher, shape, onClick)
            }

            else -> {
                onClick()
            }
        }
    }
    if (extended != null) {
        ExtendedFloatingActionButton(
            text = { Text(label, Modifier.clearAndSetSemantics {}) },
            icon = { Icon(PlatformIcons.newEntry(), label) },
            onClick = click,
            expanded = extended,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        FilledTonalIconButton(onClick = click, enabled = enabled, modifier = modifier) { Icon(PlatformIcons.newEntry(), label) }
    }
}
