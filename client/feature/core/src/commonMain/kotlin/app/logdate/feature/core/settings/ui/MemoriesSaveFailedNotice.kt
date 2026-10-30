@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.memories_settings_save_failed
import org.jetbrains.compose.resources.stringResource

/** Tells the person their last memories setting change did not save, so the control shows its stored value. */
@Composable
internal fun MemoriesSaveFailedNotice(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.memories_settings_save_failed),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
