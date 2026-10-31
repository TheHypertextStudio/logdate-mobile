@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.journal_detail_back_to_journals
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun JournalReadingHeader(
    title: String,
    onGoBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val backDescription = stringResource(Res.string.journal_detail_back_to_journals)
    BoxWithConstraints(Modifier.padding(Spacing.lg)) {
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
        val navigation: @Composable () -> Unit = {
            IconButton(onClick = onGoBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, backDescription) }
        }
        val identity: @Composable (Modifier) -> Unit = { modifier ->
            Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row {
                    navigation()
                    Spacer(Modifier.weight(1f))
                    actions()
                }
                identity(Modifier)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                navigation()
                identity(Modifier.weight(1f))
                actions()
            }
        }
    }
}
